# One-command live conformance suite

`conformance/live-suite.sh` is an explicit, billable live-operation command. Run
it only when authorized to start and stop sessions in the selected environment.
Unlike the attach-only runner and `maestro-e2e.sh`, this wrapper owns session
lifecycle. It never opens a viewer or starts/exposes a fixture server.

## Prepare before loading credentials

Install Bash, Python 3, Git, Java 21, and the Revyl CLI. Build the installed runner
before introducing runtime credentials (or explicitly remove the key):

```bash
env -u REVYL_API_KEY ./gradlew --no-daemon clean check installDist
```

Then provide `REVYL_API_KEY` through the process environment using your approved
secret mechanism. Never put keys on the command line, in flows, or in artifacts.
The suite does not install dependencies or run Gradle. Any required upstream
checkout runs with `REVYL_API_KEY` removed from Git's environment.

**The default API is production: `https://backend.revyl.ai`.** Set
`REVYL_MAESTRO_API_URL` explicitly to select another authorized environment.
The suite sets `REVYL_BACKEND_URL` to that same origin for CLI start/stop and the
harness, overriding any inherited CLI dev/backend routing. It does not pass
`--dev`. The override must be an HTTPS origin without credentials, query,
fragment, or a non-root path, with a valid optional port. HTTP is accepted only
for the literal loopback hosts `127.0.0.1` and `[::1]` for offline fixtures;
`http://localhost` is not accepted. Validation happens before any session start.

## Android and iOS

Supply the demo artifact for the selected platform; the suite does not build or
guess its URL. Use a trusted HTTPS APK for Android or a simulator-compatible ZIP
for iOS. Signed query parameters are accepted, but credentials in URL userinfo,
fragments, whitespace, controls, and backslashes are rejected. Avoid shell
history or command tracing when supplying a signed URL. The CLI necessarily
receives the artifact URL as an argument, so use a trusted local machine.

```bash
conformance/live-suite.sh --platform android \
  --demo-app-url "$DEMO_APP_URL" --out .revyl-maestro/android-suite

conformance/live-suite.sh --platform ios \
  --demo-app-url "$DEMO_APP_URL" --out .revyl-maestro/ios-suite
```

Both commands run **Wikipedia first, then demo_app**. Wikipedia's URL comes from
`e2e/manifest.txt` in the pinned Maestro checkout: the exact `wikipedia.apk`
basename for Android, or `wikipedia.zip` for iOS. Missing, ambiguous, or unsafe
manifest entries fail before provisioning. The default pin is `cli-2.10.0`;
`--maestro-ref REF` selects another simple tag/commit name and is forwarded to
the harness. A missing checkout is fetched from Maestro's public repository.

For each workspace/mode session the suite calls:

```text
revyl device start --platform PLATFORM --app-url ARTIFACT_URL --open=false --timeout 1800 --json
```

After CLI readiness returns a valid `session_id`, the suite runs
`conformance/maestro-e2e.sh` in strict mode and with `--maestro-compat`, using
separate sessions for each mode. Each harness batch has a 40-minute budget and
reserves the full configured flow timeout plus one minute before starting a
flow. When that reserve no longer fits, it returns 75; the wrapper stops the
session, starts a fresh one, and continues the same ledger without replaying
completed flows. Provisioning is bounded to ten minutes, so even a full batch
and cleanup stay comfortably below the one-hour lifecycle-task limit. Flows in
a batch still share app state. Use only non-sensitive test apps and flows, close live
viewers, and do not run concurrent controllers. Compat mode executes upstream
JavaScript, including its HTTP helper; the normal runner capability and secrecy
boundaries still apply.

The wrapper defaults `REVYL_MAESTRO_FLOW_TIMEOUT_MS` to `1800000` (30 minutes),
which covers the upstream demo travel flow at its configured speed. Set this
environment variable explicitly to a value from 1 through 1800000 milliseconds
when an authorized run needs a different bound; the runner's own deadline still
applies to each invocation.

The harness supplies upstream's `MAESTRO_EXAMPLE=test-value` environment and
recognizes platform tags with inline comments. It copies Android Wikipedia
fixtures privately to select the installed APK's visible `Forward` control,
whose old resource ID is absent. Both native setLocation copies retain all four
coordinate assertions and unchanged requests, comparing at five-decimal
resolution: ±0.000005° around each requested coordinate (less than 0.6 m per
axis). Each regex enumerates only the eleven six-decimal display values in
that interval; it accepts neither arbitrary suffixes nor a different location.
The adapter forwards doubles without rounding, while the pinned demo reads
Geolocator positions and formats them with `toStringAsFixed(6)`. Six displayed
decimals are not a sensor-accuracy guarantee: Android reported 4.1–5.9 m
accuracy and varying final decimal digits. This sub-meter comparison remains
tighter than that reported accuracy without assuming one emulator's last-digit
offset. Both axes and both locations remain required, including in strict mode
without JavaScript. The pinned checkout is never modified.

For an already authorized session, `maestro-e2e.sh` remains attach-only. Its
`--session-budget-seconds 2400` option enables the same bounded continuation:
after exit 75, add `--resume` and reuse the exact platform, workspace, mode,
ref, and output directory with a new session. Without `--resume` every
invocation starts a new ledger, including when a budget is supplied. The
harness never rotates or stops the supplied session itself; its caller owns
that session's cleanup.

## Web

An existing authorized Revyl web app UUID and a **public, worker-reachable HTTPS
fixture base** are required. Serve the pinned checkout's
`e2e/workspaces/web/fixtures/` contents at that base, preserving paths and
`index.html`. Maestro's fixture server can be started separately:

```bash
.revyl-maestro/upstream-cli-2.10.0/e2e/serve_fixtures
```

That local server is not publicly reachable by itself. Separately arrange an
authorized public HTTPS relay or cloud hosting; the suite neither starts nor
exposes one. It does not probe reachability or resolve DNS before provisioning.
The base must exclude credentials, query strings, fragments, whitespace, YAML
special characters, localhost names, and non-public IP literals. See
[`docs/web-conformance.md`](web-conformance.md) for fixture-copy behavior.

```bash
conformance/live-suite.sh --platform web \
  --web-app YOUR_WEB_APP_UUID --fixtures-url https://fixtures.example.org \
  --out .revyl-maestro/web-suite
```

This explicitly authorizes the CLI's URL-build operation as well as session
creation: `revyl device start --platform web --app UUID --url
HTTPS_BASE/index.html --open=false --timeout 1800 --json` creates or reuses a
URL-backed build on the selected app. Session cleanup does not delete that build.
The suite then passes `--workspace web --fixtures-url HTTPS_BASE` to both harness
modes. No mobile artifact is used. Workers must advertise `supports_maestro_web`
and implement the documented web contract; offline acceptance or successful
session creation does not prove deployed browser support.

## Results and cleanup

`--out` must name a new directory. Without it, the suite creates a unique
`.revyl-maestro/live-suite.*` directory. Reports and runner artifacts are private
(directories mode 700, files mode 600), separated as `WORKSPACE/strict/` and
`WORKSPACE/compat/`. Within each mode, `flows/FLOW_PATH/` preserves preflight
output, live stdout/stderr, the session UUID, and the runner's artifacts rather
than overwriting earlier flows. These are private diagnostics, not public PR
attachments; inspect and sanitize before sharing. Keep custom output paths
ignored by Git. Safe summary paths
and totals are printed on completion; `summary.tsv` and `summary.md` contain a
row per workspace/mode and a total row:

- `live-passed` and `live-failed` count actual runner outcomes, independent of
  upstream tags. A failing-tag flow that correctly fails remains a raw failure.
- `matched-tag` counts passing-tag passes and failing-tag failures. `mismatch`
  counts the opposite outcomes and makes the final suite exit nonzero.
- `unsupported` counts preflight rejections, not attempted live passes or tag
  matches. All-unsupported runs are reported honestly; they do not prove live
  compatibility. Empty or malformed reports fail rather than claim success.

The first start failure, harness error other than planned rollover, or invalid
report aborts later work. An EXIT/INT/TERM trap terminates the active child process group, parses any
available start response, and attempts to stop the owned session by its exact
UUID, never `--all`. Git checkout commands have a 120-second bound, start has a
600-second bound, each harness invocation has a 2400-second bound, and stop has
a 60-second bound. Termination gets five seconds before a process-group kill.
The CLI's `--timeout 1800` is an idle timeout, not this wrapper's start deadline.
SIGINT/TERM exit 130/143 when cleanup succeeds. A cleanup failure exits nonzero
and identifies the session UUID for manual stop; a failed normal stop also gets
the trap's final cleanup attempt.

Raw start JSON and CLI/wrapper stdout/stderr are captured only in a private
temporary directory, never copied into reports or printed on failure. Start JSON
is deleted immediately after extracting the UUID. CLI execution uses an isolated
working directory and temporary HOME so cached viewer URLs do not enter the
project. Temporary response, diagnostics, and CLI state are removed on exit;
failures print generic diagnostics rather than secret-bearing CLI errors. Per-flow
runner diagnostics remain in the private output directory described above. The
signed demo URL, viewer URL/token, and API key are never written to suite reports.

No local trap can guarantee remote cleanup after SIGKILL, machine loss, a failed
stop request, or an ambiguous provisioning failure without a returned session ID.
The CLI cancels interrupted provisioning where supported; inspect the selected
environment's session inventory and stop any orphan you own if provisioning
ended ambiguously. Do not rerun blindly. Separately stop any fixture server or
relay you started yourself.

## Offline wrapper checks

```bash
env -u REVYL_API_KEY conformance/tests/live-suite-test.sh
```

The tests copy only the wrapper into temporary repository layouts with a PATH
stub CLI, harness, and installed-runner placeholder. They test manifest/platform
selection, origin validation/routing, summary accounting, failure exits, signals,
permissions, and secret/viewer suppression. They make no live requests and do
not run Gradle. Passing them is wrapper coverage, not live conformance evidence.
