/* -*- compile-command: "find-and-gradle.sh inXw4dDeb"; -*- */
/*
 * Copyright 2009 - 2012 by Eric House (xwords@eehouse.org).  All
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

package org.eehouse.android.xw4.jni;

import android.content.Context;


import org.eehouse.android.xw4.Log;
import org.eehouse.android.xw4.jni.CommsAddrRec.CommsConnTypeSet;

public class UtilCtxtImpl implements UtilCtxt {
    private static final String TAG = UtilCtxtImpl.class.getSimpleName();
    private Context m_context;

    private UtilCtxtImpl() {}   // force subclasses to pass context

    public UtilCtxtImpl( Context context )
    {
        super();
        m_context = context;
    }

    @Override
    public void requestTime() {
        subclassOverride( "requestTime" );
    }

    @Override
    public void notifyPickTileBlank( int playerNum, int col, int row,
                                     String[] texts )
    {
        subclassOverride( "userPickTileBlank" );
    }

    @Override
    public void informNeedPickTiles( boolean isInitial, int playerNum, int nToPick,
                                     String[] texts, int[] counts )
    {
        subclassOverride( "informNeedPickTiles" );
    }

    @Override
    public void informNeedPassword( int player, String name )
    {
        subclassOverride( "informNeedPassword" );
    }

    @Override
    public void turnChanged( int newTurn )
    {
        subclassOverride( "turnChanged" );
    }

    @Override
    public boolean engineProgressCallback()
    {
        // subclassOverride( "engineProgressCallback" );
        return true;
    }

    @Override
    public void setTimer( int why, int when, int handle )
    {
        subclassOverride( "setTimer" );
    }

    @Override
    public void clearTimer( int why )
    {
        subclassOverride( "clearTimer" );
    }

    @Override
    public void remSelected()
    {
        subclassOverride( "remSelected" );
    }

    @Override
    public void timerSelected( boolean inDuplicateMode, boolean canPause )
    {
        subclassOverride( "timerSelected" );
    }

    @Override
    public void setIsServer( boolean isServer )
    {
        subclassOverride( "setIsServer" );
    }

    @Override
    public void informWordsBlocked( int nWords, String words, String dict )
    {
        subclassOverride( "informWordsBlocked" );
    }

    @Override
    public void bonusSquareHeld( int bonus )
    {
    }

    @Override
    public void playerScoreHeld( int player )
    {
    }

    @Override
    public void cellSquareHeld( String words )
    {
    }

    @Override
    public void notifyMove( String query )
    {
        subclassOverride( "notifyMove" );
    }

    @Override
    public void notifyTrade( String[] tiles )
    {
        subclassOverride( "notifyTrade" );
    }

    @Override
    public void notifyDupStatus( boolean amHost, String msg )
    {
        subclassOverride( "notifyDupStatus" );
    }

    @Override
    public void userError( int id )
    {
        subclassOverride( "userError" );
    }

    @Override
    public void informMove( int turn, String expl, String words )
    {
        subclassOverride( "informMove" );
    }

    @Override
    public void informUndo()
    {
        subclassOverride( "informUndo" );
    }

    @Override
    public void informNetDict( int lang, String oldName,
                               String newName, String newSum,
                               CurGameInfo.XWPhoniesChoice phonies )
    {
        subclassOverride( "informNetDict" );
    }

    @Override
    public void informMissing( boolean isServer,
                               CommsConnTypeSet connTypes,
                               int nDevices, int nMissingPlayers )
    {
        subclassOverride( "informMissing" );
    }

    // Probably want to cache the fact that the game over notification
    // showed up and then display it next time game's opened.
    @Override
    public void notifyGameOver()
    {
        subclassOverride( "notifyGameOver" );
    }

    @Override
    public void notifyIllegalWords( String dict, String[] words, int turn,
                                    boolean turnLost )
    {
        subclassOverride( "notifyIllegalWords" );
    }

    // These need to go into some sort of chat DB, not dropped.
    @Override
    public void showChat( String msg, int fromIndx, String fromName, int tsSeconds )
    {
        subclassOverride( "showChat" );
    }

    @Override
    public String formatPauseHistory( int pauseTyp, int player, int whenPrev,
                                      int whenCur, String msg )
    {
        subclassOverride( "formatPauseHistory" );
        return null;
    }

    private void subclassOverride( String name ) {
        // DbgUtils.logf( "%s::%s() called", getClass().getName(), name );
    }

}
