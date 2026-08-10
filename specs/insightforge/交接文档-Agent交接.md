# InsightForge（智析）项目交接文档

> 写给接手此项目的另一个 Agent 或开发者。本文档覆盖所有你需要的上下文：项目是什么、代码在哪、环境怎么搭、怎么构建运行、哪些坑踩过了、当前真实状态如何。

---

## 一、项目是什么

**一句话**：Java 21 + Spring Boot 3.3 的智能秒杀/大促平台，面试向项目。核心叙事是「AI Agent 离线规划控盘策略 → Drools 规则引擎在线确定性执行 → 高并发秒杀引擎原子扣减」。**LLM 永远不进入秒杀热路径。**

**大白话**：秒杀像「100 个坑位，1 万人同时冲」。AI 像「聪明的店长」提前算好定价和调货策略写成规则，门口的检票机按规则高速执行——店长不能亲自在门口收票，太慢。

**代码仓库**：Gitee `paper-umbrella/insight-forge.git`，master 分支。
- 仓库根目录：`D:/Java`（注意：不是 `D:/Java/insightforge`，`insightforge/` 和 `specs/` 都在同一个 git 仓库根下）
- Clone 地址：`https://gitee.com/paper-umbrella/insight-forge.git`

---

## 二、需要下载/安装的软件

以下是运行和开发此项目需要的全部外部依赖。按优先级分三档。

### 第一档：必需（零依赖本地跑起来）

| 软件 | 版本 | 下载地址 | 说明 |
|------|------|----------|------|
| JDK | **21**（非 17/非 22） | https://adoptium.net/download/ 或 https://jdk.java.net/21/ | 编译和运行都需要。原环境路径 `D:/devtools/jdk21/jdk-21.0.12+8` |
| Maven | 3.9+ | https://maven.apache.org/download.cgi | 构建工具。原环境路径 `D:/devtools/maven/apache-maven-3.9.9` |
| Git | 任意新版 | https://git-scm.com/downloads | 拉代码用 |
| Node.js | 22.x（推荐） | https://nodejs.org/ | 前端 Vite/React 构建 |
| H2 Database | **无需单独下载** | Maven 依赖自动拉取 | 内存数据库，`local` profile 下零依赖运行 |

### 第二档：集成环境（需要完整端到端跑通时安装）

| 软件 | 版本 | 下载地址 | 说明 |
|------|------|----------|------|
| Docker Desktop | 任意新版 | https://www.docker.com/products/docker-desktop/ | 起 MySQL/Redis/Kafka 容器。**沙箱无 Docker，此为待迁移项** |
| MySQL | 8.x | https://dev.mysql.com/downloads/mysql/ 或用 Docker 镜像 | 生产数据库 |
| Redis | 7.x | https://redis.io/download/ 或用 Docker 镜像 | 缓存/锁/限流 |
| Apache Kafka | 3.x | https://kafka.apache.org/downloads 或用 Docker 镜像 | 消息队列 |
| JMeter | 5.6+ | https://jmeter.apache.org/download_jmeter.cgi | 压测（脚本已写就，目录 `deploy/jmeter/`） |
| Grafana | 10.x | https://grafana.com/grafana/download | 监控看板（面板配置已写就） |

### 第三档：可选（仅在需要时）

| 软件 | 版本 | 下载地址 | 说明 |
|------|------|----------|------|
| DeepSeek API Key | — | https://platform.deepseek.com/ | 真 LLM 接入。当前用离线确定性桩，切真模型需 API Key |
| Milvus | 2.x | https://milvus.io/docs/install_standalone-docker.md | 向量库。当前为桩（抛 UnsupportedOperationException），未真实落地 |
| Nginx | 1.x | https://nginx.org/en/download.html | 接入层限流。配置写就未实跑 |
| Sentinel | 1.8+ | https://github.com/alibaba/Sentinel | 应用层熔断。未落地 |

---

## 三、代码目录结构

```
D:/Java/                              ← Git 仓库根
├── insightforge/                     ← 后端 Maven 多模块
│   ├── pom.xml                       ← 父 POM（Spring Boot 3.3.5 + Drools 9.44 + MyBatis-Plus 3.5.11）
│   ├── insightforge-common/          ← 公共工具/配置/异常/ApiResult<T>
│   ├── insightforge-persistence/     ← 实体（Activity/Product/ActivitySku/Orders/AgentTrace/AgentSpan...）+ MyBatis-Plus Mapper
│   ├── insightforge-seckill-core/    ← 高并发引擎：Lua 扣减/Redisson 锁/Kafka 削峰/幂等/状态机
│   ├── insightforge-api/             ← REST 接入层：Controller + Service + DTO + H2 本地运行
│   │   └── src/main/resources/
│   │       ├── application.yml       ← 默认配置（MySQL/Redis/Kafka localhost，对应 integration profile）
│   │       ├── application-local.yml ← 本地零依赖配置（H2 内存库，禁用 Redis/Kafka 自动配置）
│   │       ├── schema.sql            ← 所有建表 DDL（H2 + MySQL 兼容）
│   │       └── data.sql              ← 种子数据（用户/商品/活动/SKU）
│   ├── insightforge-agent-core/      ← 自研 Agent 运行时：工具注册/ReAct 循环/记忆/Critic/多智能体编排
│   └── insightforge-rule-engine/     ← 护栏规则引擎：DSL→Drools 翻译/在线执行/调拨/调价
│
├── insightforge-frontend/            ← 前端 React 项目
│   ├── package.json                  ← 依赖：React 18 + Vite + Antd + ECharts + react-router-dom v6
│   ├── src/api/index.ts              ← 统一 API 层，所有后端接口封装在此
│   └── src/pages/                    ← 页面组件
│       ├── user/SeckillPage.tsx      ← 买家秒杀页
│       ├── user/OrderHistory.tsx     ← 我的订单
│       ├── merchant/Dashboard.tsx    ← 运营大屏
│       ├── merchant/Activities.tsx   ← 活动管理（含创建活动 + AI 规划）
│       ├── merchant/Guardrails.tsx   ← 护栏策略
│       ├── merchant/Hitl.tsx         ← 人工审批
│       └── merchant/TraceViewer.tsx  ← Agent 执行轨迹 + Span 树
│
└── specs/insightforge/               ← 所有文档（和代码同仓库）
    ├── plan.md                       ← 技术方案
    ├── spec.md                       ← 详细设计规格（约 367 行 / 42KB）
    ├── tasks.md                      ← 任务清单（全部 Phase 0–13 完成状态）
    ├── analysis.md                   ← 架构分析
    ├── glossary.md                   ← 术语速查
    ├── 面试讲解稿.md                  ← 面试 Q&A
    ├── 本机实跑指南.md               ← 4 档递进运行指南（重要！）
    ├── 全项目体检报告.md              ← 代码/依赖/技术债盘点
    ├── REVIEW-规划审查与风险分析.md    ← 规划审查与修复状态
    ├── tutorial.md                   ← 总教学大纲
    └── tutorials/                    ← 分 Phase 手把手教程（Phase 0–11 + 选做 1–7）
```

---

## 四、怎么构建和运行

### 4.1 构建（最重要的一步）

**标准方式**（你自己的 JDK/Maven 已配好 PATH）：
```bash
cd D:/Java/insightforge
mvn clean package
```

**如果 Maven 未配 PATH，用 classworlds 直拉**（原沙箱 workaround）：
```bash
cd D:/Java/insightforge
JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=D:/Java/tmp" \
java -classpath "D:/devtools/maven/apache-maven-3.9.9/boot/plexus-classworlds-2.8.0.jar" \
  -Dclassworlds.conf=D:/devtools/maven/apache-maven-3.9.9/bin/m2.conf \
  -Dmaven.home=D:/devtools/maven/apache-maven-3.9.9 \
  -Dmaven.multiModuleProjectDirectory=D:/Java/insightforge \
  org.codehaus.plexus.classworlds.launcher.Launcher clean package
```

**预期结果**：`BUILD SUCCESS`，96 个测试全部通过（api 39 + agent-core 26 + rule-engine 29 + seckill-core 2）。

### 4.2 运行（local 零依赖模式）

```bash
cd D:/Java/insightforge/insightforge-api
java -Djava.io.tmpdir=D:/Java/tmp -jar target/insightforge-api-0.1.0-SNAPSHOT.jar --spring.profiles.active=local --server.port=8080
```

验证：
```bash
curl http://localhost:8080/actuator/health    # → {"status":"UP"}
curl http://localhost:8080/api/activity       # → 活动列表（种子数据 2 条）
curl http://localhost:8080/api/activity/1/skus  # → iPhone 16 (6299元, stock=100)
```

### 4.3 运行前端
```bash
cd D:/Java/insightforge-frontend
npm install        # 首次运行
npm run dev        # → http://localhost:5173，Vite proxy 反代 /api → localhost:8080
```

### 4.4 运行集成环境（需要 Docker）

```bash
# 1. 起中间件
cd D:/Java/insightforge
docker-compose up -d   # MySQL + Redis + Kafka

# 2. 以 integration profile 启动
cd insightforge-api
java -jar target/insightforge-api-0.1.0-SNAPSHOT.jar --spring.profiles.active=integration
```

### 4.5 真实闭环一键部署（Phase 14.1，2026-08-10 已实跑验证）

```bash
# 仓库根目录（D:/Java 或任意 clone 位置）
cd insightforge
docker compose -f deploy/docker-compose.yml up -d --build

# 宿主访问入口（本机演示环境端口被占用时的实际映射）：
#   前端/Nginx   http://localhost/          （/api 反代到后端）
#   后端直连      http://localhost:18080/api
#   Prometheus   http://localhost:9090
#   Grafana      http://localhost:3001     （admin/admin，看板已预置）
```

> **已实跑验证（2026-08-10，真实 Docker + MySQL 8 + Redis 7 + Kafka 3.7）**：
> 建活动 → Agent 规划发布 DSL v1 → 预热 → 上线前对账 → LIVE → 85 人并发抢购（85/85 成功）
> → `sales.tick` 被独立 rule-engine-worker 消费 → 规则引擎执行 **REPRICE（实时价 6299→6598.9）+
> REALLOCATE（调拨 +50）** → 写回同一 Redis 库存键 + DB 同步 + 审计落库 → 取消订单回补
> → 结算对账 **passed=True**（Redis/DB/台账三方一致）→ 大屏 GMV 真实统计。

---

## 五、关键设计决策（不要改！）

这些是反复纠结后的最终决定，接手后不要重开讨论：

### 5.1 控制面/数据面分离
- Agent 离线生成护栏 DSL → Drools 在线确定性执行
- **LLM 永远不进入秒杀热路径**（延迟/非确定/幻觉与强一致冲突）
- 用户抢购路径：Redis Lua 原子扣减（无显式锁，但原子不可打断）
- AI 控盘路径：Redisson 锁 0ms Fail-Fast（抢不到立即放弃，用户永远优先）

### 5.2 防超卖 = 原子条件更新
- 核心 SQL：`UPDATE activity_sku SET stock=stock-#{qty} WHERE id=? AND stock>=#{qty}`
- 受影响行数为真相，`@Version` 乐观锁在此为冗余

### 5.3 Profile 策略（集成缝核心）
- `@Profile("!integration")` = 默认内存实现，沙箱/单测零依赖
- `@Profile("integration")` = 真实 Redis/Redisson/Kafka 实现
- 所有 `local` profile 下的实现都标注了 `@Profile("!integration")`

### 5.4 锁超时分级
- 用户路径：`tryLock(10ms)` → 抢锁失败立即返回，不阻塞
- AI 调拨：`tryLock(0ms)` → 控制面不跟用户争，抢不到直接放弃

### 5.5 "同一份库存"的精确含义
用户热路径走 Lua 原子扣减 `stock:a:{aid}:s:{skid}`，AI 控盘走 Redisson 锁同一键名空间 + `INCRBY` 写回同一键。两者共享同一个 Redis 键，互相可见。**不是字面「双方都持有同一把 Redisson 锁」**——用户路径不走锁，靠 Lua 原子性。

### 5.6 Maven 依赖版本固定
父 POM `D:/Java/insightforge/pom.xml` 有 `<dependencyManagement>` 统一版本：
- Spring Boot `3.3.5`
- MyBatis-Plus `3.5.11` + mybatis-spring `3.0.4`（**必须 3.0.4**，3.0.3 与 Spring 6.1 不兼容）
- Drools `9.44.0.Final`（**是 9.x，不要降回 7.x**）
- Spring AI `1.0.0-M6`（里程碑版本，需配置 Spring Milestones 仓库）
- Spring AI Milestones 仓库 URL：`https://repo.spring.io/milestone`

---

## 六、已知踩过的坑

### 6.1 构建相关
1. **Surefire 跨盘符崩溃**：测试运行在 `C:` 临时目录而代码在 `D:` → 必须设 `-Djava.io.tmpdir=D:/Java/tmp`（同盘）。父 POM 已加 `argLine=-Djdk.net.URLClassPath.disableClassPathURLCheck=true`
2. **mybatis-spring 版本**：MyBatis-Plus 3.5.x 默认带 mybatis-spring 3.0.3 → 与 Spring 6.1 的 `factoryBeanObjectType` 类型不兼容。必须在父 POM 显式 override 为 `3.0.4`
3. **Git Bash 的 Maven**：直接用 `mvn` 可能 ClassNotFound → 需要 classworlds 直拉（见 §4.1 备选命令）
4. **文件被旧 Java 进程锁住**（Windows）：`taskkill /F /IM java.exe` 强制杀进程后再 `clean`

### 6.2 数据库
5. **H2 显式 ID 插入后序列计数器不推进**：种子数据 `INSERT INTO activity VALUES (1, ...)` 后 auto-increment 仍从 1 开始 → 主键冲突。修复：`data.sql` 末尾加了 `ALTER TABLE activity/product/activity_sku ALTER COLUMN id RESTART WITH 3`
6. **`schema.sql` 和 `schema-agent.sql` 必须同步**：api 模块用 `schema.sql`，agent-core 测试用 `schema-agent.sql`。改了一个表结构必须两个文件都改。

### 6.3 业务逻辑
7. **订单状态 `CREATE_ORDER` 不对齐**：`OrderStatus` 枚举首态是 `CREATE_ORDER`，但异步消费者曾写死 `"CREATED"` 字符串 → `OrderStatus.from("CREATED")` 抛异常 500。已修复为 `OrderStatus.CREATE_ORDER.name()`
8. **幂等键限一单**：`buildOrderId = OF-{activityId}-{skuId}-{userId}` 不含 qty，隐含每用户每 SKU 限一单（秒杀限购）。这是有意设计，已在注释中显式声明。若要支持多单需把 qty 纳入 orderId。

### 6.4 Agent 运行时
9. **`GuardrailRule` 是扁平结构**，不是嵌套的 `{trigger,condition,action}` 三层。不要按旧文档的嵌套 Schema 理解。实际是 `(id, metric, operator, threshold, action, params Map)`。
10. **`MultiAgentOrchestrator` 使用 record 的 accessor 方法**，不要用 `getXxx()` 前缀（Java record 是 `xxx()`）。

---

## 七、当前真实状态（诚实，不虚报）

### ✅ 已完成（实跑验证通过）
| 项目 | 状态 |
|------|------|
| 后端全链路 101 测试 | ✅ `mvn clean package` BUILD SUCCESS（api 44 + agent-core 26 + rule-engine 29 + seckill-core 2） |
| `local` profile 零依赖启动 | ✅ 8080 端口，所有 REST API 可用 |
| 防超卖（原子条件更新 + Lua） | ✅ `SeckillNoOversellTest` 300 并发抢 100 → stock=0/订单=100 |
| 幂等去重 | ✅ 同一用户重复请求不产生重复订单 |
| 活动/订单双状态机 + 对账 | ✅ `ActivityStateMachineTest(9)` + `ReconcileTest` |
| Agent 自研运行时（离线桩） | ✅ 工具注册/ReAct/Critic/多智能体编排/多角色 Agent |
| DSL 编译 + Fail-Close 门禁 | ✅ `DslCompiler` 两道校验，编译不过拒绝发布 |
| Drools 规则引擎在线执行 | ✅ 29 测试全绿，确定性执行零 LLM |
| 集成缝（键契约对齐） | ✅ seckill Redis 键与 rule-engine 逐字符一致，`sales.tick` 生产者+消费者已通 |
| 前端（React + Vite + Antd） | ✅ 本地 5173 可跑，登录/秒杀/订单/大屏/审批 全部接真实 API |
| **真实闭环端到端（Docker）** | ✅ 2026-08-10 实跑：建活动→Agent 规划→预热→上线→85 人抢购→规则引擎 REPRICE/REALLOCATE→取消回补→结算对账三方一致→大屏 GMV 真实统计 |
| Prometheus + Grafana | ✅ 容器实跑，Prometheus 抓取 api 指标 `health="up"`，Grafana 面板预置 |
| HITL 人工审批 | ✅ POST approve/reject 写入 `agent_trace.review_status` |
| 创建活动 + AI 规划 + Span 树 | ✅ 5 个新 API 全部 E2E 验证通过 |
| 代码推送到 Gitee | ✅ `master` 分支，latest commit `0e800df` |

### ⚠️ 待迁移（需要 Docker 环境才能做）
| 项目 | 阻塞原因 | 预期如何验证 |
|------|----------|--------------|
| 端到端集成验证 | 沙箱无 Docker 守护 | docker-compose 起 Redis/Kafka/Redisson → `integration` profile 跑全链路 |
| Phase 14 真实 A/B 评估 | 依赖集成环境 + 需要驱动规则引擎在模拟秒杀流上实跑 | 替换 `InMemoryBusinessMetricsSource` 硬编码种子（当前占位 82%→95%） |
| 前端浏览器验证 | 沙箱无浏览器 | React 页面在 Chrome 实际打开验证 |
| Grafana 面板容器验证 | 无 Docker | Prometheus + Grafana 面板实际渲染 |
| JMeter 百万抢购压测 | 无 JMeter/无 Docker | `deploy/jmeter/seckill-load-test.jmx` 实际压测 |
| CI eval 实跑 | 无 CI runner | `.github/workflows/eval.yml` 实际触发 |

### ❌ 未落地（规划超前项，明确标注不可声称已完成）
- Milvus 向量库：代码内是桩（`UnsupportedOperationException`）
- Canal binlog 订阅：未实现
- Nginx+Lua 接入层限流：配置写就，未实跑
- Sentinel 熔断：未实现
- WebSocket 实时推送大屏：未实现
- Redisson 在用户热路径：实际不持有（防超卖由 Lua + DB 条件更新承担）

### 🆕 Phase 14.1 已修复的关键缺陷（2026-08-10，均为「集成环境首次真跑」暴露）

| 问题 | 修复 |
|------|------|
| `application.yml` sql.init=always + data.sql DELETE → **MySQL 每次重启清库** | 默认/集成 profile 关 sql.init；MySQL 表结构+种子改由 `docker/mysql/init/*.sql` 首次建卷初始化 |
| compose 用 `docker` profile 但无此配置文件 → 集成 Bean 永不激活 | 全部改为 `integration` profile，api/worker 显式注入 DB 账号、Redis、Kafka 地址 |
| rule-engine / agent-core 无 Spring Boot 启动类 → worker 镜像 `java -jar` 起不来 | 新增 `RuleEngineWorkerApplication`（非 Web）+ spring-boot repackage；移除未接线的 agent-worker |
| 秒杀 `RedisScript` 两个 Bean 装配歧义（`redisStockService`/`RateLimitService`） | 加 `@Qualifier` |
| Kafka 发送失败不兜底：`send()` 只入队、Kafka `TimeoutException` 未被捕获 → 丢单/500 | `send().get(2s)` + catch Exception → 降级同步建单（已实测 Kafka 抖动时全部降级成功） |
| 取消订单只回补 DB，LIVE 期间 Redis 权威库存不回补 → 结算必漂移 | `OrderStateService.cancel` 同步回补 Redis 库存键 |
| AI 调拨只写 Redis、不写 DB/审计 → 结算对账把调拨误判为漂移、永远过不了 | `RedisStockSegmentService.confirmPending` 同步 DB `total_stock/stock` + `audit_log` 落库 + `total_stock` 键 |
| REPRICE 只写价表键、下单路径不读 → 调价是空转 | 订单新增 `price` 快照列；下单读 Redis 实时价；大屏新增真实 GMV；演示策略改为「畅销提价」（库存水位<50% 触发 REPRICE） |
| 请求零校验：负 qty 可反向加库存、DRAFT 活动可抢购、活动与 SKU 可错配 | `SeckillService` 校验 qty>0/LIVE/活动-SKU 匹配；`ActivityController` 校验创建参数；补 5 个回归测试 |
| `skuId` 语义混乱（前端传 product.id、后端按 activity_sku.id 查） | 前端改传 `activitySkuId` + 真实活动切换 |
| 404 被全局异常处理器吞成 200 | `NoResourceFoundException` 显式返回 404 |

---

## 八、后端全部 API 端点速查

### 秒杀
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/seckill` | 执行秒杀 `{activityId, skuId, userId, qty}` |
| GET | `/api/seckill/stock/{activityId}/{skuId}` | 查库存 |
| POST | `/api/seckill/admin/preheat/{skuId}` | 预热到 Redis |

### 订单
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/order?userId={id}` | 用户订单列表 |
| POST | `/api/order/{orderId}/cancel` | 取消订单（回补库存） |
| POST | `/api/order/{orderId}/pay` | 支付（状态迁转演示） |

### 活动
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/activity` | 活动列表 |
| POST | `/api/activity` | 创建活动（含自动创建商品+挂载 SKU） |
| POST | `/api/activity/{id}/arm` | DRAFT → ARMED |
| POST | `/api/activity/{id}/live` | ARMED → LIVE |
| POST | `/api/activity/{id}/settle` | LIVE → SETTLING |
| POST | `/api/activity/{id}/close` | SETTLING → CLOSED |
| GET | `/api/activity/{id}/reconcile` | 结算对账 |

### Dashboard
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/dashboard/overview` | 运营大屏快照 |
| GET | `/api/dashboard/traces?page=1&size=10` | Agent 轨迹列表 |
| GET | `/api/dashboard/traces/{id}/spans` | 某轨迹的 Span 执行树 |
| GET | `/api/dashboard/guardrails` | 护栏策略列表 |
| GET | `/api/dashboard/hitl` | 待人工审批列表 |
| POST | `/api/dashboard/hitl/{id}/approve` | 审批通过 |
| POST | `/api/dashboard/hitl/{id}/reject` | 审批拒绝 |
| GET | `/api/dashboard/skus?activityId={id}` | SKU 列表含商品信息 |

### Agent
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/agent/plan` | 触发 Agent 离线规划 `{activityId, goal}` |

### 认证
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/login` | 登录 `{username, password}` → id/role/displayName |

---

## 九、前端路由 + 角色分离

- `/` — 登录页
- `/user/seckill` — 买家秒杀页（需 role=user）
- `/user/orders` — 我的订单（需 role=user）
- `/merchant/dashboard` — 运营大屏（需 role=merchant 或 admin）
- `/merchant/activities` — 活动管理（含 AI 规划）
- `/merchant/guardrails` — 护栏策略
- `/merchant/hitl` — 人工审批
- `/merchant/traces` — Agent 轨迹

种子用户：`admin/merchant/admin123`、`user1/user/123456`、`user2/user/123456`

---

## 十、常用操作命令

```bash
# 构建（全模块 + 跑测试）
cd D:/Java/insightforge && mvn clean package

# 构建（跳过测试，仅编译）
mvn install -DskipTests

# 只跑测试
mvn test

# 启动后端（本地零依赖）
java -Djava.io.tmpdir=D:/Java/tmp -jar insightforge-api/target/insightforge-api-0.1.0-SNAPSHOT.jar --spring.profiles.active=local --server.port=8080

# 启动前端
cd D:/Java/insightforge-frontend && npm run dev

# 杀掉所有 Java 进程（Windows）
taskkill /F /IM java.exe

# 查看最新 git 提交
cd D:/Java && git log --oneline -5

# 推送到 Gitee
cd D:/Java && git push origin master

# E2E 快速验证（需后端已启动）
curl http://localhost:8080/actuator/health
curl http://localhost:8080/api/activity
curl http://localhost:8080/api/dashboard/skus?activityId=1
```

---

## 十一、文档阅读顺序（上手路径）

如果你是新接手这个项目的 Agent，按以下顺序读文档可以最快上手：

1. **本文档**（你正在读的）— 宏观概览
2. `specs/insightforge/本机实跑指南.md` — 4 档递进实战
3. `specs/insightforge/glossary.md` — 术语速查（读其他文档时对照）
4. `specs/insightforge/plan.md` — 技术方案总览
5. `specs/insightforge/tasks.md` — 所有 Phase 完成状态（看最后面就知道还差什么）
6. `specs/insightforge/tutorials/00-学习路线.md` — 如果想按教程一步步做
7. `insightforge/README.md` — 项目入口（和本文档互补）
8. `specs/insightforge/面试讲解稿.md` — 如果是为了面试准备

---

## 十二、架构图（速览）

```
┌────────────────────────────────────────────────────────────┐
│                    控制面（离线，有 LLM）                     │
│  ┌─────────────┐    ┌──────────────┐    ┌───────────────┐  │
│  │ Planner     │───▶│ 4 角色 Agent │───▶│ DSL Compiler  │  │
│  │ (分析目标)   │    │ 选品/定价/    │    │ (编译 + 校验)  │  │
│  │             │    │ 预热/风控     │    │ Fail-Close    │  │
│  └─────────────┘    └──────────────┘    └───────┬───────┘  │
│                                                  │          │
│                                          guardrail_policy   │
│                                                  │          │
├──────────────────────────────────────────────────┼──────────┤
│                    数据面（在线，零 LLM）          │          │
│  ┌───────────────────────────────────────────────▼───────┐  │
│  │              Drools 规则引擎                          │  │
│  │  sales.tick → 条件匹配 → REPRICE/REALLOCATE/ALERT    │  │
│  └───────────────────────┬───────────────────────────────┘  │
│                          │ INCRBY 写回同一 stock 键          │
│  ┌───────────────────────▼───────────────────────────────┐  │
│  │              高并发秒杀引擎                            │  │
│  │  限流(SlidingWindow) → Lua原子扣减 → Kafka异步建单    │  │
│  │  stock:a:{aid}:s:{skid}  同一份 Redis                 │  │
│  └───────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────┘
```

---

## 十三、最后提醒

1. **诚实大于好看**：这个项目很多地方写明了"代码写就、沙箱未实跑"。接手后不要悄悄把"配置写就"改成"已验证通过"——这是项目委托人的明确要求。
2. **改动前先读 MEMORY.md**：`D:/Java/.workbuddy/memory/MEMORY.md` 记录了项目的关键设计决策和硬约束，避免重复踩坑。
3. **Profile 是命脉**：所有集成缝都依赖 `@Profile("!integration")` vs `@Profile("integration")` 隔离。不要把 `integration` 的实现不小心暴露到 `local` profile。
4. **前端 Vite proxy 配置**：`vite.config.ts` 里 `/api` 反代到 `localhost:8080`。如果后端端口改了，这里也要改。
5. **JDK 路径**：原环境 JDK 21 路径是 `D:/devtools/jdk21/jdk-21.0.12+8`（注意含子目录，不是直接在 `jdk21/bin/java`）。换环境后修改 PATH 或 JAVA_HOME 即可。
6. **部署端口**：本机 8080/8082/3000 被其他应用占用，compose 已把 api 映射到宿主 `18080`、Grafana 映射到 `3001`、nginx 用 `80`。换机器后按需改回默认端口。
7. **rule-engine worker 独立成进程**：它没有自己的 application.yml，数据库账号等必须由 compose 环境变量注入（`SPRING_DATASOURCE_USERNAME/PASSWORD`），否则 MySQL 驱动默认用 root 无密码连不上。
8. **Kafka 镜像**：`bitnami/kafka:3.7` 在部分镜像源 400，已改官方 `apache/kafka:3.7.0`（KRaft，健康检查用 `/opt/kafka/bin/kafka-topics.sh`）。

---

> 编写日期：2026-08-10 ｜ 更新：2026-08-10（Phase 14.1 真实闭环 + 部署修复，未提交）
> 基于 commit `0e800df`（本文档描述基线）；Phase 14.1 改动在工作树（含交接文档本身）
> 仓库：https://gitee.com/paper-umbrella/insight-forge.git
