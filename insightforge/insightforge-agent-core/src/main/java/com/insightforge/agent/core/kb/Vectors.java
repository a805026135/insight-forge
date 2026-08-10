package com.insightforge.agent.core.kb;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 向量工具：L2 归一化、余弦相似度、JSON 序列化（落 MySQL TEXT 列）。
 * 所有 embedding 实现都输出归一化向量，因此相似度直接用点积即可。
 */
final class Vectors {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Vectors() {
    }

    static float[] normalize(float[] v) {
        double sum = 0;
        for (float x : v) {
            sum += (double) x * x;
        }
        double norm = Math.sqrt(sum);
        if (norm < 1e-12) {
            return v;
        }
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (float) (v[i] / norm);
        }
        return out;
    }

    static double cosine(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        double dot = 0;
        for (int i = 0; i < n; i++) {
            dot += (double) a[i] * b[i];
        }
        return dot;
    }

    static String toJson(float[] v) {
        try {
            return MAPPER.writeValueAsString(v);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("向量 JSON 序列化失败", e);
        }
    }

    static float[] fromJson(String json) {
        try {
            List<Double> list = MAPPER.readValue(json,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, Double.class));
            float[] out = new float[list.size()];
            for (int i = 0; i < list.size(); i++) {
                out[i] = list.get(i).floatValue();
            }
            return out;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("向量 JSON 反序列化失败", e);
        }
    }
}
