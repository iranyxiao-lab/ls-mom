package com.lsmom.ui.speech;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.core.error.LsBizException;
import org.dromara.common.core.domain.R;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * 语音转文字的服务端回退：浏览器不支持 Web Speech API 时，移动端把录音发到这里，转发给配置的语音识别服务
 * （{@code lsmom.speech.url}，接收 multipart 字段 audio，返回 {"text": "..."}）。未配置时移动端隐藏语音按钮。
 */
@RestController
@RequestMapping("/api/ui/speech")
public class SpeechController {

    private static final long MAX_BYTES = 10L * 1024 * 1024;

    private final String url;
    private final RestClient client;

    public SpeechController(@Value("${lsmom.speech.url:}") String url, @Value("${lsmom.speech.timeout-ms:15000}") int timeoutMs) {
        this.url = url == null ? "" : url.trim();
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(timeoutMs);
        f.setReadTimeout(timeoutMs);
        this.client = RestClient.builder().requestFactory(f).build();
    }

    /** 服务端语音识别是否可用。 */
    @GetMapping
    public R<Map<String, Boolean>> status() {
        return R.ok(Map.of("enabled", !url.isEmpty()));
    }

    @PostMapping
    public R<Map<String, String>> recognize(@RequestParam("audio") MultipartFile audio) throws IOException {
        if (url.isEmpty()) throw new LsBizException("SPEECH_DISABLED", "未配置语音识别服务");
        if (audio.isEmpty() || audio.getSize() > MAX_BYTES) throw LsBizException.validation(Map.of("audio", "录音为空或超过 10 MB"));
        byte[] bytes = audio.getBytes();
        String name = audio.getOriginalFilename() == null ? "speech.webm" : audio.getOriginalFilename();
        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("audio", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return name;
            }
        });
        try {
            JsonNode r = client.post().uri(url).contentType(MediaType.MULTIPART_FORM_DATA).body(form).retrieve().body(JsonNode.class);
            String text = r == null ? "" : r.path("text").asText("");
            return R.ok(Map.of("text", text));
        } catch (RestClientException e) {
            throw new LsBizException("SPEECH_FAILED", "语音识别服务暂不可用，请改用键盘输入");
        }
    }
}
