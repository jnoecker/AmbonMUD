# This document has been consolidated into [WEB_CLIENT.md](./WEB_CLIENT.md)

The web client now has complete parity with the text command interface. Every MUD command has a web UI affordance; no gameplay workflow requires the terminal. The original parity audit was a one-off release-gate exercise and all gaps have been resolved.

For the current web client architecture, tech stack, GMCP package coverage, and canvas design decisions, see [`WEB_CLIENT.md`](./WEB_CLIENT.md).

Historical parity tracking is preserved in git history:

```
git log --follow -- docs/WEB_CLIENT_PARITY_REPORT.md
```
