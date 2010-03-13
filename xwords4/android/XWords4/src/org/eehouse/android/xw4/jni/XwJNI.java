/* -*- compile-command: "cd ../../../../../../; ant install"; -*- */

package org.eehouse.android.xw4.jni;


// Collection of native methods
public class XwJNI {
    
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
    public static native void comms_getInitialAddr( CommsAddrRec addr );

    // Game methods
    public static native int initJNI();
    public static native void game_makeNewGame( int gamePtr,
                                                CurGameInfo gi, 
                                                UtilCtxt util,
                                                JNIUtils jniu,
                                                DrawCtx draw, CommonPrefs cp, 
                                                TransportProcs procs, 
                                                byte[] dict );

    public static native boolean game_makeFromStream( int gamePtr,
                                                      byte[] stream, 
                                                      CurGameInfo gi, 
                                                      byte[] dict, 
                                                      UtilCtxt util, 
                                                      JNIUtils jniu,
                                                      DrawCtx draw,
                                                      CommonPrefs cp,
                                                      TransportProcs procs );

    // leave out options params for when game won't be rendered or
    // played
    public static void game_makeNewGame( int gamePtr, CurGameInfo gi,
                                         JNIUtils jniu, CommonPrefs cp, 
                                         byte[] dict ) {
        game_makeNewGame( gamePtr, gi, (UtilCtxt)null, jniu,
                          (DrawCtx)null, cp, (TransportProcs)null, dict );
    }

    public static boolean game_makeFromStream( int gamePtr,
                                               byte[] stream, 
                                               JNIUtils jniu,
                                               CurGameInfo gi, 
                                               byte[] dict, 
                                               CommonPrefs cp ) {
        return game_makeFromStream( gamePtr, stream, gi, dict, (UtilCtxt)null, 
                                    jniu, (DrawCtx)null, cp, 
                                    (TransportProcs)null );
    }

    public static native boolean game_receiveMessage( int gamePtr, 
                                                      byte[] stream );
    public static native byte[] game_saveToStream( int gamePtr,
                                                   CurGameInfo gi );
    public static native boolean game_hasComms( int gamePtr );
    public static native void game_dispose( int gamePtr );

    // Board methods
    public static native void board_invalAll( int gamePtr );
    public static native boolean board_draw( int gamePtr );
    public static native void board_setPos( int gamePtr, int left, int top,
                                            boolean lefty );
    public static native void board_setScale( int gamePtr, int hscale, int vscale );
    public static native void board_setScoreboardLoc( int gamePtr, int left, 
                                                      int top, int width, 
                                                      int height,
                                                      boolean divideHorizontally );
    public static native void board_setTrayLoc( int gamePtr, int left, 
                                                int top, int width, 
                                                int height, int minDividerWidth );
    public static native void board_setTimerLoc( int gamePtr,
                                                 int timerLeft, int timerTop,
                                                 int timerWidth, int timerHeight );

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
    public static native void board_resetEngine( int gamePtr );
    public static native boolean board_requestHint( int gamePtr, 
                                                    boolean useTileLimits,
                                                    boolean[] workRemains );
    public static native boolean board_beginTrade( int gamePtr );

    public static native String board_formatRemainingTiles( int gamePtr );
    public static native boolean board_prefsChanged( int gamePtr, 
                                                     CommonPrefs cp );
    public static native int board_getFocusOwner( int gamePtr );
    public static native boolean board_focusChanged( int gamePtr, int typ );

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

    // Server
    public static native void server_handleUndo( int gamePtr );
    public static native boolean server_do( int gamePtr );
    public static native String server_formatDictCounts( int gamePtr, int nCols );
    public static native boolean server_getGameIsOver( int gamePtr );
    public static native String server_writeFinalScores( int gamePtr );
    public static native void server_initClientConnection( int gamePtr );
    public static native void server_endGame( int gamePtr );

    // Comms
    public static native void comms_start( int gamePtr );
    public static native void comms_getAddr( int gamePtr, CommsAddrRec addr );
    public static native void comms_setAddr( int gamePtr, CommsAddrRec addr );
    public static native void comms_resendAll( int gamePtr );

    // Dicts
    public static native boolean dict_tilesAreSame( int dictPtr1, int dictPtr2 );
    public static native String[] dict_getChars( int dictPtr );
}
