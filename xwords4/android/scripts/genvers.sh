#!/bin/sh

set -e -u

DIR=$1
VARIANT=$2
CLIENT_VERS_RELAY=$3
CHAT_SUPPORTED=$4
THUMBNAIL_SUPPORTED=$5

cd $(dirname $0)
cd ../../

GITVERSION=$(scripts/gitversion.sh)

# TODO: deal with case where there's no hash available -- exported
# code maybe?  Better: gitversion.sh does that.

cat <<EOF > android/${DIR}/res/values/git_string.xml
<?xml version="1.0" encoding="utf-8"?>
<!-- auto-generated; do not edit -->

<resources>
    <string name="git_rev">$GITVERSION</string>
</resources>
EOF

# Eventually this should pick up a tag if we're at one.  That'll be
# the way to mark a release
SHORTVERS="$(git describe --always $GITVERSION 2>/dev/null || echo unknown)"

cat <<EOF > android/${DIR}/src/org/eehouse/android/${VARIANT}/BuildConstants.java
// auto-generated; do not edit
package org.eehouse.android.${VARIANT};
class BuildConstants {
    public static final String GIT_REV = "$SHORTVERS";
    public static final short CLIENT_VERS_RELAY = $CLIENT_VERS_RELAY;
    public static final boolean CHAT_SUPPORTED = $CHAT_SUPPORTED;
    public static final boolean THUMBNAIL_SUPPORTED = $THUMBNAIL_SUPPORTED;
    public static final String BUILD_STAMP = "$(date)";
}
EOF

# touch the files that depend on git_string.xml.  (I'm not sure that
# this list is complete or if ant and java always get dependencies
# right.  Clean builds are the safest.)
touch android/${DIR}/res/xml/xwprefs.xml 
touch android/${DIR}/gen/org/eehouse/android/${VARIANT}/R.java
touch android/${DIR}/src/org/eehouse/android/${VARIANT}/Utils.java
