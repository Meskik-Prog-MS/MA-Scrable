/* -*-mode: C; compile-command: "../../scripts/ndkbuild.sh"; -*- */
/*
 * Copyright © 2009-2010 by Eric House (xwords@eehouse.org).  All
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

#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/mman.h>

#include "anddict.h"
#include "xptypes.h"
#include "dictnry.h"
#include "dictnryp.h"
#include "strutils.h"
#include "andutils.h"
#include "utilwrapper.h"

typedef struct _AndDictionaryCtxt {
    DictionaryCtxt super;
    JNIUtilCtxt* jniutil;
    JNIEnv *env;
    off_t bytesSize;
    jbyte* bytes;
    jbyteArray byteArray;
} AndDictionaryCtxt;

#define CHECK_PTR(p,c,e)                                                \
    if ( ((p)+(c)) >= (e) ) {                                           \
        XP_LOGF( "%s (line %d); out of bytes", __func__, __LINE__ );    \
        goto error;                                                     \
    }

static void splitFaces_via_java( JNIEnv* env, AndDictionaryCtxt* ctxt, 
                                 const XP_U8* ptr, 
                                 int nFaceBytes, int nFaces, XP_Bool isUTF8 );

void
dict_splitFaces( DictionaryCtxt* dict, const XP_U8* bytes,
                 XP_U16 nBytes, XP_U16 nFaces )
{
    AndDictionaryCtxt* ctxt = (AndDictionaryCtxt*)dict;
    splitFaces_via_java( ctxt->env, ctxt, bytes, nBytes, nFaces, 
                         dict->isUTF8 );
}

static XP_U32
n_ptr_tohl( XP_U8 const** inp )
{
    XP_U32 t;
    XP_MEMCPY( &t, *inp, sizeof(t) );

    *inp += sizeof(t);

    return XP_NTOHL(t);
} /* n_ptr_tohl */

static XP_U16
n_ptr_tohs( XP_U8 const ** inp )
{
    XP_U16 t;
    XP_MEMCPY( &t, *inp, sizeof(t) );

    *inp += sizeof(t);

    return XP_NTOHS(t);
} /* n_ptr_tohs */

static XP_U16
andCountSpecials( AndDictionaryCtxt* ctxt )
{
    XP_U16 result = 0;
    XP_U16 ii;

    for ( ii = 0; ii < ctxt->super.nFaces; ++ii ) {
        if ( IS_SPECIAL( ctxt->super.facePtrs[ii][0] ) ) {
            ++result;
        }
    }

    return result;
} /* andCountSpecials */

static XP_Bool
andMakeBitmap( AndDictionaryCtxt* ctxt, XP_U8 const** ptrp,
               const XP_U8 const* end, XP_Bitmap* result )
{
    XP_Bool success = XP_TRUE;
    XP_U8 const* ptr = *ptrp;
    CHECK_PTR( ptr, 1, end );
    XP_U8 nCols = *ptr++;
    jobject bitmap = NULL;
    if ( nCols > 0 ) {
        CHECK_PTR( ptr, 1, end );
        XP_U8 nRows = *ptr++;
        CHECK_PTR( ptr, ((nRows*nCols)+7) / 8, end );
#ifdef DROP_BITMAPS
        ptr += ((nRows*nCols)+7) / 8;
#else
        XP_U8 srcByte = 0;
        XP_U8 nBits;
        XP_U16 ii;

        jboolean* colors = (jboolean*)XP_CALLOC( ctxt->super.mpool, 
                                                 nCols * nRows * sizeof(*colors) );
        jboolean* next = colors;

        nBits = nRows * nCols;
        for ( ii = 0; ii < nBits; ++ii ) {
            XP_U8 srcBitIndex = ii % 8;
            XP_U8 srcMask;

            if ( srcBitIndex == 0 ) {
                srcByte = *ptr++;
            }

            srcMask = 1 << (7 - srcBitIndex);
            XP_ASSERT( next < (colors + (nRows * nCols)) );
            *next++ = ((srcByte & srcMask) == 0) ? JNI_FALSE : JNI_TRUE;
        }

        JNIEnv* env = ctxt->env;
        bitmap = and_util_makeJBitmap( ctxt->jniutil, nCols, nRows, colors );
        (void)(*env)->NewGlobalRef( env, bitmap );
        (*env)->DeleteLocalRef( env, bitmap );
        XP_FREE( ctxt->super.mpool, colors );
#endif
    }
    goto done;
 error:
    success = XP_FALSE;
 done:
    *ptrp = ptr;
    *result = bitmap;
    return success;
} /* andMakeBitmap */

static XP_Bool
andLoadSpecialData( AndDictionaryCtxt* ctxt, XP_U8 const** ptrp,
                    const XP_U8 const* end )
{
    XP_Bool success = XP_TRUE;
    XP_U16 nSpecials = andCountSpecials( ctxt );
    XP_U8 const* ptr = *ptrp;
    Tile ii;
    XP_UCHAR** texts;
    SpecialBitmaps* bitmaps;

    texts = (XP_UCHAR**)XP_MALLOC( ctxt->super.mpool, 
                                   nSpecials * sizeof(*texts) );
    bitmaps = (SpecialBitmaps*)
        XP_CALLOC( ctxt->super.mpool, nSpecials * sizeof(*bitmaps) );

    for ( ii = 0; ii < ctxt->super.nFaces; ++ii ) {
	
        const XP_UCHAR* facep = ctxt->super.facePtrs[(short)ii];
        if ( IS_SPECIAL(*facep) ) {
            /* get the string */
            CHECK_PTR( ptr, 1, end );
            XP_U8 txtlen = *ptr++;
            CHECK_PTR( ptr, txtlen, end );
            XP_UCHAR* text = (XP_UCHAR*)XP_MALLOC(ctxt->super.mpool, txtlen+1);
            texts[(int)*facep] = text;
            XP_MEMCPY( text, ptr, txtlen );
            ptr += txtlen;
            text[txtlen] = '\0';
            XP_ASSERT( *facep < nSpecials ); /* firing */

            if ( !andMakeBitmap( ctxt, &ptr, end, 
                                 &bitmaps[(int)*facep].largeBM ) ) {
                goto error;
            }
            if ( !andMakeBitmap( ctxt, &ptr, end, 
                                 &bitmaps[(int)*facep].smallBM ) ) {
                goto error;
            }
        }
    }

    goto done;
 error:
    success = XP_FALSE;
 done:
    ctxt->super.chars = texts;
    ctxt->super.bitmaps = bitmaps;

    *ptrp = ptr;
    return success;
} /* andLoadSpecialData */

/** Android doesn't include iconv for C code to use, so we'll have java do it.
 * Cons up a string with all the tile faces (skipping the specials to make
 * things easier) and have java return an array of strings.  Then load one at
 * a time into the expected null-separated format.
 */
static void
splitFaces_via_java( JNIEnv* env, AndDictionaryCtxt* ctxt, const XP_U8* ptr, 
                     int nFaceBytes, int nFaces, XP_Bool isUTF8 )
{
    XP_UCHAR facesBuf[nFaces*4]; /* seems a reasonable upper bound... */
    int indx = 0;
    int offsets[nFaces];
    int nBytes;
    int ii;

    jobject jstrarr = and_util_splitFaces( ctxt->jniutil, ptr, nFaceBytes,
                                           isUTF8 );
    XP_ASSERT( (*env)->GetArrayLength( env, jstrarr ) == nFaces );

    for ( ii = 0; ii < nFaces; ++ii ) {
        jobject jstr = (*env)->GetObjectArrayElement( env, jstrarr, ii );
        offsets[ii] = indx;
        nBytes = (*env)->GetStringUTFLength( env, jstr );

        const char* bytes = (*env)->GetStringUTFChars( env, jstr, NULL );
        char* end;
        long numval = strtol( bytes, &end, 10 );
        if ( end > bytes ) {
            XP_ASSERT( numval < 32 );
            nBytes = 1;
            facesBuf[indx] = (XP_UCHAR)numval;
        } else {
            XP_MEMCPY( &facesBuf[indx], bytes, nBytes );
        }
        (*env)->ReleaseStringUTFChars( env, jstr, bytes );
        (*env)->DeleteLocalRef( env, jstr );

        indx += nBytes;
        facesBuf[indx++] = '\0';
        XP_ASSERT( indx < VSIZE(facesBuf) );
    }
    (*env)->DeleteLocalRef( env, jstrarr );

    XP_UCHAR* faces = (XP_UCHAR*)XP_CALLOC( ctxt->super.mpool, indx );
    const XP_UCHAR** ptrs = (const XP_UCHAR**)
        XP_CALLOC( ctxt->super.mpool, nFaces * sizeof(ptrs[0]));

    XP_MEMCPY( faces, facesBuf, indx );
    for ( ii = 0; ii < nFaces; ++ii ) {
        ptrs[ii] = &faces[offsets[ii]];
    }

    XP_ASSERT( !ctxt->super.faces );
    ctxt->super.faces = faces;
    XP_ASSERT( !ctxt->super.facePtrs );
    ctxt->super.facePtrs = ptrs;
} /* splitFaces_via_java */

static XP_Bool
parseDict( AndDictionaryCtxt* ctxt, XP_U8 const* ptr, XP_U32 dictLength,
           XP_U32* numEdges )
{
    XP_Bool success = XP_TRUE;
    XP_ASSERT( !!ptr );
    const XP_U8 const* end = ptr + dictLength;
    XP_U32 offset;
    XP_U16 nFaces, numFaceBytes = 0;
    XP_U16 i;
    XP_U16 flags;
    void* mappedBase = (void*)ptr;
    XP_U8 nodeSize;
    XP_Bool isUTF8 = XP_FALSE;

    CHECK_PTR( ptr, sizeof(flags), end );
    flags = n_ptr_tohs( &ptr );
    if ( 0 != (DICT_HEADER_MASK & flags) ) {
        XP_U16 headerLen;
        flags &= ~DICT_HEADER_MASK;
        CHECK_PTR( ptr, sizeof(headerLen), end );
        headerLen = n_ptr_tohs( &ptr );
        if ( 4 <= headerLen ) { /* have word count? */
            CHECK_PTR( ptr, sizeof(ctxt->super.nWords), end );
            ctxt->super.nWords = n_ptr_tohl( &ptr );
            headerLen -= 4; /* don't skip it */
        }
        CHECK_PTR( ptr, headerLen, end );
        ptr += headerLen;
    }

    if ( flags == 0x0002 ) {
        nodeSize = 3;
    } else if ( flags == 0x0003 ) {
        nodeSize = 4;
    } else if ( flags == 0x0004 ) {
        isUTF8 = XP_TRUE;
        nodeSize = 3;
    } else if ( flags == 0x0005 ) {
        isUTF8 = XP_TRUE;
        nodeSize = 4;
    } else {
        goto error;
    }

    if ( isUTF8 ) {
        CHECK_PTR( ptr, 1, end );
        numFaceBytes = (XP_U16)(*ptr++);
    }
    CHECK_PTR( ptr, 1, end );
    nFaces = (XP_U16)(*ptr++);
    if ( nFaces > 64 ) {
        goto error;
    }

    ctxt->super.nodeSize = nodeSize;

    if ( !isUTF8 ) {
        numFaceBytes = nFaces * 2;
    }

    ctxt->super.nFaces = (XP_U8)nFaces;
    ctxt->super.isUTF8 = isUTF8;

    if ( isUTF8 ) {
        CHECK_PTR( ptr, numFaceBytes, end );
        splitFaces_via_java( ctxt->env, ctxt, ptr, numFaceBytes, nFaces,
                             XP_TRUE );
        ptr += numFaceBytes;
    } else {
        XP_U8 tmp[nFaces*4]; /* should be enough... */
        XP_U16 nBytes = 0;
        XP_U16 ii;
        /* Need to translate from iso-8859-n to utf8 */
        CHECK_PTR( ptr, 2 * nFaces, end );
        for ( ii = 0; ii < nFaces; ++ii ) {
            XP_UCHAR ch = ptr[1];

            ptr += 2;

            tmp[nBytes] = ch;
            nBytes += 1;
        }
        XP_ASSERT( nFaces == nBytes );
        splitFaces_via_java( ctxt->env, ctxt, tmp, nBytes, nFaces, 
                             XP_FALSE );
    }

    ctxt->super.is_4_byte = (ctxt->super.nodeSize == 4);

    ctxt->super.countsAndValues = 
        (XP_U8*)XP_MALLOC(ctxt->super.mpool, nFaces*2);

    CHECK_PTR( ptr, 2, end );
    ctxt->super.langCode = ptr[0] & 0x7F;
    ptr += 2;		/* skip xloc header */
    CHECK_PTR( ptr, 2 * nFaces, end );
    for ( i = 0; i < nFaces*2; i += 2 ) {
        ctxt->super.countsAndValues[i] = *ptr++;
        ctxt->super.countsAndValues[i+1] = *ptr++;
    }

    if ( !andLoadSpecialData( ctxt, &ptr, end ) ) {
        goto error;
    }

    dictLength -= ptr - (XP_U8*)mappedBase;
    if ( dictLength >= sizeof(offset) ) {
        CHECK_PTR( ptr, sizeof(offset), end );
        offset = n_ptr_tohl( &ptr );
        dictLength -= sizeof(offset);
#ifdef NODE_CAN_4
        XP_ASSERT( dictLength % ctxt->super.nodeSize == 0 );
        *numEdges = dictLength / ctxt->super.nodeSize;
#else
        XP_ASSERT( dictLength % 3 == 0 );
        *numEdges = dictLength / 3;
#endif
#ifdef DEBUG
        ctxt->super.numEdges = *numEdges;
#endif
    } else {
        offset = 0;
    }

    if ( dictLength > 0 ) {
        ctxt->super.base = (array_edge*)ptr;
#ifdef NODE_CAN_4
        ctxt->super.topEdge = ctxt->super.base 
            + (offset * ctxt->super.nodeSize);
#else
        ctxt->super.topEdge = ctxt->super.base + (offset * 3);
#endif
    } else {
        ctxt->super.topEdge = (array_edge*)NULL;
        ctxt->super.base = (array_edge*)NULL;
    }

    setBlankTile( &ctxt->super );

    goto done;
 error:
    success = XP_FALSE;
 done:
    return success;
} /* parseDict */

static void
and_dictionary_destroy( DictionaryCtxt* dict )
{
    AndDictionaryCtxt* ctxt = (AndDictionaryCtxt*)dict;
    XP_U16 nSpecials = andCountSpecials( ctxt );
    XP_U16 ii;
    JNIEnv* env = ctxt->env;

    if ( !!ctxt->super.chars ) {
        for ( ii = 0; ii < nSpecials; ++ii ) {
            XP_UCHAR* text = ctxt->super.chars[ii];
            if ( !!text ) {
                XP_FREE( ctxt->super.mpool, text );
            }
        }
        XP_FREE( ctxt->super.mpool, ctxt->super.chars );
    }
    if ( !!ctxt->super.bitmaps ) {
        for ( ii = 0; ii < nSpecials; ++ii ) {
            jobject bitmap = ctxt->super.bitmaps[ii].largeBM;
            if ( !!bitmap ) {
                (*env)->DeleteGlobalRef( env, bitmap );
            }
        }
        XP_FREE( ctxt->super.mpool, ctxt->super.bitmaps );
    }

    XP_FREE( ctxt->super.mpool, ctxt->super.faces );
    XP_FREE( ctxt->super.mpool, ctxt->super.facePtrs );
    XP_FREE( ctxt->super.mpool, ctxt->super.countsAndValues );
    XP_FREEP( ctxt->super.mpool, &ctxt->super.name );
    XP_FREEP( ctxt->super.mpool, &ctxt->super.langName );

    if ( NULL == ctxt->byteArray ) { /* mmap case */
#ifdef DEBUG
        int err = 
#endif
            munmap( ctxt->bytes, ctxt->bytesSize );
        XP_ASSERT( 0 == err );
    } else {
        (*env)->ReleaseByteArrayElements( env, ctxt->byteArray, ctxt->bytes, 0 );
        (*env)->DeleteGlobalRef( env, ctxt->byteArray );
    }
    XP_FREE( ctxt->super.mpool, ctxt );
}

jobject
and_dictionary_getChars( JNIEnv* env, DictionaryCtxt* dict )
{
    XP_ASSERT( env == ((AndDictionaryCtxt*)dict)->env );

    /* This is cheating: specials will be rep'd as 1,2, etc.  But as long as
       java code wants to ignore them anyway that's ok.  Otherwise need to
       use dict_tilesToString() */
    XP_U16 nFaces = dict_numTileFaces( dict );
    jobject jstrs = makeStringArray( env, nFaces, dict->facePtrs );
    return jstrs;
}

DictionaryCtxt* 
and_dictionary_make_empty( MPFORMAL JNIEnv* env, JNIUtilCtxt* jniutil )
{
    AndDictionaryCtxt* anddict
        = (AndDictionaryCtxt*)XP_CALLOC( mpool, sizeof( *anddict ) );
    anddict->env = env;
    anddict->jniutil = jniutil;
    dict_super_init( (DictionaryCtxt*)anddict );
    MPASSIGN( anddict->super.mpool, mpool );
    return (DictionaryCtxt*)anddict;
}

void
makeDicts( MPFORMAL JNIEnv *env, JNIUtilCtxt* jniutil, 
           DictionaryCtxt** dictp, PlayerDicts* dicts,
           jobjectArray jnames, jobjectArray jdicts, jobjectArray jpaths,
           jstring jlang )
{
    int ii;
    jsize len = (*env)->GetArrayLength( env, jdicts );
    XP_ASSERT( len == (*env)->GetArrayLength( env, jnames ) );

    for ( ii = 0; ii <= VSIZE(dicts->dicts); ++ii ) {
        DictionaryCtxt* dict = NULL;
        if ( ii < len ) {
            jobject jdict = (*env)->GetObjectArrayElement( env, jdicts, ii );
            jstring jpath = jpaths == NULL ? 
                    NULL : (*env)->GetObjectArrayElement( env, jpaths, ii );
            if ( NULL != jdict || NULL != jpath ) { 
                jstring jname = (*env)->GetObjectArrayElement( env, jnames, ii );
                dict = makeDict( MPPARM(mpool) env, jniutil, jname, jdict, 
                                 jpath, jlang, false );
                XP_ASSERT( !!dict );
                (*env)->DeleteLocalRef( env, jdict );
                (*env)->DeleteLocalRef( env, jname );
            }
            if ( NULL != jpath) {
                (*env)->DeleteLocalRef( env, jpath );
            }
        }
        if ( 0 == ii ) {
            *dictp = dict;
        } else {
            XP_ASSERT( ii-1 < VSIZE( dicts->dicts ) );
            dicts->dicts[ii-1] = dict;
        }
    }
}

DictionaryCtxt* 
makeDict( MPFORMAL JNIEnv *env, JNIUtilCtxt* jniutil, jstring jname, 
          jbyteArray jbytes, jstring jpath, jstring jlangname, jboolean check )
{
    AndDictionaryCtxt* anddict = (AndDictionaryCtxt*)
        and_dictionary_make_empty( MPPARM(mpool) env, jniutil );

    jsize len = 0;

    if ( NULL == jpath ) {
        len = (*env)->GetArrayLength( env, jbytes );
        anddict->byteArray = (*env)->NewGlobalRef( env, jbytes );
        anddict->bytes =
            (*env)->GetByteArrayElements( env, anddict->byteArray, NULL );
    } else {
        XP_ASSERT( NULL == anddict->byteArray );
        const char* path = (*env)->GetStringUTFChars( env, jpath, NULL );

        struct stat statbuf;
        if ( 0 == stat( path, &statbuf ) ) {
            int fd = open( path,  O_RDONLY );
            if ( fd >= 0 ) {
                anddict->bytes = mmap( NULL, statbuf.st_size,
                                       PROT_READ, MAP_PRIVATE,
                                       fd, 0 );
                close( fd );

                anddict->bytesSize = statbuf.st_size;
                len = statbuf.st_size;
                XP_ASSERT( MAP_FAILED != anddict->bytes );
            }
        }
        (*env)->ReleaseStringUTFChars( env, jpath, path );
    }

    anddict->super.destructor = and_dictionary_destroy;

    /* copy the name */
    anddict->super.name = getStringCopy( MPPARM(mpool) env, jname );
    anddict->super.langName = getStringCopy( MPPARM(mpool) env, jlangname );

    XP_U32 numEdges;
    XP_Bool parses = parseDict( anddict, (XP_U8*)anddict->bytes, 
                                len, &numEdges );
    if ( !parses || (check && !checkSanity( &anddict->super, numEdges ) ) ) {
        and_dictionary_destroy( (DictionaryCtxt*)anddict );
        anddict = NULL;
    }
    
    return (DictionaryCtxt*)anddict;
}

void
destroyDicts( PlayerDicts* dicts )
{
    int ii;
    DictionaryCtxt** ctxts;

    for ( ctxts = dicts->dicts, ii = 0; 
          ii < VSIZE(dicts->dicts); 
          ++ii, ++ctxts ) {
        if ( NULL != *ctxts ) {
            dict_destroy( *ctxts );
        }
    }
}
