package com.lsmom.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** 经 HTTP 调用 OpenAI 兼容端点。 */
public class HttpTransport implements LlmGateway.Transport {

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Override
    public JsonNode post(String url, Map<String, String> headers, JsonNode body, Duration timeout) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        headers.forEach(b::header);
        try {
            HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode n = r.body() == null || r.body().isBlank() ? LlmGateway.JSON.createObjectNode() : LlmGateway.JSON.readTree(r.body());
            if (r.statusCode() >= 400 && !n.has("error")) {
                return LlmGateway.JSON.createObjectNode().set("error", LlmGateway.JSON.createObjectNode().put("message", "HTTP " + r.statusCode()));
            }
            return n;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("调用被中断", e);
        }
    }
}
