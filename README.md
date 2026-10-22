# Revyl Maestro driver

Run supported Maestro flows against an **existing authorized Revyl session**.
Android/iOS commands and the capability-gated web contract are described below.
This runner embeds Maestro's parser and execution
engine; it is not a plugin for stock `maestro test`. Synthetic-app flows have
verified native assertions, taps and repeated taps, long press, focused Backspace
deletion, Enter, Home, appearance changes, and plain deep links on staging Android
emulators and iOS simulators. Android Back was also verified. Local clipboard
commands ran in those flows; OS clipboard behavior is not provided. Location
setting has loopback contract coverage, not app-level live GPS verification.
Text input uses the existing authenticated viewer control channel on Android and
iOS. A prod Android Wikipedia compat smoke verified random text input into search;
iOS input still has offline WebSocket/Orchestra coverage only.

## Install

Requires Java 21 on Linux or macOS. Build before loading runtime credentials:

```bash
git clone https://github.com/RevylAI/maestro-revyl-driver.git
cd maestro-revyl-driver
./gradlew --no-daemon installDist
```

## Run

[Prepare a Revyl session](https://docs.revyl.ai/cli/device/quickstart) with your
app already installed and running. Adapt [examples/smoke.yaml](examples/smoke.yaml)
to your app; this sample expects tapping **Continue** to show **Welcome**:

```yaml
appId: com.example.app
---
- assertVisible: Continue
- tapOn: Continue
- assertVisible: Welcome
- assertNotVisible: Continue
- takeScreenshot: smoke
```

Provide `REVYL_API_KEY` through the runner's environment, never in YAML or
command-line arguments. From the repository directory:

```bash
build/install/revyl-maestro/bin/revyl-maestro \
  --session YOUR_RUNNING_SESSION_UUID \
  --platform android examples/smoke.yaml
```

Use `--platform ios` for iOS. A failed assertion or unsupported command exits
nonzero. The screenshot is saved privately to `.revyl-maestro/smoke.png`;
delete it or choose a new screenshot name before rerunning.
For text input, **close the live Revyl viewer before running the flow**, focus the
intended field, and do not type secrets through this legacy path. See the input
limitations below before relying on the result.

## Maestro compatibility mode

Strict mode remains the default. Pass `--maestro-compat` on either invocation form
to opt into Maestro 2.10's native Orchestra execution:

```bash
build/install/revyl-maestro/bin/revyl-maestro --maestro-compat --session YOUR_RUNNING_SESSION_UUID --platform android flow.yaml
build/install/revyl-maestro/bin/revyl-maestro --maestro-compat --validate-only --platform ios flow.yaml
```

This mode accepts optional commands/selectors, repeat times/while, retry,
retryTapIfNoChange, waitUntilVisible, scrollUntilVisible, extendedWaitUntil,
relative tap/swipe points, assertTrue, evalScript, runScript, full string
expressions, when.true, hooks, random input, and travel. Scripts resolve relative
to their flow. Maestro's own GraalJS engine provides output, maestro, faker, and
the http helper. **JavaScript can make network requests and read flow env.**
Only MAESTRO_* process variables are inherited, with stock filename/shard defaults;
`-e NAME=VALUE` supplies additional explicit parameters. REVYL_API_KEY and env
values containing the process key are scrubbed at the JS-engine boundary. Never
put secrets in flows or text input.

Compatibility mode does not add adapter retries. Maestro may repeat commands
according to explicit retry/loop semantics, but a proxy or viewer uncertainty
latches a terminal failure before any later mutation, including completion hooks.
Capability gates inspect all nested branches, loops, and hooks before mutation.
Requests remain bounded by the request timeout and total flow deadline; JS HTTP
has the same per-request timeout but does not receive Revyl authentication.
Expanded-command budgets do not apply to compat loops. The flow deadline bounds
runtime instead. Validation-only parses and checks capabilities without executing
JavaScript, so expression-derived invalid values can fail at runtime.

Screenshots and recordings remain private and path-contained. Literal or resolved
output names use simple relative segments without extensions or parent traversal.
Screenshot references must stay inside the flow's Maestro workspace (the nearest
ancestor containing config.yaml, otherwise the flow directory); references are
copied privately before comparison so Maestro's diff images do not modify them.
Literal screenshot references are checked for existence, containment and size in
both modes before attachment; only expression-derived paths defer validation.
Adapter failures during expression/artifact resolution also latch terminal failure
before completion hooks can issue device actions.
Media paths remain literal, validated local files. Android WebView DevTools and
process-death kill require their worker capabilities below. Web flows use the
web contract below; AI commands, unsupported keys, and iOS airplane mode
remain unsupported.

## Strict-mode supported commands

| Command | Semantics and limits |
| --- | --- |
| Native assertions, taps, screenshots, waits | Native selectors and grid coordinates; screenshots stay private. |
| `longPressOn` | A 3000-ms hold at an element center or literal native/percent point. |
| `tapOn` with `repeat`, `doubleTapOn` | Intentional, individually acknowledged taps through Maestro's own repeat loop. Counts are 1–100; delays are 0–10000 ms, with at most 120000 ms total requested delay per sequence. Maestro defaults the delay to 100 ms. |
| `pressKey` | `Enter`, `Backspace`, and `Home`; `Back` and the `back` command are Android-only. The current HTTP Enter/Backspace path supports Android and iOS simulators, not physical iOS. |
| `eraseText` | Sends 1–100 Backspaces to current focus. Omitted count means 50 Backspaces, not a guarantee that the field is empty. |
| `inputText`, `pasteText` | Android and iOS input through the existing viewer control WebSocket. Requires a ready, authorized control connection before any flow mutation. See the accepted legacy input limitations below. |
| `setClipboard`, `copyTextFrom` | Store text only in Maestro's local clipboard variable; these commands do not set the OS clipboard. Literal `setClipboard` text is limited to 16 KiB UTF-8. `pasteText` requires an earlier clipboard command and uses the viewer input path below. |
| `runFlow` | File-based or inline subflows, optionally gated by native visible/notVisible/platform conditions and parameterized with scoped `env` strings. Nested commands must belong to this same supported subset; see the limits below. |
| `setLocation` | Finite latitude in −90…90 and longitude in −180…180 degrees. Supported by the current Android emulator/iOS simulator worker paths, not physical iOS. |
| `stopApp`, `clearState`, `clearKeychain`, `setPermissions`, `setOrientation`, full `launchApp` | Deployment-gated; see [app lifecycle](#deployment-gated-app-lifecycle). |
| `setDarkMode` | Explicit `enabled` or `disabled` through system appearance setting. Current worker support is emulator/simulator-only. Toggles and appearance assertions additionally require `supports_appearance_read`. |
| `inputRandom*`, `travel` | Maestro generates input or location steps locally. Strict random length is 1–1000; travel needs 2–20 points, 0.1–10000 m/s, at most 1800000 ms requested duration, and contributes 50 location steps per segment to the expanded-command budget. |
| `assertScreenshot`, `takeScreenshot.cropOn` | Maestro compares/crops locally using the driver's screenshot. References are path-contained and copied privately; output names use simple relative segments without extensions. |
| `hideKeyboard`, `addMedia`, appearance reads, airplane mode, recording | Deployment-gated by the feature flags below. |
| `openLink` | Plain absolute HTTP(S) or app-scheme links, with `autoVerify: false` and `browser: false` (also the scalar form's defaults). Uses system URL routing without forcing a target app. URLs are limited to 8 KiB UTF-8; relative, control-character, embedded-credential, `file:`, `data:`, `javascript:`, and `intent:` links are rejected. |

## Web platform (capability-gated worker contract)

`--platform web` accepts Maestro `url:` flow headers or HTTP(S) `appId:` targets
in both modes. It reuses the parser/Orchestra and attaches to an existing session;
it never launches Selenium/Chrome or exposes the runtime's private CDP endpoint.
**All web execution requires strict readiness and `supports_maestro_web:true`
before navigation/input.** Companion worker PR #4831 at `0be342a` derives that
flag from current runtime probes; deployment is not verified here. Loopback tests
are contract coverage, not live web support. The aligned contract is specified in
[`docs/web-worker-contract.md`](docs/web-worker-contract.md).

The mapping includes DOM text/ID/CSS selectors, screenshots, navigation/back,
state-preserving launch, named-origin reset, focused append/HTML date input,
scroll/drag, keys and Maestro web synthetic/offscreen taps. Stop/kill, permissions,
keychain and keyboard dismissal are local no-ops like Maestro web; closing the CLI
does not stop the browser/session. Mobile-only location, travel, orientation,
appearance, airplane mode, media and recording are rejected. API-key secrecy,
private artifacts, deadline bounds and terminal uncertain-response latching apply
unchanged. Worker-owned semantics include cross-origin/sandboxed frames, affine
frame transforms, visual zoom, Flutter scrolling and native caret positioning.
Hierarchy/query/tap coordinates stay in the visual-viewport-normalized grid;
the driver scales only screenshot-pixel actions. Per-element HTTP 501
`unsupported_web_element` failures are terminal even inside optional/retry/hooks;
they never echo worker text. Visual grounding is not a replacement.

```bash
build/install/revyl-maestro/bin/revyl-maestro --maestro-compat --validate-only --platform web flow.yaml
conformance/maestro-e2e.sh --maestro-compat --platform web --workspace web
```

The pinned Maestro workspace contains **13**, not 14, tagged web flows. Offline
preflight accepts 13/13 in strict and compat mode; this does not prove live page
behavior. Live fixture runs require a worker-reachable fixture base rather than
assuming the harness machine's localhost is the browser's localhost. See
[`docs/web-conformance.md`](docs/web-conformance.md) for Maestro's fixture server
and the harness's `--fixtures-url` option.

For example, with a text field **already focused**:

```yaml
appId: com.example.app
---
- eraseText: 3
- pressKey: Enter
- setDarkMode: enabled
- longPressOn: Continue
```

The complete flow, including every nested command, is checked before attachment.
There may be at most 999 source commands and 1000 expanded commands across the
whole invocation, counting each `runFlow` wrapper and each tap or Backspace in a
sequence. Referencing a file twice counts its commands twice; configurations do
not count as commands.
In strict mode, optional commands, scripts/arbitrary expressions, hooks, general repeat/retry blocks,
`retryTapIfNoChange`, and `waitUntilVisible` are rejected. Unsupported or invalid
options are not a request to approximate the action.

Android UIAutomator nodes with inverted or zero-area rectangles have no visible
target and are omitted, not normalized into tappable bounds. Their valid
descendants remain in order under the nearest retained ancestor; selectors cannot
match the omitted node itself. Malformed coordinates, invalid states, and hierarchy
size/depth violations still fail the flow.

## Subflows, conditions, and parameters

Reuse existing supported login/onboarding steps without manually flattening them:

```yaml
appId: com.example.app
---
- runFlow: steps/onboarding.yaml
- runFlow:
    file: steps/check-profile.yaml
    label: Check profile
- runFlow:
    commands:
      - assertVisible: Welcome
      - pressKey: Home
```

Referenced files use the normal Maestro `appId` configuration and command-list
format. Inline command lists inherit their containing flow's context and have no
separate configuration. Relative paths resolve beside the containing YAML file,
including file references inside inline flows; absolute paths are also accepted.
Maestro retains execution order and file-specific app IDs. Its local clipboard
state is shared across parent, child, and sibling flows.

In strict mode, use exactly one nonempty literal file path or nonempty inline `commands` list;
an optional `label`, `env`, and `when` are allowed. `optional` (even false), unknown
options, scripts, hooks, and retry/repeat blocks are rejected. Nesting is limited to 16 `runFlow` levels,
each file to 1 MiB, and aggregate YAML size across file references to 8 MiB
(counting repeated references). Cycles,
including symlink aliases, missing files, invalid nested commands, and unsupported
source options fail before any backend request. These are trusted local files;
do not edit them while a run is being parsed.

Nested gestures contribute to the same pre-mutation capability gate; nested input
requires the same up-front viewer connection. Nested screenshots use the same
private `.revyl-maestro/` directory and simple-name restrictions, and names must
be unique across the invocation and not overwrite existing artifacts. A nested
failure is terminal: no later child or parent action and no adapter replay.

In strict mode, `when` accepts `visible`, `notVisible`, and/or a literal `platform: android|ios`.
Combined conditions use Maestro's native AND semantics and lookup waits. False
conditions skip the subflow without failing its parent; transport failures remain
terminal, not skips. `when.true`, JavaScript conditions, optional selectors, and
empty conditions are unsupported. Condition selectors are resolved in the enclosing
scope, **before** the subflow's own `env` is applied.

```yaml
appId: com.example.app
env:
  PROFILE_LABEL: Profile
  DISPLAY_NAME: Demo user
---
- runFlow:
    when:
      visible: ${PROFILE_LABEL}
      platform: android
    env:
      NAME: ${DISPLAY_NAME}
    commands:
      - inputText: ${NAME}
      - assertVisible: ${NAME}
```

Strict mode accepts only `${NAME}` references and their concatenation with literal text,
using explicitly declared YAML string parameters. Names must match
`[A-Z][A-Z0-9_]{0,63}`; `JSON` is reserved. Shell variables, API credentials,
Maestro runtime objects, copied-text expressions, Java/HTTP access, JavaScript
operators/functions, escaped expressions, and undeclared references are not
available. Do not put secrets in flows or use them through the legacy viewer input
channel. Each `env` map has at most 64 entries, each value at most 16 KiB UTF-8;
the invocation permits at most 4096 parameter definitions and 1 MiB of resolved
parameter values, counting repeated definitions. Interpolation is bounded to
16384 references and 8 MiB of substituted text across the invocation.

Maestro's published parser emits variable definitions; its native Graal engine
resolves their strings through an identifier-only guard **before attachment**.
Every resolved command, including skipped branches, must pass the same validation
as literal commands. Orchestra still evaluates native conditions and executes the
validated command tree. String fields supported by Maestro's native interpolation
(such as input text, selectors, app IDs, screenshot names, links, and string
timeouts) can reference parameters. File paths and parser-time typed options
(such as key names, counts, duration, boolean flags, and platform) remain literal.
No second YAML or expression interpreter is used.

Scope and precedence follow the pinned Maestro 2.10 runtime, including its quirks:
subflows inherit enclosing parameters and restore them on return; file-level
`env` definitions override caller `runFlow.env` values with the same name. Values
within one env map resolve together against the prior scope, not sibling entries.
For inline flows, this parser emits the `runFlow.env` definition once per inline
command, then Orchestra applies all definitions before executing the body. Avoid
self-referential overrides such as `NAME: ${NAME}-suffix` in multi-command inline
flows: the suffix is applied repeatedly. Use a distinct source parameter name.

Clipboard initialization and text validity must hold on **every** possible path
before a later `pasteText`. Initialization inside a conditional branch is sufficient
for a paste inside that branch, but cannot establish initialization after it. All
branches contribute to command budgets, screenshot reservations, viewer setup,
and capability checks even when their conditions would skip execution.

Live staging checks of unconditional flows on an Android emulator and iOS simulator verified nested
file and inline flows, native Settings navigation and assertions, nested
screenshots, rejection of unsupported child commands before preceding actions,
and stopping child and parent continuation after a failed child assertion.
Loopback real-parser/Orchestra tests cover the broader boundary cases; these live
checks do not establish customer-app, nested text-input, or gesture compatibility.
Additional live Settings flows on staging Android and iOS verified combined
visible/not-visible/platform conditions, skipped branches, nested string parameters,
file-level precedence, scope restoration, parameterized selectors and screenshots,
and rejection of invalid resolved paths and undeclared process-credential references
before preceding actions. The negative checks independently asserted unchanged
device state afterward. Other condition and parameter boundaries have loopback coverage.
The intended outcome is reusable supported conditional, parameterized onboarding/login flows without
flattening. The primary metric is the
completion rate of supported nested-flow invocations, expected to increase;
runner exit status and native assertions measure completion. Guardrails are zero
backend requests for invalid flows, no replay after uncertain outcomes, and bounded
execution. No baseline, new content analytics, or broader Maestro compatibility
is claimed. Existing viewer-input restrictions on secrets still apply.

## Existing viewer input: accepted limitations

`inputText` and `pasteText` use the existing backend-authorized viewer WebSocket,
not an unpublished `/text-input` endpoint or `supports_focused_text_input` health
flag. After validating the complete flow and attaching to the authorized running
session, the runner fetches that workflow's authenticated
`streaming/worker-connection` response and establishes the connection **before any
flow mutation**, including earlier taps or keys. Missing control permission,
unready/mismatched workflow responses, invalid addresses, and handshake failures
fail early. Worker addresses cannot be supplied in YAML or runner configuration.
WSS is required outside literal loopback; redirects are forbidden. The backend's
connection token stays internal to its URL; the API key is never sent to the worker.

Each input sends one `STREAM` / `MANUAL_INPUT` message with a unique UUID
`action_id`, a client timestamp, the exact text as `value`, `incremental: true`,
`skip_tap: true`, and `clear_first: false`. The driver omits `x`/`y`, does not derive
a focus target from hierarchy, and never deliberately re-taps. On iOS it also sends
`paste: true` to request the existing pasteboard path rather than limited HID
character input; that path can change the device clipboard. Android uses the
existing provider text-input path. `pasteText` sends Maestro's local clipboard
value through the same platform-specific path. Input must be nonempty valid
Unicode, at most 16 KiB UTF-8, without control characters except tab, newline,
and carriage return. Literal pasted values are checked in preflight; values from
`copyTextFrom` are checked again immediately before input.

**This is an explicitly accepted exception to a strict no-refocus guarantee.**
The runtime may fall back to `device.input` when its passthrough helper is absent,
or to another input transport internally. Omitting coordinates avoids inventing a
target; it does not prove every provider preserves focus or selection. Prepare
the intended focus explicitly, and use native assertions or inspect the app to
verify the actual result. Physical-iOS behavior and Unicode/paste support depend
on the deployed provider; no live compatibility is claimed here.

Only a matching `ACTION_ACK` with `action: input` and boolean `success: true`
confirms the handler returned successfully. `ACTION_RECEIVED`, buffered events,
and unrelated IDs are not completion. **An ACK does not verify the text, caret,
selection, or app-level outcome.** The current runtime preferentially routes ACKs
to an open viewer's WebRTC channel, so close the live viewer during the flow.
A missing, invalid, or negative ACK, disconnect, send uncertainty, or timeout
latches a terminal failure: no later mutation, reconnect, resend, or payload replay.
Input may already have executed. Inspect the app before starting a new flow.
Connection and ACK waits use the request timeout and total flow deadline. The
adapter accepts text events up to 64 KiB, retains no inbound event queue, caps
outgoing queued messages at 128 KiB, and caps received events at 16384 per connection.
It handles bounded server ping IDs with matching pongs and cancels the local socket
on exit without stopping the external session.

**Do not enter passwords, tokens, or other secrets through this legacy path.**
Although this runner suppresses payloads, URLs, and provider diagnostics, the
existing worker can log typed content and include it in manual-action reports.
The intended outcome is successful external-runner native input without a runtime
rollout. The primary metric is native-input flow completion rate, expected to
increase. Measure usage and handler outcomes from existing Revyl manual action
records; use runner exit status and native assertions to assess actual flow
completion. The guardrails
are terminal ambiguous outcomes, no adapter retries, authorization, and no local
credential/content disclosure. No new analytics events or live success baseline
are claimed.

## Deployment-gated gestures

`swipe` and `scroll` have offline parser/Orchestra and installed-CLI coverage, **not
live-device verification**. They require worker changes and authenticated backend
relay support; their availability on current staging must not be assumed.
After session authorization, the runner reads the attached workflow's authenticated
`health` endpoint before executing any flow command. Health must identify the same
connected device and platform, and every required capability must be boolean
`true`. A missing, false, malformed, or unavailable capability fails the whole
flow before earlier taps, launches, or other mutations can run.
The previous capability-gated implementation was checked on staging Android and
iOS: rejected input and swipe flows left preceding mutations untouched. That input
check does not verify the new viewer path; the explicit-duration gesture gate is
unchanged.

| Command | Required health capability | Semantics and limits |
| --- | --- | --- |
| `swipe` | `supports_explicit_drag_duration` | One native `POST /drag`, preserving explicit endpoints and a 1–10000-ms duration; Maestro defaults to 400 ms. Supports native coordinate pairs, integer percent pairs from 0–99%, a direction, or a direction from a native element center. Endpoints must differ and fit the current native viewport. Does not use the legacy `/swipe` path or promise element dragging semantics. |
| `scroll` | `supports_explicit_drag_duration` | One upward center-to-10%-height drag, using Maestro's native default of 400 ms on Android or 333 ms on iOS. In compat mode, `scrollUntilVisible` delegates its loop and directional swipes to Maestro with this same capability gate. |

Coordinates use the same native grid as taps: Android screenshot pixels and iOS
hierarchy points, not iOS screenshot pixels. Directional swipes follow Maestro's
platform geometry; an explicit duration is forwarded unchanged, not replaced with
a fixed provider timing. Capability advertisement is a deployment contract, not
evidence of actual device timing or physical-iOS compatibility.

## Deployment-gated app lifecycle

These commands need a worker that advertises the named boolean health capability.
As with gestures, the runner checks the capability before the first flow mutation
and fails the whole flow when it is missing, false, or malformed. Every operation
is targeted at an explicit app ID; nothing acts on whichever app is in front.

| Command | Required health capability | Semantics and limits |
| --- | --- | --- |
| `launchApp` (any form other than `stopApp: false` with `permissions: {}`) | `supports_app_lifecycle` | Follows Maestro's order: `clearKeychain`, `clearState`, `setPermissions` (default `all: allow`), then a targeted stop when `stopApp` is not false, then launch. `arguments` accepts 1–64 entries with names of 1–128 `[A-Za-z0-9_.-]` characters and string (≤4 KiB, no control characters), integer, finite number, or boolean values. |
| `stopApp` | `supports_app_lifecycle` | Targeted terminate of the flow's or the named app. |
| `killApp` | `supports_app_lifecycle` on iOS; `supports_process_death_kill` on Android | iOS targeted terminate. Android uses `kill_app` with `mode: process_death` to simulate system process death rather than force-stop. |
| `clearState` | `supports_app_lifecycle` | Resets the app's data, then resets all its permissions to `unset` like Maestro. Not available on physical iOS. |
| `clearKeychain` | `supports_app_lifecycle` on iOS | Resets the simulator keychain. A local no-op on Android, matching Maestro's Android driver. |
| `setPermissions` | `supports_app_lifecycle` | Maestro permission names with `allow`, `deny`, or `unset`. Android also accepts fully qualified `*.permission.*` names. Names that do not exist on the platform fail preflight. |
| `setOrientation` | `supports_precise_orientation` | `PORTRAIT`, `LANDSCAPE_LEFT`, `LANDSCAPE_RIGHT`, or `UPSIDE_DOWN`, applied exactly or not at all. |

Explicit iOS `camera`, `notifications`, `health`, `homekit`, `speech`, and
`userTracking` names additionally require `supports_extended_ios_permissions`.
For iOS `all` (including Maestro's default launch permissions), that flag is
optional: missing, false, or malformed means the worker's supported legacy subset,
not a claim that every extended permission changed. A strict true flag enables
the worker's extended set. The driver still forwards `{all: allow|deny|unset}`;
the worker owns platform expansion. Explicit named requests never silently degrade.

`androidWebViewHierarchy: devtools` is supported in both modes and requires
`supports_webview_hierarchy` before any mutation. This is the only related flow
configuration key used by the pinned Maestro 2.10 Orchestra. When the native tree
contains `android.webkit.WebView`, the driver reads `webview_hierarchy` as
`{nodes: [...]}`, each node `{text, id, bounds: {x, y, width, height}, children}` in screen pixels,
converts bounds to Maestro rectangles, and delegates merging to Maestro's own
Android WebView merge helper. Native text and IDs are preserved; additional DOM
text/IDs become normal selectors. Empty valid lists keep the native tree; malformed
or uncertain reads are terminal rather than silently returning native-only results.
Strict mode still rejects `scrollUntilVisible` and scripts independently of this
configuration; use compat mode for upstream flows that require those semantics.

## Deployment-gated device features

These flags are checked as strict boolean true values on the authenticated worker
health response before any flow mutation, in both modes. Missing or malformed
flags fail closed, including in skipped branches, retries, loops, and hooks.

| Commands | Required health capability | Proxy contract |
| --- | --- | --- |
| hideKeyboard | supports_keyboard_control | POST hide_keyboard {} then GET keyboard -> {visible: bool}. |
| addMedia | supports_add_media | One POST add_media {name, content_base64} per file. Simple basename of at most 128 ASCII letters/digits/dots/dashes/underscores; png, jpg, jpeg, gif, heic, mp4, mov only. Every file is checked before attachment; decoded size is 1 byte–25 MiB. |
| toggleDarkMode, assertDarkMode, assertLightMode | supports_appearance_read | GET appearance -> {appearance: dark\|light}; toggles use the existing set_appearance mutation. |
| setAirplaneMode, toggleAirplaneMode | supports_airplane_mode | Android only. POST set_airplane_mode {enabled: bool}; toggle reads GET airplane_mode -> {enabled: bool}. |
| startRecording, stopRecording | supports_screen_recording | POST start_screen_recording {} returns ActionResponse plus recording_id; POST stop_screen_recording {recording_id} returns raw video/mp4, or JSON ActionResponse on error. At most 180 seconds and 100 MiB; MP4 output is private. |

Proxy routes use /api/v1/execution/device-proxy/{workflow_run_id}/{action}.
Mutations require strict success: true, the exact route name as action, and no
error; state reads require their typed fields. stop_screen_recording requires
video/mp4 bytes rather than a successful JSON response. The worker must enforce
the recording time and size limits too. These new contracts have loopback coverage;
they are not live-verified until the worker/backend deployment advertises them.

## Conformance against Maestro's public e2e flows

`conformance/maestro-e2e.sh` measures the runner against the flows Maestro uses to
test itself (`e2e/demo_app` and `e2e/workspaces/wikipedia` at the pinned Maestro
release). Each flow carries a `passing` or `failing` tag, so a run checks both
directions. Without `--session` it only classifies every flow offline as supported
or by its first rejected command; with `--session` it also runs each supported
flow against that existing session, which must already have the workspace's app
installed (the Wikipedia APK and app are listed in Maestro's `e2e/manifest.txt`;
the demo app is built from `e2e/demo_app`).

```bash
./gradlew --no-daemon installDist
conformance/maestro-e2e.sh --platform android --workspace demo_app
conformance/maestro-e2e.sh --maestro-compat --platform android --workspace demo_app
conformance/maestro-e2e.sh --maestro-compat --platform android --workspace wikipedia --session YOUR_RUNNING_SESSION_UUID
```

The per-flow report is written privately under `.revyl-maestro/conformance/`. The
same offline check is available for any single flow:

```bash
build/install/revyl-maestro/bin/revyl-maestro --validate-only --platform ios flow.yaml
```

### How to run the final live test

Build the runner without runtime credentials, then run the selected suite once
you are authorized to create billable sessions in that environment:

```bash
env -u REVYL_API_KEY ./gradlew --no-daemon clean check installDist
conformance/live-suite.sh --platform android --demo-app-url "$DEMO_APP_URL"
conformance/live-suite.sh --platform ios --demo-app-url "$DEMO_APP_URL"
conformance/live-suite.sh --platform web --web-app YOUR_WEB_APP_UUID --fixtures-url https://fixtures.example.org
```

Use the matching hosted demo APK or simulator `.app` ZIP from the
[`Build Maestro demo app`](docs/demo-app-builds.md) workflow. The suite selects
Wikipedia from Maestro's pinned manifest, runs both modes for every workspace,
writes one private summary table, and stops its own sessions on completion,
failure or interruption. It never prints the API key, viewer URLs or app URLs.
The default backend is **production**; `REVYL_MAESTRO_API_URL` selects a different
authorized backend and is also passed as the CLI's `REVYL_BACKEND_URL` for
start/stop. A web fixture server needs a worker-reachable public HTTPS URL—local
`e2e/serve_fixtures` alone is not enough. See
[`docs/live-suite.md`](docs/live-suite.md) for prerequisites, artifact hosting,
fixture exposure, timeout limits and cleanup caveats. Running each command is
the explicit session-creation/cleanup action; building alone does not run devices.

## Remaining limits and cleanup

Proxy, arbitrary browser/CDP access and AI operations remain unsupported. Web
flows require the capability-gated contract above. Keyboard dismissal, media, recording, airplane mode, appearance
reads, screenshot comparison/cropping, random input, and travel are wired to the
worker contract and are available when the attached worker advertises its strict
capability flags. The worker's
coordinate-based input is not equivalent to typing into current focus; the
accepted runtime input-fallback limitation is described above. Without the
app-lifecycle capability, the app must already be running and only the
[non-resetting launch](examples/non-resetting-launch.yaml) form (`stopApp: false`,
`permissions: {}`) runs.

The adapter does not replay a failed or uncertain request, and stops all remaining
actions when an acknowledgment fails. **This is not an end-to-end at-most-once
guarantee:** worker/device providers can retry internally or fall back to another
transport after partial execution. Repeated HTTP taps, including `doubleTapOn`,
do not guarantee the platform's native double-tap timing window. Use trusted flows,
avoid concurrent controllers, and inspect the session before manually retrying a
timed-out action. Runtime limitations can produce a terminal unsupported response;
`--platform ios` alone does not establish simulator capabilities.

Exiting detaches locally; it does **not** stop the Revyl session. When finished,
stop only a session you own:

```bash
revyl device stop --session-id YOUR_RUNNING_SESSION_UUID
```

## License

Copyright (c) 2026 Revyl. Licensed under the [Apache License 2.0](LICENSE).
Third-party components retain their own licenses; see the
[third-party notices](THIRD_PARTY_NOTICES.md). This repository publishes source,
not a prebuilt distribution of the resolved dependencies. Contributor boundaries
and validation requirements are maintained in [AGENTS.md](AGENTS.md).
