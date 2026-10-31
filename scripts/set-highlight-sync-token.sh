#!/bin/bash
# Puts the GitHub token for highlight sync on the phone over USB. Copy the token first,
# then run this: it reads the clipboard, sends the token only to the phone, and clears
# the clipboard. Nothing is typed, and nothing is stored on this Mac or in this repo.
set -euo pipefail
ADB="/Applications/Light Phone Manager.app/Contents/Resources/platform-tools/adb"
PKG=com.benfridge.lightmark
TOKEN=$(pbpaste | tr -d '[:space:]')
case "$TOKEN" in
  github_pat_*) ;;
  *) echo "The clipboard doesn't hold a GitHub token. Click Copy on the token, then run this again."; exit 1 ;;
esac
"$ADB" shell "run-as $PKG mkdir -p files/shared/.sync"
printf '%s' "$TOKEN" | "$ADB" exec-in run-as $PKG sh -c 'cat > files/shared/.sync/github-token'
sleep 1
SIZE=$("$ADB" shell "run-as $PKG wc -c files/shared/.sync/github-token" | tr -d '\r' | awk '{print $1}')
unset TOKEN
[ "${SIZE:-0}" -gt 20 ] || { echo "The token did not save. Is the phone plugged in?"; exit 1; }
pbcopy < /dev/null
echo "Token saved on the phone. Clipboard cleared."
