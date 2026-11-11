#!/usr/bin/env bash
# Builds the Linux .deb inside a Linux container (see `just deb-docker`).
# Use an Ubuntu 22.04 (jammy) image: newer dpkg/tar fail under x86 emulation on Apple silicon,
# and building on an older release keeps the package installable on more systems.
# Expects the repo mounted at /src; works on a copy so the host's build folders are untouched.
set -euo pipefail
apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq fakeroot binutils rsync >/dev/null 2>&1
rsync -a --delete --exclude build --exclude .gradle --exclude .kotlin --exclude dist /src/ /work/
cd /work
# local.properties may hold the Mac's Android SDK path; the desktop build doesn't need it.
if [ -f local.properties ]; then grep -v '^sdk.dir=' local.properties > local.properties.tmp || true; mv local.properties.tmp local.properties; fi
if ! ./gradlew --no-daemon --no-configuration-cache -q :desktopApp:packageDeb -Pcompose.desktop.verbose=true > /tmp/gradle.log 2>&1; then
    echo "---- packaging failed ----"; grep -iE "tar:|dpkg-deb|error" /tmp/gradle.log | head -30; exit 1
fi
mkdir -p /src/dist
cp desktopApp/build/compose/binaries/main/deb/*.deb /src/dist/
arch=$(dpkg --print-architecture)
for f in /src/dist/calendar-app_*.deb; do mv "$f" "/src/dist/Calendar-linux-${arch}.deb"; done
ls -la /src/dist
