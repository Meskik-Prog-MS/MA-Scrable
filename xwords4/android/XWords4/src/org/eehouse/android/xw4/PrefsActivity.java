/* -*- compile-command: "cd ../../../../../; ant debug install"; -*- */
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

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.Button;
import java.io.File;

public class PrefsActivity extends PreferenceActivity 
    implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final int REVERT_COLORS = 1;
    private static final int REVERT_ALL = 2;
    public static final int CONFIRM_SMS = 3;
    public static final int EXPLAIN_TITLE = 4;

    private String m_keyLogging;
    private String m_smsToasting;
    private String m_smsEnable;
    private String m_downloadPath;
    private String m_thumbEnabled;
    private String m_thumbSize;
    private String m_hideTitle;


    @Override
    protected Dialog onCreateDialog( int id )
    {
        Dialog dialog = null;
        DialogInterface.OnClickListener lstnr = null;
        int confirmID = 0;

        switch( id ) {
        case REVERT_COLORS:
            confirmID = R.string.confirm_revert_colors;
            lstnr = new DialogInterface.OnClickListener() {
                    public void onClick( DialogInterface dlg, int item ) {
                        SharedPreferences sp =
                            getPreferenceScreen().getSharedPreferences();
                        SharedPreferences.Editor editor = sp.edit();
                        int[] colorKeys = {
                            R.string.key_player0,
                            R.string.key_player1,
                            R.string.key_player2,
                            R.string.key_player3,
                            R.string.key_bonus_l2x,
                            R.string.key_bonus_l3x,
                            R.string.key_bonus_w2x,
                            R.string.key_bonus_w3x,
                            R.string.key_tile_back,
                            R.string.key_clr_crosshairs,
                            R.string.key_empty,
                            R.string.key_background,
                            R.string.key_clr_bonushint,
                        };
                        for ( int colorKey : colorKeys ) {
                            editor.remove( getString(colorKey) );
                        }
                        editor.commit();
                        relaunch();
                    }
                };
            break;
        case REVERT_ALL:
            confirmID = R.string.confirm_revert_all;
            lstnr = new DialogInterface.OnClickListener() {
                    public void onClick( DialogInterface dlg, int item ) {
                        SharedPreferences sp =
                            getPreferenceScreen().getSharedPreferences();
                        SharedPreferences.Editor editor = sp.edit();
                        editor.clear();
                        editor.commit();
                        relaunch();
                    }
                };
            break;
        case CONFIRM_SMS:
            dialog = SMSCheckBoxPreference.onCreateDialog( this, id );
            break;
        case EXPLAIN_TITLE:
            dialog = new AlertDialog.Builder( this )
                .setMessage( R.string.no_hide_titlebar )
                .setTitle( R.string.info_title )
                .setPositiveButton( R.string.button_ok, null )
                .create();
            break;
        }

        if ( null == dialog && null != lstnr ) {
            dialog = new AlertDialog.Builder( this )
                .setTitle( R.string.query_title )
                .setMessage( confirmID )
                .setPositiveButton( R.string.button_ok, lstnr )
                .setNegativeButton( R.string.button_cancel, null )
                .create();
        }
        return dialog;
    }

    @Override
    protected void onCreate( Bundle savedInstanceState )
    {
        super.onCreate(savedInstanceState);

        // Load the preferences from an XML resource
        addPreferencesFromResource( R.xml.xwprefs );
        setContentView( R.layout.prefs_w_buttons );

        m_keyLogging = getString( R.string.key_logging_on );
        m_smsToasting = getString( R.string.key_show_sms );
        m_smsEnable = getString( R.string.key_enable_sms );
        m_downloadPath = getString( R.string.key_download_path );
        m_thumbEnabled = getString( R.string.key_thumb_enabled );
        m_thumbSize = getString( R.string.key_thumbsize );
        m_hideTitle = getString( R.string.key_hide_title );

        Button button = (Button)findViewById( R.id.revert_colors );
        button.setOnClickListener( new View.OnClickListener() {
                public void onClick( View v ) {
                    showDialog( REVERT_COLORS );
                }
            } );
        button = (Button)findViewById( R.id.revert_all );
        button.setOnClickListener(new View.OnClickListener() {
                public void onClick( View v ) {
                    showDialog( REVERT_ALL );
                }
            } );
    }
    
    @Override
    protected void onResume() 
    {
        super.onResume();
        getPreferenceScreen().getSharedPreferences().
            registerOnSharedPreferenceChangeListener(this);   
   }

    @Override
    protected void onPause() 
    {
        super.onPause();
        getPreferenceScreen().getSharedPreferences().
            unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged( SharedPreferences sp, String key ) 
    {
        if ( key.equals( m_keyLogging ) ) {
            DbgUtils.logEnable( sp.getBoolean( key, false ) );
        } else if ( key.equals( m_smsToasting ) ) {
            SMSService.smsToastEnable( sp.getBoolean( key, false ) );
        } else if ( key.equals( m_smsEnable ) ) {
            if ( sp.getBoolean( key, true ) ) {
                SMSService.checkForInvites( this );
            } else {
                SMSService.stopService( this );
                XWPrefs.setHaveCheckedSMS( this, false );
            }
        } else if ( key.equals( m_downloadPath ) ) {
            String value = sp.getString( key, null );
            if ( null != value ) {
                File dir = new File( value );
                String msg = null;
                if ( !dir.exists() ) {
                    msg = String.format( "%s does not exist", value );
                } else if ( !dir.isDirectory() ) {
                    msg = String.format( "%s is not a directory", value );
                } else if ( !dir.canWrite() ) {
                    msg = String.format( "Cannot write to %s", value );
                }
                if ( null != msg ) {
                    Utils.showToast( this, msg );
                }
            }
            DictUtils.invalDictList();
        } else if ( key.equals( m_thumbEnabled )
                    || key.equals( m_thumbSize ) ) {
            DBUtils.clearThumbnails( this );
        } else if ( key.equals( m_hideTitle ) ) {
            if ( sp.getBoolean( key, false ) && ABUtils.haveActionBar() ) {
                CheckBoxPreference pref
                    = (CheckBoxPreference)findPreference(key);
                pref.setChecked( false );
                pref.setEnabled( false );
                showDialog( EXPLAIN_TITLE );
            }
        } 
    }

    private void relaunch()
    {
        PreferenceManager.setDefaultValues( this, R.xml.xwprefs,
                                            false );

        // Now replace this activity with a new copy
        // so the new values get loaded.
        Utils.launchSettings( this );
        finish();
    }

}
