#!/bin/sh

cd $(dirname $0)
cd ../../

cat <<EOF > android/XWords4/src/org/eehouse/android/xw4/SvnVersion.java
// auto-generated; do not edit
package org.eehouse.android.xw4;
class SvnVersion {
    public static final String VERS = "$(svnversion .)";
}
EOF

# touch the file that depends on VERS.java
touch android/XWords4/src/org/eehouse/android/xw4/Utils.java
