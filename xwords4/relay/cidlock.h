/* -*-mode: C; fill-column: 78; c-basic-offset: 4; -*- */
/* 
 * Copyright 2005-2011 by Eric House (xwords@eehouse.org).  All rights
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


#ifndef _CIDLOCK_H_
#define _CIDLOCK_H_

#include <map>
#include "xwrelay.h"
#include "cref.h"

using namespace std;

class CidInfo {
 public:
    CidInfo( CookieID cid )
        :m_cid(cid),
        m_cref(NULL),
        m_owner(NULL),
        m_socket(0) {}

    CookieID GetCid( void ) { return m_cid; }
    CookieRef* GetRef( void ) { return m_cref; }
    pthread_t GetOwner( void ) { return m_owner; }
    int GetSocket( void ) { return m_socket; }

    void SetRef( CookieRef* cref ) { m_cref = cref; }
    void SetOwner( pthread_t owner ) { m_owner = owner; }
    void SetSocket( int socket ) { m_socket = socket; }

 private:
    CookieID m_cid;
    CookieRef* m_cref;
    pthread_t m_owner;
    int m_socket;
};

class CidLock {

 public:
    static CidLock* GetInstance() {
        if ( NULL == s_instance ) {
            s_instance = new CidLock();
        }
        return s_instance;
    }

    CidInfo* Claim( void ) { return Claim(0); }
    CidInfo* Claim( CookieID cid );
    CidInfo* ClaimSocket( int sock );
    void Relinquish( CidInfo* claim );

    void Associate( const CookieRef* cref, int socket );
    void DisAssociate( const CookieRef* cref, int socket );

 private:
    CidLock();
    ~CidLock();
    void print_claimed();

    static CidLock* s_instance;
    map< CookieID, CidInfo*> m_infos;
    pthread_mutex_t m_infos_mutex;
    pthread_cond_t m_infos_condvar;
    int m_nextCID;

}; /* CidLock */

#endif
