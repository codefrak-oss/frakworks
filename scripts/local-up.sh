#!/usr/bin/env bash
# Bring the whole local stack up from a clean clone — docs/local-stack.md.
#
#   bash scripts/local-up.sh
#
# 1. writes .env from .env.example with every secret minted, if there is none,
#    and mints ENGINE_SECRETS_KEY into one that lacks it (never replacing it);
# 2. reports the .env lines nothing reads (keys .env.example does not declare)
#    and the keys .env.example declares that .env lacks: key names only, never
#    a value, and it deletes nothing (the desktop app's Config > frakworks >
#    .env removes unused lines after a confirm, observability/desktop/README.md);
# 3. builds the engine and Smith images here (engine/compose.build.yaml:
#    frakworks-engine:main and frakworks-smith:main, no registry), or, with
#    FRAKWORKS_REGISTRY set (environment or .env), pulls the engine from
#    <registry>/frakworks-engine:main (engine/README.md "Images"; the engine
#    pulls the Smith image itself); then `docker compose up -d --build`;
# 4. prints what is listening.
# Idempotent: every step is safe to re-run.
set -euo pipefail
cd "$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
command -v docker >/dev/null || { echo "docker not found — see docs/local-stack.md"; exit 1; }
docker info >/dev/null 2>&1 || { echo "docker daemon not running (colima start?)"; exit 1; }

if [ ! -f .env ]; then
  python3 - <<'PY'
import re, secrets
minted = {
  "GRAFANA_ADMIN_PASSWORD": secrets.token_urlsafe(12),
}
out = []
for line in open(".env.example"):
    m = re.match(r"^([A-Z_]+)=(.*)$", line.rstrip("\n"))
    if m and m.group(1) in minted and not m.group(2):
        line = f"{m.group(1)}={minted[m.group(1)]}\n"
    out.append(line)
open(".env", "w").write("".join(out))
PY
  echo "== wrote .env with minted secrets (edit it for real PATs)"
fi

# The engine's sealing key (engine/README.md, "AI accounts"): minted into an
# .env that has none, including one written before this step existed. Never
# replaced once set — a new key cannot open the credentials sealed under the
# old one. Keep a copy (1Password): losing it means setting every credential again.
if ! grep -q '^ENGINE_SECRETS_KEY=.' .env; then
  key=$(python3 -c 'import base64, secrets; print(base64.b64encode(secrets.token_bytes(32)).decode())')
  if grep -q '^ENGINE_SECRETS_KEY=' .env; then
    python3 - "$key" <<'PY'
import sys
lines = open(".env").read().splitlines(keepends=True)
open(".env", "w").write("".join(f"ENGINE_SECRETS_KEY={sys.argv[1]}\n" if l.rstrip("\n") == "ENGINE_SECRETS_KEY=" else l for l in lines))
PY
  else
    printf '\nENGINE_SECRETS_KEY=%s\n' "$key" >> .env
  fi
  echo "== minted ENGINE_SECRETS_KEY into .env — save a copy in 1Password"
fi

# Unused and missing .env keys, by the key rule of
# observability/desktop/src/env-lines.js: a key is the text before the first
# `=` on a line matching ^[A-Za-z_][A-Za-z0-9_]*=. Report only: whatever it
# finds, the script goes on, and no value is printed.
python3 - <<'PY' || true
import re
key = re.compile(r"^([A-Za-z_][A-Za-z0-9_]*)=")
def keys(name):
    out = []
    with open(name, encoding="utf-8", errors="replace") as f:
        for line in f.read().splitlines():
            m = key.match(line)
            if m and m.group(1) not in out:
                out.append(m.group(1))
    return out
env, example = keys(".env"), keys(".env.example")
unused = [k for k in env if k not in example]
missing = [k for k in example if k not in env]
if unused:
    print("== unused lines in .env (nothing reads them): " + " ".join(unused))
    print("   a removed line does not revoke a token or password; revoke it where it was issued")
if missing:
    print("== missing from .env (declared in .env.example): " + " ".join(missing))
PY

# The registry seam: FRAKWORKS_REGISTRY from the environment, else from .env.
registry=${FRAKWORKS_REGISTRY:-$(sed -n 's/^FRAKWORKS_REGISTRY=//p' .env | tail -n 1)}
if [ -n "$registry" ]; then
  echo "== pulling the engine from $registry"
  docker compose pull engine
else
  echo "== building the engine and Smith images locally (FRAKWORKS_REGISTRY unset)"
  docker compose -f compose.yaml -f engine/compose.build.yaml --profile tools build engine smith
fi
docker compose up -d --build
echo
docker compose ps --format 'table {{.Name}}\t{{.Status}}\t{{.Ports}}'
cat <<TXT

Grafana    http://localhost:3000   (admin / GRAFANA_ADMIN_PASSWORD in .env)
engine:    http://localhost:8090   (POST /intake; engine/README.md)
webhook    http://localhost:8090/hooks/github   (the engine's; Funnel this one path only)
TXT
