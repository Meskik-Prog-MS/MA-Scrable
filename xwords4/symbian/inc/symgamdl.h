/* -*-mode: C; fill-column: 78; c-basic-offset: 4;-*- */ 
/* 
 * Copyright 2005 by Eric House (fixin@peak.org).  All rights reserved.
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

#ifndef _SYMGAMDL_H_
#define _SYMGAMDL_H_

extern "C" {
#include "comtypes.h"
#include "xwstream.h"
#include "mempool.h"
}

#include <e32base.h>
#include <eikdialg.h>

class CXWGameInfoDlg : public CEikDialog
{
 public:
    CXWGameInfoDlg( MPFORMAL_NOCOMMA );
    ~CXWGameInfoDlg();

 private:
    void PreLayoutDynInitL();
    void HandleControlStateChangeL( TInt aControlId );
    TBool OkToExitL( TInt aKeyCode );

    MPSLOT
};

#endif
