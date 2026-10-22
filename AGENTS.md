# Revyl Maestro adapter

This standalone repository owns a Kotlin/JVM Maestro adapter.
Revyl services and upstream Maestro are external dependencies. Other repositories'
agent policies do not automatically apply here.

- Attach only to an existing authorized Revyl session. Closing the CLI must close
  local resources, never stop the session, app, or device.
- `conformance/live-suite.sh` is the explicitly invoked testing exception: it may
  create its own sessions and must stop them on completion, failure or interruption.
  Never invoke that suite without authorization for its target environment. Keep
  session handshakes, viewer tokens and signed app URLs out of public output.
- Execute Maestro's published parser, models, and Orchestra runtime. Do not add a
  second YAML interpreter or claim this is a stock Maestro CLI plugin.
- Validate the complete parsed flow before attachment. Strict mode keeps the
  supported subset explicit and rejects optional commands, scripts, hooks, and
  recovery/replay options. The user-approved `--maestro-compat` mode is the
  deliberate exception: it accepts Maestro 2.10 command semantics and delegates
  parsing, interpolation, GraalJS (including `http`), hooks, loops, retries, and
  conditions to the pinned Orchestra runtime. Compat mode still scrubs
  `REVYL_API_KEY` and values containing the process key from JS, keeps artifacts
  private, gates capabilities before mutation, bounds requests and total flow time, and latches
  uncertain proxy responses; it never adds adapter-level replay.
- Distinguish intentional bounded tap/key sequences from error recovery. In strict
  mode bound expanded commands, counts, delays, text, and coordinates; in compat
  mode the overall flow deadline bounds Orchestra's loops and recovery semantics.
  An uncertain result must latch a terminal failure before any remaining action.
- Preserve native operation semantics. Never implement text input by deliberately
  retapping, app-specific lifecycle by an untargeted operation, or a missing
  state read with a cached guess. The explicitly user-approved legacy viewer
  input path is an exception to a strict no-refocus guarantee: runtime passthrough
  can fall back to provider input. Omit coordinates, request skip-tap/no-clear,
  and document that ACKs prove handler completion, not field or selection state.
- Gate deployment-dependent gestures on explicit boolean capabilities from the
  attached session's authenticated worker health before any flow mutation.
  Missing or malformed capabilities must fail closed. Preserve requested gesture
  duration, and validate dynamically copied text before viewer input.
- Require the process-death, WebView, and explicit extended-iOS-permission
  capabilities before mutation. The approved iOS `all` exception uses the
  worker's supported legacy subset when the extended-permission flag is absent;
  never describe that result as confirming all extended permissions changed.
- Web sessions require the strict `supports_maestro_web` capability before any
  mutation. Keep proposed web routes explicitly documented as undeployed until
  worker/backend support is verified. Use typed authenticated backend routes,
  never private CDP addresses, a locally launched browser, or visual grounding as
  a substitute for Maestro's DOM/CSS semantics. Browser closing and mobile-only
  operations must not acquire accidental session-wide effects.
- For input, discover the attached workflow's existing viewer WebSocket only via
  the authenticated backend streaming worker-connection route. Establish it before
  any flow mutation. Never accept caller-supplied worker URLs or forward API auth
  to the worker. Validate workflow/status and bounded URLs; require WSS outside
  literal loopback, no userinfo/fragments, redirects, reconnects, or resends.
- Send one UUID-correlated MANUAL_INPUT at a time. Only a matching ACTION_ACK with
  action input and strict boolean success confirms completion; receipts and other
  IDs do not. Bound messages, the send queue, and waits; latch failures before any
  later mutation. Close only local resources. Warn that open live viewers can
  consume ACKs and the legacy runtime may log/report typed text; never use secrets.
- Keep API credentials in the process environment only. Never log YAML, selectors,
  provider responses, hierarchy, credentials, or customer content. Keep output
  artifacts private and ignored by Git.
- Use authenticated backend routes and the narrowly permitted discovered viewer
  input channel, bounded requests, and no redirects or retries.
  Transport and unsupported-operation errors must not be MaestroException types.
  Do not infer provider at-most-once execution from adapter no-replay behavior.
- Test through real Maestro YAML and Orchestra against loopback fixtures. Passing
  offline tests does not establish real-device or cloud compatibility.
- Keep dependency selection and licensing notes explicit. Update Gradle lockfiles
  through Gradle, not by hand. Run `./gradlew --no-daemon clean check installDist`
  before handing off changes and keep README capability claims aligned with the code.
- Strip runtime credentials from dependency, build, and test subprocesses. Use
  only loopback fixture credentials in offline tests, including installed-CLI tests.
- `--maestro-compat` is accepted by both run and `--validate-only`; compatibility
  mode is opt-in and must never weaken strict-mode defaults. Compat-mode JavaScript
  can make network requests through Maestro's `http` helper and can read flow or
  Maestro environment values, but never the Revyl API key.
- Remote repository creation, publication, commits, pushes, and live-device or
  cloud operations require separate explicit authorization.
