#!/bin/sh

echo $0

# create local.properties for 1.6 sdk (target id 4).  Use 'android
# list targets' to get the full set.
android update project --path . --target android-7

echo "local.properties looks like this:"
echo ""
cat local.properties
echo ""
exit 0
