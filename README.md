# Honk! Honk! for Kotlin and Java

[![CI](https://github.com/honk-hq/kotlin/actions/workflows/ci.yml/badge.svg)](https://github.com/honk-hq/kotlin/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/app.honk-honk/honk)](https://central.sonatype.com/artifact/app.honk-honk/honk)

Official Kotlin and Java client for [Honk! Honk!](https://honk-honk.app), the inbox that turns events
from your apps, scripts, cron jobs and CI into calm, grouped push notifications on your phone.

```kotlin
import app.honkhonk.Honk

val honk = Honk.fromEnvironment()

honk.uhoh("kitchen/freezer", "Freezer door open", "−4 °C and rising")
// someone closes it with their hip
honk.phew("kitchen/freezer", "Freezer closed", "Back to −18 °C. The ice cream never knew.")
```

`uhoh` opens an incident for `kitchen/freezer`, `phew` closes it: one group on your phone that
starts with a problem and ends with the fix, not two separate alarms.

- JDK 17+, `java.net.http.HttpClient` (keep-alive, HTTP/2 over https).
- Kotlin: `suspend` API with cancellation. Java: `honkBlocking`, `honkAsync`
  (`CompletableFuture`), builders and static helpers.
- Retries with backoff, `Retry-After`, a total deadline and an idempotency key on every send,
  so a retry never creates a duplicate.
- Runtime dependencies: `kotlin-stdlib` and `kotlinx-coroutines-core`, nothing else.

The ingestion key (`honk_…`) is a secret: use this library on servers, in jobs and CLIs. Never
ship it inside an Android or desktop app; have the app call your backend instead.

Create a project and an ingestion key at [honk-honk.app](https://honk-honk.app). Its
*Integrations* page generates ready-to-paste code for this library.

## Install

```kotlin
// build.gradle.kts
dependencies { implementation("app.honk-honk:honk:0.4.0") }
```

```xml
<!-- pom.xml -->
<dependency>
  <groupId>app.honk-honk</groupId>
  <artifactId>honk</artifactId>
  <version>0.4.0</version>
</dependency>
```

Everything is in the package `app.honkhonk` (`import app.honkhonk.Honk`).

## Quick start

Kotlin:

```kotlin
import app.honkhonk.Honk

val honk = Honk.fromEnvironment()                 // HONK_URL, HONK_KEY (+ HONK_SOURCE, HONK_ENVIRONMENT, HONK_CHANNEL)
honk.beep("Backup finished", "nightly pg_dump took 42 s")   // suspend
```

Java:

```java
import app.honkhonk.Honk;
import app.honkhonk.Message;

Honk honk = Honk.fromEnvironment();
honk.honkBlocking(Message.beep("Backup finished", "nightly pg_dump took 42 s").build());
```

Explicitly: `Honk(url, key)` in Kotlin (named arguments for the options), or
`Honk.builder().url(url).key(key).timeoutMs(3000).build()` in Java. A missing or malformed URL
or key throws `IllegalArgumentException`. Create one `Honk` per application and `close()` it on
shutdown.

## The Honk scale

Five levels. The Honk app shows them by their standard names (Info, Success, Warning, Error,
Critical); people who prefer horn sounds can switch on the Honk names (Light, Beep, Loud, Long,
Blast) with the "Honk names for levels" switch in Settings. The SDK takes both: the canonical values and the
Honk names as input aliases, in any case, and always sends the canonical value.

| Level | Honk name | Severity | Kotlin helper | Java builder | Constant |
|---|---|---|---|---|---|
| Info | Light | `light` (info) | `honk.light(title, message)` | `Message.light(title, message)` / `.light()` | `Severity.LIGHT` |
| Success | Beep | `beep` (success) | `honk.beep(…)` | `Message.beep(…)` / `.beep()` | `Severity.BEEP` |
| Warning | Loud | `loud` (warning) | `honk.loud(…)` | `Message.loud(…)` / `.loud()` | `Severity.LOUD` |
| Error | Long | `long` (error) | `honk.long(…)` | `Message.longHonk(…)` / `.longHonk()` (`long` is a Java keyword) | `Severity.LONG` |
| Critical | Blast | `blast` (critical) | `honk.blast(…)` | `Message.blast(…)` / `.blast()` | `Severity.BLAST` |

`Severity.LOUD === Severity.WARNING`; `Severity.parse("LOUD")` and the builder's
`severity("loud")` accept horn and canonical names in any case. The level alone decides how the
push interrupts: Info and Success normal, Warning and Error high, Critical time-sensitive (it
breaks through Focus). `info` … `critical` remain as synonyms.

## Recipe: notify me when a customer asks for something (Spring Boot)

Code only: the library has no Spring dependency.

```java
@Configuration
class HonkConfig {
    @Bean(destroyMethod = "close")
    Honk honk(@Value("${honk.url}") String url, @Value("${honk.key}") String key) {
        return Honk.builder().url(url).key(key).build();          // one client, keep-alive
    }
}

@Service
class CustomerRequestNotifier {
    private static final Logger log = LoggerFactory.getLogger(CustomerRequestNotifier.class);
    private final Honk honk;

    CustomerRequestNotifier(Honk honk) { this.honk = honk; }

    // After the request is saved: never block the customer's request on the network.
    @TransactionalEventListener
    void on(CustomerRequestCreated event) {
        CustomerRequest r = event.request();
        Message message = Message.light("New request: " + r.subject(), r.name() + " (" + r.company() + ") asked: " + r.body())
            .category(Category.CUSTOMERS)
            .channel("requests")
            .groupKey("requests/" + r.id())                       // one group per request
            .url("https://shop.example.com/admin/requests/" + r.id())   // https only
            .meta("request_id", String.valueOf(r.id()))
            .build();
        honk.honkAsync(message, "request-" + r.id())              // same request, same key
            .exceptionally(e -> { log.warn("honk: {}", e.getMessage()); return null; });
    }
}
```

Kotlin (Ktor, a coroutine-based service, …):

```kotlin
scope.launch {
    honk.light("New request: ${r.subject}", "${r.name} (${r.company}) asked: ${r.body.take(2000)}") {
        category(Category.CUSTOMERS)
        groupKey("requests/${r.id}")
        url("https://shop.example.com/admin/requests/${r.id}")
        idempotencyKey("request-${r.id}")
    }
}
```

## Grouping in three lines

Messages with the same `groupKey` (per project, environment, source and channel) form one group:
the first one pushes, repeats update it calmly instead of buzzing again. Use one key per customer
request (`requests/<id>`), and a shared key only for repeats of the same problem
(`queue/failed-jobs`). The key is optional: without one, messages group by their title (pass
`null` to `uhoh`/`phew` for that).

## Sending

```kotlin
suspend fun honk(message: Message, idempotencyKey: String? = null): Accepted       // id, duplicate, receivedAt, warnings
fun honkAsync(message: Message, idempotencyKey: String? = null): CompletableFuture<Accepted>   // Java
fun honkBlocking(message: Message, idempotencyKey: String? = null): Accepted                    // Java
```

| Field | Notes |
|---|---|
| `message` | text, line breaks allowed (`line()` appends one); kept up to 8192 bytes UTF-8 |
| `title` | one line, kept up to 160 characters; default: first line of `message`. **A `message` or a `title` is required** |
| `severity` | the level: `LIGHT` `BEEP` `LOUD` `LONG` `BLAST` (or `INFO` … `CRITICAL`); decides how loud the push is |
| `priority` | `LOW` `NORMAL` `HIGH` `URGENT`, stored for compatibility (only `LOW` still matters: a silent push below `LONG`); `URGENT` from a key without *allow urgent* becomes `HIGH` |
| `category` | `INFRASTRUCTURE` `SECURITY` `BACKUPS` `DEPLOYMENTS` `PAYMENTS` `CUSTOMERS` `SALES` `AUTOMATION` `PERSONAL` `OTHER` |
| `source` / `environment` / `channel` | ≤ 64 / 32 / 64 characters; default `api` / `default` / `general` or `Defaults` |
| `groupKey` | optional; without it messages group by title; longer than 128 characters is hashed |
| `eventType` | `EVENT` `PROBLEM` `RECOVERY` |
| `occurredAt` | `Instant`, sent as UTC RFC 3339 with milliseconds |
| `url` / `imageUrl` | `https://` only, no credentials (`imageUrl`: no `#fragment`; fetched by the server afterwards) |
| `actions` (`action(title, url)`) | up to 3 `Action(title, url)` buttons, see [Actions](#actions) |
| `metadata` (`meta(k, v)`) | ≤ 16 keys `[A-Za-z0-9_.-]{1,64}`; String (≤ 512 characters), Number or Boolean values |
| `ttlSeconds` | push lifetime 60–86400 (default 3600) |
| `sourceSequence` | 0 … 2^53-1, orders problems and recoveries |
| `idempotencyKey` | 1–128 printable ASCII characters (also a `honk` argument) |

null and empty optional fields are omitted. The library does not check the other fields: the
server delivers first, so a value that is too long is shortened and one it cannot use is left out,
and the message is accepted with a [warning](#warnings). A returned `Accepted` means Honk! Honk!
**durably stored** the message (202), not that a push was delivered or read.

```kotlin
honk.loud("Disk 91%", "/var on app-01") { groupKey("disk/app-01/var") }
honk.uhoh("db/backup", "Backup failed", "pg_dump exited with 1")         // a problem: a Long message by default
honk.phew("db/backup", "Backup OK", "pg_dump finished in 41 s")          // its recovery: a Beep message by default
honk.honk(Message("Imported 1 204 rows", severity = Severity.BEEP, channel = "imports"))
```

`send`, `problem`, `recovery`, `sendBlocking`, `sendAsync`, `Message.problem` and
`Message.recovery` still work: they are `honk`, `uhoh`, `phew`, `honkBlocking`, `honkAsync`,
`Message.uhoh` and `Message.phew` under their older names, with no deprecation warning.

```java
honk.honkBlocking(Message.uhoh("db/backup", "Backup failed", "pg_dump exited with 1").meta("exit", 1).build());
honk.honkBlocking(Message.phew("db/backup", "Backup OK", "pg_dump finished in 41 s").build());
honk.honkAsync(Message.builder().title("Disk 91%").message("/var on app-01").loud().build());
```

Options (Kotlin named arguments or `Honk.builder()`): `timeoutMs` 5000 per attempt, `retries` 4,
`deadlineMs` 30000, `defaults`, `backoff`, `httpClient` (configure it never to follow redirects),
`userAgent`, `onWarnings`.

## Warnings

When the server corrects something it still accepts the message (`202`), and says what it did:
`Accepted.warnings` is a list of `Warning(field, code, message)`, empty when nothing was changed.
Codes: `truncated`, `normalized`, `ignored`, `clamped`, `downgraded`, `hashed`. They are never
thrown; log them and fix the sender when convenient.

```kotlin
val accepted = honk.honk(Message("Imported 1 204 rows", ttlSeconds = 5))
accepted.warnings.forEach { println(it) }   // ttl_seconds clamped: …

val honk = Honk(url, key, onWarnings = { warnings, accepted -> warnings.forEach { log.warn("honk {}: {}", accepted.id, it) } })
```

```java
Honk honk = Honk.builder().url(url).key(key)
    .onWarnings((warnings, accepted) -> warnings.forEach(w -> log.warn("honk {}: {}", accepted.getId(), w)))
    .build();
```

`onWarnings` runs only for a send with warnings; an exception it throws is ignored.

## Actions

Up to three buttons on a message, in display order (the first is the primary). Honk! Honk! never opens
them; the phone does when you tap one: Mail for `mailto:`, the Phone app for `tel:`, Messages for
`sms:`, Safari for `https://`.

```kotlin
honk.light("New quote request", "Maria Tudor asked for a quote: 3 rooms, 2 bathrooms") {
    groupKey("requests/4812")
    action("Reply", "mailto:maria@example.com?subject=Your%20quote")
    action("Call", "tel:+15550134")
}
```

Java uses the same builder method (`Message.light(…).action("Call", "tel:+15550134").build()`);
the constructor takes `actions = listOf(Action(title, url))`. A title is 1–40 characters on one
line, shown as sent. A URL is at most 2048 bytes without spaces: `https://` (no credentials),
`mailto:` with one address and an optional `?subject=…&body=…`, `tel:` with a number, or `sms:`
with a number and an optional `?body=…`. The server leaves out an action it cannot use
(`http:`, `javascript:`, app schemes) with an `ignored` warning on `actions[i].url`, and still
delivers the message.

## Retries and idempotency, guaranteed

- Every send carries an `Idempotency-Key`: yours, or a fresh UUIDv7 (`UuidV7.generate()`).
  **The same key is reused on every retry.** Within 24 h Honk! Honk! answers a replay with the original
  id and `duplicate == true`, so a lost response never creates a second message.
- Only network errors, timeouts, `429` and `5xx` are retried, with exponential backoff and full
  jitter (`random(0, min(8 s, 0.5 s·2ⁿ))`), never sooner than the server's `Retry-After`.
- Everything stops at `deadlineMs`: if the next wait would cross it (for example a daily quota
  that resets at midnight), the exception is thrown at once with `retryAfter`.
- `4xx` other than `429` are never retried. Coroutine cancellation (and cancelling the
  `CompletableFuture` of `honkAsync`) stops the send.

## Errors

All exceptions are unchecked and extend `HonkException` (`status`, `code`, `requestId`,
`idempotencyKey`, `attempts`, `retryAfter`, `isRetryable`). The exception message names the
status, the code, the server's message, every refused field and the request id, e.g.
`Honk 422 validation_failed: … (event_type must be one of event, problem, recovery) [request req_…]`.

| Exception | When | What to do |
|---|---|---|
| `HonkValidationException` | the server refused it (`400 invalid_json`, `413`, `415`, `422 unknown_field`, an invalid `event_type`); `fields` lists every refused field. `isLocal`: no message and no title, nothing sent | fix the message |
| `HonkAuthException` | `401 invalid_key`, `403 project_suspended`, `workspace_suspended` | fix the key |
| `HonkQuotaException` | `429 quota_exceeded` (daily) or `rate_limited`, after retries | retry after `retryAfter` |
| `HonkConflictException` | `409 idempotency_conflict`: same key, different payload | new key or original payload |
| `HonkNetworkException` / `HonkTimeoutException` | unreachable / no answer before the deadline | retry later, same key |
| `HonkServerException` | `5xx` on every attempt | retry later, same key |
| `HonkException` | anything else (wrong URL → 404, a redirect) | fix the URL |

```java
try {
    honk.honkBlocking(Message.longHonk("Payment failed", "Stripe declined order 1042").groupKey("payments/stripe").build());
} catch (HonkValidationException e) {
    log.error("bug: {}", e.getFields());
} catch (HonkException e) {
    if (e.isRetryable()) queue.retryLater(e.getIdempotencyKey(), e.getRetryAfter());
    else throw e;
}
```

## Why a hand-written JSON codec

The request is one flat object (plus a flat `metadata` map) and the answers are two small fixed
shapes. A ~150-line encoder/parser (`Json.kt`, fully tested) keeps the dependency tree at
`kotlin-stdlib` + `kotlinx-coroutines-core`: no kotlinx-serialization compiler plugin to configure
in your build, no Jackson/Gson version conflicts for Java users, and an 89 KB jar.
`kotlinx-coroutines-core` is the one deliberate dependency: it makes `withTimeout`,
cancellation and `CompletableFuture` interop correct.

## Development

```sh
./gradlew test                                        # JUnit 5, JDK HttpServer mock, Kotlin + Java tests
HONK_URL=… HONK_KEY=… ./gradlew integrationTest       # against a real server (use a test project's key)
./gradlew publishToMavenLocal                          # jar, sources, Dokka javadoc and POM (signed only when a key is configured)
```

Any JDK 17+ runs the build; Gradle downloads a JDK 17 toolchain if the machine has none. The
version lives in `gradle.properties` (`VERSION_NAME`); `Honk.VERSION` is generated from it.
Releases: push a tag `vX.Y.Z` matching `VERSION_NAME` and the release workflow publishes to
Maven Central (see `CHANGELOG.md`).

## Links

- [honk-honk.app](https://honk-honk.app): the Honk! Honk! inbox (web, iPhone).
- Other SDKs: [Node.js](https://github.com/honk-hq/node),
  [PHP / Laravel](https://github.com/honk-hq/php), [Go + CLI](https://github.com/honk-hq/go),
  [Swift](https://github.com/honk-hq/honk-swift).

MIT License.
