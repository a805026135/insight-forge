package com.insightforge.agent.core.kb;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 本地词法向量（无 key 兜底）：把中文文本按 2-gram 字符特征 + 词频哈希成
 * 固定维向量并 L2 归一化。
 *
 * 诚实边界：这不是语义 embedding，只是“同主题词汇分布相似”的轻量近似，
 * 用于演示 RAG 全链路与商家隔离；拿到 DashScope key 后自动切换 text-embedding-v4，
 * 代码零改动（仅配置）。自研运行时仅用 Spring AI 接真实模型，与项目既有约定一致。
 */
public class LocalLexicalEmbeddingProvider implements EmbeddingProvider {

    private static final Pattern SPLIT = Pattern.compile(
            "[\\s,，;；。.．!！?？:：、()（）\\[\\]\\{\\}“”\"'‘’]+");

    private final int dimensions;

    public LocalLexicalEmbeddingProvider(int dimensions) {
        this.dimensions = Math.max(64, dimensions);
    }

    @Override
    public String name() {
        return "local-lexical-" + dimensions;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public float[] embed(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        String[] tokens = SPLIT.split(t);
        double[] acc = new double[dimensions];
        int count = 0;
        for (String tok : tokens) {
            if (tok.isBlank()) {
                continue;
            }
            boolean hasHan = tok.codePoints()
                    .anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
            if (hasHan && tok.length() >= 2) {
                int[] cps = tok.codePoints().toArray();
                for (int i = 0; i < cps.length - 1; i++) {
                    addFeature(acc, cps[i] * 31 + cps[i + 1]);
                    count++;
                }
            } else {
                addFeature(acc, hash(tok));
                count++;
            }
        }
        if (count == 0) {
            addFeature(acc, hash(""));
        }
        return Vectors.normalize(toFloats(acc));
    }

    private void addFeature(double[] acc, int feature) {
        acc[Math.floorMod(feature, dimensions)] += 1.0;
    }

    private float[] toFloats(double[] acc) {
        float[] out = new float[acc.length];
        for (int i = 0; i < acc.length; i++) {
            out[i] = (float) acc[i];
        }
        return out;
    }

    private int hash(String s) {
        return s.hashCode() * 31 + s.length();
    }
}
