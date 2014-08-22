#!/usr/bin/env bash
set -xe

cd RapidFTR-Android
echo no | android create avd --force -n test -t $ANDROID_TARGET --abi $ANDROID_ABI --skin WXGA800
emulator -avd test -no-skin -no-audio -no-window -no-boot-anim &

mvn clean package -DskipTests -P calabash
bundle install -j4 --binstubs

./travis/wait_for_emulator.sh
adb shell input keyevent 82
bundle exec calabash-android run `ls target/*.apk | head -1` -f rerun --out rerun.txt -f pretty
bundle exec calabash-android run `ls target/*.apk | head -1` @rerun.txt -f pretty
