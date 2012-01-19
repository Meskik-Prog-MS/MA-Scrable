/* -*- compile-command: "cd ../../../../../; ant debug install"; -*- */
/*
 * Copyright 2010 - 2011 by Eric House (xwords@eehouse.org).  All
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

import android.app.Application;
import java.util.UUID;

public class XWApp extends Application {

    @Override
    public void onCreate()
    {
        super.onCreate();

        // This one line should always get logged even if logging is
        // off -- because logging is on by default until logEnable is
        // called.
        DbgUtils.logf( "XWApp.onCreate(); git_rev=%s", 
                       getString( R.string.git_rev ) );
        DbgUtils.logEnable( this );

        RelayReceiver.RestartTimer( this );
    }

    public static UUID getAppUUID() {
        return UUID.fromString( "d0837107-421f-11e1-b86c-0800200c9a66" );
    }

    public static String getAppName() {
        return "Crosswords";
    }

}
