package com.lsmom.ai.knowledge;

import com.lsmom.action.api.Exposure;
import com.lsmom.action.api.LsOperation;
import com.lsmom.action.api.OperationContext;
import com.lsmom.action.api.OperationHandler;
import com.lsmom.action.api.Risk;

/** 知识问答的 Operation（只读；人与 Agent 同一入口，Agent 按"真人 ∩ Agent"的权限与真人的数据域检索）。 */
public final class KnowledgeOperations {

    public static final String CONCEPT = "ls.core.KnowledgeDocument";

    private KnowledgeOperations() {
    }

    @LsOperation(code = "ls.core.KnowledgeDocument.ask", concept = CONCEPT, name = "知识问答", risk = Risk.C0, exposure = Exposure.QUERY,
        permission = "lsmom:knowledge:ask", inputSchema = """
        {"type":"object","required":["question"],"properties":{"question":{"type":"string","minLength":1,"maxLength":500}}}""")
    public static class Ask implements OperationHandler {
        private final KnowledgeAnswerer answerer;

        public Ask(KnowledgeAnswerer answerer) {
            this.answerer = answerer;
        }

        @Override
        public Object handle(OperationContext ctx) {
            return answerer.ask(ctx.input().get("question").asText(), ctx.actor());
        }
    }
}
