# -*- mode: Makefile -*-

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

COMMON_PATH=../../../common
local_C_INCLUDES+= \
	-I$(LOCAL_PATH)/$(COMMON_PATH) \
	-I$(LOCAL_PATH)/../../../relay \

local_LDLIBS += -llog

#local_DEBUG = -DMEM_DEBUG -DDEBUG -DENABLE_LOGGING
local_DEFINES += \
	$(local_DEBUG) \
	-DXWFEATURE_RELAY \
	-DRELAY_NAME_DEFAULT=\"10.0.2.2\" \
	-DRELAY_PORT_DEFAULT=10999 \
	-DXWFEATURE_TURNCHANGENOTIFY \
	-DXWFEATURE_SEARCHLIMIT \
	-DKEYBOARD_NAV \
	-DKEY_SUPPORT \
	-DPOINTER_SUPPORT \
	-DNODE_CAN_4 \
	-D__LITTLE_ENDIAN \

local_SRC_FILES +=         \
	xwjni.c                \
	utilwrapper.c          \
	drawwrapper.c          \
	xportwrapper.c         \
	anddict.c              \
	andutils.c             \


COMMON_PATH=../../../common
common_SRC_FILES +=        \
	$(COMMON_PATH)/boarddrw.c   \
	$(COMMON_PATH)/scorebdp.c   \
	$(COMMON_PATH)/dragdrpp.c   \
	$(COMMON_PATH)/pool.c       \
	$(COMMON_PATH)/tray.c       \
	$(COMMON_PATH)/dictnry.c    \
	$(COMMON_PATH)/mscore.c     \
	$(COMMON_PATH)/vtabmgr.c    \
	$(COMMON_PATH)/strutils.c   \
	$(COMMON_PATH)/engine.c     \
	$(COMMON_PATH)/board.c      \
	$(COMMON_PATH)/mempool.c    \
	$(COMMON_PATH)/game.c       \
	$(COMMON_PATH)/server.c     \
	$(COMMON_PATH)/model.c      \
	$(COMMON_PATH)/comms.c      \
	$(COMMON_PATH)/memstream.c  \
	$(COMMON_PATH)/movestak.c   \


LOCAL_CFLAGS+=$(local_C_INCLUDES) $(local_DEFINES)
LOCAL_SRC_FILES := $(linux_SRC_FILES) $(local_SRC_FILES) $(common_SRC_FILES)
LOCAL_MODULE    := xwjni
LOCAL_LDLIBS := -L${SYSROOT}/usr/lib -llog -lz 

include $(BUILD_SHARED_LIBRARY)


