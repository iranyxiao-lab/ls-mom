package com.lsmom.action.risk;

import com.lsmom.action.api.Risk;
import com.lsmom.action.registry.OperationDefinition;
import com.lsmom.action.registry.OperationRegistry;
import com.lsmom.core.error.LsBizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用写入（通用 CRUD、导入）风险门禁：
 * C0 对象可通用写入；C1 对象的状态字段禁止通用写入（必须经对应 Operation）；C2 对象禁止任何通用写入。
 * 核心显式表一律不经通用 CRUD 写入。
 */
@Component
public class GenericWritePolicy {

    private final JdbcTemplate jdbc;
    private final OperationRegistry operations;

    public GenericWritePolicy(JdbcTemplate jdbc, OperationRegistry operations) {
        this.jdbc = jdbc;
        this.operations = operations;
    }

    /** Concept 的写入风险：其已发布 Operation 中的最高级别；没有 Operation 为 C0。 */
    public Risk riskOf(String concept) {
        return operations.listByConcept(concept).stream().map(OperationDefinition::risk).max(Enum::compareTo).orElse(Risk.C0);
    }

    public void check(String concept, Collection<String> fieldCodes) {
        Map<String, Object> row = jdbc.queryForList("select storage, editable from meta_concept where code = ?", concept)
            .stream().findFirst().orElseThrow(() -> new LsBizException("CONCEPT_NOT_FOUND", "Concept 不存在：" + concept));
        List<String> stateFields = jdbc.queryForList("select field_code from meta_concept_field where concept_code = ? and state_field",
            String.class, concept);
        List<String> touchedState = fieldCodes.stream().filter(stateFields::contains).toList();
        Risk risk = riskOf(concept);
        String hint = "请使用对应的 Operation：" + String.join("、", operations.listByConcept(concept).stream()
            .filter(o -> o.kind() == OperationDefinition.Kind.TRANSITION).map(OperationDefinition::code).limit(8).toList());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("concept", concept);
        details.put("risk", risk.name());
        details.put("operations", operations.listByConcept(concept).stream().map(OperationDefinition::code).toList());
        if (!touchedState.isEmpty() && risk != Risk.C0) {
            details.put("fields", touchedState);
            throw new LsBizException("GENERIC_WRITE_FORBIDDEN", "状态字段 " + String.join("、", touchedState) + " 不能通过通用接口修改，" + hint, details);
        }
        if (risk == Risk.C2) {
            throw new LsBizException("GENERIC_WRITE_FORBIDDEN", "高风险对象不能通过通用接口写入，" + hint, details);
        }
        if ("CORE_TABLE".equals(row.get("storage"))) {
            throw new LsBizException("GENERIC_WRITE_FORBIDDEN", "核心对象只能经命名 Operation 写入，" + hint, details);
        }
    }
}
