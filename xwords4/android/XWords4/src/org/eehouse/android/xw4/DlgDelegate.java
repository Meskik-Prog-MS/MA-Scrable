/* -*- compile-command: "cd ../../../../../; ant install"; -*- */
/*
 * Copyright 2009-2010 by Eric House (xwords@eehouse.org).  All
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

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.view.LayoutInflater;
import android.net.Uri;
import junit.framework.Assert;
import android.view.View;
import android.widget.TextView;
import android.app.AlertDialog;

import org.eehouse.android.xw4.jni.CommonPrefs;


public class DlgDelegate {

    public static final int DIALOG_ABOUT = 1;
    public static final int DIALOG_OKONLY = 2;
    public static final int DIALOG_NOTAGAIN = 3;
    public static final int WARN_NODICT = 4;
    public static final int DIALOG_LAST = WARN_NODICT;

    private int m_msgID;
    private Runnable m_proc = null;
    private int m_prefsKey;
    private Activity m_activity;
    private String m_dictName = null;

    public DlgDelegate( Activity activity ) {
        m_activity = activity;
    }
    
    public Dialog onCreateDialog( int id )
    {
        Dialog dialog = null;
        switch( id ) {
        case DIALOG_ABOUT:
            dialog = createAboutDialog();
            break;
        case DIALOG_OKONLY:
            dialog = createOKDialog();
            break;
        case DIALOG_NOTAGAIN:
            dialog = createNotAgainDialog();
            break;
        case WARN_NODICT:
            dialog = createNoDictDialog();
            break;
        }
        return dialog;
    }

    public void onPrepareDialog( int id, Dialog dialog )
    {
        AlertDialog ad = (AlertDialog)dialog;
        switch( id ) {
        case DIALOG_ABOUT:
            break;
        case DIALOG_NOTAGAIN:
            // Don't think onclick listeners need to be reset.  They
            // reference instance vars that are changed each time
            // showNotAgainDlgThen() is called
            // FALLTHRU
        case DIALOG_OKONLY:
            ad.setMessage( m_activity.getString(m_msgID) );
            break;
        case WARN_NODICT:
            String format = m_activity.getString( R.string.no_dictf );
            String msg = String.format( format, m_dictName );
            ((AlertDialog)dialog).setMessage( msg );
            break;
        }
    }

    public void showOKOnlyDialog( int msgID )
    {
        m_msgID = msgID;
        m_activity.showDialog( DIALOG_OKONLY );
    }

    public void showAboutDialog()
    {
        m_activity.showDialog( DIALOG_ABOUT );
    }

    public void showNotAgainDlgThen( int msgID, int prefsKey,
                                     Runnable proc )
    {
        boolean set = CommonPrefs.getPrefsBoolean( m_activity, prefsKey, false );
        if ( set ) {
            if ( null != proc ) {
                proc.run();
            } 
        } else {
            m_msgID = msgID;
            m_proc = proc;
            m_prefsKey = prefsKey;
            m_activity.showDialog( DIALOG_NOTAGAIN );
        }
    }

    public void showNoDict( String name )
    {
        m_dictName = name;
        m_activity.showDialog( WARN_NODICT );
    }

    private Dialog createAboutDialog()
    {
        LayoutInflater factory = LayoutInflater.from( m_activity );
        final View view = factory.inflate( R.layout.about_dlg, null );
        TextView vers = (TextView)view.findViewById( R.id.version_string );
        vers.setText( String.format( m_activity.getString(R.string.about_versf), 
                                     XWConstants.VERSION_STR, 
                                     GitVersion.VERS ) );

        TextView xlator = (TextView)view.findViewById( R.id.about_xlator );
        String str = m_activity.getString( R.string.xlator );
        if ( str.length() > 0 ) {
            xlator.setText( str );
        } else {
            xlator.setVisibility( View.GONE );
        }

        return new AlertDialog.Builder( m_activity )
            .setIcon( R.drawable.icon48x48 )
            .setTitle( R.string.app_name )
            .setView( view )
            .setPositiveButton( R.string.changes_button,
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick( DialogInterface dlg, 
                                                         int which )
                                    {
                                        FirstRunDialog.show( m_activity, true );
                                    }
                                } )
            .create();
    }

    private Dialog createOKDialog()
    {
        return new AlertDialog.Builder( m_activity )
            .setTitle( R.string.info_title )
            .setMessage( m_msgID )
            .setPositiveButton( R.string.button_ok, null )
            .create();
    }

    private Dialog createNotAgainDialog()
    {
        DialogInterface.OnClickListener lstnr_p = 
            new DialogInterface.OnClickListener() {
                public void onClick( DialogInterface dlg, int item ) {
                    if ( null != m_proc ) {
                        m_proc.run();
                    }
                }
            };

        DialogInterface.OnClickListener lstnr_n = 
            new DialogInterface.OnClickListener() {
                public void onClick( DialogInterface dlg, int item ) {
                    CommonPrefs.setPrefsBoolean( m_activity, m_prefsKey, true );
                    if ( null != m_proc ) {
                        m_proc.run();
                    }
                }
            };

        return new AlertDialog.Builder( m_activity )
            .setTitle( R.string.info_title )
            .setMessage( m_msgID )
            .setPositiveButton( R.string.button_ok, lstnr_p )
            .setNegativeButton( R.string.button_notagain, lstnr_n )
            .create();
    } // createNotAgainDialog

    private Dialog createNoDictDialog()
    {
        Dialog dialog = new AlertDialog.Builder( m_activity )
            .setTitle( R.string.no_dict_title )
            .setMessage( "" ) // required to get to change it later
            .setPositiveButton( R.string.button_ok, null )
            .setNegativeButton( R.string.button_download,
                                new DialogInterface.OnClickListener() {
                                    public void onClick( DialogInterface dlg, 
                                                         int item ) {
                                        Intent intent = Utils
                                            .mkDownloadActivity(m_activity);
                                        m_activity.startActivity( intent );
                                    }
                                })
            .create();
        dialog.setOnDismissListener( new DialogInterface.OnDismissListener() {
                public void onDismiss( DialogInterface di ) {
                    m_activity.finish();
                }
            });
        return dialog;
    }
}
