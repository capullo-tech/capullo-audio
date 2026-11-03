#!/usr/bin/env bash
# Keeps capullo-tunnel on Termux's newest cloudflared while mirroring every pinned version.
#
#  - Mirrors the newest Termux cloudflared .debs (4 ABIs) to a GitHub release on this repo,
#    tagged cloudflared-<version> - the fallback fetchCloudflared uses once Termux prunes it.
#  - If that version is newer than the pin, rewrites the pin + sha256s in
#    capullo-tunnel/build.gradle.kts and opens a PR (with the upstream quick_tunnel.go diff,
#    the file parsePublicUrl's input comes from) for a human to review.
#
# Idempotent: re-running with nothing new is a no-op; an existing release/branch is reused.
# DRY_RUN=1 computes and prints everything without touching GitHub or git.
set -euo pipefail

REPO="${GITHUB_REPOSITORY:-capullo-tech/capullo-audio}"
GRADLE_FILE="capullo-tunnel/build.gradle.kts"
POOL="https://packages.termux.dev/apt/termux-main/pool/main/c/cloudflared"
ARCHES=(aarch64 arm i686 x86_64)
DRY_RUN="${DRY_RUN:-0}"

current=$(sed -nE 's/^val cloudflaredVersion = "([^"]+)"/\1/p' "$GRADLE_FILE")
latest=$(curl -fsSL "$POOL/" | grep -oE 'cloudflared_[0-9][^_]*_aarch64\.deb' \
    | sed -E 's/cloudflared_(.*)_aarch64\.deb/\1/' | sort -uV | tail -1)
[[ -n "$current" && -n "$latest" ]] || { echo "could not read current ($current) / latest ($latest)"; exit 1; }
echo "pinned: $current  termux latest: $latest"

workdir=$(mktemp -d)
trap 'rm -rf "$workdir"' EXIT
declare -A sha
for a in "${ARCHES[@]}"; do
    f="cloudflared_${latest}_${a}.deb"
    curl -fsSL -o "$workdir/$f" "$POOL/$f"
    sha[$a]=$(sha256sum "$workdir/$f" | cut -d' ' -f1)
    echo "  $a ${sha[$a]}"
done

tag="cloudflared-$latest"
if [[ "$DRY_RUN" == 1 ]]; then
    echo "[dry-run] would ensure release $tag"
elif gh release view "$tag" --repo "$REPO" >/dev/null 2>&1; then
    echo "release $tag already exists"
else
    gh release create "$tag" --repo "$REPO" --target main --latest=false \
        --title "cloudflared $latest (Termux .deb mirror)" \
        --notes "Unmodified Termux cloudflared .debs, mirrored as a fallback for :capullo-tunnel:fetchCloudflared. Integrity is enforced by the sha256 pinned in $GRADLE_FILE.

\`\`\`
$(cd "$workdir" && sha256sum ./*.deb)
\`\`\`" "$workdir"/*.deb
fi

if [[ "$(printf '%s\n%s\n' "$current" "$latest" | sort -V | tail -1)" == "$current" ]]; then
    echo "pin is already the newest Termux release - nothing to bump"
    exit 0
fi

branch="cloudflared-$latest"
if [[ "$DRY_RUN" != 1 ]] && git ls-remote --exit-code --heads origin "$branch" >/dev/null 2>&1; then
    echo "branch $branch already exists - bump PR already open"
    exit 0
fi

# Rewrite the pin: version + one sha per Termux arch (matched by arch name on its line).
cp "$GRADLE_FILE" "$workdir/build.gradle.kts.orig"
sed -i -E "s/^val cloudflaredVersion = \"[^\"]+\"/val cloudflaredVersion = \"$latest\"/" "$GRADLE_FILE"
for a in "${ARCHES[@]}"; do
    sed -i -E "s/(\(\"$a\" to \")[0-9a-f]{64}(\"\))/\1${sha[$a]}\2/" "$GRADLE_FILE"
done
git --no-pager diff --stat -- "$GRADLE_FILE"

# Upstream tags drop Termux's packaging revision suffix (e.g. 2026.10.7-1 -> 2026.10.7).
up_from="${current%%-*}"; up_to="${latest%%-*}"
qt_diff=$(gh api "repos/cloudflare/cloudflared/compare/$up_from...$up_to" \
    --jq '.files[] | select(.filename == "cmd/cloudflared/tunnel/quick_tunnel.go") | .patch' 2>/dev/null || true)
[[ -n "$qt_diff" ]] || qt_diff="(no changes to cmd/cloudflared/tunnel/quick_tunnel.go between $up_from and $up_to)"

body=$(cat <<EOF
Automated bump: Termux now ships cloudflared **$latest** (pinned: $current). The .debs are mirrored at release \`$tag\`, and the sha256s in this PR come from those exact files.

**Reviewer check:** \`parsePublicUrl\` scrapes the quick-tunnel banner printed by \`cmd/cloudflared/tunnel/quick_tunnel.go\`. Confirm the public (non-\`--allowed-mail\`) path still prints the \`https://<words>.trycloudflare.com\` line through \`cliutil.LogTable\`. CI also runs \`TunnelUrlParserTest\` and a real fetch.

<details><summary>quick_tunnel.go diff ($up_from → $up_to)</summary>

\`\`\`diff
$qt_diff
\`\`\`
</details>
EOF
)

if [[ "$DRY_RUN" == 1 ]]; then
    echo "[dry-run] would push branch $branch and open PR:"; echo "$body" | head -20
    cp "$workdir/build.gradle.kts.orig" "$GRADLE_FILE"
    exit 0
fi

git checkout -b "$branch"
git add "$GRADLE_FILE"
git commit -m "Bump cloudflared to $latest" -m "Termux now ships $latest; mirrored at release $tag."
git push -u origin "$branch"
gh pr create --repo "$REPO" --base main --head "$branch" \
    --title "Bump cloudflared to $latest" --body "$body"
# PRs opened with GITHUB_TOKEN don't trigger pull_request workflows; dispatch the build on the
# branch so its checks still land on the PR's head commit.
gh workflow run build.yml --repo "$REPO" --ref "$branch"
