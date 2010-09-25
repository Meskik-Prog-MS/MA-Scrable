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
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import java.util.ArrayList;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.app.Dialog;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.view.MenuInflater;
import android.view.KeyEvent;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ListAdapter;
import android.widget.SpinnerAdapter;
import android.widget.Toast;
import android.database.DataSetObserver;
import junit.framework.Assert;

import org.eehouse.android.xw4.jni.*;
import org.eehouse.android.xw4.jni.CurGameInfo.DeviceRole;

public class GameConfig extends Activity implements View.OnClickListener,
                                                    XWListItem.DeleteCallback {

    private static final int PLAYER_EDIT = 1;
    // private static final int ROLE_EDIT_RELAY = 2;
    // private static final int ROLE_EDIT_SMS = 3;
    // private static final int ROLE_EDIT_BT = 4;
    private static final int FORCE_REMOTE = 5;
    private static final int CONFIRM_CHANGE = 6;

    private CheckBox m_notNetworkedGameCheckbx;
    private CheckBox m_joinPublicCheck;
    private LinearLayout m_publicRoomsSet;
    private LinearLayout m_privateRoomsSet;

    private boolean m_notNetworkedGame;
    private Button m_addPlayerButton;
    private Button m_jugglePlayersButton;
    private View m_connectSet;  // really a LinearLayout
    private Spinner m_roomChoose;
    // private Button m_configureButton;
    private String m_path;
    private CurGameInfo m_gi;
    private CurGameInfo m_giOrig;
    private int m_whichPlayer;
    private Dialog m_curDialog;
    // private Spinner m_roleSpinner;
    // private Spinner m_connectSpinner;
    private Spinner m_phoniesSpinner;
    private Spinner m_dictSpinner;
    private String[] m_dictItems;
    private String[] m_dicts;
    private int m_browsePosition;
    private LinearLayout m_playerLayout;
    private CommsAddrRec m_carOrig;
    private CommsAddrRec m_car;
    private CommonPrefs m_cp;
    private boolean m_canDoSMS = false;
    private boolean m_canDoBT = false;
    private int m_nMoves = 0;
    private CommsAddrRec.CommsConnType[] m_types;
    private String[] m_connStrings;

    class RemoteChoices extends XWListAdapter {
        public RemoteChoices() { super( GameConfig.this, m_gi.nPlayers ); }

        public Object getItem( int position) { return m_gi.players[position]; }
        public View getView( final int position, View convertView, 
                             ViewGroup parent ) {
            CompoundButton.OnCheckedChangeListener lstnr;
            lstnr = new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged( CompoundButton buttonView, 
                                                 boolean isChecked )
                    {
                        m_gi.players[position].isLocal = !isChecked;
                    }
                };
            CheckBox cb = new CheckBox( GameConfig.this );
            LocalPlayer lp = m_gi.players[position];
            cb.setText( lp.name );
            cb.setChecked( !lp.isLocal );
            cb.setOnCheckedChangeListener( lstnr );
            return cb;
        }
    }

    @Override
    protected Dialog onCreateDialog( int id )
    {
        Dialog dialog = null;
        LayoutInflater factory;
        DialogInterface.OnClickListener dlpos;
        AlertDialog.Builder ab;

        switch (id) {
        case PLAYER_EDIT:
            factory = LayoutInflater.from(this);
            final View playerEditView
                = factory.inflate( R.layout.player_edit, null );

            dialog = new AlertDialog.Builder( this )
                .setTitle(R.string.player_edit_title)
                .setView(playerEditView)
                .setPositiveButton( R.string.button_ok,
                                    new DialogInterface.OnClickListener() {
                                        public void onClick( DialogInterface dlg, 
                                                             int whichButton ) {
                                            getPlayerSettings();
                                            loadPlayers();
                                        }
                                    })
                .setNegativeButton( R.string.button_cancel, null )
                .create();
            break;
        // case ROLE_EDIT_RELAY:
        // case ROLE_EDIT_SMS:
        // case ROLE_EDIT_BT:
        //     dialog = new AlertDialog.Builder( this )
        //         .setTitle(titleForDlg(id))
        //         .setView( LayoutInflater.from(this)
        //                   .inflate( layoutForDlg(id), null ))
        //         .setPositiveButton( R.string.button_ok,
        //                             new DialogInterface.OnClickListener() {
        //                                 public void onClick( DialogInterface dlg, 
        //                                                      int whichButton ) {
        //                                     getRoleSettings();
        //                                 }
        //                             })
        //         .setNegativeButton( R.string.button_cancel, null )
        //         .create();
        //     break;

        case FORCE_REMOTE:
            dialog = new AlertDialog.Builder( this )
                .setTitle( R.string.force_title )
                .setView( LayoutInflater.from(this)
                          .inflate( layoutForDlg(id), null ) )
                .setPositiveButton( R.string.button_ok,
                                    new DialogInterface.OnClickListener() {
                                        public void onClick( DialogInterface dlg, 
                                                             int whichButton ) {
                                            loadPlayers();
                                        }
                                    })
                .create();
            dialog.setOnDismissListener( new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss( DialogInterface di ) 
                    {
                        if ( m_gi.forceRemoteConsistent() ) {
                            Toast.makeText( GameConfig.this, 
                                            R.string.forced_consistent,
                                            Toast.LENGTH_SHORT).show();
                            loadPlayers();
                        }
                    }
                });
            break;
        case CONFIRM_CHANGE:
            dialog = new AlertDialog.Builder( this )
                .setTitle( R.string.confirm_save_title )
                .setMessage( R.string.confirm_save )
                .setPositiveButton( R.string.button_save,
                                    new DialogInterface.OnClickListener() {
                                        public void onClick( DialogInterface dlg, 
                                                             int whichButton ) {
                                            applyChanges( true );
                                            finish();
                                        }
                                    })
                .setNegativeButton( R.string.button_discard, 
                                    new DialogInterface.OnClickListener() {
                                        public void onClick( DialogInterface dlg, 
                                                             int whichButton ) {
                                            finish();
                                        }
                                    })
                .create();
            break;
        }
        return dialog;
    }

    @Override
    protected void onPrepareDialog( int id, Dialog dialog )
    { 
        m_curDialog = dialog;

        switch ( id ) {
        case PLAYER_EDIT:
            setPlayerSettings();
            break;
        // case ROLE_EDIT_RELAY:
        // case ROLE_EDIT_SMS:
        // case ROLE_EDIT_BT:
        //     setRoleHints( id, dialog );
        //     setRoleSettings();
        //     break;
        case FORCE_REMOTE:
            ListView listview = (ListView)dialog.findViewById( R.id.players );
            listview.setAdapter( new RemoteChoices() );
            break;
        }
        super.onPrepareDialog( id, dialog );
    }

    private void setPlayerSettings()
    {
        // Hide remote option if in standalone mode...
        boolean isServer = !m_notNetworkedGame;
        LocalPlayer lp = m_gi.players[m_whichPlayer];
        Utils.setText( m_curDialog, R.id.player_name_edit, lp.name );
        Utils.setText( m_curDialog, R.id.password_edit, lp.password );

        final View localSet = m_curDialog.findViewById( R.id.local_player_set );

        CheckBox check = (CheckBox)
            m_curDialog.findViewById( R.id.remote_check );
        if ( isServer ) {
            CompoundButton.OnCheckedChangeListener lstnr =
                new CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged( CompoundButton buttonView, 
                                                  boolean checked ) {
                        localSet.setVisibility( checked ? 
                                                View.GONE : View.VISIBLE );
                    }
                };
            check.setOnCheckedChangeListener( lstnr );
            check.setVisibility( View.VISIBLE );
        } else {
            check.setVisibility( View.GONE );
            localSet.setVisibility( View.VISIBLE );
        }

        check = (CheckBox)m_curDialog.findViewById( R.id.robot_check );
        CompoundButton.OnCheckedChangeListener lstnr =
            new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged( CompoundButton buttonView, 
                                              boolean checked ) {
                    View view = m_curDialog.findViewById( R.id.password_set );
                    view.setVisibility( checked ? View.GONE : View.VISIBLE );
                }
            };
        check.setOnCheckedChangeListener( lstnr );

        Utils.setChecked( m_curDialog, R.id.robot_check, lp.isRobot );
        Utils.setChecked( m_curDialog, R.id.remote_check, ! lp.isLocal );
    }

    private void getPlayerSettings()
    {
        LocalPlayer lp = m_gi.players[m_whichPlayer];
        lp.name = Utils.getText( m_curDialog, R.id.player_name_edit );
        lp.password = Utils.getText( m_curDialog, R.id.password_edit );

        lp.isRobot = Utils.getChecked( m_curDialog, R.id.robot_check );
        lp.isLocal = !Utils.getChecked( m_curDialog, R.id.remote_check );
    }

    @Override
    public void onCreate( Bundle savedInstanceState )
    {
        super.onCreate(savedInstanceState);

        // 1.5 doesn't have SDK_INT.  So parse the string version.
        // int sdk_int = 0;
        // try {
        //     sdk_int = Integer.decode( android.os.Build.VERSION.SDK );
        // } catch ( Exception ex ) {}
        // m_canDoSMS = sdk_int >= android.os.Build.VERSION_CODES.DONUT;

        m_cp = CommonPrefs.get( this );

        Intent intent = getIntent();
        Uri uri = intent.getData();
        m_path = uri.getPath();
        if ( m_path.charAt(0) == '/' ) {
            m_path = m_path.substring( 1 );
        }

        int gamePtr = XwJNI.initJNI();
        m_giOrig = new CurGameInfo( this );
        GameUtils.loadMakeGame( this, gamePtr, m_giOrig, m_path );
        m_nMoves = XwJNI.model_getNMoves( gamePtr );
        m_giOrig.setInProgress( 0 < m_nMoves );
        int curSel = listAvailableDicts( m_giOrig.dictName );
        m_giOrig.dictLang = 
            DictLangCache.getLangCode( this, 
                                       GameUtils.dictList( this )[curSel] );
        m_gi = new CurGameInfo( m_giOrig );

        m_carOrig = new CommsAddrRec( this );
        if ( XwJNI.game_hasComms( gamePtr ) ) {
            XwJNI.comms_getAddr( gamePtr, m_carOrig );
        } else {
            String relayName = CommonPrefs.getDefaultRelayHost( this );
            int relayPort = CommonPrefs.getDefaultRelayPort( this );
            XwJNI.comms_getInitialAddr( m_carOrig, relayName, relayPort );
        }
        XwJNI.game_dispose( gamePtr );

        m_car = new CommsAddrRec( m_carOrig );

        setContentView(R.layout.game_config);

        m_notNetworkedGameCheckbx = 
            (CheckBox)findViewById(R.id.game_not_networked);
        m_notNetworkedGameCheckbx.setOnClickListener( this );
        // m_notNetworkedGameCheckbx.setChecked( true );
        // m_notNetworkedGame = true;

        m_joinPublicCheck = (CheckBox)findViewById(R.id.join_public_room_check);
        m_joinPublicCheck.setOnClickListener( this );
        m_joinPublicCheck.setChecked( m_car.ip_relay_seeksPublicRoom );
        Utils.setChecked( this, R.id.advertise_new_room_check, 
                          m_car.ip_relay_advertiseRoom );
        m_publicRoomsSet = (LinearLayout)findViewById(R.id.public_rooms_set );
        m_privateRoomsSet = (LinearLayout)findViewById(R.id.private_rooms_set );
        Utils.setText( this, R.id.room_edit, m_car.ip_relay_invite );

        m_addPlayerButton = (Button)findViewById(R.id.add_player);
        m_addPlayerButton.setOnClickListener( this );
        m_jugglePlayersButton = (Button)findViewById(R.id.juggle_players);
        m_jugglePlayersButton.setOnClickListener( this );

        m_connectSet = findViewById(R.id.connect_set);
        // m_configureButton = (Button)findViewById(R.id.configure_role);
        // m_configureButton.setOnClickListener( this );

        m_roomChoose = (Spinner)findViewById(R.id.room_spinner);
        String[] rooms = { "Room 1", "NSA 1200+", "Some other room" };
        ArrayAdapter<String> adapter = 
            new ArrayAdapter<String>( this,
                                      android.R.layout.simple_spinner_item,
                                      rooms );
        m_roomChoose.setAdapter( adapter );

        adjustConnectStuff();

        m_playerLayout = (LinearLayout)findViewById( R.id.player_list );
        m_notNetworkedGame = DeviceRole.SERVER_STANDALONE == m_gi.serverRole;
        m_notNetworkedGameCheckbx.setChecked( m_notNetworkedGame );
        loadPlayers();

        m_dictSpinner = (Spinner)findViewById( R.id.dict_spinner );
        configDictSpinner();

        // m_roleSpinner = (Spinner)findViewById( R.id.role_spinner );
        // m_roleSpinner.setSelection( m_gi.serverRole.ordinal() );
        // m_roleSpinner.setOnItemSelectedListener(new OnItemSelectedListener() {
        //         @Override
        //         public void onItemSelected(AdapterView<?> parentView, 
        //                                    View selectedItemView, int position, 
        //                                    long id ) {
        //             m_gi.setServerRole( DeviceRole.values()[position] );
        //             adjustVisibility();
        //             loadPlayers();
        //         }

        //         @Override
        //         public void onNothingSelected(AdapterView<?> parentView) {
        //         }
        //     });

        // configConnectSpinner();

        m_phoniesSpinner = (Spinner)findViewById( R.id.phonies_spinner );
        m_phoniesSpinner.setSelection( m_gi.phoniesAction.ordinal() );

        Utils.setChecked( this, R.id.hints_allowed, !m_gi.hintsNotAllowed );
        Utils.setInt( this, R.id.timer_minutes_edit, 
                      m_gi.gameSeconds/60/m_gi.nPlayers );

        CheckBox check = (CheckBox)findViewById( R.id.use_timer );
        CompoundButton.OnCheckedChangeListener lstnr =
            new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged( CompoundButton buttonView, 
                                              boolean checked ) {
                    View view = findViewById( R.id.timer_set );
                    view.setVisibility( checked ? View.VISIBLE : View.GONE );
                }
            };
        check.setOnCheckedChangeListener( lstnr );
        Utils.setChecked( this, R.id.use_timer, m_gi.timerEnabled );

        Utils.setChecked( this, R.id.smart_robot, 0 < m_gi.robotSmartness );

        // adjustVisibility();

        String fmt = getString( R.string.title_game_configf );
        setTitle( String.format( fmt, GameUtils.gameName( this, m_path ) ) );
    } // onCreate

    // DeleteCallback interface
    public void deleteCalled( int myPosition )
    {
        if ( m_gi.delete( myPosition ) ) {
            loadPlayers();
        }
    }

    public void onClick( View view ) 
    {
        if ( m_addPlayerButton == view ) {
            int curIndex = m_gi.nPlayers;
            if ( curIndex < CurGameInfo.MAX_NUM_PLAYERS ) {
                m_gi.addPlayer(); // ups nPlayers
                loadPlayers();
            }
        } else if ( m_jugglePlayersButton == view ) {
            m_gi.juggle();
            loadPlayers();
        } else if ( m_notNetworkedGameCheckbx == view ) {
            if ( m_gi.nPlayers < 2 ) {
                Assert.assertTrue( m_gi.nPlayers == 1 );
                m_gi.addPlayer();
                Toast.makeText( this, R.string.added_player,
                                Toast.LENGTH_SHORT).show();
            }
            m_notNetworkedGame = m_notNetworkedGameCheckbx.isChecked();
            m_gi.setServerRole( m_notNetworkedGame
                                ? DeviceRole.SERVER_STANDALONE 
                                : DeviceRole.SERVER_ISCLIENT );

            loadPlayers();
        } else if ( m_joinPublicCheck == view ) {
            adjustConnectStuff();
        // } else if ( m_configureButton == view ) {
        //     int position = m_connectSpinner.getSelectedItemPosition();
        //     switch ( m_types[ position ] ) {
        //     case COMMS_CONN_RELAY:
        //         showDialog( ROLE_EDIT_RELAY );
        //         break;
        //     case COMMS_CONN_SMS:
        //         showDialog( ROLE_EDIT_SMS );
        //         break;
        //     case COMMS_CONN_BT:
        //         showDialog( ROLE_EDIT_BT );
        //         break;
        //     }
        } else {
            Utils.logf( "unknown v: " + view.toString() );
        }
    } // onClick

    @Override
    public boolean onKeyDown( int keyCode, KeyEvent event )
    {
        boolean consumed = false;
        if ( keyCode == KeyEvent.KEYCODE_BACK ) {
            saveChanges();
            if ( 0 >= m_nMoves ) { // no confirm needed 
                applyChanges( true );
            } else if ( m_giOrig.changesMatter(m_gi) 
                        || m_carOrig.changesMatter(m_car) ) {
                showDialog( CONFIRM_CHANGE );
                consumed = true; // don't dismiss activity yet!
            } else {
                applyChanges( false );
            }
        }

        return consumed || super.onKeyDown( keyCode, event );
    }

    @Override
    protected void onResume()
    {
        configDictSpinner();
        super.onResume();
    }

    private void loadPlayers()
    {
        m_playerLayout.removeAllViews();

        String[] names = m_gi.visibleNames( this );
        // only enable delete if one will remain (or two if networked)
        boolean canDelete = names.length > 2
            || (m_notNetworkedGame && names.length > 1);
        LayoutInflater factory = LayoutInflater.from(this);
        for ( int ii = 0; ii < names.length; ++ii ) {

            final XWListItem view
                = (XWListItem)factory.inflate( R.layout.list_item, null );
            view.setPosition( ii );
            view.setText( names[ii] );
            view.setGravity( Gravity.CENTER );
            if ( canDelete ) {
                view.setDeleteCallback( this );
            }

            view.setOnClickListener( new View.OnClickListener() {
                    @Override
                    public void onClick( View view ) {
                        m_whichPlayer = ((XWListItem)view).getPosition();
                        showDialog( PLAYER_EDIT );
                    }
                } );
            m_playerLayout.addView( view );

            View divider = factory.inflate( R.layout.divider_view, null );
            divider.setVisibility( View.VISIBLE );
            m_playerLayout.addView( divider );
        }

        m_addPlayerButton
            .setVisibility( names.length >= CurGameInfo.MAX_NUM_PLAYERS?
                            View.GONE : View.VISIBLE );
        m_jugglePlayersButton
            .setVisibility( names.length <= 1 ?
                            View.GONE : View.VISIBLE );
        m_connectSet.setVisibility( m_notNetworkedGame?
                                    View.GONE : View.VISIBLE );

        if ( ! m_notNetworkedGame
             && ((0 == m_gi.remoteCount() )
                 || (m_gi.nPlayers == m_gi.remoteCount()) ) ) {
            showDialog( FORCE_REMOTE );
        }
        adjustPlayersLabel();
    } // loadPlayers

    private int listAvailableDicts( String curDict )
    {
        int curSel = -1;

        String[] list = GameUtils.dictList( this );

        m_browsePosition = list.length;
        m_dictItems = new String[m_browsePosition+1];
        m_dictItems[m_browsePosition] = getString( R.string.download_dicts );
        m_dicts = new String[m_browsePosition];
        
        for ( int ii = 0; ii < m_browsePosition; ++ii ) {
            String dict = list[ii];
            m_dicts[ii] = dict;
            m_dictItems[ii] = DictLangCache.annotatedDictName( this, dict );
            if ( dict.equals( curDict ) ) {
                curSel = ii;
            }
        }

        return curSel;
    }

    private void configDictSpinner()
    {
        int curSel = listAvailableDicts( m_gi.dictName );

        ArrayAdapter<String> adapter = 
            new ArrayAdapter<String>( this,
                                      android.R.layout.simple_spinner_item,
                                      m_dictItems );
        int resID = android.R.layout.simple_spinner_dropdown_item;
        adapter.setDropDownViewResource( resID );
        m_dictSpinner.setAdapter( adapter );
        if ( curSel >= 0 ) {
            m_dictSpinner.setSelection( curSel );
        } 

        m_dictSpinner.setOnItemSelectedListener(new OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parentView, 
                                       View selectedItemView, 
                                       int position, long id ) {
                if ( position == m_browsePosition ) {
                    startActivity( Utils.mkDownloadActivity(GameConfig.this) );
                } else {
                    m_gi.dictName = m_dicts[position];
                    Utils.logf( "assigned dictName: " + m_gi.dictName );
                    m_gi.dictLang = DictLangCache.getLangCode( GameConfig.this, 
                                                               m_gi.dictName );
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parentView) {}
            });
    }

    // private void configConnectSpinner()
    // {
    //     m_connectSpinner = (Spinner)findViewById( R.id.connect_spinner );
    //     m_connStrings = makeXportStrings();
    //     ArrayAdapter<String> adapter = 
    //         new ArrayAdapter<String>( this,
    //                                   android.R.layout.simple_spinner_item,
    //                                   m_connStrings );
    //     adapter.setDropDownViewResource( android.R.layout
    //                                      .simple_spinner_dropdown_item );
    //     m_connectSpinner.setAdapter( adapter );
    //     m_connectSpinner.setSelection( connTypeToPos( m_car.conType ) );
    //     AdapterView.OnItemSelectedListener
    //         lstnr = new AdapterView.OnItemSelectedListener() {
    //                 @Override
    //                 public void onItemSelected(AdapterView<?> parentView, 
    //                                            View selectedItemView, 
    //                                            int position, 
    //                                            long id ) 
    //                 {
    //                     String fmt = getString( R.string.configure_rolef );
    //                     m_configureButton
    //                         .setText( String.format( fmt, 
    //                                                  m_connStrings[position] ));
    //                 }

    //                 @Override
    //                 public void onNothingSelected(AdapterView<?> parentView) 
    //                 {
    //                 }
    //             };
    //     m_connectSpinner.setOnItemSelectedListener( lstnr );

    // } // configConnectSpinner

    private void adjustPlayersLabel()
    {
        Utils.logf( "adjustPlayersLabel()" );
        String label;
        if ( m_notNetworkedGame ) {
            label = getString( R.string.players_label_standalone );
        } else {
            String fmt = getString( R.string.players_label_host );
            int remoteCount = m_gi.remoteCount();
            label = String.format( fmt, m_gi.nPlayers - remoteCount, 
                                   remoteCount );
        }
        ((TextView)findViewById( R.id.players_label )).setText( label );
    }

    private void adjustConnectStuff()
    {
        if ( m_joinPublicCheck.isChecked() ) {
            m_privateRoomsSet.setVisibility( View.GONE );
            m_publicRoomsSet.setVisibility( View.VISIBLE );

            // make the room spinner match the saved value if present
            String invite = m_car.ip_relay_invite;
            ArrayAdapter<String> adapter = 
                (ArrayAdapter<String>)m_roomChoose.getAdapter();
            for ( int ii = 0; ii < adapter.getCount(); ++ii ) {
                if ( adapter.getItem(ii).equals( invite ) ) {
                    m_roomChoose.setSelection( ii );
                    break;
                }
            }

        } else {
            m_privateRoomsSet.setVisibility( View.VISIBLE );
            m_publicRoomsSet.setVisibility( View.GONE );
        }
    }
    
    private int connTypeToPos( CommsAddrRec.CommsConnType typ )
    {
        switch( typ ) {
        case COMMS_CONN_RELAY:
            return 0;
        case COMMS_CONN_SMS:
            return 1;
        case COMMS_CONN_BT:
            return 2;
        }
        return -1;
    }

    private int layoutForDlg( int id ) 
    {
        switch( id ) {
        // case ROLE_EDIT_RELAY:
        //     return R.layout.role_edit_relay;
        // case ROLE_EDIT_SMS:
        //     return R.layout.role_edit_sms;
        // case ROLE_EDIT_BT:
        //     return R.layout.role_edit_bt;
        case FORCE_REMOTE:
            return R.layout.force_remote;
        }
        Assert.fail();
        return 0;
    }

    private int titleForDlg( int id ) 
    {
        switch( id ) {
        // case ROLE_EDIT_RELAY:
        //     return R.string.tab_relay;
        // case ROLE_EDIT_SMS:
        //     return R.string.tab_sms;
        // case ROLE_EDIT_BT:
        //     return R.string.tab_bluetooth;
        }
        Assert.fail();
        return -1;
    }

    private String[] makeXportStrings()
    {
        ArrayList<String> strings = new ArrayList<String>();
        ArrayList<CommsAddrRec.CommsConnType> types
            = new ArrayList<CommsAddrRec.CommsConnType>();

        strings.add( getString(R.string.tab_relay) );
        types.add( CommsAddrRec.CommsConnType.COMMS_CONN_RELAY );

        if ( m_canDoSMS ) {
            strings.add( getString(R.string.tab_sms) );
            types.add( CommsAddrRec.CommsConnType.COMMS_CONN_SMS );
        }
        if ( m_canDoBT ) {
            strings.add( getString(R.string.tab_bluetooth) );
            types.add( CommsAddrRec.CommsConnType.COMMS_CONN_BT );
        }
        m_types = types.toArray( new CommsAddrRec.CommsConnType[types.size()] );
        return strings.toArray( new String[strings.size()] );
    }

    private void saveChanges()
    {
        m_gi.hintsNotAllowed = !Utils.getChecked( this, R.id.hints_allowed );
        m_gi.timerEnabled = Utils.getChecked(  this, R.id.use_timer );
        m_gi.gameSeconds = 60 * m_gi.nPlayers *
            Utils.getInt(  this, R.id.timer_minutes_edit );
        m_gi.robotSmartness
            = Utils.getChecked( this, R.id.smart_robot ) ? 1 : 0;

        int position = m_phoniesSpinner.getSelectedItemPosition();
        m_gi.phoniesAction = CurGameInfo.XWPhoniesChoice.values()[position];

        if ( !m_notNetworkedGame ) {
            m_car.ip_relay_seeksPublicRoom = m_joinPublicCheck.isChecked();
            Utils.logf( "ip_relay_seeksPublicRoom: %s", 
                        m_car.ip_relay_seeksPublicRoom?"true":"false" );
            m_car.ip_relay_advertiseRoom = 
                Utils.getChecked( this, R.id.advertise_new_room_check );
            if ( m_car.ip_relay_seeksPublicRoom ) {
                ArrayAdapter<String> adapter = 
                    (ArrayAdapter<String>)m_roomChoose.getAdapter();
                m_car.ip_relay_invite = 
                    adapter.getItem(m_roomChoose.getSelectedItemPosition());
            } else {
                m_car.ip_relay_invite = Utils.getText( this, R.id.room_edit );
            }
        }

        m_gi.fixup();

        // position = m_connectSpinner.getSelectedItemPosition();
        // m_car.conType = m_types[ position ];

        m_car.conType = m_notNetworkedGame
            ? CommsAddrRec.CommsConnType.COMMS_CONN_NONE
            : CommsAddrRec.CommsConnType.COMMS_CONN_RELAY;
    }

    private void applyChanges( boolean forceNew )
    {
        // This should be a separate function, commitChanges() or
        // somesuch.  But: do we have a way to save changes to a gi
        // that don't reset the game, e.g. player name for standalone
        // games?
        byte[] dictBytes = GameUtils.openDict( this, m_gi.dictName );
        int gamePtr = XwJNI.initJNI();
        boolean madeGame = false;

        if ( !forceNew ) {
            byte[] stream = GameUtils.savedGame( this, m_path );
            // Will fail if there's nothing in the stream but a gi.
            madeGame = XwJNI.game_makeFromStream( gamePtr, stream, 
                                                  JNIUtilsImpl.get(),
                                                  new CurGameInfo(this), 
                                                  dictBytes, m_gi.dictName, 
                                                  m_cp );
        }

        if ( forceNew || !madeGame ) {
            m_gi.setInProgress( false );
            m_gi.fixup();
            XwJNI.game_makeNewGame( gamePtr, m_gi, JNIUtilsImpl.get(), 
                                    m_cp, dictBytes, m_gi.dictName );
        }

        if ( null != m_car ) {
            XwJNI.comms_setAddr( gamePtr, m_car );
        }

        GameUtils.saveGame( this, gamePtr, m_gi, m_path );

        GameSummary summary = new GameSummary();
        XwJNI.game_summarize( gamePtr, m_gi.nPlayers, summary );
        DBUtils.saveSummary( m_path, summary );

        XwJNI.game_dispose( gamePtr );
    }

}
