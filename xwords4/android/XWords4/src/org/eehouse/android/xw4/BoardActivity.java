/* -*- compile-command: "find-and-ant.sh debug install"; -*- */
/*
 * Copyright 2009 - 2013 by Eric House (xwords@eehouse.org).  All
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

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.view.KeyEvent;

public class BoardActivity extends XWActivity {

    private BoardDelegate m_dlgt;

    @Override
    protected void onCreate( Bundle savedInstanceState ) 
    {
        m_dlgt = new BoardDelegate( this, savedInstanceState );
        super.onCreate( savedInstanceState, m_dlgt );

        if ( 9 <= Integer.valueOf( android.os.Build.VERSION.SDK ) ) {
            setRequestedOrientation( ActivityInfo.
                                     SCREEN_ORIENTATION_SENSOR_PORTRAIT );
        }
    } // onCreate

    @Override
    public void onWindowFocusChanged( boolean hasFocus )
    {
        super.onWindowFocusChanged( hasFocus );
        m_dlgt.onWindowFocusChanged( hasFocus );
    }

    @Override
    public boolean onKeyDown( int keyCode, KeyEvent event )
    {
        return m_dlgt.onKeyDown( keyCode, event )
            || super.onKeyDown( keyCode, event );
    }

    @Override
    public boolean onKeyUp( int keyCode, KeyEvent event )
    {
        return m_dlgt.onKeyUp( keyCode, event ) || super.onKeyUp( keyCode, event );
    }

    private static void noteSkip()
    {
        String msg = "BoardActivity.feedMessage[s](): skipped because "
            + "too many open Boards";
        DbgUtils.logf(msg );
    }
} // class BoardActivity
