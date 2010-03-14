/* -*- compile-command: "cd ../../../../../; ant install"; -*- */

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

public class DictImportActivity extends Activity {

    private class DownloadFilesTask extends AsyncTask<Uri, Integer, Long> {
        @Override
        protected Long doInBackground( Uri... uris )
        {
            int count = uris.length;
            Assert.assertTrue( 1 == count );
            long totalSize = 0;
            for ( int ii = 0; ii < count; ii++ ) {
                Uri uri = uris[ii];
                Utils.logf( "trying %s", uri );

                try {
                    URI jUri = new URI( uri.getScheme(), 
                                        uri.getSchemeSpecificPart(), 
                                        uri.getFragment() );
                    InputStream is = jUri.toURL().openStream();
                    saveDict( is, uri.getPath() );
                } catch ( java.net.URISyntaxException use ) {
                    Utils.logf( "URISyntaxException: %s" + use.toString() );
                } catch ( java.net.MalformedURLException mue ) {
                    Utils.logf( "MalformedURLException: %s" + mue.toString() );
                } catch ( java.io.IOException ioe ) {
                    Utils.logf( "IOException: %s" + ioe.toString() );
                }
            }
            return totalSize;
        }

        @Override
        protected void onPostExecute( Long result )
        {
            Utils.logf( "onPostExecute passed %d", result );
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
                Utils.logf( "based on MIME type" );
                new DownloadFilesTask().execute( uri );
            } else if ( uri.toString().endsWith( ".xwd" ) ) {
                String fmt = getString( R.string.downloading_dictf );
                String txt = String.format( fmt, basename( uri.getPath()) );
                TextView view = (TextView)findViewById( R.id.dwnld_message );
                view.setText( txt );
                new DownloadFilesTask().execute( uri );
			} else {
                Utils.logf( "bogus intent: %s/%s", intent.getType(), uri );
				finish();
			}
        }
	}

    private void saveDict( InputStream inputStream, String path )
    {
        try {
            Utils.saveDict( this, basename(path), inputStream );
            inputStream.close();
        } catch ( java.io.IOException ioe ) {
            Utils.logf( "IOException: %s" + ioe.toString() );
        }
    }

    private String basename( String path )
    {
        return new File(path).getName();
    }
}


