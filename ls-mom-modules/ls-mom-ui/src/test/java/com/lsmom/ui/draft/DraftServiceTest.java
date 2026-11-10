package com.lsmom.ui.draft;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lsmom.core.error.LsBizException;
import com.lsmom.test.PgTestSupport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** modernize-ui-ux 4.3：表单草稿按用户保存、内容相同不重复写入、提交后删除、键与内容校验。 */
class DraftServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final DraftService drafts = new DraftService(PgTestSupport.jdbc());

    private static long user() {
        return 930_000_000L + System.nanoTime() % 1_000_000;
    }

    @Test
    void 保存_去重_只本人可见_删除() throws Exception {
        long u = user(), other = u + 1;
        String key = "acme.tool.LoanOrder:new";
        assertThat(drafts.get(u, key)).isEmpty();
        DraftService.Draft d1 = drafts.save(u, key, JSON.readTree("{\"borrower\":\"张三\",\"lines\":[{\"tool\":\"扳手\"}]}"), "h1");
        assertThat(d1.content().get("borrower").asText()).isEqualTo("张三");
        Thread.sleep(20);
        DraftService.Draft same = drafts.save(u, key, JSON.readTree("{\"borrower\":\"张三\",\"lines\":[{\"tool\":\"扳手\"}]}"), "h1");
        assertThat(same.updatedAt()).isEqualTo(d1.updatedAt());
        DraftService.Draft d2 = drafts.save(u, key, JSON.readTree("{\"borrower\":\"李四\"}"), "h2");
        assertThat(d2.content().get("borrower").asText()).isEqualTo("李四");
        assertThat(d2.updatedAt()).isAfterOrEqualTo(d1.updatedAt());
        assertThat(drafts.get(other, key)).isEmpty();
        assertThat(drafts.mine(u)).hasSize(1);
        drafts.delete(u, key);
        assertThat(drafts.get(u, key)).isEmpty();
    }

    @Test
    void 非法键与内容被拒() {
        long u = user();
        assertThatThrownBy(() -> drafts.save(u, "../etc", JSON.createObjectNode(), "h")).isInstanceOf(LsBizException.class);
        assertThatThrownBy(() -> drafts.save(u, "k", JSON.createArrayNode(), "h")).isInstanceOf(LsBizException.class);
        assertThatThrownBy(() -> drafts.save(u, "k", JSON.createObjectNode(), "")).isInstanceOf(LsBizException.class);
        assertThatThrownBy(() -> drafts.save(u, "k", JSON.createObjectNode().put("x", "a".repeat(DraftService.MAX_BYTES)), "h"))
            .isInstanceOf(LsBizException.class);
    }
}
