#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to NDK r27c}"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17}"
export PATH="$JAVA_HOME/bin:$PATH"
version=v0.0.0-20251126181937-5c265dc024c4
go install "golang.org/x/mobile/cmd/gomobile@$version"
go install "golang.org/x/mobile/cmd/gobind@$version"
export PATH="$(go env GOPATH)/bin:$PATH"
cd "$(dirname "$0")/ridedaemon"
go test -race ./hud/...
mkdir -p ../../app/libs
gomobile bind -target=android/arm64,android/arm -androidapi=24 -o ../../app/libs/ridedaemon.aar ./hud/api
