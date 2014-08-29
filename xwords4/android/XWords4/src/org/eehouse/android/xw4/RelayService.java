/* -*- compile-command: "find-and-ant.sh debug install"; -*- */
/*
 * Copyright 2010 - 2012 by Eric House (xwords@eehouse.org).  All
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

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.concurrent.LinkedBlockingQueue;

import junit.framework.Assert;

import org.eehouse.android.xw4.MultiService.MultiEvent;
import org.eehouse.android.xw4.jni.CommsAddrRec;
import org.eehouse.android.xw4.jni.GameSummary;
import org.eehouse.android.xw4.jni.UtilCtxt;
import org.eehouse.android.xw4.jni.UtilCtxt.DevIDType;
import org.eehouse.android.xw4.jni.XwJNI;
import org.eehouse.android.xw4.jni.LastMoveInfo;
import org.eehouse.android.xw4.loc.LocUtils;

public class RelayService extends XWService 
    implements NetStateCache.StateChangedIf {
    private static final int MAX_SEND = 1024;
    private static final int MAX_BUF = MAX_SEND - 2;
    private static final int REG_WAIT_INTERVAL = 10;

    // One week, in seconds.  Probably should be configurable.
    private static final long MAX_KEEPALIVE_SECS = 7 * 24 * 60 * 60;

    private static final String CMD_STR = "CMD";

    private static enum MsgCmds { INVALID
            , PROCESS_GAME_MSGS
            , PROCESS_DEV_MSGS
            , UDP_CHANGED
            , SEND
            , SENDNOCONN
            , RECEIVE
            , TIMER_FIRED
            , RESET
            , UPGRADE
    }

    private static final String MSGS_ARR = "MSGS_ARR";
    private static final String RELAY_ID = "RELAY_ID";
    private static final String ROWID = "ROWID";
    private static final String BINBUFFER = "BINBUFFER";

    private static HashSet<Integer> s_packetsSent = new HashSet<Integer>();
    private static int s_nextPacketID = 1;
    private static boolean s_gcmWorking = false;
    private static boolean s_registered = false;

    private Thread m_fetchThread = null;
    private Thread m_UDPReadThread = null;
    private Thread m_UDPWriteThread = null;
    private DatagramSocket m_UDPSocket;
    private LinkedBlockingQueue<PacketData> m_queue = 
        new LinkedBlockingQueue<PacketData>();
    private Handler m_handler;
    private Runnable m_onInactivity;
    private int m_maxIntervalSeconds = 0;
    private long m_lastGamePacketReceived;
    private static DevIDType s_curType = DevIDType.ID_TYPE_NONE;
    private static long s_regStartTime = 0;

    // These must match the enum XWPDevProto in xwrelay.h
    private static enum XWPDevProto { XWPDEV_PROTO_VERSION_INVALID
            ,XWPDEV_PROTO_VERSION_1
            };

    // private static final int XWPDEV_NONE = 0;

    // Must be kept in sync with eponymous enum in xwrelay.h
    private enum XWRelayReg {
             XWPDEV_NONE
            ,XWPDEV_UNAVAIL
            ,XWPDEV_REG
            ,XWPDEV_REGRSP
            ,XWPDEV_KEEPALIVE
            ,XWPDEV_HAVEMSGS
            ,XWPDEV_RQSTMSGS
            ,XWPDEV_MSG
            ,XWPDEV_MSGNOCONN
            ,XWPDEV_MSGRSP
            ,XWPDEV_BADREG
            ,XWPDEV_ACK
            ,XWPDEV_DELGAME
            ,XWPDEV_ALERT
            ,XWPDEV_UPGRADE
            };

    public static void gcmConfirmed( Context context, boolean confirmed )
    {
        if ( s_gcmWorking != confirmed ) {
            DbgUtils.logf( "RelayService.gcmConfirmed(): changing "
                           + "s_gcmWorking to %b", confirmed );
            s_gcmWorking = confirmed;
        }

        // If we've gotten a GCM id and haven't registered it, do so!
        if ( confirmed && !s_curType.equals( DevIDType.ID_TYPE_ANDROID_GCM ) ) {
            s_regStartTime = 0;      // so we're sure to register
            devIDChanged();
            timerFired( context );
        }
    }

    public static void startService( Context context )
    {
        DbgUtils.logf( "RelayService.startService()" );
        Intent intent = getIntentTo( context, MsgCmds.UDP_CHANGED );
        context.startService( intent );
    }

    public static void reset( Context context )
    {
        Intent intent = getIntentTo( context, MsgCmds.RESET );
        context.startService( intent );
    }

    public static void timerFired( Context context )
    {
        Intent intent = getIntentTo( context, MsgCmds.TIMER_FIRED );
        context.startService( intent );
    }

    public static int sendPacket( Context context, long rowid, byte[] msg )
    {
        int result = -1;
        if ( NetStateCache.netAvail( context ) ) {
            Intent intent = getIntentTo( context, MsgCmds.SEND )
                .putExtra( ROWID, rowid )
                .putExtra( BINBUFFER, msg );
            context.startService( intent );
            result = msg.length;
        } else {
            DbgUtils.logf( "RelayService.sendPacket: network down" );
        }
        return result;
    }

    public static int sendNoConnPacket( Context context, long rowid, String relayID, 
                                        byte[] msg )
    {
        int result = -1;
        if ( NetStateCache.netAvail( context ) ) {
            Intent intent = getIntentTo( context, MsgCmds.SENDNOCONN )
                .putExtra( ROWID, rowid )
                .putExtra( RELAY_ID, relayID )
                .putExtra( BINBUFFER, msg );
            context.startService( intent );
            result = msg.length;
        }
        return result;
    }

    public static void devIDChanged()
    {
        s_registered = false;
    }

    // Exists to get incoming data onto the main thread
    private static void postData( Context context, long rowid, byte[] msg )
    {
        DbgUtils.logf( "RelayService::postData: packet of length %d for token %d", 
                       msg.length, rowid );
        if ( DBUtils.haveGame( context, rowid ) ) {
            Intent intent = getIntentTo( context, MsgCmds.RECEIVE )
                .putExtra( ROWID, rowid )
                .putExtra( BINBUFFER, msg );
            context.startService( intent );
        } else {
            DbgUtils.logf( "RelayService.postData(): Dropping message for "
                           + "rowid %d: not on device", rowid );
        }
    }

    public static void udpChanged( Context context )
    {
        startService( context );
    }

    public static void processGameMsgs( Context context, String relayId, 
                                        String[] msgs64 )
    {
        DbgUtils.logf( "RelayService.processGameMsgs" );
        Intent intent = getIntentTo( context, MsgCmds.PROCESS_GAME_MSGS )
            .putExtra( MSGS_ARR, msgs64 )
            .putExtra( RELAY_ID, relayId );
        context.startService( intent );
    }

    public static void processDevMsgs( Context context, String[] msgs64 )
    {
        Intent intent = getIntentTo( context, MsgCmds.PROCESS_DEV_MSGS )
            .putExtra( MSGS_ARR, msgs64 );
        context.startService( intent );
    }

    private static Intent getIntentTo( Context context, MsgCmds cmd )
    {
        Intent intent = new Intent( context, RelayService.class );
        intent.putExtra( CMD_STR, cmd.ordinal() );
        return intent;
    }

    @Override
    public void onCreate()
    {
        super.onCreate();
        m_lastGamePacketReceived = 
            XWPrefs.getPrefsLong( this, R.string.key_last_packet, 
                                  Utils.getCurSeconds() );

        m_handler = new Handler();
        m_onInactivity = new Runnable() {
                public void run() {
                    DbgUtils.logf( "RelayService: m_onInactivity fired" );
                    if ( !shouldMaintainConnection() ) {
                        NetStateCache.unregister( RelayService.this, 
                                                  RelayService.this );
                        stopSelf();
                    } else {
                        timerFired( RelayService.this );
                    }
                }
            };
    }

    @Override
    public int onStartCommand( Intent intent, int flags, int startId )
    {
        Integer result = null;
        if ( null != intent ) {
            MsgCmds cmd;
            try {
                cmd = MsgCmds.values()[intent.getIntExtra( CMD_STR, -1 )];
            } catch (Exception ex) { // OOB most likely
                cmd = null;
            }
            if ( null != cmd ) {
                DbgUtils.logf( "RelayService::onStartCommand: cmd=%s", 
                               cmd.toString() );
                switch( cmd ) {
                case PROCESS_GAME_MSGS:
                    String[] relayIDs = new String[1];
                    relayIDs[0] = intent.getStringExtra( RELAY_ID );
                    long[] rowIDs = DBUtils.getRowIDsFor( this, relayIDs[0] );
                    if ( 0 < rowIDs.length ) {
                        byte[][][] msgs = expandMsgsArray( intent );
                        process( msgs, rowIDs, relayIDs );
                    }
                    break;
                case PROCESS_DEV_MSGS:
                    byte[][][] msgss = expandMsgsArray( intent );
                    for ( byte[][] msgs : msgss ) {
                        for ( byte[] msg : msgs ) {
                            gotPacket( msg, true );
                        }
                    }
                    break;
                case UDP_CHANGED:
                    startThreads();
                    break;
                case RESET:
                    stopThreads();
                    startThreads();
                    break;
                case UPGRADE:
                    UpdateCheckReceiver.checkVersions( this, false );
                    break;
                case SEND:
                case RECEIVE:
                case SENDNOCONN:
                    startUDPThreadsIfNot();
                    long rowid = intent.getLongExtra( ROWID, -1 );
                    byte[] msg = intent.getByteArrayExtra( BINBUFFER );
                    if ( MsgCmds.SEND.equals( cmd ) ) {
                        sendMessage( rowid, msg );
                    } else if ( MsgCmds.SENDNOCONN.equals( cmd ) ) {
                        String relayID = intent.getStringExtra( RELAY_ID );
                        sendNoConnMessage( rowid, relayID, msg );
                    } else {
                        feedMessage( rowid, msg );
                    }
                    break;
                case TIMER_FIRED:
                    if ( !NetStateCache.netAvail( this ) ) {
                        DbgUtils.logf( "not connecting: no network" );
                    } else if ( startFetchThreadIf() ) {
                        // do nothing
                    } else if ( registerWithRelayIfNot() ) {
                        requestMessages();
                    }
                    break;
                default:
                    Assert.fail();
                }

                result = Service.START_STICKY;
            }
        }

        if ( null == result ) {
            result = Service.START_STICKY_COMPATIBILITY;
        }    

        NetStateCache.register( this, this );
        resetExitTimer();
        return result;
    }

    @Override
    public void onDestroy()
    {
        DbgUtils.logf( "RelayService.onDestroy() called" );

        if ( shouldMaintainConnection() ) {
            long interval_millis = getMaxIntervalSeconds() * 1000;
            RelayReceiver.restartTimer( this, interval_millis );
        }
        stopThreads();
        super.onDestroy();
    }

    // NetStateCache.StateChangedIf interface
    public void netAvail( boolean nowAvailable )
    {
        startService( this ); // bad name: will *stop* threads too
    }

    private void setupNotifications( String[] relayIDs, LastMoveInfo[] lmis )
    {
        for ( int ii = 0; ii < relayIDs.length; ++ii ) {
            String relayID = relayIDs[ii];
            LastMoveInfo lmi = lmis[ii];
            long[] rowids = DBUtils.getRowIDsFor( this, relayID );
            if ( null != rowids ) {
                for ( long rowid : rowids ) {
                    setupNotification( rowid, lmi );
                }
            }
        }
    }

    private void setupNotification( long rowid, LastMoveInfo lmi )
    {
        Intent intent = GamesListDelegate.makeRowidIntent( this, rowid );
        String msg = "";
        if ( null != lmi ) {
            msg = lmi.format( this );
        }
        String title = LocUtils.getString( this, R.string.notify_title_fmt,
                                           GameUtils.getName( this, rowid ) );
        Utils.postNotification( this, intent, title, msg, (int)rowid );
    }
    
    private boolean startFetchThreadIf()
    {
        // DbgUtils.logf( "startFetchThreadIf()" );
        boolean handled = !XWApp.UDP_ENABLED;
        if ( handled && null == m_fetchThread ) {
            m_fetchThread = new Thread( null, new Runnable() {
                    public void run() {
                        fetchAndProcess();
                        m_fetchThread = null;
                        RelayService.this.stopSelf();
                    }
                }, getClass().getName() );
            m_fetchThread.start();
        }
        return handled;
    }

    private void stopFetchThreadIf()
    {
        while ( null != m_fetchThread ) {
            DbgUtils.logf( "2: m_fetchThread NOT NULL; WHAT TO DO???" );
            try {
                Thread.sleep( 20 );
            } catch( java.lang.InterruptedException ie ) {
                DbgUtils.loge( ie );
            }
        }
    }

    private void startUDPThreadsIfNot()
    {
        DbgUtils.logf( "RelayService.startUDPThreadsIfNot()" );
        if ( XWApp.UDP_ENABLED ) {

            if ( null == m_UDPReadThread ) {
                m_UDPReadThread = new Thread( null, new Runnable() {
                        public void run() {

                            connectSocket(); // block until this is done
                            startWriteThread();

                            DbgUtils.logf( "RelayService:read thread running" );
                            byte[] buf = new byte[1024];
                            for ( ; ; ) {
                                DatagramPacket packet = 
                                    new DatagramPacket( buf, buf.length );
                                try {
                                    m_UDPSocket.receive( packet );
                                    resetExitTimer();
                                    gotPacket( packet );
                                } catch ( java.io.InterruptedIOException iioe ) {
                                    // DbgUtils.logf( "FYI: udp receive timeout" );
                                } catch( java.io.IOException ioe ) {
                                    break;
                                }
                            }
                            DbgUtils.logf( "RelayService:read thread exiting" );
                        }
                    }, getClass().getName() );
                m_UDPReadThread.start();
            } else {
                DbgUtils.logf( "m_UDPReadThread not null and assumed to "
                               + "be running" );
            }
        } else {
            DbgUtils.logf( "RelayService.startUDPThreadsIfNot(): UDP disabled" );
        }
    } // startUDPThreadsIfNot

    private void connectSocket()
    {
        if ( null == m_UDPSocket ) {
            int port = XWPrefs.getDefaultRelayPort( this );
            String host = XWPrefs.getDefaultRelayHost( this );
            try { 
                m_UDPSocket = new DatagramSocket();
                m_UDPSocket.setSoTimeout(30 * 1000); // timeout so we can log

                InetAddress addr = InetAddress.getByName( host );
                m_UDPSocket.connect( addr, port ); // remember this address
            } catch( java.net.SocketException se ) {
                DbgUtils.loge( se );
                Assert.fail();
            } catch( java.net.UnknownHostException uhe ) {
                DbgUtils.loge( uhe );
            }
        } else {
            Assert.assertTrue( m_UDPSocket.isConnected() );
            DbgUtils.logf( "m_UDPSocket not null" );
        }
    }

    private void startWriteThread()
    {
        if ( null == m_UDPWriteThread ) {
            m_UDPWriteThread = new Thread( null, new Runnable() {
                    public void run() {
                        DbgUtils.logf( "RelayService: write thread starting" );
                        for ( ; ; ) {
                            PacketData outData;
                            try {
                                outData = m_queue.take();
                            } catch ( InterruptedException ie ) {
                                DbgUtils.logf( "RelayService; write thread "
                                               + "killed" );
                                break;
                            }
                            if ( null == outData
                                 || 0 == outData.getLength() ) {
                                DbgUtils.logf( "stopping write thread" );
                                break;
                            }

                            try {
                                DatagramPacket outPacket = outData.assemble();
                                m_UDPSocket.send( outPacket );
                                int packetID = outData.m_packetID;
                                DbgUtils.logf( "Sent udp packet, id=%d, of "
                                               + "length %d", 
                                               packetID, outPacket.getLength() );
                                synchronized( s_packetsSent ) {
                                    s_packetsSent.add( packetID );
                                }
                                resetExitTimer();
                                ConnStatusHandler.showSuccessOut();
                            } catch ( java.io.IOException ioe ) {
                                DbgUtils.loge( ioe );
                            } catch ( NullPointerException npe ) {
                                DbgUtils.logf( "network problem; dropping packet" );
                            }
                        }
                        DbgUtils.logf( "RelayService: write thread exiting" );
                    }
                }, getClass().getName() );
            m_UDPWriteThread.start();
        } else {
            DbgUtils.logf( "m_UDPWriteThread not null and assumed to "
                           + "be running" );
        }
    }

    private void stopUDPThreadsIf()
    {
        DbgUtils.logf( "stopUDPThreadsIf" );
        if ( null != m_UDPWriteThread ) {
            // can't add null
            m_queue.add( new PacketData() );
            try {
                DbgUtils.logf( "joining m_UDPWriteThread" );
                m_UDPWriteThread.join();
                DbgUtils.logf( "SUCCESSFULLY joined m_UDPWriteThread" );
            } catch( java.lang.InterruptedException ie ) {
                DbgUtils.loge( ie );
            }
            m_UDPWriteThread = null;
            m_queue.clear();
        }
        if ( null != m_UDPSocket && null != m_UDPReadThread ) {
            m_UDPSocket.close();
            DbgUtils.logf( "waiting for read thread to exit" );
            try {
                m_UDPReadThread.join();
            } catch( java.lang.InterruptedException ie ) {
                DbgUtils.loge( ie );
            }
            DbgUtils.logf( "read thread exited" );
            m_UDPReadThread = null;
            m_UDPSocket = null;
        }
        DbgUtils.logf( "stopUDPThreadsIf DONE" );
    }

    // MIGHT BE Running on reader thread
    private void gotPacket( byte[] data, boolean skipAck )
    {
        ByteArrayInputStream bis = new ByteArrayInputStream( data );
        DataInputStream dis = new DataInputStream( bis );
        try {
            PacketHeader header = readHeader( dis );
            if ( null != header ) {
                if ( !skipAck ) {
                    sendAckIf( header );
                }
                DbgUtils.logf( "gotPacket: cmd=%s", header.m_cmd.toString() );
                switch ( header.m_cmd ) { 
                case XWPDEV_UNAVAIL:
                    int unavail = dis.readInt();
                    DbgUtils.logf( "relay unvailable for another %d seconds", 
                                   unavail );
                    String str = getVLIString( dis );
                    sendResult( MultiEvent.RELAY_ALERT, str );
                    break;
                case XWPDEV_ALERT:
                    str = getVLIString( dis );
                    Intent intent = GamesListDelegate.makeAlertIntent( this, str );
                    Utils.postNotification( this, intent, 
                                            R.string.relay_alert_title,
                                            str, str.hashCode() );
                    break;
                case XWPDEV_BADREG:
                    str = getVLIString( dis );
                    DbgUtils.logf( "bad relayID \"%s\" reported", str );
                    XWPrefs.clearRelayDevID( this );
                    s_registered = false;
                    registerWithRelay();
                    break;
                case XWPDEV_REGRSP:
                    str = getVLIString( dis );
                    short maxIntervalSeconds = dis.readShort();
                    DbgUtils.logf( "got relayid %s, maxInterval %d", str, 
                                   maxIntervalSeconds );
                    setMaxIntervalSeconds( maxIntervalSeconds );
                    XWPrefs.setRelayDevID( this, str );
                    s_registered = true;
                    break;
                case XWPDEV_HAVEMSGS:
                    requestMessages();
                    break;
                case XWPDEV_MSG:
                    int token = dis.readInt();
                    byte[] msg = new byte[dis.available()];
                    dis.read( msg );
                    postData( this, token, msg );

                    // game-related packets only count
                    long lastGamePacketReceived = Utils.getCurSeconds();
                    if ( lastGamePacketReceived != m_lastGamePacketReceived ) {
                        XWPrefs.setPrefsLong( this, R.string.key_last_packet,
                                              lastGamePacketReceived );
                        m_lastGamePacketReceived = lastGamePacketReceived;
                    }
                    break;
                case XWPDEV_UPGRADE:
                    intent = getIntentTo( this, MsgCmds.UPGRADE );
                    startService( intent );
                    break;
                case XWPDEV_ACK:
                    noteAck( vli2un( dis ) );
                    break;
                default:
                    DbgUtils.logf( "RelayService.gotPacket(): Unhandled cmd" );
                    break;
                }
            }
        } catch ( java.io.IOException ioe ) {
            DbgUtils.loge( ioe );
        }
    }

    private void gotPacket( DatagramPacket packet )
    {
        ConnStatusHandler.showSuccessIn();

        int packetLen = packet.getLength();
        byte[] data = new byte[packetLen];
        System.arraycopy( packet.getData(), 0, data, 0, packetLen );
        DbgUtils.logf( "RelayService::gotPacket: %d bytes of data", packetLen );
        gotPacket( data, false );
    } // gotPacket

    private boolean shouldRegister()
    {
        String relayID = XWPrefs.getRelayDevID( this );
        boolean registered = null != relayID;
        if ( registered ) {
            registered = XWPrefs
                .getPrefsBoolean( this, R.string.key_relay_regid_ackd, false );
        }
        DbgUtils.logf( "shouldRegister()=>%b", !registered );
        return !registered;
    }

    // Register: pass both the relay-assigned relayID (or empty string
    // if none has been assigned yet) and the deviceID IFF it's
    // changed since we last registered (Otherwise just ID_TYPE_NONE
    // and no string)
    // 
    // How do we know if we need to register?  We keep a timestamp
    // indicating when we last got a reg-response.  When the GCM id
    // changes, that timestamp is cleared.
    private void registerWithRelay()
    {
        long now = Utils.getCurSeconds();
        long interval = now - s_regStartTime;
        if ( interval < REG_WAIT_INTERVAL ) {
            DbgUtils.logf( "registerWithRelay: skipping because only %d "
                           + "seconds since last start", interval );
        } else {
            String relayID = XWPrefs.getRelayDevID( this );
            DevIDType[] typa = new DevIDType[1];
            String devid = getDevID( typa );
            DevIDType typ = typa[0];
            s_curType = typ;

            ByteArrayOutputStream bas = new ByteArrayOutputStream();
            try {
                DataOutputStream out = new DataOutputStream( bas );

                writeVLIString( out, relayID );             // may be empty
                if ( DevIDType.ID_TYPE_RELAY == typ ) {     // all's well
                    out.writeByte( DevIDType.ID_TYPE_NONE.ordinal() );
                } else {
                    out.writeByte( typ.ordinal() );
                    writeVLIString( out, devid );
                }

                DbgUtils.logf( "registering devID \"%s\" (type=%s)", devid, 
                               typ.toString() );

                out.writeShort( BuildConstants.CLIENT_VERS_RELAY );
                writeVLIString( out, BuildConstants.GIT_REV );
                // writeVLIString( out, String.format( "€%s", Build.MODEL) );
                writeVLIString( out, Build.MODEL );
                writeVLIString( out, Build.VERSION.RELEASE );

                postPacket( bas, XWRelayReg.XWPDEV_REG );
                s_regStartTime = now;
            } catch ( java.io.IOException ioe ) {
                DbgUtils.loge( ioe );
            }
        }
    }

    private boolean registerWithRelayIfNot()
    {
        if ( !s_registered && shouldRegister() ) {
            registerWithRelay();
        }
        return s_registered;
    }

    private void requestMessagesImpl( XWRelayReg reg )
    {
        ByteArrayOutputStream bas = new ByteArrayOutputStream();
        try {
            String devid = getDevID( null );
            if ( null != devid ) {
                DataOutputStream out = new DataOutputStream( bas );
                writeVLIString( out, devid );
                postPacket( bas, reg );
            }
        } catch ( java.io.IOException ioe ) {
            DbgUtils.loge( ioe );
        }
    }

    private void requestMessages()
    {
        requestMessagesImpl( XWRelayReg.XWPDEV_RQSTMSGS );
    }

    // private void sendKeepAlive()
    // {
    //     requestMessagesImpl( XWRelayReg.XWPDEV_KEEPALIVE );
    // }

    private void sendMessage( long rowid, byte[] msg )
    {
        DbgUtils.logf( "RelayService.sendMessage()" );
        ByteArrayOutputStream bas = new ByteArrayOutputStream();
        try {
            DataOutputStream out = new DataOutputStream( bas );
            Assert.assertTrue( rowid < Integer.MAX_VALUE );
            out.writeInt( (int)rowid );
            out.write( msg, 0, msg.length );
            postPacket( bas, XWRelayReg.XWPDEV_MSG );
        } catch ( java.io.IOException ioe ) {
            DbgUtils.loge( ioe );
        } 
    }

    private void sendNoConnMessage( long rowid, String relayID, byte[] msg )
    {
        ByteArrayOutputStream bas = new ByteArrayOutputStream();
        try {
            DataOutputStream out = new DataOutputStream( bas );
            Assert.assertTrue( rowid < Integer.MAX_VALUE ); // ???
            out.writeInt( (int)rowid );
            out.writeBytes( relayID );
            out.write( '\n' );
            out.write( msg, 0, msg.length );
            postPacket( bas, XWRelayReg.XWPDEV_MSGNOCONN );
        } catch ( java.io.IOException ioe ) {
            DbgUtils.loge( ioe );
        } 
    }

    private void sendAckIf( PacketHeader header )
    {
        if ( 0 != header.m_packetID ) {
            ByteArrayOutputStream bas = new ByteArrayOutputStream();
            try {
                DataOutputStream out = new DataOutputStream( bas );
                un2vli( header.m_packetID, out );
                postPacket( bas, XWRelayReg.XWPDEV_ACK );
            } catch ( java.io.IOException ioe ) {
                DbgUtils.loge( ioe );
            }
        }
    }

    private PacketHeader readHeader( DataInputStream dis )
        throws java.io.IOException
    {
        PacketHeader result = null;
        byte proto = dis.readByte();
        if ( XWPDevProto.XWPDEV_PROTO_VERSION_1.ordinal() == proto ) {
            int packetID = vli2un( dis );
            if ( 0 != packetID ) {
                DbgUtils.logf( "readHeader: got packetID %d", packetID );
            }
            byte ordinal = dis.readByte();
            XWRelayReg cmd = XWRelayReg.values()[ordinal];
            result = new PacketHeader( cmd, packetID );
        } else {
            DbgUtils.logf( "bad proto: %d", proto );
        }
        return result;
    }

    private String getVLIString( DataInputStream dis )
        throws java.io.IOException
    {
        int len = vli2un( dis );
        byte[] tmp = new byte[len];
        dis.read( tmp );
        String result = new String( tmp );
        return result;
    }

    private void postPacket( ByteArrayOutputStream bas, XWRelayReg cmd )
    {
        m_queue.add( new PacketData( bas, cmd ) );
        // 0 ok; thread will often have sent already!
        // DbgUtils.logf( "postPacket() done; %d in queue", m_queue.size() );
    }

    private String getDevID( DevIDType[] typp )
    {
        String devid = null;
        DevIDType typ;

        if ( XWPrefs.getPrefsBoolean( this, R.string.key_relay_regid_ackd, 
                                      false ) ) {
            devid = XWPrefs.getRelayDevID( this );
        }
        if ( null != devid && 0 < devid.length() ) {
            typ = DevIDType.ID_TYPE_RELAY;
        } else {
            devid = XWPrefs.getGCMDevID( this );
            if ( null != devid && 0 < devid.length() ) {
                typ = DevIDType.ID_TYPE_ANDROID_GCM;
            } else {
                devid = "";
                typ = DevIDType.ID_TYPE_ANON;
            }
        }
        if ( null != typp ) {
            typp[0] = typ;
        } else if ( typ != DevIDType.ID_TYPE_RELAY ) {
            devid = null;
        }
        return devid;
    }

    private void feedMessage( long rowid, byte[] msg )
    {
        DbgUtils.logf( "RelayService::feedMessage: %d bytes for rowid %d", 
                       msg.length, rowid );
        if ( BoardDelegate.feedMessage( rowid, msg ) ) {
            DbgUtils.logf( "feedMessage: board ate it" );
            // do nothing
        } else {
            RelayMsgSink sink = new RelayMsgSink();
            sink.setRowID( rowid );
            LastMoveInfo lmi = new LastMoveInfo();
            if ( GameUtils.feedMessage( this, rowid, msg, null, 
                                        sink, lmi ) ) {
                setupNotification( rowid, lmi );
            } else {
                DbgUtils.logf( "feedMessage(): background dropped it" );
            }
        }
    }

    private void fetchAndProcess()
    {
        long[][] rowIDss = new long[1][];
        String[] relayIDs = DBUtils.getRelayIDs( this, rowIDss );
        if ( null != relayIDs && 0 < relayIDs.length ) {
            byte[][][] msgs = NetUtils.queryRelay( this, relayIDs );
            process( msgs, rowIDss[0], relayIDs );
        }
    }

    private void process( byte[][][] msgs, long[] rowIDs, String[] relayIDs )
    {
        DbgUtils.logf( "RelayService.process()" );
        if ( null != msgs ) {
            RelayMsgSink sink = new RelayMsgSink();
            int nameCount = relayIDs.length;
            DbgUtils.logf( "RelayService.process(): nameCount: %d", nameCount );
            ArrayList<String> idsWMsgs = new ArrayList<String>( nameCount );
            ArrayList<LastMoveInfo> lmis = new ArrayList<LastMoveInfo>( nameCount );

            for ( int ii = 0; ii < nameCount; ++ii ) {
                byte[][] forOne = msgs[ii];

                // if game has messages, open it and feed 'em to it.
                if ( null != forOne ) {
                    LastMoveInfo lmi = new LastMoveInfo();
                    sink.setRowID( rowIDs[ii] );
                    if ( BoardDelegate.feedMessages( rowIDs[ii], forOne )
                         || GameUtils.feedMessages( this, rowIDs[ii],
                                                    forOne, null,
                                                    sink, lmi ) ) {
                        idsWMsgs.add( relayIDs[ii] );
                        lmis.add( lmi );
                    } else {
                        DbgUtils.logf( "message for %s (rowid %d) not consumed",
                                       relayIDs[ii], rowIDs[ii] );
                    }
                }
            }
            if ( 0 < idsWMsgs.size() ) {
                String[] tmp = new String[idsWMsgs.size()];
                idsWMsgs.toArray( tmp );
                LastMoveInfo[] lmisa = new LastMoveInfo[lmis.size()];
                lmis.toArray( lmisa );
                setupNotifications( tmp, lmisa );
            }
            sink.send( this );
        }
    }

    private static class AsyncSender extends AsyncTask<Void, Void, Void> {
        private Context m_context;
        private HashMap<String,ArrayList<byte[]>> m_msgHash;

        public AsyncSender( Context context, 
                            HashMap<String,ArrayList<byte[]>> msgHash )
        {
            m_context = context;
            m_msgHash = msgHash;
        }

        @Override
        protected Void doInBackground( Void... ignored )
        {
            // format: total msg lenth: 2
            //         number-of-relayIDs: 2
            //         for-each-relayid: relayid + '\n': varies
            //                           message count: 1
            //                           for-each-message: length: 2
            //                                             message: varies

            // Build up a buffer containing everything but the total
            // message length and number of relayIDs in the message.
            try {
                ByteArrayOutputStream store = 
                    new ByteArrayOutputStream( MAX_BUF ); // mem
                DataOutputStream outBuf = new DataOutputStream( store );
                int msgLen = 4;          // relayID count + protocol stuff
                int nRelayIDs = 0;
        
                Iterator<String> iter = m_msgHash.keySet().iterator();
                while ( iter.hasNext() ) {
                    String relayID = iter.next();
                    int thisLen = 1 + relayID.length(); // string and '\n'
                    thisLen += 2;                        // message count

                    ArrayList<byte[]> msgs = m_msgHash.get( relayID );
                    for ( byte[] msg : msgs ) {
                        thisLen += 2 + msg.length;
                    }

                    if ( msgLen + thisLen > MAX_BUF ) {
                        // Need to deal with this case by sending multiple
                        // packets.  It WILL happen.
                        break;
                    }
                    // got space; now write it
                    ++nRelayIDs;
                    outBuf.writeBytes( relayID );
                    outBuf.write( '\n' );
                    outBuf.writeShort( msgs.size() );
                    for ( byte[] msg : msgs ) {
                        outBuf.writeShort( msg.length );
                        outBuf.write( msg );
                    }
                    msgLen += thisLen;
                }
                // Now open a real socket, write size and proto, and
                // copy in the formatted buffer
                Socket socket = NetUtils.makeProxySocket( m_context, 8000 );
                if ( null != socket ) {
                    DataOutputStream outStream = 
                        new DataOutputStream( socket.getOutputStream() );
                    outStream.writeShort( msgLen );
                    outStream.writeByte( NetUtils.PROTOCOL_VERSION );
                    outStream.writeByte( NetUtils.PRX_PUT_MSGS );
                    outStream.writeShort( nRelayIDs );
                    outStream.write( store.toByteArray() );
                    outStream.flush();
                    socket.close();
                }
            } catch ( java.io.IOException ioe ) {
                DbgUtils.loge( ioe );
            }
            return null;
        } // doInBackground
    }

    private static void sendToRelay( Context context,
                                     HashMap<String,ArrayList<byte[]>> msgHash )
    {
        if ( null != msgHash ) {
            new AsyncSender( context, msgHash ).execute();
        } else {
            DbgUtils.logf( "sendToRelay: null msgs" );
        }
    } // sendToRelay

    private class RelayMsgSink extends MultiMsgSink {

        private HashMap<String,ArrayList<byte[]>> m_msgLists = null;
        private long m_rowid = -1;

        public void setRowID( long rowid ) { m_rowid = rowid; }

        public void send( Context context )
        {
            if ( -1 == m_rowid ) {
                sendToRelay( context, m_msgLists );
            } else {
                Assert.assertNull( m_msgLists );
            }
        }

        /***** TransportProcs interface *****/

        public int transportSend( byte[] buf, final CommsAddrRec addr, 
                                  int gameID )
        {
            Assert.assertTrue( -1 != m_rowid );
            sendPacket( RelayService.this, m_rowid, buf );
            return buf.length;
        }

        public boolean relayNoConnProc( byte[] buf, String relayID )
        {
            if ( -1 != m_rowid ) {
                sendNoConnMessage( m_rowid, relayID, buf );
            } else {
                if ( null == m_msgLists ) {
                    m_msgLists = new HashMap<String,ArrayList<byte[]>>();
                }

                ArrayList<byte[]> list = m_msgLists.get( relayID );
                if ( list == null ) {
                    list = new ArrayList<byte[]>();
                    m_msgLists.put( relayID, list );
                }
                list.add( buf );
            }
            return true;
        }
    }

    private static int nextPacketID( XWRelayReg cmd )
    {
        int nextPacketID = 0;
        if ( XWRelayReg.XWPDEV_ACK != cmd ) {
            synchronized( s_packetsSent ) {
                nextPacketID = ++s_nextPacketID;
            }
        }
        return nextPacketID;
    }

    private static void noteAck( int packetID )
    {
        synchronized( s_packetsSent ) {
            if ( s_packetsSent.contains( packetID ) ) {
                s_packetsSent.remove( packetID );
            } else {
                DbgUtils.logf( "Weird: got ack %d but never sent", packetID );
            }
            DbgUtils.logf( "RelayService.noteAck(): Got ack for %d; "
                           + "there are %d unacked packets", 
                           packetID, s_packetsSent.size() );
        }
    }

    // Called from any thread
    private void resetExitTimer()
    {
        // DbgUtils.logf( "RelayService.resetExitTimer()" );
        m_handler.removeCallbacks( m_onInactivity );

        // UDP socket's no good as a return address after several
        // minutes of inactivity, so do something after that time.
        m_handler.postDelayed( m_onInactivity, 
                               getMaxIntervalSeconds() * 1000 );
    }

    private void startThreads()
    {
        DbgUtils.logf( "RelayService.startThreads()" );
        if ( !NetStateCache.netAvail( this ) ) {
            stopThreads();
        } else if ( XWApp.UDP_ENABLED ) {
            stopFetchThreadIf();
            startUDPThreadsIfNot();
            registerWithRelay();
        } else {
            stopUDPThreadsIf();
            startFetchThreadIf();
        }
    }

    private void stopThreads()
    {
        DbgUtils.logf( "RelayService.stopThreads()" );
        stopFetchThreadIf();
        stopUDPThreadsIf();
    }

    private static void un2vli( int nn, OutputStream os ) 
        throws java.io.IOException
    {
        int indx = 0;
        boolean done = false;
        do {
            byte byt = (byte)(nn & 0x7F);
            nn >>= 7;
            done = 0 == nn;
            if ( done ) {
                byt |= 0x80;
            }
            os.write( byt );
        } while ( !done );
    }

    private static int vli2un( InputStream is ) throws java.io.IOException
    {
        int result = 0;
        byte[] buf = new byte[1];
        int nRead = 0;

        boolean done = false;
        for ( int count = 0; !done; ++count ) {
            nRead = is.read( buf );
            if ( 1 != nRead ) {
                DbgUtils.logf( "vli2un: unable to read from stream" );
                break;
            }
            int byt = buf[0];
            done = 0 != (byt & 0x80);
            if ( done ) {
                byt &= 0x7F;
            } 
            result |= byt << (7 * count);
        }

        return result;
    }

    private static void writeVLIString( DataOutputStream os, String str )
        throws java.io.IOException
    {
        if ( null == str ) {
            str = "";
        }
        byte[] bytes = str.getBytes( "UTF-8" );
        int len = bytes.length;
        un2vli( len, os );
        os.write( bytes, 0, len );
    }

    private void setMaxIntervalSeconds( int maxIntervalSeconds )
    {
        if ( m_maxIntervalSeconds != maxIntervalSeconds ) {
            m_maxIntervalSeconds = maxIntervalSeconds;
            XWPrefs.setPrefsInt( this, R.string.key_udp_interval, 
                                 maxIntervalSeconds );
        }
    }

    private int getMaxIntervalSeconds()
    {
        if ( 0 == m_maxIntervalSeconds ) {
            m_maxIntervalSeconds = 
                XWPrefs.getPrefsInt( this, R.string.key_udp_interval, 60 );
        }
        return m_maxIntervalSeconds;
    }

    private byte[][][] expandMsgsArray( Intent intent )
    {
        String[] msgs64 = intent.getStringArrayExtra( MSGS_ARR );
        int count = msgs64.length;

        byte[][][] msgs = new byte[1][count][];
        for ( int ii = 0; ii < count; ++ii ) {
            msgs[0][ii] = XwJNI.base64Decode( msgs64[ii] );
        }
        return msgs;
    }

    /* Timers:
     *
     * Two goals: simulate the GCM experience for those who don't have
     * it (e.g. Kindle); and stop this service when it's not needed.
     * For now, we'll try to be immediately reachable from the relay
     * if: 1) the device doesn't have GCM; and 2) it's been less than
     * a week since we last received a packet from the relay.  We'll
     * do this even if there are no relay games left on the device in
     * order to support the rematch feature.
     *
     * Goal: maintain connection by keeping this service alive with
     * its periodic pings to relay.  When it dies or is killed,
     * notice, and use RelayReceiver's timer to get it restarted a bit
     * later.  But note: s_gcmWorking will not be set when the app is
     * relaunched.
     */

    private boolean shouldMaintainConnection()
    {
        boolean result = XWApp.GCM_IGNORED || !s_gcmWorking;
        if ( result ) {
            long interval = Utils.getCurSeconds() - m_lastGamePacketReceived;
            result = interval < MAX_KEEPALIVE_SECS;
        }
        // DbgUtils.logf( "RelayService.shouldMaintainConnection=>%b", result );
        return result;
    }

    private class PacketHeader {
        public int m_packetID;
        public XWRelayReg m_cmd;
        public PacketHeader( XWRelayReg cmd, int packetID ) {
            m_packetID = packetID;
            m_cmd = cmd;
        }
    }

    private class PacketData {
        public PacketData() { m_bas = null; }

        public PacketData( ByteArrayOutputStream bas, XWRelayReg cmd )
        {
            m_bas = bas;
            m_cmd = cmd;
        }

        public int getLength()
        {
            int result = 0;
            if ( null != m_bas ) { // empty case?
                if ( null == m_header ) {
                    makeHeader();
                }
                result = m_header.length + m_bas.size();
            }
            return result;
        }

        public DatagramPacket assemble()
        {
            byte[] dest = new byte[getLength()];
            System.arraycopy( m_header, 0, dest, 0, m_header.length );
            byte[] basData = m_bas.toByteArray();
            System.arraycopy( basData, 0, dest, m_header.length, basData.length );
            return new DatagramPacket( dest, dest.length );
        }

        private void makeHeader()
        {
            ByteArrayOutputStream bas = new ByteArrayOutputStream();
            try {
                m_packetID = nextPacketID( m_cmd );
                DataOutputStream out = new DataOutputStream( bas );
                DbgUtils.logf( "Building packet with cmd %s", 
                               m_cmd.toString() );
                out.writeByte( XWPDevProto.XWPDEV_PROTO_VERSION_1.ordinal() );
                un2vli( m_packetID, out );
                out.writeByte( m_cmd.ordinal() );
                m_header = bas.toByteArray();
            } catch ( java.io.IOException ioe ) {
                DbgUtils.loge( ioe );
            }
        }

        public ByteArrayOutputStream m_bas;
        public XWRelayReg m_cmd;
        public byte[] m_header;
        public int m_packetID;
    }

}
