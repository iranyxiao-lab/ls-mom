package com.lsmom.core.schema;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 备份恢复后 PostgreSQL 以等价写法输出索引谓词，不能误判为漂移；真实的索引变化仍能发现。 */
class SchemaFingerprintNormalizeTest {

    @Test
    void 恢复前后等价的部分索引定义规范化后相同() {
        String before = "idx CREATE INDEX idx_biz_flow_callback_pending ON public.biz_flow_callback_log USING btree (status, next_retry_at) "
            + "WHERE ((status)::text = ANY ((ARRAY['PENDING'::character varying, 'PENDING_RETRY'::character varying])::text[]))";
        String after = "idx CREATE INDEX idx_biz_flow_callback_pending ON public.biz_flow_callback_log USING btree (status, next_retry_at) "
            + "WHERE ((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('PENDING_RETRY'::character varying)::text]))";
        assertThat(SchemaFingerprintService.normalize(before)).isEqualTo(SchemaFingerprintService.normalize(after));
    }

    @Test
    void 索引列或谓词变化仍被发现_列定义不受影响() {
        String a = "idx CREATE INDEX i ON public.t USING btree (status) WHERE ((status)::text = 'A'::text)";
        String b = "idx CREATE INDEX i ON public.t USING btree (status, id) WHERE ((status)::text = 'A'::text)";
        String c = "idx CREATE INDEX i ON public.t USING btree (status) WHERE ((status)::text = 'B'::text)";
        assertThat(SchemaFingerprintService.normalize(a)).isNotEqualTo(SchemaFingerprintService.normalize(b))
            .isNotEqualTo(SchemaFingerprintService.normalize(c));
        String col = "col public.t.name character varying(50) not null";
        assertThat(SchemaFingerprintService.normalize(col)).isEqualTo(col);
    }
}
