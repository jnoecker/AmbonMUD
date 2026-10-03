#!/bin/zsh
# panel.sh <button-label> <screenshot-path> [settle-seconds]
# Clicks the button with matching aria-label/text, screenshots, then Escape.
LABEL="$1"; OUT="$2"; SETTLE="${3:-2}"
C="${0:A:h}/cap.sh"
$C js "(() => { const btns=[...document.querySelectorAll('button')]; const b=btns.find(x => (x.getAttribute('aria-label')||x.textContent||'').trim().startsWith('$LABEL')); if(!b) return 'NO BUTTON: $LABEL'; b.click(); return 'clicked $LABEL'; })()"
sleep $SETTLE
$C screenshot "$OUT"
$C press Escape
sleep 0.5
