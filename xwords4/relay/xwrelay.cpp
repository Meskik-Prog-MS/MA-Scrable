/* -*- compile-command: "make -j3"; -*- */

/* 
 * Copyright 2005 - 2012 by Eric House (xwords@eehouse.org).  All rights
 * reserved.
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

//////////////////////////////////////////////////////////////////////////////
//
// This program is a *very rough* cut at a message forwarding server that's
// meant to sit somewhere that cellphones can reach and forward packets across
// connections so that they can communicate.  It exists to work around the
// fact that many cellular carriers prevent direct incoming connections from
// reaching devices on their networks.  It's meant for Crosswords, but might
// be useful for other things.  It also needs a lot of work, and I hacked it
// up before making an exhaustive search for other alternatives.
//
//////////////////////////////////////////////////////////////////////////////

#include <stdio.h>
#include <unistd.h>
#include <netdb.h>		/* gethostbyname */
#include <errno.h>
#include <signal.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <unistd.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <assert.h>
#include <sys/select.h>
#include <stdarg.h>
#include <syslog.h>
#include <sys/wait.h>
#include <sys/prctl.h>

#if defined(__FreeBSD__)
# if (OSVERSION > 500000)
#  include "getopt.h"
# else
#  include "unistd.h"
# endif
#else
# include <getopt.h>
#endif

#include <sys/time.h>

#include "xwrelay.h"
#include "crefmgr.h"
#include "ctrl.h"
#include "http.h"
#include "mlock.h"
#include "tpool.h"
#include "configs.h"
#include "timermgr.h"
#include "permid.h"
#include "lstnrmgr.h"
#include "dbmgr.h"

static int s_nSpawns = 0;

void
logf( XW_LogLevel level, const char* format, ... )
{
    RelayConfigs* rc = RelayConfigs::GetConfigs();
    int configLevel = level;

    if ( NULL != rc ) {

        if ( ! rc->GetValueFor( "LOGLEVEL", &configLevel ) ) {
            configLevel = level - 1; /* drop it */
        }
    }

    if ( level <= configLevel ) {
#ifdef USE_SYSLOG
        char buf[256];
        va_list ap;
        va_start( ap, format );
        vsnprintf( buf, sizeof(buf), format, ap );
        syslog( LOG_LOCAL0 | LOG_INFO, buf );
        va_end(ap);
#else
        FILE* where = NULL;
        struct tm* timp;
        struct timeval tv;
        bool useFile;
        char logFile[256];

        useFile = rc->GetValueFor( "LOGFILE_PATH", logFile, sizeof(logFile) );

        if ( useFile ) {
            where = fopen( logFile, "a" );
        } else  {
            where = stderr;
        }

        if ( !!where ) {
            static int tm_yday = 0;
            gettimeofday( &tv, NULL );
            struct tm result;
            timp = localtime_r( &tv.tv_sec, &result );

            /* log the date once/day.  This isn't threadsafe so may be
               repeated but that's harmless. */
            if ( tm_yday != timp->tm_yday ) {
                tm_yday = timp->tm_yday;
                fprintf( where, "It's a new day: %.2d/%.2d/%d\n", timp->tm_mday,
                         1 + timp->tm_mon, /* 0-based */
                         1900 + timp->tm_year ); /* 1900-based */
            }

            pthread_t me = pthread_self();

            fprintf( where, "<%p>%.2d:%.2d:%.2d: ", (void*)me, timp->tm_hour, 
                     timp->tm_min, timp->tm_sec );

            va_list ap;
            va_start( ap, format );
            vfprintf( where, format, ap );
            va_end(ap);
            fprintf( where, "\n" );

            if ( useFile && !!where ) {
                fclose( where );
            }
        }
#endif
    }
} /* logf */

const char*
cmdToStr( XWRELAY_Cmd cmd )
{
# define CASESTR(s)  case s: return #s
    switch( cmd ) {
        CASESTR(XWRELAY_NONE);
        CASESTR(XWRELAY_GAME_CONNECT);
        CASESTR(XWRELAY_GAME_RECONNECT);
        CASESTR(XWRELAY_ACK);
        CASESTR(XWRELAY_GAME_DISCONNECT);
        CASESTR(XWRELAY_CONNECT_RESP);
        CASESTR(XWRELAY_RECONNECT_RESP);
        CASESTR(XWRELAY_ALLHERE);
        CASESTR(XWRELAY_DISCONNECT_YOU);
        CASESTR(XWRELAY_DISCONNECT_OTHER);
        CASESTR(XWRELAY_CONNECTDENIED);
#ifdef RELAY_HEARTBEAT
        CASESTR(XWRELAY_HEARTBEAT);
#endif
        CASESTR(XWRELAY_MSG_FROMRELAY);
        CASESTR(XWRELAY_MSG_TORELAY);
    default:
        logf( XW_LOGERROR, "%s: unknown command %d", __func__, cmd );
        return "<unknown>";
    }
}

static bool
parseRelayID( unsigned char** const inp, const unsigned char* const end,
              char* buf, int buflen, HostID* hid )
{
    const char* hidp = strchr( (char*)*inp, '/' );

    bool ok = NULL != hidp;
    int connNameLen;

    if ( ok ) {
        connNameLen = hidp - (char*)*inp;
        ok = connNameLen < buflen;
    }
    if ( ok ) {
        strncpy( buf, (char*)*inp, connNameLen );
        buf[connNameLen] = '\0';
        *hid = atoi( hidp+1 );
        char* endptr;
        *hid = strtol( hidp + 1, &endptr, 10 );
        if ( '\n' == *endptr ) {
            ++endptr;
        }
        *inp = (unsigned char*)endptr;
    }
    return ok;
}

static bool
getNetShort( unsigned char** bufpp, const unsigned char* end, 
             unsigned short* out )
{
    unsigned short tmp;
    bool ok = *bufpp + sizeof(tmp) <= end;
    if ( ok ) {
        memcpy( &tmp, *bufpp, sizeof(tmp) );
        *bufpp += sizeof(tmp);
        *out = ntohs( tmp );
    }
    return ok;
} /* getNetShort */

static bool
getNetByte( unsigned char** bufpp, const unsigned char* end, 
            unsigned char* out )
{
    bool ok = *bufpp < end;
    if ( ok ) {
        *out = **bufpp;
        ++*bufpp;
    }
    return ok;
} /* getNetByte */

static bool
getNetString( unsigned char** bufpp, const unsigned char* end, string& out )
{
    char* str = (char*)*bufpp;
    size_t len = 1 + strlen( str );
    bool success = str + len <= (char*)end;
    if ( success ) {
        out = str;
        *bufpp += len;
    }
    // logf( XW_LOGERROR, "%s => %d", __func__, out.c_str() );
    return success;
}

#ifdef RELAY_HEARTBEAT
static bool
processHeartbeat( unsigned char* buf, int bufLen, int socket )
{
    unsigned char* end = buf + bufLen;
    CookieID cookieID; 
    HostID hostID;
    bool success = false;

    if ( getNetShort( &buf, end, &cookieID ) /* may be wrong if ALLCONN hasn't been sent */
         && getNetByte( &buf, end, &hostID ) ) {
        logf( XW_LOGINFO, "processHeartbeat: cookieID 0x%lx, hostID 0x%x", 
              cookieID, hostID );

        {
            SafeCref scr( socket );
            success = scr.HandleHeartbeat( hostID, socket );
        }
    }
    return success;
} /* processHeartbeat */
#endif

static bool
readStr( unsigned char** bufp, const unsigned char* end, 
         char* outBuf, int bufLen )
{
    unsigned char clen = **bufp;
    ++*bufp;
    if ( ((*bufp + clen) <= end) && (clen < bufLen) ) {
        memcpy( outBuf, *bufp, clen );
        outBuf[clen] = '\0';
        *bufp += clen;
        return true;
    }
    return false;
} /* readStr */

static XWREASON
flagsOK( unsigned char** bufp, unsigned char const* end, 
         unsigned short* clientVersion, unsigned short* flagsp )
{
    XWREASON err = XWRELAY_ERROR_OLDFLAGS;
    unsigned char flags;
    if ( getNetByte( bufp, end, &flags ) ) {
        *flagsp = flags;
        switch ( flags ) {
        case XWRELAY_PROTO_VERSION_CLIENTID:
        case XWRELAY_PROTO_VERSION_CLIENTVERS:
            if ( getNetShort( bufp, end, clientVersion ) ) {
                err = XWRELAY_ERROR_NONE;
            }
            break;
        case XWRELAY_PROTO_VERSION_NOCLIENT:
            *clientVersion = 0;
            err = XWRELAY_ERROR_NONE;
            break;
        default:
            break;
        }
    }
    return err;
} /* flagsOK */

void
denyConnection( int socket, XWREASON err )
{
    unsigned char buf[2];

    buf[0] = XWRELAY_CONNECTDENIED;
    buf[1] = err;

    send_with_length_unsafe( socket, buf, sizeof(buf) );
}

/* No mutex here.  Caller better be ensuring no other thread can access this
 * socket. */
bool
send_with_length_unsafe( int socket, unsigned char* buf, int bufLen )
{
    bool ok = false;
    unsigned short len = htons( bufLen );
    ssize_t nSent = send( socket, &len, 2, 0 );
    if ( nSent == 2 ) {
        nSent = send( socket, buf, bufLen, 0 );
        if ( nSent == bufLen ) {
            logf( XW_LOGINFO, "sent %d bytes on socket %d", nSent, socket );
            ok = true;
        }
    }

    if ( !ok ) {
        logf( XW_LOGERROR, "%s(socket=%d) failed", __func__, socket );
    }

    return ok;
} /* send_with_length_unsafe */


/* A CONNECT message from a device gives us the hostID and socket we'll
 * associate with one participant in a relayed session.  We'll store this
 * information with the cookie where other participants can find it when they
 * arrive.
 *
 * What to do if we already have a game going?  In that case the connection ID
 * passed in will be non-zero.  If the device can be associated with an
 * ongoing game, with its new socket, associate it and forward any messages
 * outstanding.  Otherwise close down the socket.  And maybe the others in the
 * game?
 */
static bool
processConnect( unsigned char* bufp, int bufLen, int socket, in_addr& addr )
{
    char cookie[MAX_INVITE_LEN+1];
    unsigned char* end = bufp + bufLen;
    bool success = false;

    cookie[0] = '\0';

    unsigned short clientVersion;
    unsigned short flags;
    XWREASON err = flagsOK( &bufp, end, &clientVersion, &flags );
    if ( err == XWRELAY_ERROR_NONE ) {
        /* HostID srcID; */
        unsigned char nPlayersH;
        unsigned char nPlayersT;
        unsigned short seed;
        unsigned char langCode;
        unsigned char makePublic, wantsPublic;
        if ( readStr( &bufp, end, cookie, sizeof(cookie) ) 
             && getNetByte( &bufp, end, &wantsPublic )
             && getNetByte( &bufp, end, &makePublic )
             /* && getNetByte( &bufp, end, &srcID ) */
             && getNetByte( &bufp, end, &nPlayersH )
             && getNetByte( &bufp, end, &nPlayersT )
             && getNetShort( &bufp, end, &seed )
             && getNetByte( &bufp, end, &langCode ) ) {

            DevID devID;
            if ( XWRELAY_PROTO_VERSION_CLIENTID <= flags ) {
                unsigned char devIDType = 0;
                if ( getNetByte( &bufp, end, &devIDType )
                     && 0 != devIDType ) {
                    if ( getNetString( &bufp, end, devID.m_devIDString )
                         && 0 < devID.m_devIDString.length() ) {
                        devID.m_devIDType = (DevIDType)devIDType;
                    }
                }
            }

            logf( XW_LOGINFO, "%s(): langCode=%d; nPlayersT=%d; "
                  "wantsPublic=%d; seed=%.4X",
                  __func__, langCode, nPlayersT, wantsPublic, seed );

            /* Make sure second thread can't create new cref for same cookie
               this one just handled.*/
            static pthread_mutex_t s_newCookieLock = PTHREAD_MUTEX_INITIALIZER;
            MutexLock ml( &s_newCookieLock );

            SafeCref scr( cookie, socket, clientVersion, &devID, 
                          nPlayersH, nPlayersT, seed, langCode, 
                          wantsPublic, makePublic );
            /* nPlayersT etc could be slots in SafeCref to avoid passing
               here */
            success = scr.Connect( socket, nPlayersH, nPlayersT, seed, addr );
        } else {
            err = XWRELAY_ERROR_BADPROTO;
        }
    }

    if ( err != XWRELAY_ERROR_NONE ) {
        denyConnection( socket, err );
    }
    return success;
} /* processConnect */

static bool
processReconnect( unsigned char* bufp, int bufLen, int socket, in_addr& addr )
{
    unsigned char* end = bufp + bufLen;
    bool success = false;

    logf( XW_LOGINFO, "%s()", __func__ );

    unsigned short clientVersion;
    unsigned short flags;
    XWREASON err = flagsOK( &bufp, end, &clientVersion, &flags );
    if ( err == XWRELAY_ERROR_NONE ) {
        char cookie[MAX_INVITE_LEN+1];
        char connName[MAX_CONNNAME_LEN+1] = {0};
        HostID srcID;
        unsigned char nPlayersH;
        unsigned char nPlayersT;
        unsigned short gameSeed;
        unsigned char makePublic, wantsPublic;
        unsigned char langCode;

        if ( readStr( &bufp, end, cookie, sizeof(cookie) )
             && getNetByte( &bufp, end, &wantsPublic )
             && getNetByte( &bufp, end, &makePublic )
             && getNetByte( &bufp, end, &srcID )
             && getNetByte( &bufp, end, &nPlayersH )
             && getNetByte( &bufp, end, &nPlayersT )
             && getNetShort( &bufp, end, &gameSeed )
             && getNetByte( &bufp, end, &langCode )
             && readStr( &bufp, end, connName, sizeof(connName) ) ) {

            SafeCref scr( connName[0]? connName : NULL, 
                          cookie, srcID, socket, clientVersion, nPlayersH, 
                          nPlayersT, gameSeed, langCode,
                          wantsPublic, makePublic );
            success = scr.Reconnect( socket, srcID, nPlayersH, nPlayersT, 
                                     gameSeed, addr, &err );
            if ( !success ) {
                assert( err != XWRELAY_ERROR_NONE );
            }
        } else { 
            err = XWRELAY_ERROR_BADPROTO;
        }
    }

    if ( err != XWRELAY_ERROR_NONE ) {
        denyConnection( socket, err );
    }

    return success;
} /* processReconnect */

static bool
processAck( unsigned char* bufp, int bufLen, int socket )
{
    bool success = false;
    unsigned char* end = bufp + bufLen;
    HostID srcID;
    if ( getNetByte( &bufp, end, &srcID ) ) {
        SafeCref scr( socket );
        success = scr.HandleAck( srcID );
    }
    return success;
}

static bool
processDisconnect( unsigned char* bufp, int bufLen, int socket )
{
    unsigned char* end = bufp + bufLen;
    CookieID cookieID;
    HostID hostID;
    bool success = false;

    if ( getNetShort( &bufp, end, &cookieID ) 
         && getNetByte( &bufp, end, &hostID ) ) {

        SafeCref scr( socket );
        scr.Disconnect( socket, hostID );
        success = true;
    } else {
        logf( XW_LOGERROR, "dropping XWRELAY_GAME_DISCONNECT; wrong length" );
    }
    return success;
} /* processDisconnect */

static void
killSocket( int socket )
{
    logf( XW_LOGINFO, "%s(%d)", __func__, socket );
    CRefMgr::Get()->RemoveSocketRefs( socket );
}

time_t
uptime( void ) 
{
    static time_t startTime = time(NULL);
    return time(NULL) - startTime;
}

void
blockSignals( void )
{
    sigset_t set;
    sigemptyset( &set );
    sigaddset( &set, SIGINT );
    sigaddset( &set, SIGTERM);
    int s = pthread_sigmask( SIG_BLOCK, &set, NULL );
    assert( 0 == s );
}

int
GetNSpawns(void)
{
    return s_nSpawns;
}

/* forward the message.  Need only change the command after looking up the
 * socket and it's ready to go. */
static bool
forwardMessage( unsigned char* buf, int buflen, int srcSocket, in_addr& addr )
{
    bool success = false;
    unsigned char* bufp = buf + 1; /* skip cmd */
    unsigned char* end = buf + buflen;
    CookieID cookieID;
    HostID src;
    HostID dest;

    if ( getNetShort( &bufp, end, &cookieID )
         && getNetByte( &bufp, end, &src ) 
         && getNetByte( &bufp, end, &dest ) ) {
        logf( XW_LOGINFO, "cookieID = %d", cookieID );

        if ( COOKIE_ID_NONE == cookieID ) {
            SafeCref scr( srcSocket );
            success = scr.Forward( src, addr, dest, buf, buflen );
        } else {
            SafeCref scr( cookieID ); /* won't work if not allcon; will be 0 */
            success = scr.Forward( src, addr, dest, buf, buflen );
        }
    }
    return success;
} /* forwardMessage */

static bool
processMessage( unsigned char* buf, int bufLen, int socket, in_addr& addr )
{
    bool success = false;            /* default is failure */
    XWRELAY_Cmd cmd = *buf;

    logf( XW_LOGINFO, "%s got %s", __func__, cmdToStr(cmd) );

    switch( cmd ) {
    case XWRELAY_GAME_CONNECT: 
        success = processConnect( buf+1, bufLen-1, socket, addr );
        break;
    case XWRELAY_GAME_RECONNECT: 
        success = processReconnect( buf+1, bufLen-1, socket, addr );
        break;
    case XWRELAY_ACK:
        success = processAck( buf+1, bufLen-1, socket );
        break;
    case XWRELAY_GAME_DISCONNECT:
        success = processDisconnect( buf+1, bufLen-1, socket );
        break;
#ifdef RELAY_HEARTBEAT
    case XWRELAY_HEARTBEAT:
        success = processHeartbeat( buf + 1, bufLen - 1, socket );
        break;
#endif
    case XWRELAY_MSG_TORELAY:
        success = forwardMessage( buf, bufLen, socket, addr );
        break;
    default:
        logf( XW_LOGERROR, "%s bad: %d", __func__, cmd );
        break;
        /* just drop it */
    }

    if ( !success ) {
        XWThreadPool::GetTPool()->EnqueueKill( socket, "failure" );
    }

    return success;
} /* processMessage */

int 
make_socket( unsigned long addr, unsigned short port )
{
    int sock = socket( AF_INET, SOCK_STREAM, 0 );
    assert( sock );

    /* We may be relaunching after crashing with sockets open.  SO_REUSEADDR
       allows them to be immediately rebound. */
    int t = true;
    if ( 0 != setsockopt( sock, SOL_SOCKET, SO_REUSEADDR, &t, sizeof(t) ) ) {
        logf( XW_LOGERROR, "setsockopt failed. errno = %s (%d)\n", 
              strerror(errno), errno );
        return -1;
    }

    struct sockaddr_in sockAddr;
    sockAddr.sin_family = AF_INET;
    sockAddr.sin_addr.s_addr = htonl(addr);
    sockAddr.sin_port = htons(port);

    int result = bind( sock, (struct sockaddr*)&sockAddr, sizeof(sockAddr) );
    if ( result != 0 ) {
        logf( XW_LOGERROR, "exiting: unable to bind port %d: %d, "
              "errno = %s (%d)\n", port, result, strerror(errno), errno );
        return -1;
    }
    logf( XW_LOGINFO, "bound socket %d on port %d", sock, port );

    result = listen( sock, 5 );
    if ( result != 0 ) {
        logf( XW_LOGERROR, "exiting: unable to listen: %d, "
              "errno = %s (%d)\n", result, strerror(errno), errno );
        return -1;
    }
    return sock;
} /* make_socket */

static void
usage( char* arg0 )
{
    fprintf( stderr, "usage: %s \\\n", arg0 );

    fprintf( stderr,
             "\t-?                   (print this help)\\\n"
             "\t-c <cport>           (localhost port for control console)\\\n"
#ifdef DO_HTTP
             "\t-w <cport>           (localhost port for web interface)\\\n"
#endif
             "\t-D                   (don't become daemon)\\\n"
             "\t-F                   (don't fork and wait to respawn child)\\\n"
             "\t-f <conffile>        (config file)\\\n"
             "\t-h                   (print this help)\\\n"
             "\t-i <idfile>          (file where next global id stored)\\\n"
             "\t-l <logfile>         (write logs here, not stderr)\\\n"
             "\t-n <serverName>      (used in permID generation)\\\n"
             "\t-p <port>            (port to listen on)\\\n"
#ifdef DO_HTTP
             "\t-s <path>            (path to css file for http iface)\\\n"
#endif
             "\t-t <nWorkerThreads>  (how many worker threads to use)\\\n"
             );
    fprintf( stderr, "git rev. %s\n", SVN_REV );
}

/* sockets that need to be closable from interrupt handler */
ListenerMgr g_listeners;
int g_control;
#ifdef DO_HTTP
static int g_http = -1;
#endif

void
shutdown()
{
    XWThreadPool* tPool = XWThreadPool::GetTPool();
    if ( tPool != NULL ) {
        tPool->Stop();
    }

    CRefMgr* cmgr = CRefMgr::Get();
    if ( cmgr != NULL ) {
        cmgr->CloseAll();
        delete cmgr;
    }

    delete tPool;

    //stop_ctrl_threads();

    g_listeners.RemoveAll();
    close( g_control );
#ifdef DO_HTTP
    close( g_http );
#endif
    exit( 0 );
    logf( XW_LOGINFO, "exit done" );
}

static void
SIGINT_handler( int sig )
{
    logf( XW_LOGERROR, "%s", __func__ );
    shutdown();
}

#ifdef SPAWN_SELF
static void
printWhy( int status ) 
{
    if ( WIFEXITED(status) ) {
        logf( XW_LOGINFO, "why: exited" );
    } else if ( WIFSIGNALED(status) ) {
        logf( XW_LOGINFO, "why: signaled; signal: %d", WTERMSIG(status) );
    } else if ( WCOREDUMP(status) ) {
        logf( XW_LOGINFO, "why: core" );
    } else if ( WIFSTOPPED(status) ) {
        logf( XW_LOGINFO, "why: traced" );
    }
} /* printWhy */
#endif

static void
parentDied( int sig )
{
    logf( XW_LOGINFO, "%s", __func__ );
    exit(0);
}

static void
handlePipe( int sig )
{
    logf( XW_LOGINFO, "%s", __func__ );
}

int
read_packet( int sock, unsigned char* buf, int buflen )
{
    int result = -1;
    ssize_t nread;
    unsigned short msgLen;
    nread = recv( sock, &msgLen, sizeof(msgLen), MSG_WAITALL );
    if ( 0 == nread ) {
        logf( XW_LOGINFO, "%s: recv => 0: remote closed", __func__ );
    } else if ( nread != sizeof(msgLen) ) {
        logf( XW_LOGERROR, "%s: first recv => %d: %s", __func__, 
              nread, strerror(errno) );
    } else {
        msgLen = ntohs( msgLen );
        if ( msgLen >= buflen ) {
            logf( XW_LOGERROR, "%s: buf too small; need %d but have %d", 
                  __func__, msgLen, buflen );
        } else {
            nread = recv( sock, buf, msgLen, MSG_WAITALL );
            if ( nread == msgLen ) {
                result = nread;
            } else {
                logf( XW_LOGERROR, "%s: second recv failed: %s", __func__, 
                      strerror(errno) );
            }
        }
    }

    return result;
} /* read_packet */

static void
pushShort( vector<unsigned char>& out, unsigned short num )
{
    num = htons( num );
    out.insert( out.end(), (unsigned char*)&num, ((unsigned char*)&num) + 2 );
}

static void
pushMsgs( vector<unsigned char>& out, DBMgr* dbmgr, const char* connName, 
          HostID hid, int msgCount, vector<int>& msgIDs )
{
    int ii;
    for ( ii = 0; ii < msgCount; ++ii ) {
        unsigned char buf[1024];
        size_t buflen = sizeof(buf);
        int msgID;
        if ( !dbmgr->GetNthStoredMessage( connName, hid, ii, buf, 
                                          &buflen, &msgID ) ) {
            logf( XW_LOGERROR, "%s: %dth message not there", __func__, ii );
            break;
        }
        pushShort( out, buflen );
        out.insert( out.end(), buf, buf + buflen );
        msgIDs.push_back( msgID );
    }
}

static void
handleMsgsMsg( int sock, in_addr& addr, bool sendFull,
               unsigned char* bufp, const unsigned char* end )
{
    unsigned short nameCount;
    int ii;
    if ( getNetShort( &bufp, end, &nameCount ) ) {
        DBMgr* dbmgr = DBMgr::Get();
        vector<unsigned char> out(4); /* space for len and n_msgs */
        assert( out.size() == 4 );
        vector<int> msgIDs;
        for ( ii = 0; ii < nameCount && bufp < end; ++ii ) {

            // See NetUtils.java for reply format
            // message-length: 2
            // nameCount: 2
            // name count reps of:
            //    counts-this-name: 2
            //    counts-this-name reps of
            //       len: 2
            //       msg: <len>

            // pack msgs for one game
            HostID hid;
            char connName[MAX_CONNNAME_LEN+1];
            if ( !parseRelayID( &bufp, end, connName, sizeof(connName),
                                &hid ) ) {
                break;
            }

            dbmgr->RecordAddress( connName, hid, addr );

            /* For each relayID, write the number of messages and then
               each message (in the getmsg case) */
            int msgCount = dbmgr->PendingMsgCount( connName, hid );
            pushShort( out, msgCount );
            if ( sendFull ) {
                pushMsgs( out, dbmgr, connName, hid, msgCount, msgIDs );
            }
        }

        unsigned short tmp = htons( out.size() - sizeof(tmp) );
        memcpy( &out[0], &tmp, sizeof(tmp) );
        tmp = htons( nameCount );
        memcpy( &out[2], &tmp, sizeof(tmp) );
        ssize_t nwritten = write( sock, &out[0], out.size() );
        logf( XW_LOGINFO, "%s: wrote %d bytes", __func__, nwritten );
        if ( sendFull && nwritten >= 0 && (size_t)nwritten == out.size() ) {
            dbmgr->RecordSent( &msgIDs[0], msgIDs.size() );
            dbmgr->RemoveStoredMessages( &msgIDs[0], msgIDs.size() );
        }
    }
} // handleMsgsMsg

#define NUM_PER_LINE 8
void
log_hex( const unsigned char* memp, int len, const char* tag )
{
    const char* hex = "0123456789ABCDEF";
    int i, j;
    int offset = 0;

    while ( offset < len ) {
        char buf[128];
        unsigned char vals[NUM_PER_LINE*3];
        unsigned char* valsp = vals;
        unsigned char chars[NUM_PER_LINE+1];
        unsigned char* charsp = chars;
        int oldOffset = offset;

        for ( i = 0; i < NUM_PER_LINE && offset < len; ++i ) {
            unsigned char byte = memp[offset];
            for ( j = 0; j < 2; ++j ) {
                *valsp++ = hex[(byte & 0xF0) >> 4];
                byte <<= 4;
            }
            *valsp++ = ':';

            byte = memp[offset];
            if ( (byte >= 'A' && byte <= 'Z')
                 || (byte >= 'a' && byte <= 'z')
                 || (byte >= '0' && byte <= '9') ) {
                /* keep it */
            } else {
                byte = '.';
            }
            *charsp++ = byte;
            ++offset;
        }
        *(valsp-1) = '\0';      /* -1 to overwrite ':' */
        *charsp = '\0';

        if ( (NULL == tag) || (strlen(tag) + sizeof(vals) >= sizeof(buf)) ) {
            tag = "<tag>";
        }
        snprintf( buf, sizeof(buf), "%s[%d]: %s %s", tag, oldOffset, 
                  vals, chars );
        fprintf( stderr, "%s\n", buf );
    }
} // log_hex

static void
handleProxyMsgs( int sock, in_addr& addr, unsigned char* bufp, unsigned char* end )
{
    // log_hex( bufp, end-bufp, __func__ );
    unsigned short nameCount;
    int ii;
    if ( getNetShort( &bufp, end, &nameCount ) ) {
        vector<unsigned char> out(4); /* space for len and n_msgs */
        assert( out.size() == 4 );
        for ( ii = 0; ii < nameCount && bufp < end; ++ii ) {

            // See NetUtils.java for reply format
            // message-length: 2
            // nameCount: 2
            // name count reps of:
            //    counts-this-name: 2
            //    counts-this-name reps of
            //       len: 2
            //       msg: <len>

            // pack msgs for one game
            HostID hid;
            char connName[MAX_CONNNAME_LEN+1];
            if ( !parseRelayID( &bufp, end, connName, sizeof(connName),
                                &hid ) ) {
                break;
            }
            unsigned short nMsgs;
            if ( getNetShort( &bufp, end, &nMsgs ) ) {
                SafeCref scr( connName );
                while ( nMsgs-- > 0 ) {
                    unsigned short len;
                    HostID src;
                    HostID dest;
                    XWRELAY_Cmd cmd;
                    if ( getNetShort( &bufp, end, &len ) ) {
                        unsigned char* start = bufp;
                        if ( getNetByte( &bufp, end, &cmd )
                             && getNetByte( &bufp, end, &src )
                             && getNetByte( &bufp, end, &dest ) ) {
                            assert( cmd == XWRELAY_MSG_TORELAY_NOCONN );
                            assert( hid == dest );
                            scr.PutMsg( src, addr, dest, start, len );
                            bufp = start + len;
                            continue;
                        }
                    }
                    break;
                }
            }
        }
        assert( bufp == end );  // don't ship with this!!!
    }
} // handleProxyMsgs

void
handle_proxy_packet( unsigned char* buf, int len, int sock, in_addr& addr )
{
    logf( XW_LOGINFO, "%s()", __func__ );
    if ( len > 0 ) {
        unsigned char* bufp = buf;
        unsigned char* end = bufp + len;
        if ( (0 == *bufp++) ) { /* protocol */
            XWPRXYCMD cmd = (XWPRXYCMD)*bufp++;
            switch( cmd ) {
            case PRX_NONE:
                break;
            case PRX_PUB_ROOMS:
                if ( len >= 4 ) {
                    int lang = *bufp++;
                    int nPlayers = *bufp++;
                    string names;
                    int nNames;

                    // sleep(2);   /* use this to test when running locally */

                    DBMgr::Get()->PublicRooms( lang, nPlayers, &nNames, names );
                    unsigned short netshort = htons( names.size()
                                                     + sizeof(unsigned short) );
                    write( sock, &netshort, sizeof(netshort) );
                    netshort = htons( (unsigned short)nNames );
                    write( sock, &netshort, sizeof(netshort) );
                    write( sock, names.c_str(), names.size() );
                }
                break;
            case PRX_HAS_MSGS:
            case PRX_GET_MSGS:
                if ( len >= 2 ) {
                    handleMsgsMsg( sock, addr, PRX_GET_MSGS == cmd, bufp, end );
                }
                break;          /* PRX_HAS_MSGS */

            case PRX_PUT_MSGS:
                handleProxyMsgs( sock, addr, bufp, end );
                break;

            case PRX_DEVICE_GONE:
                logf( XW_LOGINFO, "%s: got PRX_DEVICE_GONE", __func__ );
                if ( len >= 2 ) {
                    unsigned short nameCount;
                    if ( getNetShort( &bufp, end, &nameCount ) ) {
                        int ii;
                        for ( ii = 0; ii < nameCount; ++ii ) {
                            unsigned short seed;
                            if ( !getNetShort( &bufp, end, &seed ) ) {
                                break;
                            }

                            HostID hid;
                            char connName[MAX_CONNNAME_LEN+1];
                            if ( !parseRelayID( &bufp, end, connName, 
                                                sizeof( connName ), &hid ) ) {
                                break;
                            }
                            SafeCref scr( connName );
                            scr.DeviceGone( hid, seed );
                        }
                    }
                }
                len = 0;        /* return a 0-length message */
                write( sock, &len, sizeof(len) );
                break;          /* PRX_DEVICE_GONE */
            default:
                logf( XW_LOGERROR, "unexpected command %d", __func__, cmd );
                break;
            }
        }
    }
} /* handle_proxy_packet */

static void
set_timeouts( int sock )
{
    struct timeval tv;
    int result;

    int timeout = 5;
    (void)RelayConfigs::GetConfigs()->GetValueFor( "SOCK_TIMEOUT_SECONDS", 
                                                   &timeout );

    tv.tv_sec = timeout;     /* seconds */
    tv.tv_usec = 0;    /* microseconds */

    result = setsockopt( sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv) );
    if ( 0 != result ) {
        logf( XW_LOGERROR, "setsockopt=>%d (%s)", errno, strerror(errno) );
        assert( 0 );
    }
    result = setsockopt( sock, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv) );
    if ( 0 != result ) {
        logf( XW_LOGERROR, "setsockopt=>%d (%s)", errno, strerror(errno) );
        assert( 0 );
    }
}

static void
enable_keepalive( int sock )
{
    int optval = 1;
    if ( 0 > setsockopt( sock, SOL_SOCKET, SO_KEEPALIVE, 
                         &optval, sizeof( optval ) ) ) {
        logf( XW_LOGERROR, "setsockopt(SO_KEEPALIVE)=>%d (%s)", errno, 
              strerror(errno) );
        assert( 0 );
    }
    /*
      The above will kill sockets, eventually, whose remote ends have died
      without notifying us.  (Duplicate by pulling a phone's battery while it
      has an open connection.)  It'll take nearly three hours, however.  The
      info below appears to allow for significantly shortening the time,
      though at the expense of greater network traffic.  I'm going to let it
      run this way before bothering with anything more.

      from http://tldp.org/HOWTO/html_single/TCP-Keepalive-HOWTO/

      "There are also three other socket options you can set for keepalive
      when you write your application. They all use the SOL_TCP level instead
      of SOL_SOCKET, and they override system-wide variables only for the
      current socket.  If you read without writing first, the current
      system-wide parameters will be returned."

      * TCP_KEEPCNT: overrides tcp_keepalive_probes
      * TCP_KEEPIDLE: overrides tcp_keepalive_time
      * TCP_KEEPINTVL: overrides tcp_keepalive_intvl
      */
}

int
main( int argc, char** argv )
{
    int port = 0;
    int ctrlport = 0;
#ifdef DO_HTTP
    int httpport = 0;
    const char* cssFile = NULL;
#endif
    int nWorkerThreads = 0;
    char* conffile = NULL;
    const char* serverName = NULL;
    // const char* idFileName = NULL;
    const char* logFile = NULL;
    bool doDaemon = true;
    bool doFork = true;

    (void)uptime();                /* force capture of start time */

    /* Verify sizes here... */
    assert( sizeof(CookieID) == 2 );

    /* Read options. Options trump config file values when they conflict, but
       the name of the config file is an option so we have to get that
       first. */

    for ( ; ; ) {
       int opt = getopt(argc, argv, "h?c:p:n:f:l:t:s:w:"
                        "DF" );

       if ( opt == -1 ) {
           break;
       }
       switch( opt ) {
       case '?':
       case 'h':
           usage( argv[0] );
           exit( 0 );
       case 'c':
           ctrlport = atoi( optarg );
           break;
#ifdef DO_HTTP
       case 'w':
           httpport = atoi( optarg );
           break;
       case 's':
           cssFile = optarg;
           break;
#else
       case 'w':
       case 's':
           fprintf( stderr, "option -%c disabled and ignored\n", opt );
           break;
#endif
       case 'D':
           doDaemon = false;
           break;
       case 'F':
           doFork = false;
           break;
       case 'f':
           conffile = optarg;
           break;
       // case 'i':
       //     idFileName = optarg;
       //     break;
       case 'l':
           logFile = optarg;
           break;
       case 'n':
           serverName = optarg;
           break;
       case 'p':
           port = atoi( optarg );
           break;
       case 't':
           nWorkerThreads = atoi( optarg );
           break;
       default:
           usage( argv[0] );
           exit( 1 );
       }
    }

    /* Did we consume all the options passed in? */
    if ( optind != argc ) {
        usage( argv[0] );
        exit( 1 );
    }

    RelayConfigs::InitConfigs( conffile );
    RelayConfigs* cfg = RelayConfigs::GetConfigs();

    if ( NULL != logFile ) {
        cfg->SetValueFor( "LOGFILE_PATH", logFile );
    }

    if ( ctrlport == 0 ) {
        (void)cfg->GetValueFor( "CTLPORT", &ctrlport );
    }
#ifdef DO_HTTP
    if ( httpport == 0 ) {
        (void)cfg->GetValueFor( "WWW_PORT", &httpport );
    }
#endif
    if ( nWorkerThreads == 0 ) {
        (void)cfg->GetValueFor( "NTHREADS", &nWorkerThreads );
    }
    char serverNameBuf[128];
    if ( serverName == NULL ) {
        if ( cfg->GetValueFor( "SERVERNAME", serverNameBuf, 
                               sizeof(serverNameBuf) ) ) {
            serverName = serverNameBuf;
        }
    }

#ifdef DO_HTTP
    /* http module uses this */
    if ( !!cssFile ) {
        cfg->SetValueFor( "WWW_CSS_PATH", cssFile );
    }
#endif

    PermID::SetServerName( serverName );
    /* add signal handling here */

    /*
      The daemon() function is for programs wishing to detach themselves from
      the controlling terminal and run in the background as system daemons.
 
      Unless the argument nochdir is non-zero, daemon() changes the current
      working directory to the root ("/").
 
      Unless the argument noclose is non-zero, daemon() will redirect standard
      input, standard output and standard error to /dev/null.

      (This function forks, and if the fork() succeeds, the parent does
      _exit(0), so that further errors are seen by the child only.)  On
      success zero will be returned.  If an error occurs, daemon() returns -1
      and sets the global variable errno to any of the errors specified for
      the library functions fork(2) and setsid(2).
    */
    if ( doDaemon ) {
        if ( 0 != daemon( true, false ) ) {
            logf( XW_LOGERROR, "dev() => %s", strerror(errno) );
            exit( -1 );
        }
    }

#ifdef SPAWN_SELF
    /* loop forever, relaunching children as they die. */
    while ( doFork ) {
        ++s_nSpawns;             /* increment in parent *before* copy */
        pid_t pid = fork();
        if ( pid == 0 ) {       /* child */
            break;
        } else if ( pid > 0 ) {
            int status;
            logf( XW_LOGINFO, "parent waiting on child pid=%d", pid );
            time_t time_before = time( NULL );
            waitpid( pid, &status, 0 );
            printWhy( status );
            time_t time_after = time( NULL );
            doFork = time_after > time_before;
            if ( !doFork ) {
                logf( XW_LOGERROR, "exiting b/c respawned too quickly" );
            }
        } else {
            logf( XW_LOGERROR, "fork() => %s", strerror(errno) );
        }
    }
#endif

    /* Needs to be reset after a crash/respawn */
    PermID::SetStartTime( time(NULL) );

    logf( XW_LOGERROR, "***** forked %dth new process *****", s_nSpawns );

    /* Arrange to be sent SIGUSR1 on death of parent. */
    prctl( PR_SET_PDEATHSIG, SIGUSR1 );

    struct sigaction sact;
    memset( &sact, 0, sizeof(sact) );
    sact.sa_handler = parentDied;
    (void)sigaction( SIGUSR1, &sact, NULL );

    memset( &sact, 0, sizeof(sact) );
    sact.sa_handler = handlePipe;
    (void)sigaction( SIGPIPE, &sact, NULL );

    if ( port != 0 ) {
        g_listeners.AddListener( port, true );
    }
    vector<int> ints_game;
    if ( !cfg->GetValueFor( "GAME_PORTS", ints_game ) ) {
        exit( 1 );
    }

    DBMgr::Get()->ClearCIDs();  /* get prev boot's state in db */

    vector<int>::const_iterator iter_game;
    for ( iter_game = ints_game.begin(); iter_game != ints_game.end(); 
          ++iter_game ) {
        int port = *iter_game;
        if ( !g_listeners.PortInUse( port ) ) {
            if ( !g_listeners.AddListener( port, true ) ) {
                exit( 1 );
            }
        } else {
            logf( XW_LOGERROR, "port %d was in use", port );
        }
    }

    vector<int> ints_device;
    if ( cfg->GetValueFor( "DEVICE_PORTS", ints_device ) ) {

        vector<int>::const_iterator iter;
        for ( iter = ints_device.begin(); iter != ints_device.end(); ++iter ) {
            int port = *iter;
            if ( !g_listeners.PortInUse( port ) ) {
                if ( !g_listeners.AddListener( port, false ) ) {
                    exit( 1 );
                }
            } else {
                logf( XW_LOGERROR, "port %d was in use", port );
            }
        }
    }

    g_control = make_socket( INADDR_LOOPBACK, ctrlport );
    if ( g_control == -1 ) {
        exit( 1 );
    }

#ifdef DO_HTTP
    HttpState http_state;
    int addr;

    memset( &http_state, 0, sizeof(http_state) );
    if ( cfg->GetValueFor( "WWW_SAMPLE_INTERVAL", 
                           &http_state.m_sampleInterval )
         && cfg->GetValueFor( "WWW_LISTEN_ADDR", &addr ) ) {
        g_http = make_socket( addr, httpport );
        if ( g_http == -1 ) {
            exit( 1 );
        }
        http_state.ctrl_sock = g_http;
    }
    if ( -1 != g_http ) {
        pthread_mutex_init( &http_state.m_dataMutex, NULL );
    }
#endif

    struct sigaction act;
    memset( &act, 0, sizeof(act) );
    act.sa_handler = SIGINT_handler;
    (void)sigaction( SIGINT, &act, NULL );

    XWThreadPool* tPool = XWThreadPool::GetTPool();
    tPool->Setup( nWorkerThreads, processMessage, killSocket );

    /* set up select call */
    fd_set rfds;
    for ( ; ; ) {
        FD_ZERO(&rfds);
        g_listeners.AddToFDSet( &rfds );
        FD_SET( g_control, &rfds );
#ifdef DO_HTTP
        if ( -1 != g_http ) {
            FD_SET( g_http, &rfds );
        }
#endif
        int highest = g_listeners.GetHighest();
        if ( g_control > highest ) {
            highest = g_control;
        }
#ifdef DO_HTTP
        if ( g_http > highest ) {
            highest = g_http;
        }
#endif
        ++highest;

        int retval = select( highest, &rfds, NULL, NULL, NULL );
        if ( retval < 0 ) {
            if ( errno != 4 ) { /* 4's what we get when signal interrupts */
                logf( XW_LOGINFO, "errno: %s (%d)", strerror(errno), errno );
            }
        } else {
            ListenersIter iter(&g_listeners, true);
            while ( retval > 0 ) {
                bool perGame;
                int listener = iter.next( &perGame );
                if ( listener < 0 ) {
                    break;
                }

                if ( FD_ISSET( listener, &rfds ) ) {
                    struct sockaddr_in newaddr;
                    socklen_t siz = sizeof(newaddr);
                    int newSock = accept( listener, (sockaddr*)&newaddr,
                                          &siz );
                    if ( newSock < 0 ) {
                        logf( XW_LOGERROR, "accept failed: errno(%d)=%s",
                              errno, strerror(errno) );
                        assert( 0 ); // we're leaking files or load has grown
                    } else {

                        /* Set timeout so send and recv won't block forever */
                        set_timeouts( newSock );
                        
                        enable_keepalive( newSock );

                        logf( XW_LOGINFO, 
                              "%s: accepting connection from %s on socket %d", 
                              __func__, inet_ntoa(newaddr.sin_addr), newSock );

                        tPool->AddSocket( newSock,
                                          perGame ? XWThreadPool::STYPE_GAME 
                                          : XWThreadPool::STYPE_PROXY,
                                          newaddr.sin_addr );
                    }
                    --retval;
                }
            }
            if ( FD_ISSET( g_control, &rfds ) ) {
                assert(0);      // not working; don't use until fixed
                // run_ctrl_thread( g_control );
                --retval;
            }
#ifdef DO_HTTP
            if ( FD_ISSET( g_http, &rfds ) ) {
                FD_CLR( g_http, &rfds );
                run_http_thread( &http_state );
                --retval;
            }
#endif
            assert( retval == 0 );
        }
    }

    g_listeners.RemoveAll();
    close( g_control );

    delete cfg;

    return 0;
} // main
