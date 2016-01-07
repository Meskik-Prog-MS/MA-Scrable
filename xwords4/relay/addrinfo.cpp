/* -*-mode: C; fill-column: 78; c-basic-offset: 4; -*- */

/* 
 * Copyright 2005-2011 by Eric House (xwords@eehouse.org).  All rights
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

#include <assert.h>
#include <string.h>
#include <time.h>

#include "addrinfo.h"
#include "xwrelay_priv.h"
#include "tpool.h"
#include "udpager.h"

// static uint32_t s_prevCreated = 0L;

void
AddrInfo::construct( int sock, const AddrUnion* saddr, bool isTCP ) 
{
    memset( this, 0, sizeof(*this) ); 
        
    struct timespec tp;
    clock_gettime( CLOCK_MONOTONIC, &tp );
    /* convert to milliseconds */
    m_createdMillis = (tp.tv_sec * 1000) + (tp.tv_nsec / 1000000);
    /* assert( m_created >= s_prevCreated ); */
    /* s_prevCreated = m_created; */

    m_socket = sock;
    m_isTCP = isTCP;
    memcpy( &m_saddr, saddr, sizeof(m_saddr) );
    m_isValid = true;
}

bool
AddrInfo::isCurrent() const 
{ 
    bool result;
    if ( isTCP() ) {
        result = XWThreadPool::GetTPool()->IsCurrent( this ); 
    } else {
        result = UDPAger::Get()->IsCurrent( this );
    }
    return result;
}

bool
AddrInfo::equals( const AddrInfo& other ) const
{ 
    bool equal = other.isTCP() == isTCP();
    if ( equal ) {
        if ( isTCP() ) {
            equal = m_socket == other.m_socket;
            if ( equal && created() != other.created() ) {
                logf( XW_LOGINFO, "%s: rejecting on time mismatch (%lx vs %lx)", 
                      __func__, created(), other.created() );
                equal = false;
            }
        } else {
            // assert( m_socket == other.m_socket ); /* both same UDP socket */
            /* what does equal mean on udp addresses?  Same host, or same host AND game */
            equal = m_clientToken == other.m_clientToken
                && 0 == memcmp( &m_saddr, &other.m_saddr, sizeof(m_saddr) );
        }
    }
    return equal;
}

