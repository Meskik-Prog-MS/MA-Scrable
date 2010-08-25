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
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.AdapterView;
import android.content.Context;
import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;
import android.view.ContextMenu;
import android.view.MenuItem;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.LayoutInflater;
import android.view.MenuInflater;
import android.preference.PreferenceManager;
import android.content.SharedPreferences;
import junit.framework.Assert;

import org.eehouse.android.xw4.jni.XwJNI;
import org.eehouse.android.xw4.jni.JNIUtilsImpl;

public class DictsActivity extends ListActivity 
    implements View.OnClickListener,
               XWListItem.DeleteCallback {
    String[] m_dicts;

    private class DictListAdapter extends XWListAdapter {
        private Context m_context;
        private String[] m_langNames;

        public DictListAdapter( Context context ) {
            super( context, m_dicts.length );
            m_context = context;
            Resources res = m_context.getResources();
            m_langNames = res.getStringArray( R.array.language_names );
        }

        public Object getItem( int position) { return m_dicts[position]; }
        public View getView( final int position, View convertView, 
                             ViewGroup parent ) {
            LayoutInflater factory = LayoutInflater.from( DictsActivity.this );
            final XWListItem view
                = (XWListItem)factory.inflate( R.layout.list_item, null );
            view.setPosition( position );

            // append language code
            byte[] dict = GameUtils.openDict( DictsActivity.this,
                                              m_dicts[position] );
            int code = XwJNI.dict_getLanguageCode( dict, JNIUtilsImpl.get() );
            view.setText( m_dicts[position] + " (" + m_langNames[code] + ")" );

            if ( !GameUtils.dictIsBuiltin( DictsActivity.this,
                                           m_dicts[position] ) ) {
                view.setDeleteCallback( DictsActivity.this );
            }

            return view;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate( savedInstanceState );

        setContentView( R.layout.dict_browse );
        registerForContextMenu( getListView() );

        Button download = (Button)findViewById( R.id.download );
        download.setOnClickListener( this );

        mkListAdapter();
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        mkListAdapter();
    }

    public void onClick( View v ) {
        startActivity( Utils.mkDownloadActivity( this ) );
    }

    @Override
    public void onCreateContextMenu( ContextMenu menu, View view, 
                                     ContextMenuInfo menuInfo ) 
    {
        super.onCreateContextMenu( menu, view, menuInfo );

        MenuInflater inflater = getMenuInflater();
        inflater.inflate( R.menu.dicts_item_menu, menu );

        AdapterView.AdapterContextMenuInfo info
            = (AdapterView.AdapterContextMenuInfo)menuInfo;
    }
   
    @Override
    public boolean onContextItemSelected( MenuItem item ) 
    {
        boolean handled = false;
        AdapterView.AdapterContextMenuInfo info;
        try {
            info = (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
        } catch (ClassCastException e) {
            Utils.logf( "bad menuInfo:" + e.toString() );
            return false;
        }
        
        int id = item.getItemId();
        switch( id ) {
        case R.id.dicts_item_select:
            SharedPreferences sp
                = PreferenceManager.getDefaultSharedPreferences( this );
            SharedPreferences.Editor editor = sp.edit();
            String key = getString( R.string.key_default_dict );
            editor.putString( key, m_dicts[info.position] );
            editor.commit();
            break;
        case R.id.dicts_item_details:
            Utils.notImpl( this );
            break;
        }

        return handled;
    }

    // DeleteCallback interface
    public void deleteCalled( int myPosition )
    {
        GameUtils.deleteDict( this, m_dicts[myPosition] );
        mkListAdapter();
    }

    private void mkListAdapter()
    {
        m_dicts = GameUtils.dictList( this );
        setListAdapter( new DictListAdapter( this ) );
    }

}