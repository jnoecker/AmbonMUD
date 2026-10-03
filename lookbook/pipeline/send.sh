#!/bin/zsh
# Send a game command through the web client's command input (React-safe).
CMD="$1"
ESC=${CMD//\'/\\\'}
D="${0:A:h}"
"$D/ensure.sh" || exit 1
"$D/cap.sh" js "(() => { const inp=document.querySelector('.canvas-command-input'); if(!inp) return 'NO INPUT'; const set=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set; set.call(inp,'$ESC'); inp.dispatchEvent(new Event('input',{bubbles:true})); inp.closest('form').requestSubmit(); return 'sent: $ESC'; })()"
