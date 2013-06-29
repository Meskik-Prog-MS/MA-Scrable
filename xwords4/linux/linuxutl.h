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


#ifndef _LINUXUTL_H_
#define _LINUXUTL_H_

#include "xptypes.h"
#include "dictnry.h"
#include "util.h"
#include "main.h"

#ifdef DEBUG
void linux_debugf(const char*, ...)
    __attribute__ ((format (printf, 1, 2)));
#endif

DictionaryCtxt* linux_dictionary_make( MPFORMAL const LaunchParams* mainParams,
                                       const char* dictFileName, XP_Bool useMMap );


void linux_util_vt_init( MPFORMAL XW_UtilCtxt* util );
void linux_util_vt_destroy( XW_UtilCtxt* util );

const XP_UCHAR* linux_getErrString( UtilErrID id, XP_Bool* silent );

void formatConfirmTrade( const XP_UCHAR** tiles, XP_U16 nTiles, char* buf, 
                         XP_U16 buflen );

void initNoConnStorage( CommonGlobals* cGlobals );
XP_Bool storeNoConnMsg( CommonGlobals* cGlobals, const XP_U8* msg, XP_U16 len,
                        const XP_UCHAR* relayID );
void writeNoConnMsgs( CommonGlobals* cGlobals, int fd );

void figureMD5Sum( const XP_U8* data, XP_U16 datalen, 
                   XP_UCHAR* buf, XP_U16* buflen );


#ifdef STREAM_VERS_BIGBOARD
void setSquareBonuses( const CommonGlobals* cGlobals );
#else
# define setSquareBonuses( cg )
#endif

#endif
