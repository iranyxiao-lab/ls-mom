package com.lsmom.ai.llm;

import java.util.regex.Pattern;

/** 日志脱敏：密钥、令牌、手机号、身份证号、邮箱。 */
public final class Masking {

    private static final Pattern[] PATTERNS = {
        Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._\\-]+"),
        Pattern.compile("(?i)\\b(sk|ak|pk)-[A-Za-z0-9_\\-]{8,}"),
        Pattern.compile("(?i)((?:api[_-]?key|token|secret|password|passwd)\\s*[=:：]\\s*)\\S+"),
        Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)"),
        Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)"),
        Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    };

    private Masking() {
    }

    public static String mask(String s) {
        if (s == null) return "";
        String out = PATTERNS[0].matcher(s).replaceAll("$1***");
        out = PATTERNS[1].matcher(out).replaceAll("***");
        out = PATTERNS[2].matcher(out).replaceAll("$1***");
        for (int i = 3; i < PATTERNS.length; i++) out = PATTERNS[i].matcher(out).replaceAll("***");
        return out;
    }
}
