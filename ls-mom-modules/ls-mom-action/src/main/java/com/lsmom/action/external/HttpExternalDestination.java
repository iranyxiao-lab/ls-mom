package com.lsmom.action.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lsmom.core.event.DomainEvent;
import com.lsmom.core.event.OutboxDestination;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 通过 HTTP 把事件过账到外部系统（ERP/OA）。事件由命名 Operation 产生；入队时登记"待同步"，
 * 外部 2xx 才标记已同步，否则记失败并由 Outbox 退避重试，超过上限进入补偿列表。
 * 请求带 {@code Idempotency-Key}（事件幂等键），外部系统据此去重。
 */
public class HttpExternalDestination implements OutboxDestination {

    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

    private final String system;
    private final URI endpoint;
    private final Set<String> eventTypes;
    private final ExternalSyncService sync;
    private final HttpClient http;
    private final Duration timeout;
    private final int maxAttempts;

    public HttpExternalDestination(String system, URI endpoint, Set<String> eventTypes, ExternalSyncService sync, Duration timeout, int maxAttempts) {
        this.system = system;
        this.endpoint = endpoint;
        this.eventTypes = eventTypes;
        this.sync = sync;
        this.timeout = timeout;
        this.maxAttempts = maxAttempts;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public String name() {
        return "external:" + system;
    }

    @Override
    public boolean accepts(String eventType) {
        return eventTypes.contains(eventType);
    }

    @Override
    public void enqueued(DomainEvent event) {
        sync.pending(system, event.subjectConcept(), event.subjectId(), event.eventId());
    }

    @Override
    public void deliver(DomainEvent event) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", event.eventId());
        body.put("type", event.type());
        body.put("occurredAt", event.occurredAt());
        body.put("subject", Map.of("concept", event.subjectConcept(), "id", event.subjectId()));
        body.put("payload", event.payload());
        HttpRequest req = HttpRequest.newBuilder(endpoint).timeout(timeout)
            .header("Content-Type", "application/json").header("Idempotency-Key", event.idempotencyKey())
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("外部系统返回 " + resp.statusCode() + "：" + abbreviate(resp.body()));
            }
            sync.synced(system, event.eventId(), abbreviate(resp.body()));
        } catch (Exception e) {
            sync.failed(system, event.eventId(), e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }
    }

    @Override
    public int maxAttempts() {
        return maxAttempts;
    }

    private static String abbreviate(String s) {
        return s == null ? null : s.length() > 120 ? s.substring(0, 120) : s;
    }
}
