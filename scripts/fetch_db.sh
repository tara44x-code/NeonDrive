#!/usr/bin/env bash
# Bundles the game database + all Sega cheat files inside the APK, so games are
# identified and cheats work with NO internet. (CI runs this automatically.)
set -euo pipefail
cd "$(dirname "$0")/.."

SYSTEMS=(
  "GENESIS|Sega - Mega Drive - Genesis"
  "MASTER_SYSTEM|Sega - Master System - Mark III"
  "GAME_GEAR|Sega - Game Gear"
  "SG1000|Sega - SG-1000"
)

tmp="$(mktemp -d)"
git clone -q --depth 1 --filter=blob:none --sparse https://github.com/libretro/libretro-database "$tmp/db"
paths=()
for s in "${SYSTEMS[@]}"; do
  name="${s#*|}"
  paths+=("cht/$name" "metadat/no-intro/$name.dat")
done
git -C "$tmp/db" sparse-checkout set --no-cone "${paths[@]/#//}"

assets="app/src/main/assets"
rm -rf "$assets/db" "$assets/cheats.zip"
mkdir -p "$assets/db" "$tmp/cht"
for s in "${SYSTEMS[@]}"; do
  key="${s%%|*}"; name="${s#*|}"
  cp "$tmp/db/metadat/no-intro/$name.dat" "$assets/db/$key.dat"
  if [ -d "$tmp/db/cht/$name" ]; then
    mkdir -p "$tmp/cht/$key"
    cp "$tmp/db/cht/$name/"*.cht "$tmp/cht/$key/"
  fi
done
( cd "$tmp/cht" && zip -q -r -9 "$OLDPWD/$assets/cheats.zip" . )
date -u +%Y-%m-%d > "$assets/db/version.txt"
rm -rf "$tmp"
echo "Offline database ready:"; ls -la "$assets/db" "$assets/cheats.zip"
