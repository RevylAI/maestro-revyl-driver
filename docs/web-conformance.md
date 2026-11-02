# Web conformance harness

`conformance/maestro-e2e.sh --platform web --workspace web` classifies Maestro's
`e2e/workspaces/web` flows offline. The pinned `cli-2.10.0` workspace contains
**13 top-level YAML flows**, not 14. Platform-tag filtering excludes flows tagged
for the other platforms. No session, fixture server, or URL replacement is needed
for an offline run; add `--maestro-compat` to classify compatibility mode.

## Live fixtures

Live runs attach only to an existing authorized web session. Fixture flows use
`http://127.0.0.1:7357`, which addresses the worker browser's machine, not this
harness's machine. When those URLs occur, `--session` requires `--fixtures-url`
with a public-safe HTTP(S) base that the worker browser can reach:

```bash
conformance/maestro-e2e.sh --maestro-compat --platform web --workspace web \
  --session YOUR_RUNNING_SESSION_UUID \
  --fixtures-url https://fixtures.example.org
```

Serve the contents of the pinned checkout's `e2e/workspaces/web/fixtures/` at that
base, preserving filenames and relative paths. Maestro's own server can be run
separately in a terminal:

```bash
.revyl-maestro/upstream-cli-2.10.0/e2e/serve_fixtures
```

That script binds to local IPv4 loopback on port 7357 (`FIXTURES_PORT` overrides
it). Starting it does **not** make it accessible to a cloud browser. An explicitly
configured public-safe relay or fixture host is still required. Stop a separately
started server yourself when finished; the harness never starts or owns one.

The base must not contain credentials, query strings, fragments, whitespace, or
YAML quoting/interpolation characters. Localhost and non-public IP literals are
rejected; hostname validation does not establish DNS resolution or worker-side
reachability. Use only non-sensitive fixture pages and a non-secret URL.

For live fixture runs, the harness copies the entire web workspace into a private
temporary directory and replaces only the literal fixture origin in YAML bytes.
The origin must end at a URL boundary, so another port or hostname suffix is not
rewritten. Other URLs, scripts, comments, and YAML formatting are preserved except
for matching origin bytes. Referenced scripts/assets retain their relative paths;
no YAML is parsed or reserialized by the harness. Upstream files remain unchanged,
and the temporary copy is removed on exit. Reports remain private under the
selected output directory. Acceptance by offline preflight does not prove live
browser compatibility or fixture reachability.
