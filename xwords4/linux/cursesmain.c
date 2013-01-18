/* -*- compile-command: "make MEMDEBUG=TRUE -j3"; -*- */
/* 
 * Copyright 2000 - 2011 by Eric House (xwords@eehouse.org).  All rights
 * reserved.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
#ifdef PLATFORM_NCURSES

#include <ncurses.h>
#include <signal.h>
#include <assert.h>
#include <ctype.h>

#include <netdb.h>		/* gethostbyname */
#include <errno.h>
//#include <net/netinet.h>

#include <sys/poll.h>

#include <sys/types.h>
#include <sys/socket.h>
#include <unistd.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netinet/in.h>

#include "linuxmain.h"
#include "linuxutl.h"
#include "linuxdict.h"
#include "cursesmain.h"
#include "cursesask.h"
#include "cursesletterask.h"
#include "linuxbt.h"
#include "model.h"
#include "draw.h"
#include "board.h"
#include "engine.h"
/* #include "compipe.h" */
#include "xwproto.h"
#include "xwstream.h"
#include "xwstate.h"
#include "strutils.h"
#include "server.h"
#include "memstream.h"
#include "util.h"
#include "dbgutil.h"
#include "linuxsms.h"
#include "linuxudp.h"

#ifdef CURSES_SMALL_SCREEN
# define MENU_WINDOW_HEIGHT 1
# define BOARD_OFFSET 0
#else
# define MENU_WINDOW_HEIGHT 5	/* three lines plus borders */
# define BOARD_OFFSET 1
#endif

#ifndef CURSES_CELL_HT
# define CURSES_CELL_HT 1
#endif
#ifndef CURSES_CELL_WIDTH
# define CURSES_CELL_WIDTH 2
#endif

#ifndef CURSES_MAX_HEIGHT
# define CURSES_MAX_HEIGHT 40
#endif
#ifndef CURSES_MAX_WIDTH
//# define CURSES_MAX_WIDTH 50
# define CURSES_MAX_WIDTH 70
#endif

#define INFINITE_TIMEOUT -1
#define BOARD_SCORE_PADDING 3


typedef XP_Bool (*CursesMenuHandler)(CursesAppGlobals* globals);
typedef struct MenuList {
    CursesMenuHandler handler;
    char* desc;
    char* keyDesc;
    char key;
} MenuList;

static XP_Bool handleQuit( CursesAppGlobals* globals );
static XP_Bool handleResend( CursesAppGlobals* globals );
static XP_Bool handleSpace( CursesAppGlobals* globals );
static XP_Bool handleRet( CursesAppGlobals* globals );
static XP_Bool handleHint( CursesAppGlobals* globals );
#ifdef KEYBOARD_NAV
static XP_Bool handleLeft( CursesAppGlobals* globals );
static XP_Bool handleRight( CursesAppGlobals* globals );
static XP_Bool handleUp( CursesAppGlobals* globals );
static XP_Bool handleDown( CursesAppGlobals* globals );
#endif
static XP_Bool handleCommit( CursesAppGlobals* globals );
static XP_Bool handleFlip( CursesAppGlobals* globals );
static XP_Bool handleToggleValues( CursesAppGlobals* globals );
static XP_Bool handleBackspace( CursesAppGlobals* globals );
static XP_Bool handleUndo( CursesAppGlobals* globals );
static XP_Bool handleReplace( CursesAppGlobals* globals );
static XP_Bool handleJuggle( CursesAppGlobals* globals );
static XP_Bool handleHide( CursesAppGlobals* globals );
static XP_Bool handleAltLeft( CursesAppGlobals* globals );
static XP_Bool handleAltRight( CursesAppGlobals* globals );
static XP_Bool handleAltUp( CursesAppGlobals* globals );
static XP_Bool handleAltDown( CursesAppGlobals* globals );
#ifdef CURSES_SMALL_SCREEN
static XP_Bool handleRootKeyShow( CursesAppGlobals* globals );
static XP_Bool handleRootKeyHide( CursesAppGlobals* globals );
#endif


const MenuList g_sharedMenuList[] = {
    { handleQuit, "Quit", "Q", 'Q' },
    { handleResend, "Resend", "R", 'R' },
    { handleSpace, "Raise focus", "<spc>", ' ' },
    { handleRet, "Click/tap", "<ret>", '\r' },
    { handleHint, "Hint", "?", '?' },

#ifdef KEYBOARD_NAV
    { handleLeft, "Left", "H", 'H' },
    { handleRight, "Right", "L", 'L' },
    { handleUp, "Up", "J", 'J' },
    { handleDown, "Down", "K", 'K' },
#endif

    { handleCommit, "Commit move", "C", 'C' },
    { handleFlip, "Flip", "F", 'F' },
    { handleToggleValues, "Show values", "V", 'V' },

    { handleBackspace, "Remove from board", "<del>", 8 },
    { handleUndo, "Undo prev", "U", 'U' },
    { handleReplace, "uNdo cur", "N", 'N' },

    { NULL, NULL, NULL, '\0'}
};

const MenuList g_boardMenuList[] = {
    { handleAltLeft,  "Force left", "{", '{' },
    { handleAltRight, "Force right", "}", '}' },
    { handleAltUp,    "Force up", "_", '_' },
    { handleAltDown,  "Force down", "+", '+' },
    { NULL, NULL, NULL, '\0'}
};

const MenuList g_scoreMenuList[] = {
#ifdef KEYBOARD_NAV
#endif
    { NULL, NULL, NULL, '\0'}
};

const MenuList g_trayMenuList[] = {
    { handleJuggle, "Juggle", "G", 'G' },
    { handleHide, "[un]hIde", "I", 'I' },
    { handleAltLeft, "Divider left", "{", '{' },
    { handleAltRight, "Divider right", "}", '}' },

    { NULL, NULL, NULL, '\0'}
};

#ifdef CURSES_SMALL_SCREEN
const MenuList g_rootMenuListShow[] = {
    { handleRootKeyShow,  "Press . for menu", "", '.' },
    { NULL, NULL, NULL, '\0'}
};

const MenuList g_rootMenuListHide[] = {
    { handleRootKeyHide,  "Clear menu", ".", '.' },
    { NULL, NULL, NULL, '\0'}
};
#endif


static CursesAppGlobals g_globals;	/* must be global b/c of SIGWINCH_handler */

#ifdef KEYBOARD_NAV
static void changeMenuForFocus( CursesAppGlobals* globals, 
                                BoardObjectType obj );
static XP_Bool handleLeft( CursesAppGlobals* globals );
static XP_Bool handleRight( CursesAppGlobals* globals );
static XP_Bool handleUp( CursesAppGlobals* globals );
static XP_Bool handleDown( CursesAppGlobals* globals );
static XP_Bool handleFocusKey( CursesAppGlobals* globals, XP_Key key );
#else 
# define handleFocusKey( g, k ) XP_FALSE
#endif
static void countMenuLines( const MenuList** menuLists, int maxX, int padding,
                            int* nLinesP, int* nColsP );
static void drawMenuFromList( WINDOW* win, const MenuList** menuLists,
                              int nLines, int padding );
static CursesMenuHandler getHandlerForKey( const MenuList* list, char ch );


#ifdef MEM_DEBUG
# define MEMPOOL cGlobals->util->mpool,
#else
# define MEMPOOL
#endif

/* extern int errno; */

static void
cursesUserError( CursesAppGlobals* globals, const char* format, ... )
{
    char buf[512];
    va_list ap;

    va_start( ap, format );

    vsprintf( buf, format, ap );

    (void)cursesask( globals, buf, 1, "OK" );

    va_end(ap);
} /* cursesUserError */

static XP_S16 
curses_util_userPickTileBlank( XW_UtilCtxt* uc, XP_U16 playerNum, 
                               const XP_UCHAR** texts, XP_U16 nTiles )
{
    CursesAppGlobals* globals = (CursesAppGlobals*)uc->closure;
    char query[128];
    XP_S16 index;
    char* playerName = globals->cGlobals.gi.players[playerNum].name;

    snprintf( query, sizeof(query), 
              "Pick tile for %s! (Tab or type letter to select "
              "then hit <cr>.)", playerName );

    index = curses_askLetter( globals, query, texts, nTiles );
    return index;
} /* util_userPickTile */

static XP_S16 
curses_util_userPickTileTray( XW_UtilCtxt* uc, const PickInfo* XP_UNUSED(pi), 
                              XP_U16 playerNum, const XP_UCHAR** texts, 
                              XP_U16 nTiles )
{
    CursesAppGlobals* globals = (CursesAppGlobals*)uc->closure;
    char query[128];
    XP_S16 index;
    char* playerName = globals->cGlobals.gi.players[playerNum].name;

    snprintf( query, sizeof(query), 
              "Pick tile for %s! (Tab or type letter to select "
              "then hit <cr>.)", playerName );

    index = curses_askLetter( globals, query, texts, nTiles );
    return index;
} /* util_userPickTile */

static void
curses_util_userError( XW_UtilCtxt* uc, UtilErrID id )
{
    CursesAppGlobals* globals = (CursesAppGlobals*)uc->closure;
    XP_Bool silent;
    const XP_UCHAR* message = linux_getErrString( id, &silent );

    if ( silent ) {
        XP_LOGF( "silent userError: %s", message );
    } else {
        cursesUserError( globals, message );
    }
} /* curses_util_userError */

static XP_Bool
curses_util_userQuery( XW_UtilCtxt* uc, UtilQueryID id, XWStreamCtxt* stream )
{
    CursesAppGlobals* globals;
    char* question;
    char* answers[3] = {NULL};
    short numAnswers = 0;
    XP_Bool freeMe = XP_FALSE;
    XP_Bool result;
    XP_U16 okIndex = 1;

    switch( id ) {
    case QUERY_COMMIT_TURN:
        question = strFromStream( stream );
        freeMe = XP_TRUE;
        answers[numAnswers++] = "Cancel";
        answers[numAnswers++] = "Ok";
        break;
    case QUERY_ROBOT_TRADE:
        question = strFromStream( stream );
        freeMe = XP_TRUE;
        answers[numAnswers++] = "Ok";
        okIndex = 0;
        break;
        
    default:
        XP_ASSERT( 0 );
        return 0;
    }
    globals = (CursesAppGlobals*)uc->closure;
    result = cursesask( globals, question, numAnswers, 
                        answers[0], answers[1], answers[2] ) == okIndex;

    if ( freeMe ) {
        free( question );
    }

    return result;
} /* curses_util_userQuery */

static XP_Bool
curses_util_confirmTrade( XW_UtilCtxt* uc, const XP_UCHAR** tiles,
                          XP_U16 nTiles )
{
    CursesAppGlobals* globals = (CursesAppGlobals*)uc->closure;
    char question[256];
    formatConfirmTrade( tiles, nTiles, question, sizeof(question) );
    return 1 == cursesask( globals, question, 2, "Cancel", "Ok" );
}

static void
curses_util_trayHiddenChange( XW_UtilCtxt* XP_UNUSED(uc), 
                              XW_TrayVisState XP_UNUSED(state),
                              XP_U16 XP_UNUSED(nVisibleRows) )
{
    /* nothing to do if we don't have a scrollbar */
} /* curses_util_trayHiddenChange */

static void
cursesShowFinalScores( CursesAppGlobals* globals )
{
    XWStreamCtxt* stream;
    XP_UCHAR* text;

    stream = mem_stream_make( MPPARM(globals->cGlobals.util->mpool)
                              globals->cGlobals.params->vtMgr,
                              globals, CHANNEL_NONE, NULL );
    server_writeFinalScores( globals->cGlobals.game.server, stream );

    text = strFromStream( stream );

    (void)cursesask( globals, text, 1, "Ok" );

    free( text );
    stream_destroy( stream );
} /* cursesShowFinalScores */

static void
curses_util_informMove( XW_UtilCtxt* uc, XWStreamCtxt* expl, 
                        XWStreamCtxt* XP_UNUSED(words))
{
    CursesAppGlobals* globals = (CursesAppGlobals*)uc->closure;
    char* question = strFromStream( expl );
    (void)cursesask( globals, question, 1, "Ok" );
    free( question );
}

static void
curses_util_informUndo( XW_UtilCtxt* XP_UNUSED(uc))
{
    LOG_FUNC();
}

static void
curses_util_notifyGameOver( XW_UtilCtxt* uc, XP_S16 quitter )
{
    CursesAppGlobals* globals = (CursesAppGlobals*)uc->closure;
    board_draw( globals->cGlobals.game.board );

    /* game belongs in cGlobals... */
    if ( globals->cGlobals.params->printHistory ) {    
        catGameHistory( &globals->cGlobals );
    }

    catFinalScores( &globals->cGlobals, quitter );

    if ( globals->cGlobals.params->quitAfter >= 0 ) {
        sleep( globals->cGlobals.params->quitAfter );
        handleQuit( globals );
    } else if ( globals->cGlobals.params->undoWhenDone ) {
        server_handleUndo( globals->cGlobals.game.server, 0 );
    } else if ( !globals->cGlobals.params->skipGameOver ) {
        /* This is modal.  Don't show if quitting */
        cursesShowFinalScores( globals );
    }
} /* curses_util_notifyGameOver */

static void
curses_util_informNetDict( XW_UtilCtxt* uc, XP_LangCode XP_UNUSED(lang),
                           const XP_UCHAR* XP_UNUSED_DBG(oldName),
                           const XP_UCHAR* XP_UNUSED_DBG(newName), 
                           const XP_UCHAR* XP_UNUSED_DBG(newSum),
                           XWPhoniesChoice phoniesAction )
{
    XP_USE(uc);
    XP_USE(phoniesAction);
    XP_LOGF( "%s: %s => %s (cksum: %s)", __func__, oldName, newName, newSum );
}

static void
curses_util_setIsServer( XW_UtilCtxt* uc, XP_Bool isServer )
{
    LOG_FUNC();
    CommonGlobals* cGlobals = (CommonGlobals*)uc->closure;
    linuxSetIsServer( cGlobals, isServer );
}

#ifdef XWFEATURE_HILITECELL
static XP_Bool
curses_util_hiliteCell( XW_UtilCtxt* uc, 
                        XP_U16 XP_UNUSED(col), XP_U16 XP_UNUSED(row) )
{
    CursesAppGlobals* globals = (CursesAppGlobals*)uc->closure;
    if ( globals->cGlobals.params->sleepOnAnchor ) {
        usleep( 10000 );
    }
    return XP_TRUE;
} /* curses_util_hiliteCell */
#endif

static XP_Bool
curses_util_engineProgressCallback( XW_UtilCtxt* XP_UNUSED(uc) )
{
    return XP_TRUE;
} /* curses_util_engineProgressCallback */

#ifdef USE_GLIBLOOP
static gboolean
timerFired( gpointer data )
{
    TimerInfo* ti = (TimerInfo*)data;
    CommonGlobals* globals = ti->globals;
    XWTimerReason why = ti - globals->timerInfo;
    if ( linuxFireTimer( globals, why ) ) {
        board_draw( globals->game.board );
    }

    return FALSE;
}
#endif

static void
curses_util_setTimer( XW_UtilCtxt* uc, XWTimerReason why, XP_U16 when,
                      XWTimerProc proc, void* closure )
{
    CursesAppGlobals* globals = (CursesAppGlobals*)uc->closure;
    TimerInfo* ti = &globals->cGlobals.timerInfo[why];

    ti->proc = proc;
    ti->closure = closure;

#ifdef USE_GLIBLOOP
    ti->globals = &globals->cGlobals;
    (void)g_timeout_add_seconds( when, timerFired, ti );
#else
    ti->when = util_getCurSeconds(uc) + when;
#endif
} /* curses_util_setTimer */

static void
curses_util_clearTimer( XW_UtilCtxt* uc, XWTimerReason why )
{
    CursesAppGlobals* globals = (CursesAppGlobals*)uc->closure;
    globals->cGlobals.timerInfo[why].proc = NULL;
}

#ifdef USE_GLIBLOOP
static gboolean
onetime_idle( gpointer data )
{
    CursesAppGlobals* globals = (CursesAppGlobals*)data;
    if ( server_do( globals->cGlobals.game.server ) ) {
        if ( !!globals->cGlobals.game.board ) {
            board_draw( globals->cGlobals.game.board );
        }
        saveGame( &globals->cGlobals );
    }
    return FALSE;
}
#endif 

static void
curses_util_requestTime( XW_UtilCtxt* uc ) 
{
    CursesAppGlobals* globals = (CursesAppGlobals*)uc->closure;
#ifdef USE_GLIBLOOP
# if 0
    (void)g_idle_add( onetime_idle, globals );
# else
    (void)g_timeout_add( 1,// interval,
                         onetime_idle, globals );
# endif
#else
    /* I've created a pipe whose read-only end is plugged into the array of
       fds that my event loop polls so that I can write to it to simulate
       post-event on a more familiar system.  It works, so no complaints! */
    if ( 1 != write( globals->timepipe[1], "!", 1 ) ) {
        XP_ASSERT(0);
    }
#endif
} /* curses_util_requestTime */

static void
initCurses( CursesAppGlobals* globals )
{
    WINDOW* mainWin;
    WINDOW* menuWin;
    WINDOW* boardWin;

    int width, height;

    /* ncurses man page says most apps want this sequence  */
    mainWin = initscr(); 
    cbreak(); 
    noecho();
    nonl();
    intrflush(stdscr, FALSE);
    keypad(stdscr, TRUE);       /* effects wgetch only? */

    getmaxyx(mainWin, height, width );
    XP_LOGF( "getmaxyx->w:%d; h:%d", width, height );
    if ( height > CURSES_MAX_HEIGHT ) {
        height = CURSES_MAX_HEIGHT;
    }
    if ( width > CURSES_MAX_WIDTH ) {
        width = CURSES_MAX_WIDTH;
    }

    globals->statusLine = height - MENU_WINDOW_HEIGHT - 1;
    menuWin = newwin( MENU_WINDOW_HEIGHT, width, 
                      height-MENU_WINDOW_HEIGHT, 0 );
    nodelay(menuWin, 1);		/* don't block on getch */
    boardWin = newwin( height-MENU_WINDOW_HEIGHT, width, 0, 0 );

    globals->menuWin = menuWin;
    globals->boardWin = boardWin;
    globals->mainWin = mainWin;
} /* initCurses */

#if 0
static void
showStatus( CursesAppGlobals* globals )
{
    char* str;

    switch ( globals->state ) {
    case XW_SERVER_WAITING_CLIENT_SIGNON:
	str = "Waiting for client[s] to connnect";
	break;
    case XW_SERVER_READY_TO_PLAY:
	str = "It's somebody's move";
	break;
    default:
	str = "unknown state";
    }

    
    standout();
    mvaddstr( globals->statusLine, 0, str );
/*     clrtoeol();     */
    standend();

    refresh();
} /* showStatus */
#endif

static XP_Bool
handleQuit( CursesAppGlobals* globals )
{
#ifdef USE_GLIBLOOP
    g_main_loop_quit( globals->loop );
#else
    globals->timeToExit = XP_TRUE;
#endif
    return XP_TRUE;
} /* handleQuit */

static XP_Bool
handleResend( CursesAppGlobals* globals )
{
    if ( !!globals->cGlobals.game.comms ) {
        comms_resendAll( globals->cGlobals.game.comms, XP_TRUE );
    }
    return XP_TRUE;
}

#ifdef KEYBOARD_NAV
static void
checkAssignFocus( BoardCtxt* board )
{
    if ( OBJ_NONE == board_getFocusOwner(board) ) {
        board_focusChanged( board, OBJ_BOARD, XP_TRUE );
    }
}
#else
# define checkAssignFocus(b)
#endif

static XP_Bool
handleSpace( CursesAppGlobals* globals )
{
    XP_Bool handled;
    checkAssignFocus( globals->cGlobals.game.board );

    globals->doDraw = board_handleKey( globals->cGlobals.game.board, 
                                       XP_RAISEFOCUS_KEY, &handled );
    return XP_TRUE;
} /* handleSpace */

static XP_Bool
handleRet( CursesAppGlobals* globals )
{
    XP_Bool handled;
    globals->doDraw = board_handleKey( globals->cGlobals.game.board, 
                                       XP_RETURN_KEY, &handled );
    return XP_TRUE;
} /* handleRet */

static XP_Bool
handleHint( CursesAppGlobals* globals )
{
    XP_Bool redo;
    globals->doDraw = board_requestHint( globals->cGlobals.game.board, 
#ifdef XWFEATURE_SEARCHLIMIT
                                         XP_FALSE,
#endif
                                         XP_FALSE, &redo );
    return XP_TRUE;
} /* handleHint */

static XP_Bool
handleCommit( CursesAppGlobals* globals )
{
    globals->doDraw = board_commitTurn( globals->cGlobals.game.board );
    return XP_TRUE;
} /* handleCommit */

static XP_Bool
handleJuggle( CursesAppGlobals* globals )
{
    globals->doDraw = board_juggleTray( globals->cGlobals.game.board );
    return XP_TRUE;
} /* handleJuggle */

static XP_Bool
handleHide( CursesAppGlobals* globals )
{
    XW_TrayVisState curState = 
        board_getTrayVisState( globals->cGlobals.game.board );

    if ( curState == TRAY_REVEALED ) {
        globals->doDraw = board_hideTray( globals->cGlobals.game.board );
    } else {
        globals->doDraw = board_showTray( globals->cGlobals.game.board );
    }

    return XP_TRUE;
} /* handleJuggle */

#ifdef CURSES_SMALL_SCREEN
static XP_Bool
handleRootKeyShow( CursesAppGlobals* globals )
{
    WINDOW* win;
    MenuList* lists[] = { g_sharedMenuList, globals->menuList, 
                          g_rootMenuListHide, NULL };
    int winMaxY, winMaxX;
    
    wclear( globals->menuWin );
    wrefresh( globals->menuWin ); 

    getmaxyx( globals->boardWin, winMaxY, winMaxX );

    int border = 2;
    int width = winMaxX - (border * 2);
    int padding = 1;            /* for the box */
    int nLines, nCols;
    countMenuLines( lists, width, padding, &nLines, &nCols );

    if ( width > nCols ) {
        width = nCols;
    }

    win = newwin( nLines+(padding*2), width+(padding*2), 
                  ((winMaxY-nLines-padding-padding)/2), (winMaxX-width)/2 );
    wclear( win );
    box( win, '|', '-');

    drawMenuFromList( win, lists, nLines, padding );
    wrefresh( win );

    CursesMenuHandler handler = NULL;
    while ( !handler ) {
        int ch = fgetc( stdin );

        int i;
        for ( i = 0; !!lists[i]; ++i ) {
            handler = getHandlerForKey( lists[i], ch );
            if ( !!handler ) {
                break;
            }
        }
    }

    delwin( win );

    touchwin( globals->boardWin );
    wrefresh( globals->boardWin );
    MenuList* ml[] = { g_rootMenuListShow, NULL };
    drawMenuFromList( globals->menuWin, ml, 1, 0 );
    wrefresh( globals->menuWin ); 

    return handler != NULL && (*handler)(globals);
} /* handleRootKeyShow */

static XP_Bool
handleRootKeyHide( CursesAppGlobals* globals )
{
    globals->doDraw = XP_TRUE;
    return XP_TRUE;
}
#endif

static XP_Bool
handleAltLeft( CursesAppGlobals* XP_UNUSED_KEYBOARD_NAV(globals) )
{
    return handleFocusKey( globals, XP_CURSOR_KEY_ALTLEFT );
}

static XP_Bool
handleAltRight( CursesAppGlobals* XP_UNUSED_KEYBOARD_NAV(globals) )
{
    return handleFocusKey( globals, XP_CURSOR_KEY_ALTRIGHT );
}

static XP_Bool
handleAltUp( CursesAppGlobals* XP_UNUSED_KEYBOARD_NAV(globals) )
{
    return handleFocusKey( globals, XP_CURSOR_KEY_ALTUP );
}

static XP_Bool
handleAltDown( CursesAppGlobals* XP_UNUSED_KEYBOARD_NAV(globals) )
{
    return handleFocusKey( globals, XP_CURSOR_KEY_ALTDOWN );
}

static XP_Bool
handleFlip( CursesAppGlobals* globals )
{
    globals->doDraw = board_flip( globals->cGlobals.game.board );
    return XP_TRUE;
} /* handleFlip */

static XP_Bool
handleToggleValues( CursesAppGlobals* globals )
{
    globals->doDraw = board_toggle_showValues( globals->cGlobals.game.board );
    return XP_TRUE;
} /* handleToggleValues */

static XP_Bool
handleBackspace( CursesAppGlobals* globals )
{
    XP_Bool handled;
    globals->doDraw = board_handleKey( globals->cGlobals.game.board,
                                       XP_CURSOR_KEY_DEL, &handled );
    return XP_TRUE;
} /* handleBackspace */

static XP_Bool
handleUndo( CursesAppGlobals* globals )
{
    globals->doDraw = server_handleUndo( globals->cGlobals.game.server, 0 );
    return XP_TRUE;
} /* handleUndo */

static XP_Bool
handleReplace( CursesAppGlobals* globals )
{
    globals->doDraw = board_replaceTiles( globals->cGlobals.game.board );
    return XP_TRUE;
} /* handleReplace */

#ifdef KEYBOARD_NAV
static XP_Bool
handleFocusKey( CursesAppGlobals* globals, XP_Key key )
{
    XP_Bool handled;
    XP_Bool draw;

    checkAssignFocus( globals->cGlobals.game.board );

    draw = board_handleKey( globals->cGlobals.game.board, key, &handled );
    if ( !handled ) {
        BoardObjectType nxt;
        BoardObjectType order[] = { OBJ_BOARD, OBJ_SCORE, OBJ_TRAY };
        draw = linShiftFocus( &globals->cGlobals, key, order, &nxt ) || draw;
        if ( nxt != OBJ_NONE ) {
            changeMenuForFocus( globals, nxt );
        }
    }

    globals->doDraw = draw || globals->doDraw;
    return XP_TRUE;
} /* handleFocusKey */

static XP_Bool
handleLeft( CursesAppGlobals* globals )
{
    return handleFocusKey( globals, XP_CURSOR_KEY_LEFT );
} /* handleLeft */

static XP_Bool
handleRight( CursesAppGlobals* globals )
{
    return handleFocusKey( globals, XP_CURSOR_KEY_RIGHT );
} /* handleRight */

static XP_Bool
handleUp( CursesAppGlobals* globals )
{
    return handleFocusKey( globals, XP_CURSOR_KEY_UP );
} /* handleUp */

static XP_Bool
handleDown( CursesAppGlobals* globals )
{
    return handleFocusKey( globals, XP_CURSOR_KEY_DOWN );
} /* handleDown */
#endif

static void
fmtMenuItem( const MenuList* item, char* buf, int maxLen )
{
    snprintf( buf, maxLen, "%s %s", item->keyDesc, item->desc );
}


static void
countMenuLines( const MenuList** menuLists, int maxX, int padding,
                int* nLinesP, int* nColsP )
{
    int nCols = 0;
    /* The menu space should be wider rather than taller, but line up by
       column.  So we want to use as many columns as possible to minimize the
       number of lines.  So start with one line and lay out.  If that doesn't
       fit, try two.  Given the number of lines, get the max width of each
       column.
    */

    maxX -= padding * 2;        /* on left and right side */

    int nLines;
    for ( nLines = 1; ; ++nLines ) {
        short line = 0;
        XP_Bool tooFewLines = XP_FALSE;
        int maxThisCol = 0;
        int i;
        nCols = 0;

        for ( i = 0; !tooFewLines && (NULL != menuLists[i]); ++i ) {
            const MenuList* entry;
            for ( entry = menuLists[i]; !tooFewLines && !!entry->handler; 
                  ++entry ) {
                int width;
                char buf[32];

                /* time to switch to new column? */
                if ( line == nLines ) {
                    nCols += maxThisCol;
                    if ( nCols > maxX ) {
                        tooFewLines = XP_TRUE;
                        break;
                    }
                    maxThisCol = 0;
                    line = 0;
                }

                fmtMenuItem( entry, buf, sizeof(buf) );
                width = strlen(buf) + 2; /* padding */

                if ( maxThisCol < width ) {
                    maxThisCol = width;
                }

                ++line;
            }
        }
        /* If we get here without running out of space, we're done */
        nCols += maxThisCol;
        if ( !tooFewLines && (nCols < maxX) ) {
            break;
        }
    }
    
    *nColsP = nCols;
    *nLinesP = nLines;
} /* countMenuLines */

static void
drawMenuFromList( WINDOW* win, const MenuList** menuLists,
                  int nLines, int padding )
{
    short line = 0, col, i;
    int winMaxY, winMaxX;

    getmaxyx( win, winMaxY, winMaxX );
    XP_USE(winMaxY);

    int maxColWidth = 0;
    if ( 0 == nLines ) {
        int ignore;
        countMenuLines( menuLists, winMaxX, padding, &nLines, &ignore );
    }
    col = 0;

    for ( i = 0; NULL != menuLists[i]; ++i ) {
        const MenuList* entry;
        for ( entry = menuLists[i]; !!entry->handler; ++entry ) {
            char buf[32];

            fmtMenuItem( entry, buf, sizeof(buf) );

            mvwaddstr( win, line+padding, col+padding, buf );

            int width = strlen(buf) + 2;
            if ( width > maxColWidth ) {
                maxColWidth = width;
            }

            if ( ++line == nLines ) {
                line = 0;
                col += maxColWidth;
                maxColWidth = 0;
            }

        }
    }
} /* drawMenuFromList */

static void 
SIGWINCH_handler( int signal )
{
    int x, y;

    assert( signal == SIGWINCH );

    endwin(); 

/*     (*globals.drawMenu)( &globals );  */

    getmaxyx( stdscr, y, x );
    wresize( g_globals.mainWin, y-MENU_WINDOW_HEIGHT, x );

    board_draw( g_globals.cGlobals.game.board );
} /* SIGWINCH_handler */

static void 
SIGINTTERM_handler( int XP_UNUSED(signal) )
{
    (void)handleQuit( &g_globals );
}

static void
cursesListenOnSocket( CursesAppGlobals* globals, int newSock
#ifdef USE_GLIBLOOP
                      , GIOFunc func 
#endif
)
{
#ifdef USE_GLIBLOOP
    GIOChannel* channel = g_io_channel_unix_new( newSock );
    guint watch = g_io_add_watch( channel, G_IO_IN | G_IO_ERR,
                                  func, globals );

    SourceData* data = g_malloc( sizeof(*data) );
    data->channel = channel;
    data->watch = watch;
    globals->sources = g_list_append( globals->sources, data );

#else
    XP_ASSERT( globals->fdCount+1 < FD_MAX );

    XP_WARNF( "%s: setting fd[%d] to %d", __func__, globals->fdCount, newSock );
    globals->fdArray[globals->fdCount].fd = newSock;
    globals->fdArray[globals->fdCount].events = POLLIN | POLLERR | POLLHUP;

    ++globals->fdCount;
    XP_LOGF( "%s: there are now %d sources to poll",
             __func__, globals->fdCount );
#endif
} /* cursesListenOnSocket */

static void
curses_stop_listening( CursesAppGlobals* globals, int sock )
{
#ifdef USE_GLIBLOOP
    GList* sources = globals->sources;
    while ( !!sources ) {
        SourceData* data = sources->data;
        gint fd = g_io_channel_unix_get_fd( data->channel );
        if ( fd == sock ) {
            g_io_channel_unref( data->channel );
            g_free( data );
            globals->sources = g_list_remove_link( globals->sources, sources );
            break;
        }
        sources = sources->next;
    }
    
#else
    int count = globals->fdCount;
    int i;
    bool found = false;

    for ( i = 0; i < count; ++i ) {
        if ( globals->fdArray[i].fd == sock ) {
            found = true;
        } else if ( found ) {
            globals->fdArray[i-1].fd = globals->fdArray[i].fd;
        }
    }

    assert( found );
    --globals->fdCount;
#endif
} /* curses_stop_listening */

#ifdef USE_GLIBLOOP
static gboolean
data_socket_proc( GIOChannel* source, GIOCondition condition, gpointer data )
{
    if ( 0 != (G_IO_IN & condition) ) {
        CursesAppGlobals* globals = (CursesAppGlobals*)data;
        int fd = g_io_channel_unix_get_fd( source );
        unsigned char buf[256];
        int nBytes;
        CommsAddrRec addrRec;
        CommsAddrRec* addrp = NULL;

        /* It's a normal data socket */
        switch ( globals->cGlobals.params->conType ) {
#ifdef XWFEATURE_RELAY
        case COMMS_CONN_RELAY:
            nBytes = linux_relay_receive( &globals->cGlobals, buf, 
                                          sizeof(buf) );
            break;
#endif
#ifdef XWFEATURE_SMS
        case COMMS_CONN_SMS:
            addrp = &addrRec;
            nBytes = linux_sms_receive( &globals->cGlobals, fd,
                                        buf, sizeof(buf), addrp );
            break;
#endif
#ifdef XWFEATURE_BLUETOOTH
        case COMMS_CONN_BT:
            nBytes = linux_bt_receive( fd, buf, sizeof(buf) );
            break;
#endif
        default:
            XP_ASSERT( 0 ); /* fired */
        }

        if ( nBytes != -1 ) {
            XWStreamCtxt* inboundS;
            XP_Bool redraw = XP_FALSE;

            inboundS = stream_from_msgbuf( &globals->cGlobals, buf, nBytes );
            if ( !!inboundS ) {
                if ( comms_checkIncomingStream( globals->cGlobals.game.comms,
                                                inboundS, addrp ) ) {
                    redraw = server_receiveMessage( globals->cGlobals.game.server, 
                                                    inboundS );
                }
                stream_destroy( inboundS );
            }
                
            /* if there's something to draw resulting from the
               message, we need to give the main loop time to reflect
               that on the screen before giving the server another
               shot.  So just call the idle proc. */
            if ( redraw ) {
                curses_util_requestTime( globals->cGlobals.util );
            }
        }
    }
    return TRUE;
} /* data_socket_proc */
#endif

static void
curses_socket_changed( void* closure, int oldSock, int newSock,
                       void** XP_UNUSED(storage) )
{
    CursesAppGlobals* globals = (CursesAppGlobals*)closure;
    if ( oldSock != -1 ) {
        curses_stop_listening( globals, oldSock );
    }
    if ( newSock != -1 ) {
        cursesListenOnSocket( globals, newSock
#ifdef USE_GLIBLOOP
                              , data_socket_proc 
#endif
                              );
    }

#ifdef XWFEATURE_RELAY
    globals->cGlobals.socket = newSock;
#endif
} /* curses_socket_changed */

#ifdef USE_GLIBLOOP
static gboolean
fire_acceptor( GIOChannel* source, GIOCondition condition, gpointer data )
{
    if ( 0 != (G_IO_IN & condition) ) {
        CursesAppGlobals* globals = (CursesAppGlobals*)data;

        int fd = g_io_channel_unix_get_fd( source );
        XP_ASSERT( fd == globals->csInfo.server.serverSocket );
        (*globals->cGlobals.acceptor)( fd, globals );
    }
    return TRUE;
}
#endif

static void
curses_socket_acceptor( int listener, Acceptor func, CommonGlobals* cGlobals,
                        void** XP_UNUSED(storage) )
{
    if ( -1 == listener ) {
        XP_LOGF( "%s: removal of listener not implemented!!!!!", __func__ );
    } else {
        CursesAppGlobals* globals = (CursesAppGlobals*)cGlobals;
        XP_ASSERT( !cGlobals->acceptor || (func == cGlobals->acceptor) );
        cGlobals->acceptor = func;
        globals->csInfo.server.serverSocket = listener;
        cursesListenOnSocket( globals, listener
#ifdef USE_GLIBLOOP
                              , fire_acceptor
#endif
                              );
    }
}

#ifndef USE_GLIBLOOP
#ifdef XWFEATURE_RELAY
static int
figureTimeout( CursesAppGlobals* globals )
{
    int result = INFINITE_TIMEOUT;
    XWTimerReason ii;
    XP_U32 now = util_getCurSeconds( globals->cGlobals.params->util );

    now *= 1000;

    for ( ii = 0; ii < NUM_TIMERS_PLUS_ONE; ++ii ) {
        TimerInfo* tip = &globals->cGlobals.timerInfo[ii];
        if ( !!tip->proc ) {
            XP_U32 then = tip->when * 1000;
            if ( now >= then ) {
                result = 0;
                break;          /* if one's immediate, we're done */
            } else {
                then -= now;
                if ( result == -1 || then < result ) {
                    result = then;
                }
            }
        }
    }
    return result;
} /* figureTimeout */
#else 
# define figureTimeout(g) INFINITE_TIMEOUT
#endif

#ifdef XWFEATURE_RELAY
static void
fireCursesTimer( CursesAppGlobals* globals )
{
    XWTimerReason ii;
    TimerInfo* smallestTip = NULL;

    for ( ii = 0; ii < NUM_TIMERS_PLUS_ONE; ++ii ) {
        TimerInfo* tip = &globals->cGlobals.timerInfo[ii];
        if ( !!tip->proc ) { 
            if ( !smallestTip ) {
                smallestTip = tip;
            } else if ( tip->when < smallestTip->when ) {
                smallestTip = tip;
            }
        }
    }

    if ( !!smallestTip ) {
        XP_U32 now = util_getCurSeconds( globals->cGlobals.params->util ) ;
        if ( now >= smallestTip->when ) {
            if ( linuxFireTimer( &globals->cGlobals, 
                                 smallestTip - globals->cGlobals.timerInfo ) ){
                board_draw( globals->cGlobals.game.board );
            }
        } else {
            XP_LOGF( "skipping timer: now (%ld) < when (%ld)", 
                     now, smallestTip->when );
        }
    }
} /* fireCursesTimer */
#endif
#endif

/* 
 * Ok, so this doesn't block yet.... 
 */
#ifndef USE_GLIBLOOP
static XP_Bool
blocking_gotEvent( CursesAppGlobals* globals, int* ch )
{
    XP_Bool result = XP_FALSE;
    int numEvents, ii;
    short fdIndex;
    XP_Bool redraw = XP_FALSE;

    int timeout = figureTimeout( globals );
    numEvents = poll( globals->fdArray, globals->fdCount, timeout );

    if ( timeout != INFINITE_TIMEOUT && numEvents == 0 ) {
#ifdef XWFEATURE_RELAY
        fireCursesTimer( globals );
#endif
    } else if ( numEvents > 0 ) {
	
        /* stdin first */
        if ( (globals->fdArray[FD_STDIN].revents & POLLIN) != 0 ) {
            int evtCh = wgetch(globals->mainWin);
            XP_LOGF( "%s: got key: %x", __func__, evtCh );
            *ch = evtCh;
            result = XP_TRUE;
            --numEvents;
        }
        if ( (globals->fdArray[FD_STDIN].revents & ~POLLIN ) ) {
            XP_LOGF( "some other events set on stdin" );
        }

        if ( (globals->fdArray[FD_TIMEEVT].revents & POLLIN) != 0 ) {
            char ch;
            if ( 1 != read(globals->fdArray[FD_TIMEEVT].fd, &ch, 1 ) ) {
                XP_ASSERT(0);
            }
        }

        fdIndex = FD_FIRSTSOCKET;

        if ( numEvents > 0 ) {
            if ( (globals->fdArray[fdIndex].revents & ~POLLIN ) ) {
                XP_LOGF( "some other events set on socket %d", 
                         globals->fdArray[fdIndex].fd  );
            }

            if ( (globals->fdArray[fdIndex].revents & POLLIN) != 0 ) {

                --numEvents;

                if ( globals->fdArray[fdIndex].fd 
                     == globals->csInfo.server.serverSocket ) {
                    /* It's the listening socket: call platform's accept()
                       wrapper */
                    (*globals->cGlobals.acceptor)( globals->fdArray[fdIndex].fd, 
                                                   globals );
                } else {
#ifndef XWFEATURE_STANDALONE_ONLY
                    unsigned char buf[256];
                    int nBytes;
                    CommsAddrRec addrRec;
                    CommsAddrRec* addrp = NULL;

                    /* It's a normal data socket */
                    switch ( globals->cGlobals.params->conType ) {
#ifdef XWFEATURE_RELAY
                    case COMMS_CONN_RELAY:
                        nBytes = linux_relay_receive( &globals->cGlobals, buf, 
                                                      sizeof(buf) );
                        break;
#endif
#ifdef XWFEATURE_SMS
                    case COMMS_CONN_SMS:
                        addrp = &addrRec;
                        nBytes = linux_sms_receive( &globals->cGlobals, 
                                                    globals->fdArray[fdIndex].fd,
                                                    buf, sizeof(buf), addrp );
                        break;
#endif
#ifdef XWFEATURE_BLUETOOTH
                    case COMMS_CONN_BT:
                        nBytes = linux_bt_receive( globals->fdArray[fdIndex].fd, 
                                                   buf, sizeof(buf) );
                        break;
#endif
                    default:
                        XP_ASSERT( 0 ); /* fired */
                    }

                    if ( nBytes != -1 ) {
                        XWStreamCtxt* inboundS;
                        redraw = XP_FALSE;

                        inboundS = stream_from_msgbuf( &globals->cGlobals, 
                                                       buf, nBytes );
                        if ( !!inboundS ) {
                            if ( comms_checkIncomingStream(
                                                           globals->cGlobals.game.comms,
                                                           inboundS, addrp ) ) {
                                redraw = server_receiveMessage( 
                                                               globals->cGlobals.game.server, inboundS );
                            }
                            stream_destroy( inboundS );
                        }
                
                        /* if there's something to draw resulting from the
                           message, we need to give the main loop time to reflect
                           that on the screen before giving the server another
                           shot.  So just call the idle proc. */
                        if ( redraw ) {
                            curses_util_requestTime(globals->cGlobals.params->util);
                        }
                    }
#else
                    XP_ASSERT(0);   /* no socket activity in standalone game! */
#endif                          /* #ifndef XWFEATURE_STANDALONE_ONLY */
                }
                ++fdIndex;
            }
        }

        for ( ii = 0; ii < 5; ++ii ) {
            redraw = server_do( globals->cGlobals.game.server, NULL ) || redraw;
        }
        if ( redraw ) {
            /* messages change a lot */
            board_invalAll( globals->cGlobals.game.board );
            board_draw( globals->cGlobals.game.board );
        }
        saveGame( globals->cGlobals );
    }
    return result;
} /* blocking_gotEvent */
#endif

static void
remapKey( int* kp )
{
    /* There's what the manual says I should get, and what I actually do from
     * a funky M$ keyboard....
     */
    int key = *kp;
    switch( key ) {
    case KEY_B2:                /* "center of keypad" */
        key = '\r';
        break;
    case KEY_DOWN:
    case 526:
        key = 'K';
        break;
    case KEY_UP:
    case 523:
        key = 'J';
        break;
    case KEY_LEFT:
    case 524:
        key = 'H';
        break;
    case KEY_RIGHT:
    case 525:
        key = 'L';
        break;
    default:
        if ( key > 0x7F ) {
            XP_LOGF( "%s(%d): no mapping", __func__, key );
        }
        break;
    }
    *kp = key;
} /* remapKey */

static void
drawMenuLargeOrSmall( CursesAppGlobals* globals, const MenuList* menuList )
{
#ifdef CURSES_SMALL_SCREEN
    const MenuList* lists[] = { g_rootMenuListShow, NULL };
#else
    const MenuList* lists[] = { g_sharedMenuList, menuList, NULL };
#endif
    wclear( globals->menuWin );
    drawMenuFromList( globals->menuWin, lists, 0, 0 );
    wrefresh( globals->menuWin );
}

#ifdef KEYBOARD_NAV
static void
changeMenuForFocus( CursesAppGlobals* globals, BoardObjectType focussed )
{
    if ( focussed == OBJ_TRAY ) {
        globals->menuList = g_trayMenuList;
    } else if ( focussed == OBJ_BOARD ) {
        globals->menuList = g_boardMenuList;
    } else if ( focussed == OBJ_SCORE ) {
        globals->menuList = g_scoreMenuList;
    } else {
        XP_ASSERT(0);
    }
    drawMenuLargeOrSmall( globals, globals->menuList );
} /* changeMenuForFocus */
#endif

#if 0
static void
initClientSocket( CursesAppGlobals* globals, char* serverName )
{
    struct hostent* hostinfo;
    hostinfo = gethostbyname( serverName );
    if ( !hostinfo ) {
        userError( globals, "unable to get host info for %s\n", serverName );
    } else {
        char* hostName = inet_ntoa( *(struct in_addr*)hostinfo->h_addr );
        XP_LOGF( "gethostbyname returned %s", hostName );
        globals->csInfo.client.serverAddr = inet_addr(hostName);
        XP_LOGF( "inet_addr returned %lu", 
                 globals->csInfo.client.serverAddr );
    }
} /* initClientSocket */
#endif

static VTableMgr*
curses_util_getVTManager(XW_UtilCtxt* uc)
{
    CursesAppGlobals* globals = (CursesAppGlobals*)uc->closure;
    return globals->cGlobals.params->vtMgr;
} /* linux_util_getVTManager */

static XP_Bool
curses_util_askPassword( XW_UtilCtxt* XP_UNUSED(uc), 
                         const XP_UCHAR* XP_UNUSED(name), 
                         XP_UCHAR* XP_UNUSED(buf), XP_U16* XP_UNUSED(len) )
{
    XP_WARNF( "curses_util_askPassword not implemented" );
    return XP_FALSE;
} /* curses_util_askPassword */

static void
curses_util_yOffsetChange( XW_UtilCtxt* XP_UNUSED(uc), 
                           XP_U16 XP_UNUSED(maxOffset),
                           XP_U16 XP_UNUSED(oldOffset), XP_U16 XP_UNUSED(newOffset) )
{
    /* if ( oldOffset != newOffset ) { */
    /*     XP_WARNF( "curses_util_yOffsetChange(%d,%d,%d) not implemented", */
    /*               maxOffset, oldOffset, newOffset ); */
    /* } */
} /* curses_util_yOffsetChange */

static XP_Bool
curses_util_warnIllegalWord( XW_UtilCtxt* XP_UNUSED(uc), 
                             BadWordInfo* XP_UNUSED(bwi), 
                             XP_U16 XP_UNUSED(player),
                             XP_Bool XP_UNUSED(turnLost) )
{
    XP_WARNF( "curses_util_warnIllegalWord not implemented" );
    return XP_FALSE;
} /* curses_util_warnIllegalWord */

static void
curses_util_remSelected( XW_UtilCtxt* uc )
{
    CursesAppGlobals* globals = (CursesAppGlobals*)uc->closure;
    XWStreamCtxt* stream;
    XP_UCHAR* text;

    stream = mem_stream_make( MPPARM(globals->cGlobals.util->mpool)
                              globals->cGlobals.params->vtMgr,
                              globals, CHANNEL_NONE, NULL );
    board_formatRemainingTiles( globals->cGlobals.game.board, stream );

    text = strFromStream( stream );

    (void)cursesask( globals, text, 1, "Ok" );

    free( text );
}

#ifndef XWFEATURE_STANDALONE_ONLY
static XWStreamCtxt*
curses_util_makeStreamFromAddr(XW_UtilCtxt* uc, XP_PlayerAddr channelNo )
{
    CursesAppGlobals* globals = (CursesAppGlobals*)uc->closure;
    LaunchParams* params = globals->cGlobals.params;

    XWStreamCtxt* stream = mem_stream_make( MPPARM(uc->mpool) params->vtMgr,
                                            &globals->cGlobals, channelNo,
                                            sendOnClose );
    return stream;
} /* curses_util_makeStreamFromAddr */
#endif

static void
setupCursesUtilCallbacks( CursesAppGlobals* globals, XW_UtilCtxt* util )
{
    util->vtable->m_util_userError = curses_util_userError;

    util->vtable->m_util_getVTManager = curses_util_getVTManager;
    util->vtable->m_util_askPassword = curses_util_askPassword;
    util->vtable->m_util_yOffsetChange = curses_util_yOffsetChange;
    util->vtable->m_util_warnIllegalWord = curses_util_warnIllegalWord;
    util->vtable->m_util_remSelected = curses_util_remSelected;
#ifndef XWFEATURE_STANDALONE_ONLY
    util->vtable->m_util_makeStreamFromAddr = curses_util_makeStreamFromAddr;
#endif
    util->vtable->m_util_userQuery = curses_util_userQuery;
    util->vtable->m_util_confirmTrade = curses_util_confirmTrade;
    util->vtable->m_util_userPickTileBlank = curses_util_userPickTileBlank;
    util->vtable->m_util_userPickTileTray = curses_util_userPickTileTray;
    util->vtable->m_util_trayHiddenChange = curses_util_trayHiddenChange;
    util->vtable->m_util_informMove = curses_util_informMove;
    util->vtable->m_util_informUndo = curses_util_informUndo;
    util->vtable->m_util_notifyGameOver = curses_util_notifyGameOver;
    util->vtable->m_util_informNetDict = curses_util_informNetDict;
    util->vtable->m_util_setIsServer = curses_util_setIsServer;

#ifdef XWFEATURE_HILITECELL
    util->vtable->m_util_hiliteCell = curses_util_hiliteCell;
#endif
    util->vtable->m_util_engineProgressCallback = 
        curses_util_engineProgressCallback;

    util->vtable->m_util_setTimer = curses_util_setTimer;
    util->vtable->m_util_clearTimer = curses_util_clearTimer;
    util->vtable->m_util_requestTime = curses_util_requestTime;

    util->closure = globals;
} /* setupCursesUtilCallbacks */

static CursesMenuHandler
getHandlerForKey( const MenuList* list, char ch )
{
    CursesMenuHandler handler = NULL;
    while ( list->handler != NULL ) {
        if ( list->key == ch ) {
            handler = list->handler;
            break;
        }
        ++list;
    }
    return handler;
}

static XP_Bool
handleKeyEvent( CursesAppGlobals* globals, const MenuList* list, char ch )
{
    CursesMenuHandler handler = getHandlerForKey( list, ch );
    XP_Bool result = XP_FALSE;
    if ( !!handler ) {
        result = (*handler)(globals);
    }
    return result;
} /* handleKeyEvent */

static XP_Bool
passKeyToBoard( CursesAppGlobals* globals, char ch )
{
    XP_Bool handled = ch >= 'a' && ch <= 'z';
    if ( handled ) {
        ch += 'A' - 'a';
        globals->doDraw = board_handleKey( globals->cGlobals.game.board, 
                                           ch, NULL );
    }
    return handled;
} /* passKeyToBoard */

static void
positionSizeStuff( CursesAppGlobals* globals, int width, int height )
{
    XP_U16 cellWidth, cellHt, scoreLeft, scoreWidth;
    BoardCtxt* board = globals->cGlobals.game.board;
    int remWidth = width;
    int nRows = globals->cGlobals.gi.boardSize;

    cellWidth = CURSES_CELL_WIDTH;
    cellHt = CURSES_CELL_HT;
    board_setPos( board, BOARD_OFFSET, BOARD_OFFSET, 
                  cellWidth * nRows, cellHt * nRows, 
                  cellWidth, XP_FALSE );
    /* board_setScale( board, cellWidth, cellHt ); */
    scoreLeft = (cellWidth * nRows);// + BOARD_SCORE_PADDING;
    remWidth -= cellWidth * nRows;

    /* If the scoreboard will right of the board, put it there.  Otherwise try
       to fit it below the boards. */
    int tileWidth = 3;
    int trayWidth = (tileWidth*MAX_TRAY_TILES);
    int trayLeft = scoreLeft;
    int trayTop;
    int trayHt = 4;
    if ( trayWidth < remWidth ) {
        trayLeft += XP_MIN(remWidth - trayWidth, BOARD_SCORE_PADDING );
        trayTop = 8;
    } else {
        trayLeft = BOARD_OFFSET;
        trayTop = BOARD_OFFSET + (cellHt * nRows);
        if ( trayTop + trayHt > height ) {
            XP_ASSERT( height > trayTop );
            trayHt = height - trayTop;
        }
    }
    board_setTrayLoc( board, trayLeft, trayTop, (3*MAX_TRAY_TILES)+1, 
                      trayHt, 1 );

    scoreWidth = remWidth;
    if ( scoreWidth > 45 ) {
        scoreWidth = 45;
        scoreLeft += (remWidth - scoreWidth) / 2;
    }
    board_setScoreboardLoc( board, scoreLeft, 1,
                            scoreWidth, 5, /*4 players + rem*/ XP_FALSE );

    /* no divider -- yet */
    /*     board_setTrayVisible( globals.board, XP_TRUE, XP_FALSE ); */

    board_invalAll( board );
} /* positionSizeStuff */

static XP_Bool 
relay_sendNoConn_curses( const XP_U8* msg, XP_U16 len,
                         const XP_UCHAR* relayID, void* closure )
{
    CursesAppGlobals* globals = (CursesAppGlobals*)closure;
    return storeNoConnMsg( &globals->cGlobals, msg, len, relayID );
} /* relay_sendNoConn_curses */

static void
relay_status_curses( void* XP_UNUSED(closure), 
                     CommsRelayState XP_UNUSED_DBG(state) )
{
    XP_LOGF( "%s got status: %s", __func__, CommsRelayState2Str(state) );
}

static void
relay_connd_curses( void* XP_UNUSED(closure), XP_UCHAR* const XP_UNUSED(room),
                    XP_Bool XP_UNUSED(reconnect), XP_U16 XP_UNUSED(devOrder),
                    XP_Bool XP_UNUSED_DBG(allHere),
                    XP_U16 XP_UNUSED_DBG(nMissing) )
{
    XP_LOGF( "%s got allHere: %d; nMissing: %d", __func__, allHere, nMissing );
}

static void
relay_error_curses( void* XP_UNUSED(closure), XWREASON XP_UNUSED_DBG(relayErr) )
{
#ifdef DEBUG
    XP_LOGF( "%s(%s)", __func__, XWREASON2Str( relayErr ) );
#endif
}

#ifdef USE_GLIBLOOP
static gboolean
handle_stdin( GIOChannel* XP_UNUSED_DBG(source), GIOCondition condition, 
              gpointer data )
{
    if ( 0 != (G_IO_IN & condition) ) {
#ifdef DEBUG
        gint fd = g_io_channel_unix_get_fd( source );
        XP_ASSERT( 0 == fd );
#endif
        CursesAppGlobals* globals = (CursesAppGlobals*)data;
        int ch = wgetch( globals->mainWin );
        remapKey( &ch );
        if (
#ifdef CURSES_SMALL_SCREEN
            handleKeyEvent( globals, g_rootMenuListShow, ch ) ||
#endif
            handleKeyEvent( globals, globals->menuList, ch )
            || handleKeyEvent( globals, g_sharedMenuList, ch )
            || passKeyToBoard( globals, ch ) ) {
            if ( g_globals.doDraw ) {
                board_draw( globals->cGlobals.game.board );
                globals->doDraw = XP_FALSE;
            }
        }
    }
    return TRUE;
}
#endif

#ifdef COMMS_XPORT_FLAGSPROC
static XP_U32
curses_getFlags( void* XP_UNUSED(closure) )
{
    return COMMS_XPORT_FLAGS_HASNOCONN;
}
#endif

void
cursesmain( XP_Bool isServer, LaunchParams* params )
{
    int width, height;
    CommonGlobals* cGlobals = &g_globals.cGlobals;

    memset( &g_globals, 0, sizeof(g_globals) );

#ifdef USE_GLIBLOOP
    g_globals.loop = g_main_loop_new( NULL, FALSE );
#endif

    g_globals.amServer = isServer;
    g_globals.cGlobals.params = params;
#ifdef XWFEATURE_RELAY
    g_globals.cGlobals.socket = -1;
#endif

    g_globals.cGlobals.socketChanged = curses_socket_changed;
    g_globals.cGlobals.socketChangedClosure = &g_globals;
    g_globals.cGlobals.addAcceptor = curses_socket_acceptor;

    g_globals.cGlobals.cp.showBoardArrow = XP_TRUE;
    g_globals.cGlobals.cp.showRobotScores = params->showRobotScores;
    g_globals.cGlobals.cp.hideTileValues = params->hideValues;
    g_globals.cGlobals.cp.skipCommitConfirm = params->skipCommitConfirm;
    g_globals.cGlobals.cp.sortNewTiles = params->sortNewTiles;
    g_globals.cGlobals.cp.showColors = params->showColors;
    g_globals.cGlobals.cp.allowPeek = params->allowPeek;
#ifdef XWFEATURE_SLOW_ROBOT
    g_globals.cGlobals.cp.robotThinkMin = params->robotThinkMin;
    g_globals.cGlobals.cp.robotThinkMax = params->robotThinkMax;
    g_globals.cGlobals.cp.robotTradePct = params->robotTradePct;
#endif

    setupUtil( &g_globals.cGlobals );
    setupCursesUtilCallbacks( &g_globals, g_globals.cGlobals.util );

    initFromParams( &g_globals.cGlobals, params );

#ifdef XWFEATURE_RELAY
    if ( params->conType == COMMS_CONN_RELAY ) {
        g_globals.cGlobals.defaultServerName
            = params->connInfo.relay.relayName;
    }
#endif

#ifdef USE_GLIBLOOP
    cursesListenOnSocket( &g_globals, 0, handle_stdin );
    setOneSecondTimer( &g_globals.cGlobals );
#else
    cursesListenOnSocket( &g_globals, 0 ); /* stdin */

    int piperesult = pipe( g_globals.timepipe );
    XP_ASSERT( piperesult == 0 );
    /* reader pipe */
    cursesListenOnSocket( &g_globals, g_globals.timepipe[0] );
#endif

    struct sigaction act = { .sa_handler = SIGINTTERM_handler };
    sigaction( SIGINT, &act, NULL );
    sigaction( SIGTERM, &act, NULL );
    struct sigaction act2 = { .sa_handler = SIGWINCH_handler };
    sigaction( SIGWINCH, &act2, NULL );

    TransportProcs procs = {
        .closure = &g_globals,
        .send = LINUX_SEND,
#ifdef COMMS_HEARTBEAT
        .reset = linux_reset,
#endif
#ifdef XWFEATURE_RELAY
        .rstatus = relay_status_curses,
        .rconnd = relay_connd_curses,
        .rerror = relay_error_curses,
        .sendNoConn = relay_sendNoConn_curses,
# ifdef COMMS_XPORT_FLAGSPROC
        .getFlags = curses_getFlags,
# endif
#endif
    };

    if ( !!params->pipe && !!params->fileName ) {
        read_pipe_then_close( &g_globals.cGlobals, &procs );
    } else if ( !!params->nbs && !!params->fileName ) {
        do_nbs_then_close( &g_globals.cGlobals, &procs );
    } else {
        XP_Bool opened = XP_FALSE;
        initCurses( &g_globals );
        getmaxyx( g_globals.boardWin, height, width );

        g_globals.draw = (struct CursesDrawCtx*)
            cursesDrawCtxtMake( g_globals.boardWin );

        XWStreamCtxt* stream = NULL;
        if ( !!params->fileName && file_exists( params->fileName ) ) {
            stream = streamFromFile( &g_globals.cGlobals, params->fileName, 
                                     &g_globals );
#ifdef USE_SQLITE
        } else if ( !!params->dbFileName && file_exists( params->dbFileName ) ) {
            stream = streamFromDB( &g_globals.cGlobals, &g_globals );
#endif
        }

        if ( !!stream ) {
            if ( NULL == cGlobals->dict ) {
                cGlobals->dict = makeDictForStream( cGlobals, stream );
            }
            (void)game_makeFromStream( MEMPOOL stream, &cGlobals->game, 
                                       &cGlobals->gi, cGlobals->dict, &cGlobals->dicts,
                                       cGlobals->util, 
                                       (DrawCtx*)g_globals.draw, 
                                       &g_globals.cGlobals.cp, &procs );

            stream_destroy( stream );
            if ( !isServer && cGlobals->gi.serverRole == SERVER_ISSERVER ) {
                isServer = XP_TRUE;
            }
            opened = XP_TRUE;
        }
        if ( !opened ) {
            game_makeNewGame( MEMPOOL &cGlobals->game, &cGlobals->gi,
                              cGlobals->util, (DrawCtx*)g_globals.draw,
                              &g_globals.cGlobals.cp, &procs, params->gameSeed );
        }

#ifndef XWFEATURE_STANDALONE_ONLY
        if ( cGlobals->game.comms ) {
            CommsAddrRec addr = {0};

            if ( 0 ) {
# ifdef XWFEATURE_RELAY
            } else if ( params->conType == COMMS_CONN_RELAY ) {
                addr.conType = COMMS_CONN_RELAY;
                addr.u.ip_relay.ipAddr = 0;       /* ??? */
                addr.u.ip_relay.port = params->connInfo.relay.defaultSendPort;
                addr.u.ip_relay.seeksPublicRoom = params->connInfo.relay.seeksPublicRoom;
                addr.u.ip_relay.advertiseRoom = params->connInfo.relay.advertiseRoom;
                XP_STRNCPY( addr.u.ip_relay.hostName, params->connInfo.relay.relayName,
                            sizeof(addr.u.ip_relay.hostName) - 1 );
                XP_STRNCPY( addr.u.ip_relay.invite, params->connInfo.relay.invite,
                            sizeof(addr.u.ip_relay.invite) - 1 );
# endif
# ifdef XWFEATURE_SMS
            } else if ( params->conType == COMMS_CONN_SMS ) {
                addr.conType = COMMS_CONN_SMS;
                XP_STRNCPY( addr.u.sms.phone, params->connInfo.sms.serverPhone,
                            sizeof(addr.u.sms.phone) - 1 );
                addr.u.sms.port = params->connInfo.sms.port;
# endif
# ifdef XWFEATURE_BLUETOOTH
            } else if ( params->conType == COMMS_CONN_BT ) {
                addr.conType = COMMS_CONN_BT;
                XP_ASSERT( sizeof(addr.u.bt.btAddr) 
                           >= sizeof(params->connInfo.bt.hostAddr));
                XP_MEMCPY( &addr.u.bt.btAddr, &params->connInfo.bt.hostAddr,
                           sizeof(params->connInfo.bt.hostAddr) );
# endif
            }
            comms_setAddr( cGlobals->game.comms, &addr );
        }
#endif

        if ( NULL == cGlobals->dict ) {
            cGlobals->dict = 
                linux_dictionary_make( MEMPOOL params, 
                                       cGlobals->gi.dictName, XP_TRUE );
        }
        model_setDictionary( cGlobals->game.model, cGlobals->dict );
        setSquareBonuses( cGlobals );
        positionSizeStuff( &g_globals, width, height );

#ifndef XWFEATURE_STANDALONE_ONLY
        /* send any events that need to get off before the event loop begins */
        if ( !isServer ) {
            if ( 1 /* stream_open( params->info.clientInfo.stream )  */) {
                server_initClientConnection( cGlobals->game.server, 
                                             mem_stream_make( MEMPOOL
                                                              params->vtMgr,
                                                              cGlobals,
                                                              (XP_PlayerAddr)0,
                                                              sendOnClose ) );
            } else {
                cursesUserError( &g_globals, "Unable to open connection to server");
                exit( 0 );
            }
        }
#endif

        server_do( g_globals.cGlobals.game.server );

        g_globals.menuList = g_boardMenuList;
        drawMenuLargeOrSmall( &g_globals, g_boardMenuList ); 
        board_draw( g_globals.cGlobals.game.board );

#ifdef USE_GLIBLOOP
        g_main_loop_run( g_globals.loop );
#else
        while ( !g_globals.timeToExit ) {
            int ch = 0;
            if ( blocking_gotEvent( &g_globals, &ch ) ) {
                remapKey( &ch );
                if (
#ifdef CURSES_SMALL_SCREEN
                    handleKeyEvent( &g_globals, g_rootMenuListShow, ch ) ||
#endif
                    handleKeyEvent( &g_globals, g_globals.menuList, ch )
                    || handleKeyEvent( &g_globals, g_sharedMenuList, ch )
                    || passKeyToBoard( &g_globals, ch ) ) {
                    if ( g_globals.doDraw ) {
                        board_draw( g_globals.cGlobals.game.board );
                        g_globals.doDraw = XP_FALSE;
                    }
                }
            }
        }
#endif
    }
    saveGame( &g_globals.cGlobals );

    game_dispose( &g_globals.cGlobals.game ); /* takes care of the dict */
    gi_disposePlayerInfo( MEMPOOL &cGlobals->gi );
    
#ifdef XWFEATURE_BLUETOOTH
    linux_bt_close( &g_globals.cGlobals );
#endif
#ifdef XWFEATURE_SMS
    linux_sms_close( &g_globals.cGlobals );
#endif
#ifdef XWFEATURE_IP_DIRECT
    linux_udp_close( &g_globals.cGlobals );
#endif

    endwin();

    linux_util_vt_destroy( g_globals.cGlobals.util );
} /* cursesmain */
#endif /* PLATFORM_NCURSES */
