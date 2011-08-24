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
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import junit.framework.Assert;

import org.eehouse.android.xw4.jni.CommonPrefs;


public class DlgDelegate {

    public static final int DIALOG_ABOUT = 1;
    public static final int DIALOG_OKONLY = 2;
    public static final int DIALOG_NOTAGAIN = 3;
    public static final int CONFIRM_THEN = 4;
    public static final int TEXT_OR_HTML_THEN = 5;
    public static final int DLG_DICTGONE = 6;
    public static final int DIALOG_LAST = DLG_DICTGONE;

    public static final String MSG = "msg";

    private int m_msgID;
    private String m_msg;
    private Runnable m_proc = null;
    private int m_prefsKey;
    private Activity m_activity;
    private String m_dictName = null;
    DialogInterface.OnClickListener m_then;

    public interface TextOrHtmlClicked {
        public void clicked( boolean choseText );
    };
    private TextOrHtmlClicked m_txt_or_html;

    public DlgDelegate( Activity activity, Bundle bundle ) 
    {
        m_activity = activity;

        if ( null != bundle ) {
            m_msg = bundle.getString( MSG );
        }
    }

    public void onSaveInstanceState( Bundle outState ) 
    {
        outState.putString( MSG, m_msg );
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
        case CONFIRM_THEN:
            dialog = createConfirmThenDialog();
            break;
        case TEXT_OR_HTML_THEN:
            dialog = createHtmlThenDialog();
            break;
        case DLG_DICTGONE:
            dialog = createDictGoneDialog();
            break;
        }
        return dialog;
    }

    public void onPrepareDialog( int id, Dialog dialog )
    {
        AlertDialog ad = (AlertDialog)dialog;
        DialogInterface.OnClickListener lstnr;

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
        case CONFIRM_THEN:
            // I'm getting an occasional 0 here on device only.  May
            // be related to screen orientation changes.  Let's be safe
            ad.setMessage( m_msg );
            ad.setButton( AlertDialog.BUTTON_POSITIVE, 
                          m_activity.getString( R.string.button_ok ), m_then );
            break;
        case TEXT_OR_HTML_THEN:
            lstnr = new DialogInterface.OnClickListener() {
                    public void onClick( DialogInterface dlg, int button ) {
                        if ( null != m_txt_or_html ) {
                            m_txt_or_html.
                                clicked( button == AlertDialog.BUTTON_POSITIVE );
                        }
                    }
                };
            ad.setButton( AlertDialog.BUTTON_POSITIVE, 
                          m_activity.getString( R.string.button_text ),
                          lstnr );
            ad.setButton( AlertDialog.BUTTON_NEGATIVE, 
                          m_activity.getString( R.string.button_html ),
                          lstnr );
            break;
        }
    }

    public void showOKOnlyDialog( int msgID )
    {
        m_msgID = msgID;
        m_activity.showDialog( DIALOG_OKONLY );
    }

    public void showDictGoneFinish()
    {
        m_activity.showDialog( DLG_DICTGONE );
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

    public void showConfirmThen( String msg, DialogInterface.OnClickListener then )
    {
        m_msg = msg;
        m_then = then;
        m_activity.showDialog( CONFIRM_THEN );
    }

    public void showTextOrHtmlThen( TextOrHtmlClicked txtOrHtml )
    {
        m_txt_or_html = txtOrHtml;
        m_activity.showDialog( TEXT_OR_HTML_THEN );
    }

    public void doSyncMenuitem()
    {
        if ( null == DBUtils.getRelayIDs( m_activity, false ) ) {
            showOKOnlyDialog( R.string.no_games_to_refresh );
        } else {
            RelayReceiver.RestartTimer( m_activity, true );
            Toast.makeText( m_activity, 
                            m_activity.getString( R.string.msgs_progress ),
                            Toast.LENGTH_LONG ).show();
        }
    }

    private Dialog createAboutDialog()
    {
        final View view = Utils.inflate( m_activity, R.layout.about_dlg );
        TextView vers = (TextView)view.findViewById( R.id.version_string );
        vers.setText( String.format( m_activity.getString(R.string.about_versf), 
                                     m_activity.getString(R.string.app_version),
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
        Dialog dialog = null;
        if ( 0 != m_msgID ) {
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
                        CommonPrefs.setPrefsBoolean( m_activity, m_prefsKey, 
                                                     true );
                        if ( null != m_proc ) {
                            m_proc.run();
                        }
                    }
                };

            dialog = new AlertDialog.Builder( m_activity )
                .setTitle( R.string.newbie_title )
                .setMessage( m_msgID )
                .setPositiveButton( R.string.button_ok, lstnr_p )
                .setNegativeButton( R.string.button_notagain, lstnr_n )
                .create();
        }
        return dialog;
    } // createNotAgainDialog

    private Dialog createConfirmThenDialog()
    {
        Dialog dialog = new AlertDialog.Builder( m_activity )
            .setTitle( R.string.query_title )
            .setMessage( "" )
            .setPositiveButton( R.string.button_ok, null ) // will change
            .setNegativeButton( R.string.button_cancel, null )
            .create();
        return dialog;
    }

    private Dialog createHtmlThenDialog()
    {
        return new AlertDialog.Builder( m_activity )
            .setTitle( R.string.query_title )
            .setMessage( R.string.text_or_html )
            .setPositiveButton( R.string.button_text, null ) // will change
            .setNegativeButton( R.string.button_html, null )
            .create();
    }

    private Dialog createDictGoneDialog()
    {
        Dialog dialog = new AlertDialog.Builder( m_activity )
            .setTitle( R.string.no_dict_title )
            .setMessage( R.string.no_dict_finish )
            .setPositiveButton( R.string.button_close_game, null )
            .create();

        dialog.setOnDismissListener( new DialogInterface.OnDismissListener() {
                public void onDismiss( DialogInterface di ) {
                    m_activity.finish();
                }
            } );

        return dialog;
    }

}
