/* -*- compile-command: "find-and-gradle.sh -PuseCrashlytics insXw4dDeb"; -*- */
/*
 * Copyright 2019 by Eric House (xwords@eehouse.org).  All rights reserved.
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
import android.content.Intent;

public class FBMService {
    private static final String TAG = FBMService.class.getSimpleName();

    public static void init( Context context )
    {
        Log.d( TAG, "init()" );
        RelayService.fcmConfirmed( context, false );
    }

    public static String getFCMDevID( Context context )
    {
        return null;
    }
}
