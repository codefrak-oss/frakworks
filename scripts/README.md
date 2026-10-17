# scripts

- `local-up.sh`: the local Docker Compose stack (`docs/local-stack.md`).
- `intake-notify-watcher.py`: tells a session when its intake was filed.
- `check-deployment-values.sh`: refuses commits that add a deployment value (a host, address, domain or repo of the deployment's own).
- `install-hooks.sh`: installs the global pre-push hook that runs it.

Tests: `python3 -m unittest discover scripts/tests`.

## Deployment values

The repo is public; its deployment's hosts, addresses, private domains and other repos are not. The patterns that name them never live in the tree: only the generic check does.

`check-deployment-values.sh <base>..<head>` (or `--pre-push`, reading git's pre-push lines on stdin: a new branch checks what no remote has, a deletion checks nothing) looks at every line the commits add. Patterns, one extended regex per line, blank lines and `#` comments ignored, come from `--patterns FILE`, else `$FRAKWORKS_DEPLOYMENT_PATTERNS` (the content), else the file named by `$FRAKWORKS_DEPLOYMENT_PATTERNS_FILE`. ALLOW lines, exact added lines that pass anyway, come the same way (`--allow`, `$FRAKWORKS_DEPLOYMENT_ALLOW`, `$FRAKWORKS_DEPLOYMENT_ALLOW_FILE`). A hit prints `file:line (commit <sha>)` and exits 1; it never prints the pattern or the matched line. With no patterns it prints a one-line notice and exits 0.

An illustrative patterns file (the names are made up):

```
# hosts
\bmy-nas\.home\.invalid\b
\b192\.168\.[0-9]+\.[0-9]+\b
```

`install-hooks.sh` sets `git config --global core.hooksPath` to `~/.config/frakworks/git-hooks` and writes a `pre-push` there that runs this checkout's `check-deployment-values.sh` by absolute path with `~/.config/frakworks/deployment-patterns` (and `deployment-allow`, if present). Run again, it changes nothing and says so. If a different `core.hooksPath` is already set it stops and says so. A global `core.hooksPath` replaces every repo's own `.git/hooks`.

Smiths are checked by the engine, not by a hook: every push it makes for them is checked against the sealed `PUT /settings/deployment-patterns` (engine/README.md, "Deployment patterns").
