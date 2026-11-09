package com.lsmom.core.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TraceContextTest {

    @AfterEach
    void tearDown() {
        TraceContext.clear();
    }

    @Test
    void filterReusesValidIncomingHeaderAndEchoesIt() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceContext.HEADER, "abc12345-trace");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();

        new TraceIdFilter().doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seen.set(TraceContext.current());
            }
        });

        assertThat(seen.get()).isEqualTo("abc12345-trace");
        assertThat(response.getHeader(TraceContext.HEADER)).isEqualTo("abc12345-trace");
        assertThat(TraceContext.current()).as("请求结束后清理 MDC").isNull();
    }

    @Test
    void filterRejectsMaliciousHeaderAndGeneratesNewId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceContext.HEADER, "bad\nINJECTED log line");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new TraceIdFilter().doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(TraceContext.HEADER)).matches("[0-9a-f]{32}");
    }

    @Test
    void wrappedTaskCarriesTraceIdToOtherThread() throws Exception {
        TraceContext.set("trace-from-request");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<String> inWorker = new AtomicReference<>();
            pool.submit(TraceContext.wrap(() -> inWorker.set(TraceContext.current()))).get();
            String afterTask = pool.submit(TraceContext::current).get();

            assertThat(inWorker.get()).isEqualTo("trace-from-request");
            assertThat(afterTask).as("工作线程执行完后恢复原 MDC").isNull();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void runWithRestoresPreviousTrace() {
        TraceContext.set("outer");
        AtomicReference<String> inner = new AtomicReference<>();
        TraceContext.runWith("from-outbox-record", () -> inner.set(TraceContext.current()));
        assertThat(inner.get()).isEqualTo("from-outbox-record");
        assertThat(TraceContext.current()).isEqualTo("outer");
    }
}
