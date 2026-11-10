package com.lsmom.ai.assist;

import com.lsmom.action.rule.CelRuleEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 设计助手装配。LLM 端点：lsmom.llm.endpoint / model / api-key / timeout-seconds（环境变量 LSMOM_LLM_*）；
 * 不配置端点时回放录制响应。lsmom.llm.recordings 指定额外的录制目录，lsmom.llm.record=true 时真实调用的新响应写入该目录。
 */
@Configuration(proxyBeanMethods = false)
public class AssistConfiguration {

    @Bean
    public LlmClients llmClients(@Value("${lsmom.llm.endpoint:${LSMOM_LLM_ENDPOINT:}}") String endpoint,
                                 @Value("${lsmom.llm.api-key:${LSMOM_LLM_API_KEY:}}") String apiKey,
                                 @Value("${lsmom.llm.model:${LSMOM_LLM_MODEL:}}") String model,
                                 @Value("${lsmom.llm.timeout-seconds:${LSMOM_LLM_TIMEOUT_SECONDS:60}}") long timeoutSeconds,
                                 @Value("${lsmom.llm.recordings:${LSMOM_LLM_RECORDINGS:}}") String recordings,
                                 @Value("${lsmom.llm.record:false}") boolean record) {
        return new LlmClients(endpoint, apiKey, model, Duration.ofSeconds(timeoutSeconds), recordings.isBlank() ? null : Path.of(recordings), record);
    }

    @Bean
    public FormAssistService formAssistService(LlmClients clients) {
        return new FormAssistService(clients, new CelRuleEngine());
    }
}
