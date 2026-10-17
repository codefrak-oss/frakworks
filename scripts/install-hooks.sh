#!/usr/bin/env bash
# Installs a global git pre-push hook that runs scripts/check-deployment-values.sh on every push from this machine,
# with the patterns in ~/.config/frakworks/deployment-patterns (and ALLOW lines in deployment-allow). scripts/README.md.
# Idempotent; says what it changed. A different core.hooksPath already set stops it, unchanged.
set -euo pipefail

config="${XDG_CONFIG_HOME:-$HOME/.config}/frakworks"
hooks="$config/git-hooks"
script="$(cd "$(dirname "$0")" && pwd)/check-deployment-values.sh"

current=$(git config --global --get core.hooksPath || true)
if [ -n "$current" ] && [ "$current" != "$hooks" ]; then
    echo "install-hooks: core.hooksPath is already $current; not overwriting it. Unset it, or call $script from a pre-push hook there." >&2
    exit 1
fi

mkdir -p "$hooks"
hook="$hooks/pre-push"
want=$(cat <<HOOK
#!/usr/bin/env bash
# Written by frakworks scripts/install-hooks.sh: refuses a push whose commits add a deployment value.
FRAKWORKS_DEPLOYMENT_PATTERNS_FILE="$config/deployment-patterns" FRAKWORKS_DEPLOYMENT_ALLOW_FILE="$config/deployment-allow" \\
    exec "$script" --pre-push
HOOK
)
if [ -f "$hook" ] && [ "$(cat "$hook")" = "$want" ]; then
    echo "install-hooks: $hook is up to date"
else
    printf '%s\n' "$want" > "$hook"; chmod +x "$hook"
    echo "install-hooks: wrote $hook"
fi
if [ "$current" = "$hooks" ]; then echo "install-hooks: core.hooksPath already $hooks"
else git config --global core.hooksPath "$hooks"; echo "install-hooks: set git config --global core.hooksPath $hooks"; fi
[ -f "$config/deployment-patterns" ] || echo "install-hooks: no $config/deployment-patterns yet: the hook checks nothing until it exists"
