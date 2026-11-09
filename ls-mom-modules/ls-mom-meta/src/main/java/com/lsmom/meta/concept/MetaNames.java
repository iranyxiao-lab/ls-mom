package com.lsmom.meta.concept;

import com.lsmom.core.error.LsBizException;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** 编码与物理标识符规则。物理标识符只能由这里生成，并且必须通过白名单校验。 */
public final class MetaNames {

    public static final Pattern CONCEPT_CODE = Pattern.compile("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)+\\.[A-Z][A-Za-z0-9]*$");
    public static final Pattern FIELD_CODE = Pattern.compile("^[a-z][A-Za-z0-9]{0,47}$");
    public static final Pattern IDENTIFIER = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");
    /** 核心命名空间禁止出现的客户/扩展领域词汇。 */
    public static final Pattern CORE_FORBIDDEN = Pattern.compile("(?i)(exam|training|trainee|course|lesson|quiz|consult|survey|customer)");

    private MetaNames() {
    }

    public static String toColumn(String fieldCode) {
        return fieldCode.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    public static String toFieldCode(String column) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char c : column.toCharArray()) {
            if (c == '_') { up = true; continue; }
            sb.append(up ? Character.toUpperCase(c) : c);
            up = false;
        }
        return sb.toString();
    }

    /** {@code acme.ledger.SafetyCheck} → {@code c_acme_ledger_safety_check}。 */
    public static String managedTable(String conceptCode) {
        String ns = conceptCode.substring(0, conceptCode.lastIndexOf('.')).replace('.', '_');
        String name = toColumn(conceptCode.substring(conceptCode.lastIndexOf('.') + 1));
        return requireIdentifier("c_" + ns + "_" + name);
    }

    public static String requireIdentifier(String id) {
        if (id == null || !IDENTIFIER.matcher(id).matches()) {
            throw new LsBizException("INVALID_IDENTIFIER", "非法的物理标识符：" + id, Map.of("identifier", String.valueOf(id)));
        }
        return id;
    }
}
