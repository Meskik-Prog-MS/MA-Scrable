/* -*-mode: C; fill-column: 78; c-basic-offset: 4; -*- */
/* 
 * Copyright 2014 by Eric House (xwords@eehouse.org).  All rights reserved.
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


#ifndef _DICTMGR_H_
#define _DICTMGR_H_

#ifdef CPLUS
extern "C" {
#endif

#include "mempool.h"

typedef struct DictMgrCtxt DictMgrCtxt;

DictMgrCtxt* dmgr_make( MPFORMAL_NOCOMMA );
void dmgr_destroy( DictMgrCtxt* dmgr, XWEnv xwe );

void dmgr_put( DictMgrCtxt* dmgr, XWEnv xwe, const XP_UCHAR* key, DictionaryCtxt* dict );
DictionaryCtxt* dmgr_get( DictMgrCtxt* dmgr, XWEnv xwe, const XP_UCHAR* key );

#ifdef CPLUS
}
#endif

#endif
