#!/bin/zsh
# Ensure the browser is logged into the game; auto-relogin if not.
C="${0:A:h}/cap.sh"
URL=${LOOKBOOK_URL:-http://localhost:18543}
NAME=${LOOKBOOK_NAME:-Loremaster}
PASS=${LOOKBOOK_PASS:-lookbook-pass-1}
ok=$($C js "document.querySelector('.canvas-command-input') ? 'IN' : 'OUT'" 2>/dev/null)
[ "$ok" = "IN" ] && exit 0
echo "session lost — relogging in"
$C viewport 1600x1000 >/dev/null 2>&1
$C goto "$URL" >/dev/null
sleep 4
# picker path (saved character) or name-entry path
state=$($C js "document.querySelector('.character-picker-btn') ? 'PICKER' : (document.querySelector('.login-art-input') ? 'NAME' : 'UNKNOWN')" 2>/dev/null)
if [ "$state" = "PICKER" ]; then
  $C js "document.querySelector('.character-picker-btn').click(); 'p'" >/dev/null
  sleep 2
elif [ "$state" = "NAME" ]; then
  $C js "const i=document.querySelector('.login-art-input'); const set=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set; set.call(i,'$NAME'); i.dispatchEvent(new Event('input',{bubbles:true})); i.closest('form').requestSubmit(); 'n'" >/dev/null
  sleep 3
else
  echo "UNKNOWN login state"; exit 1
fi
$C type "$PASS" >/dev/null
$C press Enter >/dev/null
sleep 8
ok=$($C js "document.querySelector('.canvas-command-input') ? 'IN' : 'OUT'" 2>/dev/null)
[ "$ok" = "IN" ] && echo "relogged in" || { echo "RELOGIN FAILED ($ok)"; exit 1; }
