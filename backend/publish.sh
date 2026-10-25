#!/usr/bin/env bash
# Force-pushes the site dir as a single fresh commit on gh-pages, so old images don't pile up
# in git history. Needs GITHUB_TOKEN and GITHUB_REPOSITORY (set by the workflow).
set -euo pipefail
cd "$1"
rm -rf .git
git init -q -b gh-pages
git add -A
git -c user.name="deals-bot" -c user.email="actions@users.noreply.github.com" commit -q -m "Update deals"
git push -f -q "https://x-access-token:${GITHUB_TOKEN}@github.com/${GITHUB_REPOSITORY}.git" gh-pages
