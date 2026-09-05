# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]
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
