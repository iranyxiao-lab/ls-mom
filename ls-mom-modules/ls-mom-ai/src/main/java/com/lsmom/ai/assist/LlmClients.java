package com.lsmom.ai.assist;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.ai.llm.HttpTransport;
import com.lsmom.ai.llm.LlmGateway;
import com.lsmom.ai.llm.RecordedTransport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * 服务端 LLM 调用入口：每次请求新建 {@link LlmGateway}（配额按单次请求计）。
 * 未配置端点时回放录制的响应（classpath:lsmom-llm-recordings/&lt;请求摘要&gt;.json，另可指定目录），用于演示与确定性测试；
 * 配置端点后真实调用（可同时指定录制目录：record=true 时把新响应存下来）。
 */
public class LlmClients {

    public static final String RECORDED_MODEL = "recorded";
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final Path recordings;
    private final boolean record;

    public LlmClients(String endpoint, String apiKey, String model, Duration timeout, Path recordings, boolean record) {
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.apiKey = apiKey;
        this.model = model == null || model.isBlank() ? RECORDED_MODEL : model.trim();
        this.timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
        this.recordings = recordings;
        this.record = record;
    }

    /** 是否回放模式（没有真实端点）。 */
    public boolean replay() {
        return endpoint.isEmpty();
    }

    public LlmGateway create() {
        LlmGateway.Transport transport;
        String ep = endpoint;
        if (replay()) {
            ep = "recorded://local";
            transport = new ClasspathRecorded(recordings);
        } else if (recordings != null) {
            transport = new RecordedTransport(recordings, record ? new HttpTransport() : null);
        } else {
            transport = new HttpTransport();
        }
        return new LlmGateway(new LlmGateway.Config(ep, apiKey, model, timeout, 5, 60_000), transport);
    }

    /** 先找 classpath 内置录制，再找指定目录。 */
    static final class ClasspathRecorded implements LlmGateway.Transport {
        private final Path dir;

        ClasspathRecorded(Path dir) {
            this.dir = dir;
        }

        @Override
        public JsonNode post(String url, Map<String, String> headers, JsonNode body, Duration timeout) throws IOException {
            String digest = LlmGateway.digest(body);
            try (InputStream in = LlmClients.class.getClassLoader().getResourceAsStream("lsmom-llm-recordings/" + digest + ".json")) {
                if (in != null) return new com.fasterxml.jackson.databind.ObjectMapper().readTree(in);
            }
            if (dir != null) return new RecordedTransport(dir).post(url, headers, body, timeout);
            throw new IOException("未配置 LLM 端点，且没有录制的响应：" + digest);
        }
    }
}
