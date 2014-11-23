#!/bin/sh

set -u -e

CONNNAME=""

usage() {
    echo "usage: %0 --long|--short logfile*"
    exit 1
}

if [ "--long" = $1 ]; then
    LONG=1
elif [ "--short" = $1 ]; then
    LONG=""
else
    usage
fi

shift

while [ $# -ge 1 ]; do
    LOG=$1
    while read LINE; do
        case "$LINE" in
            *got_connect_cmd:\ connName* )
                CONNNAME="$(echo $LINE | sed 's,^.*connName: "\(.*\)" .*$,\1,')"
                ;;
            *got_connect_cmd:\ set\ hostid* )
                HOSTID=$(echo $LINE | sed 's,^.*set hostid: \(.\)$,\1,')
                ;;
            *getChannelSeed:\ made\ seed:\ cno:\ *)
                SEED=$(echo $LINE | sed 's,^.*getChannelSeed: made seed: cno: \(.*\)|.*$,\1,')
                ;;
        esac
    done < $LOG
    if [ -z "${CONNNAME}" ]; then
        echo "CONNNAME not found in $LOG" >&2
    elif [ -z "${HOSTID}" ]; then
        echo "HOSTID not found in $LOG" >&2
    elif [ "${HOSTID}" -eq 0 ]; then
        echo "HOSTID 0 in $LOG; try later" >&2
    elif [ -n "$LONG" -a -z "${SEED}" ]; then
        echo "SEED not found in $LOG" >&2
    else
        RESULT=${CONNNAME}/${HOSTID}
        if [ -n "$LONG" ]; then
            RESULT="${RESULT}/${SEED}"
            fi
        echo $RESULT
    fi

    shift
done
