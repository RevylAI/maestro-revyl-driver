# Revyl Maestro adapter — private experiment

A standalone Kotlin/JVM command-line runner that attaches Maestro to an **existing,
running Revyl Android or iOS device session**. It uses the published Maestro 2.10.0
`YamlCommandReader.readCommands(Path)`, actual `Driver` interface, and
`Orchestra(Maestro(driver)).runFlow(...)` implementation. Assertions and selector
matching execute in Maestro, not a replacement YAML interpreter.

This is **not** a plugin for stock `maestro test`, an officially supported Maestro
distribution, or proof of Revyl cloud/device compatibility. Validation uses real
Maestro flows with synthetic loopback HTTP services only. Nothing here provisions
a device, creates a session, installs an app, or runs a cloud test. The source is
private and unlicensed for redistribution; see `LICENSE` and
`THIRD_PARTY_NOTICES.md`. Repository creation and publication are separate steps.

## Prepare and build

Use Java 21 on Linux or macOS. The checked-in official Gradle 8.13 wrapper downloads
Gradle with a pinned distribution checksum. Kotlin's build plugin is 2.2.0;
published dependencies are pinned by the Gradle lockfile. No local Android SDK,
ADB, Xcode, stock Maestro CLI, or Revyl monorepo checkout is required.

```sh
./gradlew --no-daemon clean check installDist
build/install/revyl-maestro/bin/revyl-maestro --help
```

The local executable and its dependency JARs live under
`build/install/revyl-maestro/`. Keep that directory together. There is no Maven
publication, package registry release, or system-wide installer configured here.
Do not provide runtime credentials to build or dependency-install commands.

## Attach to a prepared session

Obtain approval and prepare a session separately using the
[Revyl device lifecycle guide](https://docs.revyl.com/cli/device/quickstart).
The app must already be installed, launched, and on the expected screen. Obtain
the device-session UUID, not a workflow UUID. The organization associated with
the API key must be authorized to access that session.

Set `REVYL_API_KEY` only in the runner's process environment through your normal
secret mechanism. There is no API-key argument, YAML field, `.env` loader, or
credential store in this adapter. Never put a real key in a flow, shell history,
issue, screenshot, or source file.

```sh
# REVYL_API_KEY must already be available in this process environment.
build/install/revyl-maestro/bin/revyl-maestro \
  --session 11111111-1111-4111-8111-111111111111 \
  --platform android examples/smoke.yaml
```

The UUID above is a placeholder. Use `--platform ios` for an iOS session. Required
arguments have the order shown; no command-line credential options are accepted.

| Environment variable | Behavior |
| --- | --- |
| `REVYL_API_KEY` | Required process-only credential for the authenticated Revyl backend. |
| `REVYL_MAESTRO_API_URL` | Optional backend origin; defaults to `https://backend.revyl.ai`. HTTPS only except literal loopback HTTP for local tests. Paths, query strings, fragments, and URL credentials are rejected. |
| `REVYL_MAESTRO_REQUEST_TIMEOUT_MS` | Request-wide timeout including response body; default 30000, range 1–120000 milliseconds. |
| `REVYL_MAESTRO_FLOW_TIMEOUT_MS` | Total parse/preflight/attach/execution deadline; default 300000, range 1–1800000 milliseconds. |

Attachment first authorizes `GET /api/v1/execution/device-sessions/{sessionUUID}`
and validates the returned ID, running status, platform, and workflow UUID. Only
then can the runner use authenticated
`/api/v1/execution/device-proxy/{workflowUUID}/{hierarchy,screenshot,tap,launch}`
routes. Hierarchy/screenshot reads use GET; tap/launch use POST. It never accesses
worker addresses directly.

Closing the runner releases only local resources. It never destroys or resets the
externally owned session. Follow the separate lifecycle guide when you actually
want to stop that session.

## Supported native subset

`examples/smoke.yaml` is a genuine Maestro flow. Adapt its app ID and visible text
to your prepared app; it does not launch or reset the app:

```yaml
appId: com.example.app
---
- assertVisible: Continue
- tapOn: Continue
- assertVisible: Welcome
- assertNotVisible: Continue
- takeScreenshot: smoke
```

- `assertVisible`, `assertNotVisible`, and native `extendedWaitUntil` use Maestro's
  real assertions, retrying read-only element lookup within its timeout. Supported
  selectors retain Maestro's text/ID regex matching, native state, index, spatial,
  and parent/child relationships; CSS/web selectors are rejected.
- `tapOn` permits one element-center tap or a literal point, such as
  `tapOn: {point: "50%, 50%"}`. Replay, repeat, long-press, recovery, and
  `waitUntilVisible: true` options are rejected. Normal settling reads do not tap.
- `waitForAnimationToEnd` performs Maestro's bounded read-only screenshot comparison.
  Like upstream Maestro, it waits up to its deadline; use an assertion or
  `extendedWaitUntil` when a specific final screen state must be proven.
- `takeScreenshot: smoke` writes `.revyl-maestro/smoke.png`, not a public report.
  Use a simple unique name with letters, digits, underscores, or hyphens, without
  directories or extensions. Existing output files are rejected, not overwritten.
  The directory is private (0700) and the screenshot file is private (0600).
- `launchApp` is supported **only** when it explicitly skips stopping and permission
  changes. Use `examples/non-resetting-launch.yaml` or:

  ```yaml
  - launchApp:
      stopApp: false
      permissions: {}
  ```

  Plain `launchApp` is rejected: Maestro otherwise defaults to stopping the app
  and setting all permissions. Clear-state/keychain flags and launch arguments
  are rejected; an empty permission map means there are no permission changes.

Android hierarchies use native UIAutomator bounds and resource IDs; content
descriptions remain accessibility text, not IDs. iOS IDB hierarchies use
`AXUniqueId` for IDs, `AXValue` for text-field contents, and `AXLabel` for labels.
Missing states remain unknown rather than becoming invented booleans. iOS tap
coordinates use hierarchy frame **points**; screenshot dimensions remain PNG
**pixels**. The runner requires an origin-aligned full-screen iOS root and checks
that its dimensions have a consistent 1×–4× relationship to the screenshot.
Orientation or hierarchy formats outside that contract fail closed.

## Unsupported means an error

The complete parsed flow is preflighted before attachment. Unknown commands and
unsupported options fail the whole flow, even after an otherwise valid tap in the
file. Hooks, nested `runFlow`, `retry`, `repeat`, optional commands/selectors, flow
environment variables, JavaScript interpolation, scripts, and configuration
extensions are rejected. This deliberately sacrifices broader Maestro compatibility
instead of silently accepting partial or destructive semantics.

`inputText` is unsupported: Revyl's current input action retaps supplied coordinates,
so it cannot faithfully implement Maestro's input into the current focus. Stop,
kill, clear state/keychain, scrolling/swiping, long/double/repeated taps, key or
keyboard operations, links, clipboard, location/orientation, media, recording,
permission changes, proxy, airplane/dark mode, AI, and web operations are also
unsupported. There are no fake-success substitutes.

Exit status is 0 only for a successful Maestro `FlowResult`; parse, assertion,
transport, timeout, and unsupported-operation failures return nonzero. Errors are
sanitized and upstream logging is disabled because it can contain selectors,
YAML, hierarchy, provider responses, and customer text. The runner emits only a
bounded success/failure summary, with no debug artifact bundle or device log
collection. This limits diagnostics intentionally; inspect authorized session
reports separately rather than enabling verbose runtime logs around real data.

HTTP redirects and automatic adapter retries are disabled, including status-based
follow-ups. Responses are capped at 16 MiB, hierarchies at 100 levels/10000 nodes,
flows at 1 MiB/999 commands, and decoded screenshots at 16 million pixels. PNG
chunk bounds, checksums, decoding, and complete end markers are validated.
Transport failures latch locally so upstream best-effort screenshot handlers
cannot swallow a failure and permit later actions or a passing flow.

There is **no adapter/runtime-added mutation replay** for accepted flows. This is
not an end-to-end exactly-once guarantee: the underlying Revyl iOS worker has its
own `DEVICE_RETRY` behavior. A timed-out action may already have executed. Inspect
the existing session before deciding whether to rerun a flow.

Treat input flows as local code-adjacent artifacts from a trusted author. Maestro's
own parser resolves referenced files before model preflight; this runner is not a
filesystem sandbox for hostile YAML. Keep flows and `.revyl-maestro/` out of public
artifacts, and delete private screenshots explicitly when finished.

## Offline development and validation

Follow `AGENTS.md` for this repository's editing and side-effect boundaries.

```sh
./gradlew --no-daemon clean check installDist
./gradlew --no-daemon dependencies --configuration runtimeClasspath
build/install/revyl-maestro/bin/revyl-maestro --help
```

Tests execute real YAML + Orchestra on both Android and iOS loopback fixtures,
exercise the installed CLI as a subprocess, initialize a real community GraalJS
context, and cover authentication, native geometry, unsupported preflight,
assertion failures, deadlines, redirect/size checks, mutation non-replay, and
local-only detach. The suite never calls a real backend or device. Do not pass
production credentials to it.

For deliberate dependency changes, regenerate dependency locks with Gradle's
`--write-locks` option while running `check installDist` and runtime dependency
inspection, then review the graph and rerun the tests. The CI workflow uses Java
21 and Gradle `check installDist`, with no publication or artifact upload step.

Maestro's Apache-2.0 license does **not** license its entire transitive dependency
graph. This build replaces `org.graalvm.js:js:24.2.0` with
`org.graalvm.js:js-community:24.2.0`, selecting community `truffle-runtime` instead
of Oracle GFTC `truffle-enterprise`. Tests exercise real GraalJS evaluation on
standard Java 21; its optional optimizing JVMCI runtime is not required. The
build also checks the resolved graph for the community modules and rejects
enterprise modules. This is not a substitute for a complete redistribution/license review;
see `THIRD_PARTY_NOTICES.md` before any distribution.

## Release gates and outcomes

The intended customer outcome is less work to reuse supported native Maestro
smoke flows; the company outcome is more successful customer-controlled device
usage. Measure the supported-flow pass rate and time to the first real assertion.
Guardrails are unauthorized control, accidental teardown, silent unsupported
commands, uncertain-action replay, and command latency. No real-device baseline
or target has been established.

The proxy receives the existing `Maestro` agent attribution and a per-run
correlation ID. Existing device action reports can associate attempted actions,
confirmed outcomes, and latency with that run; they do not measure flow
assertions, discovery, attach failures, or retention. Real Android and iOS smoke
evidence, those product-funnel measurement gaps, and dependency-license review
remain release gates. The local mock suite is not a product release.
