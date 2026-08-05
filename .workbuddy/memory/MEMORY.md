# InsightForge（智析）项目长期笔记

定位：Java + AI Agent 面试项目，多智能体数据分析与报告平台。要求真实完整、可上线、可量化评估。

## 硬约束（用户明确）
- Agent 指 LLM 智能体；场景避开大众化 RAG 问答，选"多智能体数据分析与报告"。
- 后端必须企业级完整：整合黑马点评生产范式（Redis/Redisson 分布式锁、Kafka 异步解耦、Caffeine+Redis 多级缓存、缓存穿透/击穿/雪崩、Redis+Lua 限流、JMeter 压测、Nginx 负载均衡、Docker Compose）。
- 数据库 MySQL8；向量库 Milvus（语义缓存/相似复用）；模型 DeepSeek；前端 React+TS+Vite+Antd+ECharts。
- Agent 核心自研（调度/工具注册/记忆/反思重试），仅用 Spring AI 接模型。

## 多智能体角色
Planner（拆 DAG）→ Data（取数+校验）→ Viz（出图）→ Writer（成文，引用数据）→ Critic（自检：数据支撑/图文匹配/幻觉）→ Orchestrator（总控/并发/回滚/traceId）。

## 待办
- 落盘 spec：docs/plans/2026-08-05-insightforge-design.md
- 评估闭环（§5）、项目结构/里程碑（§6）细化后写入 spec。
