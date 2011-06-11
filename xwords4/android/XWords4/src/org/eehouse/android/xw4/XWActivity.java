/* -*- compile-command: "cd ../../../../../; ant install"; -*- */
/*
 * Copyright 2010 by Eric House (xwords@eehouse.org).  All rights
 * reserved.
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
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import junit.framework.Assert;
import android.view.View;
import android.widget.TextView;
import android.app.AlertDialog;
import android.os.Bundle;

import org.eehouse.android.xw4.jni.CommonPrefs;

public class XWActivity extends Activity {

    private DlgDelegate m_delegate;

    protected void onCreate( Bundle savedInstanceState ) 
    {
        Utils.logf( "%s.onCreate()", getClass().getName() );
        super.onCreate( savedInstanceState );
        m_delegate = new DlgDelegate( this );
    }

    @Override
    protected void onStart()
    {
        Utils.logf( "%s.onStart()", getClass().getName() );
        super.onStart();
        DispatchNotify.SetRunning( this );
    }

    @Override
    protected void onStop()
    {
        Utils.logf( "%s.onStop()", getClass().getName() );
        DispatchNotify.ClearRunning( this );
        super.onStop();
    }

    @Override
    protected void onDestroy()
    {
        Utils.logf( "%s.onDestroy()", getClass().getName() );
        super.onDestroy();
    }

    @Override
    protected Dialog onCreateDialog( int id )
    {
        Utils.logf( "%s.onCreateDialog() called", getClass().getName() );
        Dialog dialog = m_delegate.onCreateDialog( id );
        if ( null != dialog ) {
            setRemoveOnDismiss( dialog, id );
        }
        return dialog;
    }

    protected void setRemoveOnDismiss( Dialog dialog, int id )
    {
        m_delegate.setRemoveOnDismiss( dialog, id );
    }

    @Override
    protected void onPrepareDialog( int id, Dialog dialog )
    {
        m_delegate.onPrepareDialog( id, dialog );
    }

    // these are duplicated in XWListActivity -- sometimes multiple
    // inheritance would be nice to have...
    protected void showAboutDialog()
    {
        m_delegate.showAboutDialog();
    }

    protected void showNotAgainDlgThen( int msgID, int prefsKey,
                                        Runnable proc )
    {
        m_delegate.showNotAgainDlgThen( msgID, prefsKey, proc );
    }

    protected void showOKOnlyDialog( int msgID )
    {
        m_delegate.showOKOnlyDialog( msgID );
    }

    protected void showConfirmThen( int msgID, 
                                    DialogInterface.OnClickListener action )
    {
        m_delegate.showConfirmThen( getString(msgID), action );
    }

    protected void doSyncMenuitem()
    {
        m_delegate.doSyncMenuitem();
    }
}
