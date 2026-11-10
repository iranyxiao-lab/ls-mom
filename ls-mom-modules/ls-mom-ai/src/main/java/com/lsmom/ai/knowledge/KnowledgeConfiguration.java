package com.lsmom.ai.knowledge;

import com.lsmom.action.api.MetricPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;

/** 知识库装配。默认使用本地特征哈希向量；接入向量模型时另行提供 {@link Embedder} Bean。 */
@Configuration(proxyBeanMethods = false)
public class KnowledgeConfiguration {

    @Bean
    @ConditionalOnMissingBean(Embedder.class)
    public Embedder knowledgeEmbedder() {
        return new HashingEmbedder();
    }

    @Bean
    public KnowledgeService knowledgeService(JdbcTemplate jdbc, Embedder embedder, PlatformTransactionManager txm) {
        return new KnowledgeService(jdbc, embedder, txm);
    }

    /** 启动时补建缺失或过期的分块（在核心 Concept 登记之后）。 */
    @Bean
    public org.springframework.boot.ApplicationRunner knowledgeReindex(KnowledgeService knowledge) {
        return new KnowledgeReindex(knowledge);
    }

    @org.springframework.core.annotation.Order(300)
    static final class KnowledgeReindex implements org.springframework.boot.ApplicationRunner {
        private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(KnowledgeReindex.class);
        private final KnowledgeService knowledge;

        KnowledgeReindex(KnowledgeService knowledge) {
            this.knowledge = knowledge;
        }

        @Override
        public void run(org.springframework.boot.ApplicationArguments args) {
            int n = knowledge.reindexStale();
            if (n > 0) log.info("知识库：已为 {} 个文档重建分块（向量模型 {}）", n, knowledge.embedder().model());
        }
    }

    @Bean
    public KnowledgeAnswerer knowledgeAnswerer(KnowledgeService knowledge, ObjectProvider<MetricPort> metrics, JdbcTemplate jdbc, Clock clock) {
        return new KnowledgeAnswerer(knowledge, metrics.getIfAvailable(), jdbc, clock);
    }

    @Bean
    public KnowledgeOperations.Ask knowledgeAsk(KnowledgeAnswerer answerer) {
        return new KnowledgeOperations.Ask(answerer);
    }
}
