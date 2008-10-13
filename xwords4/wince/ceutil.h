/* -*-mode: C; fill-column: 77; c-basic-offset: 4; -*- */
/* 
 * Copyright 2002-2004 by Eric House (xwords@eehouse.org).  All rights reserved.
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

#ifndef _CEUTIL_H_
#define _CEUTIL_H_

#include "stdafx.h" 
#include "cemain.h"

void ceSetDlgItemText( HWND hDlg, XP_U16 id, const XP_UCHAR* buf );
void ceGetDlgItemText( HWND hDlg, XP_U16 id, XP_UCHAR* buf, XP_U16* bLen );

void ceSetDlgItemNum( HWND hDlg, XP_U16 id, XP_S32 num );
XP_S32 ceGetDlgItemNum( HWND hDlg, XP_U16 id );

void ceSetDlgItemFileName( HWND hDlg, XP_U16 id, XP_UCHAR* buf );

void positionDlg( HWND hDlg );

void ce_selectAndShow( HWND hDlg, XP_U16 resID, XP_U16 index );

void ceShowOrHide( HWND hDlg, XP_U16 resID, XP_Bool visible );
void ceEnOrDisable( HWND hDlg, XP_U16 resID, XP_Bool visible );

XP_Bool ceGetChecked( HWND hDlg, XP_U16 resID );
void ceSetChecked( HWND hDlg, XP_U16 resID, XP_Bool check );

void ceCenterCtl( HWND hDlg, XP_U16 resID );

/* set vHeight to 0 to turn off scrolling */
typedef enum { DLG_STATE_NONE = 0
               , DLG_STATE_TRAPBACK = 1 
               , DLG_STATE_DONEONLY = 2 
} DlgStateTask;
#define MAX_EDITS 8             /* the most any control has */
typedef struct CeDlgHdr {
    CEAppGlobals* globals;
    HWND hDlg;

    /* Below this line is private to ceutil.c */
    DlgStateTask doWhat;
    XP_U16 nPage;
    HWND edits[MAX_EDITS];
} CeDlgHdr;
void ceDlgSetup( CeDlgHdr* dlgHdr, HWND hDlg, DlgStateTask doWhat );
void ceDlgComboShowHide( CeDlgHdr* dlgHdr, XP_U16 baseId );
XP_Bool ceDoDlgHandle( CeDlgHdr* dlgHdr, UINT message, WPARAM wParam, LPARAM lParam);

/* Are we drawing things in landscape mode? */
XP_Bool ceIsLandscape( CEAppGlobals* globals );

void ceSetLeftSoftkey( CEAppGlobals* globals, XP_U16 id );

#if defined _WIN32_WCE
void ceSizeIfFullscreen( CEAppGlobals* globals, HWND hWnd );
#else
# define ceSizeIfFullscreen( globals, hWnd )
#endif

#ifdef OVERRIDE_BACKKEY
void trapBackspaceKey( HWND hDlg );
#else
# define trapBackspaceKey( hDlg )
#endif


#endif
