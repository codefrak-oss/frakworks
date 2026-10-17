#!/usr/bin/env bash
# Refuses commits that add a deployment value (a host, address, domain or repo name of the deployment's own).
# Generic: the patterns never live in this repo. engine/README.md, "Deployment patterns"; scripts/README.md.
#
#   check-deployment-values.sh [--patterns FILE] [--allow FILE] <base>..<head> ...
#   check-deployment-values.sh [--patterns FILE] [--allow FILE] --pre-push   (git's pre-push lines on stdin)
#
# Patterns: one extended regex per line, blank lines and `#` comments ignored, from --patterns FILE, else
# $FRAKWORKS_DEPLOYMENT_PATTERNS (the content), else the file $FRAKWORKS_DEPLOYMENT_PATTERNS_FILE.
# ALLOW: exact added lines that pass anyway, from --allow FILE, $FRAKWORKS_DEPLOYMENT_ALLOW or
# $FRAKWORKS_DEPLOYMENT_ALLOW_FILE. A hit prints file:line (commit) and exits 1, never the pattern or the line.
# No patterns: a one-line notice, exit 0.
set -euo pipefail

patterns_file="" allow_file="" pre_push=0 ranges=()
while [ $# -gt 0 ]; do
    case "$1" in
        --patterns) patterns_file="$2"; shift 2 ;;
        --allow) allow_file="$2"; shift 2 ;;
        --pre-push) pre_push=1; shift ;;
        -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
        *) ranges+=("$1"); shift ;;
    esac
done

tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
read_source() { # $1 file arg, $2 content var, $3 file var, $4 out
    if [ -n "$1" ]; then { [ -f "$1" ] && cat "$1"; } > "$4" || true
    elif [ -n "${!2:-}" ]; then printf '%s\n' "${!2}" > "$4"
    elif [ -n "${!3:-}" ] && [ -f "${!3}" ]; then cat "${!3}" > "$4"
    else : > "$4"; fi
}
read_source "$patterns_file" FRAKWORKS_DEPLOYMENT_PATTERNS FRAKWORKS_DEPLOYMENT_PATTERNS_FILE "$tmp/raw"
read_source "$allow_file" FRAKWORKS_DEPLOYMENT_ALLOW FRAKWORKS_DEPLOYMENT_ALLOW_FILE "$tmp/allow"
tr -d '\r' < "$tmp/raw" | grep -v -E '^[[:space:]]*(#|$)' > "$tmp/patterns" || true
if [ ! -s "$tmp/patterns" ]; then
    echo "check-deployment-values: no deployment patterns configured, nothing checked"
    exit 0
fi

zero=0000000000000000000000000000000000000000
if [ "$pre_push" = 1 ]; then
    while read -r _local_ref local_sha _remote_ref remote_sha; do
        [ -n "${local_sha:-}" ] || continue
        case "$local_sha" in *[!0]*) ;; *) continue ;; esac          # a deletion pushes nothing
        case "$remote_sha" in
            *[!0]*) ranges+=("$remote_sha..$local_sha") ;;
            *) ranges+=("$local_sha --not --remotes") ;;             # a new branch: what no remote has yet
        esac
    done
fi
[ ${#ranges[@]} -gt 0 ] || { echo "check-deployment-values: no commits to check"; exit 0; }

: > "$tmp/hits"
for range in "${ranges[@]}"; do
    # shellcheck disable=SC2086 # a pre-push range is several words
    git log -p -U0 --no-color --no-renames --no-ext-diff --format='%x01commit %H' $range -- > "$tmp/log"
    awk -v pf="$tmp/patterns" -v af="$tmp/allow" '
        BEGIN { while ((getline p < pf) > 0) pats[++n] = p; while ((getline a < af) > 0) { sub(/\r$/, "", a); allow[a] = 1 } }
        /^\001commit / { commit = substr($2, 1, 12); path = ""; next }
        /^\+\+\+ / { path = substr($0, 5); if (path == "/dev/null") path = ""; else sub(/^b\//, "", path); next }
        /^@@ / { split($0, h, " "); s = h[3]; sub(/^\+/, "", s); sub(/,.*/, "", s); line = s + 0; next }
        /^\+/ && path != "" {
            text = substr($0, 2)
            if (!(text in allow)) for (i = 1; i <= n; i++) if (text ~ pats[i]) { print path ":" line " (commit " commit ")"; break }
            line++
        }' "$tmp/log" >> "$tmp/hits"
done

if [ -s "$tmp/hits" ]; then
    echo "check-deployment-values: refused, a deployment pattern matches lines these commits add:" >&2
    sort -u "$tmp/hits" | sed 's/^/  /' >&2
    exit 1
fi
