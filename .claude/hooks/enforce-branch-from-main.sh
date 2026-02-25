#!/usr/bin/env bash
# Blocks git branch-creation commands unless the current branch is main.
# Wired as a PreToolUse hook for the Bash tool in .claude/settings.json.
# Pure bash/grep — no python3 or jq required.

INPUT=$(cat)

# The Claude Code JSON envelope embeds the command as a string literal,
# so we can just grep the raw payload for branch-creation patterns.
if ! printf '%s' "$INPUT" | grep -qE 'git (checkout -b|switch -c)'; then
    exit 0
fi

CURRENT=$(git branch --show-current 2>/dev/null || echo "unknown")

if [ "$CURRENT" != "main" ]; then
    echo "BLOCKED: new branches must start from main."
    echo "Current branch: $CURRENT"
    echo ""
    echo "Run first:"
    echo "  git checkout main"
    echo "  git pull origin main"
    echo ""
    echo "Then retry your branch command."
    exit 2
fi

exit 0
