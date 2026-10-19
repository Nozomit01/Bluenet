#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK}"
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 21 installation}"
ndk_path="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/27.2.12479018}"
llvm_bin="$ndk_path/toolchains/llvm/prebuilt/linux-x86_64/bin"
./proxy/build-android.sh
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$llvm_bin/aarch64-linux-android26-clang"
export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="$llvm_bin/armv7a-linux-androideabi26-clang"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$llvm_bin/x86_64-linux-android26-clang"
# Compatible with both traditional Android pages and Android 15+ 16 KiB devices.
export RUSTFLAGS="${RUSTFLAGS:-} -C link-arg=-Wl,-z,max-page-size=16384"
for pair in 'aarch64-linux-android:arm64-v8a' 'armv7-linux-androideabi:armeabi-v7a' 'x86_64-linux-android:x86_64'; do
    target_name="${pair%%:*}"
    abi_name="${pair##*:}"
    cargo build --manifest-path native/Cargo.toml --locked --release --lib --target "$target_name"
    mkdir -p "app/src/main/jniLibs/$abi_name"
    cp "native/target/$target_name/release/libbluenet.so" "app/src/main/jniLibs/$abi_name/"
done
python3 create-signing-key.py
./gradlew --no-daemon :app:assembleRelease :app:lintRelease
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk dist/BlueNetwork-0.6.0.apk
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose dist/BlueNetwork-0.6.0.apk
"$ANDROID_HOME/build-tools/35.0.0/zipalign" -c -P 16 4 dist/BlueNetwork-0.6.0.apk
sha256sum dist/BlueNetwork-0.6.0.apk > dist/BlueNetwork-0.6.0.apk.sha256
