/* -*- compile-command: "find-and-ant.sh debug install"; -*- */
/*
 * Copyright 2009-2011 by Eric House (xwords@eehouse.org).  All
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

import android.content.Intent;
import android.os.Bundle;
import org.json.JSONException;
import org.json.JSONObject;

public class AbsLaunchInfo {
    private static final String LANG = "abslaunchinfo_lang";
    private static final String DICT = "abslaunchinfo_dict";
    private static final String NPLAYERS = "abslaunchinfo_nplayers";
    private static final String NPLAYERST = "abslaunchinfo_nplayerst";
    private static final String VALID = "abslaunchinfo_valid";

    protected String dict;
    protected int lang;
    protected int nPlayersT;

    private boolean m_valid;

    protected AbsLaunchInfo() {}

    protected JSONObject init( String data ) throws JSONException
    {
        JSONObject json = new JSONObject( data );
        lang = json.getInt( LANG );
        dict = json.getString( DICT );
        nPlayersT = json.getInt( NPLAYERST );
        return json;
    }

    protected void init( Intent intent )
    {
        Bundle bundle = intent.getExtras();
        init( bundle );
    }

    protected void init( Bundle bundle )
    {
        lang = bundle.getInt( LANG );
        dict = bundle.getString( DICT );
        nPlayersT = bundle.getInt( NPLAYERS );
        m_valid = bundle.getBoolean( VALID );
    }

    protected void putSelf( Bundle bundle )
    {
        bundle.putInt( LANG, lang );
        bundle.putString( DICT, dict );
        bundle.putInt( NPLAYERS, nPlayersT );
        bundle.putBoolean( VALID, m_valid );
    }

    protected static JSONObject makeLaunchJSONObject( int lang, String dict, 
                                                      int nPlayersT )
        throws JSONException
    {
        return new JSONObject()
            .put( LANG, lang )
            .put( DICT, dict )
            .put( NPLAYERST, nPlayersT )
            ;
    }

    protected boolean isValid() { return m_valid; }
    protected void setValid( boolean valid ) { m_valid = valid; }
}
