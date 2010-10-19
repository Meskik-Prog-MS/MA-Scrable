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

import android.app.ListActivity;
import android.app.Dialog;
import android.app.AlertDialog;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.Button;
import android.view.MenuInflater;
import java.io.File;
import android.preference.PreferenceManager;
import junit.framework.Assert;

import org.eehouse.android.xw4.jni.*;

public class GamesList extends XWListActivity 
    implements DispatchNotify.HandleRelaysIface,
               RefreshMsgsTask.RefreshMsgsIface {

    private static final int WARN_NODICT = Utils.DIALOG_LAST + 1;
    private static final int CONFIRM_DELETE_ALL = Utils.DIALOG_LAST + 2;

    private GameListAdapter m_adapter;
    private String m_invalPath = null;
    private String m_missingDict;
    private Handler m_handler;

    private Object[] m_dlgObjects = null;

    @Override
    protected Dialog onCreateDialog( int id )
    {
        Dialog dialog = null;
        switch( id ) {
        case WARN_NODICT:
            dialog = new AlertDialog.Builder( this )
                .setTitle( R.string.no_dict_title )
                .setMessage( "" ) // required to get to change it later
                .setPositiveButton( R.string.button_ok, null )
                .setNegativeButton( R.string.button_download,
                    new DialogInterface.OnClickListener() {
                        public void onClick( DialogInterface dlg, int item ) {
                            Intent intent = 
                                Utils.mkDownloadActivity(GamesList.this);
                            startActivity( intent );
                        }
                    })
                .create();
            break;
        case CONFIRM_DELETE_ALL:
            DialogInterface.OnClickListener lstnr = 
                new DialogInterface.OnClickListener() {
                    public void onClick( DialogInterface dlg, int item ) {
                        for( String game:GameUtils.gamesList(GamesList.this)) {
                            GameUtils.deleteGame( GamesList.this, game  );
                        }
                        m_adapter = new GameListAdapter( GamesList.this );
                        setListAdapter( m_adapter );
                    }
                };
            dialog = new AlertDialog.Builder( this )
                .setTitle( R.string.query_title )
                .setMessage( R.string.confirm_delete_all )
                .setPositiveButton( R.string.button_ok, lstnr )
                .setNegativeButton( R.string.button_cancel, null )
                .create();
            break;
        default:
            dialog = Utils.onCreateDialog( this, id, m_dlgObjects );
        }
        return dialog;
    }

    @Override
    protected void onPrepareDialog( int id, Dialog dialog )
    {
        if ( WARN_NODICT == id ) {
            String format = getString( R.string.no_dictf );
            String msg = String.format( format, m_missingDict );
            ((AlertDialog)dialog).setMessage( msg );
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) 
    {
        super.onCreate(savedInstanceState);

        m_handler = new Handler();

        PreferenceManager.setDefaultValues( this, R.xml.xwprefs, false );

        setContentView(R.layout.game_list);

        // setDefaultKeyMode(DEFAULT_KEYS_SHORTCUT);

        registerForContextMenu( getListView() );

        Button newGameB = (Button)findViewById(R.id.new_game);
        newGameB.setOnClickListener( new View.OnClickListener() {
                @Override
                public void onClick( View v ) {
                    saveNew( new CurGameInfo( GamesList.this ) );
                    onContentChanged();
                }
            } );

        m_adapter = new GameListAdapter( this );
        setListAdapter( m_adapter );

        FirstRunDialog.show( this, false );

        RelayReceiver.RestartTimer( this );
    }

    @Override
    protected void onStart()
    {
        super.onStart();
        DispatchNotify.SetRelayIDsHandler( this );
    }

    @Override
    protected void onStop()
    {
        super.onStop();
        DispatchNotify.SetRelayIDsHandler( null );
    }

    // DispatchNotify.HandleRelaysIface interface
    public void HandleRelaysIDs( final String[] relayIDs )
    {
        m_handler.post( new Runnable() {
                public void run() {
                    if ( null == relayIDs ) {
                        Utils.logf( "relayIDs null" );
                    } else if ( relayIDs.length == 0 ) {
                        Utils.logf( "relayIDs empty" );
                    } else {
                        for ( String relayID : relayIDs ) {
                            Utils.logf( "HandleRelaysIDs: got %s", relayID );
                            String path = DBUtils.getPathFor( GamesList.this,
                                                              relayID );
                            m_adapter.inval( path );
                        }
                        onContentChanged();
                    }
                }
            } );
    }

    // RefreshMsgsTask.RefreshMsgsIface interface
    public void RefreshMsgsResults( String[] relayIDs )
    {
        HandleRelaysIDs( relayIDs );
    }

    // @Override
    // protected void onNewIntent( Intent intent )
    // {
    //     RelayService.CancelNotification();

    //     Utils.logf( "onNewIntent called" );
    //     String[] relayIDs = intent.
    //         getStringArrayExtra( getString(R.string.relayids_extra) );
    //     HandleRelaysIDs( relayIDs );
    // }

    @Override
    public void onWindowFocusChanged( boolean hasFocus )
    {
        super.onWindowFocusChanged( hasFocus );
        if ( hasFocus && null != m_invalPath ) {
            m_adapter.inval( m_invalPath );
            m_invalPath = null;
            onContentChanged();
        }
    }

    @Override
    public void onCreateContextMenu( ContextMenu menu, View view, 
                                     ContextMenuInfo menuInfo ) 
    {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate( R.menu.games_list_item_menu, menu );
        if ( CommonPrefs.getClickLaunches( this ) ) {
            menu.removeItem( R.id.list_item_play );
        }
    }
        
    @Override
    public boolean onContextItemSelected( MenuItem item ) 
    {
        AdapterView.AdapterContextMenuInfo info;
        try {
            info = (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
        } catch (ClassCastException e) {
            Utils.logf( "bad menuInfo:" + e.toString() );
            return false;
        }

        return handleMenuItem( item.getItemId(), info.position );
    } // onContextItemSelected

    public boolean onCreateOptionsMenu( Menu menu )
    {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate( R.menu.games_list_menu, menu );
        return true;
    }

    public boolean onOptionsItemSelected( MenuItem item )
    {
        boolean handled = true;
        Intent intent;

        switch (item.getItemId()) {
        case R.id.gamel_menu_delete_all:
            if ( GameUtils.gamesList( this ).length > 0 ) {
                showDialog( CONFIRM_DELETE_ALL );
            }
            handled = true;
            break;

        case R.id.gamel_menu_dicts:
            intent = new Intent( this, DictsActivity.class );
            startActivity( intent );
            break;

        case R.id.gamel_menu_checkmoves:
            if ( null == DBUtils.getRelayIDNoMsgs( this ) ) {
                m_dlgObjects = new Object[] {
                    new Integer(R.string.no_games_to_refresh)
                };
                showDialog( Utils.DIALOG_OKONLY );
            } else {
                new RefreshMsgsTask( this, this ).execute();
            }
            break;

        case R.id.gamel_menu_prefs:
            intent = new Intent( this, PrefsActivity.class );
            startActivity( intent );
            break;

        case R.id.gamel_menu_about:
            m_dlgObjects = null;
            showDialog( Utils.DIALOG_ABOUT );
            break;

        // case R.id.gamel_menu_view_hidden:
        //     Utils.notImpl( this );
        //     break;
        default:
            handled = false;
        }

        return handled;
    }

    @Override
    protected void onListItemClick( ListView l, View v, int position, long id )
    {
        super.onListItemClick( l, v, position, id );

        if ( CommonPrefs.getClickLaunches( this ) ) {
            handleMenuItem( R.id.list_item_play, position );
        } else {
            v.showContextMenu();
        }
    }

    private boolean handleMenuItem( int menuID, int position ) 
    {
        boolean handled = true;
        byte[] stream;
        String invalPath = null;

        String path = GameUtils.gamesList( this )[position];
    
        if ( R.id.list_item_delete == menuID ) {
            GameUtils.deleteGame( this, path );
            invalPath = path;
        } else {
            String[] missingName = new String[1];
            boolean hasDict = GameUtils.gameDictHere( this, path, missingName );
            if ( !hasDict ) {
                m_missingDict = missingName[0];
                showDialog( WARN_NODICT );
            } else {
                switch ( menuID ) {
                case R.id.list_item_play:
                    File file = new File( path );
                    Uri uri = Uri.fromFile( file );
                    Intent intent = new Intent( Intent.ACTION_EDIT, uri,
                                                this, BoardActivity.class );
                    startActivity( intent );
                    m_invalPath = path;
                    break;

                case R.id.list_item_config:
                    doConfig( path );
                    m_invalPath = path;
                    break;

                case R.id.list_item_reset:
                    GameUtils.resetGame( this, path, path );
                    invalPath = path;
                    break;
                case R.id.list_item_new_from:
                    String newName = GameUtils.resetGame( this, path );  
                    invalPath = newName;
                    break;

                case R.id.list_item_copy:
                    stream = GameUtils.savedGame( this, path );
                    newName = GameUtils.saveGame( this, stream );
                    DBUtils.saveSummary( newName, 
                                         DBUtils.getSummary( this, path ) );
                    break;

                    // These require some notion of predictable sort order.
                    // Maybe put off until I'm using a db?
                    // case R.id.list_item_hide:
                    // case R.id.list_item_move_up:
                    // case R.id.list_item_move_down:
                    // case R.id.list_item_move_to_top:
                    // case R.id.list_item_move_to_bottom:
                    // Utils.notImpl( this );
                    // break;
                default:
                    handled = false;
                    break;
                }
            }
        }

        if ( null != invalPath ) {
            m_adapter.inval( invalPath );
        }

        if ( handled ) {
            onContentChanged();
        }

        return handled;
    } // handleMenuItem

    private void doConfig( String path )
    {
        Uri uri = Uri.fromFile( new File(path) );
        
        Intent intent = new Intent( Intent.ACTION_EDIT, uri,
                                    this, GameConfig.class );
        startActivity( intent );
    }

    private void saveNew( CurGameInfo gi )
    {
        byte[] bytes = XwJNI.gi_to_stream( gi );
        if ( null != bytes ) {
            GameUtils.saveGame( this, bytes );
        } else {
            Utils.logf( "gi_to_stream=>null" );
        }
    }
}
