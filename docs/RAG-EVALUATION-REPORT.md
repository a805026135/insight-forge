# RAG 评测报告（云端 Milvus 实测）

评测集：`rag-cn-commerce-v1.0`，40 条；Embedding：`dashscope:qwen3.7-text-embedding`；向量库：Milvus。

## 两轮结果

| 指标 | 初始基线 | 意图路由 + 安全门槛后 | 变化 |
|---|---:|---:|---:|
| Recall@4 | 85.71% | 97.14% | +11.43pp |
| 答案正确率 | 68.57% | 88.57% | +20.00pp |
| 拒答准确率 | 20.00% | 100.00% | +80.00pp |
| 检索 P95 | 632ms | 617ms | -15ms |

时间：2026-08-12；来源：云服务器生产配置下调用 `/api/dashboard/rag-evaluation/run` 的真实结果，未使用预填数据。

## 优化说明

初始版本把所有问题交给同一个通用向量检索，出现发票问题命中 FAQ、敏感问题被低相关知识“勉强命中”的情况。优化后先按意图路由到 BUYER_POLICY / PLAYBOOK / FAQ / PRODUCT / ACTIVITY / DECISION 来源，再对秘密、跨商家越权、未知实时事实和绕过审批请求执行 fail-close 安全门槛。

当前仍有 4 条未通过：1 条 FAQ 召回失败、3 条命中文档但关键事实未落入返回切块。它们作为下一轮 chunking/reranker 优化样本保留，没有从数据集中删除。
