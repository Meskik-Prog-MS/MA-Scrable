/* -*- compile-command: "cd ../../../../../; ant debug install"; -*- */
/*
 * Copyright 2009-2011 by Eric House (xwords@eehouse.org).  All rights
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
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Environment;
import android.text.Html;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Random;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import junit.framework.Assert;

import org.eehouse.android.xw4.jni.*;
import org.eehouse.android.xw4.jni.CurGameInfo.DeviceRole;

public class DictUtils {

    // keep in sync with loc_names string-array
    public enum DictLoc { UNKNOWN, BUILT_IN, INTERNAL, EXTERNAL, DOWNLOAD };
    public static final String INVITED = "invited";

    private static DictAndLoc[] s_dictListCache = null;

    static {
        MountEventReceiver.register( new MountEventReceiver.SDCardNotifiee() {
                public void cardMounted( boolean nowMounted )
                {
                    invalDictList();
                }
            } );
    }
 
    public static class DictPairs {
        public byte[][] m_bytes;
        public String[] m_paths;
        public DictPairs( byte[][] bytes, String[] paths ) {
            m_bytes = bytes; m_paths = paths;
        }

        public boolean anyMissing( final String[] names )
        {
            boolean missing = false;
            for ( int ii = 0; ii < m_paths.length; ++ii ) {
                if ( names[ii] != null ) {
                    // It's ok for there to be no dict IFF there's no
                    // name.  That's a player using the default dict.
                    if ( null == m_paths[ii] && null == m_bytes[ii] ) {
                        missing = true;
                        break;
                    }
                }
            }
            return missing;
        }
    } // DictPairs

    public static class DictAndLoc {
        public DictAndLoc( String pname, DictLoc ploc ) {
            name = removeDictExtn(pname); loc = ploc;
        }
        public String name;
        public DictLoc loc;

        @Override 
        public boolean equals( Object obj ) 
        {
            boolean result = false;
            if ( obj instanceof DictAndLoc ) {
                DictAndLoc other = (DictAndLoc)obj;
                
                result = name.equals( other.name )
                    && loc.equals( other.loc );
            }
            return result;
        }
    }
 
    public static void invalDictList()
    {
        s_dictListCache = null;
        // Should I have a list of folks who want to know when this
        // changes?
    }

    private static void tryDir( File dir, boolean strict, DictLoc loc, 
                                ArrayList<DictAndLoc> al )
    {
        if ( null != dir ) {
            String[] list = dir.list();
            if ( null != list ) {
                for ( String file : list ) {
                    if ( isDict( file, strict? dir : null ) ) {
                        al.add( new DictAndLoc( removeDictExtn( file ), loc ) );
                    }
                }
            }
        }
    }

    public static DictAndLoc[] dictList( Context context )
    {
        if ( null == s_dictListCache ) {
            ArrayList<DictAndLoc> al = new ArrayList<DictAndLoc>();

            for ( String file : getAssets( context ) ) {
                if ( isDict( file, null ) ) {
                    al.add( new DictAndLoc( removeDictExtn( file ), 
                                            DictLoc.BUILT_IN ) );
                }
            }

            for ( String file : context.fileList() ) {
                if ( isDict( file, null ) ) {
                    al.add( new DictAndLoc( removeDictExtn( file ),
                                            DictLoc.INTERNAL ) );
                }
            }

            tryDir( getSDDir( context ), false, DictLoc.EXTERNAL, al );
            tryDir( getDownloadDir(), true, DictLoc.DOWNLOAD, al );

            s_dictListCache = 
                al.toArray( new DictUtils.DictAndLoc[al.size()] );
        }
        return s_dictListCache;
    }

    public static DictLoc getDictLoc( Context context, String name )
    {
        DictLoc loc = null;
        name = addDictExtn( name );

        for ( String file : getAssets( context ) ) {
            if ( file.equals( name ) ) {
                loc = DictLoc.BUILT_IN;
                break;
            }
        }

        if ( null == loc ) {
            try {
                FileInputStream fis = context.openFileInput( name );
                fis.close();
                loc = DictLoc.INTERNAL;
            } catch ( java.io.FileNotFoundException fnf ) {
                DbgUtils.loge( fnf );
            } catch ( java.io.IOException ioe ) {
                DbgUtils.loge( ioe );
            }
        }

        if ( null == loc ) {
            File file = getSDPathFor( context, name );
            if ( null != file && file.exists() ) {
                loc = DictLoc.EXTERNAL;
            }
        }

        return loc;
    }

    public static boolean dictExists( Context context, String name )
    {
        return null != getDictLoc( context, name );
    }

    public static boolean dictIsBuiltin( Context context, String name )
    {
        return DictLoc.BUILT_IN == getDictLoc( context, name );
    }

    public static boolean moveDict( Context context, String name,
                                    DictLoc from, DictLoc to )
    {
        boolean success;
        name = addDictExtn( name );

        File toPath = getDictFile( context, name, to );
        if ( null != toPath && toPath.exists() ) {
            success = false;
        } else {
            success = copyDict( context, name, from, to );
            if ( success ) {
                deleteDict( context, name, from );
                invalDictList();
            }
        }
        return success;
    }

    private static boolean copyDict( Context context, String name,
                                     DictLoc from, DictLoc to )
    {
        Assert.assertFalse( from.equals(to) );
        boolean success = false;

        try {
            FileInputStream fis = DictLoc.INTERNAL == from 
                ? context.openFileInput( name )
                : new FileInputStream( getDictFile( context, name, from ) );

            FileOutputStream fos = DictLoc.INTERNAL == to 
                ? context.openFileOutput( name, Context.MODE_PRIVATE ) 
                : new FileOutputStream( getDictFile( context, name, to ) );

            success = DBUtils.copyFileStream( fos, fis );
        } catch ( java.io.FileNotFoundException fnfe ) {
            DbgUtils.loge( fnfe );
        }
        return success;
    } // copyDict

    public static void deleteDict( Context context, String name, DictLoc loc )
    {
        name = addDictExtn( name );
        File path = null;
        switch( loc ) {
        case DOWNLOAD:
            path = getDownloadsPathFor( name );
            break;
        case EXTERNAL:
            path = getSDPathFor( context, name );
            break;
        case INTERNAL:
            context.deleteFile( name );
            break;
        default:
            Assert.fail();
        }

        if ( null != path ) {
            path.delete();
        }

        invalDictList();
    }

    public static void deleteDict( Context context, String name )
    {
        deleteDict( context, name, getDictLoc( context, name ) );
    }

    private static byte[] openDict( Context context, String name, DictLoc loc )
    {
        byte[] bytes = null;

        name = addDictExtn( name );

        if ( loc == DictLoc.UNKNOWN || loc == DictLoc.BUILT_IN ) {
            try {
                AssetManager am = context.getAssets();
                InputStream dict = am.open( name, android.content.res.
                                            AssetManager.ACCESS_RANDOM );

                int len = dict.available(); // this may not be the
                                            // full length!
                bytes = new byte[len];
                int nRead = dict.read( bytes, 0, len );
                if ( nRead != len ) {
                    DbgUtils.logf( "**** warning ****; read only %d of %d bytes.",
                                   nRead, len );
                }
                // check that with len bytes we've read the whole file
                Assert.assertTrue( -1 == dict.read() );
            } catch ( java.io.IOException ee ){
                DbgUtils.logf( "%s failed to open; likely not built-in", name );
            }
        }

        // not an asset?  Try external and internal storage
        if ( null == bytes ) {
            try {
                FileInputStream fis = null;
                if ( null == fis ) {
                    if ( loc == DictLoc.UNKNOWN || loc == DictLoc.DOWNLOAD ) {
                        File path = getDownloadsPathFor( name );
                        if ( null != path && path.exists() ) {
                            DbgUtils.logf( "loading %s from Download", name );
                            fis = new FileInputStream( path );
                        }
                    }
                }
                if ( loc == DictLoc.UNKNOWN || loc == DictLoc.EXTERNAL ) {
                    File sdFile = getSDPathFor( context, name );
                    if ( null != sdFile && sdFile.exists() ) {
                        DbgUtils.logf( "loading %s from SD", name );
                        fis = new FileInputStream( sdFile );
                    }
                }
                if ( null == fis ) {
                    if ( loc == DictLoc.UNKNOWN || loc == DictLoc.INTERNAL ) {
                        DbgUtils.logf( "loading %s from private storage", name );
                        fis = context.openFileInput( name );
                    }
                }
                int len = (int)fis.getChannel().size();
                bytes = new byte[len];
                fis.read( bytes, 0, len );
                fis.close();
                DbgUtils.logf( "Successfully loaded %s", name );
            } catch ( java.io.FileNotFoundException fnf ) {
                DbgUtils.loge( fnf );
            } catch ( java.io.IOException ioe ) {
                DbgUtils.loge( ioe );
            }
        }
        
        return bytes;
    } // openDict

    private static byte[] openDict( Context context, String name )
    {
        return openDict( context, name, DictLoc.UNKNOWN );
    }

    private static String getDictPath( Context context, String name )
    {
        name = addDictExtn( name );

        File file = context.getFileStreamPath( name );
        if ( !file.exists() ) {
            file = getSDPathFor( context, name );
            if ( null != file && !file.exists() ) {
                file = null;
            }
        }
        String path = null == file? null : file.getPath();
        return path;
    }

    private static File getDictFile( Context context, String name, DictLoc to )
    {
        File path;
        switch ( to ) {
        case DOWNLOAD:
            path = getDownloadsPathFor( name );
            break;
        case EXTERNAL:
            path = getSDPathFor( context, name );
            break;
        case INTERNAL:
            path = context.getFileStreamPath( name );
            break;
        default:
            Assert.fail();
            path = null;
        }
        return path;
    }

    public static DictPairs openDicts( Context context, String[] names )
    {
        byte[][] dictBytes = new byte[names.length][];
        String[] dictPaths = new String[names.length];

        HashMap<String,byte[]> seen = new HashMap<String,byte[]>();
        for ( int ii = 0; ii < names.length; ++ii ) {
            byte[] bytes = null;
            String path = null;
            String name = names[ii];
            if ( null != name ) {
                path = getDictPath( context, name );
                if ( null == path ) {
                    bytes = seen.get( name );
                    if ( null == bytes ) {
                        bytes = openDict( context, name );
                        seen.put( name, bytes );
                    }
                }
            }
            dictBytes[ii] = bytes;
            dictPaths[ii] = path;
        }
        return new DictPairs( dictBytes, dictPaths );
    }

    public static boolean saveDict( Context context, InputStream in,
                                    String name, DictLoc loc )
    {
        boolean success = false;
        File sdFile = null;
        boolean useSD = DictLoc.EXTERNAL == loc;
        if ( useSD ) {
            sdFile = getSDPathFor( context, name );
        }

        if ( null != sdFile || !useSD ) {
            try {
                FileOutputStream fos;
                if ( null != sdFile ) {
                    fos = new FileOutputStream( sdFile );
                } else {
                    fos = context.openFileOutput( name, Context.MODE_PRIVATE );
                }
                byte[] buf = new byte[1024];
                int nRead;
                while( 0 <= (nRead = in.read( buf, 0, buf.length )) ) {
                    fos.write( buf, 0, nRead );
                }
                fos.close();
                invalDictList();
                success = true;
            } catch ( java.io.FileNotFoundException fnf ) {
                DbgUtils.loge( fnf );
            } catch ( java.io.IOException ioe ) {
                DbgUtils.loge( ioe );
                deleteDict( context, name );
            }
        }
        return success;
    }

    /*
    // The goal here was to attach intalled dicts to email to save
    // folks having to download them, but there are too many problems
    // to make me think I can create a good UE.  Chief is that the
    // email clients are ignoring my mime type (though they require
    // one in the Intent) because they think they know what an .xwd
    // file is.  Without the right mime type I can't get Crosswords
    // launched to receive the attachment on the other end.
    // Additionally, gmail at least requires that the path start with
    // /mnt/sdcard, and there's no failure notification so I get to
    // warn users myself, or disable the share menuitem for dicts in
    // internal storage.
    public static void shareDict( Context context, String name )
    {
        Intent intent = new Intent( Intent.ACTION_SEND );
        intent.setType( "application/x-xwordsdict" );

        File dictFile = new File( getDictPath( context, name ) );
        DbgUtils.logf( "path: %s", dictFile.getPath() );
        Uri uri = Uri.fromFile( dictFile );
        DbgUtils.logf( "uri: %s", uri.toString() );
        intent.putExtra( Intent.EXTRA_STREAM, uri );

        intent.putExtra( Intent.EXTRA_SUBJECT, 
                         context.getString( R.string.share_subject ) );
        intent.putExtra( Intent.EXTRA_TEXT, 
                         Utils.format( context, R.string.share_bodyf, name ) );

        String title = context.getString( R.string.share_chooser );
        context.startActivity( Intent.createChooser( intent, title ) ); 
    }
     */

    private static boolean isGame( String file )
    {
        return file.endsWith( XWConstants.GAME_EXTN );
    }
 
    private static boolean isDict( String file, File dir )
    {
        boolean ok = file.endsWith( XWConstants.DICT_EXTN );
        if ( ok && null != dir ) {
            String fullPath = new File( dir, file ).getPath();
            ok = XwJNI.dict_getInfo( null, fullPath, JNIUtilsImpl.get(), 
                                     true, null );
        }
        return ok;
    }

    public static String removeDictExtn( String str )
    {
        if ( str.endsWith( XWConstants.DICT_EXTN ) ) {
            int indx = str.lastIndexOf( XWConstants.DICT_EXTN );
            str = str.substring( 0, indx );
        }
        return str;
    }

    private static String addDictExtn( String str ) 
    {
        if ( ! str.endsWith( XWConstants.DICT_EXTN ) ) {
            str += XWConstants.DICT_EXTN;
        }
        return str;
    }

    private static String[] getAssets( Context context )
    {
        try {
            AssetManager am = context.getAssets();
            return am.list("");
        } catch( java.io.IOException ioe ) {
            DbgUtils.loge( ioe );
            return new String[0];
        }
    }
    
    public static boolean haveWriteableSD()
    {
        String state = Environment.getExternalStorageState();

        return state.equals( Environment.MEDIA_MOUNTED );
        // want this later? Environment.MEDIA_MOUNTED_READ_ONLY
    }

    private static String figureMD5Sum( Context context, DictAndLoc dandl )
    {
        byte[] digest = null;
        String result = null;
        String name = dandl.name;
        File path = getDictFile( context, addDictExtn( name ), dandl.loc );
        try {
            InputStream fis = new FileInputStream( path );
            byte[] buffer = new byte[1024];
            MessageDigest md = MessageDigest.getInstance("MD5");
            for ( ; ; ) {
                int nRead = fis.read( buffer );
                if ( 0 > nRead ) {
                    break;
                }
                md.update( buffer, 0, nRead );
            }
            fis.close();

            digest = md.digest();
        } catch ( java.io.FileNotFoundException fnfe ) {
            DbgUtils.loge( fnfe );
        } catch( java.security.NoSuchAlgorithmException nsae ) {
            DbgUtils.loge( nsae );
        } catch( java.io.IOException ioe ) {
            DbgUtils.loge( ioe );
        }

        return Utils.digestToString( digest );
    } // figureMD5Sum

    public static String getMD5SumFor( Context context, DictAndLoc dandl )
    {
        String sum = null; // DBUtils.getDictMD5Sum( context, dandl.name, 
                           // dandl.loc.ordinal() );
        if ( null == sum ) {
            sum = figureMD5Sum( context, dandl );
            // DBUtils.setDictMD5Sum( context, dandl.name, 
            //                        dandl.loc.ordinal(), sum );
        }
        return sum;
    }

    private static File getSDDir( Context context )
    {
        File result = null;
        if ( haveWriteableSD() ) {
            File storage = Environment.getExternalStorageDirectory();
            if ( null != storage ) {
                String packdir = String.format( "Android/data/%s/files/",
                                                context.getPackageName() );
                result = new File( storage.getPath(), packdir );
                if ( !result.exists() ) {
                    result.mkdirs();
                    if ( !result.exists() ) {
                        DbgUtils.logf( "unable to create sd dir %s", packdir );
                        result = null;
                    }
                }
            }
        }
        return result;
    }

    private static File getSDPathFor( Context context, String name )
    {
        File result = null;
        File dir = getSDDir( context );
        if ( dir != null ) {
            result = new File( dir, name );
        }
        return result;
    }

    private static File getDownloadDir()
    {
        File result = null;
        if ( haveWriteableSD() ) {
            File storage = Environment.getExternalStorageDirectory();
            if ( null != storage ) {
                result = new File( storage.getPath(), "download/" );
                if ( !result.exists() ) {
                    result = null;
                }
            }
        }
        return result;
    }

    private static File getDownloadsPathFor( String name )
    {
        File result = null;
        File dir = getDownloadDir();
        if ( dir != null ) {
            result = new File( dir, name );
        }
        return result;
    }
}
