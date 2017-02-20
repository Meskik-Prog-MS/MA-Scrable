/* -*-mode: C; fill-column: 78; c-basic-offset: 4; -*- */
/* 
 * Copyright 1997 - 2010 by Eric House (xwords@eehouse.org).  All rights
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

#ifndef _UTIL_H_
#define _UTIL_H_

#include "comtypes.h"

#include "dawg.h"
#include "model.h"
#include "board.h"
#include "mempool.h"
#include "vtabmgr.h"
#include "comms.h"

#include "xwrelay.h"

#define LETTER_NONE '\0'

typedef enum {
    ERR_NONE,                   /* 0 is special case */
    ERR_TILES_NOT_IN_LINE,   /* scoring a move where tiles aren't in line */
    ERR_NO_EMPTIES_IN_TURN,
    ERR_TWO_TILES_FIRST_MOVE,
    ERR_TILES_MUST_CONTACT,
/*     ERR_NO_HINT_MID_TURN, */
    ERR_TOO_FEW_TILES_LEFT_TO_TRADE,
    ERR_NOT_YOUR_TURN,
    ERR_NO_PEEK_ROBOT_TILES,
#ifndef XWFEATURE_STANDALONE_ONLY
    ERR_SERVER_DICT_WINS,
    ERR_NO_PEEK_REMOTE_TILES,
    ERR_REG_UNEXPECTED_USER, /* server asked to register too many remote
                                users */
    ERR_REG_SERVER_SANS_REMOTE,
    STR_NEED_BT_HOST_ADDR,
#endif
    ERR_NO_EMPTY_TRADE,
/*     ERR_CANT_ENGINE_MID_MOVE, */
/*     ERR_NOT_YOUR_TURN_TO_TRADE, */
/*     ERR_NOT_YOUR_TURN_TO_MOVE, */
    ERR_CANT_UNDO_TILEASSIGN,
    ERR_CANT_HINT_WHILE_DISABLED,
    ERR_NO_HINT_FOUND,          /* not really an error... */

    ERR_RELAY_BASE,
    ERR_RELAY_END = ERR_RELAY_BASE + XWRELAY_ERROR_LASTERR
} UtilErrID;

#define PICKER_PICKALL -1
#define PICKER_BACKUP -2

typedef struct PickInfo {
    const XP_UCHAR** curTiles;
    XP_U16 nCurTiles;
    XP_U16 nTotal;              /* count to fetch for turn, <= MAX_TRAY_TILES */
    XP_U16 thisPick;            /* <= nTotal */
} PickInfo;

typedef struct BadWordInfo {
    XP_U16 nWords;
    const XP_UCHAR* dictName;
    const XP_UCHAR* words[MAX_TRAY_TILES+1]; /* can form in both directions */
} BadWordInfo;

/* XWTimerProc returns true if redraw was necessitated by what the proc did */
typedef XP_Bool (*XWTimerProc)( void* closure, XWTimerReason why );

/* Platform-specific utility functions that need to be
 */
typedef struct UtilVtable {
    
    VTableMgr* (*m_util_getVTManager)(XW_UtilCtxt* uc);

#ifndef XWFEATURE_STANDALONE_ONLY
    XWStreamCtxt* (*m_util_makeStreamFromAddr )(XW_UtilCtxt* uc,
                                                XP_PlayerAddr channelNo );
#endif
    
    XWBonusType (*m_util_getSquareBonus)( XW_UtilCtxt* uc, XP_U16 boardSize,
                                          XP_U16 col, XP_U16 row );
    void (*m_util_userError)( XW_UtilCtxt* uc, UtilErrID id );

    void (*m_util_notifyMove)( XW_UtilCtxt* uc, XWStreamCtxt* stream );
    void (*m_util_notifyTrade)( XW_UtilCtxt* uc, const XP_UCHAR** tiles,
                                XP_U16 nTiles );
    /* return of < 0 means computer should pick */
    void (*m_util_notifyPickTileBlank)( XW_UtilCtxt* uc, XP_U16 playerNum,
                                        XP_U16 col, XP_U16 row,
                                        const XP_UCHAR** tileFaces, 
                                        XP_U16 nTiles );
    XP_S16 (*m_util_userPickTileTray)( XW_UtilCtxt* uc, const PickInfo* pi, 
                                        XP_U16 playerNum,
                                        const XP_UCHAR** texts, XP_U16 nTiles );

    void (*m_util_informNeedPassword)( XW_UtilCtxt* uc, XP_U16 playerNum,
                                       const XP_UCHAR* name );

    void (*m_util_trayHiddenChange)(XW_UtilCtxt* uc, 
                                    XW_TrayVisState newState,
                                    XP_U16 nVisibleRows );
    void (*m_util_yOffsetChange)(XW_UtilCtxt* uc, XP_U16 maxOffset,
                                 XP_U16 oldOffset, XP_U16 newOffset );
#ifdef XWFEATURE_TURNCHANGENOTIFY
    void (*m_util_turnChanged)(XW_UtilCtxt* uc, XP_S16 newTurn);
#endif
    void (*m_util_informMove)( XW_UtilCtxt* uc, XP_S16 turn, 
                               XWStreamCtxt* expl, XWStreamCtxt* words );
    void (*m_util_informUndo)( XW_UtilCtxt* uc );
    void (*m_util_informNetDict)( XW_UtilCtxt* uc, XP_LangCode lang,
                                  const XP_UCHAR* oldName,
                                  const XP_UCHAR* newName,
                                  const XP_UCHAR* newSum,
                                  XWPhoniesChoice phoniesAction );

    void (*m_util_notifyGameOver)( XW_UtilCtxt* uc, XP_S16 quitter );
#ifdef XWFEATURE_HILITECELL
    XP_Bool (*m_util_hiliteCell)( XW_UtilCtxt* uc, XP_U16 col, XP_U16 row );
#endif

    XP_Bool (*m_util_engineProgressCallback)( XW_UtilCtxt* uc );

    void (*m_util_setTimer)( XW_UtilCtxt* uc, XWTimerReason why, XP_U16 when,
                             XWTimerProc proc, void* closure );
    void (*m_util_clearTimer)( XW_UtilCtxt* uc, XWTimerReason why );

    void (*m_util_requestTime)( XW_UtilCtxt* uc );

    XP_Bool (*m_util_altKeyDown)( XW_UtilCtxt* uc );

    XP_U32 (*m_util_getCurSeconds)( XW_UtilCtxt* uc );
#ifdef XWFEATURE_DEVID
    const XP_UCHAR* (*m_util_getDevID)( XW_UtilCtxt* uc, DevIDType* typ );
    void (*m_util_deviceRegistered)( XW_UtilCtxt* uc, DevIDType typ, 
                                     const XP_UCHAR* idRelay );
#endif
    DictionaryCtxt* (*m_util_makeEmptyDict)( XW_UtilCtxt* uc );

    const XP_UCHAR* (*m_util_getUserString)( XW_UtilCtxt* uc, 
                                             XP_U16 stringCode );
    const XP_UCHAR* (*m_util_getUserQuantityString)( XW_UtilCtxt* uc, 
                                                     XP_U16 stringCode,
                                                     XP_U16 quantity );

    void (*m_util_notifyIllegalWords)( XW_UtilCtxt* uc, BadWordInfo* bwi,
                                       XP_U16 turn, XP_Bool turnLost );

    void (*m_util_remSelected)(XW_UtilCtxt* uc);

#ifndef XWFEATURE_MINIWIN
    void (*m_util_bonusSquareHeld)( XW_UtilCtxt* uc, XWBonusType bonus );
    void (*m_util_playerScoreHeld)( XW_UtilCtxt* uc, XP_U16 player );
#endif
#ifdef XWFEATURE_BOARDWORDS
    void (*m_util_cellSquareHeld)( XW_UtilCtxt* uc, XWStreamCtxt* words );
#endif

#ifdef XWFEATURE_SMS
    XP_Bool (*m_util_phoneNumbersSame)( XW_UtilCtxt* uc, const XP_UCHAR* p1,
                                        const XP_UCHAR* p2 );
#endif

#ifndef XWFEATURE_STANDALONE_ONLY
    void (*m_util_informMissing)(XW_UtilCtxt* uc, XP_Bool isServer, 
                                 const CommsAddrRec* addr, XP_U16 nDevs,
                                 XP_U16 nMissing );
    void (*m_util_addrChange)( XW_UtilCtxt* uc, const CommsAddrRec* oldAddr,
                               const CommsAddrRec* newAddr );
    void (*m_util_setIsServer)(XW_UtilCtxt* uc, XP_Bool isServer );
#endif

#ifdef XWFEATURE_SEARCHLIMIT
    XP_Bool (*m_util_getTraySearchLimits)(XW_UtilCtxt* uc, 
                                          XP_U16* min, XP_U16* max );
#endif

#ifdef XWFEATURE_CHAT
    void (*m_util_showChat)( XW_UtilCtxt* uc, const XP_UCHAR* const msg, 
                             XP_S16 from );
#endif

#ifdef SHOW_PROGRESS
    void (*m_util_engineStarting)( XW_UtilCtxt* uc, XP_U16 nBlanks );
    void (*m_util_engineStopping)( XW_UtilCtxt* uc );
#endif

#ifdef COMMS_CHECKSUM
    XP_UCHAR* (*m_util_md5sum)( XW_UtilCtxt* uc, const XP_U8* ptr, XP_U16 len );
#endif

} UtilVtable;


struct XW_UtilCtxt {
    UtilVtable* vtable;

    struct CurGameInfo* gameInfo;

    void* closure;
    MPSLOT
};

#define util_getVTManager(uc) \
         (uc)->vtable->m_util_getVTManager((uc))

#define util_makeStreamFromAddr(uc,a) \
         (uc)->vtable->m_util_makeStreamFromAddr((uc),(a))

#define util_getSquareBonus(uc,b,c,r)                           \
         (uc)->vtable->m_util_getSquareBonus((uc),(b),(c),(r))

#define util_userError(uc,err) \
         (uc)->vtable->m_util_userError((uc),(err))

#define util_notifyMove(uc, str)                         \
         (uc)->vtable->m_util_notifyMove((uc), (str))

#define util_notifyTrade(uc, tx, nt)                            \
        (uc)->vtable->m_util_notifyTrade((uc), (tx), (nt))

#define util_notifyPickTileBlank( uc, c, r, n, tx, nt )                  \
         (uc)->vtable->m_util_notifyPickTileBlank( (uc), (c), (r), (n), (tx), (nt) )
#define util_userPickTileTray( uc, w, n, tx, nt ) \
         (uc)->vtable->m_util_userPickTileTray( (uc), (w), (n), (tx), (nt) )
#define util_informNeedPassword( uc, pn, n ) \
         (uc)->vtable->m_util_informNeedPassword( (uc), (pn), (n) )

#define util_trayHiddenChange( uc, b, n ) \
         (uc)->vtable->m_util_trayHiddenChange((uc), (b), (n))

#define util_yOffsetChange( uc, m, o, n )                        \
         (uc)->vtable->m_util_yOffsetChange((uc), (m), (o), (n) )

#ifdef XWFEATURE_TURNCHANGENOTIFY
# define util_turnChanged( uc, t )                    \
        (uc)->vtable->m_util_turnChanged( (uc), (t) )
#else
# define util_turnChanged( uc, t )
#endif

#define util_informMove(uc,t,e,w)                               \
         (uc)->vtable->m_util_informMove( (uc), (t), (e), (w))
#define util_informUndo(uc) \
         (uc)->vtable->m_util_informUndo( (uc))
#define util_informNetDict(uc, cd, on, nn, ns, pa )                      \
         (uc)->vtable->m_util_informNetDict( (uc), (cd), (on), (nn), (ns), \
                                             (pa) )
#define util_notifyGameOver( uc, q )                  \
         (uc)->vtable->m_util_notifyGameOver((uc), (q))

#ifdef XWFEATURE_HILITECELL
# define util_hiliteCell( uc, c, r ) \
         (uc)->vtable->m_util_hiliteCell((uc), (c), (r))
#endif

#define util_engineProgressCallback( uc ) \
         (uc)->vtable->m_util_engineProgressCallback((uc))

#define util_setTimer( uc, why, when, proc, clos ) \
         (uc)->vtable->m_util_setTimer((uc),(why),(when),(proc),(clos))
#define util_clearTimer( uc, why ) \
         (uc)->vtable->m_util_clearTimer((uc),(why))

#define util_requestTime( uc ) \
         (uc)->vtable->m_util_requestTime((uc))

#define util_altKeyDown( uc ) \
         (uc)->vtable->m_util_altKeyDown((uc))

#define util_getCurSeconds(uc) \
         (uc)->vtable->m_util_getCurSeconds((uc))

#ifdef XWFEATURE_DEVID
# define util_getDevID( uc, t )                     \
         (uc)->vtable->m_util_getDevID((uc),(t))
# define util_deviceRegistered( uc, typ, id )                       \
         (uc)->vtable->m_util_deviceRegistered( (uc), (typ), (id) )
#endif

#define util_makeEmptyDict( uc ) \
         (uc)->vtable->m_util_makeEmptyDict((uc))

#define util_getUserString( uc, c ) \
         (uc)->vtable->m_util_getUserString((uc),(c))
#define util_getUserQuantityString( uc, c, q )            \
         (uc)->vtable->m_util_getUserQuantityString((uc),(c),(q))

#define util_notifyIllegalWords( uc, w, p, b ) \
         (uc)->vtable->m_util_notifyIllegalWords((uc),(w),(p),(b))

#define util_remSelected( uc )              \
         (uc)->vtable->m_util_remSelected((uc))

#ifndef XWFEATURE_MINIWIN
# define util_bonusSquareHeld( uc, b )                                  \
         (uc)->vtable->m_util_bonusSquareHeld( (uc), (b) )
# define util_playerScoreHeld( uc, player )                                \
         (uc)->vtable->m_util_playerScoreHeld( (uc), (player) )
#endif
#ifdef XWFEATURE_BOARDWORDS
#define util_cellSquareHeld(uc, s)                      \
    (uc)->vtable->m_util_cellSquareHeld( (uc), (s) )
#endif
#ifdef XWFEATURE_SMS
#define util_phoneNumbersSame(uc,p1,p2)                                 \
    (uc)->vtable->m_util_phoneNumbersSame( (uc), (p1), (p2) )
#endif

#ifndef XWFEATURE_STANDALONE_ONLY
# define util_informMissing( uc, is, ct, nd, nm )                       \
    (uc)->vtable->m_util_informMissing((uc), (is), (ct), (nd), (nm) )
# define util_addrChange( uc, addro, addrn ) \
         (uc)->vtable->m_util_addrChange((uc), (addro), (addrn))
# define util_setIsServer( uc, is ) \
         (uc)->vtable->m_util_setIsServer((uc), (is))
# else
# define util_addrChange( uc, addro, addrn )
#endif

#ifdef XWFEATURE_SEARCHLIMIT
#define util_getTraySearchLimits(uc,min,max) \
         (uc)->vtable->m_util_getTraySearchLimits((uc), (min), (max))
#endif

#ifdef XWFEATURE_CHAT
# define util_showChat( uc, m, f ) (uc)->vtable->m_util_showChat((uc),(m),(f))
#endif

# ifdef SHOW_PROGRESS
# define util_engineStarting( uc, nb ) \
         (uc)->vtable->m_util_engineStarting((uc),(nb))
# define util_engineStopping( uc ) \
         (uc)->vtable->m_util_engineStopping((uc))
# else
# define util_engineStarting( uc, nb )
# define util_engineStopping( uc )
# endif

#ifdef COMMS_CHECKSUM
# define util_md5sum( uc, p, l ) (uc)->vtable->m_util_md5sum((uc), (p), (l))
#endif

#endif
