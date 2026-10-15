# Changelog

All notable changes to `app.honk-honk:honk` (Maven) are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [0.4.0] - 2026-10-15

### Changed
- The Maven coordinate is now `app.honk-honk:honk` and the package `app.honkhonk`.

## [0.3.0] - 2026-10-14

### Changed
- **Breaking:** the Maven artifact is now `app.honk-me:honk` (it was `app.honk-me:sdk`). The
  package stays `app.honkme`, so imports don't change.

  Upgrading: point the dependency at `implementation("app.honk-me:honk:0.3.0")` (Maven:
  `<artifactId>honk</artifactId>`) and drop the old artifact; no code changes.

### Added
- `honk(message, idempotencyKey)`, `uhoh(groupKey, title, message)` and
  `phew(groupKey, title, message)` suspend functions on `Honk`.
- For Java: `honkBlocking` and `honkAsync` on `Honk`, and the `Message.uhoh(groupKey, title,
  message)` and `Message.phew(…)` builders.
- `send`, `problem`, `recovery`, `sendBlocking`, `sendAsync`, `Message.problem` and
  `Message.recovery` stay, without deprecation warnings, as plain aliases of the new names:
  same arguments, same results.
- `Message.actions`: up to 3 `Action(title, url)` buttons (`https://`, `mailto:`, `tel:` or
  `sms:`), sent as `actions` and omitted when empty; `Message.Builder.action(title, url)` and
  `actions(list)`. Validated locally like the server, with errors on `actions`,
  `actions[i].title` and `actions[i].url`; `Limits.ACTIONS` and `Limits.ACTION_TITLE`.

## [0.1.0] - 2026-10-04

### Added
- `Honk` client for `POST /v1/messages` on `java.net.http.HttpClient` (JDK 17+): a `suspend`
  `send`, plus `sendAsync` (`CompletableFuture`) and `sendBlocking` for Java; `Honk.builder()`,
  `Honk.fromEnvironment()`, `AutoCloseable`.
- `Message` data class with every field of the v1 ingestion API (including `imageUrl`) and a
  fluent `Message.Builder` (`Message.builder().title(…).loud().build()`), plus static helper
  builders `Message.loud(title, message)`, `Message.problem(groupKey, …)`, ….
- The Honk scale: `Severity.LIGHT/BEEP/LOUD/LONG/BLAST` aliases of `INFO … CRITICAL`,
  `Severity.parse()`, and suspend helpers `light`, `beep`, `loud`, `long`, `blast` (plus the
  `info` … `critical` synonyms), `problem`, `recovery`.
- Automatic UUIDv7 `Idempotency-Key` (or your own), reused on every retry; retries for network
  errors, timeouts, 429 and 5xx with exponential backoff, full jitter, `Retry-After` and a total
  deadline; redirects are reported, never followed.
- Unchecked exceptions `HonkValidationException`, `HonkAuthException`, `HonkQuotaException`,
  `HonkConflictException`, `HonkNetworkException`, `HonkTimeoutException`,
  `HonkServerException` (base `HonkException`); local validation of every field.
- Runtime dependencies: `kotlin-stdlib` and `kotlinx-coroutines-core` only (hand-written JSON).
