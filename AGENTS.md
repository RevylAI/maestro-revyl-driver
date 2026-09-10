# Revyl Maestro adapter

This standalone repository owns a Kotlin/JVM Maestro adapter.
Revyl services and upstream Maestro are external dependencies. Other repositories'
agent policies do not automatically apply here.

- Attach only to an existing authorized Revyl session. Closing the CLI must close
  local resources, never stop the session, app, or device.
- Execute Maestro's published parser, models, and Orchestra runtime. Do not add a
  second YAML interpreter or claim this is a stock Maestro CLI plugin.
- Validate the complete parsed flow before attachment. Keep the supported subset
  explicit and reject unsupported commands, hooks, scripts, and recovery/replay
  options. Source-option guards may reject information Maestro normalizes or
  drops, but must not translate or execute YAML independently of Maestro.
- Distinguish intentional bounded tap/key sequences from error recovery. Bound
  expanded commands as well as individual counts, delays, text, and coordinates.
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
- Remote repository creation, publication, commits, pushes, and live-device or
  cloud operations require separate explicit authorization.
