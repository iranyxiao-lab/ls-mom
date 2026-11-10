package com.lsmom.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** LlmGateway：OpenAI 兼容请求、模型版本记录、超时、配额、脱敏、录制回放。 */
class LlmGatewayTest {

    HttpServer server;
    final AtomicReference<String> lastBody = new AtomicReference<>();
    final AtomicReference<String> lastAuth = new AtomicReference<>();
    final AtomicReference<String> lastPath = new AtomicReference<>();

    String start(long delayMs) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            lastPath.set(ex.getRequestURI().getPath());
            lastAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] out = """
                {"id":"x","model":"qwen2.5-72b-instruct","system_fingerprint":"fp_3a9","choices":[{"index":0,"message":{"role":"assistant","content":"{\\"ok\\":true}"}}],
                 "usage":{"prompt_tokens":12,"completion_tokens":5}}""".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/";
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void OpenAI兼容请求_记录模型版本与用量() throws Exception {
        LlmGateway g = new LlmGateway(new LlmGateway.Config(start(0), "sk-test-0123456789abcdef", "qwen2.5-72b-instruct", Duration.ofSeconds(5), 5, 1000),
            new HttpTransport());
        LlmGateway.Completion c = g.complete(List.of(LlmGateway.Message.system("你是需求分析助手"), LlmGateway.Message.user("设备点检")), true);
        assertThat(c.content()).isEqualTo("{\"ok\":true}");
        assertThat(c.modelVersion()).isEqualTo("qwen2.5-72b-instruct@fp_3a9");
        assertThat(lastPath.get()).isEqualTo("/v1/chat/completions");
        assertThat(lastAuth.get()).isEqualTo("Bearer sk-test-0123456789abcdef");
        JsonNode body = LlmGateway.JSON.readTree(lastBody.get());
        assertThat(body.path("model").asText()).isEqualTo("qwen2.5-72b-instruct");
        assertThat(body.path("temperature").asInt()).isZero();
        assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_object");
        assertThat(body.path("messages").get(1).path("content").asText()).isEqualTo("设备点检");
        assertThat(g.tokens()).isEqualTo(17);
        assertThat(c.requestDigest()).isEqualTo(LlmGateway.digest(body));
    }

    @Test
    void 超时与配额() throws Exception {
        LlmGateway slow = new LlmGateway(new LlmGateway.Config(start(1500), null, "m", Duration.ofMillis(300), 5, 1000), new HttpTransport());
        assertThatThrownBy(() -> slow.complete(List.of(LlmGateway.Message.user("x")), false))
            .isInstanceOfSatisfying(LlmGateway.LlmException.class, e -> assertThat(e.code()).isEqualTo("LLM_TIMEOUT"));
        stop();
        LlmGateway g = new LlmGateway(new LlmGateway.Config(start(0), null, "m", Duration.ofSeconds(5), 2, 1000), new HttpTransport());
        g.complete(List.of(LlmGateway.Message.user("1")), false);
        g.complete(List.of(LlmGateway.Message.user("2")), false);
        assertThatThrownBy(() -> g.complete(List.of(LlmGateway.Message.user("3")), false))
            .isInstanceOfSatisfying(LlmGateway.LlmException.class, e -> assertThat(e.code()).isEqualTo("LLM_QUOTA_EXCEEDED"));
    }

    @Test
    void 日志脱敏() {
        String s = Masking.mask("Authorization: Bearer abc.def-123 api_key=sk-abcdefghijk 手机 13812345678 身份证 11010119900307123X 邮箱 a.b@example.com");
        assertThat(s).doesNotContain("abc.def-123", "sk-abcdefghijk", "13812345678", "11010119900307123X", "a.b@example.com").contains("***");
    }

    @Test
    void 录制后回放_未录制的请求失败() throws Exception {
        Path dir = Files.createTempDirectory("llm-rec");
        String ep = start(0);
        LlmGateway rec = new LlmGateway(new LlmGateway.Config(ep, "sk-secret-abcdefgh", "m", Duration.ofSeconds(5), 5, 1000),
            new RecordedTransport(dir, new HttpTransport()));
        LlmGateway.Completion c1 = rec.complete(List.of(LlmGateway.Message.user("同一个问题")), true);
        assertThat(Files.list(dir)).hasSize(1);
        assertThat(Files.readString(dir.resolve(c1.requestDigest() + ".json"))).doesNotContain("sk-secret");
        stop();
        LlmGateway replay = new LlmGateway(new LlmGateway.Config("http://unreachable.invalid", null, "m", Duration.ofSeconds(1), 5, 1000),
            new RecordedTransport(dir));
        assertThat(replay.complete(List.of(LlmGateway.Message.user("同一个问题")), true).content()).isEqualTo(c1.content());
        assertThatThrownBy(() -> replay.complete(List.of(LlmGateway.Message.user("别的问题")), true))
            .isInstanceOfSatisfying(LlmGateway.LlmException.class, e -> assertThat(e.getMessage()).contains("没有录制的响应"));
    }
}
