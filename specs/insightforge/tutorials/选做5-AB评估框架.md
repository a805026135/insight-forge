# 选做5 · A/B 评估框架

> 进阶选做章节。跳过不影响主线理解。前置：读过 Phase 11 主线概念。

## 📖 是什么

Phase 11 的 **evaluation 子系统**（`agent-core/evaluation/`，共 15 个文件，约 600+ 行）：一整套"评估 Agent 控盘有没有用"的框架。

- `EvaluationHarness`：评估入口，跑 golden 正向集 + 失败样本 + A/B。
- `AbExperiment`：A/B 实验结果 record（售罄率/毛利率提升 + `winner()` 判定）。
- `BusinessMetrics` / `BusinessMetricsSource` / `InMemoryBusinessMetricsSource`：经营指标（人工 vs AI）。
- `SystemRunMetrics` / `SystemRunMetricsSource` / `InMemorySystemRunMetricsSource`：系统指标（含**正确失败率**红线）。
- `QualitativeAssessor` / `LocalQualitativeAssessor`：定性补充（模板，**非 LLM**）。
- `EvaluationThresholds` / `EvaluationGateMain`：5 道门槛 Fail-Close + CLI 非零退出阻断。
- `GoldenEvalHarness`（Phase 8 提升）：10 条 golden 正向用例防回归。

## 🧠 为什么是选做

**这是本项目最该"标选做"的一块**，原因很硬：

- REVIEW §4 已坐实：`InMemoryBusinessMetricsSource` 的 A/B 数字是**硬编码种子**（人工 82%/28% vs AI 95%/36%）——即**这 15 个文件、600+ 行框架，最终打印的是一个写死的"AI 胜"**。框架再大，结论也不是规则引擎实跑出来的。
- 面试讲"我有评估闭环 + 正确失败率 + golden 回归"这句概念就够了，不必展开整套 15 文件的内部结构。

> 本选做存在的意义是：**如果你确实被追问"评估到底怎么做的"**，能指到具体文件、讲清门槛与正确失败率，并**诚实**说明 A/B 数字是占位、真实评估见 Phase 14 待办。

## 关键文件（`insightforge-agent-core/.../evaluation/`）

```
EvaluationHarness.java          98   评估入口
AbExperiment.java               61   A/B 结果（sellThroughLift/grossMarginLift/winner）
BusinessMetricsSource.java / InMemoryBusinessMetricsSource.java   指标源（种子）
SystemRunMetricsSource.java / InMemorySystemRunMetricsSource.java 系统指标（正确失败率）
EvaluationThresholds.java       33   5 道门槛 gatePassed()
EvaluationGateMain.java         87   CLI（System.exit(1) 阻断）
GoldenEvalHarness.java          76   golden 回归
LocalQualitativeAssessor.java   35   定性（模板，非 LLM）
```

## 核心入口

```java
EvaluationReport report = evaluationHarness.run();
boolean passed = evaluationThresholds.gatePassed(report);  // 5 道门槛
// EvaluationGateMain.main() 内 System.exit(passed ? 0 : 1) 阻断发布
```

## 如何跑

```bash
mvn -pl insightforge-agent-core test
# 含 EvaluationHarnessTest(2) / EvaluationReportTest(2) / GoldenEvalHarnessTest(1)
```

## ⚠️ 诚实边界（重要，勿虚报）

- **A/B 数字是占位演示数据、非规则引擎实跑产出**——源码注释已写死，不可对外宣称"实测 AI 售罄率 +13pct、毛利 +8pct"。
- 真正驱动规则引擎在模拟秒杀流上跑出指标的真实评估，是 **Phase 14 待办**，未虚报已做。
- `GitHub Actions CI` 评估闸门（`.github/workflows/eval.yml`）写就，沙箱无 CI runner 未实跑；`EvaluationHarnessTest` 是 JUnit 层可信闸门。

## 与主线的关系

主线 Phase 11 只讲"评估闭环 = golden 回归 + 正确失败率 + 门槛 Fail-Close"这个**概念**。本选做展开整套 15 文件实现。**A/B 的占位性质不改 Agent 控盘/规则引擎的真实行为**——评估是"事后量化"，不进热路径。
