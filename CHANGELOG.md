# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]
### Changed
- The `:dev` profile now pins `clj-telemetry 0.5.0-SNAPSHOT` instead of
  `0.3.1-SNAPSHOT`, so `lein test` exercises this library against the
  clj-telemetry it is actually deployed beside.  The old pin resolved a build
  from May 2025 carrying `opentelemetry-api 1.37.0`; the suite had therefore
  never run against the current library or the current OpenTelemetry API
  (1.62.0).  Nothing outside the `:dev` profile changed, and the suite is green
  against the new pin — but green against a LOCAL install of the current
  clj-telemetry source, which shadows the published `0.5.0` snapshot in the
  local repository.  That published snapshot is older than the source it was
  built from and stays older until clj-telemetry is next deployed, so on a
  machine without the local install this pin resolves the earlier artifact and
  the run proves less than it does here.

### Fixed
- `opentelemetry.middleware/with-span` was defined twice, and the definition that
  won made every span a root of a new trace, never closed the `Scope` it opened,
  and installed no clj-http middleware for its body.  The leaked `Scope` left the
  ended span current on the thread, so every later log line on that thread carried
  a dead span id.  There is now one definition, which:
  - parents the span on the span that is current on the thread, and roots only
    when no valid span is current;
  - closes the `Scope` through `with-open`, so whatever was current before the
    body is current again after it, on the exceptional path too;
  - records an exception thrown by the body on the span (`exception.escaped`
    true) and rethrows it;
  - installs `clj-http-with-telemetry-span-middleware` for the body, so a
    clj-http call made inside the span injects `traceparent` and the service it
    calls continues the same trace.

  The macro's name, arity and return value are unchanged.

- `get-tracer` memoizes, and `get-open-telemetry` answers the noop instance
  until something is registered, so ONE `get-tracer` call made before
  `create-open-telemetry!` cached a noop tracer for the life of the process:
  every later `with-span` then started an invalid span, silently — no
  exception, no `traceparent`, no `traceID=` on any log line, and no way to
  tell it from "telemetry is off".

  `get-tracer` now holds an invariant instead: **the cache only ever contains a
  tracer built from a registered instance.**  With nothing registered it
  returns an uncached tracer from the noop instance on every call, so there is
  nothing to go stale; the first call made after an instance is installed
  builds from that instance and caches it.  The instance is read once and the
  tracer is built from exactly the value that decided whether to cache it, so a
  `get-tracer` racing an in-progress registration cannot cache the noop either.
  (`set-tracer!` is unchanged: it is set-if-absent, so it installs a tracer of
  the caller's own only when none is cached yet.)

- `extract-trace-context` handed a non-map carrier (a string, a vector — any
  wire shape nobody expected) would throw from the `TextMapGetter` as soon as
  a propagator iterated its keys.  It now reads only maps and answers nil
  otherwise: losing a trace is a reporting loss, and it must never cost the
  work that was carrying it.

### Added
- `opentelemetry.middleware/current-trace-context` — the current W3C trace context
  as a map of header name to header value, or nil when no valid span is current.
- `opentelemetry.middleware/extract-trace-context` — those headers back into an
  `io.opentelemetry.context.Context`, or nil when the result holds no valid span.
  Header names may be strings or keywords: a carrier that has been through JSON
  usually comes back keywordized (`cheshire/parse-string ... true`), and reading
  only the string name would lose the trace silently.
- `opentelemetry.middleware/with-trace-context` — run a body with such headers
  current on this thread, always releasing the `Scope`.
- `opentelemetry.middleware/without-trace-context` — run a body with NO trace
  context current: the root context is made current, so `Span/current` is
  invalid, `current-trace-context` returns nil and any `with-span` inside
  starts a new root.  For work that is not part of the caller's trace but runs
  on the caller's thread — an administrative request that re-creates a batch of
  previously-planned work inline, where capturing the request's span would put
  thousands of unrelated items on one trace rooted at an operator's button.
- `opentelemetry.middleware/wrap-with-current-context` — a Clojure fn that runs
  another fn under the `Context` that was current when it was wrapped.

  OpenTelemetry's current context is a Java thread-local: neither `future` nor
  `bound-fn` carries it, so work handed to an executor, put on a queue, or
  serialized to another process arrives with no trace and starts a fresh root.
  These four are the two halves of carrying it across such a boundary.

## [0.1.1] - 2019-06-23
### Changed
- Documentation on how to make the widgets.

### Removed
- `make-widget-sync` - we're all async, all the time.

### Fixed
- Fixed widget maker to keep working when daylight savings switches over.

## 0.1.0 - 2019-06-23
### Added
- Files from the new template.
- Widget maker public API - `make-widget-sync`.

[Unreleased]: https://github.com/your-name/ring-correlation-id/compare/0.1.1...HEAD
[0.1.1]: https://github.com/your-name/ring-correlation-id/compare/0.1.0...0.1.1
