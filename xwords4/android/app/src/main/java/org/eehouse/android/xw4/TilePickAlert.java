/* -*- compile-command: "cd ../../../../../../../../ && ./gradlew installXw4Debug"; -*- */
/*
 * Copyright 2017 by Eric House (xwords@eehouse.org).  All rights reserved.
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

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.support.v4.app.DialogFragment;
import android.view.View;
import android.widget.Button;

import java.io.Serializable;

import junit.framework.Assert;

import org.eehouse.android.xw4.DlgDelegate.Action;
import org.eehouse.android.xw4.DlgDelegate.DlgClickNotify;
import org.eehouse.android.xw4.loc.LocUtils;

public class TilePickAlert extends XWDialogFragment
    implements TilePickView.TilePickListener {
    private static final String TPS = "TPS";
    private static final String ACTION = "ACTION";
    private TilePickView m_view;
    private TilePickState m_state;
    private Action m_action;
    private AlertDialog m_dialog;
    private int[] m_selTiles;

    public static class TilePickState implements Serializable {
        public int col;
        public int row;
        public int playerNum;
        public int[] counts;
        public String[] faces;
        public boolean isInitial;
        public int nToPick;
        
        public TilePickState( int player, String[] faces, int col, int row ) {
            this.col = col; this.row = row; this.playerNum = player;
            this.faces = faces;
            this.nToPick = 1;
        }
        public TilePickState( boolean isInitial, int playerNum, int nToPick,
                              String[] faces, int[] counts ) {
            this.playerNum = playerNum;
            this.isInitial = isInitial;
            this.nToPick = nToPick;
            this.faces = faces;
            this.counts = counts;
        }
    }

    public static TilePickAlert newInstance( Action action, TilePickState state )
    {
        TilePickAlert result = new TilePickAlert();
        Bundle args = new Bundle();
        args.putSerializable( ACTION, action );
        args.putSerializable( TPS, state );
        result.setArguments( args );
        return result;
    }

    public TilePickAlert() {}

    @Override
    public void onSaveInstanceState( Bundle bundle )
    {
        super.onSaveInstanceState( bundle );
        bundle.putSerializable( TPS, m_state );
        bundle.putSerializable( ACTION, m_action );
        m_view.saveInstanceState( bundle );
    }

    @Override
    public Dialog onCreateDialog( Bundle sis )
    {
        if ( null == sis ) {
            sis = getArguments();
        }
        m_state = (TilePickState)sis.getSerializable( TPS );
        m_action = (Action)sis.getSerializable( ACTION );
        
        Activity activity = getActivity();
        Assert.assertNotNull( activity );
        m_view = (TilePickView)LocUtils.inflate( activity, R.layout.tile_picker );
        m_view.init( this, m_state, sis );


        AlertDialog.Builder ab = LocUtils.makeAlertBuilder( activity )
            .setTitle( String.format( "Pick %d", m_state.nToPick ) )
            .setView( m_view );
        if ( null != m_state.counts ) {
            DialogInterface.OnClickListener lstnr =
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick( DialogInterface dialog, int which ) {
                        onDone();
                    }
                };
            ab.setPositiveButton( R.string.tilepick_all, lstnr );
        }
        m_dialog = ab.create();
        return m_dialog;
    }

    private void onDone()
    {
        Activity activity = getActivity();
        if ( activity instanceof DlgClickNotify ) {
            DlgClickNotify notify = (DlgClickNotify)activity;
            notify.onPosButton( m_action, m_state, m_selTiles );
        } else {
            Assert.assertTrue( !BuildConfig.DEBUG );
        }
        dismiss();
    }

    // TilePickView.TilePickListener
    @Override
    public void onTilesChanged( int nToPick, int[] newTiles )
    {
        m_selTiles = newTiles;
        boolean done = nToPick == newTiles.length;
        if ( done && null == m_state.counts ) {
            onDone();
        } else if ( null != m_dialog ) {
            int msgID = done ? android.R.string.ok : R.string.tilepick_all;
            Button button = m_dialog.getButton( AlertDialog.BUTTON_POSITIVE );
            button.setText( LocUtils.getString( getContext(), msgID ) );
        }
    }
}
