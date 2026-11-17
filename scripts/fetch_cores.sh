#!/usr/bin/env bash
# Downloads the Genesis Plus GX libretro cores (normal + widescreen) for each ABI
# into app/src/main/jniLibs so they ship inside the APK.
# Genesis Plus GX is licensed for NON-COMMERCIAL use only -> this app must stay free.
set -euo pipefail
cd "$(dirname "$0")/.."

BASE="https://buildbot.libretro.com/nightly/android/latest"
CORES=("genesis_plus_gx" "genesis_plus_gx_wide")
ABIS=("arm64-v8a" "armeabi-v7a")

tmp="$(mktemp -d)"
for abi in "${ABIS[@]}"; do
  out="app/src/main/jniLibs/$abi"
  mkdir -p "$out"
  for core in "${CORES[@]}"; do
    name="${core}_libretro_android.so"
    echo ">> $abi / $name"
    curl -fL --retry 3 -o "$tmp/$name.zip" "$BASE/$abi/$name.zip"
    unzip -o -q "$tmp/$name.zip" -d "$tmp/$abi"
    # The "lib" prefix is required so Android extracts it to nativeLibraryDir.
    mv "$tmp/$abi/$name" "$out/lib$name"
  done
done
rm -rf "$tmp"
echo "Cores ready."
