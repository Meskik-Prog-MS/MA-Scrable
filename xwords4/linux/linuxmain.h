/* -*- compile-command: "make MEMDEBUG=TRUE -j3"; -*- */ 
/* 
 * Copyright 1997-2011 by Eric House (xwords@eehouse.org).  All rights
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
#ifndef _LINUXMAIN_H_
#define _LINUXMAIN_H_

#include "main.h"
#include "dictnry.h"
#include "mempool.h"
#include "comms.h"
#include "memstream.h"
/* #include "compipe.h" */

extern int errno;

typedef struct LinuxBMStruct {
    XP_U8 nCols;
    XP_U8 nRows;
    XP_U8 nBytes;
} LinuxBMStruct;

int initListenerSocket( int port );
XP_S16 linux_send( const XP_U8* buf, XP_U16 buflen, 
                   const CommsAddrRec* addrRec, 
                   XP_U32 gameID, void* closure );
#ifndef XWFEATURE_STANDALONE_ONLY
# define LINUX_SEND linux_send
#else
# define LINUX_SEND NULL
#endif

#ifdef COMMS_HEARTBEAT
void linux_reset( void* closure );
#endif
int linux_relay_receive( CommonGlobals* cGlobals, unsigned char* buf, 
                         int bufSize );

XP_Bool linuxFireTimer( CommonGlobals* cGlobals, XWTimerReason why );


XWStreamCtxt* stream_from_msgbuf( CommonGlobals* cGlobals, 
                                  const unsigned char* bufPtr, XP_U16 nBytes );
XP_UCHAR* strFromStream( XWStreamCtxt* stream );

void catGameHistory( CommonGlobals* cGlobals );
void catOnClose( XWStreamCtxt* stream, void* closure );
void sendOnClose( XWStreamCtxt* stream, void* closure );

void catFinalScores( const CommonGlobals* cGlobals, XP_S16 quitter );
XP_Bool file_exists( const char* fileName );
XWStreamCtxt* streamFromFile( CommonGlobals* cGlobals, char* name, 
                              void* closure );
XWStreamCtxt* streamFromDB( CommonGlobals* cGlobals, void* closure );
void writeToFile( XWStreamCtxt* stream, void* closure );
XP_Bool getDictPath( const LaunchParams *params, const char* name, 
                     char* result, int resultLen );
GSList* listDicts( const LaunchParams *params );
void saveGame( CommonGlobals* cGlobals );

int blocking_read( int fd, unsigned char* buf, int len );

void linux_close_socket( CommonGlobals* cGlobals );

#ifdef KEYBOARD_NAV
XP_Bool linShiftFocus( CommonGlobals* cGlobals, XP_Key key,
                       const BoardObjectType* order,
                       BoardObjectType* nxtP );
#endif

void read_pipe_then_close( CommonGlobals* cGlobals, 
                           const TransportProcs* procs );
void do_nbs_then_close( CommonGlobals* cGlobals, 
                        const TransportProcs* procs );

#ifdef USE_GLIBLOOP
void setOneSecondTimer( CommonGlobals* cGlobals );
#else
# define setOneSecondTimer( cGlobals )
#endif

void setupLinuxUtilCallbacks( XW_UtilCtxt* util );
void initFromParams( CommonGlobals* cGlobals, LaunchParams* params );
void setupUtil( CommonGlobals* cGlobals );

DictionaryCtxt* makeDictForStream( CommonGlobals* cGlobals, 
                                   XWStreamCtxt* stream );
void linuxSetIsServer( CommonGlobals* cGlobals, XP_Bool isServer );
void linuxChangeRoles( CommonGlobals* cGlobals );

/* void initParams( LaunchParams* params ); */
/* void freeParams( LaunchParams* params ); */

#endif
