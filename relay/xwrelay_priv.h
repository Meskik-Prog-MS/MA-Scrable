/* -*-mode: C; fill-column: 78; c-basic-offset: 4; -*- */

#ifndef _XWRELAY_PRIV_H_
#define _XWRELAY_PRIV_H_

typedef unsigned short HostID;
typedef unsigned short CookieID; /* stands in for string after connection established */

typedef struct ThreadData {
    int socket;
} ThreadData;

void logf( const char* format, ... );



#endif
