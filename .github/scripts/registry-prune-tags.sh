#!/usr/bin/env bash
# Prunes an image's per-commit tags in the LAN registry: keeps `main`,
# `buildcache` and the newest KEEP `sha-<commit>` tags (newest by this
# checkout's first-parent history, since the registry keeps no dates), and
# deletes the manifests of the rest, so the registry's weekly garbage
# collection (plain `garbage-collect`, never `--delete-untagged`: on
# docker-registry 2.8 that flag deletes the platform manifests under every
# tagged OCI index, which took the fleet down on 2026-10-18) can free their
# layers. Nothing deploys from a sha tag (Argo, the Smith pods and the desktop
# all pull :main); the kept ones are a rollback handle.
#
# The registry deletes by digest, for every tag that shares it, and a commit
# that changes nothing in the image (a docs-only merge) pushes the same digest
# as the one before: a digest any kept tag points at is never deleted.
#
#   registry-prune-tags.sh <registry host[:port]> <image> [keep, default 5]
#
# Needs a checkout with enough history to see the kept commits (fetch-depth
# 300 in the workflows); a sha tag for a commit outside that history is old
# and goes.
set -euo pipefail
registry=$1; image=$2; keep=${3:-5}
accept='Accept: application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json'

digest_of() {  # the tag's digest, or nothing when the tag dangles (its manifest already deleted)
  curl -sS -I -H "$accept" "http://$registry/v2/$image/manifests/$1" | awk 'tolower($1)=="docker-content-digest:" {print $2}' | tr -d '\r'
}

mapfile -t tags < <(curl -sSf "http://$registry/v2/$image/tags/list" | jq -r '.tags[]?' | grep -E '^sha-[0-9a-f]{7}$' || true)
[ "${#tags[@]}" -gt 0 ] || { echo "$image: no sha tags"; exit 0; }

# The kept sha tags: the newest KEEP commits of this history that have one.
declare -A tagged
for t in "${tags[@]}"; do tagged[${t#sha-}]=1; done
kept=(main buildcache)
while read -r sha && [ "$(( ${#kept[@]} - 2 ))" -lt "$keep" ]; do
  [ -n "${tagged[$sha]:-}" ] && kept+=("sha-$sha")
done < <(git rev-list --first-parent --abbrev-commit --abbrev=7 HEAD)

declare -A kept_digest
for t in "${kept[@]}"; do
  d=$(digest_of "$t"); [ -n "$d" ] && kept_digest[$d]=$t
done
echo "$image: keeping ${kept[*]}"

deleted=0
for t in "${tags[@]}"; do
  for k in "${kept[@]}"; do [ "$t" = "$k" ] && continue 2; done
  d=$(digest_of "$t")
  [ -n "$d" ] || continue  # already gone
  if [ -n "${kept_digest[$d]:-}" ]; then echo "$t shares ${kept_digest[$d]}'s digest, kept"; continue; fi
  curl -sSf -X DELETE -H "$accept" "http://$registry/v2/$image/manifests/$d" > /dev/null && { echo "deleted $t ($d)"; deleted=$((deleted + 1)); }
done
echo "$image: $deleted sha tags deleted"
