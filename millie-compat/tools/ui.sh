#!/bin/zsh
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
adb shell cat /sdcard/ui.xml | tr '<' '\n' | grep -E 'text="[^"]+"|clickable="true"' | grep -o -E 'text="[^"]*"|bounds="[^"]*"|checked="true"' | paste -s -d' ' - | sed -E 's/ text=/\n text=/g' | grep -v 'text="" bounds' | head -60
