#!/bin/sh

set -u -e

VARIANT=xw4

usage() {
    [ $# -ge 1 ] && echo "Error: $1"
    echo "usage: $(basename $0) [-e] [-d] [-v variant]"
    exit 1
}

DEVICES=""

while [ $# -ge 1 ]; do
    case $1 in
        -e)
            DEVICES="$DEVICES $(adb devices | grep '^emulator' | awk '{print $1}')"
            ;;
        -d)
            DEVICES="$DEVICES $(adb devices | grep -v emulator | grep 'device$' | awk '{print $1}')"
            ;;
        -v)
            shift
            VARIANT=$1
            ;;
        *)
            usage
            ;;
    esac
    shift
done

COUNT=0

for DEVICE in $DEVICES; do
    echo $DEVICE
    adb -s $DEVICE uninstall org.eehouse.android.${VARIANT}
    COUNT=$((COUNT+1))
done

echo "removed from $COUNT devices/emulators"
