/* -*-mode: C; fill-column: 78; c-basic-offset: 4; -*- */

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

/* Runs a single thread polling for activity on any of the sockets in its
 * list.  When there is activity, removes the socket from that list and adds
 * it ot a queue of socket ready for reading.  Starts up a bunch of threads
 * waiting on that queue.  When a new socket appears, a thread grabs the
 * socket, reads from it, passes the buffer on, and puts the socket back in
 * the list being read from in our main thread.
 */

#include <vector>
#include <deque>
#include <set>

using namespace std;

class XWThreadPool {

 public:
    typedef enum { STYPE_UNKNOWN, STYPE_GAME, STYPE_PROXY } SockType;
    typedef struct _SockInfo {
        SockType m_type;
        in_addr m_addr;
    } SockInfo;

    static XWThreadPool* GetTPool();
    typedef bool (*packet_func)( unsigned char* buf, int bufLen, int socket,
                                 in_addr& addr );
    typedef void (*kill_func)( int socket );

    XWThreadPool();
    ~XWThreadPool();

    void Setup( int nThreads, packet_func pFunc, kill_func kFunc );
    void Stop();

    /* Add to set being listened on */
    void AddSocket( int socket, SockType stype, in_addr& fromAddr );
    /* remove from tpool altogether, and close */
    void CloseSocket( int socket );

    void EnqueueKill( int socket, const char* const why );

 private:
    typedef enum { Q_READ, Q_KILL } QAction;
    typedef struct { QAction m_act; int m_socket; SockInfo m_info; } QueuePr;

    /* Remove from set being listened on */
    bool RemoveSocket( int socket );

    void enqueue( int socket, QAction act = Q_READ );
    void enqueue( int socket, SockInfo si, QAction act = Q_READ );
    void release_socket_locked( int socket );
    void grab_elem_locked( QueuePr* qpp );
    void print_in_use( void );

    bool get_process_packet( int socket, SockType stype, in_addr& addr );
    void interrupt_poll();

    void* real_tpool_main();
    static void* tpool_main( void* closure );

    void* real_listener();
    static void* listener_main( void* closure );

    /* Sockets main thread listens on */
    vector< pair<int,SockInfo> >m_activeSockets;
    pthread_rwlock_t m_activeSocketsRWLock;

    /* Sockets waiting for a thread to read 'em */
    deque<QueuePr> m_queue;
    set<int> m_sockets_in_use;
    pthread_mutex_t m_queueMutex;
    pthread_cond_t m_queueCondVar;

    /* for self-write pipe hack */
    int m_pipeRead;
    int m_pipeWrite;

    bool m_timeToDie;
    int m_nThreads;
    packet_func m_pFunc;
    kill_func m_kFunc;

    static XWThreadPool* g_instance;
};
