#!/usr/bin/env bash
# Measures this runner against Maestro's own public e2e flows.
# Without --session it only classifies flows offline (supported subset or the first
# rejected command). With --session it also runs every supported flow against that
# existing Revyl session and checks the outcome against the flow's passing/failing tag.
# --maestro-compat classifies and runs flows in the runner's opt-in Maestro compatibility mode.
set -euo pipefail
umask 077

usage() {
  echo "Usage: conformance/maestro-e2e.sh --platform android|ios|web --workspace demo_app|wikipedia|web [--session UUID] [--fixtures-url HTTP(S)_BASE] [--maestro-compat] [--maestro-ref REF] [--out DIR]" >&2
  exit 2
}

platform="" workspace="" session="" fixtures_url="" ref="cli-2.10.0" out=".revyl-maestro/conformance" mode=()
while [ $# -gt 0 ]; do
  case "$1" in
    --platform) [ $# -ge 2 ] || usage; platform="$2"; shift 2 ;;
    --workspace) [ $# -ge 2 ] || usage; workspace="$2"; shift 2 ;;
    --session) [ $# -ge 2 ] || usage; session="$2"; shift 2 ;;
    --fixtures-url) [ $# -ge 2 ] && [ -n "$2" ] || usage; fixtures_url="$2"; shift 2 ;;
    --maestro-compat) mode=(--maestro-compat); shift ;;
    --maestro-ref) [ $# -ge 2 ] || usage; ref="$2"; shift 2 ;;
    --out) [ $# -ge 2 ] || usage; out="$2"; shift 2 ;;
    *) usage ;;
  esac
done
case "$platform" in android|ios|web) ;; *) usage ;; esac
case "$workspace" in
  demo_app) flows_path="e2e/demo_app/.maestro" ;;
  wikipedia) flows_path="e2e/workspaces/wikipedia" ;;
  web) flows_path="e2e/workspaces/web" ;;
  *) usage ;;
esac

root="$(cd "$(dirname "$0")/.." && pwd)"
runner="$root/build/install/revyl-maestro/bin/revyl-maestro"
[ -x "$runner" ] || { echo "Build the runner first: ./gradlew --no-daemon installDist" >&2; exit 2; }

checkout="$root/.revyl-maestro/upstream-$ref"
if [ ! -d "$checkout/.git" ]; then
  env -u REVYL_API_KEY git clone --quiet --depth 1 --branch "$ref" --filter=blob:none --sparse https://github.com/mobile-dev-inc/maestro.git "$checkout"
  env -u REVYL_API_KEY git -C "$checkout" sparse-checkout set e2e >/dev/null
fi

flows_root="$checkout/$flows_path"
if [ "$workspace" = web ] && [ -n "$session" ]; then
  temporary_workspace="$(mktemp -d "$root/.revyl-maestro/web-conformance.XXXXXX")"
  trap 'rm -rf -- "$temporary_workspace"' EXIT
  flows_root="$(env -u REVYL_API_KEY python3 - "$flows_root" "$temporary_workspace/web" "$fixtures_url" <<'PY'
import ipaddress
import pathlib
import re
import shutil
import sys
from urllib.parse import urlsplit

source, destination = map(pathlib.Path, sys.argv[1:3])
base = sys.argv[3].rstrip("/")
if base:
    try:
        url = urlsplit(base)
        if (
            url.scheme not in ("http", "https")
            or not url.hostname
            or url.username is not None
            or url.password is not None
            or url.query
            or url.fragment
            or not re.fullmatch(r"[A-Za-z0-9:/._~%!$&()*+,;=@\[\]-]+", base)
            or url.hostname.rstrip(".").lower() == "localhost"
            or url.hostname.rstrip(".").lower().endswith(".localhost")
            or url.port == 0
        ):
            raise ValueError
        try:
            address = ipaddress.ip_address(url.hostname)
        except ValueError:
            address = None
        if address is not None and not address.is_global:
            raise ValueError
    except ValueError:
        sys.exit("--fixtures-url must be a public-safe HTTP(S) base without credentials, query, fragment, or YAML-special characters.")

fixture_origin = re.compile(rb"http://127\.0\.0\.1:7357(?=[/?#\s'\"]|$)")
fixture_flows = [path for path in source.rglob("*.yaml") if fixture_origin.search(path.read_bytes())]
if fixture_flows and not base:
    sys.exit("Live web fixture flows require --fixtures-url with a base reachable from the worker browser; local e2e/serve_fixtures is not remotely reachable by default.")
if fixture_flows:
    shutil.copytree(source, destination)
    for path in fixture_flows:
        copied = destination / path.relative_to(source)
        copied.write_bytes(fixture_origin.sub(lambda _: base.encode("ascii"), copied.read_bytes()))
    print(destination)
else:
    print(source)
PY
)"
fi

mkdir -p "$out"
chmod 700 "$out"
report="$out/$workspace-$platform${mode:+-compat}.tsv"
printf 'flow\texpected\tpreflight\tlive\tverdict\n' > "$report"
other_platforms="$(printf 'android\nios\nweb\n' | grep -vx "$platform" | paste -sd'|')"

while IFS= read -r flow; do
  header="$(awk '/^---/{exit} {print}' "$flow")"
  expected="$(printf '%s\n' "$header" | grep -oE '^\s*-\s*(passing|failing)\s*$' | tr -d ' -' | head -1 || true)"
  [ -n "$expected" ] || continue
  if printf '%s\n' "$header" | grep -qE "^\s*-\s*($other_platforms)\s*$"; then continue; fi
  name="${flow#"$flows_root/"}"
  if preflight="$(env -u REVYL_API_KEY "$runner" "${mode[@]}" --validate-only --platform "$platform" "$flow" 2>&1)"; then
    preflight_result="supported"
  else
    preflight_result="$(printf '%s' "$preflight" | grep -oE 'Rejected command: [A-Za-z]+Command' | sed 's/Rejected command: //' || true)"
    if [ -z "$preflight_result" ] && printf '%s' "$preflight" | grep -q 'simple parameter references'; then preflight_result="JavaScript"; fi
    preflight_result="unsupported:${preflight_result:-FlowOptionOrHook}"
  fi
  live="-" verdict="-"
  if [ -n "$session" ] && [ "$preflight_result" = "supported" ]; then
    if (cd "$out" && "$runner" "${mode[@]}" --session "$session" --platform "$platform" "$flow" >/dev/null 2>&1); then live="passed"; else live="failed"; fi
    if { [ "$expected" = passing ] && [ "$live" = passed ]; } || { [ "$expected" = failing ] && [ "$live" = failed ]; }; then verdict="ok"; else verdict="mismatch"; fi
  fi
  printf '%s\t%s\t%s\t%s\t%s\n' "$name" "$expected" "$preflight_result" "$live" "$verdict" >> "$report"
done < <(find "$flows_root" -name '*.yaml' ! -name config.yaml | sort)

total=$(($(wc -l < "$report") - 1))
supported=$(awk -F'\t' 'NR>1 && $3=="supported"' "$report" | wc -l)
echo "$workspace on $platform${mode:+ (compat)}: $supported/$total flows accepted by preflight (Maestro $ref)."
awk -F'\t' 'NR>1 && $3!="supported" {print $3}' "$report" | sort | uniq -c | sort -rn | sed 's/^/  /'
if [ -n "$session" ]; then
  ok=$(awk -F'\t' 'NR>1 && $5=="ok"' "$report" | wc -l)
  echo "Live: $ok/$supported supported flows matched their passing/failing tag."
  awk -F'\t' 'NR>1 && $5=="mismatch" {print "  mismatch: " $1 " (expected " $2 ", " $4 ")"}' "$report"
fi
echo "Report: $report"
