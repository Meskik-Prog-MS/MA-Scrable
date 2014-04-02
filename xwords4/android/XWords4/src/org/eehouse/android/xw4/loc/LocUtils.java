/* -*- compile-command: "find-and-ant.sh debug install"; -*- */
/*
 * Copyright 2014 by Eric House (xwords@eehouse.org).  All rights reserved.
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

package org.eehouse.android.xw4.loc;

import android.content.Context;
import android.util.AttributeSet;

import junit.framework.Assert;

import org.eehouse.android.xw4.R;
import org.eehouse.android.xw4.DbgUtils;

public class LocUtils {

    public interface LocIface {
        void setText( CharSequence text );
    }

    public static void loadStrings( Context context, AttributeSet as, LocIface view )
    {
        // There should be a way to look up the index of "strid" but I don't
        // have it yet.  This got things working.
        int count = as.getAttributeCount();
        for ( int ii = 0; ii < count; ++ii ) {
            if ( "strid".equals( as.getAttributeName(ii) ) ) {
                String value = as.getAttributeValue(ii);
                Assert.assertTrue( '@' == value.charAt(0) );
                int id = Integer.parseInt( value.substring(1) );
                view.setText( context.getString( id ).toUpperCase() );
                break;
            }
        }
        
    }
}
