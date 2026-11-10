package com.lsmom.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** DNS 重绑定防护：只接受同源或配置允许的 Origin。 */
class McpControllerTest {

    @Test
    void 同源判断() {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.addHeader("Host", "127.0.0.1:18081");
        assertThat(McpController.sameOrigin("http://127.0.0.1:18081", r)).isTrue();
        assertThat(McpController.sameOrigin("http://evil.example:18081", r)).isFalse();
        assertThat(McpController.sameOrigin("http://127.0.0.1:9999", r)).isFalse();
        MockHttpServletRequest p = new MockHttpServletRequest();
        p.addHeader("Host", "app:8080");
        p.addHeader("X-Forwarded-Host", "mom.example.com");
        assertThat(McpController.sameOrigin("https://mom.example.com", p)).isTrue();
        assertThat(McpController.sameOrigin("not a uri", p)).isFalse();
    }
}
