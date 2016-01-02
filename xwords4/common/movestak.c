/* -*- compile-command: "cd ../linux && make -j5 MEMDEBUG=TRUE"; -*- */
/* 
 * Copyright 2001-2015 by Eric House (xwords@eehouse.org). All rights
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

//#include "modelp.h"

#include "mempool.h"
#include "xwstream.h"
#include "movestak.h"
#include "memstream.h"
#include "strutils.h"
#include "dbgutil.h"

/* HASH_STREAM: It should be possible to hash the move stack by simply hashing
   the stream from the beginning to the top of the undo stack (excluding
   what's in the redo area), avoiding iterating over it and doing a ton of
   bitwise operations to read it into entries.  But I don't currently seem to
   have a XWStreamPos that corresponds to the undo-top and so I can't figure
   out the length.  Hashing that includes the redo part of the stack doesn't
   work once there's been undo activity.  (Not sure why...)  */

#ifdef CPLUS
extern "C" {
#endif

struct StackCtxt {
    VTableMgr* vtmgr;

    XWStreamCtxt* data;

    XWStreamPos   top;

    XWStreamPos cachedPos;

    XP_U16 cacheNext;
    XP_U16 nEntries;
    XP_U16 bitsPerTile;
    XP_U16 highWaterMark;

    DIRTY_SLOT
    MPSLOT
};

void
stack_init( StackCtxt* stack )
{
    stack->nEntries = stack->highWaterMark = 0;
    stack->top = START_OF_STREAM;

    /* I see little point in freeing or shrinking stack->data.  It'll get
       shrunk to fit as soon as we serialize/deserialize anyway. */
} /* stack_init */

#ifdef STREAM_VERS_HASHSTREAM
XP_U32
stack_getHash( const StackCtxt* stack, XP_Bool correct )
{
    XP_U32 hash = 0;
    if ( !!stack->data ) {
        hash = stream_getHash( stack->data, stack->top, correct );
    }
    return hash;
} /* stack_getHash */
#endif

void
stack_setBitsPerTile( StackCtxt* stack, XP_U16 bitsPerTile )
{
    XP_ASSERT( !!stack );
    XP_ASSERT( bitsPerTile == 5 || bitsPerTile == 6 );
    stack->bitsPerTile = bitsPerTile;
}

StackCtxt*
stack_make( MPFORMAL VTableMgr* vtmgr )
{
    StackCtxt* result = (StackCtxt*)XP_MALLOC( mpool, sizeof( *result ) );
    if ( !!result ) {
        XP_MEMSET( result, 0, sizeof(*result) );
        MPASSIGN(result->mpool, mpool);
        result->vtmgr = vtmgr;
    }

    return result;
} /* stack_make */

void
stack_destroy( StackCtxt* stack )
{
    if ( !!stack->data ) {
        stream_destroy( stack->data );
    }
    /* Ok to close with a dirty stack, e.g. if not saving a deleted game */
    // ASSERT_NOT_DIRTY( stack );
    XP_FREE( stack->mpool, stack );
} /* stack_destroy */

void
stack_loadFromStream( StackCtxt* stack, XWStreamCtxt* stream )
{
    XP_U16 nBytes = stream_getU16( stream );

    if ( nBytes > 0 ) {
        stack->highWaterMark = stream_getU16( stream );
        stack->nEntries = stream_getU16( stream );
        stack->top = stream_getU32( stream );
        stack->data = mem_stream_make( MPPARM(stack->mpool) stack->vtmgr,
                                       NULL, 0,
                                       (MemStreamCloseCallback)NULL );

        stream_getFromStream( stack->data, stream, nBytes );
    } else {
        XP_ASSERT( stack->nEntries == 0 );
        XP_ASSERT( stack->top == 0 );
    }
    CLEAR_DIRTY( stack );
} /* stack_makeFromStream */

void
stack_writeToStream( const StackCtxt* stack, XWStreamCtxt* stream )
{
    XP_U16 nBytes;
    XWStreamCtxt* data = stack->data;
    XWStreamPos oldPos = START_OF_STREAM;

    if ( !!data ) {
        oldPos = stream_setPos( data, POS_READ, START_OF_STREAM );
        nBytes = stream_getSize( data );
    } else {
        nBytes = 0;
    }

    stream_putU16( stream, nBytes );

    if ( nBytes > 0 ) {
        stream_putU16( stream, stack->highWaterMark );
        stream_putU16( stream, stack->nEntries );
        stream_putU32( stream, stack->top );

        stream_getFromStream( stream, data, nBytes );
        /* in case it'll be used further */
        (void)stream_setPos( data, POS_READ, oldPos );
    }
    CLEAR_DIRTY( stack );
} /* stack_writeToStream */

StackCtxt*
stack_copy( const StackCtxt* stack )
{
    StackCtxt* newStack = NULL;
    XWStreamCtxt* stream = mem_stream_make( MPPARM(stack->mpool)
                                            stack->vtmgr, NULL, 0, NULL );
    stack_writeToStream( stack, stream );

    newStack = stack_make( MPPARM(stack->mpool) stack->vtmgr );
    stack_loadFromStream( newStack, stream );
    stack_setBitsPerTile( newStack, stack->bitsPerTile );
    stream_destroy( stream );
    return newStack;
}

static void
pushEntryImpl( StackCtxt* stack, const StackEntry* entry )
{
    XP_U16 ii, bitsPerTile;
    XWStreamPos oldLoc;
    XP_U16 nTiles = entry->u.move.moveInfo.nTiles;
    XWStreamCtxt* stream = stack->data;

    if ( !stream ) {
        stream = mem_stream_make( MPPARM(stack->mpool) stack->vtmgr, NULL, 0,
                                  (MemStreamCloseCallback)NULL );
        stack->data = stream;
    }

    oldLoc = stream_setPos( stream, POS_WRITE, stack->top );

    stream_putBits( stream, 2, entry->moveType );
    stream_putBits( stream, 2, entry->playerNum );

    switch( entry->moveType ) {
    case MOVE_TYPE:
    case PHONY_TYPE:

        stream_putBits( stream, NTILES_NBITS, nTiles );
        stream_putBits( stream, 5, entry->u.move.moveInfo.commonCoord );
        stream_putBits( stream, 1, entry->u.move.moveInfo.isHorizontal );
        bitsPerTile = stack->bitsPerTile;
        XP_ASSERT( bitsPerTile == 5 || bitsPerTile == 6 );
        for ( ii = 0; ii < nTiles; ++ii ) {
            Tile tile;
            stream_putBits( stream, 5, 
                            entry->u.move.moveInfo.tiles[ii].varCoord );

            tile = entry->u.move.moveInfo.tiles[ii].tile;
            stream_putBits( stream, bitsPerTile, tile & TILE_VALUE_MASK );
            stream_putBits( stream, 1, (tile & TILE_BLANK_BIT) != 0 );
        }
        if ( entry->moveType == MOVE_TYPE ) {
            traySetToStream( stream, &entry->u.move.newTiles );
        }
        break;

    case ASSIGN_TYPE:
        traySetToStream( stream, &entry->u.assign.tiles );
        break;

    case TRADE_TYPE:
        XP_ASSERT( entry->u.trade.newTiles.nTiles
                   == entry->u.trade.oldTiles.nTiles );
        traySetToStream( stream, &entry->u.trade.oldTiles );
        /* could save three bits per trade by just writing the tiles of the
           second guy */
        traySetToStream( stream, &entry->u.trade.newTiles );
        break;
    }

    ++stack->nEntries;
    stack->highWaterMark = stack->nEntries;
    stack->top = stream_setPos( stream, POS_WRITE, oldLoc );
#ifdef DEBUG_HASHING
    XP_LOGSTREAM( stack->data );
#endif
    SET_DIRTY( stack );
} /* pushEntryImpl */

static void
pushEntry( StackCtxt* stack, const StackEntry* entry )
{
#ifdef DEBUG_HASHING
    XP_Bool correct = XP_TRUE;
    XP_U32 origHash = stack_getHash( stack, correct );
#endif

    pushEntryImpl( stack, entry );

#ifdef DEBUG_HASHING
    XP_U32 newHash = stack_getHash( stack, XP_TRUE );
    StackEntry lastEntry;
    if ( stack_popEntry( stack, &lastEntry ) ) {
        XP_ASSERT( origHash == stack_getHash( stack, correct ) );
        pushEntryImpl( stack, &lastEntry );
        XP_ASSERT( newHash == stack_getHash( stack, correct ) );
        XP_LOGF( "%s: all ok", __func__ );
    }
#endif
}

static void
readEntry( const StackCtxt* stack, StackEntry* entry )
{
    XP_U16 nTiles, ii, bitsPerTile;
    XWStreamCtxt* stream = stack->data;

    entry->moveType = (StackMoveType)stream_getBits( stream, 2 );
    entry->playerNum = (XP_U8)stream_getBits( stream, 2 );

    switch( entry->moveType ) {

    case MOVE_TYPE:
    case PHONY_TYPE:
        nTiles = entry->u.move.moveInfo.nTiles = 
            (XP_U8)stream_getBits( stream, NTILES_NBITS );
        XP_ASSERT( nTiles <= MAX_TRAY_TILES );
        entry->u.move.moveInfo.commonCoord = (XP_U8)stream_getBits(stream, 5);
        entry->u.move.moveInfo.isHorizontal = (XP_U8)stream_getBits(stream, 1);
        bitsPerTile = stack->bitsPerTile;
        XP_ASSERT( bitsPerTile == 5 || bitsPerTile == 6 );
        for ( ii = 0; ii < nTiles; ++ii ) {
            Tile tile;
            entry->u.move.moveInfo.tiles[ii].varCoord = 
                (XP_U8)stream_getBits(stream, 5);
            tile = (Tile)stream_getBits( stream, bitsPerTile );
            if ( 0 != stream_getBits( stream, 1 ) ) {
                tile |= TILE_BLANK_BIT;
            }
            entry->u.move.moveInfo.tiles[ii].tile = tile;
        }

        if ( entry->moveType == MOVE_TYPE ) {
            traySetFromStream( stream, &entry->u.move.newTiles );
        }
        break;

    case ASSIGN_TYPE:
        traySetFromStream( stream, &entry->u.assign.tiles );
        break;

    case TRADE_TYPE:
        traySetFromStream( stream, &entry->u.trade.oldTiles );
        traySetFromStream( stream, &entry->u.trade.newTiles );
        XP_ASSERT( entry->u.trade.newTiles.nTiles
                   == entry->u.trade.oldTiles.nTiles );
        break;
    }

} /* readEntry */

void
stack_addMove( StackCtxt* stack, XP_U16 turn, const MoveInfo* moveInfo, 
               const TrayTileSet* newTiles )
{
    StackEntry move;

    move.playerNum = (XP_U8)turn;
    move.moveType = MOVE_TYPE;

    XP_MEMCPY( &move.u.move.moveInfo, moveInfo, sizeof(move.u.move.moveInfo));
    move.u.move.newTiles = *newTiles;

    pushEntry( stack, &move );
} /* stack_addMove */

void
stack_addPhony( StackCtxt* stack, XP_U16 turn, const MoveInfo* moveInfo )
{
    StackEntry move;

    move.playerNum = (XP_U8)turn;
    move.moveType = PHONY_TYPE;

    XP_MEMCPY( &move.u.phony.moveInfo, moveInfo, 
               sizeof(move.u.phony.moveInfo));

    pushEntry( stack, &move );
} /* stack_addPhony */

void
stack_addTrade( StackCtxt* stack, XP_U16 turn, 
                const TrayTileSet* oldTiles, const TrayTileSet* newTiles )
{
    StackEntry move;

    move.playerNum = (XP_U8)turn;
    move.moveType = TRADE_TYPE;

    move.u.trade.oldTiles = *oldTiles;
    move.u.trade.newTiles = *newTiles;

    pushEntry( stack, &move );
} /* stack_addTrade */

void
stack_addAssign( StackCtxt* stack, XP_U16 turn, const TrayTileSet* tiles )
{
    StackEntry move;

    move.playerNum = (XP_U8)turn;
    move.moveType = ASSIGN_TYPE;

    move.u.assign.tiles = *tiles;

    pushEntry( stack, &move );
} /* stack_addAssign */

static XP_Bool
setCacheReadyFor( StackCtxt* stack, XP_U16 nn )
{
    XP_U16 ii;
    
    stream_setPos( stack->data, POS_READ, START_OF_STREAM );
    for ( ii = 0; ii < nn; ++ii ) {
        StackEntry dummy;
        readEntry( stack, &dummy );
    }

    stack->cacheNext = nn;
    stack->cachedPos = stream_getPos( stack->data, POS_READ );

    return XP_TRUE;
} /* setCacheReadyFor */

XP_U16
stack_getNEntries( const StackCtxt* stack )
{
    return stack->nEntries;
} /* stack_getNEntries */

XP_Bool
stack_getNthEntry( StackCtxt* stack, XP_U16 nn, StackEntry* entry )
{
    XP_Bool found;

    if ( nn >= stack->nEntries ) {
        found = XP_FALSE;
    } else if ( stack->cacheNext != nn ) {
        XP_ASSERT( !!stack->data );
        found = setCacheReadyFor( stack, nn );
        XP_ASSERT( stack->cacheNext == nn );
    } else {
        found = XP_TRUE;
    }

    if ( found ) {
        XWStreamPos oldPos = stream_setPos( stack->data, POS_READ, 
                                            stack->cachedPos );

        readEntry( stack, entry );
        entry->moveNum = (XP_U8)nn;

        stack->cachedPos = stream_setPos( stack->data, POS_READ, oldPos );
        ++stack->cacheNext;
    }

    return found;
} /* stack_getNthEntry */

XP_Bool
stack_popEntry( StackCtxt* stack, StackEntry* entry )
{
    XP_U16 nn = stack->nEntries - 1;
    XP_Bool found = stack_getNthEntry( stack, nn, entry );
    if ( found ) {
        stack->nEntries = nn;

        setCacheReadyFor( stack, nn ); /* set cachedPos by side-effect */
        stack->top = stack->cachedPos;
    }
#ifdef DEBUG_HASHING
    XP_LOGSTREAM( stack->data );
#endif
    return found;
} /* stack_popEntry */

XP_Bool
stack_redo( StackCtxt* stack, StackEntry* entry )
{
    XP_Bool canRedo = (stack->nEntries + 1) <= stack->highWaterMark;
    if ( canRedo ) {
        ++stack->nEntries;
        if ( NULL != entry ) {
            stack_getNthEntry( stack, stack->nEntries-1, entry );
        }
        setCacheReadyFor( stack, stack->nEntries );
        stack->top = stack->cachedPos;
    }
    return canRedo;
} /* stack_redo */

#ifdef CPLUS
}
#endif
