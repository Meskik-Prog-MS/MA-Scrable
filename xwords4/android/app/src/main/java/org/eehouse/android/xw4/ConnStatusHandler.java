/* -*- compile-command: "find-and-gradle.sh inXw4dDeb"; -*- */
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


import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.provider.Settings;
import android.text.format.DateUtils;

import org.eehouse.android.xw4.jni.CommsAddrRec.CommsConnType;
import org.eehouse.android.xw4.jni.CommsAddrRec.CommsConnTypeSet;
import org.eehouse.android.xw4.jni.CommsAddrRec;
import org.eehouse.android.xw4.jni.XwJNI;
import org.eehouse.android.xw4.loc.LocUtils;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class ConnStatusHandler {
    private static final String TAG = ConnStatusHandler.class.getSimpleName();
    private static final String RECS_KEY = TAG + "/recs";

    public interface ConnStatusCBacks {
        public void invalidateParent();
        public void onStatusClicked();
        public Handler getHandler();
    }

    private static final int SUCCESS_IN = 0;
    private static final int SUCCESS_OUT = 1;
    private static final int SHOW_SUCCESS_INTERVAL = 1000;
    private static final boolean SOLO_NOGREEN = true;

    private static Rect s_rect;
    private static boolean s_downOnMe = false;
    private static ConnStatusCBacks s_cbacks;
    private static Paint s_fillPaint = new Paint( Paint.ANTI_ALIAS_FLAG );
    private static boolean[] s_showSuccesses = { false, false };

    private static class SuccessRecord implements java.io.Serializable {
        public long lastSuccess;
        public long lastFailure;
        public boolean successNewer;

        public boolean haveFailure()
        {
            return lastFailure > 0;
        }

        public boolean haveSuccess()
        {
            return lastSuccess > 0;
        }

        public String newerStr( Context context )
        {
            return format( context, successNewer? lastSuccess : lastFailure );
        }

        public String olderStr( Context context )
        {
            return format( context, successNewer? lastFailure : lastSuccess );
        }

        public void update( boolean success )
        {
            long now = System.currentTimeMillis();
            if ( success ) {
                lastSuccess = now;
            } else {
                lastFailure = now;
            }
            successNewer = success;
        }

        private static String format( Context context, long millis )
        {
            String result = null;
            if ( millis > 0 ) {
                CharSequence seq =
                    DateUtils.getRelativeDateTimeString( context, millis,
                                                         DateUtils.SECOND_IN_MILLIS,
                                                         DateUtils.WEEK_IN_MILLIS,
                                                         0 );
                result = seq.toString();
            }
            return result;
        }
    }

    private ConnStatusHandler() {}

    private static boolean s_needsSave = false;

    public static void setRect( int left, int top, int right, int bottom )
    {
        s_rect = new Rect( left, top, right, bottom );
    }

    public static void clearRect()
    {
        s_rect = null;
    }

    public static void setHandler( ConnStatusCBacks cbacks )
    {
        s_cbacks = cbacks;
    }

    public static boolean handleDown( int xx, int yy )
    {
        s_downOnMe = null != s_rect && s_rect.contains( xx, yy );
        return s_downOnMe;
    }

    public static boolean handleUp( int xx, int yy )
    {
        boolean result = s_downOnMe && s_rect.contains( xx, yy );
        if ( result && null != s_cbacks ) {
            s_cbacks.onStatusClicked();
        }
        s_downOnMe = false;
        return result;
    }

    public static boolean handleMove( int xx, int yy )
    {
        return s_downOnMe && s_rect.contains( xx, yy );
    }

    public static String getStatusText( Context context,
                                        CommsConnTypeSet connTypes,
                                        CommsAddrRec addr )
    {
        String msg;
        if ( null == connTypes || 0 == connTypes.size() ) {
            msg = null;
        } else {
            StringBuffer sb = new StringBuffer();
            String tmp;
            synchronized( ConnStatusHandler.class ) {
                sb.append( LocUtils.getString( context,
                                               R.string.connstat_net_fmt,
                                               connTypes.toString( context, true )));
                for ( CommsConnType typ : connTypes.getTypes() ) {
                    sb.append( String.format( "\n\n*** %s ", typ.longName( context ) ) );
                    String did = addDebugInfo( context, addr, typ );
                    if ( null != did ) {
                        sb.append( did ).append( " " );
                    }
                    sb.append( "***\n" );

                    // For sends we list failures too.
                    SuccessRecord record = recordFor( context, typ, false );
                    tmp = LocUtils.getString( context, record.successNewer?
                                              R.string.connstat_succ :
                                              R.string.connstat_unsucc );

                    String timeStr = record.newerStr( context );
                    if ( null != timeStr ) {
                        sb.append( LocUtils
                                   .getString( context, R.string.connstat_lastsend_fmt,
                                               tmp, timeStr ) )
                            .append( "\n" );
                    }

                    int fmtId = 0;
                    if ( record.successNewer ) {
                        if ( record.haveFailure() ) {
                            fmtId = R.string.connstat_lastother_succ_fmt;
                        }
                    } else {
                        if ( record.haveSuccess() ) {
                            fmtId = R.string.connstat_lastother_unsucc_fmt;
                        }
                    }
                    timeStr = record.olderStr( context );
                    if ( 0 != fmtId && null != timeStr ) {
                        sb.append( LocUtils.getString( context, fmtId, timeStr ))
                            .append( "\n" );
                    }
                    sb.append( "\n" );

                    record = recordFor( context, typ, true );
                    if ( record.haveSuccess() ) {
                        sb.append( LocUtils.getString( context,
                                                       R.string.connstat_lastreceipt_fmt,
                                                       record.newerStr( context ) ) );
                    } else {
                        sb.append( LocUtils.getString( context, R.string.connstat_noreceipt) );
                    }
                }
            }
            msg = sb.toString();
        }
        return msg;
    }

    private static void invalidateParent()
    {
        if ( null != s_cbacks ) {
            s_cbacks.invalidateParent();
        }
    }

    public static void updateStatus( Context context, ConnStatusCBacks cbacks,
                                     CommsConnType connType, boolean success )
    {
        updateStatusImpl( context, cbacks, connType, success, true );
        updateStatusImpl( context, cbacks, connType, success, false );
    }

    public static void updateStatusIn( Context context, ConnStatusCBacks cbacks,
                                       CommsConnType connType, boolean success )
    {
        updateStatusImpl( context, cbacks, connType, success, true );
    }

    public static void updateStatusOut( Context context, ConnStatusCBacks cbacks,
                                        CommsConnType connType, boolean success )
    {
        updateStatusImpl( context, cbacks, connType, success, false );
    }

    private static void updateStatusImpl( Context context, ConnStatusCBacks cbacks,
                                          CommsConnType connType, boolean success,
                                          boolean isIn )
    {
        if ( null == cbacks ) {
            cbacks = s_cbacks;
        }

        synchronized( ConnStatusHandler.class ) {
            SuccessRecord record = recordFor( context, connType, isIn );
            record.update( success );
        }
        invalidateParent();
        saveState( context, cbacks );
        if ( success ) {
            showSuccess( cbacks, isIn );
        }
    }

    public static void showSuccessIn( ConnStatusCBacks cbcks )
    {
        showSuccess( cbcks, true );
    }

    public static void showSuccessIn()
    {
        showSuccessIn( s_cbacks );
    }

    public static void showSuccessOut( ConnStatusCBacks cbcks )
    {
        showSuccess( cbcks, false );
    }

    public static void showSuccessOut()
    {
        showSuccessOut( s_cbacks );
    }

    public static void draw( Context context, Canvas canvas, Resources res,
                             CommsConnTypeSet connTypes, boolean isSolo )
    {
        if ( !isSolo && null != s_rect ) {
            synchronized( ConnStatusHandler.class ) {
                Rect scratchR = new Rect( s_rect );
                int quarterHeight = scratchR.height() / 4;

                boolean enabled = anyTypeEnabled( context, connTypes );

                // Do the background coloring and arrow. Top half first
                scratchR.bottom -= (2 * quarterHeight);
                fillHalf( context, canvas, scratchR, connTypes, enabled, false );
                scratchR.bottom -= quarterHeight;
                drawArrow( canvas, res, scratchR, false );

                // bottom half and arrow
                scratchR.top = s_rect.top + (2 * quarterHeight);
                scratchR.bottom = s_rect.bottom;
                fillHalf( context, canvas, scratchR, connTypes, enabled, true );
                scratchR.top += quarterHeight;
                drawArrow( canvas, res, scratchR, true );

                // Center the icon in the remaining (vertically middle) rect
                scratchR.top = s_rect.top + quarterHeight;
                scratchR.bottom = s_rect.bottom - quarterHeight;
                int minDim = Math.min( scratchR.width(), scratchR.height() );
                int dx = (scratchR.width() - minDim) / 2;
                int dy = (scratchR.height() - minDim) / 2;
                scratchR.inset( dx, dy );
                Assert.assertTrue( !BuildConfig.DEBUG
                                   || 1 >= Math.abs(scratchR.width()
                                                    - scratchR.height()) );
                drawIn( canvas, res, R.drawable.multigame__gen, scratchR );
            }
        }
    }

    private static void fillHalf( Context context, Canvas canvas, Rect rect,
                                  CommsConnTypeSet connTypes, boolean enabled,
                                  boolean isIn )
    {
        enabled = enabled && null != newestSuccess( context, connTypes, isIn );
        s_fillPaint.setColor( enabled ? XWApp.GREEN : XWApp.RED );
        canvas.drawRect( rect, s_fillPaint );
    }

    private static void drawArrow( Canvas canvas, Resources res, Rect rect,
                                   boolean isIn )
    {
        int arrowID;
        boolean showSuccesses = s_showSuccesses[isIn? SUCCESS_IN : SUCCESS_OUT];
        if ( isIn ) {
            arrowID = showSuccesses ?
                R.drawable.in_arrow_active : R.drawable.in_arrow;
        } else {
            arrowID = showSuccesses ?
                R.drawable.out_arrow_active : R.drawable.out_arrow;
        }
        drawIn( canvas, res, arrowID, rect );
    }

    // This gets rid of lint warning, but I don't like it as it
    // effects the whole method.
    // @SuppressWarnings("unchecked")
    private static HashMap<CommsConnType,SuccessRecord[]> s_records;
    private static HashMap<CommsConnType,SuccessRecord[]> getRecords( Context context )
    {
        synchronized( ConnStatusHandler.class ) {
            if ( s_records == null ) {
                s_records = (HashMap<CommsConnType, SuccessRecord[]>)
                    DBUtils.getSerializableFor( context, RECS_KEY );
                if ( null == s_records ) {
                    s_records = new HashMap<>();
                }
            }
        }
        return s_records;
    }

    private static void saveState( final Context context,
                                   ConnStatusCBacks cbcks )
    {
        if ( null == cbcks ) {
            doSave( context );
        } else {
            boolean savePending;
            synchronized( ConnStatusHandler.class ) {
                savePending = s_needsSave;
                if ( !savePending ) {
                    s_needsSave = true;
                }
            }

            if ( !savePending ) {
                Handler handler = cbcks.getHandler();
                if ( null != handler ) {
                    Runnable proc = new Runnable() {
                            public void run() {
                                doSave( context );
                            }
                        };
                    handler.postDelayed( proc, 5000 );
                }
            }
        }
    }

    private static void showSuccess( ConnStatusCBacks cbcks, boolean isIn )
    {
        if ( null != cbcks ) {
            synchronized( ConnStatusHandler.class ) {
                if ( isIn && s_showSuccesses[SUCCESS_IN] ) {
                    // do nothing
                } else if ( !isIn && s_showSuccesses[SUCCESS_OUT] ) {
                    // do nothing
                } else {
                    Handler handler = cbcks.getHandler();
                    if ( null != handler ) {
                        final int index = isIn? SUCCESS_IN : SUCCESS_OUT;
                        s_showSuccesses[index] = true;

                        Runnable proc = new Runnable() {
                                public void run() {
                                    synchronized( ConnStatusHandler.class ) {
                                        s_showSuccesses[index] = false;
                                        invalidateParent();
                                    }
                                }
                            };
                        handler.postDelayed( proc, SHOW_SUCCESS_INTERVAL );
                        invalidateParent();
                    }
                }
            }
        }
    }

    private static void drawIn( Canvas canvas, Resources res, int id, Rect rect )
    {
        Drawable icon = res.getDrawable( id );
        Assert.assertTrue( icon.getBounds().width() == icon.getBounds().height() );
        icon.setBounds( rect );
        icon.draw( canvas );
    }

    private static SuccessRecord newestSuccess( Context context,
                                                CommsConnTypeSet connTypes,
                                                boolean isIn )
    {
        SuccessRecord result = null;
        if ( null != connTypes ) {
            Iterator<CommsConnType> iter = connTypes.iterator();
            while ( iter.hasNext() ) {
                CommsConnType connType = iter.next();
                SuccessRecord record = recordFor( context, connType, isIn );
                if ( record.successNewer ) {
                    if ( null == result || result.lastSuccess < record.lastSuccess ) {
                        result = record;
                    }
                }
            }
        }
        return result;
    }

    private static SuccessRecord recordFor( Context context,
                                            CommsConnType connType,
                                            boolean isIn )
    {
        SuccessRecord[] records = getRecords( context ).get( connType );
        if ( null == records ) {
            records = new SuccessRecord[] { new SuccessRecord(),
                                            new SuccessRecord(),
            };
            getRecords( context ).put( connType, records );
        }
        return records[isIn?0:1];
    }

    private static void doSave( Context context )
    {
        synchronized( ConnStatusHandler.class ) {
            DBUtils.setSerializableFor( context, RECS_KEY, getRecords( context ) );
            s_needsSave = false;
        }
    }

    private static boolean anyTypeEnabled( Context context, CommsConnTypeSet connTypes )
    {
        boolean enabled = false;
        Iterator<CommsConnType> iter = connTypes.iterator();
        while ( !enabled && iter.hasNext() ) {
            enabled = connTypeEnabled( context, iter.next() );
        }
        return enabled;
    }

    private static boolean connTypeEnabled( Context context,
                                            CommsConnType connType )
    {
        boolean result = true;
        switch( connType ) {
        case COMMS_CONN_SMS:
            result = XWPrefs.getNBSEnabled( context )
                && !getAirplaneModeOn( context );
            break;
        case COMMS_CONN_BT:
            result = XWApp.BTSUPPORTED && BTService.BTEnabled()
                && BTService.BTEnabled();
            // No: we can be in airplane mode but with BT turned on manually.
            //!getAirplaneModeOn( context );
            break;
        case COMMS_CONN_RELAY:
            result = RelayService.relayEnabled( context )
                && NetStateCache.netAvail( context );
            break;
        case COMMS_CONN_P2P:
            result = WiDirService.connecting();
            break;
        default:
            Log.w( TAG, "connTypeEnabled: %s not handled", connType.toString() );
            break;
        }
        return result;
    }

    private static boolean getAirplaneModeOn( Context context )
    {
        boolean result =
            0 != Settings.System.getInt( context.getContentResolver(),
                                         Settings.System.AIRPLANE_MODE_ON, 0 );
        return result;
    }

    private static String addDebugInfo( Context context, CommsAddrRec addr,
                                        CommsConnType typ )
    {
        String result = null;
        if ( BuildConfig.DEBUG ) {
            switch ( typ ) {
            case COMMS_CONN_RELAY:
                String fcmMsg = SuccessRecord
                    .format( context, RelayService.getLastFCMMillis() );
                result = String.format( "DevID: %d; host: %s; latest FCM: %s",
                                        DevID.getRelayDevIDInt(context),
                                        XWPrefs.getDefaultRelayHost(context),
                                        fcmMsg );
                break;
            case COMMS_CONN_P2P:
                result = WiDirService.formatNetStateInfo();
                break;
            case COMMS_CONN_BT:
                if ( null != addr ) {
                    result = addr.bt_hostName;
                }
                break;
            case COMMS_CONN_SMS:
                if ( null != addr ) {
                    result = addr.sms_phone;
                }
                break;
            default:
                break;
            }
        }

        if ( null != result ) {
            result = "(" + result + ")";
        }

        return result;
    }
}
