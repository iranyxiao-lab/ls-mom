package com.lsmom.core.event;

/**
 * 事件订阅方（待办、通知、外部系统、读模型）。投递失败抛异常即进入退避重试；
 * 实现方须以 {@code event.idempotencyKey()}（或 eventId）幂等处理，可借助 {@link ConsumerReceipts}。
 */
public interface OutboxDestination {

    /** 稳定名称，写入 core_outbox.destination。 */
    String name();

    /** 是否订阅该类型事件。 */
    boolean accepts(String eventType);

    void deliver(DomainEvent event) throws Exception;

    /** 事件入队（与业务同事务）时回调，如登记"待同步"状态。 */
    default void enqueued(DomainEvent event) {
    }

    /** 最大尝试次数，超过进入人工补偿列表。 */
    default int maxAttempts() {
        return 5;
    }
}
