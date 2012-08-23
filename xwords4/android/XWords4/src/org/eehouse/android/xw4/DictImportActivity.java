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

import android.app.Activity;
import android.os.Bundle;
import android.os.AsyncTask;
import android.content.Intent;
import android.net.Uri;
import android.view.Window;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.io.InputStream;
import java.io.File;
import java.net.URI;

import junit.framework.Assert;

public class DictImportActivity extends XWActivity {

    private static DictUtils.DictLoc s_saveWhere = DictUtils.DictLoc.INTERNAL;

    public static void setUseSD( boolean useSD ) 
    {
        s_saveWhere = useSD ?
            DictUtils.DictLoc.EXTERNAL : DictUtils.DictLoc.INTERNAL;
    }

    private class DownloadFilesTask extends AsyncTask<Uri, Integer, Long> {
        private String m_saved = null;
        @Override
        protected Long doInBackground( Uri... uris )
        {
            m_saved = null;

            int count = uris.length;
            Assert.assertTrue( 1 == count );
            long totalSize = 0;
            for ( int ii = 0; ii < count; ii++ ) {
                Uri uri = uris[ii];
                DbgUtils.logf( "trying %s", uri );

                try {
                    URI jUri = new URI( uri.getScheme(), 
                                        uri.getSchemeSpecificPart(), 
                                        uri.getFragment() );
                    InputStream is = jUri.toURL().openStream();
                    m_saved = saveDict( is, uri.getPath() );
                    is.close();
                } catch ( java.net.URISyntaxException use ) {
                    DbgUtils.loge( use );
                } catch ( java.net.MalformedURLException mue ) {
                    DbgUtils.loge( mue );
                } catch ( java.io.IOException ioe ) {
                    DbgUtils.loge( ioe );
                }
            }
            return totalSize;
        }

        @Override
        protected void onPostExecute( Long result )
        {
            DbgUtils.logf( "onPostExecute passed %d", result );
            if ( null != m_saved ) {
                DictLangCache.inval( DictImportActivity.this, m_saved, 
                                     s_saveWhere, true );
            }
            finish();
        }
    } // class DownloadFilesTask

	@Override
	protected void onCreate( Bundle savedInstanceState ) 
    {
		super.onCreate( savedInstanceState );

		requestWindowFeature( Window.FEATURE_LEFT_ICON );
		setContentView( R.layout.import_dict );
		getWindow().setFeatureDrawableResource( Window.FEATURE_LEFT_ICON,
                                                R.drawable.icon48x48 );

		ProgressBar progressBar = (ProgressBar)findViewById( R.id.progress_bar );

		Intent intent = getIntent();
		Uri uri = intent.getData();
		if ( null != uri) {
			if ( null != intent.getType() 
                 && intent.getType().equals( "application/x-xwordsdict" ) ) {
                DbgUtils.logf( "based on MIME type" );
                new DownloadFilesTask().execute( uri );
            } else if ( uri.toString().endsWith( XWConstants.DICT_EXTN ) ) {
                String fmt = getString( R.string.downloading_dictf );
                String txt = String.format( fmt, basename( uri.getPath()) );
                TextView view = (TextView)findViewById( R.id.dwnld_message );
                view.setText( txt );
                new DownloadFilesTask().execute( uri );
			} else {
                DbgUtils.logf( "bogus intent: %s/%s", intent.getType(), uri );
				finish();
			}
        }
	}

    private String saveDict( InputStream inputStream, String path )
    {
        String name = basename( path );
        if ( DictUtils.saveDict( this, inputStream, name, s_saveWhere ) ) {
            return name;
        } else {
            return null;
        }
    }

    private String basename( String path )
    {
        return new File(path).getName();
    }
}


