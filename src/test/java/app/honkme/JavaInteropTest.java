package app.honkme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** The Java-facing API: builders, static aliases, blocking and CompletableFuture sends. */
class JavaInteropTest {
    @Test
    void javaApi() throws Exception {
        try (MockServer server = new MockServer(Step.Companion.accepted(false));
             Honk honk = Honk.builder().url(server.getUrl()).key(MockServerKt.TEST_KEY).timeoutMs(3000).retries(2).build()) {

            Accepted accepted = honk.sendBlocking(Message.builder().title("Disk 91%").message("/var on app-01").loud().build());
            assertEquals("msg_01k6h3w4z5x6y7z8a9b0c1d2e3", accepted.getId());

            CompletableFuture<Accepted> future = honk.sendAsync(
                Message.problem("db/backup", "Backup failed", "pg_dump exited with 1").meta("exit", 1).build(), "backup-2026-10-03");
            assertEquals(false, future.get().getDuplicate());

            honk.sendBlocking(Message.blast("Payments down", "Stripe answers 500").build());
            honk.sendBlocking(new Message("constructor with defaults"));
            honk.sendBlocking(Message.longHonk("Job failed", "exit 1").build());
            honk.sendBlocking(Message.builder().message("x").longHonk().build());
            honk.sendBlocking(Message.light("New quote request", "Emily asked for a quote")
                .action("Reply", "mailto:emily@example.com?subject=Your%20quote")
                .actions(List.of(new Action("Call", "tel:+15550134")))
                .build());

            Map<String, Object> second = server.getRequests().get(1).getJson();
            assertEquals("problem", second.get("event_type"));
            assertEquals("error", second.get("severity"));
            assertEquals("backup-2026-10-03", server.getRequests().get(1).header("Idempotency-Key"));
            assertEquals("critical", server.getRequests().get(2).getJson().get("severity"));
            assertEquals("error", server.getRequests().get(4).getJson().get("severity"));
            assertEquals("error", server.getRequests().get(5).getJson().get("severity"));
            assertEquals(
                List.of(Map.of("title", "Reply", "url", "mailto:emily@example.com?subject=Your%20quote"), Map.of("title", "Call", "url", "tel:+15550134")),
                server.getRequests().get(6).getJson().get("actions"));
        }
        assertSame(Severity.WARNING, Severity.LOUD);
        assertSame(Severity.CRITICAL, Severity.parse("blast"));
        assertEquals(Duration.ofSeconds(5), Honk.parseRetryAfter("5"));
    }

    @Test
    void javaPlayfulNamesSendWhatTheOldNamesSend() throws Exception {
        try (MockServer server = new MockServer(Step.Companion.accepted(false));
             Honk honk = Honk.builder().url(server.getUrl()).key(MockServerKt.TEST_KEY).build()) {
            Message order = Message.beep("New order", "Someone bought 3 rubber chickens").build();
            honk.honkBlocking(order, "order-1042");
            honk.sendBlocking(order, "order-1042");
            honk.honkAsync(Message.uhoh("kitchen/freezer", "Freezer door open", "Open for 4 minutes").build(), "freezer-1").get();
            honk.sendAsync(Message.problem("kitchen/freezer", "Freezer door open", "Open for 4 minutes").build(), "freezer-1").get();
            honk.honkBlocking(Message.phew("kitchen/freezer", "Freezer closed", "The ice cream never knew.").build(), "freezer-2");
            honk.sendBlocking(Message.recovery("kitchen/freezer", "Freezer closed", "The ice cream never knew.").build(), "freezer-2");
            honk.honkBlocking(order);
            honk.honkAsync(order).get();

            List<Recorded> r = server.getRequests();
            assertEquals(8, r.size());
            for (int i = 0; i < 6; i += 2) {
                assertEquals(r.get(i).getBody(), r.get(i + 1).getBody());
                assertEquals(r.get(i).header("Idempotency-Key"), r.get(i + 1).header("Idempotency-Key"));
            }
            assertEquals("order-1042", r.get(0).header("Idempotency-Key"));
            assertEquals("success", r.get(0).getJson().get("severity"));
            assertEquals("problem", r.get(2).getJson().get("event_type"));
            assertEquals("error", r.get(2).getJson().get("severity"));
            assertEquals("freezer-1", r.get(2).header("Idempotency-Key"));
            assertEquals("recovery", r.get(4).getJson().get("event_type"));
            assertEquals("success", r.get(4).getJson().get("severity"));
            assertEquals("kitchen/freezer", r.get(4).getJson().get("group_key"));
            assertTrue(MockServerKt.getUUID_V7().matches(r.get(6).header("Idempotency-Key")));
            assertTrue(MockServerKt.getUUID_V7().matches(r.get(7).header("Idempotency-Key")));
        }
    }

    @Test
    void javaExceptions() {
        try (MockServer server = new MockServer(Step.Companion.error(429, "quota_exceeded", "3600", ""));
             Honk honk = new Honk(server.getUrl(), MockServerKt.TEST_KEY)) {
            HonkQuotaException e = assertThrows(HonkQuotaException.class, () -> honk.sendBlocking(Message.of("x")));
            assertEquals(Duration.ofHours(1), e.getRetryAfter());
            assertTrue(e.isRetryable());
        }
        assertThrows(HonkValidationException.class, () -> Message.builder().title("missing text").build());
        assertThrows(IllegalArgumentException.class, () -> new Honk("", "honk_x"));
    }
}
