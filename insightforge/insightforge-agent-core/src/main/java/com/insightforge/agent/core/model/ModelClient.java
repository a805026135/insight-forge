package com.insightforge.agent.core.model;

/**
 * 模型接入抽象（Agent 与具体 LLM 之间唯一的接缝）。
 *
 * 为什么抽这一层：spec 要求「Agent 核心自研，仅用 Spring AI 接模型」。
 * 于是所有「聪明」都来自我们自己写的运行时（工具/记忆/反思），只有「调用模型拿 token」这步走 ModelClient。
 * 本接口有两个实现：
 *   - {@link LocalDeterministicModelClient}：离线确定性桩，本地/测试零依赖跑通整条管线（默认）。
 *   - {@link DeepSeekModelClient}：生产用，Spring AI 接 DeepSeek（OpenAI 兼容）。
 * 换模型 = 换一个实现，运行时一行不用动——这就是「不绑死模型」的价值。
 */
public interface ModelClient {

    /** 给定对话历史与可用工具，返回模型响应（最终答案 或 工具调用）。 */
    ModelResponse generate(ModelRequest req);
}
