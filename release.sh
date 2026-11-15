#!/usr/bin/env bash
# Publishes a new version to GitHub Releases; installed apps then offer the update.
#   ./release.sh 0.2.0
# Needs: clean git tree, `gh auth login` done, origin remote pointing at the repo below.
set -euo pipefail
cd "$(dirname "$0")"

VERSION="${1:?usage: ./release.sh <version>, e.g. ./release.sh 0.2.0}"
VERSION="${VERSION#v}"
REPO="$(sed -n 's/^tickers.githubRepo=//p' gradle.properties)"
CUR_NAME="$(sed -n 's/^tickers.versionName=//p' gradle.properties)"
CUR_CODE="$(sed -n 's/^tickers.versionCode=//p' gradle.properties)"

die() { echo "error: $*" >&2; exit 1; }

[[ "$VERSION" =~ ^[0-9]+(\.[0-9]+)*$ ]] || die "version must look like 1.2.3"
[[ -n "$REPO" ]] || die "set tickers.githubRepo in gradle.properties"
gh auth status >/dev/null 2>&1 || die "run: gh auth login"
git remote get-url origin >/dev/null 2>&1 || die "no origin remote; create the repo first:
  gh repo create $REPO --public --source . --push"
[[ -z "$(git status --porcelain)" ]] || die "commit or stash your changes first"
[[ "$(printf '%s\n%s\n' "$CUR_NAME" "$VERSION" | sort -V | tail -1)" == "$VERSION" && "$VERSION" != "$CUR_NAME" ]] \
  || die "$VERSION is not newer than current $CUR_NAME"
[[ -f "${TICKERS_SIGNING_DIR:-$HOME/.config/tickers-signing}/keystore.properties" ]] \
  || die "signing key missing (./dw keygen, or restore your backup)"

NEW_CODE=$((CUR_CODE + 1))
echo "Releasing $CUR_NAME ($CUR_CODE) -> $VERSION ($NEW_CODE) to $REPO"
sed -i "s/^tickers.versionName=.*/tickers.versionName=$VERSION/; s/^tickers.versionCode=.*/tickers.versionCode=$NEW_CODE/" gradle.properties

./dw clean testDebugUnitTest assembleRelease
mkdir -p dist
APK="dist/tickers-$VERSION.apk"
cp app/build/outputs/apk/release/app-release.apk "$APK"
./dw verify-signing "$APK"

git add gradle.properties
git commit -m "Release v$VERSION"
git tag "v$VERSION"
git push origin HEAD "v$VERSION"
gh release create "v$VERSION" "$APK" --repo "$REPO" --title "Tickers $VERSION" --generate-notes

echo "Done: https://github.com/$REPO/releases/tag/v$VERSION"
