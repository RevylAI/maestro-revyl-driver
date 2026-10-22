#!/usr/bin/env bash
set -euo pipefail
umask 077
root="$(cd "$(dirname "$0")/../.." && pwd)"
test_root="$(mktemp -d)"
trap 'rm -rf -- "$test_root"' EXIT
env -u REVYL_API_KEY python3 - "$root" "$test_root" <<'PY'
import csv
import json
import os
import pathlib
import shutil
import signal
import stat
import subprocess
import sys
import time

source, scratch = map(pathlib.Path, sys.argv[1:])
key = "offline-key-DO-NOT-LEAK"
token = "viewer-token-DO-NOT-LEAK"
signed_url = "https://artifacts.example/demo.apk?signature=DO-NOT-LEAK"
sid = "01234567-89ab-4cde-8012-3456789abcde"
app = "12345678-1234-4234-8234-123456789abc"
cli = r'''#!/usr/bin/env python3
import json, os, pathlib, signal, stat, sys, time
args = sys.argv[1:]
assert os.environ['REVYL_API_KEY'] == 'offline-key-DO-NOT-LEAK'
assert os.environ['REVYL_BACKEND_URL'] == os.environ['REVYL_MAESTRO_API_URL']
assert os.environ['REVYL_MAESTRO_FLOW_TIMEOUT_MS'] == os.environ['EXPECTED_FLOW_TIMEOUT']
assert 'offline-key-DO-NOT-LEAK' not in args
assert '--dev' not in args
events = pathlib.Path(os.environ['EVENTS'])
kind = args[1]
if kind == 'start':
    assert args[:2] == ['device', 'start']
    assert '--open=false' in args and '--json' in args
    assert args[args.index('--timeout')+1] == '1800'
    assert stat.S_IMODE(os.fstat(1).st_mode) == 0o600
    assert stat.S_IMODE(os.fstat(2).st_mode) == 0o600
    platform = args[args.index('--platform')+1]
    if platform == 'web':
        assert args[args.index('--app')+1] == '12345678-1234-4234-8234-123456789abc'
        assert args[args.index('--url')+1] == 'https://fixtures.example/base/index.html'
        label = 'web'
    else:
        url = args[args.index('--app-url')+1]
        if 'signature=' in url:
            assert url == 'https://artifacts.example/demo.apk?signature=DO-NOT-LEAK'
            label = 'demo_app'
        else:
            ext = 'apk' if platform == 'android' else 'zip'
            assert url == 'https://manifest.example/pinned/wikipedia.' + ext
            label = 'wikipedia'
    with events.open('a') as f: f.write('start ' + label + ' ' + os.environ['REVYL_BACKEND_URL'] + '\n')
    (pathlib.Path.cwd()/'.revyl').mkdir(exist_ok=True)
    (pathlib.Path.cwd()/'.revyl'/'state.json').write_text('viewer-token-DO-NOT-LEAK')
    print('offline-key-DO-NOT-LEAK viewer-token-DO-NOT-LEAK signature=DO-NOT-LEAK', file=sys.stderr)
    scenario = os.environ['SCENARIO']
    if scenario == 'bad-json':
        print('viewer-token-DO-NOT-LEAK'); sys.exit(0)
    print(json.dumps({'session_id':'01234567-89ab-4cde-8012-3456789abcde', 'viewer_url':'https://viewer.example/?token=viewer-token-DO-NOT-LEAK'}), flush=True)
    if scenario.startswith('signal-start'):
        pathlib.Path(os.environ['READY']).touch()
        signal.signal(signal.SIGTERM, lambda *args: sys.exit(0))
        while True: time.sleep(0.1)
    sys.exit(1 if scenario == 'start-fail' else 0)
assert kind == 'stop' and args[args.index('--session-id')+1] == '01234567-89ab-4cde-8012-3456789abcde'
assert '--all' not in args and '--json' in args
with events.open('a') as f: f.write('stop\n')
print('offline-key-DO-NOT-LEAK viewer-token-DO-NOT-LEAK', file=sys.stderr)
sys.exit(1 if os.environ['SCENARIO'] == 'stop-fail' else 0)
'''
harness = r'''#!/usr/bin/env bash
set -euo pipefail
python3 - "$@" <<'STUB'
import os, pathlib, signal, sys, time
args = sys.argv[1:]
workspace = args[args.index('--workspace')+1]
platform = args[args.index('--platform')+1]
out = pathlib.Path(args[args.index('--out')+1])
assert out.is_absolute()
assert args[args.index('--session')+1] == '01234567-89ab-4cde-8012-3456789abcde'
assert args[args.index('--maestro-ref')+1] == os.environ['EXPECTED_REF']
assert os.environ['REVYL_BACKEND_URL'] == os.environ['REVYL_MAESTRO_API_URL']
assert not list(pathlib.Path(os.environ['TMPDIR']).glob('revyl-live-suite.*/start.json'))
if platform == 'web': assert args[args.index('--fixtures-url')+1] == 'https://fixtures.example/base/'
mode = 'compat' if '--maestro-compat' in args else 'strict'
with open(os.environ['EVENTS'], 'a') as f: f.write('harness '+workspace+' '+mode+'\n')
print('offline-key-DO-NOT-LEAK viewer-token-DO-NOT-LEAK signature=DO-NOT-LEAK')
scenario = os.environ['SCENARIO']
if scenario == 'harness-fail': sys.exit(1)
if scenario.startswith('signal-harness'):
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    pathlib.Path(os.environ['CHILD_PID']).write_text(str(os.getpid()))
    pathlib.Path(os.environ['READY']).touch()
    while True: time.sleep(0.1)
suffix = '-compat' if mode == 'compat' else ''
report = out/(workspace+'-'+platform+suffix+'.tsv')
if scenario == 'bad-report': report.write_text('not a report'); sys.exit(0)
rows = 'flow\texpected\tpreflight\tlive\tverdict\npositive\tpassing\tsupported\tpassed\tok\nnegative\tfailing\tsupported\tfailed\tok\nunsupported\tpassing\tunsupported:Command\t-\t-\n'
if scenario == 'empty-report': rows = rows.splitlines()[0] + '\n'
if scenario == 'mismatch': rows += 'unexpected-pass\tfailing\tsupported\tpassed\tmismatch\n'
report.write_text(rows)
STUB
'''

def run_case(name, platform="android", scenario="ok", extra=(), api=None, expected=0, signal_to_send=None, ref="cli-2.10.0", flow_timeout=None):
    case = scratch / name
    repo = case / "repo"
    (repo / "conformance").mkdir(parents=True)
    shutil.copy(source / "conformance/live-suite.sh", repo / "conformance/live-suite.sh")
    (repo / "conformance/maestro-e2e.sh").write_text(harness)
    runner = repo / "build/install/revyl-maestro/bin/revyl-maestro"
    runner.parent.mkdir(parents=True)
    runner.write_text("#!/bin/sh\nexit 99\n")
    runner.chmod(0o700)
    checkout = repo / (".revyl-maestro/upstream-" + ref)
    (checkout / ".git").mkdir(parents=True)
    (checkout / "e2e").mkdir()
    manifest = "https://manifest.example/pinned/wikipedia.zip\nhttps://manifest.example/pinned/wikipedia.apk\nhttps://manifest.example/not-wikipedia.apk\n"
    (checkout / "e2e/manifest.txt").write_text("" if scenario == "manifest-fail" else manifest)
    bin_dir = case / "bin"
    bin_dir.mkdir()
    (bin_dir / "revyl").write_text(cli)
    (bin_dir / "revyl").chmod(0o700)
    for command in ("git", "curl", "wget", "gradle"):
        (bin_dir / command).write_text("#!/bin/sh\necho 'Unexpected external command' >&2\nexit 99\n")
        (bin_dir / command).chmod(0o700)
    tmp = case / "tmp"
    tmp.mkdir()
    environment = {k: v for k, v in os.environ.items() if not k.startswith(("REVYL_", "LIVE_SUITE_"))}
    environment.update(PATH=str(bin_dir) + os.pathsep + os.environ["PATH"], REVYL_API_KEY=key,
                       REVYL_BACKEND_URL="http://unwanted-dev-route.invalid", TMPDIR=str(tmp),
                       SCENARIO=scenario, EVENTS=str(case / "events"), READY=str(case / "ready"), EXPECTED_REF=ref,
                       CHILD_PID=str(case / "child-pid"), EXPECTED_FLOW_TIMEOUT=flow_timeout or "1800000")
    if api is not None: environment["REVYL_MAESTRO_API_URL"] = api
    if flow_timeout is not None: environment["REVYL_MAESTRO_FLOW_TIMEOUT_MS"] = flow_timeout
    options = ["--platform", platform]
    if platform == "web": options += ["--web-app", app, "--fixtures-url", "https://fixtures.example/base/"]
    else: options += ["--demo-app-url", signed_url]
    options += ["--out", str(case / "out"), "--maestro-ref", ref, *extra]
    process = subprocess.Popen(["bash", str(repo / "conformance/live-suite.sh"), *options], cwd=repo,
                               env=environment, stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
    if signal_to_send:
        deadline = time.monotonic() + 8
        while not (case / "ready").exists():
            assert process.poll() is None, name
            assert time.monotonic() < deadline, name
            time.sleep(0.02)
        process.send_signal(signal_to_send)
    stdout, stderr = process.communicate(timeout=15)
    assert process.returncode == expected, (name, process.returncode, stdout, stderr)
    output = stdout + stderr
    for secret in (key, token, signed_url, "signature=DO-NOT-LEAK"):
        assert secret.encode() not in output, (name, "output disclosure")
    assert not list(tmp.iterdir()), (name, "private temporary state remains")
    if (case / "child-pid").exists():
        child_pid = int((case / "child-pid").read_text())
        status = pathlib.Path(f"/proc/{child_pid}/status")
        assert not status.exists() or "State:\tZ" in status.read_text(), (name, "child still running")
    for path in case.rglob("*"):
        if path.is_file() and (path.is_relative_to(repo / ".revyl-maestro") or path.is_relative_to(case / "out")):
            for secret in (key, token, signed_url):
                assert secret not in path.read_text(), (name, "artifact disclosure", path)
        if path.is_relative_to(case / "out"):
            assert stat.S_IMODE(path.stat().st_mode) & 0o077 == 0, (name, "artifact permissions")
    events = (case / "events").read_text().splitlines() if (case / "events").exists() else []
    if scenario == "ok" or scenario == "mismatch":
        workspaces = ["web"] if platform == "web" else ["wikipedia", "demo_app"]
        origin = api if api is not None else "https://backend.revyl.ai"
        assert events == [event for w in workspaces for event in
                          [f"start {w} {origin}", f"harness {w} strict", f"harness {w} compat", "stop"]], (name, events)
        with (case / "out/summary.tsv").open() as f: rows = list(csv.reader(f, delimiter="\t"))
        n = len(workspaces) * 2
        assert rows[-1] == ["TOTAL", "all", str(n * (2 if scenario == "mismatch" else 1)), str(n), str(2*n), str(n if scenario == "mismatch" else 0), str(n)]
        assert "| TOTAL | all |" in (case / "out/summary.md").read_text()
    elif scenario in ("manifest-fail", "invalid"):
        assert not events, (name, events)
    elif scenario == "bad-json":
        assert len(events) == 1 and events[0].startswith("start"), events
    else:
        assert events[0].startswith("start wikipedia "), events
        assert events[-1] == "stop", events
        assert sum(event.startswith("start") for event in events) == 1, events
        assert events.count("stop") == (2 if scenario == "stop-fail" else 1), events
    print("PASS " + name)

run_case("android")
run_case("ios", platform="ios")
run_case("web", platform="web")
run_case("pinned-ref", ref="custom-pin")
run_case("api-override", api="https://staging.example")
run_case("loopback", api="http://127.0.0.1:8765")
run_case("ipv6-loopback", api="http://[::1]:8765")
run_case("flow-timeout-override", flow_timeout="120000")
run_case("invalid-flow-timeout", scenario="invalid", flow_timeout="1800001", expected=2)
run_case("mismatch", scenario="mismatch", expected=1)
for scenario in ("start-fail", "harness-fail", "bad-json", "bad-report", "empty-report", "manifest-fail", "stop-fail"):
    run_case(scenario, scenario=scenario, expected=1)
for stage in ("start", "harness"):
    for signum in (signal.SIGINT, signal.SIGTERM):
        run_case(f"signal-{stage}-{signum}", scenario="signal-"+stage, signal_to_send=signum, expected=128+signum)
for index, origin in enumerate(("http://localhost:8000", "http://example.org", "https://api.example/path", "https://user:pass@api.example", "https://api.example?secret=value", "https://api.example#fragment", "https://api.example:99999", "")):
    run_case(f"invalid-origin-{index}", scenario="invalid", api=origin, expected=2)
for index, url in enumerate(("", "http://example.org/app.apk", "https://user:pass@example.org/app.apk", "https://example.org/app.apk#fragment", "https://example.org/app.apk\nsecret")):
    run_case(f"invalid-artifact-{index}", scenario="invalid", extra=("--demo-app-url", url), expected=2)
for index, extra in enumerate((("--web-app", ""), ("--web-app", "not-a-uuid"), ("--fixtures-url", "http://fixtures.example"), ("--fixtures-url", "https://127.0.0.1"), ("--fixtures-url", "https://localhost"), ("--fixtures-url", "https://fixtures.example?token=secret"))):
    run_case(f"invalid-web-{index}", platform="web", scenario="invalid", extra=extra, expected=2)
run_case("explicit-platform", scenario="invalid", extra=("--platform", ""), expected=2)
run_case("unknown-option", scenario="invalid", extra=("--workspace", "demo_app"), expected=2)
run_case("ref-traversal", scenario="invalid", extra=("--maestro-ref", "../../escape"), expected=2)
print("All offline live-suite tests passed; no live requests or builds ran.")
PY
