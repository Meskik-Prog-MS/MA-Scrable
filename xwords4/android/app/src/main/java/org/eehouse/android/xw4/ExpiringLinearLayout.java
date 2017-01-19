/* -*- compile-command: "find-and-gradle.sh installXw4Debug"; -*- */
/*
 * Copyright 2012 by Eric House (xwords@eehouse.org).  All
 * rights reserved.
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
package org.eehouse.android.xw4;

import android.content.Context;
import android.graphics.Canvas;
import android.os.Handler;
import android.util.AttributeSet;
import android.widget.LinearLayout;

public class ExpiringLinearLayout extends LinearLayout {
    private ExpiringDelegate m_delegate;

    public ExpiringLinearLayout( Context context, AttributeSet as ) {
        super( context, as );
    }

    public void setPct( Handler handler, boolean haveTurn,
                        boolean haveTurnLocal, long startSecs )
    {
        if ( null == m_delegate ) {
            m_delegate = new ExpiringDelegate( getContext(), this );
            m_delegate.setHandler( handler );
        }
        m_delegate.configure( haveTurn, haveTurnLocal, startSecs );
    }

    public boolean hasDelegate()
    {
        return null != m_delegate;
    }

    @Override
    // not called unless setWillNotDraw( false ) called
    protected void onDraw( Canvas canvas )
    {
        super.onDraw( canvas );
        if ( null != m_delegate ) {
            m_delegate.onDraw( canvas );
        }
    }
}
