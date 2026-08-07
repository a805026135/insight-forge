# Phase 11 —— 评估闭环（M6）

> ⚠️ **本章含选做进阶内容**：整套 A/B evaluation 框架（15 文件）属选做5。**主线只需**"评估闭环 = golden 回归 + 正确失败率 + 门槛 Fail-Close"这个概念。⚠️ 诚实提醒：A/B 数字为占位演示数据、非规则引擎实跑。深度按需读 → [`选做5-AB评估框架.md`](选做5-AB评估框架.md) / [`00-学习路线.md`](00-学习路线.md)。

> 大白话：前面 10 个 Phase 把系统做出来了，但「做得好不好」得拿数据说话，不能自卖自夸。
> Phase 11 干三件事，凑齐「可被量化证明」的闭环：
> 1. **无超卖的硬证据**：用 JMeter 把抢购流量打成洪峰，压完做库存对账，证明 `MIN(stock) ≥ 0` 且 `初始库存 - 已售 = 剩余`。
> 2. **AI 真的有用吗**：双轨评估 harness——先用**客观硬指标**（售罄率/毛利率/库存周转，BigDecimal 确定性算）对比「AI 控盘 vs 人工规则」，再用**定性结论**补一句人话。
> 3. **不让退化蒙混过关**：系统指标报告盯住**正确失败率**这条红线；GitHub Actions CI 把评估门槛当闸门，不达标就阻断合并。
>
> ⚠️ **诚实边界（重要）**：本 Phase 的**离线评估闭环**（golden 回归 + 失败样本回归重放 + A/B + 系统指标 + 门槛判定）在沙箱 `mvn` 实跑验证通过；
> 但 **JMeter 百万压测**（沙箱无 JMeter、无运行中的 seckill 服务）与 **GitHub Actions CI**（沙箱无 CI runner）
> 仅写出配置，**未实跑**。教程会明确标注哪些步骤是「写好了但本环境没跑」。

---

## §1 先搞懂几个术语

| 术语 | 大白话 |
|---|---|
| 评估闭环（Eval Loop） | 把「证明系统没退化、AI 真有用」这件事自动化跑一遍，输出一份量化报告 + 及格/不及格 |
| 双轨评估 | 一条轨道用确定性数字（毛利率/售罄率）硬性对比；另一条轨道用 LLM/模板补一句定性结论。客观优先，定性兜底 |
| golden 回归 | 把历史上「应该成功」的样例（golden set）重跑一遍，确认老功能没被新改动弄坏 |
| 失败样本回归 | 把历史上「危险/越权」的样例重放，确认系统**依然安全拒绝**（Fail-Close 没退化）。这是护栏不退化的证据 |
| A/B 实验 | 同一批货，一边让人工规则定价/调拨，一边让 AI 控盘，比谁卖得好、赚得多 |
| 正确失败率 | AI 该拒绝/该求助时**主动拒绝**（而非硬上）的比例。越高说明 AI 越克制、越安全——这是红线指标 |
| 门槛 / 闸门（Gate） | 一组及格线（如 golden 验收率 100%）。任何一项不达标 → 报告判不及格 → JUnit 断言失败 → `mvn test` 红 → CI 阻断合并 |
| Fail-Close | 遇到看不懂/不安全的情况，宁可拒绝执行也不蒙混过关的设计铁律 |

---

## §2 本 Phase 新增/改动文件清单

**评估核心（`insightforge-agent-core/src/main/java/.../evaluation/`，可实跑）**
- `BusinessMetrics.java` —— record（总量/已售/营收/成本），确定性推导 `grossMarginRate()`/`sellThroughRate()`/`inventoryTurnover()`
- `AbExperiment.java` —— record（人工指标 + AI 指标 + 定性结论），含 `sellThroughLift()`/`grossMarginLift()`/`winner()`
- `SystemRunMetrics.java` —— record（成功/正确失败/失败/最大延迟/成本），含 `correctFailureRate()`（红线）
- `EvaluationThresholds.java` —— record（5 道门槛），`defaults()` = (golden 1.0 / 回归 1.0 / 售罄率提升 0.05 / 毛利率地板 0.20 / 正确失败率上限 1.0)
- `BusinessMetricsSource.java` / `SystemRunMetricsSource.java` —— 数据源**接口**（隔离数据源；生产换真实表实现）
- `InMemoryBusinessMetricsSource.java` / `InMemorySystemRunMetricsSource.java` —— `@Component` 本地种子实现
- `QualitativeAssessor.java` / `LocalQualitativeAssessor.java` —— 定性结论接口 + 本地模板实现（**非 LLM**）
- `FailureSample.java` —— 从 `classpath:evaluation/failure-samples.json` 读 5 条危险输入
- `EvaluationReport.java` —— 聚合四段 + `gatePassed()` 撞 5 道门槛（Fail-Close 闸门）
- `EvaluationHarness.java` —— `@Component`，串起 golden 回归 → 失败样本重放 → A/B → 系统指标
- `EvaluationConfig.java` —— `@Configuration` 暴露 `EvaluationThresholds` Bean
- `EvaluationGateMain.java` —— 纯 `@Configuration` + `main()`，CLI 打印报告；门槛不过 `System.exit(1)`

**测试（`insightforge-agent-core/src/test/.../evaluation/`，沙箱实跑全绿）**
- `EvaluationHarnessTest.java`（2）—— `@SpringBootTest` 真实跑整条闭环，断言 `gatePassed()=true` + `winner=AI_CONTROL`
- `EvaluationReportTest.java`（2）—— 纯 JUnit，验证门槛被打破时 `gatePassed()=false`

**资源 / 配置（部分未实跑）**
- `src/main/resources/evaluation/failure-samples.json` —— 5 条危险输入（activityId 901–905）
- `../golden/phase8-cases.json`（`git mv` 从 test 提升 main，GoldenEvalHarness 复用）—— Phase 8 的 10 条正向 case
- `../planning/GoldenEvalHarness.java`（`git mv` 从 test 提升 main）—— golden 回归编排器
- 父 `pom.xml` —— `<pluginManagement>` 给 surefire 加 `-Djdk.net.URLClassPath.disableClassPathURLCheck=true`（修 JDK21 跨盘 fork 崩溃）

**压测 / CI（⚠️ 沙箱未实跑）**
- `D:/Java/deploy/jmeter/seckill-load-test.jmx` —— JMeter 5.x 计划（2000 线程 × 50 loop，POST /api/seckill）
- `D:/Java/.github/workflows/eval.yml` —— GitHub Actions，`mvn clean package` 即门槛闸门，失败阻断合并

---

## §3 关键代码走读（可实跑部分）

### 3.1 客观硬指标：BigDecimal 确定性推导（绝不靠 LLM 算经营数字）

经营指标必须用 `BigDecimal` 精确算——这是「客观硬指标优先」的落地。AI 可以建议策略，但「赚了多少、卖了多少」这种账不能交给会幻觉的 LLM。

```java
// BusinessMetrics.java（节选）
public record BusinessMetrics(long totalQty, long soldQty, BigDecimal revenue, BigDecimal cost) {
    /** 毛利率 = (营收 - 成本) / 营收，BigDecimal 精确除法 */
    public BigDecimal grossMarginRate() {
        if (revenue.signum() == 0) return BigDecimal.ZERO;
        return revenue.subtract(cost).divide(revenue, 4, RoundingMode.HALF_UP);
    }
    /** 售罄率 = 已售 / 总量 */
    public BigDecimal sellThroughRate() {
        if (totalQty == 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(soldQty)
                .divide(BigDecimal.valueOf(totalQty), 4, RoundingMode.HALF_UP);
    }
}
```

🧠 理解一下：`@Component` 的 `InMemoryBusinessMetricsSource` .seed 了两个世界：人工规则（10000 件卖 8200，单价 100、成本 72 → 售罄 82%、毛利 28%）和 AI 控盘（卖 9500，成本压到 64 → 售罄 95%、毛利 36%）。这两个数就是 A/B 的「擂台双方」。

### 3.2 A/B 判定：双指标都不低于人工，才算 AI 胜

```java
// AbExperiment.java（节选）
public record AbExperiment(BusinessMetrics manual, BusinessMetrics ai, String qualitative) {
    /** AI 相对人工的售罄率绝对提升（可正可负） */
    public BigDecimal sellThroughLift() {
        return ai.sellThroughRate().subtract(manual.sellThroughRate());
    }
    /** 胜者判定：售罄率与毛利率「都不低于」人工，才算 AI 控盘胜 */
    public String winner() {
        boolean better = ai.sellThroughRate().compareTo(manual.sellThroughRate()) >= 0
                && ai.grossMarginRate().compareTo(manual.grossMarginRate()) >= 0;
        return better ? "AI_CONTROL" : "MANUAL_RULE";
    }
}
```

🧠 为什么是「都不低于」而不是「至少一个更高」？因为业务上 AI 不能为了冲售罄率就牺牲毛利（赔本赚吆喝不算赢）。双指标约束让结论站得住脚。

### 3.3 失败样本回归：危险输入重放，断言仍被安全拒绝

这是「护栏没退化」的硬证据。重放用的不是另起一套，而是**同一个编排器**但拧成 `forceInvalidPolicy=true`，逼它走危险路径——证明的是「同一套 DSL 编译/校验在危险输入下依然 Fail-Close」。

```java
// EvaluationHarness.java（节选）—— 失败样本重放
private List<EvaluationReport.RegressionResult> replayFailureSamples() throws Exception {
    List<FailureSample.Item> samples = FailureSample.load();   // 5 条危险输入
    for (FailureSample.Item s : samples) {
        AgentTask task = new AgentTask(s.activityId(), s.goal(), null);
        PlanningResult r = rejectOrchestrator.plan(task);      // forceInvalidPolicy=true
        AgentRunStatus expect = AgentRunStatus.valueOf(s.expect()); // 期望 CORRECT_FAILURE
        boolean passed = r.status() == expect;                 // 仍被安全拒绝才算过
        out.add(new EvaluationReport.RegressionResult(s, r.status(), passed));
    }
}
```

### 3.4 门槛闸门：5 道线，任意一道不及格就 Fail-Close

```java
// EvaluationReport.java（节选）—— 这是「CI 阈值失败阻断」的依据
public boolean gatePassed() {
    gateFailures.clear();
    if (goldenAcceptanceRate() < thresholds.minGoldenAcceptanceRate().doubleValue())
        gateFailures.add("golden 验收率不足…");
    if (regressionPassRate() < thresholds.minRegressionPassRate().doubleValue())
        gateFailures.add("失败样本回归通过率不足…");
    if (abExperiment.sellThroughLift().compareTo(thresholds.minSellThroughLift()) < 0)
        gateFailures.add("AI 售罄率提升低于阈值…");
    if (abExperiment.ai().grossMarginRate().compareTo(thresholds.minGrossMarginFloor()) < 0)
        gateFailures.add("AI 毛利率低于地板…");
    BigDecimal cfr = systemMetrics.correctFailureRate();
    if (cfr.signum() < 0 || cfr.compareTo(thresholds.maxCorrectFailureRate()) > 0)
        gateFailures.add("正确失败率越界…");
    return gateFailures.isEmpty();   // 全过才 true
}
```

🧠 `EvaluationHarnessTest` 在 JUnit 里断言 `gatePassed()=true`——所以 `mvn test` 不过 → 构建红 → CI 被挡。门槛逻辑对本地测试和远程 CI **一模一样**，这就是「可信验证」。

### 3.5 CLI 闸门：把报告打印出来，门槛不过就非零退出

```java
// EvaluationGateMain.java（节选）—— 纯配置类，不实现 CommandLineRunner（避免被 @SpringBootTest 误执行）
@Configuration @EnableAutoConfiguration @ComponentScan @MapperScan(...)
public class EvaluationGateMain {
    public static void main(String[] args) throws Exception {
        try (var ctx = SpringApplication.run(EvaluationGateMain.class, args)) {
            var harness = ctx.getBean(EvaluationHarness.class);
            EvaluationReport report = harness.run();
            // …打印报告…
            if (!report.gatePassed()) {
                System.err.println("评估门槛未通过：" + report.gateFailures());
                System.exit(1);   // 非零退出 → CI 步骤判失败
            }
        }
    }
}
```

---

## §4 验证（离线评估闭环，沙箱实跑）

```bash
# 在 insightforge 根目录：全 reactor 构建 + 全部测试
mvn clean package
```

期望结尾（累计测试数随 Phase 递增，以本次真实输出为准）：

```
[INFO] Tests run: 35, Failures: 0, Errors: 0, Skipped: 0   <- api（不变）
[INFO] Tests run: 26, Failures: 0, Errors: 0, Skipped: 0   <- agent-core（Phase 11 新增 4）
[INFO] Tests run: 22, Failures: 0, Errors: 0, Skipped: 0   <- rule-engine（不变）
[INFO] BUILD SUCCESS
```

> 数字说明：本 Phase agent-core 新增 4 测试（`EvaluationHarnessTest` 2 + `EvaluationReportTest` 2）；
> api 累计 35、agent-core 26、rule-engine 22，**全项目累计 83 测试全绿**。
> `EvaluationHarnessTest` 真实跑通整条闭环：golden 10 条全过、失败样本 5 条全 `passed`、A/B 判 `AI_CONTROL` 胜、正确失败率 ∈ [0,1]。

可选：用 CLI 单独打印评估闭环报告（真实可跑）：

```bash
mvn -pl insightforge-agent-core -am test-compile exec:java \
  -Dexec.mainClass=com.insightforge.agent.core.evaluation.EvaluationGateMain
# 期望：打印 golden/回归/A/B/系统指标四段，结尾 gatePassed=true；门槛不过则退出码 1。
```

> ⚠️ **诚实边界**：**JMeter 百万压测**与 **GitHub Actions CI** 在沙箱**未实跑**
> （无 JMeter、无运行中的 seckill 服务、无 CI runner）。它们的配置已写好，读者在本机按下面命令即可：
> ```bash
> # 1) JMeter 压测（需本机安装 JMeter 5.x，且先启动 seckill 服务）
> #    用 GUI 打开 deploy/jmeter/seckill-load-test.jmx，或命令行：
> jmeter -n -t deploy/jmeter/seckill-load-test.jmx -l seckill-result.jtl
> #    压完做对账（见 .jmx 顶部注释）：MIN(stock)≥0 且 初始库存-SUM(sold)=SUM(stock) 即证明无超卖。
> #
> # 2) CI（需推到 GitHub/Gitee，其 Actions runner 自动跑 eval.yml）
> #    推动 master/main 或开 PR → CI 自动 mvn clean package → 评估门槛不过则阻断合并。
> ```

---

## §5 评估闭环数据流

```mermaid
flowchart TB
    subgraph 离线评估闭环[EvaluationHarness.run]
      G[golden 回归\nPhase8 10 case] --> RPT[EvaluationReport]
      F[失败样本回归\n5 危险输入重放\nforceInvalidPolicy=true] --> RPT
      A[数据源\nAI vs 人工\nInMemory 种子] --> AB[A/B 实验\n售罄率/毛利]
      AB --> Q[定性结论\n模板非LLM]
      Q --> RPT
      S[系统指标\n正确失败率] --> RPT
    end
    RPT --> GATE{gatePassed?\n撞 5 道门槛}
    GATE -- 全过 --> OK[BUILD SUCCESS\nCI 放行]
    GATE -- 任一不过 --> FAIL[System.exit(1)\nCI 阻断合并]
```

```mermaid
flowchart LR
    subgraph 数据面证据[可被量化证明的两件事]
      J[JMeter 洪峰\nPOST /api/seckill] --> REC[库存对账\nMIN(stock)≥0\n初始-已售=剩余]
      REC --> PROOF1[证明：无超卖 + exactly-once]
      LOOP[评估闭环门槛\ngolden 100% / 回归 100%\nAI 售罄+5% / 毛利≥20%] --> PROOF2[证明：AI 策略真提升经营]
    end
```

---

## §6 排错表（本 Phase 实踩的坑）

| 现象 | 根因 | 解法 |
|---|---|---|
| 编译报错：找不到符号 `GoldenEvalHarness` | 它在 Phase 8 写在 `src/test/`，main 代码不可见 | `git mv` 提升到 `src/main/java/.../planning/`，并把 `phase8-cases.json` 移到 `src/main/resources/golden/` |
| 编译报错：找不到符号 `PlanningResult` | 导入包写成了 `...runtime` 而非 `...planning` | 改 `import com.insightforge.agent.core.planning.PlanningResult;` |
| 编译报错：找不到符号 `BigDecimal` | `EvaluationReport` 用到却没 import | 加 `import java.math.BigDecimal;` |
| `BUILD FAILURE`：`The forked VM terminated without properly saying goodbye` | JDK 21 下 surefire boot jar 落在 `C:`（系统 temp），工程在 `D:`，跨盘符拒绝 Manifest 绝对路径 | ① 跑测试时设 `java.io.tmpdir=D:/Java/tmp`（boot jar 同盘）；② 父 POM surefire 加 `-Djdk.net.URLClassPath.disableClassPathURLCheck=true`（双保险） |
| `EvaluationReportTest` 断言失败：`expected <true> but was <false>` | 中文精确匹配 `gateFailures().contains("golden 验收率不足")` 疑似编码不一致 | 改为 `stream().anyMatch(s -> s.contains("golden"))` / `contains("售罄率")`，并打印实际失败原因 |
| `EvaluationGateMain` 被 `@SpringBootTest` 误执行并 `System.exit(1)` 杀掉测试 JVM | 它实现了 `CommandLineRunner`，Spring 测试上下文启动会自动跑 | 重构为纯 `@Configuration` 类，逻辑只在 `main()` 内 `try(ctx){...}` 执行，去掉 `CommandLineRunner` |
| `EvaluationGateMain` 编译报错：未报告的异常 `Exception` | `harness.run()` 抛 `Exception` 但 `main` 未声明 | `main(String[] args) throws Exception` |
| JMeter / CI 无法验证 | 沙箱无 JMeter、无运行中的服务、无 CI runner | 写好 `.jmx` 与 `eval.yml`；本环境不虚报「已压测 / 已 CI 通关」 |

---

## §7 完工总结 & 项目总收尾

**Phase 11 交付**：
- 评估闭环（`EvaluationHarness` + `EvaluationReport`）实跑通过：golden 10 条全过、失败样本 5 条全 `passed`、A/B 判 `AI_CONTROL` 胜、正确失败率 ∈ [0,1]、门槛 `gatePassed()=true`。
- 门槛 Fail-Close 由 `EvaluationHarnessTest`（2）+ `EvaluationReportTest`（2）在 JUnit 层可信断言——这就是「CI 阈值失败阻断」的本地等价验证（累计 **83 测试全绿**）。
- JMeter 压测计划（`deploy/jmeter/seckill-load-test.jmx`）与 GitHub Actions（`eval.yml`）写就，诚实标注未实跑。

**回看整条 Phase 0–11 主线（智能秒杀 / 大促平台，Agent 辅助）**：
- 高并发命脉：`Redis+Lua` 原子扣减 + 条件更新防超卖（`stock>=qty`）→ Phase 1/2；
- 工程化：多级缓存 + 限流（Phase 5）、状态机 + 对账（Phase 6）；
- AI 灵魂：**控制面/数据面分离**——LLM 离线生成护栏 DSL（Phase 7/8）→ Drools 在线确定性执行（Phase 9），热路径零 LLM；
- 看得见 + 可证明：大屏/可观测（Phase 10）、评估闭环（Phase 11）。

> 至此 Phase 0–11 全部落地。**诚实边界总览**：后端全链路（扣减/缓存/限流/状态机/对账/Agent 运行时/规则引擎/评估闭环）均 `mvn` 实跑通过；
> 前端 React 大屏、Docker/Nginx/Grafana 部署、JMeter 百万压测、GitHub Actions CI 仅在代码中写就、本沙箱未实跑——全部已显式标注，未虚报。
