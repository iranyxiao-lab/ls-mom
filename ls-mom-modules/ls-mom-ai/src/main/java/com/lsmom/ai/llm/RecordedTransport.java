package com.lsmom.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * 录制回放：按请求摘要（{@link LlmGateway#digest}）读取 {@code <dir>/<digest>.json}。
 * 带 delegate 时为录制模式：未录制的请求转发给真实端点并保存响应（不保存请求头与密钥）。
 */
public class RecordedTransport implements LlmGateway.Transport {

    private final Path dir;
    private final LlmGateway.Transport delegate;

    public RecordedTransport(Path dir) {
        this(dir, null);
    }

    public RecordedTransport(Path dir, LlmGateway.Transport delegate) {
        this.dir = dir;
        this.delegate = delegate;
    }

    @Override
    public JsonNode post(String url, Map<String, String> headers, JsonNode body, Duration timeout) throws IOException {
        String digest = LlmGateway.digest(body);
        Path f = dir.resolve(digest + ".json");
        if (Files.exists(f)) return LlmGateway.JSON.readTree(Files.readString(f));
        if (delegate == null) throw new IOException("没有录制的响应：" + digest + "（在 " + dir + " 下）");
        JsonNode r = delegate.post(url, headers, body, timeout);
        Files.createDirectories(dir);
        Files.writeString(f, r.toPrettyString());
        return r;
    }
}
