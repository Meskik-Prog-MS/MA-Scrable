/* -*- compile-command: "cd ../../../../../; ant debug install"; -*- */
/*
 * Copyright 2012 by Eric House (xwords@eehouse.org).  All rights
 * reserved.
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

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import com.google.android.gcm.GCMBaseIntentService;
import com.google.android.gcm.GCMRegistrar;

public class GCMIntentService extends GCMBaseIntentService {

	@Override
    protected void onError( Context context, String error ) 
    {
        DbgUtils.logf("GCMIntentService.onError(%s)", error );
    }

    @Override
    protected void onRegistered( Context context, String regId ) 
    {
        DbgUtils.logf("GCMIntentService.onRegistered(%s)", regId );
        XWPrefs.setGCMDevID( context, regId );
    }

    @Override
        protected void onUnregistered( Context context, String regId ) 
    {
        DbgUtils.logf( "GCMIntentService.onUnregistered(%s)", regId );
        XWPrefs.clearGCMDevID( context );
    }

    @Override
    protected void onMessage( Context context, Intent intent ) 
    {
        DbgUtils.logf( "GCMIntentService.onMessage(%s)", intent.toString() );
        RelayReceiver.RestartTimer( context, true );
    }

    public static void init( Application app )
    {
        DbgUtils.logf( "GCMIntentService.init()" );
        int sdkVersion = Integer.valueOf(android.os.Build.VERSION.SDK);
        if ( 8 <= sdkVersion ) {
            try {
                GCMRegistrar.checkDevice( app );
                GCMRegistrar.checkManifest( app );
                final String regId = GCMRegistrar.getRegistrationId( app );
                if (regId.equals("")) {
                    DbgUtils.logf( "registering..." );
                    GCMRegistrar.register( app, GCMConsts.SENDER_ID );
                } else {
                    DbgUtils.logf( "Already registered: id=\"%s\"", regId );
                }

                String curID = XWPrefs.getGCMDevID( app );
                if ( ! curID.equals( regId ) ) {
                    DbgUtils.logf( "saved bad id: %s", curID );
                    XWPrefs.setGCMDevID( app, regId );
                }
            } catch ( UnsupportedOperationException uoe ) {
                DbgUtils.showf( app, "Device can't do GCM; am I on an emulator?" );
            }
        }
    }

}