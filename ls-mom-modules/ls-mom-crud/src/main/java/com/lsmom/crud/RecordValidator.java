package com.lsmom.crud;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.action.api.ActorContext;

import java.util.Map;

/**
 * 记录级校验扩展点（由表单模块实现）：在通用字段校验之后、写入之前执行，按记录冻结的表单版本重新校验
 * 必填、范围、正则、字典、引用过滤与 CEL 规则；不通过时抛 VALIDATION_FAILED（details.fields 为 字段 → 原因）。
 */
public interface RecordValidator {

    /**
     * @param body    本次报文（表单编码与版本放在 {@code _form: {code, version}}）
     * @param current 修改时的当前记录（字段编码键，子表键为行列表）；新建时为 null
     * @param orgId   记录所属组织
     */
    record Context(String concept, JsonNode body, Map<String, Object> current, Long orgId, ActorContext actor) {
    }

    /** formVersion：需冻结到记录 schema_version 的表单版本；overrides：服务端重算的字段值（如计算字段）。 */
    record Outcome(Integer formVersion, Map<String, JsonNode> overrides) {
        public static final Outcome NONE = new Outcome(null, Map.of());
    }

    Outcome validate(Context ctx);
}
