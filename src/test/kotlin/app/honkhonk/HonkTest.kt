package app.honkhonk

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import java.net.ServerSocket
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HonkTest {
    @Test
    fun `serialises every field with the openapi names`() = runBlocking {
        MockServer(Step.accepted()).use { s ->
            val accepted = client(s.url).send(
                Message(
                    "Billing API could not connect to Redis after 3 attempts.",
                    title = "Redis connection failed", severity = Severity.LONG, priority = Priority.HIGH,
                    category = Category.INFRASTRUCTURE, source = "billing-api", environment = "production",
                    channel = "infrastructure", groupKey = "billing/redis/connectivity", eventType = EventType.PROBLEM,
                    occurredAt = Instant.parse("2026-10-01T21:10:00Z"), url = "https://example.com/incidents/redis",
                    imageUrl = "https://cdn.example.com/a.jpg?w=1&h=2",
                    metadata = mapOf("host" to "app-01", "attempts" to 3, "retried" to true, "ratio" to 0.5),
                    ttlSeconds = 3600, sourceSequence = 42,
                    actions = listOf(Action("Runbook", "https://example.com/runbooks/redis"), Action("Call on-call", "tel:+15550134")),
                ),
            )
            assertEquals(Accepted("msg_01k6h3w4z5x6y7z8a9b0c1d2e3", false, Instant.parse("2026-10-02T21:10:00.123Z")), accepted)
            val r = s.requests.single()
            assertEquals("/v1/messages", r.path)
            assertEquals("Bearer $TEST_KEY", r.header("Authorization"))
            assertEquals("application/json", r.header("Content-Type"))
            assertEquals("honk-kotlin/${Honk.VERSION}", r.header("User-Agent"))
            assertTrue(UUID_V7.matches(r.header("Idempotency-Key")!!))
            assertEquals(
                """{"title":"Redis connection failed","message":"Billing API could not connect to Redis after 3 attempts.","severity":"error","priority":"high","category":"infrastructure","source":"billing-api","environment":"production","channel":"infrastructure","group_key":"billing/redis/connectivity","event_type":"problem","occurred_at":"2026-10-01T21:10:00.000Z","url":"https://example.com/incidents/redis","image_url":"https://cdn.example.com/a.jpg?w=1&h=2","actions":[{"title":"Runbook","url":"https://example.com/runbooks/redis"},{"title":"Call on-call","url":"tel:+15550134"}],"metadata":{"host":"app-01","attempts":3,"retried":true,"ratio":0.5},"ttl_seconds":3600,"source_sequence":42}""",
                r.body,
            )
        }
    }

    @Test
    fun `minimal message and defaults`() = runBlocking {
        MockServer(Step.accepted()).use { s ->
            val honk = client(s.url, defaults = Defaults("cron", "production", ""))
            honk.send(Message("a"))
            honk.send(Message("b", source = "laravel", channel = "requests"))
            honk.send(Message("c", actions = emptyList()))
            assertEquals("""{"message":"a","source":"cron","environment":"production"}""", s.requests[0].body)
            assertEquals("""{"message":"b","source":"laravel","environment":"production","channel":"requests"}""", s.requests[1].body)
            assertEquals("""{"message":"c","source":"cron","environment":"production"}""", s.requests[2].body)
        }
    }

    @Test
    fun `helpers and the honk scale`() = runBlocking {
        MockServer(Step.accepted()).use { s ->
            val honk = client(s.url)
            honk.light("a", "m")
            honk.beep("b", "m")
            honk.loud("c", "m") { groupKey("disk/var") }
            honk.long("d", "m")
            honk.blast("e", "m") { severity("light") }
            honk.problem("db/backup", "Backup failed", "exit 1") { idempotencyKey("p-1") }
            honk.recovery("db/backup", "Backup OK", "exit 0")
            honk.problem("q", "Queue", "failing") { severity(Severity.BLAST) }
            honk.warning(null, "synonym")
            val r = s.requests
            assertEquals(listOf("info", "success", "warning", "error", "critical", "error", "success", "critical", "warning"), r.map { it.json["severity"] })
            assertEquals("disk/var", r[2].json["group_key"])
            assertEquals("problem", r[5].json["event_type"])
            assertEquals("p-1", r[5].header("Idempotency-Key"))
            assertEquals("recovery", r[6].json["event_type"])
            assertNull(r[8].json["title"])
        }
        assertTrue(Severity.LOUD === Severity.WARNING && Severity.BLAST === Severity.CRITICAL)
        assertEquals(Severity.WARNING, Severity.parseOrNull(" LOUD "))
        assertNull(Severity.parseOrNull("fatal"))
        assertEquals("loud", Severity.WARNING.horn)
        assertFailsWith<HonkValidationException> { Severity.parse("fatal") }
    }

    @Test
    fun `honk, uhoh and phew send exactly what send, problem and recovery send`() = runBlocking {
        MockServer(Step.accepted()).use { s ->
            val honk = client(s.url)
            val order = Message("Someone bought 3 rubber chickens", title = "New order", severity = Severity.BEEP, category = Category.SALES)
            honk.honk(order, "order-1042")
            honk.send(order, "order-1042")
            honk.uhoh("kitchen/freezer", "Freezer door open", "Open for 4 minutes") { priority(Priority.HIGH); idempotencyKey("freezer-1") }
            honk.problem("kitchen/freezer", "Freezer door open", "Open for 4 minutes") { priority(Priority.HIGH); idempotencyKey("freezer-1") }
            honk.phew("kitchen/freezer", "Freezer closed", "The ice cream never knew.") { idempotencyKey("freezer-2") }
            honk.recovery("kitchen/freezer", "Freezer closed", "The ice cream never knew.") { idempotencyKey("freezer-2") }
            honk.honk(order)
            honk.uhoh("q", null, "failing") { groupKey("ignored"); severity(Severity.BLAST) }
            val r = s.requests
            assertEquals(8, r.size)
            for (i in 0 until 6 step 2) {
                assertEquals(r[i].body, r[i + 1].body)
                assertEquals(r[i].header("Idempotency-Key"), r[i + 1].header("Idempotency-Key"))
            }
            assertEquals("order-1042", r[0].header("Idempotency-Key"))
            assertEquals("""{"title":"New order","message":"Someone bought 3 rubber chickens","severity":"success","category":"sales"}""", r[0].body)
            assertEquals(
                """{"title":"Freezer door open","message":"Open for 4 minutes","severity":"error","priority":"high","group_key":"kitchen/freezer","event_type":"problem"}""",
                r[2].body,
            )
            assertEquals("freezer-1", r[2].header("Idempotency-Key"))
            assertEquals(
                """{"title":"Freezer closed","message":"The ice cream never knew.","severity":"success","group_key":"kitchen/freezer","event_type":"recovery"}""",
                r[4].body,
            )
            assertTrue(UUID_V7.matches(r[6].header("Idempotency-Key")!!))
            assertEquals("""{"message":"failing","severity":"critical","group_key":"q","event_type":"problem"}""", r[7].body)
        }
        assertEquals(Message.problem("db/backup", "Backup failed", "exit 1").build(), Message.uhoh("db/backup", "Backup failed", "exit 1").build())
        assertEquals(Message.recovery("db/backup", null, "exit 0").build(), Message.phew("db/backup", null, "exit 0").build())
    }

    @Test
    fun `retries reuse the idempotency key and body`() = runBlocking {
        MockServer(Step.error(503, "unavailable", "0"), Step.error(500, "internal"), Step.Drop, Step.accepted()).use { s ->
            client(s.url).send(Message("x"))
            assertEquals(4, s.requests.size)
            assertEquals(1, s.requests.map { it.header("Idempotency-Key") }.toSet().size)
            assertEquals(1, s.requests.map { it.body }.toSet().size)
        }
    }

    @Test
    fun `retries a timed-out attempt with the same key`() = runBlocking {
        MockServer(Step.Respond(202, "{}", delayMs = 600), Step.accepted()).use { s ->
            client(s.url, timeoutMs = 150).send(Message("x"), "k-1")
            assertEquals(listOf("k-1", "k-1"), s.requests.map { it.header("Idempotency-Key") })
        }
    }

    @Test
    fun `honours Retry-After`() = runBlocking {
        MockServer(Step.error(429, "rate_limited", "1"), Step.accepted()).use { s ->
            val start = System.nanoTime()
            client(s.url).send(Message("x"))
            assertTrue(System.nanoTime() - start >= 1_000_000_000)
        }
    }

    @Test
    fun `Retry-After beyond the deadline fails fast`() = runBlocking {
        MockServer(Step.error(429, "quota_exceeded", "7200", ""","limit":"messages_per_day"""")).use { s ->
            val start = System.nanoTime()
            val e = assertFailsWith<HonkQuotaException> { client(s.url).send(Message("x"), "q-1") }
            assertEquals("quota_exceeded", e.code)
            assertEquals(429, e.status)
            assertEquals(Duration.ofSeconds(7200), e.retryAfter)
            assertEquals(1, e.attempts)
            assertEquals("q-1", e.idempotencyKey)
            assertTrue(e.isRetryable)
            assertTrue(System.nanoTime() - start < 1_000_000_000)
            assertEquals(1, s.requests.size)
        }
    }

    @Test
    fun `gives up after retries`() = runBlocking {
        MockServer(Step.error(503, "unavailable", "0")).use { s ->
            val e = assertFailsWith<HonkServerException> { client(s.url, retries = 2).send(Message("x")) }
            assertEquals(3, e.attempts)
            assertEquals("req_test", e.requestId)
            assertEquals(3, s.requests.size)
        }
    }

    @Test
    fun `stops at the deadline`() = runBlocking {
        MockServer(Step.error(503, "unavailable", "1")).use { s ->
            val start = System.nanoTime()
            assertFailsWith<HonkServerException> { client(s.url, retries = 10, deadlineMs = 1_500).send(Message("x")) }
            assertTrue(System.nanoTime() - start < 1_600_000_000)
            assertEquals(2, s.requests.size)
        }
    }

    @Test
    fun `timeouts become HonkTimeoutException`() = runBlocking {
        MockServer(Step.Respond(202, "{}", delayMs = 500)).use { s ->
            val e = assertFailsWith<HonkTimeoutException> { client(s.url, timeoutMs = 50, retries = 1).send(Message("x")) }
            val network: HonkNetworkException = e // timeouts are network errors
            assertTrue(network.isRetryable)
            assertEquals("timeout", e.code)
            assertEquals(2, e.attempts)
        }
    }

    @Test
    fun `connection refused becomes HonkNetworkException`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val e = assertFailsWith<HonkNetworkException> { client("http://127.0.0.1:$port", retries = 1).send(Message("x")) }
        assertFalse(e is HonkTimeoutException)
        assertEquals("network_error", e.code)
        assertEquals(2, e.attempts)
        assertTrue(UUID_V7.matches(e.idempotencyKey!!))
    }

    @Test
    fun `coroutine cancellation propagates`() = runBlocking {
        MockServer(Step.Respond(202, "{}", delayMs = 2_000)).use { s ->
            assertFailsWith<TimeoutCancellationException> { withTimeout(100) { client(s.url).send(Message("x")) } }
        }
    }

    @Test
    fun `error mapping is never retried`() = runBlocking {
        val cases = listOf(
            Step.error(422, "validation_failed", extra = ""","fields":[{"field":"severity","code":"invalid_enum","message":"must be one of"}]""") to HonkValidationException::class,
            Step.error(413, "payload_too_large") to HonkValidationException::class,
            Step.error(401, "invalid_key") to HonkAuthException::class,
            Step.error(403, "project_suspended") to HonkAuthException::class,
            Step.error(409, "idempotency_conflict") to HonkConflictException::class,
            Step.error(404, "not_found") to HonkException::class,
        )
        for ((step, type) in cases) {
            MockServer(step).use { s ->
                val e = assertFailsWith<HonkException> { client(s.url).send(Message("x")) }
                assertEquals(type, e::class, "${step.status}")
                assertEquals(step.status, e.status)
                assertEquals("req_test", e.requestId)
                assertEquals(1, e.attempts)
                assertFalse(e.isRetryable)
                if (e is HonkValidationException) assertFalse(e.isLocal)
                assertEquals(1, s.requests.size)
            }
        }
    }

    @Test
    fun `server refusals expose code, fields and request id`() = runBlocking {
        MockServer(Step.error(422, "validation_failed", extra = ""","fields":[{"field":"event_type","code":"invalid_enum","message":"must be one of event, problem, recovery"}]""")).use { s ->
            val e = assertFailsWith<HonkValidationException> { client(s.url).send(Message("x")) }
            assertEquals(listOf(FieldError("event_type", "invalid_enum", "must be one of event, problem, recovery")), e.fields)
            assertEquals("validation_failed", e.code)
            assertEquals("req_test", e.requestId)
            assertFalse(e.isLocal)
            assertEquals(
                "Honk 422 validation_failed: validation_failed happened (event_type must be one of event, problem, recovery) [request req_test]",
                e.message,
            )
        }
        MockServer(Step.error(422, "unknown_field", extra = ""","fields":[{"field":"colour","code":"not_allowed"}]""")).use { s ->
            val e = assertFailsWith<HonkValidationException> { client(s.url).send(Message("x")) }
            assertEquals("unknown_field", e.code)
            assertTrue(e.message!!.contains("(colour not_allowed) [request req_test]"), e.message)
        }
        MockServer(Step.error(401, "invalid_key")).use { s ->
            val e = assertFailsWith<HonkAuthException> { client(s.url).send(Message("x")) }
            assertEquals("Honk 401 invalid_key: invalid_key happened [request req_test]", e.message)
        }
    }

    @Test
    fun `warnings are returned and passed to onWarnings`() = runBlocking {
        val warnings = """[{"field":"title","code":"truncated","message":"title was 212 characters; shortened to 160"},""" +
            """{"field":"actions[1].url","code":"ignored","message":"not an https, mailto, tel or sms URL; action left out"}]"""
        val seen = mutableListOf<Pair<List<Warning>, String>>()
        MockServer(Step.accepted(warnings = warnings), Step.accepted()).use { s ->
            val honk = client(s.url, onWarnings = { w, accepted -> seen += w to accepted.id })
            val accepted = honk.send(Message("x"))
            val expected = listOf(
                Warning("title", "truncated", "title was 212 characters; shortened to 160"),
                Warning("actions[1].url", "ignored", "not an https, mailto, tel or sms URL; action left out"),
            )
            assertEquals(expected, accepted.warnings)
            assertEquals("title truncated: title was 212 characters; shortened to 160", accepted.warnings[0].toString())
            assertEquals(listOf(expected to "msg_01k6h3w4z5x6y7z8a9b0c1d2e3"), seen)
            assertEquals(emptyList(), honk.send(Message("y")).warnings)
            assertEquals(1, seen.size) // not called without warnings
        }
        MockServer(Step.accepted(warnings = warnings)).use { s ->
            val accepted = client(s.url, onWarnings = { _, _ -> error("logger down") }).send(Message("x"))
            assertEquals(2, accepted.warnings.size) // a failing hook never fails the send
        }
        MockServer(Step.Respond(202, """{"id":"msg_old","duplicate":false}""")).use { s ->
            assertEquals(emptyList(), client(s.url).send(Message("x")).warnings) // an older server without warnings
        }
    }

    @Test
    fun `field contents are sent unchecked`() = runBlocking {
        MockServer(Step.accepted()).use { s ->
            client(s.url).send(
                Message(
                    "bell\u0007", title = "t".repeat(200), groupKey = "g".repeat(200), url = "http://example.com",
                    ttlSeconds = 5, metadata = mapOf("bad key" to 1, "n" to Double.NaN),
                    actions = listOf(Action("Open", "javascript:alert(1)"), Action("a", "tel:1"), Action("b", "tel:2"), Action("c", "tel:3")),
                ),
                "k-1",
            )
            client(s.url).send(Message("recovered", eventType = EventType.RECOVERY, sourceSequence = 3))
            client(s.url).phew(null, "Backup OK", "fine")
            val r = s.requests
            assertEquals(3, r.size)
            assertEquals("t".repeat(200), r[0].json["title"])
            assertEquals(5L, r[0].json["ttl_seconds"])
            assertEquals("http://example.com", r[0].json["url"])
            assertEquals(mapOf("bad key" to 1L, "n" to "NaN"), r[0].json["metadata"])
            assertEquals(4, (r[0].json["actions"] as List<*>).size)
            assertEquals("""{"message":"recovered","event_type":"recovery","source_sequence":3}""", r[1].body)
            assertEquals("""{"title":"Backup OK","message":"fine","severity":"success","event_type":"recovery"}""", r[2].body)
        }
    }

    @Test
    fun `a message or a title is enough, neither is refused before sending`() = runBlocking {
        MockServer(Step.accepted()).use { s ->
            val honk = client(s.url)
            honk.send(Message(title = "Deploy finished"))
            honk.send(Message.builder().title("Backup OK").build())
            honk.beep("Title only", "")
            assertEquals(
                listOf("""{"title":"Deploy finished"}""", """{"title":"Backup OK"}""", """{"title":"Title only","severity":"success"}"""),
                s.requests.map { it.body },
            )
            for (m in listOf(Message(""), Message("  ", title = " "))) {
                val e = assertFailsWith<HonkValidationException> { honk.send(m) }
                assertTrue(e.isLocal)
                assertEquals(0, e.attempts)
                assertEquals(listOf(FieldError("message", "required", "is required (send a message or a title)")), e.fields)
            }
            assertFailsWith<HonkValidationException> { Message.builder().build() }
            assertEquals(3, s.requests.size)
        }
    }

    @Test
    fun `proxy html error is retried then reported`() = runBlocking {
        MockServer(Step.Respond(502, "<html>Bad Gateway</html>")).use { s ->
            val e = assertFailsWith<HonkServerException> { client(s.url, retries = 1).send(Message("x")) }
            assertEquals(502, e.status)
            assertNull(e.code)
            assertEquals(2, s.requests.size)
        }
    }

    @Test
    fun `redirects are reported, not followed`() = runBlocking {
        MockServer(Step.Respond(301, "", mapOf("Location" to "https://honk.example.com/v1/messages"))).use { s ->
            val e = assertFailsWith<HonkException> { client(s.url).send(Message("x")) }
            assertTrue(e.message!!.contains("redirect to https://honk.example.com"))
            assertEquals(1, s.requests.size)
        }
    }

    @Test
    fun `duplicate and explicit key`() = runBlocking {
        MockServer(Step.accepted(duplicate = true)).use { s ->
            assertTrue(client(s.url).send(Message("x"), "deploy-4812").duplicate)
            assertEquals("deploy-4812", s.requests[0].header("Idempotency-Key"))
        }
    }

    @Test
    fun `builder and idempotency keys a header cannot carry`() = runBlocking {
        val m = Message.builder().title("New request").line("Customer: Ana").line("Company: Acme").loud().meta("request_id", "1").build()
        assertEquals(Message("Customer: Ana\nCompany: Acme", title = "New request", severity = Severity.WARNING, metadata = mapOf("request_id" to "1")), m)
        val reply = Action("Reply", "mailto:emily@example.com?subject=Your%20quote")
        val withActions = Message.builder().message("Emily asked for a quote").action(reply.title, reply.url).actions(listOf(Action("Call", "tel:+15550134"))).build()
        assertEquals(listOf(reply, Action("Call", "tel:+15550134")), withActions.actions)
        assertEquals(withActions, withActions.toBuilder().build())
        assertEquals(3, withActions.toBuilder().action("Open", "https://example.com").build().actions.size)
        assertEquals(2, withActions.actions.size) // the builder copies, never shares
        assertEquals(Severity.CRITICAL, Message.builder().message("x").severity("BLAST").build().severity)

        val honk = Honk("https://honk.example.com", TEST_KEY)
        for (key in listOf("", "x".repeat(129), "ünicode", "line\nbreak")) {
            val e = assertFailsWith<HonkValidationException> { honk.send(Message("x"), key) }
            assertEquals("Idempotency-Key", e.fields.first().field)
            assertTrue(e.isLocal)
        }
    }

    @Test
    fun `json round trip`() {
        val text = Json.write(mapOf("s" to "a\"b\\c\n\u0001é", "n" to 1, "d" to 1.25, "b" to true, "z" to null, "l" to listOf(1, "x"), "i" to Double.POSITIVE_INFINITY))
        assertEquals("""{"s":"a\"b\\c\n\u0001é","n":1,"d":1.25,"b":true,"z":null,"l":[1,"x"],"i":"Infinity"}""", text)
        assertEquals(mapOf("s" to "a\"b\\c\n\u0001é", "n" to 1L, "d" to 1.25, "b" to true, "z" to null, "l" to listOf(1L, "x"), "i" to "Infinity"), Json.parse(text))
        assertEquals(mapOf("u" to "é/"), Json.parse("""{ "u" : "é\/" }"""))
    }

    @Test
    fun `sendAsync and sendBlocking`() {
        MockServer(Step.accepted(), Step.error(401, "invalid_key")).use { s ->
            val honk = client(s.url)
            assertEquals("msg_01k6h3w4z5x6y7z8a9b0c1d2e3", honk.sendAsync(Message("x")).get().id)
            assertFailsWith<HonkAuthException> { honk.sendBlocking(Message("y")) }
            honk.close()
        }
    }

    @Test
    fun `concurrent sends share one client`() = runBlocking {
        MockServer(Step.accepted()).use { s ->
            val honk = client(s.url)
            (1..20).map { async { honk.light("t$it", "m") } }.forEach { it.await() }
            assertEquals(20, s.requests.size)
            delay(1)
        }
    }

    @Test
    fun `construction rejects missing or malformed url and key`() {
        val cases = listOf(
            Triple("", TEST_KEY, "HONK_URL"), Triple("honk.example.com", TEST_KEY, "https://"),
            Triple("https://h", "", "HONK_KEY"), Triple("https://h", "hka_mobile", "honk_"),
        )
        for ((url, key, needle) in cases) {
            val e = assertFailsWith<IllegalArgumentException> { Honk(url, key) }
            assertTrue(e.message!!.contains(needle), e.message)
        }
        assertEquals("https://honk.example.com", Honk("https://honk.example.com/v1/messages/", TEST_KEY).url)
    }

    @Test
    fun `fromEnvironment and UUIDv7 and Retry-After`() {
        val honk = Honk.fromEnvironment(mapOf("HONK_URL" to "https://honk.example.com", "HONK_KEY" to TEST_KEY, "HONK_SOURCE" to "cron"))
        assertEquals(Defaults("cron"), honk.defaults)

        val a = UuidV7.generate(1_700_000_000_000)
        val b = UuidV7.generate(1_700_000_000_001)
        assertTrue(UUID_V7.matches(a) && a < b)
        assertEquals("018bcfe56800", a.take(13).replace("-", ""))

        val now = Instant.parse("2026-10-02T10:00:00Z")
        assertEquals(Duration.ofSeconds(3), Honk.parseRetryAfter("3"))
        assertEquals(Duration.ofSeconds(120), Honk.parseRetryAfter(" 120 "))
        assertNull(Honk.parseRetryAfter(null))
        assertNull(Honk.parseRetryAfter("soon"))
        assertEquals(Duration.ofSeconds(30), Honk.parseRetryAfter("Fri, 2 Oct 2026 10:00:30 GMT", now))
        assertEquals(Duration.ZERO, Honk.parseRetryAfter("Fri, 2 Oct 2026 09:00:00 GMT", now))
    }
}
