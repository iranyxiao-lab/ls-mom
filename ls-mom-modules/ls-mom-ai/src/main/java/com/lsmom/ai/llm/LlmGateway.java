package com.lsmom.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 调用 OpenAI 兼容的 Chat Completions 接口（可指向私有化 vLLM / Qwen 等）：
 * 超时、单次运行调用次数与 token 配额、日志脱敏（只记录摘要与打码后的片段，不记录密钥）、记录实际模型与版本。
 * 传输可替换：{@link HttpTransport} 真实调用，{@link RecordedTransport} 按请求摘要回放录制响应（确定性测试）。
 */
public class LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(LlmGateway.class);
    static final ObjectMapper JSON = new ObjectMapper();

    /**
     * @param endpoint      OpenAI 兼容端点根地址（如 https://llm.example/v1）
     * @param apiKey        密钥（只从环境变量 / 配置读取，不写入日志与产物）
     * @param model         模型名
     * @param timeout       单次调用超时
     * @param maxCalls      单次运行最多调用次数
     * @param maxTokens     单次运行最多消耗 token（提示＋生成）
     */
    public record Config(String endpoint, String apiKey, String model, Duration timeout, int maxCalls, int maxTokens) {

        public Config {
            if (endpoint == null || endpoint.isBlank()) throw new IllegalArgumentException("缺少 LLM 端点");
            if (model == null || model.isBlank()) throw new IllegalArgumentException("缺少模型名");
            if (timeout == null) timeout = Duration.ofSeconds(60);
            if (maxCalls <= 0) maxCalls = 20;
            if (maxTokens <= 0) maxTokens = 200_000;
        }

        /** 从环境变量读取：LSMOM_LLM_ENDPOINT、LSMOM_LLM_API_KEY、LSMOM_LLM_MODEL、LSMOM_LLM_TIMEOUT_SECONDS。 */
        public static Config fromEnv(Map<String, String> env, String endpointOverride, String modelOverride) {
            String ep = endpointOverride != null ? endpointOverride : env.get("LSMOM_LLM_ENDPOINT");
            String model = modelOverride != null ? modelOverride : env.get("LSMOM_LLM_MODEL");
            long t = Long.parseLong(env.getOrDefault("LSMOM_LLM_TIMEOUT_SECONDS", "60"));
            return new Config(ep, env.get("LSMOM_LLM_API_KEY"), model, Duration.ofSeconds(t), 20, 200_000);
        }
    }

    public record Message(String role, String content) {
        public static Message system(String c) {
            return new Message("system", c);
        }

        public static Message user(String c) {
            return new Message("user", c);
        }
    }

    /**
     * @param modelVersion 端点返回的模型标识与指纹（如 {@code qwen2.5-72b-instruct@fp_3a9}），用于来源追溯
     * @param requestDigest 请求摘要（模型＋消息），录制回放的键
     */
    public record Completion(String content, String model, String modelVersion, int promptTokens, int completionTokens, String requestDigest) {
    }

    /** 传输层：POST JSON，返回响应 JSON。 */
    public interface Transport {
        JsonNode post(String url, Map<String, String> headers, JsonNode body, Duration timeout) throws IOException;
    }

    public static class LlmException extends RuntimeException {
        private final String code;

        public LlmException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private final Config config;
    private final Transport transport;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger tokens = new AtomicInteger();

    public LlmGateway(Config config, Transport transport) {
        this.config = config;
        this.transport = transport;
    }

    public Config config() {
        return config;
    }

    /** 本次运行已用调用次数与 token。 */
    public int calls() {
        return calls.get();
    }

    public int tokens() {
        return tokens.get();
    }

    /** 对话补全；json=true 时要求模型输出 JSON 对象（temperature 0，便于复现）。 */
    public Completion complete(List<Message> messages, boolean json) {
        if (calls.get() >= config.maxCalls()) throw new LlmException("LLM_QUOTA_EXCEEDED", "超过单次运行的调用次数配额（" + config.maxCalls() + "）", null);
        if (tokens.get() >= config.maxTokens()) throw new LlmException("LLM_QUOTA_EXCEEDED", "超过单次运行的 token 配额（" + config.maxTokens() + "）", null);
        ObjectNode body = JSON.createObjectNode();
        body.put("model", config.model());
        body.put("temperature", 0);
        ArrayNode msgs = body.putArray("messages");
        for (Message m : messages) msgs.addObject().put("role", m.role()).put("content", m.content());
        if (json) body.putObject("response_format").put("type", "json_object");
        String digest = digest(body);
        Map<String, String> headers = config.apiKey() == null || config.apiKey().isBlank() ? Map.of() : Map.of("Authorization", "Bearer " + config.apiKey());
        log.info("LLM 请求 {} 模型 {}：{}", digest.substring(0, 12), config.model(), preview(messages));
        calls.incrementAndGet();
        JsonNode r;
        try {
            r = transport.post(config.endpoint().replaceAll("/+$", "") + "/chat/completions", headers, body, config.timeout());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new LlmException("LLM_TIMEOUT", "LLM 调用超时（" + config.timeout().toSeconds() + " 秒）", e);
        } catch (IOException e) {
            throw new LlmException("LLM_UNAVAILABLE", "LLM 调用失败：" + Masking.mask(e.getMessage()), e);
        }
        if (r.has("error")) throw new LlmException("LLM_ERROR", "LLM 返回错误：" + Masking.mask(r.path("error").path("message").asText(r.path("error").toString())), null);
        String content = r.path("choices").path(0).path("message").path("content").asText(null);
        if (content == null) throw new LlmException("LLM_BAD_RESPONSE", "LLM 响应中没有内容", null);
        int pt = r.path("usage").path("prompt_tokens").asInt(0), ct = r.path("usage").path("completion_tokens").asInt(0);
        tokens.addAndGet(pt + ct);
        String model = r.path("model").asText(config.model());
        String fp = r.path("system_fingerprint").asText("");
        log.info("LLM 响应 {} 模型 {}{}，token {}+{}", digest.substring(0, 12), model, fp.isEmpty() ? "" : "@" + fp, pt, ct);
        return new Completion(content, model, fp.isEmpty() ? model : model + "@" + fp, pt, ct, digest);
    }

    /** 请求摘要：模型与消息的 sha256（与密钥、时间无关）。 */
    public static String digest(JsonNode body) {
        ObjectNode key = JSON.createObjectNode();
        key.put("model", body.path("model").asText());
        key.set("messages", body.path("messages"));
        key.set("response_format", body.path("response_format"));
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(key.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String preview(List<Message> messages) {
        String last = messages.isEmpty() ? "" : messages.get(messages.size() - 1).content();
        String m = Masking.mask(last).replace('\n', ' ');
        return m.length() > 120 ? m.substring(0, 120) + "…" : m;
    }
}
