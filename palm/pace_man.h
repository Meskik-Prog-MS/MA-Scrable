/* -*-mode: C; fill-column: 77; c-basic-offset: 4; -*- */

#ifndef _ARMFUNC_HC_H_
#define _ARMFUNC_HC_H_

#ifdef XW_TARGET_PNO

#include "pnostate.h"

extern Int16 StrPrintF( Char* s, const Char* formatStr, ... );
extern Int16 StrVPrintF( Char* s, const Char* formatStr, _Palm_va_list arg );
extern void TimSecondsToDateTime( UInt32 seconds, DateTimeType* dateTimeP );


/* #define FUNC_HEADER(n) printf( "%s called", #n ); */
/* #define FUNC_TAIL(n)   printf( "%s done", #n ); */
#define FUNC_HEADER(n)
#define FUNC_TAIL(n)

#define ADD_TO_STACK4(val,offset) \
    (unsigned char)(((unsigned long)val) >> 24), \
    (unsigned char)(((unsigned long)val) >> 16), \
    (unsigned char)(((unsigned long)val) >> 8), \
    (unsigned char)(((unsigned long)val) >> 0)

#define ADD_TO_STACK2(val,offset) \
    (unsigned char)(((unsigned short)val) >> 8), \
    (unsigned char)(((unsigned short)val) >> 0)

#define ADD_TO_STACK1(val,offset) \
    (unsigned char)(0), \
    (unsigned char)((val) >> 0)

PNOState* getStorageLoc();
#define GET_CALLBACK_STATE() getStorageLoc()

#define crash()  *(int*)1L = 1

unsigned long Byte_Swap32( unsigned long l );
unsigned short Byte_Swap16( unsigned short l );

#endif
#endif
