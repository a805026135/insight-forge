package com.insightforge.agent.core.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.List;

/**
 * 失败样本（大白话：历史上「AI 应该拒绝 / 应该求助」的那些危险输入，存起来反复考）。
 *
 * 评估闭环的「失败样本回归重放」就是把这些曾经的危机输入一遍遍重放，
 * 断言 AI 仍然给出安全的终态（如 CORRECT_FAILURE），而不是某次重构后
 * 「退化成假装成功」去执行危险动作。这是把红线「正确失败率」落到工程的复利机制。
 *
 * 样本从 classpath: evaluation/failure-samples.json 加载；expect 是期望的安全终态。
 * 重放时统一走「强制不合规」编排器（forceInvalidPolicy=true），因此都应落 CORRECT_FAILURE。
 */
public class FailureSample {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 一条失败样本。 */
    public record Item(String name, Long activityId, String goal, String expect) {
    }

    /** 加载失败样本集。 */
    public static List<Item> load() throws Exception {
        try (InputStream is = FailureSample.class.getClassLoader()
                .getResourceAsStream("evaluation/failure-samples.json")) {
            if (is == null) {
                throw new IllegalStateException("evaluation/failure-samples.json 未找到");
            }
            return MAPPER.readValue(is, new TypeReference<List<Item>>() {
            });
        }
    }
}
