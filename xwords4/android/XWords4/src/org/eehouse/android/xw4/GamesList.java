/* -*- compile-command: "find-and-ant.sh debug install"; -*- */
/*
 * Copyright 2009 - 2012 by Eric House (xwords@eehouse.org).  All
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
import android.app.ListActivity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;

import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ExpandableListView;
import android.widget.LinearLayout;
import android.widget.ListView;

import java.io.File;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

// import android.telephony.PhoneStateListener;
// import android.telephony.TelephonyManager;
import junit.framework.Assert;

import org.eehouse.android.xw4.jni.*;

public class GamesList extends XWExpandableListActivity 
    implements OnItemLongClickListener,
               DBUtils.DBChangeListener, SelectableItem, 
               DictImportActivity.DownloadFinishedListener,
               NetStateCache.StateChangedIf {

    private static final int WARN_NODICT       = DlgDelegate.DIALOG_LAST + 1;
    private static final int WARN_NODICT_SUBST = WARN_NODICT + 1;
    private static final int SHOW_SUBST        = WARN_NODICT + 2;
    private static final int GET_NAME          = WARN_NODICT + 3;
    private static final int RENAME_GAME       = WARN_NODICT + 4;
    private static final int NEW_GROUP         = WARN_NODICT + 5;
    private static final int RENAME_GROUP      = WARN_NODICT + 6;
    private static final int CHANGE_GROUP      = WARN_NODICT + 7;
    private static final int WARN_NODICT_NEW   = WARN_NODICT + 8;

    private static final String SAVE_ROWID = "SAVE_ROWID";
    private static final String SAVE_ROWIDS = "SAVE_ROWIDS";
    private static final String SAVE_GROUPID = "SAVE_GROUPID";
    private static final String SAVE_DICTNAMES = "SAVE_DICTNAMES";

    private static final String RELAYIDS_EXTRA = "relayids";
    private static final String ROWID_EXTRA = "rowid";
    private static final String GAMEID_EXTRA = "gameid";
    private static final String REMATCH_ROWID_EXTRA = "rowid_rm";
    private static final String ALERT_MSG = "alert_msg";

    private static enum GamesActions { NEW_NET_GAME
            ,RESET_GAMES
            ,SYNC_MENU
            ,NEW_FROM
            ,DELETE_GAMES
            ,DELETE_GROUPS
            ,OPEN_GAME
            ,CLEAR_SELS
            };

    private static final int[] DEBUG_ITEMS = { 
        // R.id.games_menu_loaddb,
        R.id.games_menu_storedb,
        R.id.games_menu_checkupdates,
    };
    private static final int[] NOSEL_ITEMS = { 
        R.id.games_menu_newgroup,
        R.id.games_menu_prefs,
        R.id.games_menu_dicts,
        R.id.games_menu_about,
        R.id.games_menu_email,
        R.id.games_menu_checkmoves,
    };
    private static final int[] ONEGAME_ITEMS = {
        R.id.games_game_config,
        R.id.games_game_rename,
        R.id.games_game_new_from,
        R.id.games_game_copy,
    };

    private static final int[] ONEGROUP_ITEMS = {
        R.id.games_group_rename,
    };

    private static boolean s_firstShown = false;

    private GameListAdapter m_adapter;
    private String m_missingDict;
    private String m_missingDictName;
    private long m_missingDictRowId = DBUtils.ROWID_NOTFOUND;
    private String[] m_sameLangDicts;
    private int m_missingDictLang;
    private long m_rowid;
    private long[] m_rowids;
    private long m_groupid;
    private String m_nameField;
    private NetLaunchInfo m_netLaunchInfo;
    private GameNamer m_namer;
    private long m_launchedGame = DBUtils.ROWID_NOTFOUND;
    private boolean m_menuPrepared;
    private boolean m_netAvail;
    private HashSet<Long> m_selGames;
    private HashSet<Long> m_selGroupIDs;
    private CharSequence m_origTitle;

    @Override
    protected Dialog onCreateDialog( int id )
    {
        DialogInterface.OnClickListener lstnr;
        DialogInterface.OnClickListener lstnr2;
        LinearLayout layout;

        Dialog dialog = super.onCreateDialog( id );
        if ( null == dialog ) {
            AlertDialog.Builder ab;
            switch ( id ) {
            case WARN_NODICT:
            case WARN_NODICT_NEW:
            case WARN_NODICT_SUBST:
                lstnr = new DialogInterface.OnClickListener() {
                        public void onClick( DialogInterface dlg, int item ) {
                            // no name, so user must pick
                            if ( null == m_missingDictName ) {
                                DictsActivity.launchAndDownload( GamesList.this, 
                                                                 m_missingDictLang );
                            } else {
                                DictImportActivity
                                    .downloadDictInBack( GamesList.this,
                                                         m_missingDictLang,
                                                         m_missingDictName,
                                                         GamesList.this );
                            }
                        }
                    };
                String message;
                String langName = 
                    DictLangCache.getLangName( this, m_missingDictLang );
                String gameName = GameUtils.getName( this, m_missingDictRowId );
                if ( WARN_NODICT == id ) {
                    message = getString( R.string.no_dictf,
                                         gameName, langName );
                } else if ( WARN_NODICT_NEW == id ) {
                    message = 
                        getString( R.string.invite_dict_missing_body_nonamef,
                                   null, m_missingDictName, langName );
                } else {
                    message = getString( R.string.no_dict_substf,
                                         gameName, m_missingDictName, 
                                         langName );
                }

                ab = new AlertDialog.Builder( this )
                    .setTitle( R.string.no_dict_title )
                    .setMessage( message )
                    .setPositiveButton( R.string.button_cancel, null )
                    .setNegativeButton( R.string.button_download, lstnr )
                    ;
                if ( WARN_NODICT_SUBST == id ) {
                    lstnr = new DialogInterface.OnClickListener() {
                            public void onClick( DialogInterface dlg, int item ) {
                                showDialog( SHOW_SUBST );
                            }
                        };
                    ab.setNeutralButton( R.string.button_substdict, lstnr );
                }
                dialog = ab.create();
                Utils.setRemoveOnDismiss( this, dialog, id );
                break;
            case SHOW_SUBST:
                m_sameLangDicts = 
                    DictLangCache.getHaveLangCounts( this, m_missingDictLang );
                lstnr = new DialogInterface.OnClickListener() {
                        public void onClick( DialogInterface dlg,
                                             int which ) {
                            int pos = ((AlertDialog)dlg).getListView().
                                getCheckedItemPosition();
                            String dict = m_sameLangDicts[pos];
                            dict = DictLangCache.stripCount( dict );
                            if ( GameUtils.replaceDicts( GamesList.this,
                                                         m_missingDictRowId,
                                                         m_missingDictName,
                                                         dict ) ) {
                                launchGameIf();
                            }
                        }
                    };
                dialog = new AlertDialog.Builder( this )
                    .setTitle( R.string.subst_dict_title )
                    .setPositiveButton( R.string.button_substdict, lstnr )
                    .setNegativeButton( R.string.button_cancel, null )
                    .setSingleChoiceItems( m_sameLangDicts, 0, null )
                    .create();
                // Force destruction so onCreateDialog() will get
                // called next time and we can insert a different
                // list.  There seems to be no way to change the list
                // inside onPrepareDialog().
                Utils.setRemoveOnDismiss( this, dialog, id );
                break;

            case RENAME_GAME:
                lstnr = new DialogInterface.OnClickListener() {
                        public void onClick( DialogInterface dlg, int item ) {
                            String name = m_namer.getName();
                            DBUtils.setName( GamesList.this, m_rowid, name );
                            m_adapter.invalName( m_rowid );
                        }
                    };
                dialog = buildNamerDlg( GameUtils.getName( this, m_rowid ),
                                        R.string.rename_label,
                                        R.string.game_rename_title,
                                        lstnr, RENAME_GAME );
                break;

            case RENAME_GROUP:
                lstnr = new DialogInterface.OnClickListener() {
                        public void onClick( DialogInterface dlg, int item ) {
                            String name = m_namer.getName();
                            DBUtils.setGroupName( GamesList.this, m_groupid, 
                                                  name );
                            m_adapter.inval( m_rowid );
                            onContentChanged();
                        }
                    };
                dialog = buildNamerDlg( m_adapter.groupName( m_groupid ),
                                        R.string.rename_group_label,
                                        R.string.game_name_group_title,
                                        lstnr, RENAME_GROUP );
                break;

            case NEW_GROUP:
                lstnr = new DialogInterface.OnClickListener() {
                        public void onClick( DialogInterface dlg, int item ) {
                            String name = m_namer.getName();
                            DBUtils.addGroup( GamesList.this, name );
                            // m_adapter.inval();
                            onContentChanged();
                        }
                    };
                dialog = buildNamerDlg( "", R.string.newgroup_label,
                                        R.string.game_name_group_title,
                                        lstnr, RENAME_GROUP );
                Utils.setRemoveOnDismiss( this, dialog, id );
                break;

            case CHANGE_GROUP:
                final long startGroup = ( 1 == m_rowids.length )
                    ? DBUtils.getGroupForGame( this, m_rowids[0] ) : -1;
                final int[] selItem = {-1}; // hack!!!!
                lstnr = new DialogInterface.OnClickListener() {
                        public void onClick( DialogInterface dlgi, int item ) {
                            selItem[0] = item;
                            AlertDialog dlg = (AlertDialog)dlgi;
                            Button btn = 
                                dlg.getButton( AlertDialog.BUTTON_POSITIVE );
                            boolean enabled = startGroup == -1;
                            if ( !enabled ) {
                                long newGroup = m_adapter.getGroupIDFor( item );
                                enabled = newGroup != startGroup;
                            }
                            btn.setEnabled( enabled );
                        }
                    };
                lstnr2 = new DialogInterface.OnClickListener() {
                        public void onClick( DialogInterface dlg, int item ) {
                            Assert.assertTrue( -1 != selItem[0] );
                            long gid = m_adapter.getGroupIDFor( selItem[0] );
                            for ( long rowid : m_rowids ) {
                                DBUtils.moveGame( GamesList.this, rowid, gid );
                            }
                            DBUtils.setGroupExpanded( GamesList.this, gid, 
                                                      true );
                            onContentChanged();
                        }
                    };
                String[] groups = m_adapter.groupNames();
                int curGroupPos = m_adapter.getGroupPosition( startGroup );
                dialog = new AlertDialog.Builder( this )
                    .setTitle( getString( R.string.change_group ) )
                    .setSingleChoiceItems( groups, curGroupPos, lstnr )
                    .setPositiveButton( R.string.button_move, lstnr2 )
                    .setNegativeButton( R.string.button_cancel, null )
                    .create();
                Utils.setRemoveOnDismiss( this, dialog, id );
                break;

            case GET_NAME:
                layout = 
                    (LinearLayout)Utils.inflate( this, R.layout.dflt_name );
                final EditText etext =
                    (EditText)layout.findViewById( R.id.name_edit );
                etext.setText( CommonPrefs.getDefaultPlayerName( this, 0, 
                                                                 true ) );
                dialog = new AlertDialog.Builder( this )
                    .setTitle( R.string.default_name_title )
                    .setMessage( R.string.default_name_message )
                    .setPositiveButton( R.string.button_ok, null )
                    .setView( layout )
                    .create();
                dialog.setOnDismissListener(new DialogInterface.
                                            OnDismissListener() {
                        public void onDismiss( DialogInterface dlg ) {
                            String name = etext.getText().toString();
                            if ( 0 == name.length() ) {
                                name = CommonPrefs.
                                    getDefaultPlayerName( GamesList.this,
                                                          0, true );
                            }
                            CommonPrefs.setDefaultPlayerName( GamesList.this,
                                                              name );
                        }
                    });
                break;

            default:
                // just drop it; super.onCreateDialog likely failed
                break;
            }
        }
        return dialog;
    } // onCreateDialog

    @Override protected void onPrepareDialog( int id, Dialog dialog )
    {
        super.onPrepareDialog( id, dialog );

        if ( CHANGE_GROUP == id ) {
            ((AlertDialog)dialog).getButton( AlertDialog.BUTTON_POSITIVE )
                .setEnabled( false );
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) 
    {
        super.onCreate( savedInstanceState );
        // scary, but worth playing with:
        // Assert.assertTrue( isTaskRoot() );

        CrashTrack.init( this );

        m_netAvail = NetStateCache.netAvail( this );

        m_selGames = new HashSet<Long>();
        m_selGroupIDs = new HashSet<Long>();
        getBundledData( savedInstanceState );

        setContentView(R.layout.game_list);
        ExpandableListView listview = getExpandableListView();
        DBUtils.setDBChangeListener( this );

        boolean isUpgrade = Utils.firstBootThisVersion( this );
        if ( isUpgrade && !s_firstShown ) {
            FirstRunDialog.show( this );
            s_firstShown = true;
        }
        PreferenceManager.setDefaultValues( this, R.xml.xwprefs, isUpgrade );

        m_adapter = makeNewAdapter();
        listview.setOnItemLongClickListener( this );

        NetUtils.informOfDeaths( this );

        tryStartsFromIntent( getIntent() );

        askDefaultNameIf();

        m_origTitle = getTitle();
    } // onCreate

    @Override
    // called when we're brought to the front (probably as a result of
    // notification)
    protected void onNewIntent( Intent intent )
    {
        super.onNewIntent( intent );
        m_launchedGame = DBUtils.ROWID_NOTFOUND;
        Assert.assertNotNull( intent );
        invalRelayIDs( intent.getStringArrayExtra( RELAYIDS_EXTRA ) );
        invalRowID( intent.getLongExtra( ROWID_EXTRA, -1 ) );
        tryStartsFromIntent( intent );
    }

    @Override
    protected void onStart()
    {
        super.onStart();
        NetStateCache.register( this, this );
    }

    @Override
    protected void onStop()
    {
        NetStateCache.unregister( this, this );
        // TelephonyManager mgr = 
        //     (TelephonyManager)getSystemService( Context.TELEPHONY_SERVICE );
        // mgr.listen( m_phoneStateListener, PhoneStateListener.LISTEN_NONE );
        // m_phoneStateListener = null;
        long[] positions = m_adapter.getGroupPositions();
        XWPrefs.setGroupPositions( this, positions );
        super.onStop();
    }

    @Override
    protected void onDestroy()
    {
        DBUtils.clearDBChangeListener( this );
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState( Bundle outState ) 
    {
        super.onSaveInstanceState( outState );
        outState.putLong( SAVE_ROWID, m_rowid );
        outState.putLongArray( SAVE_ROWIDS, m_rowids );
        outState.putLong( SAVE_GROUPID, m_groupid );
        outState.putString( SAVE_DICTNAMES, m_missingDictName );
        if ( null != m_netLaunchInfo ) {
            m_netLaunchInfo.putSelf( outState );
        }
    }

    private void getBundledData( Bundle bundle )
    {
        if ( null != bundle ) {
            m_rowid = bundle.getLong( SAVE_ROWID );
            m_rowids = bundle.getLongArray( SAVE_ROWIDS );
            m_groupid = bundle.getLong( SAVE_GROUPID );
            m_netLaunchInfo = new NetLaunchInfo( bundle );
            m_missingDictName = bundle.getString( SAVE_DICTNAMES );
        }
    }

    @Override
    public void onWindowFocusChanged( boolean hasFocus )
    {
        super.onWindowFocusChanged( hasFocus );
        if ( hasFocus ) {
            updateField();

            if ( DBUtils.ROWID_NOTFOUND != m_launchedGame ) {
                selectJustLaunched();
                m_launchedGame = DBUtils.ROWID_NOTFOUND;
            }
        }
    }

    // OnItemLongClickListener interface
    public boolean onItemLongClick( AdapterView<?> parent, View view, 
                                    int position, long id ) {
        boolean success = view instanceof SelectableItem.LongClickHandler;
        if ( success ) {
            ((SelectableItem.LongClickHandler)view).longClicked();
        }
        return success;
    }

    // DBUtils.DBChangeListener interface
    public void gameSaved( final long rowid, final boolean countChanged )
    {
        runOnUiThread( new Runnable() {
                public void run() {
                    if ( countChanged || DBUtils.ROWID_NOTFOUND == rowid ) {
                        onContentChanged();
                        if ( DBUtils.ROWID_NOTFOUND != rowid ) {
                            m_launchedGame = rowid;
                        }
                    } else {
                        Assert.assertTrue( DBUtils.ROWID_NOTFOUND != rowid );
                        m_adapter.inval( rowid );
                    }
                }
            } );
    }

    // SelectableItem interface
    public void itemClicked( SelectableItem.LongClickHandler clicked,
                             GameSummary summary )
    {
        // We need a way to let the user get back to the basic-config
        // dialog in case it was dismissed.  That way it to check for
        // an empty room name.
        if ( clicked instanceof GameListItem ) {
            if ( DBUtils.ROWID_NOTFOUND == m_launchedGame ) {
                long rowid = ((GameListItem)clicked).getRowID();
                showNotAgainDlgThen( R.string.not_again_newselect, 
                                     R.string.key_notagain_newselect,
                                     GamesActions.OPEN_GAME.ordinal(), 
                                     rowid, summary );
            }
        }
    }

    public void itemToggled( SelectableItem.LongClickHandler toggled, 
                             boolean selected )
    {
        if ( toggled instanceof GameListItem ) {
            long rowid = ((GameListItem)toggled).getRowID();
            if ( selected ) {
                m_selGames.add( rowid );
                clearSelectedGroups();
            } else {
                m_selGames.remove( rowid );
            }
        } else if ( toggled instanceof GameListGroup ) {
            long id = ((GameListGroup)toggled).getGroupID();
            if ( selected ) {
                m_selGroupIDs.add( id );
                clearSelectedGames();
            } else {
                m_selGroupIDs.remove( id );
            }
        }
        ABUtils.invalidateOptionsMenuIf( this );
        setTitleBar();
    }

    public boolean getSelected( SelectableItem.LongClickHandler obj )
    {
        boolean selected;
        if ( obj instanceof GameListItem ) {
            long rowid = ((GameListItem)obj).getRowID();
            selected = m_selGames.contains( rowid );
        } else if ( obj instanceof GameListGroup ) {
            long groupID = ((GameListGroup)obj).getGroupID();
            selected = m_selGroupIDs.contains( groupID );
        } else {
            Assert.fail();
            selected = false;
        }
        return selected;
    }

    // BTService.MultiEventListener interface
    @Override
    public void eventOccurred( MultiService.MultiEvent event, 
                               final Object ... args )
    {
        switch( event ) {
        case HOST_PONGED:
            post( new Runnable() {
                    public void run() {
                        DbgUtils.showf( GamesList.this,
                                        "Pong from %s", args[0].toString() );
                    } 
                });
            break;
        default:
            super.eventOccurred( event, args );
            break;
        }
    }

    // DlgDelegate.DlgClickNotify interface
    @Override
    public void dlgButtonClicked( int id, int which, Object[] params )
    {
        if ( AlertDialog.BUTTON_POSITIVE == which ) {
            GamesActions action = GamesActions.values()[id];
            switch( action ) {
            case NEW_NET_GAME:
                if ( checkWarnNoDict( m_netLaunchInfo ) ) {
                    makeNewNetGameIf();
                }
                break;
            case RESET_GAMES:
                long[] rowids = (long[])params[0];
                for ( long rowid : rowids ) {
                    GameUtils.resetGame( this, rowid );
                }
                onContentChanged(); // required because position may change
                break;
            case SYNC_MENU:
                doSyncMenuitem();
                break;
            case NEW_FROM:
                long curID = (Long)params[0];
                long newid = GameUtils.dupeGame( GamesList.this, curID );
                m_selGames.add( newid );
                if ( null != m_adapter ) {
                    m_adapter.inval( newid );
                }
                break;

            case DELETE_GROUPS:
                long[] groupIDs = (long[])params[0];
                for ( long groupID : groupIDs ) {
                    GameUtils.deleteGroup( this, groupID );
                }
                clearSelections();
                onContentChanged();
                break;
            case DELETE_GAMES:
                deleteGames( (long[])params[0] );
                break;
            case OPEN_GAME:
                doOpenGame( params );
                break;
            case CLEAR_SELS:
                clearSelections();
                break;
            default:
                Assert.fail();
            }
        }
    }

    @Override
    public void onContentChanged()
    {
        super.onContentChanged();
        if ( null != m_adapter ) {
            m_adapter.expandGroups( getExpandableListView() );
        }
    }

    @Override
    public void onBackPressed() {
        if ( 0 == m_selGames.size() && 0 == m_selGroupIDs.size() ) {
            super.onBackPressed();
        } else {
            showNotAgainDlgThen( R.string.not_again_backclears, 
                                 R.string.key_notagain_backclears,
                                 GamesActions.CLEAR_SELS.ordinal() );
        }
    }

    @Override
    public boolean onCreateOptionsMenu( Menu menu )
    {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate( R.menu.games_list_menu, menu );

        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu( Menu menu ) 
    {
        int nGamesSelected = m_selGames.size();
        int nGroupsSelected = m_selGroupIDs.size();
        m_menuPrepared = 0 == nGamesSelected || 0 == nGroupsSelected;
        if ( m_menuPrepared ) {
            boolean nothingSelected = 0 == (nGroupsSelected + nGamesSelected);
        
            boolean showDbg = BuildConfig.DEBUG
                || XWPrefs.getDebugEnabled( this );
            showItemsIf( DEBUG_ITEMS, menu, nothingSelected && showDbg );
            Utils.setItemVisible( menu, R.id.games_menu_loaddb, 
                                  showDbg && DBUtils.gameDBExists( this ) );

            showItemsIf( NOSEL_ITEMS, menu, nothingSelected );
            showItemsIf( ONEGAME_ITEMS, menu, 1 == nGamesSelected );
            showItemsIf( ONEGROUP_ITEMS, menu, 1 == nGroupsSelected );

            int selGroupPos = -1;
            if ( 1 == nGroupsSelected ) {
                long id = m_selGroupIDs.iterator().next();
                selGroupPos = m_adapter.getGroupPosition( id );
            }

            // You can't delete the default group, nor make it the default
            boolean defaultAvail = 1 == nGroupsSelected;
            if ( defaultAvail ) {
                long selID = m_adapter.getGroupIDFor( selGroupPos );
                defaultAvail = selID != XWPrefs.getDefaultNewGameGroup( this );
            }
            Utils.setItemVisible( menu, R.id.games_group_default, defaultAvail );
            Utils.setItemVisible( menu, R.id.games_group_delete, defaultAvail );

            // Move up/down enabled for groups if not the top-most or bottommost
            // selected
            Utils.setItemVisible( menu, R.id.games_group_moveup, 
                                  0 < selGroupPos );
            boolean enable = 0 <= selGroupPos
                && (selGroupPos + 1) < m_adapter.getGroupCount();
            Utils.setItemVisible( menu, R.id.games_group_movedown, enable );

            // New game available when nothing selected or one group
            Utils.setItemVisible( menu, R.id.games_menu_newgame,
                                  nothingSelected || 1 == nGroupsSelected );
                
            // Multiples can be deleted
            Utils.setItemVisible( menu, R.id.games_game_delete, 
                                  0 < nGamesSelected );

            // multiple games can be regrouped/reset.
            Utils.setItemVisible( menu, R.id.games_game_move, 
                                  (1 < m_adapter.getGroupCount()
                                    && 0 < nGamesSelected) );
            Utils.setItemVisible( menu, R.id.games_game_reset, 
                                  0 < nGamesSelected );

            // Hide rate-me if not a google play app
            enable = nothingSelected && Utils.isGooglePlayApp( this );
            Utils.setItemVisible( menu, R.id.games_menu_rateme, enable );

            enable = nothingSelected && XWPrefs.getStudyEnabled( this );
            Utils.setItemVisible( menu, R.id.games_menu_study, enable );

            m_menuPrepared = super.onPrepareOptionsMenu( menu );
        } else {
            DbgUtils.logf( "onPrepareOptionsMenu: incomplete so bailing" );
        }
        return m_menuPrepared;
    }

    public boolean onOptionsItemSelected( MenuItem item )
    {
        Assert.assertTrue( m_menuPrepared );

        boolean handled = true;
        boolean changeContent = false;
        boolean dropSels = false;
        int groupPos = getSelGroupPos();
        long groupID = DBUtils.GROUPID_UNSPEC;
        if ( 0 <= groupPos ) {
            groupID = m_adapter.getGroupIDFor( groupPos );
        }
        final long[] selRowIDs = getSelRowIDs();

        if ( 1 == selRowIDs.length && !checkWarnNoDict( selRowIDs[0] ) ) {
            return true;        // FIXME: RETURN FROM MIDDLE!!!
        }

        switch ( item.getItemId() ) {

            // There's no selection for these items, so nothing to clear
        case R.id.games_menu_newgame:
            startNewGameActivity( groupID );
            break;

        case R.id.games_menu_newgroup:
            showDialog( NEW_GROUP );
            break;

        case R.id.games_game_config:
            GameUtils.doConfig( this, selRowIDs[0], GameConfig.class );
            break;

        case R.id.games_menu_dicts:
            DictsActivity.start( this );
            break;

        case R.id.games_menu_checkmoves:
            showNotAgainDlgThen( R.string.not_again_sync,
                                 R.string.key_notagain_sync,
                                 GamesActions.SYNC_MENU.ordinal() );
            break;

        case R.id.games_menu_checkupdates:
            UpdateCheckReceiver.checkVersions( this, true );
            break;

        case R.id.games_menu_prefs:
            Utils.launchSettings( this );
            break;

        case R.id.games_menu_rateme:
            String str = String.format( "market://details?id=%s",
                                        getPackageName() );
            try {
                startActivity( new Intent( Intent.ACTION_VIEW, Uri.parse( str ) ) );
            } catch ( android.content.ActivityNotFoundException anf ) {
                showOKOnlyDialog( R.string.no_market );
            }
            break;

        case R.id.games_menu_study:
            StudyList.launchOrAlert( this, StudyList.NO_LANG, this );
            break;

        case R.id.games_menu_about:
            showAboutDialog();
            break;

        case R.id.games_menu_email:
            Utils.emailAuthor( this );
            break;

        case R.id.games_menu_loaddb:
            DBUtils.loadDB( this );
            XWPrefs.clearGroupPositions( this );
            m_adapter = makeNewAdapter();
            changeContent = true;
            break;
        case R.id.games_menu_storedb:
            DBUtils.saveDB( this );
            break;

            // Game menus: one or more games selected
        case R.id.games_game_delete:
            String msg = Utils.format( this, R.string.confirm_seldeletesf, 
                                       selRowIDs.length );
            showConfirmThen( msg, R.string.button_delete, 
                             GamesActions.DELETE_GAMES.ordinal(), selRowIDs );
            break;

        case R.id.games_game_move:
            if ( 1 >= m_adapter.getGroupCount() ) {
                showOKOnlyDialog( R.string.no_move_onegroup );
            } else {
                m_rowids = selRowIDs;
                showDialog( CHANGE_GROUP );
            }
            break;
        case R.id.games_game_new_from:
            dropSels = true;    // will select the new game instead
            showNotAgainDlgThen( R.string.not_again_newfrom,
                                 R.string.key_notagain_newfrom, 
                                 GamesActions.NEW_FROM.ordinal(), 
                                 selRowIDs[0] );
            break;
        case R.id.games_game_copy:
            final GameSummary smry = DBUtils.getSummary( this, selRowIDs[0] );
            if ( smry.inNetworkGame() ) {
                showOKOnlyDialog( R.string.no_copy_network );
            } else {
                dropSels = true;    // will select the new game instead
                post( new Runnable() {
                        public void run() {
                            byte[] stream = GameUtils.savedGame( GamesList.this,
                                                                 selRowIDs[0] );
                            long groupID = 
                                XWPrefs.getDefaultNewGameGroup(GamesList.this);
                            GameLock lock = 
                                GameUtils.saveNewGame( GamesList.this, stream,
                                                       groupID );
                            DBUtils.saveSummary( GamesList.this, lock, smry );
                            m_selGames.add( lock.getRowid() );
                            lock.unlock();
                            onContentChanged();
                        }
                    });
            }
            break;

        case R.id.games_game_reset:
            showConfirmThen( R.string.confirm_reset, R.string.button_reset, 
                             GamesActions.RESET_GAMES.ordinal(), selRowIDs );
            break;

        case R.id.games_game_rename:
            m_rowid = selRowIDs[0];
            showDialog( RENAME_GAME );
            break;

            // Group menus
        case R.id.games_group_delete:
            long dftGroup = XWPrefs.getDefaultNewGameGroup( this );
            if ( m_selGroupIDs.contains( dftGroup ) ) {
                msg = getString( R.string.cannot_delete_default_groupf,
                                 m_adapter.groupName( dftGroup ) );
                showOKOnlyDialog( msg );
            } else {
                long[] groupIDs = getSelGroupIDs();
                Assert.assertTrue( 0 < groupIDs.length );
                msg = getString( 1 == groupIDs.length ? R.string.group_confirm_del
                                 : R.string.groups_confirm_del );
                int nGames = 0;
                for ( long tmp : groupIDs ) {
                    nGames += m_adapter.getChildrenCount( tmp );
                }
                if ( 0 < nGames ) {
                    int fmtID = 1 == groupIDs.length ? R.string.group_confirm_delf 
                        : R.string.groups_confirm_delf;
                    msg += getString( fmtID, nGames );
                }
                showConfirmThen( msg, GamesActions.DELETE_GROUPS.ordinal(),
                                 groupIDs );
            }
            break;
        case R.id.games_group_default:
            XWPrefs.setDefaultNewGameGroup( this, groupID );
            break;
        case R.id.games_group_rename:
            m_groupid = groupID;
            showDialog( RENAME_GROUP );
            break;
        case R.id.games_group_moveup:
            changeContent = m_adapter.moveGroup( groupID, -1 );
            break;
        case R.id.games_group_movedown:
            changeContent = m_adapter.moveGroup( groupID, 1 );
            break;

        default:
            handled = false;
        }

        if ( dropSels ) {
            clearSelections();
        }
        if ( changeContent ) {
            onContentChanged();
        }

        return handled || super.onOptionsItemSelected( item );
    }

    // DictImportActivity.DownloadFinishedListener interface
    public void downloadFinished( String name, final boolean success )
    {
        post( new Runnable() {
                public void run() {
                    boolean madeGame = false;
                    if ( success ) {
                        madeGame = makeNewNetGameIf() || launchGameIf();
                    }
                    if ( ! madeGame ) {
                        int id = success ? R.string.download_done 
                            : R.string.download_failed;
                        Utils.showToast( GamesList.this, id );
                    }
                }
            } );
    }

    //////////////////////////////////////////////////////////////////////
    // NetStateCache.StateChangedIf 
    //////////////////////////////////////////////////////////////////////
    public void netAvail( boolean nowAvailable )
    {
        if ( m_netAvail != nowAvailable ) {
            m_netAvail = nowAvailable;
            if ( BuildConfig.DEBUG ) {
                int id = nowAvailable ?
                    R.string.net_change_gained : R.string.net_change_lost;
                Utils.showToast( this, id );
                DbgUtils.logf( "GamesList.netAvail(%s)", getString( id ) );
            }
        }
    }

    private void setTitleBar()
    {
        int fmt = 0;
        int nSels = m_selGames.size();
        if ( 0 < nSels ) {
            fmt = R.string.sel_gamesf;
        } else {
            nSels = m_selGroupIDs.size();
            if ( 0 < nSels ) {
                fmt = R.string.sel_groupsf;
            }
        }

        if ( 0 == fmt ) {
            setTitle( m_origTitle );
        } else {
            setTitle( getString( fmt, nSels ) );
        }
    }

    private boolean checkWarnNoDict( NetLaunchInfo nli )
    {
        // check that we have the dict required
        boolean haveDict;
        if ( null == nli.dict ) { // can only test for language support
            String[] dicts = DictLangCache.getHaveLang( this, nli.lang );
            haveDict = 0 < dicts.length;
            if ( haveDict ) {
                // Just pick one -- good enough for the period when
                // users aren't using new clients that include the
                // dict name.
                nli.dict = dicts[0]; 
            }
        } else {
            haveDict = 
                DictLangCache.haveDict( this, nli.lang, nli.dict );
        }
        if ( !haveDict ) {
            m_netLaunchInfo = nli;
            m_missingDictLang = nli.lang;
            m_missingDictName = nli.dict;
            showDialog( WARN_NODICT_NEW );
        }
        return haveDict;
    }

    private boolean checkWarnNoDict( long rowid )
    {
        String[][] missingNames = new String[1][];
        int[] missingLang = new int[1];
        boolean hasDicts = 
            GameUtils.gameDictsHere( this, rowid, missingNames, missingLang );
        if ( !hasDicts ) {
            m_missingDictLang = missingLang[0];
            if ( 0 < missingNames[0].length ) {
                m_missingDictName = missingNames[0][0];
            } else {
                m_missingDictName = null;
            }
            m_missingDictRowId = rowid;
            if ( 0 == DictLangCache.getLangCount( this, m_missingDictLang ) ) {
                showDialog( WARN_NODICT );
            } else if ( null != m_missingDictName ) {
                showDialog( WARN_NODICT_SUBST );
            } else {
                String dict = 
                    DictLangCache.getHaveLang( this, m_missingDictLang)[0];
                if ( GameUtils.replaceDicts( this, m_missingDictRowId, 
                                             null, dict ) ) {
                    launchGameIf();
                }
            }
        }
        return hasDicts;
    }

    private void invalRelayIDs( String[] relayIDs ) 
    {
        if ( null != relayIDs ) {
            for ( String relayID : relayIDs ) {
                long[] rowids = DBUtils.getRowIDsFor( this, relayID );
                if ( null != rowids ) {
                    for ( long rowid : rowids ) {
                        m_adapter.inval( rowid );
                    }
                }
            }
        }
    }

    private void invalRowID( long rowid )
    {
        if ( -1 != rowid ) {
            m_adapter.inval( rowid );
        }
    }

    // Launch the first of these for which there's a dictionary
    // present.
    private boolean startFirstHasDict( String[] relayIDs )
    {
        boolean launched = false;
        if ( null != relayIDs ) {
            outer:
            for ( String relayID : relayIDs ) {
                long[] rowids = DBUtils.getRowIDsFor( this, relayID );
                if ( null != rowids ) {
                    for ( long rowid : rowids ) {
                        if ( GameUtils.gameDictsHere( this, rowid ) ) {
                            launchGame( rowid );
                            launched = true;
                            break outer;
                        }
                    }
                }
            }
        }
        return launched;
    }

    private void startFirstHasDict( long rowid )
    {
        if ( -1 != rowid && DBUtils.haveGame( this, rowid ) ) {
            if ( GameUtils.gameDictsHere( this, rowid ) ) {
                launchGame( rowid );
            }
        }
    }

    private void startFirstHasDict( Intent intent )
    {
        if ( null != intent ) {
            String[] relayIDs = intent.getStringArrayExtra( RELAYIDS_EXTRA );
            if ( !startFirstHasDict( relayIDs ) ) {
                long rowid = intent.getLongExtra( ROWID_EXTRA, -1 );
                startFirstHasDict( rowid );
            }
        }
    }

    private void startNewGameActivity( long groupID )
    {
        NewGameActivity.startActivity( this, groupID );
    }

    private void startNewNetGame( NetLaunchInfo nli )
    {
        Date create = DBUtils.getMostRecentCreate( this, nli );

        if ( null == create ) {
            if ( checkWarnNoDict( nli ) ) {
                makeNewNetGame( nli );
            }
        } else if ( XWPrefs.getSecondInviteAllowed( this ) ) {
            String msg = getString( R.string.dup_game_queryf, 
                                    create.toString() );
            m_netLaunchInfo = nli;
            showConfirmThen( msg, GamesActions.NEW_NET_GAME.ordinal(), nli );
        } else {
            showOKOnlyDialog( R.string.dropped_dupe );
        }
    } // startNewNetGame

    private void startNewNetGame( Intent intent )
    {
        NetLaunchInfo nli = null;
        if ( MultiService.isMissingDictIntent( intent ) ) {
            nli = new NetLaunchInfo( intent );
        } else {
            Uri data = intent.getData();
            if ( null != data ) {
                nli = new NetLaunchInfo( this, data );
            }
        }
        if ( null != nli && nli.isValid() ) {
            startNewNetGame( nli );
        }
    } // startNewNetGame

    private void startHasGameID( int gameID )
    {
        long[] rowids = DBUtils.getRowIDsFor( this, gameID );
        if ( null != rowids && 0 < rowids.length ) {
            launchGame( rowids[0] );
        }
    }

    private void startHasGameID( Intent intent )
    {
        int gameID = intent.getIntExtra( GAMEID_EXTRA, 0 );
        if ( 0 != gameID ) {
            startHasGameID( gameID );
        }
    }

    private void startHasRowID( Intent intent )
    {
        long rowid = intent.getLongExtra( REMATCH_ROWID_EXTRA, -1 );
        if ( -1 != rowid ) {
            // this will juggle if the preference is set
            long newid = GameUtils.dupeGame( this, rowid );
            launchGame( newid );
        }
    }

    private void tryAlert( Intent intent )
    {
        String msg = intent.getStringExtra( ALERT_MSG );
        if ( null != msg ) {
            showOKOnlyDialog( msg );
        }
    }

    private void tryNFCIntent( Intent intent )
    {
        String data = NFCUtils.getFromIntent( intent );
        if ( null != data ) {
            NetLaunchInfo nli = new NetLaunchInfo( data );
            if ( nli.isValid() ) {
                startNewNetGame( nli );
            }
        }
    }

    private void askDefaultNameIf()
    {
        if ( null == CommonPrefs.getDefaultPlayerName( this, 0, false ) ) {
            String name = CommonPrefs.getDefaultPlayerName( this, 0, true );
            CommonPrefs.setDefaultPlayerName( GamesList.this, name );
            showDialog( GET_NAME );
        }
    }

    private void updateField()
    {
        String newField = CommonPrefs.getSummaryField( this );
        if ( m_adapter.setField( newField ) ) {
            // The adapter should be able to decide whether full
            // content change is required.  PENDING
            onContentChanged();
        }
    }

    private Dialog buildNamerDlg( String curname, int labelID, int titleID,
                                  DialogInterface.OnClickListener lstnr, 
                                  int dlgID )
    {
        m_namer = (GameNamer)Utils.inflate( this, R.layout.rename_game );
        m_namer.setName( curname );
        m_namer.setLabel( labelID );
        Dialog dialog = new AlertDialog.Builder( this )
            .setTitle( titleID )
            .setNegativeButton( R.string.button_cancel, null )
            .setPositiveButton( R.string.button_ok, lstnr )
            .setView( m_namer )
            .create();
        Utils.setRemoveOnDismiss( this, dialog, dlgID );
        return dialog;
    }

    private void deleteGames( long[] rowids )
    {
        for ( long rowid : rowids ) {
            GameUtils.deleteGame( this, rowid, false );
        }

        NetUtils.informOfDeaths( this );
        clearSelections();
    }

    private boolean makeNewNetGameIf()
    {
        boolean madeGame = null != m_netLaunchInfo;
        if ( madeGame ) {
            makeNewNetGame( m_netLaunchInfo );
            m_netLaunchInfo = null;
        }
        return madeGame;
    }

    private void clearSelections()
    {
        boolean inval = false;
        if ( clearSelectedGames() ) {
            inval = true;
        }
        if ( clearSelectedGroups() ) {
            inval = true;
        }
        if ( inval ) {
            ABUtils.invalidateOptionsMenuIf( this );
            setTitleBar();
        }
    }

    private boolean clearSelectedGames()
    {
        // clear any selection
        boolean needsClear = 0 < m_selGames.size();
        if ( needsClear ) {
            long[] rowIDs = getSelRowIDs();
            m_selGames.clear();
            m_adapter.clearSelectedGames( rowIDs );
        }
        return needsClear;
    }

    private boolean clearSelectedGroups()
    {
        // clear any selection
        boolean needsClear = 0 < m_selGroupIDs.size();
        if ( needsClear ) {
            m_adapter.clearSelectedGroups( m_selGroupIDs );
            m_selGroupIDs.clear();
        }
        return needsClear;
    }

    private boolean launchGameIf()
    {
        boolean madeGame = DBUtils.ROWID_NOTFOUND != m_missingDictRowId;
        if ( madeGame ) {
            GameUtils.launchGame( this, m_missingDictRowId );
            m_missingDictRowId = DBUtils.ROWID_NOTFOUND;
        }
        return madeGame;
    }

    private void launchGame( long rowid, boolean invited )
    {
        if ( DBUtils.ROWID_NOTFOUND == m_launchedGame ) {
            m_launchedGame = rowid;
            GameUtils.launchGame( this, rowid, invited );
        }
    }

    private void launchGame( long rowid )
    {
        launchGame( rowid, false );
    }

    private void makeNewNetGame( NetLaunchInfo nli )
    {
        long rowid = GameUtils.makeNewNetGame( this, nli );
        launchGame( rowid, true );
    }

    private void tryStartsFromIntent( Intent intent )
    {
        startFirstHasDict( intent );
        startNewNetGame( intent );
        startHasGameID( intent );
        startHasRowID( intent );
        tryAlert( intent );
        tryNFCIntent( intent );
    }

    private void doOpenGame( Object[] params )
    {
        GameSummary summary = (GameSummary)params[1];
        long rowid = (Long)params[0];

        if ( summary.conType == CommsAddrRec.CommsConnType.COMMS_CONN_RELAY
             && summary.roomName.length() == 0 ) {
            // If it's unconfigured and of the type RelayGameActivity
            // can handle send it there, otherwise use the full-on
            // config.
            Class clazz;
            
            if ( RelayGameActivity.isSimpleGame( summary ) ) {
                clazz = RelayGameActivity.class;
            } else {
                clazz = GameConfig.class;
            }
            GameUtils.doConfig( this, rowid, clazz );
        } else {
            if ( checkWarnNoDict( rowid ) ) {
                launchGame( rowid );
            }
        }
    }

    private long[] getSelRowIDs()
    {
        long[] result = new long[m_selGames.size()];
        int ii = 0;
        for ( Iterator<Long> iter = m_selGames.iterator(); 
              iter.hasNext(); ) {
            result[ii++] = iter.next();
        }
        return result;
    }

    private int getSelGroupPos()
    {
        int result = -1;
        if ( 1 == m_selGroupIDs.size() ) {
            long id = m_selGroupIDs.iterator().next();
            result = m_adapter.getGroupPosition( id );
        }
        return result;
    }

    private long[] getSelGroupIDs()
    {
        long[] result = new long[m_selGroupIDs.size()];
        int ii = 0;
        for ( Iterator<Long> iter = m_selGroupIDs.iterator(); 
              iter.hasNext(); ) {
            result[ii++] = iter.next();
        }
        return result;
    }

    private void showItemsIf( int[] items, Menu menu, boolean select )
    {
        for ( int item : items ) {
            Utils.setItemVisible( menu, item, select );
        }
    }

    private void selectJustLaunched()
    {
        clearSelections();
        if ( null != m_adapter ) {
            GameListItem item = m_adapter.getGameItemFor( m_launchedGame );
            if ( null != item ) {
                item.setSelected( true );
            }
        }
    }

    private GameListAdapter makeNewAdapter()
    {
        ExpandableListView listview = getExpandableListView();
        String field = CommonPrefs.getSummaryField( this );
        long[] positions = XWPrefs.getGroupPositions( this );
        GameListAdapter adapter = 
            new GameListAdapter( this, listview, new Handler(), 
                                 this, positions, field );
        setListAdapter( adapter );
        adapter.expandGroups( listview );
        return adapter;
    }

    public static void onGameDictDownload( Context context, Intent intent )
    {
        intent.setClass( context, GamesList.class );
        context.startActivity( intent );
    }

    private static Intent makeSelfIntent( Context context )
    {
        Intent intent = new Intent( context, GamesList.class );
        intent.setFlags( Intent.FLAG_ACTIVITY_CLEAR_TOP
                         | Intent.FLAG_ACTIVITY_NEW_TASK );
        return intent;
    }

    public static Intent makeRowidIntent( Context context, long rowid )
    {
        Intent intent = makeSelfIntent( context );
        intent.putExtra( ROWID_EXTRA, rowid );
        return intent;
    }

    public static Intent makeGameIDIntent( Context context, int gameID )
    {
        Intent intent = makeSelfIntent( context );
        intent.putExtra( GAMEID_EXTRA, gameID );
        return intent;
    }

    public static Intent makeRematchIntent( Context context, CurGameInfo gi,
                                            long rowid )
    {
        Intent intent = null;
        
        if ( CurGameInfo.DeviceRole.SERVER_STANDALONE == gi.serverRole ) {
            intent = makeSelfIntent( context )
                .putExtra( REMATCH_ROWID_EXTRA, rowid );
        } else {
            Utils.notImpl( context );
        }

        return intent;
    }

    public static Intent makeAlertIntent( Context context, String msg )
    {
        Intent intent = makeSelfIntent( context );
        intent.putExtra( ALERT_MSG, msg );
        return intent;
    }

    public static void openGame( Context context, Uri data )
    {
        Intent intent = makeSelfIntent( context );
        intent.setData( data );
        context.startActivity( intent );
    }
}
