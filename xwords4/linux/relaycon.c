/* -*- compile-command: "make MEMDEBUG=TRUE -j3"; -*- */
/* 
 * Copyright 2013 by Eric House (xwords@eehouse.org).  All rights reserved.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

#include <netdb.h>
#include <errno.h>
#include <stdbool.h>
#include <curl/curl.h>
#include <json-c/json.h>


#include "relaycon.h"
#include "linuxmain.h"
#include "comtypes.h"
#include "gamesdb.h"

#define MAX_MOVE_CHECK_SECS ((XP_U16)(60 * 60 * 24))
#define RELAY_API_PROTO "http"

typedef struct _RelayConStorage {
    pthread_t mainThread;
    guint moveCheckerID;
    XP_U16 nextMoveCheckSecs;
    pthread_cond_t relayCondVar;
    pthread_mutex_t relayMutex;
    GSList* relayTaskList;

    int socket;
    RelayConnProcs procs;
    void* procsClosure;
    struct sockaddr_in saddr;
    uint32_t nextID;
    XWPDevProto proto;
    LaunchParams* params;
    XP_UCHAR host[64];
} RelayConStorage;

typedef struct _MsgHeader {
    XWRelayReg cmd;
    uint32_t packetID;
} MsgHeader;

static RelayConStorage* getStorage( LaunchParams* params );
static XP_Bool onMainThread( RelayConStorage* storage );
static XP_U32 hostNameToIP( const XP_UCHAR* name );
static gboolean relaycon_receive( GIOChannel *source, GIOCondition condition, 
                                  gpointer data );
static void schedule_next_check( RelayConStorage* storage );
static void reset_schedule_check_interval( RelayConStorage* storage );
static void checkForMovesOnce( RelayConStorage* storage );

static ssize_t sendIt( RelayConStorage* storage, const XP_U8* msgbuf, XP_U16 len );
static size_t addVLIStr( XP_U8* buf, size_t len, const XP_UCHAR* str );
static void getNetString( const XP_U8** ptr, XP_U16 len, XP_UCHAR* buf );
static XP_U16 getNetShort( const XP_U8** ptr );
static XP_U32 getNetLong( const XP_U8** ptr );
static int writeHeader( RelayConStorage* storage, XP_U8* dest, XWRelayReg cmd );
static bool readHeader( const XP_U8** buf, MsgHeader* header );
static size_t writeDevID( XP_U8* buf, size_t len, const XP_UCHAR* str );
static size_t writeShort( XP_U8* buf, size_t len, XP_U16 shrt );
static size_t writeLong( XP_U8* buf, size_t len, XP_U32 lng );
static size_t writeBytes( XP_U8* buf, size_t len, const XP_U8* bytes, 
                          size_t nBytes );
static size_t writeVLI( XP_U8* out, uint32_t nn );
static size_t un2vli( int nn, uint8_t* buf );
static bool vli2un( const uint8_t** inp, uint32_t* outp );

static void* relayThread( void* arg );

typedef struct _WriteState {
    gchar* ptr;
    size_t curSize;
} WriteState;

typedef enum { POST, QUERY, } TaskType;

typedef struct _RelayTask {
    TaskType typ;
    RelayConStorage* storage;
    WriteState ws;
    union {
        struct {
            XP_U8* msgbuf;
            XP_U16 len;
        } post;
        struct {
            GHashTable* map;
        } query;
    } u;
} RelayTask;

static RelayTask* makeRelayTask(RelayConStorage* storage, TaskType typ);
static void freeRelayTask(RelayTask* task);
static void handlePost( RelayTask* task );
static void handleQuery( RelayTask* task );


static size_t
write_callback(void *contents, size_t size, size_t nmemb, void* data)
{
    WriteState* ws = (WriteState*)data;

    if ( !ws->ptr ) {
        ws->ptr = g_malloc0(1);
        ws->curSize = 1L;
    }

    XP_LOGF( "%s(size=%ld, nmemb=%ld)", __func__, size, nmemb );
    size_t oldLen = ws->curSize;
    const size_t newLength = size * nmemb;
    XP_ASSERT( (oldLen + newLength) > 0 );
    ws->ptr = g_realloc( ws->ptr, oldLen + newLength );
    memcpy( ws->ptr + oldLen - 1, contents, newLength );
    ws->ptr[oldLen + newLength - 1] = '\0';
    // XP_LOGF( "%s() => %ld: (passed: \"%s\")", __func__, result, *strp );
    return newLength;
}

static void
addJsonParams( CURL* curl, const char* name, json_object* param )
{
    const char* asStr = json_object_to_json_string( param );
    XP_LOGF( "%s: adding param: %s", __func__, asStr );
    
    char* curl_params = curl_easy_escape( curl, asStr, strlen(asStr) );
    gchar* buf = g_strdup_printf( "%s=%s", name, curl_params );
    XP_LOGF( "%s: added param: %s", __func__, buf );
    curl_free( curl_params );

    curl_easy_setopt( curl, CURLOPT_POSTFIELDS, buf );
    curl_easy_setopt( curl, CURLOPT_POSTFIELDSIZE, (long)strlen(buf) );

    // Can't free the buf!! Well, maybe after the send...
    // g_free( buf );
    json_object_put( param );
}

void
checkForMsgsNow( LaunchParams* params )
{
    RelayConStorage* storage = getStorage( params );
    XP_ASSERT( onMainThread(storage) );
    checkForMovesOnce( storage );
}

void
relaycon_init( LaunchParams* params, const RelayConnProcs* procs, 
               void* procsClosure, const char* host, int port )
{
    XP_ASSERT( !params->relayConStorage );
    RelayConStorage* storage = getStorage( params );
    XP_MEMCPY( &storage->procs, procs, sizeof(storage->procs) );
    storage->procsClosure = procsClosure;

    if ( params->useHTTP ) {
        storage->mainThread = pthread_self();
        pthread_mutex_init ( &storage->relayMutex, NULL );
        pthread_cond_init( &storage->relayCondVar, NULL );
        pthread_t thread;
        (void)pthread_create( &thread, NULL, relayThread, storage );
        pthread_detach( thread );

        XP_ASSERT( XP_STRLEN(host) < VSIZE(storage->host) );
        XP_MEMCPY( storage->host, host, XP_STRLEN(host) + 1 );
    } else {
        storage->socket = socket( AF_INET, SOCK_DGRAM, IPPROTO_UDP );
        (*procs->socketAdded)( storage, storage->socket, relaycon_receive );

        XP_MEMSET( &storage->saddr, 0, sizeof(storage->saddr) );
        storage->saddr.sin_family = PF_INET;
        storage->saddr.sin_addr.s_addr = htonl( hostNameToIP(host) );
        storage->saddr.sin_port = htons(port);

    }
    storage->params = params;

    storage->proto = XWPDEV_PROTO_VERSION_1;

    if ( params->useHTTP ) {
        schedule_next_check( storage );
    }
}

/* Send existing relay-assigned rDevID to relay, or empty string if we have
   none.  Send local devID and type, ID_TYPE_NONE, if we aren't providing an
   update.  It's an error for neither to be provided. */
void
relaycon_reg( LaunchParams* params, const XP_UCHAR* rDevID, 
              DevIDType typ, const XP_UCHAR* devID )
{
    XP_LOGF( "%s(typ=%d)", __func__, typ );
    XP_U8 tmpbuf[256];
    int indx = 0;

    RelayConStorage* storage = getStorage( params );
    indx += writeHeader( storage, tmpbuf, XWPDEV_REG );
    indx += addVLIStr( &tmpbuf[indx], sizeof(tmpbuf) - indx, rDevID );

    assert( ID_TYPE_RELAY != typ );
    tmpbuf[indx++] = typ;
    if ( ID_TYPE_NONE != typ ) {
        indx += writeDevID( &tmpbuf[indx], sizeof(tmpbuf) - indx, devID );
    }
    indx += writeShort( &tmpbuf[indx], sizeof(tmpbuf) - indx, 
                        INITIAL_CLIENT_VERS );
    indx += addVLIStr( &tmpbuf[indx], sizeof(tmpbuf) - indx, SVN_REV );
    indx += addVLIStr( &tmpbuf[indx], sizeof(tmpbuf) - indx, "linux box" );
    indx += addVLIStr( &tmpbuf[indx], sizeof(tmpbuf) - indx, "linux version" );

    sendIt( storage, tmpbuf, indx );
}

void
relaycon_invite( LaunchParams* params, XP_U32 destDevID, 
                 const XP_UCHAR* relayID, NetLaunchInfo* invit )
{
    XP_U8 tmpbuf[256];
    int indx = 0;

    RelayConStorage* storage = getStorage( params );
    indx += writeHeader( storage, tmpbuf, XWPDEV_INVITE );
    XP_U32 me = linux_getDevIDRelay( params );
    indx += writeLong( &tmpbuf[indx], sizeof(tmpbuf) - indx, me );

    /* write relayID <connname>/<hid>, or if we have an actual devID write a
       null byte plus it. */
    if ( 0 == destDevID ) {
        XP_ASSERT( '\0' != relayID[0] );
        indx += writeBytes( &tmpbuf[indx], sizeof(tmpbuf) - indx, 
                            (XP_U8*)relayID, 1 + XP_STRLEN( relayID ) );
    } else {
        tmpbuf[indx++] = '\0';  /* null byte: zero-len str */
        indx += writeLong( &tmpbuf[indx], sizeof(tmpbuf) - indx, destDevID );
    }

    XWStreamCtxt* stream = mem_stream_make( MPPARM(params->mpool) 
                                            params->vtMgr, params, 
                                            CHANNEL_NONE, NULL );
    nli_saveToStream( invit, stream );
    XP_U16 len = stream_getSize( stream );
    indx += writeShort( &tmpbuf[indx], sizeof(tmpbuf) - indx, len );
    XP_ASSERT( indx + len < sizeof(tmpbuf) );
    const XP_U8* ptr = stream_getPtr( stream );
    indx += writeBytes( &tmpbuf[indx], sizeof(tmpbuf) - indx, ptr, len );
    stream_destroy( stream );

    sendIt( storage, tmpbuf, indx );
    LOG_RETURN_VOID();
}

XP_S16
relaycon_send( LaunchParams* params, const XP_U8* buf, XP_U16 buflen, 
               XP_U32 gameToken, const CommsAddrRec* XP_UNUSED(addrRec) )
{
    XP_ASSERT( 0 != gameToken );
    ssize_t nSent = -1;
    RelayConStorage* storage = getStorage( params );

    XP_U8 tmpbuf[1 + 4 + 1 + sizeof(gameToken) + buflen];
    int indx = 0;
    indx += writeHeader( storage, tmpbuf, XWPDEV_MSG );
    indx += writeLong( &tmpbuf[indx], sizeof(tmpbuf) - indx, gameToken );
    indx += writeBytes( &tmpbuf[indx], sizeof(tmpbuf) - indx, buf, buflen );
    nSent = sendIt( storage, tmpbuf, indx );
    if ( nSent > buflen ) {
        nSent = buflen;
    }
    LOG_RETURNF( "%zd", nSent );
    return nSent;
}

XP_S16 
relaycon_sendnoconn( LaunchParams* params, const XP_U8* buf, XP_U16 buflen, 
                     const XP_UCHAR* relayID, XP_U32 gameToken )
{
    XP_LOGF( "%s(relayID=%s)", __func__, relayID );
    XP_ASSERT( 0 != gameToken );
    XP_U16 indx = 0;
    ssize_t nSent = -1;
    RelayConStorage* storage = getStorage( params );

    XP_U16 idLen = XP_STRLEN( relayID );
    XP_U8 tmpbuf[1 + 4 + 1 +
                 1 + idLen +
                 sizeof(gameToken) + buflen];
    indx += writeHeader( storage, tmpbuf, XWPDEV_MSGNOCONN );
    indx += writeLong( &tmpbuf[indx], sizeof(tmpbuf) - indx, gameToken );
    indx += writeBytes( &tmpbuf[indx], sizeof(tmpbuf) - indx, 
                        (const XP_U8*)relayID, idLen );
    tmpbuf[indx++] = '\n';
    indx += writeBytes( &tmpbuf[indx], sizeof(tmpbuf) - indx, buf, buflen );
    nSent = sendIt( storage, tmpbuf, indx );
    if ( nSent > buflen ) {
        nSent = buflen;
    }
    LOG_RETURNF( "%zd", nSent );
    return nSent;
}

void
relaycon_requestMsgs( LaunchParams* params, const XP_UCHAR* devID )
{
    XP_LOGF( "%s(devID=%s)", __func__, devID );
    RelayConStorage* storage = getStorage( params );

    XP_U8 tmpbuf[128];
    int indx = 0;
    indx += writeHeader( storage, tmpbuf, XWPDEV_RQSTMSGS );
    indx += addVLIStr( &tmpbuf[indx], sizeof(tmpbuf) - indx, devID );

    sendIt( storage, tmpbuf, indx );
}

void
relaycon_deleted( LaunchParams* params, const XP_UCHAR* devID, 
                  XP_U32 gameToken )
{
    LOG_FUNC();
    RelayConStorage* storage = getStorage( params );
    XP_U8 tmpbuf[128];
    int indx = 0;
    indx += writeHeader( storage, tmpbuf, XWPDEV_DELGAME );
    indx += writeDevID( &tmpbuf[indx], sizeof(tmpbuf) - indx, devID );
    indx += writeLong( &tmpbuf[indx], sizeof(tmpbuf) - indx, gameToken );

    sendIt( storage, tmpbuf, indx );
}

static XP_Bool
onMainThread( RelayConStorage* storage )
{
    return storage->mainThread = pthread_self();
}

static void*
relayThread( void* arg )
{
    RelayConStorage* storage = (RelayConStorage*)arg;
    for ( ; ; ) {
        pthread_mutex_lock( &storage->relayMutex );
        while ( !storage->relayTaskList ) {
            pthread_cond_wait( &storage->relayCondVar, &storage->relayMutex );
        }

        RelayTask* task = storage->relayTaskList->data;
        storage->relayTaskList = storage->relayTaskList->next;
        pthread_mutex_unlock( &storage->relayMutex );

        switch ( task->typ ) {
        case POST:
            handlePost( task );
            break;
        case QUERY:
            handleQuery( task );
            break;
        default:
            XP_ASSERT(0);
        }
    }
    return NULL;
}

static void
addTask( RelayConStorage* storage, RelayTask* task )
{
    pthread_mutex_lock( &storage->relayMutex );
    storage->relayTaskList = g_slist_append( storage->relayTaskList, task );
    pthread_cond_signal( &storage->relayCondVar );
    pthread_mutex_unlock( &storage->relayMutex );
}

static RelayTask*
makeRelayTask( RelayConStorage* storage, TaskType typ )
{
    RelayTask* task = (RelayTask*)g_malloc0(sizeof(*task));
    task->typ = typ;
    task->storage = storage;
    return task;
}

static void
freeRelayTask( RelayTask* task )
{
    g_free( task->ws.ptr );
    g_free( task );
}

static void
sendAckIf( RelayConStorage* storage, const MsgHeader* header )
{
    if ( header->cmd != XWPDEV_ACK ) {
        XP_U8 tmpbuf[16];
        int indx = writeHeader( storage, tmpbuf, XWPDEV_ACK );
        indx += writeVLI( &tmpbuf[indx], header->packetID );
        sendIt( storage, tmpbuf, indx );
    }
}

static gboolean
process( RelayConStorage* storage, XP_U8* buf, ssize_t nRead )
{
    if ( 0 <= nRead ) {
        const XP_U8* ptr = buf;
        const XP_U8* end = buf + nRead;
        MsgHeader header;
        if ( readHeader( &ptr, &header ) ) {
            sendAckIf( storage, &header );

            switch( header.cmd ) {
            case XWPDEV_REGRSP: {
                uint32_t len;
                if ( !vli2un( &ptr, &len ) ) {
                    assert(0);
                }
                XP_UCHAR devID[len+1];
                getNetString( &ptr, len, devID );
                XP_U16 maxInterval = getNetShort( &ptr );
                XP_LOGF( "%s: maxInterval=%d", __func__, maxInterval );
                (*storage->procs.devIDReceived)( storage->procsClosure, devID,
                                                 maxInterval );
            }
                break;
            case XWPDEV_MSG: {
                CommsAddrRec addr = {0};
                addr_addType( &addr, COMMS_CONN_RELAY );
                (*storage->procs.msgReceived)( storage->procsClosure, &addr,
                                               ptr, end - ptr );
            }
                break;
            case XWPDEV_BADREG:
                (*storage->procs.devIDReceived)( storage->procsClosure, NULL, 0 );
                break;
            case XWPDEV_HAVEMSGS: {
                (*storage->procs.msgNoticeReceived)( storage->procsClosure );
                break;
            }
            case XWPDEV_UNAVAIL: {
#ifdef DEBUG
                XP_U32 unavail = getNetLong( &ptr );
                XP_LOGF( "%s: unavail = %u", __func__, unavail );
#endif
                uint32_t len;
                if ( !vli2un( &ptr, &len ) ) {
                    assert(0);
                }
                XP_UCHAR buf[len+1];
                getNetString( &ptr, len, buf );

                (*storage->procs.msgErrorMsg)( storage->procsClosure, buf );
                break;
            }
            case XWPDEV_ACK: {
                uint32_t packetID;
                if ( !vli2un( &ptr, &packetID ) ) {
                    assert( 0 );
                }
                XP_USE( packetID );
                XP_LOGF( "got ack for packetID %d", packetID );
                break;
            }
            case XWPDEV_ALERT: {
                uint32_t len;
                if ( !vli2un( &ptr, &len ) ) {
                    assert(0);
                }
                XP_UCHAR buf[len + 1];
                getNetString( &ptr, len, buf );
                XP_LOGF( "%s: got message: %s", __func__, buf );
                break;
            }
            case XWPDEV_GOTINVITE: {
                XP_LOGF( "%s(): got XWPDEV_GOTINVITE", __func__ );
#ifdef DEBUG
                XP_U32 sender = 
#endif
                    getNetLong( &ptr );
                XP_U16 len = getNetShort( &ptr );
                XWStreamCtxt* stream = mem_stream_make( MPPARM(storage->params->mpool) 
                                                        storage->params->vtMgr, storage,
                                                        CHANNEL_NONE, NULL );
                stream_putBytes( stream, ptr, len );
                NetLaunchInfo invit;
                XP_Bool success = nli_makeFromStream( &invit, stream );
                XP_LOGF( "sender: %d", sender );
                stream_destroy( stream );

                if ( success ) {
                    (*storage->procs.inviteReceived)( storage->procsClosure, 
                                                      &invit );
                }
            }
                break;

            default:
                XP_LOGF( "%s: Unexpected cmd %d", __func__, header.cmd );
                XP_ASSERT( 0 );
            }
        }
    } else {
        XP_LOGF( "%s: error reading udp socket: %d (%s)", __func__, 
                 errno, strerror(errno) );
    }
    return TRUE;
}

static gboolean
relaycon_receive( GIOChannel* source, GIOCondition XP_UNUSED_DBG(condition), gpointer data )
{
    XP_ASSERT( 0 != (G_IO_IN & condition) ); /* FIX ME */
    RelayConStorage* storage = (RelayConStorage*)data;
    XP_ASSERT( !storage->params->useHTTP );
    XP_U8 buf[512];
    struct sockaddr_in from;
    socklen_t fromlen = sizeof(from);

    int socket = g_io_channel_unix_get_fd( source );
    XP_LOGF( "%s: calling recvfrom on socket %d", __func__, socket );

    ssize_t nRead = recvfrom( socket, buf, sizeof(buf), 0, /* flags */
                              (struct sockaddr*)&from, &fromlen );

    gchar* b64 = g_base64_encode( (const guchar*)buf,
                                  ((0 <= nRead)? nRead : 0) );
    XP_LOGF( "%s: read %zd bytes ('%s')", __func__, nRead, b64 );
    g_free( b64 );
#ifdef COMMS_CHECKSUM
    gchar* sum = g_compute_checksum_for_data( G_CHECKSUM_MD5, buf, nRead );
    XP_LOGF( "%s: read %zd bytes ('%s')(sum=%s)", __func__, nRead, b64, sum );
    g_free( sum );
#endif
    return process( storage, buf, nRead );
}

void
relaycon_cleanup( LaunchParams* params )
{
    XP_FREEP( params->mpool, &params->relayConStorage );
}

static RelayConStorage* 
getStorage( LaunchParams* params )
{
    XP_ASSERT( params->useUdp );
    RelayConStorage* storage = (RelayConStorage*)params->relayConStorage;
    if ( NULL == storage ) {
        storage = XP_CALLOC( params->mpool, sizeof(*storage) );
        storage->socket = -1;
        params->relayConStorage = storage;
    }
    return storage;
}

static XP_U32
hostNameToIP( const XP_UCHAR* name )
{
    XP_U32 ip;
    struct hostent* host;
    XP_LOGF( "%s: looking up %s", __func__, name );
    host = gethostbyname( name );
    if ( NULL == host ) {
        XP_WARNF( "gethostbyname returned NULL\n" );
    } else {
        XP_MEMCPY( &ip, host->h_addr_list[0], sizeof(ip) );
        ip = ntohl(ip);
    }
    XP_LOGF( "%s found %x for %s", __func__, ip, name );
    return ip;
}

typedef struct _PostArgs {
    RelayConStorage* storage;
    WriteState ws;
    XP_U8* msgbuf;
    XP_U16 len;
} PostArgs;

static gboolean
onGotPostData(gpointer user_data)
{
    RelayTask* task = (RelayTask*)user_data;
    /* Now pull any data from the reply */
    // got "{"status": "ok", "dataLen": 14, "data": "AYQDiDAyMUEzQ0MyADw=", "err": "none"}"
    if ( !!task->ws.ptr ) {
        json_object* reply = json_tokener_parse( task->ws.ptr );
        json_object* replyData;
        if ( json_object_object_get_ex( reply, "data", &replyData ) && !!replyData ) {
            int len = json_object_array_length(replyData);
            for ( int ii = 0; ii < len; ++ii ) {
                json_object* datum = json_object_array_get_idx( replyData, ii );
                const char* str = json_object_get_string( datum );
                gsize out_len;
                guchar* buf = g_base64_decode( (const gchar*)str, &out_len );
                process( task->storage, buf, out_len );
                g_free( buf );
            }
            (void)json_object_put( replyData );
        }
        (void)json_object_put( reply );
    }

    g_free( task->u.post.msgbuf );

    freeRelayTask( task );

    return FALSE;
}

static void
handlePost( RelayTask* task )
{
    XP_LOGF( "%s(len=%d)", __func__, task->u.post.len );
    XP_ASSERT( !onMainThread(task->storage) );
    char* data = g_base64_encode( task->u.post.msgbuf, task->u.post.len );
    struct json_object* jobj = json_object_new_object();
    struct json_object* jstr = json_object_new_string(data);
    g_free( data );
    json_object_object_add( jobj, "data", jstr );

    CURLcode res = curl_global_init(CURL_GLOBAL_DEFAULT);
    XP_ASSERT(res == CURLE_OK);
    CURL* curl = curl_easy_init();

    char url[128];
    snprintf( url, sizeof(url), "%s://%s/xw4/relay.py/post",
              RELAY_API_PROTO, task->storage->host );
    curl_easy_setopt( curl, CURLOPT_URL, url );
    curl_easy_setopt( curl, CURLOPT_POST, 1L );

    addJsonParams( curl, "params", jobj );

    curl_easy_setopt( curl, CURLOPT_WRITEFUNCTION, write_callback );
    curl_easy_setopt( curl, CURLOPT_WRITEDATA, &task->ws );
    curl_easy_setopt( curl, CURLOPT_VERBOSE, 1L );

    res = curl_easy_perform(curl);
    XP_LOGF( "%s(): curl_easy_perform() => %d", __func__, res );
    /* Check for errors */
    if (res != CURLE_OK) {
        XP_LOGF( "curl_easy_perform() failed: %s", curl_easy_strerror(res));
    }
    /* always cleanup */
    curl_easy_cleanup(curl);
    curl_global_cleanup();

    XP_LOGF( "%s(): got \"%s\"", __func__, task->ws.ptr );

    // Put the data on the main thread for processing
    (void)g_idle_add( onGotPostData, task );
} /* handlePost */

static ssize_t
post( RelayConStorage* storage, const XP_U8* msgbuf, XP_U16 len )
{
    XP_LOGF( "%s(len=%d)", __func__, len );
    RelayTask* task = makeRelayTask( storage, POST );
    task->u.post.msgbuf = g_malloc(len);
    XP_MEMCPY( task->u.post.msgbuf, msgbuf, len );
    task->u.post.len = len;
    addTask( storage, task );
    return len;
}

static gboolean
onGotQueryData( gpointer user_data )
{
    RelayTask* task = (RelayTask*)user_data;
    RelayConStorage* storage = task->storage;
    XP_Bool foundAny = false;
    if ( !!task->ws.ptr ) {
        json_object* reply = json_tokener_parse( task->ws.ptr );
        if ( !!reply ) {
            CommsAddrRec addr = {0};
            addr_addType( &addr, COMMS_CONN_RELAY );

            /* Currently there's an array of arrays for each relayID (value) */
            json_object_object_foreach(reply, relayID, arrOfArrOfMoves) {
                int len1 = json_object_array_length( arrOfArrOfMoves );
                if ( len1 > 0 ) {
                    sqlite3_int64 rowid = *(sqlite3_int64*)g_hash_table_lookup( task->u.query.map, relayID );
                    for ( int ii = 0; ii < len1; ++ii ) {
                        json_object* forGameArray = json_object_array_get_idx( arrOfArrOfMoves, ii );
                        int len2 = json_object_array_length( forGameArray );
                        for ( int jj = 0; jj < len2; ++jj ) {
                            json_object* oneMove = json_object_array_get_idx( forGameArray, jj );
                            const char* asStr = json_object_get_string( oneMove );
                            gsize out_len;
                            guchar* buf = g_base64_decode( asStr, &out_len );
                            (*storage->procs.msgForRow)( storage->procsClosure, &addr,
                                                         rowid, buf, out_len );
                            g_free(buf);
                            foundAny = XP_TRUE;
                        }
                    }
                }
            }
            json_object_put( reply );
        }
    }

    if ( foundAny ) {
        /* Reschedule. If we got anything this time, check again sooner! */
        reset_schedule_check_interval( storage );
    }
    schedule_next_check( storage );

    g_hash_table_destroy( task->u.query.map );
    freeRelayTask(task);
    
    return FALSE;
}

static void
handleQuery( RelayTask* task )
{
    XP_ASSERT( !onMainThread(task->storage) );

    if ( g_hash_table_size( task->u.query.map ) > 0 ) {
        GList* ids = g_hash_table_get_keys( task->u.query.map );

        json_object* jIds = json_object_new_array();
        for ( GList* iter = ids; !!iter; iter = iter->next ) {
            json_object* idstr = json_object_new_string( iter->data );
            json_object_array_add(jIds, idstr);
        }
        g_list_free( ids );

        CURLcode res = curl_global_init(CURL_GLOBAL_DEFAULT);
        XP_ASSERT(res == CURLE_OK);
        CURL* curl = curl_easy_init();

        char url[128];
        snprintf( url, sizeof(url), "%s://%s/xw4/relay.py/query",
                  RELAY_API_PROTO, task->storage->host );
        curl_easy_setopt(curl, CURLOPT_URL, url );
        curl_easy_setopt(curl, CURLOPT_POST, 1L);

        addJsonParams( curl, "ids", jIds );
    
        curl_easy_setopt( curl, CURLOPT_WRITEFUNCTION, write_callback );
        curl_easy_setopt( curl, CURLOPT_WRITEDATA, &task->ws );
        curl_easy_setopt( curl, CURLOPT_VERBOSE, 1L );

        res = curl_easy_perform( curl );

        /* Check for errors */
        if (res != CURLE_OK) {
            XP_LOGF( "curl_easy_perform() failed: %s", curl_easy_strerror(res));
        }
        /* always cleanup */
        curl_easy_cleanup(curl);
        curl_global_cleanup();

        XP_LOGF( "%s(): got <<%s>>", __func__, task->ws.ptr );

    }
    /* Put processing back on the main thread */
    g_idle_add( onGotQueryData, task );
} /* handleQuery */

static void
checkForMovesOnce( RelayConStorage* storage )
{
    LOG_FUNC();
    XP_ASSERT( onMainThread(storage) );

    RelayTask* task = makeRelayTask( storage, QUERY );
    sqlite3* dbp = storage->params->pDb;
    task->u.query.map = getRelayIDsToRowsMap( dbp );
    addTask( storage, task );
}

static gboolean
checkForMoves( gpointer user_data )
{
    RelayConStorage* storage = (RelayConStorage*)user_data;
    checkForMovesOnce( storage );
    schedule_next_check( storage );
    return FALSE;
}

static void
reset_schedule_check_interval( RelayConStorage* storage )
{
    XP_ASSERT( onMainThread(storage) );
    storage->nextMoveCheckSecs = 0;
}

static void
schedule_next_check( RelayConStorage* storage )
{
    XP_ASSERT( onMainThread(storage) );

    if ( storage->moveCheckerID != 0 ) {
        g_source_remove( storage->moveCheckerID );
        storage->moveCheckerID = 0;
    }

    storage->nextMoveCheckSecs *= 2;
    if ( storage->nextMoveCheckSecs > MAX_MOVE_CHECK_SECS ) {
        storage->nextMoveCheckSecs = MAX_MOVE_CHECK_SECS;
    } else if ( storage->nextMoveCheckSecs == 0 ) {
        storage->nextMoveCheckSecs = 1;
    }

    storage->moveCheckerID = g_timeout_add( 1000 * storage->nextMoveCheckSecs,
                                            checkForMoves, storage );
    XP_ASSERT( storage->moveCheckerID != 0 );
}

static ssize_t
sendIt( RelayConStorage* storage, const XP_U8* msgbuf, XP_U16 len )
{
    ssize_t nSent;
    if ( storage->params->useHTTP ) {
        nSent = post( storage, msgbuf, len );
    } else {
        nSent = sendto( storage->socket, msgbuf, len, 0, /* flags */
                        (struct sockaddr*)&storage->saddr, 
                        sizeof(storage->saddr) );
#ifdef COMMS_CHECKSUM
    gchar* sum = g_compute_checksum_for_data( G_CHECKSUM_MD5, msgbuf, len );
    XP_LOGF( "%s: sent %d bytes with sum %s", __func__, len, sum );
    g_free( sum );
#else
    XP_LOGF( "%s()=>%zd", __func__, nSent );
#endif
    }
    return nSent;
}

static size_t
addVLIStr( XP_U8* buf, size_t buflen, const XP_UCHAR* str )
{
    uint32_t len = !!str? strlen( str ) : 0;
    uint8_t nbuf[5];
    size_t nsize = un2vli( len, nbuf );
    if ( nsize + len <= buflen ) {
        memcpy( buf, nbuf, nsize );
        buf += nsize;
        XP_MEMCPY( buf, str, len );
    }
    return nsize + len;
}

static size_t
writeDevID( XP_U8* buf, size_t len, const XP_UCHAR* str )
{
    return addVLIStr( buf, len, str );
}

static size_t
writeShort( XP_U8* buf, size_t len, XP_U16 shrt )
{
    shrt = htons( shrt );
    assert( sizeof( shrt ) <= len );
    XP_MEMCPY( buf, &shrt, sizeof(shrt) );
    return sizeof(shrt);
}

static size_t
writeLong( XP_U8* buf, size_t len, XP_U32 lng )
{
    lng = htonl( lng );
    assert( sizeof( lng ) <= len );
    memcpy( buf, &lng, sizeof(lng) );
    return sizeof(lng);
}

static size_t
writeBytes( XP_U8* buf, size_t len, const XP_U8* bytes, size_t nBytes )
{
    assert( nBytes <= len );
    XP_MEMCPY( buf, bytes, nBytes );
    return nBytes;
}

static size_t
writeVLI( XP_U8* out, uint32_t nn )
{
    uint8_t buf[5];
    size_t numSiz = un2vli( nn, buf );
    memcpy( out, buf, numSiz );
    return numSiz;
}

static XP_U16
getNetShort( const XP_U8** ptr )
{
    XP_U16 result;
    memcpy( &result, *ptr, sizeof(result) );
    *ptr += sizeof(result);
    return ntohs( result );
}

static XP_U32
getNetLong( const XP_U8** ptr )
{
    XP_U32 result;
    memcpy( &result, *ptr, sizeof(result) );
    *ptr += sizeof(result);
    return ntohl( result );
}

static void
getNetString( const XP_U8** ptr, XP_U16 len, XP_UCHAR* buf )
{
    memcpy( buf, *ptr, len );
    *ptr += len;
    buf[len] = '\0';
}

static int
writeHeader( RelayConStorage* storage, XP_U8* dest, XWRelayReg cmd )
{
    int indx = 0;
    dest[indx++] = storage->proto;
    XP_LOGF( "%s: wrote proto %d", __func__, storage->proto );
    uint32_t packetNum = 0;
    if ( XWPDEV_ACK != cmd ) {
        packetNum = storage->nextID++;
    }

    if ( XWPDEV_PROTO_VERSION_1 == storage->proto ) {
        indx += writeVLI( &dest[indx], packetNum );
    } else {
        assert( 0 );
    }

    dest[indx++] = cmd;
    return indx;
}

static bool
readHeader( const XP_U8** buf, MsgHeader* header )
{
    const XP_U8* ptr = *buf;
    bool ok = XWPDEV_PROTO_VERSION_1 == *ptr++;
    assert( ok );

    if ( !vli2un( &ptr, &header->packetID ) ) {
        assert( 0 );
    }
    XP_LOGF( "%s: got packet %d", __func__, header->packetID );

    header->cmd = *ptr++;
    *buf = ptr;
    return ok;
}

/* Utilities */
#define TOKEN_MULT 1000000
XP_U32 
makeClientToken( sqlite3_int64 rowid, XP_U16 seed )
{
    XP_ASSERT( rowid < 0x0000FFFF );
    XP_U32 result = rowid;
    result *= TOKEN_MULT;             /* so visible when displayed as base-10 */
    result += seed;
    return result;
}

void
rowidFromToken( XP_U32 clientToken, sqlite3_int64* rowid, XP_U16* seed )
{
    *rowid = clientToken / TOKEN_MULT;
    *seed = clientToken % TOKEN_MULT;
}

static size_t
un2vli( int nn, uint8_t* buf )
{
    int indx = 0;
    bool done = false;
    do {
        uint8_t byt = nn & 0x7F;
        nn >>= 7;
        done = 0 == nn;
        if ( done ) {
            byt |= 0x80;
        }
        buf[indx++] = byt;
    } while ( !done );

    return indx;
}

static bool
vli2un( const uint8_t** inp, uint32_t* outp )
{
    uint32_t result = 0;
    const uint8_t* in = *inp;
    const uint8_t* end = in + 5;

    int count;
    for ( count = 0; in < end; ++count ) {
        unsigned int byt = *in++;
        bool done = 0 != (byt & 0x80);
        if ( done ) {
            byt &= 0x7F;
        } 
        result |= byt << (7 * count);
        if ( done ) {
            break;
        }
    }

    bool success = in < end;
    if ( success ) {
        *inp = in;
        *outp = result;
    }
    return success;
}
