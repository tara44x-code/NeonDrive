#!/usr/bin/env bash
# Fetches the two native dependencies of the vendored LibretroDroid module,
# pinned to the exact commits LibretroDroid 0.14.0 uses.
set -euo pipefail
cd "$(dirname "$0")/.."
CPP=libretrodroid/src/main/cpp

fetch() { # url commit dir
  if [ -f "$3/.pinned" ] && [ "$(cat "$3/.pinned")" = "$2" ]; then return; fi
  mkdir -p "$3"
  git -C "$3" init -q
  git -C "$3" fetch -q --depth 1 "$1" "$2"
  git -C "$3" checkout -q FETCH_HEAD
  echo "$2" > "$3/.pinned"
}

fetch https://github.com/google/oboe b15f5e39c01a7ada306d959e5129620b145fb8b4 "$CPP/oboe"
fetch https://github.com/libretro/libretro-common b0c348ea5543c4d7fb0bc479258aa6988b20c0c9 "$CPP/libretro/libretro-common"
echo "LibretroDroid dependencies ready."
