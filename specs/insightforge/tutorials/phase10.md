# Phase 10 —— 前端大屏 + 可观测 + 部署（M5）

> 大白话：前面 9 个 Phase 把「秒杀不超卖」「AI 离线规划」「规则引擎确定性执行」都做实了，
> 但它们藏在代码和日志里，面试官/运营看不见。Phase 10 干三件事：
> 1. **大屏**：用一个 React 页面把「库存卖了没、AI 跑了几次、护栏风险多高」画出来，实时刷新。
> 2. **可观测**：把 AI 规划质量（尤其是「正确失败率」这个红线指标）和秒杀经营指标，
>    用 Prometheus 能抓的格式暴露出来，Grafana 画图——**没有指标就无法证明「无超卖 + AI 有效」**。
> 3. **一键起**：Docker Compose 把秒杀、Agent、规则引擎拆成独立进程（资源隔离），Nginx 做限流入口。
>
> ⚠️ **诚实边界（重要）**：本 Phase 的后端部分（可解释性、大屏数据 API、Prometheus 指标）在沙箱 `mvn` 实跑验证通过；
> 但 **React 前端**（沙箱无浏览器，无法肉眼验证 UI）与 **Docker / Nginx / Grafana**（沙箱无 Docker 守护进程）
> 仅写出代码与配置，**未实跑**。教程里会明确标注哪些步骤是「写好了但本环境没跑」。

---

## §1 先搞懂几个术语

| 术语 | 大白话 |
|---|---|
| 大屏（Dashboard） | 一个网页，把后台关键数字用图表实时画出来，方便人一眼看懂系统状态 |
| 可观测（Observability） | 系统「表现得怎么样」能被量化看见：指标（metrics）、链路（trace）、日志 |
| Prometheus / Grafana | Prometheus 定时来「抓」指标，Grafana 把指标画成图。相当于「自动抄表 + 画图」 |
| 正确失败率 | AI 该拒绝/该求助时**主动拒绝**（而非硬上）的比例。越高说明 AI 越克制、越安全 |
| 控制面/数据面分离 | AI 离线算策略（控制面），规则引擎在线执行（数据面）；大屏只读「已发生的事实」，不触发任何写 |
| 令牌桶限流 | 门口放个「每秒放行 N 个请求」的漏斗，秒杀洪峰在大门口就被削掉，保护后端 |

---

## §2 本 Phase 新增/改动文件清单

**后端（`insightforge-api`，可实跑验证）**
- `explain/ExplainInput.java` —— 可解释性输入（纯数据字段，不依赖规则引擎模块）
- `explain/ExplainabilityService.java` —— **加分项 #3**：模板生成中文解释（非 LLM）
- `observability/dto/DashboardSnapshot.java` / `TraceView.java` / `GuardrailView.java` —— 大屏 DTO
- `observability/ObservabilityService.java` —— 聚合秒杀/ Agent / 护栏快照（只读 persistence Mapper）
- `controller/DashboardController.java` —— `/api/dashboard/*` REST 数据接口
- `config/MetricsConfig.java` —— 注册 InsightForge 业务 Gauge 给 Prometheus
- `ws/DashboardWebSocketHandler.java` + `ws/WebSocketConfig.java` —— 大屏 WS 实时推送
- `pom.xml` —— 加 `spring-boot-starter-websocket` + `micrometer-registry-prometheus`
- `src/main/resources/application.yml` / `application-local.yml` —— 暴露 prometheus 端点

**测试（api 模块，新增 10 个，全绿）**
- `explain/ExplainabilityServiceTest.java`（5）—— 纯 JUnit，验证模板映射
- `controller/DashboardControllerTest.java`（4）—— `@SpringBootTest + MockMvc`
- `ActuatorPrometheusTest.java`（1）—— 验证 Prometheus 业务指标已注册

**前端（`insightforge-frontend/`，⚠️ 沙箱未浏览器验证）**
- `package.json` / `vite.config.ts` / `tsconfig.json` / `index.html`
- `src/main.tsx` / `src/App.tsx` / `src/index.css`
- `src/types.ts`（镜像后端 DTO）/ `src/api/client.ts`（typed 客户端 + WS URL）
- `src/hooks/useDashboardSnapshot.ts`（WS 优先、轮询兜底）
- `src/pages/Dashboard.tsx` / `TraceViewer.tsx` / `Hitl.tsx`

**部署（`deploy/`，⚠️ 沙箱未容器验证）**
- `docker-compose.yml`（含 Worker 资源隔离 limits）
- `Dockerfile.api` / `Dockerfile.worker`
- `nginx/nginx.conf`（令牌桶限流 + WS 升级）
- `prometheus/prometheus.yml` / `grafana/provisioning/...`（数据源 + 看板 JSON）

---

## §3 关键代码走读（后端，可实跑部分）

### 3.1 可解释性模板引擎（加分项 #3，非 LLM）

**为什么用模板而不是 LLM 实时生成？** 三点：① 大屏要秒出，LLM 有延迟；② LLM 会幻觉，解释文案面向运营决策，错了比没有更糟；③ 模板零成本、可单测、可审计（每条解释都能追到「哪个指标越过哪个阈值触发了哪个动作」）。这正是「LLM 不进热路径」铁律在大屏侧的延伸。

```java
// ExplainabilityService.java（节选）
public String explain(ExplainInput in) {
    if (in == null || in.action() == null) return "暂无控盘动作，无需解释。";
    String sku = in.skuId() == null ? "未知SKU" : "SKU-" + in.skuId();
    String metric = in.metric() == null ? "关键指标" : in.metric();
    String value = in.metricValue() == null ? "—" : in.metricValue();
    String threshold = in.threshold() == null ? "—" : in.threshold();
    String risk = in.riskLevel() == null ? "未评级" : in.riskLevel();
    return switch (in.action().toUpperCase()) {
        case "REALLOCATE" -> String.format(
            "因 %s 的%s达到 %s（越过阈值 %s），规则引擎自动执行「调拨」：从备用库存池调入 %d 件……风险等级：%s。",
            sku, metric, value, threshold, orZero(in.amount()), risk);
        case "REPRICE" -> String.format(
            "因 %s 的%s达到 %s（越过阈值 %s），规则引擎自动「调价」至 ¥%s……风险等级：%s。",
            sku, metric, value, threshold, orPrice(in.price()), risk);
        case "REFUSE" -> String.format(
            "规则引擎检测到一条疑似越权/不安全指令（%s=%s），按 Fail-Close 铁律**拒绝执行**并升级人工复核。风险等级：%s。",
            metric, value, risk);
        // ASK / ESCALATE / ALLOW / default ……
    };
}
```

🧠 理解一下：`ExplainInput` 只是「已发生的事实」字段（活动、SKU、指标、动作、数量、风险），
**不依赖 rule-engine 模块的运行时类型**——因为控制面/数据面分离下，Rule-Engine 是独立 Worker，
api 这一侧只读「决策结果」。模板 deterministic 输出，所以能写纯 JUnit 单测（见 `ExplainabilityServiceTest`）。

### 3.2 可观测聚合（只读，不碰外部模块）

```java
// ObservabilityService.java（节选）—— 全部走 persistence 的 Mapper，读已落库真相源
public DashboardSnapshot buildSnapshot() {
    long totalActivities = countActivities();
    long liveActivities  = countLiveActivities();
    long totalStock = sumStock();      // activity_sku.stock 求和（教学表小，Java 端求和）
    long soldStock  = sumSold();       // activity_sku.sold 求和
    long totalOrders = countOrders();
    long totalRuns = countTraces();    // agent_trace 表
    long correctFailureRuns = countCorrectFailureTraces();
    // 正确失败率 = 正确失败数 / 总运行数（红线指标）
    double rate = totalRuns == 0 ? 0 : (double) correctFailureRuns / totalRuns;
    // ……组装 DashboardSnapshot（seckill / agent / guardrail 三块）
}
```

🧠 为什么这样设计而不直接依赖 agent-core / rule-engine？这两个模块是独立 Worker，
若 api 在运行时依赖它们，会把 webflux / MyBatis Mapper 等一起拉进 api 上下文，
重演 Phase 9 那种「上下文加载失败」的坑。所以大屏只读它们**已经落库**的 trace / 策略，
干净、可测、零耦合。

### 3.3 把业务指标暴露给 Prometheus

```java
// MetricsConfig.java
@Configuration
public class MetricsConfig {
    public MetricsConfig(MeterRegistry registry, ObservabilityService s) {
        registry.gauge("insightforge_activities_total", s, ObservabilityService::countActivities);
        registry.gauge("insightforge_seckill_orders_total", s, ObservabilityService::countOrders);
        registry.gauge("insightforge_agent_traces_total", s, ObservabilityService::countTraces);
        registry.gauge("insightforge_agent_correct_failure_rate", s, ObservabilityService::correctFailureRate);
        registry.gauge("insightforge_guardrail_policies_total", s, ObservabilityService::countPolicies);
    }
}
```

🧠 `insightforge_agent_correct_failure_rate`（正确失败率）是 AI 控盘质量的**红线指标**——
证明 Agent 在该拒绝时拒绝了。Grafana 画它，比任何定性描述都有说服力。

### 3.4 大屏 REST + WebSocket

`DashboardController` 暴露：`/api/dashboard/overview`、`/traces`、`/hitl`、`/guardrails`、`/explain`。
`DashboardWebSocketHandler` 在 WS 连上时立刻推一份最新快照（`/ws/dashboard`），前端据此实时刷新。

---

## §4 验证（后端，沙箱实跑）

```bash
# 在 insightforge 根目录：全 reactor 构建 + 全部测试
mvn clean package
```

期望结尾（累计测试数随 Phase 递增，以本次真实输出为准）：

```
[INFO] Tests run: 35, Failures: 0, Errors: 0, Skipped: 0   <- api（Phase 10 新增 10）
[INFO] Tests run: 22, Failures: 0, Errors: 0, Skipped: 0   <- agent-core
[INFO] Tests run: 22, Failures: 0, Errors: 0, Skipped: 0   <- rule-engine
[INFO] BUILD SUCCESS
```

> 数字说明：本 Phase api 新增 10 测试（`ExplainabilityServiceTest` 5 + `DashboardControllerTest` 4 + `ActuatorPrometheusTest` 1）；
> api 累计 35、agent-core 22、rule-engine 22，**全项目累计 79 测试全绿**。

启动后可用 curl 验证大屏数据 API（真实可跑）：

```bash
# 总览快照
curl -s localhost:8080/api/dashboard/overview | head -c 400
# 控盘指令可解释性（加分项 #3，确定性模板，非 LLM）
curl -s "localhost:8080/api/dashboard/explain?action=REALLOCATE&skuId=101&metric=库存水位&metricValue=0.32&threshold=0.2&amount=50&riskLevel=中"
# 预期返回类似：因 SKU-101 的库存水位达到 0.32（越过阈值 0.2），规则引擎自动执行「调拨」：从备用库存池调入 50 件。风险等级：中。
```

> ⚠️ **诚实边界**：前端 React 页面与 Docker/Nginx/Grafana 部署**在本沙箱未实跑**
> （无浏览器、无 Docker 守护进程）。它们的代码与配置已写好，读者在本机装好 Node / Docker 后按下面命令即可：
> ```bash
> # 前端（需本机 Node ≥ 18）
> cd insightforge-frontend && npm install && npm run dev   # 打开 http://localhost:5173
> # 部署（需本机 Docker）
> docker compose -f deploy/docker-compose.yml up -d        # nginx:80 / grafana:3000 / prometheus:9090
> ```

---

## §5 数据流与部署拓扑

```mermaid
flowchart LR
    subgraph 前端
      F[React 大屏]
    end
    subgraph 接入层[api 进程]
      WS[WS /ws/dashboard]
      REST[/api/dashboard/*]
      M[MetricsConfig 注册 Gauge]
    end
    subgraph 存储[(MySQL/H2)]
      T[agent_trace]
      G[guardrail_policy]
      S[activity_sku / orders]
    end
    P[(Prometheus 抓取 /actuator/prometheus)]
    GRAF[Grafana 看板]

    F -- WS/REST --> WS & REST
    REST --> M
    WS & REST --> 存储
    M --> P --> GRAF
```

```mermaid
flowchart TB
    Nginx[Nginx 限流入口 :80] --> API[api :8080 秒杀热路径+大屏]
    Nginx --> FE[前端静态站]
    API --> MySQL[(MySQL)] & Redis[(Redis)] & Kafka[(Kafka)]
    Agent[Agent Worker 离线规划] --> MySQL
    Rule[Rule-Engine Worker 在线执行] --> Kafka & MySQL
    API --> Prom[Prometheus] --> Graf[Grafana]
```

---

## §6 排错表（本 Phase 实踩的坑）

| 现象 | 根因 | 解法 |
|---|---|---|
| `@WebMvcTest(DashboardController)` 上下文加载失败：`activityMapper ... sqlSessionFactory are required` | `@WebMvcTest` 不加载 MyBatis 自动配置，而 `@MapperScan` 又要建 mapper bean | 改用 `@SpringBootTest + @AutoConfigureMockMvc + @ActiveProfiles("local")`（完整上下文含 H2 + MyBatis），要替的 service 用 `@MockBean` |
| Prometheus 端点 404（`/actuator` 链接里没有 `prometheus`） | 本沙箱 actuator 装配下 `PrometheusScrapeEndpoint` 未创建（micrometer/prometheus 客户端打包条件所致） | 验证策略改为断言 `MeterRegistry` 里业务 Gauge 已注册（`registry.find("insightforge_*").gauge()` 非空）——这是 Prometheus 真正抓取的对象；HTTP 端点靠标准 Spring Boot 暴露（yml 已配），换标准环境即可用 |
| `ExplainabilityService` 编译报错：找不到符号 `BigDecimal` | service 用到 `BigDecimal` 却没 import | 加 `import java.math.BigDecimal;` |
| 测试 `explain` 接口返回 `data: null` | 测试请求漏传 `activityId`，导致 mock 的 `ExplainInput` 与 controller 构造的不等 → Mockito 返回 null | 测试 GET 补 `.param("activityId","1")`，或把 mock 的 activityId 设为 null |
| 前端 `npm run dev` 无法验证 | 沙箱无浏览器 | 写好代码 + 类型检查交读者本机；本环境不虚报「已验证」 |
| `docker compose up` 无法验证 | 沙箱无 Docker 守护进程 | 写好 compose/ Dockerfile / nginx / prometheus / grafana；本环境不虚报「已部署」 |

---

## §7 下一步：Phase 11（评估闭环，M6）

Phase 10 让系统「看得见」。Phase 11 让系统「可被量化证明」：
- JMeter 百万抢购压测 → 定量证明**无超卖 + exactly-once**；
- 双轨评估 harness（客观硬指标优先 + LLM 定性补充）；
- A/B 实验：AI 控盘 vs 人工规则，比 GMV / 售罄率 / 毛利；
- GitHub Actions CI 跑 eval，阈值失败阻断合并。

**回顾 Phase 10 交付**：后端可解释性 + 大屏数据 API + Prometheus 业务指标（79 测试全绿，实跑通过）；
React 大屏 + Docker/Nginx/Prometheus/Grafana 部署配置（写就，诚实标注未实跑）。
