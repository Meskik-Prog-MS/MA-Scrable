/* -*-mode: C; fill-column: 78; c-basic-offset: 4; -*- */
/* 
 * Copyright 2005-2009 by Eric House (xwords@eehouse.org).  All rights
 * reserved.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option.
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


#ifndef _CREF_H_
#define _CREF_H_

#include <map>
#include <vector>
#include <string>
#include <deque>
#include <pthread.h>
#include "xwrelay_priv.h"
#include "xwrelay.h"
#include "states.h"

using namespace std;

class CookieMapIterator;        /* forward */

struct HostRec {
 public:
   HostRec(HostID hostID, int socket, int nPlayersH, int nPlayersT,
        int seed ) 
        : m_hostID(hostID) 
        , m_socket(socket)
        , m_nPlayersH(nPlayersH) 
        , m_nPlayersT(nPlayersT) 
        , m_seed(seed) 
        , m_lastHeartbeat(uptime()) 
        {}
    HostID m_hostID;
    int m_socket;
    int m_nPlayersH;
    int m_nPlayersT;
    int m_seed;
    time_t m_lastHeartbeat;
};

class CookieRef {

 private:
    /* These classes have access to CookieRef.  All others should go through
       SafeCref instances. */
    friend class CRefMgr;
    friend class SafeCref;
    friend class CookieMapIterator;

    CookieRef( const char* cookie, const char* connName, CookieID id );
    void ReInit( const char* cookie, const char* connName, CookieID id );
    ~CookieRef();

    void Clear(void);                /* make clear it's unused */

    bool Lock( void );
    void Unlock( void );

    /* Within this cookie, remember that this hostID and socket go together.
       If the hostID is HOST_ID_SERVER, it's the server. */
    CookieID GetCookieID() { return m_cookieID; }
    int GetTotalSent() { return m_totalSent; }
    int GetPlayersSought() { return m_nPlayersSought; }
    int GetPlayersHere() { return m_nPlayersHere; }

    int CountSockets() { return m_sockets.size(); }
    bool HasSocket( int socket );
    bool HasSocket_locked( int socket );
    const char* Cookie() { return m_cookie.c_str(); }
    const char* ConnName() { return m_connName.c_str(); }

    int GetHeartbeat() { return m_heatbeat; }
    int SocketForHost( HostID dest );

    bool NeverFullyConnected();
    bool GameOpen( const char* cookie, int nPlayersH, bool isNew, 
                   bool* alreadyHere );

    /* for console */
    void _PrintCookieInfo( string& out );
    void PrintSocketInfo( string& out, int socket );
    void _FormatHostInfo( string* hostIds, string* seeds, string* addrs );

    static CookieMapIterator GetCookieIterator();

    /* Nuke an existing */
    static void Delete( CookieID id );
    static void Delete( const char* name );

    void _Connect( int socket, HostID srcID, int nPlayersH, int nPlayersT,
                   int seed );
    void _Reconnect( int socket, HostID srcID, int nPlayersH, int nPlayersT,
                     int seed );
    void _Disconnect(int socket, HostID hostID );
    void _Shutdown();
    void _HandleHeartbeat( HostID id, int socket );
    void _CheckHeartbeats( time_t now );
    void _Forward( HostID src, HostID dest, unsigned char* buf, int buflen );
    void _Remove( int socket );
    void _CheckAllConnected();

    bool ShouldDie() { return m_curState == XWS_DEAD; }
    XW_RELAY_STATE CurState() { return m_curState; }

    void logf( XW_LogLevel level, const char* format, ... );

    typedef struct CRefEvent {
        XW_RELAY_EVENT type;
        union {
            struct {
                HostID src;
                HostID dest;
                unsigned char* buf;
                int buflen;
            } fwd;
            struct {
                int socket;
                int nPlayersH;
                int nPlayersT;
                int seed;
                HostID srcID;
            } con;
            struct {
                int socket;
                HostID srcID;
            } discon;
            struct {
                HostID id;
                int socket;
            } heart;
            struct {
                time_t now;
            } htime;
            struct {
                int socket;
            } rmsock;
            struct {
                int socket;
                XWREASON why;
            } disnote;
        } u;
    } CRefEvent;

    void send_with_length( int socket, unsigned char* buf, int bufLen,
                           bool cascade );
    void send_msg( int socket, HostID id, XWRelayMsg msg, XWREASON why,
                   bool cascade );

    void RecordSent( int nBytes, int socket ) {
        m_totalSent += nBytes;
    }

    void pushConnectEvent( int socket, HostID srcID,
                           int nPlayersH, int nPlayersT,
                           int seed );
    void pushReconnectEvent( int socket, HostID srcID,
                             int nPlayersH, int nPlayersT,
                             int seed );
    void pushHeartbeatEvent( HostID id, int socket );
    void pushHeartFailedEvent( int socket );
    
    void pushForwardEvent( HostID src, HostID dest, unsigned char* buf, 
                           int buflen );
    void pushDestBadEvent();
    void pushLastSocketGoneEvent();
    void pushRemoveSocketEvent( int socket );
    void pushNotifyDisconEvent( int socket, XWREASON why );

    void handleEvents();

    void sendResponse( const CRefEvent* evt, bool initial );
    void increasePlayerCounts( const CRefEvent* evt );
    void reducePlayerCounts( int socket );
    void checkCounts( const CRefEvent* evt );

    void setAllConnectedTimer();
    void cancelAllConnectedTimer();

    void forward( const CRefEvent* evt );
    void checkFromServer( const CRefEvent* evt );
    void notifyOthers( int socket, XWRelayMsg msg, XWREASON why );

    void disconnectSockets( int socket, XWREASON why );
    void noteHeartbeat(const CRefEvent* evt);
    void notifyDisconn(const CRefEvent* evt);
    void removeSocket( int socket );
    void sendAllHere( void );
    bool SeedBelongs( int gameSeed );
    bool SeedsBelong( const char* connName );
    void assignConnName( void );
    void assignHostIds( void );
    
    HostID nextHostID() { return ++m_nextHostID; }

    time_t GetStarttime( void ) { return m_starttime; }

    bool notInUse(void) { return m_cookieID == 0; }

    /* timer callback */
    static void s_checkAllConnected( void* closure );

    vector<HostRec> m_sockets;
    bool m_gameFull;         /* once we've filled up, no more *new*
                                connections ever */
    int m_heatbeat;           /* might change per carrier or something. */
    string m_cookie;            /* cookie used for initial connections */
    string m_connName;          /* globally unique name */
    CookieID m_cookieID;        /* Unique among current games on this server */
    int m_totalSent;

    /* Guard the event queue.  Only one thread at a time can post to the
       queue, but once in a thread can post new events while processing
       current ones. */
/*     pthread_mutex_t    m_EventsMutex; */


    XW_RELAY_STATE     m_curState;
    XW_RELAY_STATE     m_nextState;
    deque<CRefEvent>   m_eventQueue;

    HostID m_nextHostID;
    int m_nPlayersSought;
    int m_nPlayersHere;

    time_t m_starttime;

    pthread_mutex_t m_mutex;

    pthread_t m_locking_thread; /* for debugging only */
}; /* CookieRef */

#endif
