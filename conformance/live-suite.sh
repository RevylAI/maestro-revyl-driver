#!/usr/bin/env bash
set +x
set -euo pipefail
umask 077

usage() {
  echo 'Usage: conformance/live-suite.sh --platform android|ios --demo-app-url HTTPS_URL [--out DIR] [--maestro-ref REF]' >&2
  echo '   or: conformance/live-suite.sh --platform web --web-app UUID --fixtures-url HTTPS_BASE [--out DIR] [--maestro-ref REF]' >&2
  exit 2
}
fail() { echo "$1" >&2; exit 1; }

platform='' demo_url='' web_app='' fixtures_url='' ref='cli-2.10.0' out=''
while [ $# -gt 0 ]; do
  [ $# -ge 2 ] || usage
  case "$1" in
    --platform) platform="$2" ;;
    --demo-app-url) demo_url="$2" ;;
    --web-app) web_app="$2" ;;
    --fixtures-url) fixtures_url="$2" ;;
    --out) out="$2" ;;
    --maestro-ref) ref="$2" ;;
    *) usage ;;
  esac
  shift 2
done
case "$platform" in android|ios|web) ;; *) usage ;; esac
root="$(cd "$(dirname "$0")/.." && pwd)"
export REVYL_MAESTRO_API_URL="${REVYL_MAESTRO_API_URL-https://backend.revyl.ai}"
export REVYL_BACKEND_URL="$REVYL_MAESTRO_API_URL"
export REVYL_MAESTRO_FLOW_TIMEOUT_MS="${REVYL_MAESTRO_FLOW_TIMEOUT_MS-1800000}"
export LIVE_SUITE_PLATFORM="$platform" LIVE_SUITE_DEMO_URL="$demo_url"
export LIVE_SUITE_WEB_APP="$web_app" LIVE_SUITE_FIXTURES_URL="$fixtures_url" LIVE_SUITE_REF="$ref"
python3 - <<'PY' || exit 2
import ipaddress
import os
import re
import sys
import uuid
from urllib.parse import urlsplit

def url(value, *, origin=False, public=False, signed=False):
    if not value or not value.isascii() or re.search(r"[\s\x00-\x20\x7f\\]", value):
        raise ValueError
    parsed = urlsplit(value)
    loopback = parsed.hostname in ("127.0.0.1", "::1")
    if not parsed.hostname or parsed.username is not None or parsed.password is not None:
        raise ValueError
    if parsed.scheme != "https" and not (origin and parsed.scheme == "http" and loopback):
        raise ValueError
    if parsed.fragment or (parsed.query and not signed) or parsed.port == 0:
        raise ValueError
    if origin and parsed.path not in ("", "/"):
        raise ValueError
    if public:
        host = parsed.hostname.rstrip(".").lower()
        if host == "localhost" or host.endswith(".localhost"):
            raise ValueError
        try:
            address = ipaddress.ip_address(host)
        except ValueError:
            address = None
        if address is not None and not address.is_global:
            raise ValueError
        if not re.fullmatch(r"[A-Za-z0-9:/._~%!$&()*+,;=@\[\]-]+", value):
            raise ValueError

try:
    url(os.environ["REVYL_MAESTRO_API_URL"], origin=True)
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,100}", os.environ["LIVE_SUITE_REF"]):
        raise ValueError
    if not 1 <= int(os.environ["REVYL_MAESTRO_FLOW_TIMEOUT_MS"]) <= 1800000:
        raise ValueError
    if os.environ["LIVE_SUITE_PLATFORM"] == "web":
        uuid.UUID(os.environ["LIVE_SUITE_WEB_APP"])
        url(os.environ["LIVE_SUITE_FIXTURES_URL"], public=True)
        if os.environ["LIVE_SUITE_DEMO_URL"]:
            raise ValueError
    else:
        url(os.environ["LIVE_SUITE_DEMO_URL"], signed=True)
        if os.environ["LIVE_SUITE_WEB_APP"] or os.environ["LIVE_SUITE_FIXTURES_URL"]:
            raise ValueError
except (ValueError, AttributeError):
    sys.exit("Invalid live-suite options or API origin; see docs/live-suite.md.")
PY
unset LIVE_SUITE_PLATFORM LIVE_SUITE_DEMO_URL LIVE_SUITE_WEB_APP LIVE_SUITE_FIXTURES_URL LIVE_SUITE_REF
[ -n "${REVYL_API_KEY:-}" ] || fail 'Set REVYL_API_KEY in the process environment.'
command -v revyl >/dev/null || fail 'Install the Revyl CLI first.'
[ -x "$root/build/install/revyl-maestro/bin/revyl-maestro" ] || fail 'Build the runner before loading credentials; see docs/live-suite.md.'

private="$(mktemp -d "${TMPDIR:-/tmp}/revyl-live-suite.XXXXXXXX")"
session='' child='' start_response="$private/start.json"
parse_session() {
  python3 - "$start_response" <<'PY'
import json
import sys
import uuid
try:
    with open(sys.argv[1]) as response:
        value = json.load(response)["session_id"]
    print(str(uuid.UUID(value)))
except (OSError, ValueError, TypeError, KeyError, AttributeError):
    sys.exit(1)
PY
}
run_private() {
  python3 - "$@" >"${capture:-$private/stdout}" 2>"$private/stderr" <<'PY' &
import os
import signal
import subprocess
import sys
os.setsid()
process = None
pending_signal = 0
def terminate(signum, frame):
    global pending_signal
    if process is None:
        pending_signal = signum
        return
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    signal.signal(signal.SIGINT, signal.SIG_IGN)
    try:
        os.killpg(process.pid, signal.SIGTERM)
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait()
    except ProcessLookupError:
        pass
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    sys.exit(128 + signum)
signal.signal(signal.SIGTERM, terminate)
signal.signal(signal.SIGINT, terminate)
process = subprocess.Popen(sys.argv[2:], start_new_session=True)
if pending_signal:
    terminate(pending_signal, None)
try:
    sys.exit(process.wait(timeout=int(sys.argv[1])))
except subprocess.TimeoutExpired:
    terminate(signal.SIGTERM, None)
PY
  child=$!
  local status=0
  wait "$child" 2>/dev/null || status=$?
  child=''
  return "$status"
}
stop_session() {
  if [ -n "$session" ]; then
    if ! run_private 60 bash -c 'cd "$1"; shift; exec "$@"' _ "$private" env HOME="$private/home" revyl device stop --session-id "$session" --json; then
      echo "Session stop failed; manually stop owned session $session using your Revyl session inventory." >&2
      return 1
    fi
    session=''
  fi
}
cleanup() {
  local status=$?
  trap - EXIT
  trap '' INT TERM
  if [ -n "$child" ]; then
    kill -TERM -- "-$child" 2>/dev/null || kill -TERM "$child" 2>/dev/null || true
    wait "$child" 2>/dev/null || true
    child=''
  fi
  if [ -z "$session" ] && [ -f "$start_response" ]; then
    session="$(parse_session 2>/dev/null)" || true
  fi
  rm -f -- "$start_response"
  stop_session || status=1
  rm -rf -- "$private"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
mkdir -p "$private/home"
if [ -z "$out" ]; then
  mkdir -p "$root/.revyl-maestro"
  out="$(mktemp -d "$root/.revyl-maestro/live-suite.XXXXXXXX")"
else
  [ ! -e "$out" ] || fail '--out must be a new directory.'
  mkdir -p "$out"
fi
chmod 700 "$out"
out="$(cd "$out" && pwd)"
checkout="$root/.revyl-maestro/upstream-$ref"
if [ ! -d "$checkout/.git" ]; then
  run_private 120 env -u REVYL_API_KEY git clone --quiet --depth 1 --branch "$ref" --filter=blob:none --sparse https://github.com/mobile-dev-inc/maestro.git "$checkout" || fail 'Maestro checkout failed.'
  run_private 120 env -u REVYL_API_KEY git -C "$checkout" sparse-checkout set e2e || fail 'Maestro checkout failed.'
fi
if [ "$platform" = web ]; then
  workspaces=(web)
else
  workspaces=(wikipedia demo_app)
  wikipedia_url="$(env -u REVYL_API_KEY python3 - "$checkout/e2e/manifest.txt" "$platform" <<'PY'
import pathlib
import sys
from urllib.parse import urlsplit
try:
    name = "wikipedia.apk" if sys.argv[2] == "android" else "wikipedia.zip"
    urls = [line.strip() for line in pathlib.Path(sys.argv[1]).read_text().splitlines()
            if urlsplit(line.strip()).path.rsplit("/", 1)[-1] == name]
    if len(urls) != 1:
        raise ValueError
    value = urlsplit(urls[0])
    if value.scheme != "https" or not value.hostname or value.username or value.password or value.query or value.fragment:
        raise ValueError
    print(urls[0])
except (OSError, ValueError):
    sys.exit("Pinned Wikipedia manifest entry unavailable or invalid.")
PY
)" || fail 'Cannot select Wikipedia artifact from the pinned manifest.'
fi
printf 'workspace\tmode\tlive-passed\tlive-failed\tmatched-tag\tmismatch\tunsupported\n' >"$out/summary.tsv"
for workspace in "${workspaces[@]}"; do
  start=(device start --platform "$platform" --open=false --timeout 1800 --json)
  if [ "$workspace" = web ]; then
    start+=(--app "$web_app" --url "${fixtures_url%/}/index.html")
  elif [ "$workspace" = wikipedia ]; then
    start+=(--app-url "$wikipedia_url")
  else
    start+=(--app-url "$demo_url")
  fi
  start_status=0
  capture="$start_response"
  run_private 2100 bash -c 'cd "$1"; shift; exec "$@"' _ "$private" env HOME="$private/home" revyl "${start[@]}" || start_status=$?
  unset capture
  session="$(parse_session 2>/dev/null)" || fail 'Session start did not return a valid session ID.'
  rm -f -- "$start_response" "$private/stderr"
  [ "$start_status" -eq 0 ] || fail 'Session start failed.'
  for mode in strict compat; do
    mode_out="$out/$workspace/$mode"
    mkdir -p "$mode_out"
    harness=(bash "$root/conformance/maestro-e2e.sh" --platform "$platform" --workspace "$workspace" --session "$session" --maestro-ref "$ref" --out "$mode_out")
    [ "$mode" = strict ] || harness+=(--maestro-compat)
    [ "$workspace" != web ] || harness+=(--fixtures-url "$fixtures_url")
    run_private 7200 "${harness[@]}" || fail 'Conformance harness failed; stopping the suite.'
    suffix=''
    [ "$mode" = strict ] || suffix='-compat'
    env -u REVYL_API_KEY python3 - "$mode_out/$workspace-$platform$suffix.tsv" "$out/summary.tsv" "$workspace" "$mode" <<'PY' || fail 'Invalid conformance report.'
import csv
import sys
try:
    with open(sys.argv[1]) as report:
        reader = csv.DictReader(report, delimiter="\t")
        if reader.fieldnames != ["flow", "expected", "preflight", "live", "verdict"]:
            raise ValueError
        totals = [0] * 5
        for row in reader:
            if row["expected"] not in ("passing", "failing"):
                raise ValueError
            if row["preflight"].startswith("unsupported:") and row["live"] == "-" and row["verdict"] == "-":
                totals[4] += 1
            elif row["preflight"] == "supported" and row["live"] in ("passed", "failed"):
                matched = (row["expected"] == "passing") == (row["live"] == "passed")
                if row["verdict"] != ("ok" if matched else "mismatch"):
                    raise ValueError
                totals[row["live"] == "failed"] += 1
                totals[2 if matched else 3] += 1
            else:
                raise ValueError
        if not sum(totals[:2]) and not totals[4]:
            raise ValueError
    with open(sys.argv[2], "a") as summary:
        csv.writer(summary, delimiter="\t", lineterminator="\n").writerow([*sys.argv[3:5], *totals])
except (OSError, ValueError, TypeError, KeyError):
    sys.exit(1)
PY
  done
  stop_session || fail 'Stopping the suite after a session cleanup failure.'
done
summary_status=0
env -u REVYL_API_KEY python3 - "$out" <<'PY' || summary_status=$?
import csv
import pathlib
import sys
out = pathlib.Path(sys.argv[1])
with (out / "summary.tsv").open() as source:
    rows = list(csv.reader(source, delimiter="\t"))
totals = [sum(int(row[index]) for row in rows[1:]) for index in range(2, 7)]
rows.append(["TOTAL", "all", *map(str, totals)])
with (out / "summary.tsv").open("a") as target:
    csv.writer(target, delimiter="\t", lineterminator="\n").writerow(rows[-1])
with (out / "summary.md").open("w") as target:
    target.write("| " + " | ".join(rows[0]) + " |\n| " + " | ".join(["---"] * 7) + " |\n")
    for row in rows[1:]:
        target.write("| " + " | ".join(row) + " |\n")
print("Live passed: %d; live failed: %d; matched-tag: %d; mismatch: %d; unsupported: %d." % tuple(totals))
sys.exit(1 if totals[3] else 0)
PY
printf 'Summary TSV: %s/summary.tsv\nSummary Markdown: %s/summary.md\n' "$out" "$out"
exit "$summary_status"
