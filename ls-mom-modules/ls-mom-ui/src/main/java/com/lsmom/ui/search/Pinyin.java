package com.lsmom.ui.search;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;

import java.util.Locale;

/** 汉字转拼音：全拼（如 shebeitaizhang）与首字母（如 sbtz）。多音字取第一个读音；非汉字原样小写保留。 */
public final class Pinyin {

    private static final HanyuPinyinOutputFormat FORMAT = new HanyuPinyinOutputFormat();

    static {
        FORMAT.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        FORMAT.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        FORMAT.setVCharType(HanyuPinyinVCharType.WITH_V);
    }

    private Pinyin() {
    }

    public record Spelling(String full, String initials) {
    }

    public static Spelling of(String text) {
        if (text == null) return new Spelling("", "");
        StringBuilder full = new StringBuilder();
        StringBuilder initials = new StringBuilder();
        for (char ch : text.toCharArray()) {
            String[] py = null;
            if (Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN) {
                try {
                    py = PinyinHelper.toHanyuPinyinStringArray(ch, FORMAT);
                } catch (Exception e) {
                    py = null;
                }
            }
            if (py != null && py.length > 0) {
                full.append(py[0]);
                initials.append(py[0].charAt(0));
            } else if (Character.isLetterOrDigit(ch)) {
                String s = String.valueOf(ch).toLowerCase(Locale.ROOT);
                full.append(s);
                initials.append(s);
            }
        }
        return new Spelling(full.toString(), initials.toString());
    }
}
