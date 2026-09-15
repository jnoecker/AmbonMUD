#!/bin/zsh
# Robust browse wrapper for AmbonMUD lookbook capture.
# Usage: cap.sh <command...>   — retries through daemon stalls.
BB=${BROWSE_BIN:-$HOME/.claude/skills/gstack/browse/dist/browse}
for i in {1..30}; do
  if out=$($BB --headed "$@" 2>&1); then
    echo "$out"
    exit 0
  fi
  case "$out" in
    *"not responding"*|*"Starting server"*|*"Timeout"*|*"timed out"*) sleep 5 ;;
    *) echo "$out"; exit 1 ;;
  esac
done
echo "FAILED after retries: $out"
exit 1
