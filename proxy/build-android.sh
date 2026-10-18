#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_HOME:?}"; : "${GO_BIN:=go}"
llvm="$ANDROID_HOME/ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/bin"
export CGO_ENABLED=1 GOOS=android GOMAXPROCS=3
for triple in 'arm64:arm64-v8a:aarch64' 'arm:armeabi-v7a:armv7a' 'amd64:x86_64:x86_64'; do
 IFS=: read -r goarch abi clangarch <<< "$triple"
 export GOARCH="$goarch" CC="$llvm/${clangarch}-linux-android26-clang"
 if [ "$goarch" = arm ]; then export CC="$llvm/armv7a-linux-androideabi26-clang" GOARM=7; else unset GOARM; fi
 mkdir -p "../app/src/main/jniLibs/$abi"
 "$GO_BIN" build -mod=readonly -trimpath -buildmode=pie -ldflags='-s -w -checklinkname=0 -linkmode=external -extldflags=-Wl,-z,max-page-size=16384' -o "../app/src/main/jniLibs/$abi/libbluenet_proxy.so" .
done
