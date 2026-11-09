package com.lsmom.core.event;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 消费方幂等：{@link #firstTime} 返回 true 表示首次处理，重复投递返回 false。 */
@Service
public class ConsumerReceipts {

    private final JdbcTemplate jdbc;

    public ConsumerReceipts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean firstTime(String consumer, String idempotencyKey) {
        return jdbc.update("insert into core_consumer_receipt (consumer, idempotency_key) values (?, ?) on conflict do nothing",
            consumer, idempotencyKey) == 1;
    }
}
