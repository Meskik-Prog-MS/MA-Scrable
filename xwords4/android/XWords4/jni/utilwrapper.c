/* -*-mode: C; compile-command: "../../scripts/ndkbuild.sh"; -*- */
/* 
 * Copyright 2001-2009 by Eric House (xwords@eehouse.org).  All rights
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
#include <sys/time.h>

#include <jni.h>

#include "comtypes.h"
#include "utilwrapper.h"
#include "anddict.h"
#include "andutils.h"
#include "LocalizedStrIncludes.h"

typedef struct _TimerStorage {
    XWTimerProc proc;
    void* closure;
} TimerStorage;

typedef struct _AndUtil {
    XW_UtilCtxt util;
    JNIEnv** env;
    jobject jutil;  /* global ref to object implementing XW_UtilCtxt */
    TimerStorage timerStorage[NUM_TIMERS_PLUS_ONE];
    XP_UCHAR* userStrings[N_AND_USER_STRINGS];
} AndUtil;


static VTableMgr*
and_util_getVTManager( XW_UtilCtxt* uc )
{
    AndGlobals* globals = (AndGlobals*)uc->closure;
    return globals->vtMgr;
}

#ifndef XWFEATURE_STANDALONE_ONLY
static XWStreamCtxt*
and_util_makeStreamFromAddr( XW_UtilCtxt* uc, XP_PlayerAddr channelNo )
{
    AndUtil* util = (AndUtil*)uc;
    AndGlobals* globals = (AndGlobals*)uc->closure;
    XWStreamCtxt* stream = and_empty_stream( MPPARM(util->util.mpool)
                                             globals );
    stream_setAddress( stream, channelNo );
    stream_setOnCloseProc( stream, and_send_on_close );
    return stream;
}
#endif

#define UTIL_CBK_HEADER(nam,sig)                                        \
    AndUtil* util = (AndUtil*)uc;                                       \
    JNIEnv* env = *util->env;                                           \
    if ( NULL != util->jutil ) {                                        \
        jmethodID mid = getMethodID( env, util->jutil, nam, sig )

#define UTIL_CBK_TAIL()                                                 \
    } else {                                                            \
        XP_LOGF( "%s: skipping call into java because jutil==NULL",     \
                 __func__ );                                            \
    }
    
static XWBonusType and_util_getSquareBonus( XW_UtilCtxt* uc, 
                                            const ModelCtxt* XP_UNUSED(model),
                                            XP_U16 col, XP_U16 row )
{
    XWBonusType result = BONUS_NONE;
    UTIL_CBK_HEADER("getSquareBonus","(II)I" );
    result = (*env)->CallIntMethod( env, util->jutil, mid, 
                                  col, row );
    UTIL_CBK_TAIL();
    return result;
}

static void
and_util_userError( XW_UtilCtxt* uc, UtilErrID id )
{
    UTIL_CBK_HEADER( "userError", "(I)V" );
    (*env)->CallVoidMethod( env, util->jutil, mid, id );
    if ((*env)->ExceptionOccurred(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        XP_LOGF( "exception found" );
    }
    UTIL_CBK_TAIL();
}

static XP_Bool
and_util_userQuery( XW_UtilCtxt* uc, UtilQueryID id, XWStreamCtxt* stream )
{
    jboolean result = XP_FALSE;
    XP_ASSERT( id < QUERY_LAST_COMMON );
    UTIL_CBK_HEADER("userQuery", "(ILjava/lang/String;)Z" );

    jstring jstr = NULL;
    if ( NULL != stream ) {
        jstr = streamToJString( MPPARM(util->util.mpool) env, stream );
    }
    result = (*env)->CallBooleanMethod( env, util->jutil, mid, id, jstr );
    if ( NULL != jstr ) {
        (*env)->DeleteLocalRef( env, jstr );
    }
    UTIL_CBK_TAIL();
    return result;
}

static XP_S16
and_util_userPickTile( XW_UtilCtxt* uc, const PickInfo* pi, 
                       XP_U16 playerNum, const XP_UCHAR** texts, XP_U16 nTiles )
{
    XP_S16 result = -1;
    UTIL_CBK_HEADER("userPickTile", "(I[Ljava/lang/String;)I" );

#ifdef FEATURE_TRAY_EDIT
    ++error;                       /* need to pass pi if this is on */
#endif

    jobject jtexts = makeStringArray( env, nTiles, texts );

    result = (*env)->CallIntMethod( env, util->jutil, mid, 
                                    playerNum, jtexts );

    (*env)->DeleteLocalRef( env, jtexts );
    UTIL_CBK_TAIL();
    return result;
} /* and_util_userPickTile */


static XP_Bool
and_util_askPassword( XW_UtilCtxt* uc, const XP_UCHAR* name, 
                      XP_UCHAR* buf, XP_U16* len )
{
    return XP_FALSE;;
}


static void
and_util_trayHiddenChange(XW_UtilCtxt* uc, XW_TrayVisState newState,
                          XP_U16 nVisibleRows )
{
}

static void
and_util_yOffsetChange(XW_UtilCtxt* uc, XP_U16 oldOffset, XP_U16 newOffset )
{
#if 0
    AndUtil* util = (AndUtil*)uc;
    JNIEnv* env = *util->env;
    const char* sig = "(II)V";
    jmethodID mid = getMethodID( env, util->jutil, "yOffsetChange", sig );

    (*env)->CallVoidMethod( env, util->jutil, mid, 
                            oldOffset, newOffset );
#endif
}

#ifdef XWFEATURE_TURNCHANGENOTIFY
static void
and_util_turnChanged(XW_UtilCtxt* uc)
{
    /* don't log; this is getting called a lot */
}

#endif
static void
and_util_notifyGameOver( XW_UtilCtxt* uc )
{
    UTIL_CBK_HEADER( "notifyGameOver", "()V" );
    (*env)->CallVoidMethod( env, util->jutil, mid );
    UTIL_CBK_TAIL();
}


static XP_Bool
and_util_hiliteCell( XW_UtilCtxt* uc, XP_U16 col, XP_U16 row )
{
    /* don't log; this is getting called a lot */
    return XP_TRUE;             /* means keep going */
}


static XP_Bool
and_util_engineProgressCallback( XW_UtilCtxt* uc )
{
    XP_Bool result = XP_FALSE;
    UTIL_CBK_HEADER("engineProgressCallback","()Z" );
    result = (*env)->CallBooleanMethod( env, util->jutil, mid );
    UTIL_CBK_TAIL();
    return result;
}

/* This is added for java, not part of the util api */
bool
utilTimerFired( XW_UtilCtxt* uc, XWTimerReason why, int handle )
{
    AndUtil* util = (AndUtil*)uc;
    TimerStorage* timerStorage = &util->timerStorage[why];
    XP_ASSERT( handle == (int)timerStorage );
    return (*timerStorage->proc)( timerStorage->closure, why );
}

static void
and_util_setTimer( XW_UtilCtxt* uc, XWTimerReason why, XP_U16 when,
                   XWTimerProc proc, void* closure )
{
    UTIL_CBK_HEADER("setTimer", "(III)V" );

    XP_ASSERT( why < VSIZE(util->timerStorage) );
    TimerStorage* storage = &util->timerStorage[why];
    storage->proc = proc;
    storage->closure = closure;
    (*env)->CallVoidMethod( env, util->jutil, mid,
                            why, when, (int)storage );
    UTIL_CBK_TAIL();
}

static void
and_util_clearTimer( XW_UtilCtxt* uc, XWTimerReason why )
{
    UTIL_CBK_HEADER("clearTimer", "(I)V" );
    (*env)->CallVoidMethod( env, util->jutil, mid, why );
    UTIL_CBK_TAIL();
}


static void
and_util_requestTime( XW_UtilCtxt* uc )
{
    UTIL_CBK_HEADER("requestTime", "()V" );
    (*env)->CallVoidMethod( env, util->jutil, mid );
    UTIL_CBK_TAIL();
}

static XP_Bool
and_util_altKeyDown( XW_UtilCtxt* uc )
{
    LOG_FUNC();
    return XP_FALSE;
}


static XP_U32
and_util_getCurSeconds( XW_UtilCtxt* uc )
{
    struct timeval tv;
    gettimeofday( &tv, NULL );
    return tv.tv_sec;
}


static DictionaryCtxt* 
and_util_makeEmptyDict( XW_UtilCtxt* uc )
{
    AndUtil* util = (AndUtil*)uc;
#ifdef STUBBED_DICT
    XP_ASSERT(0);
#else
    return and_dictionary_make_empty( MPPARM_NOCOMMA( util->util.mpool ) );
#endif
}

static const XP_UCHAR*
and_util_getUserString( XW_UtilCtxt* uc, XP_U16 stringCode )
{
    XP_UCHAR* result = NULL;
    UTIL_CBK_HEADER("getUserString", "(I)Ljava/lang/String;" );
    int index = stringCode - 1; /* see LocalizedStrIncludes.h */
    XP_ASSERT( index < VSIZE( util->userStrings ) );

    if ( ! util->userStrings[index] ) {
        jstring jresult = (*env)->CallObjectMethod( env, util->jutil, mid, 
                                                    stringCode );
        jsize len = (*env)->GetStringUTFLength( env, jresult );
        XP_UCHAR* buf = XP_MALLOC( util->util.mpool, len + 1 );

        const char* jchars = (*env)->GetStringUTFChars( env, jresult, NULL );
        XP_MEMCPY( buf, jchars, len );
        buf[len] = '\0';
        (*env)->ReleaseStringUTFChars( env, jresult, jchars );
        (*env)->DeleteLocalRef( env, jresult );
        util->userStrings[index] = buf;
    }

    result = util->userStrings[index];
    UTIL_CBK_TAIL();
    return result;
}


static XP_Bool
and_util_warnIllegalWord( XW_UtilCtxt* uc, BadWordInfo* bwi, 
                          XP_U16 turn, XP_Bool turnLost )
{
    LOG_FUNC();
    return XP_FALSE;
}


static void
and_util_remSelected(XW_UtilCtxt* uc)
{
    UTIL_CBK_HEADER("remSelected", "()V" );
    (*env)->CallVoidMethod( env, util->jutil, mid );
    UTIL_CBK_TAIL();
}


#ifndef XWFEATURE_STANDALONE_ONLY
static void
and_util_addrChange( XW_UtilCtxt* uc, const CommsAddrRec* oldAddr,
                     const CommsAddrRec* newAddr )
{
    LOG_FUNC();
}

#endif

#ifdef XWFEATURE_SEARCHLIMIT
static XP_Bool
and_util_getTraySearchLimits(XW_UtilCtxt* uc, XP_U16* min, XP_U16* max )
{
    LOG_FUNC();
    foobar;                     /* this should not be compiling */
}

#endif

#ifdef SHOW_PROGRESS
static void
and_util_engineStarting( XW_UtilCtxt* uc, XP_U16 nBlanks )
{
    LOG_FUNC();
}

static void
and_util_engineStopping( XW_UtilCtxt* uc )
{
    LOG_FUNC();
}
#endif

XW_UtilCtxt*
makeUtil( MPFORMAL JNIEnv** envp, jobject jutil, CurGameInfo* gi, 
          AndGlobals* closure )
{
    AndUtil* util = (AndUtil*)XP_CALLOC( mpool, sizeof(*util) );
    UtilVtable* vtable = (UtilVtable*)XP_CALLOC( mpool, sizeof(*vtable) );
    util->env = envp;
    JNIEnv* env = *envp;
    if ( NULL != jutil ) {
        util->jutil = (*env)->NewGlobalRef( env, jutil );
    }
    util->util.vtable = vtable;
    MPASSIGN( util->util.mpool, mpool );
    util->util.closure = closure;
    util->util.gameInfo = gi;

#define SET_PROC(nam) vtable->m_util_##nam = and_util_##nam
    SET_PROC(getVTManager);
#ifndef XWFEATURE_STANDALONE_ONLY
    SET_PROC(makeStreamFromAddr);
#endif
    SET_PROC(getSquareBonus);
    SET_PROC(userError);
    SET_PROC(userQuery);
    SET_PROC(userPickTile);
    SET_PROC(askPassword);
    SET_PROC(trayHiddenChange);
    SET_PROC(yOffsetChange);
#ifdef XWFEATURE_TURNCHANGENOTIFY
    SET_PROC(    turnChanged);
#endif
    SET_PROC(notifyGameOver);
    SET_PROC(hiliteCell);
    SET_PROC(engineProgressCallback);
    SET_PROC(setTimer);
    SET_PROC(clearTimer);
    SET_PROC(requestTime);
    SET_PROC(altKeyDown);
    SET_PROC(getCurSeconds);
    SET_PROC(makeEmptyDict);
    SET_PROC(getUserString);
    SET_PROC(warnIllegalWord);
    SET_PROC(remSelected);

#ifndef XWFEATURE_STANDALONE_ONLY
    SET_PROC(addrChange);
#endif
#ifdef XWFEATURE_SEARCHLIMIT
    SET_PROC(getTraySearchLimits);
#endif
#ifdef SHOW_PROGRESS
    SET_PROC(engineStarting);
    SET_PROC(engineStopping);
#endif
#undef SET_PROC
    return (XW_UtilCtxt*)util;
} /* makeUtil */

void
destroyUtil( XW_UtilCtxt** utilc )
{
    AndUtil* util = (AndUtil*)*utilc;
    JNIEnv *env = *util->env;

    int ii;
    for ( ii = 0; ii < VSIZE(util->userStrings); ++ii ) {
        XP_UCHAR* ptr = util->userStrings[ii];
        if ( NULL != ptr ) {
            XP_FREE( util->util.mpool, ptr );
        }
    }

    if ( NULL != util->jutil ) {
        (*env)->DeleteGlobalRef( env, util->jutil );
    }
    XP_FREE( util->util.mpool, util->util.vtable );
    XP_FREE( util->util.mpool, util );
    *utilc = NULL;
}
