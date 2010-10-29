/* -*- compile-command: "cd ../../../../../; ant install"; -*- */
/*
 * Copyright 2009-2010 by Eric House (xwords@eehouse.org).  All
 * rights reserved.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

package org.eehouse.android.xw4;

import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.ByteBuffer;
import java.net.InetSocketAddress;
import java.util.Vector;
import java.util.Iterator;
import junit.framework.Assert;
import android.telephony.SmsManager;
import android.content.Intent;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Handler;
import android.os.Message;

import org.eehouse.android.xw4.jni.*;
import org.eehouse.android.xw4.jni.JNIThread.*;
import org.eehouse.android.xw4.jni.CurGameInfo.DeviceRole;

public class CommsTransport extends Thread implements TransportProcs {

    public static final int DIALOG = 0;
    public static final int DIALOG_RETRY = 1;
    public static final int TOAST = 2;
    public static final int RELAY_COND = 3;

    public static final int RELAY_CONNND_ALLHERE = 0;
    public static final int RELAY_CONNND_MISSING = 1;

    private Selector m_selector;
    private SocketChannel m_socketChannel;
    private int m_jniGamePtr;
    private boolean m_running = false;
    private CommsAddrRec m_addr;
    private JNIThread m_jniThread;
    private Handler m_handler;
    private boolean m_done = false;

    private Vector<ByteBuffer> m_buffersOut;
    private ByteBuffer m_bytesOut;
    private ByteBuffer m_bytesIn;

    private Context m_context;

    // assembling inbound packet
    private byte[] m_packetIn;
    private int m_haveLen = -1;

    public CommsTransport( int jniGamePtr, Context context, Handler handler,
                           DeviceRole role )
    {
        m_jniGamePtr = jniGamePtr;
        m_context = context;
        m_handler = handler;
        m_buffersOut = new Vector<ByteBuffer>();
        m_bytesIn = ByteBuffer.allocate( 2048 );
    }
    
    public void setReceiver( JNIThread jnit )
    {
        m_jniThread = jnit;
    }

    public void waitToStop()
    {
        m_done = true;
        if ( null != m_selector ) {
            m_selector.wakeup();
        }
        if ( m_running ) {      // synchronized this?  Or use Thread method
            try {
                join(100);          // wait up to 1/10 second
            } catch ( java.lang.InterruptedException ie ) {
                Utils.logf( "got InterruptedException: " + ie.toString() );
            }
        }
    }

    @Override
    public void run() 
    {
        try {
            m_selector = Selector.open();

            loop();

            closeSocket();
        } catch ( java.io.IOException ioe ) {
            Utils.logf( ioe.toString() );
        } catch ( UnresolvedAddressException uae ) {
            Utils.logf( "bad address: name: %s; port: %s; exception: %s",
                        m_addr.ip_relay_hostName, m_addr.ip_relay_port, 
                        uae.toString() );
        }
    }

    private void loop()
    {
        while ( !m_done ) {
            try {
                synchronized( this ) {

                    // if we have data and no socket, try to connect.
                    if ( null == m_socketChannel
                         && 0 < m_buffersOut.size() ) {
                        try {
                            m_socketChannel = SocketChannel.open();
                            m_socketChannel.configureBlocking( false );
                            InetSocketAddress isa
                                = new InetSocketAddress( m_addr.ip_relay_hostName, 
                                                         m_addr.ip_relay_port );
                            m_socketChannel.connect( isa );
                        } catch ( java.io.IOException ioe ) {
                            Utils.logf( ioe.toString() );
                        }
                    }

                    if ( null != m_socketChannel ) {
                        int ops = figureOps();
                        // Utils.logf( "calling with ops=%x", ops );
                        m_socketChannel.register( m_selector, ops );
                    }
                }
                m_selector.select();
            } catch ( ClosedChannelException cce ) {
                // we get this when relay goes down.  Need to notify!
                m_jniThread.handle( JNICmd.CMD_TRANSFAIL );
                closeSocket();
                Utils.logf( "exiting: " + cce.toString() );
            } catch ( java.io.IOException ioe ) {
                closeSocket();
                Utils.logf( "exiting: " + ioe.toString() );
                Utils.logf( ioe.toString() );
            }

            Iterator<SelectionKey> iter = m_selector.selectedKeys().iterator();
            while ( iter.hasNext() ) {
                SelectionKey key = (SelectionKey)iter.next();
                SocketChannel channel = (SocketChannel)key.channel();
                iter.remove();
                try { 
                    if (key.isValid() && key.isConnectable()) {
                        if ( !channel.finishConnect() ) {
                            key.cancel(); 
                        }
                    }
                    if (key.isValid() && key.isReadable()) {
                        m_bytesIn.clear(); // will wipe any pending data!
                        // Utils.logf( "socket is readable; buffer has space for "
                        //             + m_bytesIn.remaining() );
                        int nRead = channel.read( m_bytesIn );
                        if ( nRead == -1 ) {
                            channel.close();
                        } else {
                            addIncoming();
                        }
                    }
                    if (key.isValid() && key.isWritable()) {
                        getOut();
                        if ( null != m_bytesOut ) {
                            int nWritten = channel.write( m_bytesOut );
                            //Utils.logf( "wrote " + nWritten + " bytes" );
                        }
                    }
                } catch ( java.io.IOException ioe ) {
                    key.cancel(); 
                }
            }
        }
    } // loop

    private synchronized void putOut( final byte[] buf )
    {
        int len = buf.length;
        ByteBuffer netbuf = ByteBuffer.allocate( len + 2 );
        netbuf.putShort( (short)len );
        netbuf.put( buf );
        m_buffersOut.add( netbuf );
        Assert.assertEquals( netbuf.remaining(), 0 );

        if ( null != m_selector ) {
            m_selector.wakeup();    // tell it it's got some writing to do
        }
    }

    private synchronized void closeSocket()
    {
        if ( null != m_socketChannel ) {
            try {
                m_socketChannel.close();
            } catch ( Exception e ) {
                Utils.logf( "closing socket: %s", e.toString() );
            }
            m_socketChannel = null;
        }
    }

    private synchronized void getOut()
    {
        if ( null != m_bytesOut && m_bytesOut.remaining() == 0 ) {
            m_bytesOut = null;
        }

        if ( null == m_bytesOut && m_buffersOut.size() > 0 ) {
            m_bytesOut = m_buffersOut.remove(0);
            m_bytesOut.flip();
        }
    }

    private synchronized int figureOps() {
        int ops;
        if ( null == m_socketChannel ) {
            ops = 0;
        } else if ( m_socketChannel.isConnected() ) {
            ops = SelectionKey.OP_READ;
            if ( (null != m_bytesOut && m_bytesOut.hasRemaining())
                 || m_buffersOut.size() > 0 ) {
                ops |= SelectionKey.OP_WRITE;
            }
        } else {
            ops = SelectionKey.OP_CONNECT;
        }
        return ops;
    }

    private void addIncoming( )
    {
        m_bytesIn.flip();
        
        for ( ; ; ) {
            int len = m_bytesIn.remaining();
            if ( len <= 0 ) {
                break;
            }

            if ( null == m_packetIn ) { // we're not mid-packet
                Assert.assertTrue( len > 1 ); // tell me if I see this case
                if ( len == 1 ) {       // half a length byte...
                    break;              // can I leave it in the buffer?
                } else {                
                    m_packetIn = new byte[m_bytesIn.getShort()];
                    m_haveLen = 0;
                }
            } else {                    // we're mid-packet
                int wantLen = m_packetIn.length - m_haveLen;
                if ( wantLen > len ) {
                    wantLen = len;
                }
                m_bytesIn.get( m_packetIn, m_haveLen, wantLen );
                m_haveLen += wantLen;
                if ( m_haveLen == m_packetIn.length ) {
                    // send completed packet
                    m_jniThread.handle( JNICmd.CMD_RECEIVE, m_packetIn );
                    m_packetIn = null;
                }
            }
        }
    }

    // TransportProcs interface
    public int transportSend( byte[] buf, final CommsAddrRec faddr )
    {
        int nSent = -1;

        if ( null == m_addr ) {
            if ( null == faddr ) {
                m_addr = new CommsAddrRec( m_context );
                XwJNI.comms_getAddr( m_jniGamePtr, m_addr );
            } else {
                m_addr = new CommsAddrRec( faddr );
            }
        }

        switch ( m_addr.conType ) {
        case COMMS_CONN_RELAY:
            putOut( buf );      // add to queue
            if ( !m_running ) {
                m_running = true;
                start();
            }
            nSent = buf.length;
            break;
        case COMMS_CONN_SMS:
            Assert.fail();
            // This code can't be here, even if unreachable, unless
            // app has permission to use SMS.  So put it in a separate
            // module and catch the error that'll come when it fails
            // to verify.  IFF the plan's to ship a version that
            // doesn't do SMS.

            // Utils.logf( "sending via sms to  %s:%d", 
            //             m_addr.sms_phone, m_addr.sms_port );
            // try {
            //     Intent intent = new Intent( m_context, StatusReceiver.class);
            //     PendingIntent pi
            //         = PendingIntent.getBroadcast( m_context, 0,
            //                                       intent, 0 );
            //     if ( 0 == m_addr.sms_port ) {
            //          SmsManager.getDefault().sendTextMessage( m_addr.sms_phone,
            //                                                   null, "Hello world",
            //                                                   pi, pi );
            //         Utils.logf( "called sendTextMessage" );
            //     } else {
            //         SmsManager.getDefault().
            //             sendDataMessage( m_addr.sms_phone, (String)null,
            //                              (short)m_addr.sms_port, 
            //                              buf, pi, pi );
            //         Utils.logf( "called sendDataMessage" );
            //     }
            //     nSent = buf.length;
            // } catch ( java.lang.IllegalArgumentException iae ) {
            //     Utils.logf( iae.toString() );
            // }
            break;
        case COMMS_CONN_BT:
            break;
        }

        return nSent;
    } 

    public void relayStatus( CommsRelayState newState )
    {
        //Utils.logf( "relayStatus called; state=%s", newState.toString() );
        if ( null != m_jniThread ) {
            m_jniThread.handle( JNICmd.CMD_DRAW_CONNS_STATUS, newState );
        } else {
            Utils.logf( "can't draw status yet" );
        }
    }

    public void relayConnd( String room, boolean allHere, int nMissing )
    {
        String message = null;
        if ( allHere ) {
            Message.obtain( m_handler, RELAY_COND, RELAY_CONNND_ALLHERE, 
                            -1/*ignored*/, room ).sendToTarget();
        } else if ( nMissing > 0 ) {
            Message.obtain( m_handler, RELAY_COND, RELAY_CONNND_MISSING, 
                            nMissing, room ).sendToTarget();
        }
    }

    public void relayErrorProc( XWRELAY_ERROR relayErr )
    {
        //Utils.logf( "relayErrorProc called; got " + relayErr.toString() );

        int strID = 0;
        int how = TOAST;

        switch ( relayErr ) {
        case TOO_MANY: 
            strID = R.string.msg_too_many;
            how = DIALOG;
            break;
        case NO_ROOM:
            strID = R.string.msg_no_room;
            how = DIALOG_RETRY;
            break;
        case DUP_ROOM:
            strID = R.string.msg_dup_room;
            how = DIALOG;
            break;
        case LOST_OTHER:
        case OTHER_DISCON:
            strID = R.string.msg_lost_other;
            break;

        case OLDFLAGS:
        case BADPROTO:
        case RELAYBUSY:
        case SHUTDOWN:
        case TIMEOUT:
        case HEART_YOU:
        case HEART_OTHER:
            break;
        }

        if ( 0 != strID ) {
            String str = m_context.getString( strID );
            Message.obtain( m_handler, how, R.string.relay_alert, 
                            0, str ).sendToTarget();
        }
    }
}
