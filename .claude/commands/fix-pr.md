Review PR #$ARGUMENTS and implement any reasonable fixes or improvements.
First, ensure you're on the main branch and it's up to date with the origin
Next, use `gh pr view $ARGUMENTS --json headRefName` to find the branch.
Checkout that branch, pull latest, perform a review, suggest fixes, make the fixes, commit, and push.
Do not create a new branch.