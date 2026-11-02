# Maestro web worker contract

This documents the `--platform web` contract implemented by companion
[`cognisim-monorepo` PR #4831 at `0be342a`](https://github.com/RevylAI/cognisim-monorepo/pull/4831).
The driver has loopback contract coverage; neither that coverage nor the worker's
local Chromium fixtures establishes cloud deployment or live browser compatibility.

## Investigation

The pinned Maestro 2.10 `maestro-client` has Selenium `WebDriver` and
`CdpWebDriver` implementations. They launch and own Chrome, inject the bundled
`maestro-web.js`, walk the DOM (including iframe handling), and perform browser
input. They retry some script/DOM operations and quit their browser on close.
Reusing them unchanged would violate this runner's existing-session and
no-adapter-replay guarantees. Their DOM attribute/bounds semantics are the source
for the proxy contract below; this runner still uses Maestro's parser and Orchestra.

The Cognisim sources inspected are `cognisim_python_cli/workers/run_web_worker.py`,
`cognisim_python_cli/web_runtime/`,
`cognisim_backend/app/services/web_worker_proxy.py`, and shared
`runtime_supervisor_schema.py`. The original web routes exposed authenticated
screenshots, coordinate taps, focused input, keys, navigation/history, scrolling,
and entry-origin state clearing through the normal device proxy, but lacked
Maestro DOM/CSS/frame queries. The companion adds the typed `maestro_web_*`
routes, health advertisement and bounded DOM responses described below. Generic
`hierarchy` is not a substitute. Ordinary web responses retain the 64 KiB limit;
only Maestro hierarchy/query responses can reach 16 MiB.

The runtime's CDP bridge is private sandbox infrastructure. The web branch of
`streaming/worker-connection` returns video metadata, not an externally usable
CDP URL. No public authenticated CDP WebSocket relay exists. The driver therefore
does not accept or expose private CDP URLs, launch a local browser, use visual
grounding as a substitute for DOM selectors, or forward API keys to a browser.

## Authorization and capability

All endpoints below live under the existing authenticated backend path:

`/api/v1/execution/device-proxy/{workflow_run_id}/{action}`

The backend must retain org/session ownership, running/control permission,
fence/revocation checks, request bounds and safe error handling. No caller-selected
worker URL or CDP endpoint is accepted. New DOM routes are specific opt-in
diagnostic/automation reads, not a new browser-debugging tunnel.

`GET health` preserves the strict booleans `ready`, `current_fence`,
`first_frame_observed`, and `supports_maestro_web` through backend validation.
All four must be true before any flow mutation. The companion derives Maestro
support from a bounded (at most two-second), current-generation/session CDP
adapter probe, a current fence, report readiness and a fresh frame. Unsupported
adapters remain false; missing, false or malformed flags fail closed before
navigation or input. A single unsupported element does not disable the whole
adapter: it returns a terminal per-element error as specified below.

## Existing routes reused

- `GET screenshot`: existing PNG bytes. Backend verifies its screen token/hash
  and fences as today. Driver retains PNG integrity and dimension checks.
- `POST navigate {url}` and `POST back {}`: existing navigation and history, with
  safe absolute HTTP(S) URL policy. Ordinary launch preserves state and navigates
  the already-authorized browser; it does not create another browser/session.
- `POST key {key}`: existing `Enter`, `Backspace`, `Home` names. Erasure sends the
  requested bounded Backspace sequence, never an invented clear-all operation.
- `POST maestro_web_scroll {}`: Maestro web's viewport scroll semantics: regular
  pages scroll by half the current viewport and Flutter pages use their smooth
  Flutter-specific path. This is distinct from coordinate wheel scrolling.
- `POST drag {start_x,start_y,end_x,end_y,duration_ms}` and `POST longpress
  {x,y,duration_ms:3000}`: existing screenshot-pixel actions. Driver converts
  grid points to screenshot pixels and limits drag duration to 5000 ms.

## New typed reads

`GET maestro_web_info` returns:

```json
{"width_pixels":1280,"height_pixels":720,"width_grid":1280,"height_grid":720}
```

Grid units are visual-viewport-normalized CSS units; image units are screenshot
pixels. At 2× pinch zoom, a 640-pixel viewport remains a 640-unit grid while a
document-CSS x coordinate of 40 reports grid x=80. The worker handles zoom,
visual viewport offsets and affine frame transforms. The driver passes hierarchy,
query and tap grid coordinates through unchanged; it must not apply zoom again.
Only screenshot-pixel actions such as drag and long press multiply grid points
by the reported image/grid scale. Dimensions
are positive integers, grid up to 3840×2160 and image up to 11520×6480, at most
16 million image pixels, exactly uniform scale 1–3. Reads must describe the
current active authorized tab.

`GET maestro_web_hierarchy` returns one root node:

```json
{"attributes":{"text":"","bounds":{"left":0,"top":0,"right":1280,"bottom":720}},"children":[{"attributes":{"text":"Username","resource-id":"user-name","bounds":{"left":20,"top":40,"right":240,"bottom":80}},"children":[]}]}
```

`POST maestro_web_query {css}` is a **read**, returning `{"nodes":[node,...]}`.
CSS is at most 8192 UTF-8 bytes and contains no control characters. Query nodes
must have exactly the same own attributes as their counterparts in the full
hierarchy (children may be empty): Maestro's CSS filter joins on that identity.
Backend must
authorize/classify this as read-only rather than recording it as an input action.

Nodes follow Maestro's bundled web script: required text and four integer bounds,
optional `resource-id`, strict booleans `selected`, `synthetic`, and
`ignoreBoundsFiltering`; required children array. Bounds use the normalized grid, bounded
to -100000…1000000, nonnegative width/height, with zero-area structural wrappers
allowed. Each result is limited to 10000 nodes/depth 100; normal response size
limit must be raised specifically for these reads to at most 16 MiB. Preserve
visible text, inputs' value/placeholder behavior, textarea contents, element IDs,
Flutter semantics-identifier precedence, and iframe geometry. Same-origin,
cross-origin/OOPIF and opaque sandbox frames use isolated CDP contexts and
box-model affine transforms bound to the authorized active page. Traversal is
limited to 100 frames, with aggregate 10000-node/depth-100/16-MiB limits.
Unavailable element contexts must never appear as an empty page. Empty valid
query arrays are allowed; malformed results fail terminally.

## New mutations for semantics missing from current routes

- `POST maestro_web_tap {x,y}` takes normalized grid or Maestro synthetic coordinates
  (0…1000000). Match Maestro web tapping: scroll an off-viewport target into view,
  resolve synthetic-coordinate elements using the current DOM snapshot, then
  dispatch exactly one click. Do not pass synthetic coordinates to screenshot
  pixel input, choose a target by visual grounding, or retry an uncertain click.
- `POST maestro_web_input {text}` appends into the currently focused editable
  element without clicking/clearing, including iframe focus. It must implement
  Maestro's native HTML date-input path (e.g. compact `02111990` becomes
  `1990-02-11`), textarea/contenteditable and ordinary text input, with exact
  native caret positioning for email and number inputs. Generic `Input.insertText`
  alone does not establish date-input equivalence. Text uses
  this driver's existing valid-Unicode/16 KiB bounds. Never use secrets.
- `POST maestro_web_launch {url,arguments}` navigates the existing browser and
  installs bounded typed launch arguments with Maestro web's page-script binding
  semantics. It is used only when launch arguments are nonempty. Browser-global
  collisions are rejected; bindings are not reinstalled by diagnostic reads or
  ordinary later navigation.
- `POST maestro_web_clear_state {url}` clears cookies/storage for the named safe
  origin, matching Maestro's origin clearing. Existing `clear_state {}` is not a
  substitute: it clears only the runtime's configured entry origin, which can
  differ from an overridden flow launch URL. No browser shutdown is implied.

All mutations return the existing RuntimeActionResponse shape: strict
`success:true`, exact action name (except existing `longpress` uses `long_press`),
finite nonnegative latency, null/absent error, and strict `execution_uncertain:false`.
Any malformed, negative, uncertain or transport response is terminal;
the adapter sends no replay. Existing backend/provider retries remain outside
an end-to-end at-most-once guarantee.

## Per-element unsupported responses

Singular/projective frame geometry, unavailable element contexts, and temporal
widgets without exact Maestro date semantics return HTTP 501 with
`{"error":"unsupported_web_element"}`. This is a per-element failure, not a
reason to return `supports_maestro_web:false` for the adapter. Adapter-level
unsupported responses may retain `not_available_on_platform`.

The driver treats HTTP 501 and either structured error code (a string `error`
or an `error.code` object) as terminal `AdapterFailure`, never a
`MaestroException`. This also covers structured failures in successful-status
read/action bodies. It displays only its fixed safe message, never worker or
customer text. Optional commands, retries and completion hooks cannot turn these
failures into continuation or replay.

## Lifecycle, supported subset and verification

Like Maestro web, stop/kill, permission changes, keychain clearing and keyboard
dismissal are local no-ops, never requests to stop the Revyl session/browser.
Mobile location/travel/orientation/appearance/airplane/media/recording commands are
rejected before attachment rather than approximated. Proxy commands, arbitrary
browser JavaScript/CDP, dialogs/downloads and other unimplemented driver operations
remain unsupported. Compat mode adds Orchestra's flow JS, not browser debug access.

The pinned `cli-2.10.0` web workspace contains 13 tagged YAML flows, not 14. Offline
preflight acceptance proves command compatibility only. The companion implements
cross-origin/sandbox frame traversal, affine geometry, visual zoom, Flutter
scrolling, caret/date input, synthetic taps and origin clearing with local
sandboxed Chromium coverage. Deployment, date widgets, iframe focus, DOM
extraction and state-reset fidelity still require authorized cloud-browser
testing. See
`docs/web-conformance.md` for fixture hosting: local loopback pages are not
automatically reachable by a cloud browser.
