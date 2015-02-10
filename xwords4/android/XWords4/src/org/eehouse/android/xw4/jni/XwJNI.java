/* -*- compile-command: "find-and-ant.sh debug install"; -*- */
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

package org.eehouse.android.xw4.jni;

import android.graphics.Rect;

import org.eehouse.android.xw4.DbgUtils;
import org.eehouse.android.xw4.Utils;
import org.eehouse.android.xw4.jni.CommsAddrRec.CommsConnType;

// Collection of native methods and a bit of state
public class XwJNI {

    private static XwJNI s_JNI = null;
    private static XwJNI getJNI()
    {
        if ( null == s_JNI ) {
            s_JNI = new XwJNI();
        }
        return s_JNI;
    }

    private int m_ptr;
    private XwJNI()
    {
        m_ptr = initGlobals();
    }

    public static void cleanGlobals()
    {
        synchronized( XwJNI.class ) { // let's be safe here
            XwJNI jni = getJNI();
            cleanGlobals( jni.m_ptr );
            jni.m_ptr = 0;
        }
    }

    // @Override
    public void finalize() throws java.lang.Throwable
    {
        cleanGlobals( m_ptr );
        super.finalize();
    }

    // This needs to be called before the first attempt to use the
    // jni.
    static {
        System.loadLibrary("xwjni");
    }
    
    /* XW_TrayVisState enum */
    public static final int TRAY_HIDDEN = 0;
    public static final int TRAY_REVERSED = 1;
    public static final int TRAY_REVEALED = 2;

    // Methods not part of the common interface but necessitated by
    // how java/jni work (or perhaps my limited understanding of it.)

    // callback into jni from java when timer set here fires.
    public static native boolean timerFired( int gamePtr, int why, 
                                             int when, int handle );

    // Stateless methods
    public static native byte[] gi_to_stream( CurGameInfo gi );
    public static native void gi_from_stream( CurGameInfo gi, byte[] stream );
    public static native void comms_getInitialAddr( CommsAddrRec addr,
                                                    String relayHost,
                                                    int relayPort );
    public static native String comms_getUUID();

    // Game methods
    public static int initJNI( long rowid )
    {
        int seed = Utils.nextRandomInt();
        String tag = String.format( "%d", rowid );
        return initJNI( getJNI().m_ptr, seed, tag );
    }

    public static int initJNI()
    {
        return initJNI( 0 );
    }

    // hack to allow cleanup of env owned by thread that doesn't open game
    public static void threadDone()
    {
        envDone( getJNI().m_ptr );
    }

    public static native void game_makeNewGame( int gamePtr,
                                                CurGameInfo gi, 
                                                UtilCtxt util,
                                                JNIUtils jniu,
                                                DrawCtx draw, CommonPrefs cp, 
                                                TransportProcs procs, 
                                                String[] dictNames,
                                                byte[][] dictBytes, 
                                                String[] dictPaths, 
                                                String langName );

    public static native boolean game_makeFromStream( int gamePtr,
                                                      byte[] stream, 
                                                      CurGameInfo gi, 
                                                      String[] dictNames,
                                                      byte[][] dictBytes, 
                                                      String[] dictPaths, 
                                                      String langName,
                                                      UtilCtxt util, 
                                                      JNIUtils jniu,
                                                      DrawCtx draw,
                                                      CommonPrefs cp,
                                                      TransportProcs procs );

    // leave out options params for when game won't be rendered or
    // played
    public static void game_makeNewGame( int gamePtr, CurGameInfo gi,
                                         JNIUtils jniu, CommonPrefs cp, 
                                         String[] dictNames, byte[][] dictBytes, 
                                         String[] dictPaths, String langName ) {
        game_makeNewGame( gamePtr, gi, (UtilCtxt)null, jniu,
                          (DrawCtx)null, cp, (TransportProcs)null, 
                          dictNames, dictBytes, dictPaths, langName );
    }

    public static void game_makeNewGame( int gamePtr, CurGameInfo gi,
                                         JNIUtils jniu, CommonPrefs cp, 
                                         TransportProcs procs,
                                         String[] dictNames, byte[][] dictBytes, 
                                         String[] dictPaths, String langName ) {
        game_makeNewGame( gamePtr, gi, (UtilCtxt)null, jniu, (DrawCtx)null, 
                          cp, procs, dictNames, dictBytes, dictPaths, langName );
    }

    public static boolean game_makeFromStream( int gamePtr,
                                               byte[] stream, 
                                               CurGameInfo gi, 
                                               String[] dictNames,
                                               byte[][] dictBytes, 
                                               String[] dictPaths, 
                                               String langName,
                                               JNIUtils jniu,
                                               CommonPrefs cp
                                               ) {
        return game_makeFromStream( gamePtr, stream, gi, dictNames, dictBytes,
                                    dictPaths, langName, (UtilCtxt)null, jniu,
                                    (DrawCtx)null, cp, (TransportProcs)null );
    }

    public static boolean game_makeFromStream( int gamePtr,
                                               byte[] stream, 
                                               CurGameInfo gi, 
                                               String[] dictNames,
                                               byte[][] dictBytes, 
                                               String[] dictPaths, 
                                               String langName,
                                               UtilCtxt util, 
                                               JNIUtils jniu,
                                               CommonPrefs cp,
                                               TransportProcs procs
                                               ) {
        return game_makeFromStream( gamePtr, stream, gi, dictNames, dictBytes,
                                    dictPaths, langName, util, jniu, 
                                    (DrawCtx)null, cp, procs );
    }

    public static native boolean game_receiveMessage( int gamePtr, 
                                                      byte[] stream,
                                                      CommsAddrRec retAddr );
    public static native void game_summarize( int gamePtr, GameSummary summary );
    public static native byte[] game_saveToStream( int gamePtr,
                                                   CurGameInfo gi  );
    public static native void game_saveSucceeded( int gamePtr );
    public static native void game_getGi( int gamePtr, CurGameInfo gi );
    public static native void game_getState( int gamePtr, 
                                             JNIThread.GameStateInfo gsi );
    public static native boolean game_hasComms( int gamePtr );

    // Keep for historical purposes.  But threading issues make it
    // impossible to implement this without a ton of work.
    // public static native boolean game_changeDict( int gamePtr, CurGameInfo gi,
    //                                               String dictName, 
    //                                               byte[] dictBytes, 
    //                                               String dictPath ); 
    public static native void game_dispose( int gamePtr );

    // Board methods
    public static native void board_setDraw( int gamePtr, DrawCtx draw );
    public static native void board_invalAll( int gamePtr );
    public static native boolean board_draw( int gamePtr );

    // Only if COMMON_LAYOUT defined
    public static native void board_figureLayout( int gamePtr, CurGameInfo gi, 
                                                  int left, int top, int width,
                                                  int height, int scorePct, 
                                                  int trayPct, int scoreWidth,
                                                  int fontWidth, int fontHt, 
                                                  boolean squareTiles, 
                                                  BoardDims dims );
    // Only if COMMON_LAYOUT defined
    public static native void board_applyLayout( int gamePtr, BoardDims dims );

    // public static native void board_setPos( int gamePtr, int left, int top,
                                            // int width, int height, 
                                            // int maxCellHt, boolean lefty );
    // public static native void board_setScoreboardLoc( int gamePtr, int left, 
    //                                                   int top, int width, 
    //                                                   int height,
    //                                                   boolean divideHorizontally );
    // public static native void board_setTrayLoc( int gamePtr, int left, 
    //                                             int top, int width, 
    //                                             int height, int minDividerWidth );
    // public static native void board_setTimerLoc( int gamePtr,
    //                                              int timerLeft, int timerTop,
    //                                              int timerWidth, 
    //                                              int timerHeight );
    public static native boolean board_zoom( int gamePtr, int zoomBy, 
                                             boolean[] canZoom );
    public static native boolean board_getActiveRect( int gamePtr, Rect rect,
                                                      int[] dims );

    public static native boolean board_handlePenDown( int gamePtr, 
                                                      int xx, int yy, 
                                                      boolean[] handled );
    public static native boolean board_handlePenMove( int gamePtr, 
                                                      int xx, int yy );
    public static native boolean board_handlePenUp( int gamePtr, 
                                                    int xx, int yy );

    public static native boolean board_juggleTray( int gamePtr );
    public static native int board_getTrayVisState( int gamePtr );
    public static native boolean board_hideTray( int gamePtr );
    public static native boolean board_showTray( int gamePtr );
    public static native boolean board_toggle_showValues( int gamePtr );
    public static native boolean board_commitTurn( int gamePtr );
    public static native boolean board_flip( int gamePtr );
    public static native boolean board_replaceTiles( int gamePtr );
    public static native boolean board_redoReplacedTiles( int gamePtr );
    public static native void board_resetEngine( int gamePtr );
    public static native boolean board_requestHint( int gamePtr, 
                                                    boolean useTileLimits,
                                                    boolean goBackwards,
                                                    boolean[] workRemains );
    public static native boolean board_beginTrade( int gamePtr );
    public static native boolean board_endTrade( int gamePtr );

    public static native String board_formatRemainingTiles( int gamePtr );

    public enum XP_Key {
        XP_KEY_NONE,
        XP_CURSOR_KEY_DOWN,
        XP_CURSOR_KEY_ALTDOWN,
        XP_CURSOR_KEY_RIGHT,
        XP_CURSOR_KEY_ALTRIGHT,
        XP_CURSOR_KEY_UP,
        XP_CURSOR_KEY_ALTUP,
        XP_CURSOR_KEY_LEFT,
        XP_CURSOR_KEY_ALTLEFT,

        XP_CURSOR_KEY_DEL,
        XP_RAISEFOCUS_KEY,
        XP_RETURN_KEY,

        XP_KEY_LAST
    };
    public static native boolean board_handleKey( int gamePtr, XP_Key key, 
                                                  boolean up, boolean[] handled );
    // public static native boolean board_handleKeyDown( XP_Key key, 
    //                                                   boolean[] handled );
    // public static native boolean board_handleKeyRepeat( XP_Key key, 
    //                                                     boolean[] handled );

    // Model
    public static native String model_writeGameHistory( int gamePtr, 
                                                        boolean gameOver );
    public static native int model_getNMoves( int gamePtr );
    public static native int model_getNumTilesInTray( int gamePtr, int player );
    public static native void model_getPlayersLastScore( int gamePtr, 
                                                         int player, 
                                                         LastMoveInfo lmi );
    // Server
    public static native void server_reset( int gamePtr );
    public static native void server_handleUndo( int gamePtr );
    public static native boolean server_do( int gamePtr );
    public static native String server_formatDictCounts( int gamePtr, int nCols );
    public static native boolean server_getGameIsOver( int gamePtr );
    public static native String server_writeFinalScores( int gamePtr );
    public static native boolean server_initClientConnection( int gamePtr );
    public static native void server_endGame( int gamePtr );
    public static native void server_sendChat( int gamePtr, String msg );

    // hybrid to save work
    public static native boolean board_server_prefsChanged( int gamePtr, 
                                                            CommonPrefs cp );

    // Comms
    public static native void comms_start( int gamePtr );
    public static native void comms_stop( int gamePtr );
    public static native void comms_resetSame( int gamePtr );
    public static native void comms_getAddr( int gamePtr, CommsAddrRec addr );
    public static native CommsAddrRec[] comms_getAddrs( int gamePtr );
    public static native void comms_setAddr( int gamePtr, CommsAddrRec addr );
    public static native void comms_resendAll( int gamePtr, boolean force,
                                               boolean andAck );
    public static native void comms_ackAny( int gamePtr );
    public static native void comms_transportFailed( int gamePtr, 
                                                     CommsConnType failed );
    public static native boolean comms_isConnected( int gamePtr );
    public static native String comms_getStats( int gamePtr );

    // Dicts
    public static class DictWrapper {
        private int m_dictPtr;

        public DictWrapper()
        {
            m_dictPtr = 0;
        }

        public DictWrapper( int dictPtr )
        {
            m_dictPtr = dictPtr;
            dict_ref( dictPtr );
        }

        public void release()
        {
            if ( 0 != m_dictPtr ) {
                dict_unref( m_dictPtr );
                m_dictPtr = 0;
            }
        }

        public int getDictPtr()
        {
            return m_dictPtr;
        }

        // @Override
        public void finalize() throws java.lang.Throwable 
        {
            release();
            super.finalize();
        }

    }

    public static native boolean dict_tilesAreSame( int dict1, int dict2 );
    public static native String[] dict_getChars( int dict );
    public static boolean dict_getInfo( byte[] dict, String name,
                                        String path, JNIUtils jniu, 
                                        boolean check, DictInfo info )
    {
        return dict_getInfo( getJNI().m_ptr, dict, name, path, jniu, 
                             check, info );
    }

    public static native int dict_getTileValue( int dictPtr, int tile );

    // Dict iterator
    public final static int MAX_COLS_DICT = 15; // from dictiter.h
    public static int dict_iter_init( byte[] dict, String name,
                                      String path, JNIUtils jniu )
    {
        return dict_iter_init( getJNI().m_ptr, dict, name, path, jniu );
    }
    public static native void dict_iter_setMinMax( int closure,
                                                   int min, int max );
    public static native void dict_iter_destroy( int closure );
    public static native int dict_iter_wordCount( int closure );
    public static native int[] dict_iter_getCounts( int closure );
    public static native String dict_iter_nthWord( int closure, int nn );
    public static native String[] dict_iter_getPrefixes( int closure );
    public static native int[] dict_iter_getIndices( int closure );
    public static native int dict_iter_getStartsWith( int closure, 
                                                      String prefix );
    public static native String dict_iter_getDesc( int closure );

    // base64 stuff since 2.1 doesn't support it in java
    public static native String base64Encode( byte[] in );
    public static native byte[] base64Decode( String in );


    // Private methods -- called only here
    private static native int initGlobals();
    private static native void cleanGlobals( int globals );
    private static native int initJNI( int jniState, int seed, String tag );
    private static native void envDone( int globals );
    private static native void dict_ref( int dictPtr );
    private static native void dict_unref( int dictPtr );
    private static native boolean dict_getInfo( int jniState, byte[] dict, 
                                                String name, String path, 
                                                JNIUtils jniu, boolean check, 
                                                DictInfo info );
    private static native int dict_iter_init( int jniState, byte[] dict, 
                                              String name, String path, 
                                              JNIUtils jniu );
}
