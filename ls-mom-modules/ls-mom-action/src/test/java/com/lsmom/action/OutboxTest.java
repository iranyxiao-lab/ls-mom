package com.lsmom.action;

import com.lsmom.core.event.ConsumerReceipts;
import com.lsmom.core.event.DomainEvent;
import com.lsmom.core.event.OutboxDestination;
import com.lsmom.core.event.OutboxWorker;
import com.lsmom.test.CoreFixture;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 5.7：Outbox 同事务、SKIP LOCKED 投递、退避重试、补偿列表、手工重投、消费幂等、归档。 */
class OutboxTest {

    static final List<String> delivered = new CopyOnWriteArrayList<>();
    static final AtomicBoolean downstreamDown = new AtomicBoolean(false);

    static class Notify implements OutboxDestination {
        final ConsumerReceipts receipts;

        Notify(ConsumerReceipts receipts) {
            this.receipts = receipts;
        }

        public String name() { return "test-notify"; }

        public boolean accepts(String type) { return type.startsWith("ls.core.Work."); }

        public void deliver(DomainEvent e) throws Exception {
            if (downstreamDown.get()) throw new java.net.SocketTimeoutException("通知服务超时");
            if (receipts.firstTime(name(), e.idempotencyKey() + ":" + e.eventId())) delivered.add(e.eventId().toString());
        }

        public int maxAttempts() { return 3; }
    }

    private final ActionFixture f = new ActionFixture(List.of(new Notify(new ConsumerReceipts(com.lsmom.test.PgTestSupport.jdbc()))));

    private int outboxOf(String workId) {
        return f.count("select count(*) from core_outbox o join core_domain_event e on e.event_id = o.event_id where e.subject_id = ? and e.subject_concept = 'ls.core.Work'", workId);
    }

    @Test
    void 业务回滚无Outbox_成功后投递() {
        CoreFixture.Plant p = f.core.plant();
        String id = f.newWork(ActionFixture.admin(), p.teamA(), "outbox");
        assertThat(outboxOf(id)).isEqualTo(1);
        assertThatThrownBy(() -> f.on("ls.core.Work.testWriteThenFail", ActionFixture.admin(), id, 0, "{}"));
        assertThat(outboxOf(id)).isEqualTo(1);

        downstreamDown.set(false);
        drain();
        UUID ev = f.events.eventsOf("ls.core.Work", id).get(0).eventId();
        assertThat(delivered).contains(ev.toString());
        assertThat(f.outbox.entriesOf(ev)).extracting(OutboxWorker.OutboxEntry::status).containsExactly("DELIVERED");
    }

    @Test
    void 下游超时退避重试达上限进入补偿列表并可手工重投() {
        CoreFixture.Plant p = f.core.plant();
        downstreamDown.set(true);
        String id = f.newWork(ActionFixture.admin(), p.teamA(), "timeout");
        UUID ev = f.events.eventsOf("ls.core.Work", id).get(0).eventId();
        try {
            drain();
            OutboxWorker.OutboxEntry entry = f.outbox.entriesOf(ev).get(0);
            assertThat(entry.status()).isEqualTo("DEAD");
            assertThat(entry.attempts()).isEqualTo(3);
            assertThat(entry.lastError()).contains("超时");
            assertThat(f.outbox.deadLetters("test-notify")).extracting(OutboxWorker.OutboxEntry::eventId).contains(ev);
        } finally {
            downstreamDown.set(false);
        }
        long outboxId = f.outbox.entriesOf(ev).get(0).id();
        f.outbox.redeliver(outboxId);
        drain();
        assertThat(f.outbox.entriesOf(ev).get(0).status()).isEqualTo("DELIVERED");
        assertThatThrownBy(() -> f.outbox.redeliver(outboxId)).extracting("code").isEqualTo("OUTBOX_NOT_DEAD");
    }

    @Test
    void 退避时间按尝试次数增长() {
        CoreFixture.Plant p = f.core.plant();
        OutboxWorker slow = new OutboxWorker(f.core.jdbc, f.destinations, f.txm, f.core.clock, Duration.ofSeconds(10));
        downstreamDown.set(true);
        try {
            String id = f.newWork(ActionFixture.admin(), p.teamA(), "backoff");
            UUID ev = f.events.eventsOf("ls.core.Work", id).get(0).eventId();
            f.core.jdbc.update("update core_outbox set next_attempt_at = now() - interval '1 day' where status = 'PENDING' and event_id <> ?", ev);
            for (int i = 0; i < 20 && slow.deliverBatch(50) > 0; i++) { }
            OutboxWorker.OutboxEntry e = f.outbox.entriesOf(ev).get(0);
            assertThat(e.status()).isEqualTo("PENDING");
            assertThat(e.attempts()).isEqualTo(1);
            assertThat(e.nextAttemptAt()).isAfter(java.time.LocalDateTime.now().plusSeconds(5));
        } finally {
            downstreamDown.set(false);
        }
    }

    @Test
    void 重复投递消费方只处理一次_归档不影响业务() {
        CoreFixture.Plant p = f.core.plant();
        downstreamDown.set(false);
        String id = f.newWork(ActionFixture.admin(), p.teamA(), "dedup");
        drain();
        UUID ev = f.events.eventsOf("ls.core.Work", id).get(0).eventId();
        long before = delivered.stream().filter(ev.toString()::equals).count();
        f.core.jdbc.update("update core_outbox set status = 'PENDING', next_attempt_at = now() where event_id = ?", ev);
        drain();
        assertThat(delivered.stream().filter(ev.toString()::equals).count()).isEqualTo(before);

        f.core.jdbc.update("update core_outbox set delivered_at = now() - interval '40 days' where event_id = ?", ev);
        assertThat(f.outbox.archiveDelivered(Duration.ofDays(30))).isGreaterThanOrEqualTo(1);
        assertThat(f.outbox.entriesOf(ev)).isEmpty();
        assertThat(f.core.works.get(Long.parseLong(id)).title()).isEqualTo("dedup");
        assertThat(f.events.eventsOf("ls.core.Work", id)).isNotEmpty();
    }

    private void drain() {
        for (int i = 0; i < 50; i++) {
            if (f.outbox.deliverBatch(100) == 0) break;
        }
    }
}
