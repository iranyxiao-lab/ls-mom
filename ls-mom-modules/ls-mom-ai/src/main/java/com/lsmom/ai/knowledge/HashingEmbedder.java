package com.lsmom.ai.knowledge;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 本地确定性向量化（特征哈希）：中文按单字与相邻二字、其他文字按词，映射到固定维度并归一化。
 * 不依赖外部模型，离线可用、结果可复现；语义能力弱于模型向量，接入模型时以另一个 {@link Embedder} Bean 替换。
 */
public class HashingEmbedder implements Embedder {

    public static final int DIM = 512;

    @Override
    public String model() {
        return "hash-ngram-" + DIM + "-v1";
    }

    @Override
    public float[] embed(String text) {
        float[] v = new float[DIM];
        for (String t : tokens(text)) {
            int h = murmur(t);
            int idx = Math.floorMod(h, DIM);
            float w = t.codePointCount(0, t.length()) >= 2 ? 1.5f : 1f;
            v[idx] += (h >>> 31) == 0 ? w : -w;
        }
        double n = 0;
        for (float x : v) n += x * x;
        if (n > 0) {
            float k = (float) (1 / Math.sqrt(n));
            for (int i = 0; i < DIM; i++) v[i] *= k;
        }
        return v;
    }

    /** 中文单字＋二字；字母数字连续串为一个词（小写）。空白与标点忽略。 */
    static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        String s = text.toLowerCase(Locale.ROOT);
        StringBuilder word = new StringBuilder();
        int prevHan = -1;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) {
                flush(word, out);
                String c = new String(Character.toChars(cp));
                out.add(c);
                if (prevHan >= 0) out.add(new String(Character.toChars(prevHan)) + c);
                prevHan = cp;
            } else if (Character.isLetterOrDigit(cp)) {
                word.appendCodePoint(cp);
                prevHan = -1;
            } else {
                flush(word, out);
                prevHan = -1;
            }
        }
        flush(word, out);
        return out;
    }

    private static void flush(StringBuilder w, List<String> out) {
        if (!w.isEmpty()) {
            out.add(w.toString());
            w.setLength(0);
        }
    }

    private static int murmur(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        int h = 0x9747b28c;
        for (byte x : b) {
            h ^= x & 0xff;
            h *= 0x5bd1e995;
            h ^= h >>> 15;
        }
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        return h ^ (h >>> 15);
    }
}
