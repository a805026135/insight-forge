# InsightForge（智析）—— 智能秒杀 / 大促平台

> Java + AI Agent 面试项目：**高并发强一致秒杀 × 控制面/数据面分离的 Agent 护栏**。

## 一句话说清楚

秒杀像「厕所就 100 个坑位，1 万人同时冲」——必须保证不超卖。
AI 像「聪明的店长」，离线算好今天哪些货该降价、哪些该从别的仓调货，把策略写成规则——但**店长不能冲在门口收门票**（太慢会堵死），所以门口检票机按规则自动执行。

**LLM 永不进入秒杀热路径。** 延迟/幻觉/非确定性与强一致性天生冲突。

---

## 项目状态

| 维度 | 状态 |
|------|------|
| 后端全链路 | ✅ Phase 0–13 全部落地，**96 测试全绿**，`mvn clean package` BUILD SUCCESS |
| 沙箱验证 | ✅ JDK 21 + Maven 3.9.9 实跑通过（Windows 沙箱，classworlds 直拉） |
| 集成环境 | ⚠️ 需 Docker（Redis/Kafka/Redisson），键契约已对齐、契约级单测已证明链路接通，端到端实跑待迁移到有 Docker 的机器 |
| 前端大屏/Grafana/JMeter/CI | ⚠️ 代码写就，沙箱未浏览器/容器验证，已诚实标注，不虚报 |

---

## 快速开始

### 前提

- JDK 21（`D:/devtools/jdk21/jdk-21.0.12+8`）
- Maven 3.9+（`D:/devtools/maven/apache-maven-3.9.9`）

### 构建（零依赖，开箱即跑）

```bash
cd insightforge
JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=D:/Java/tmp" \
java -classpath "D:/devtools/maven/apache-maven-3.9.9/boot/plexus-classworlds-2.8.0.jar" \
  -Dclassworlds.conf=D:/devtools/maven/apache-maven-3.9.9/bin/m2.conf \
  -Dmaven.home=D:/devtools/maven/apache-maven-3.9.9 \
  -Dmaven.multiModuleProjectDirectory=D:/Java/insightforge \
  org.codehaus.plexus.classworlds.launcher.Launcher clean package
```

> 如果你用的是标准 `mvn` 命令，直接 `mvn clean package` 即可。上面的 classworlds 方式是沙箱 workaround。

### 启动（`local` profile，零依赖）

```bash
cd insightforge-api
java -jar target/insightforge-api-*.jar --spring.profiles.active=local
```

访问 `http://localhost:8080/actuator/health` → `{"status":"UP"}`。

### 集成环境（需要 Docker）

```bash
docker-compose up -d    # 起 MySQL/Redis/Kafka
cd insightforge-api
java -jar target/insightforge-api-*.jar --spring.profiles.active=integration
```

---

## 模块结构

```
insightforge/
├── insightforge-api/             # REST + WebSocket 接入层（35 测试）
├── insightforge-seckill-core/    # 高并发引擎：Lua/Redisson/Kafka/幂等/状态机（2 测试）
├── insightforge-rule-engine/     # 护栏规则引擎：Drools 在线执行/调拨/调价（29 测试）
├── insightforge-agent-core/      # 自研 Agent 运行时：工具/记忆/反思/评估（26 测试）
├── insightforge-persistence/     # 实体 + MyBatis-Plus 仓储
├── insightforge-common/          # 公共工具/配置/异常
└── pom.xml                       # 父 POM（Spring Boot 3.3 + Drools 9 + MyBatis-Plus）
```

---

## 核心亮点（面试可讲的故事线）

### 1. 高并发防超卖（分层纵深）

| 层 | 手段 | 大白话 |
|----|------|--------|
| 入口限流 | Redis+Lua 滑动窗口 | 门口限流，超了就 429 |
| 快速扣减 | Redis+Lua 原子 DECRBY | 门口快速发号（单 key 不可打断） |
| DB 兜底 | `UPDATE ... SET stock=stock-? WHERE stock>=?` 受影响行数 | 仓库真库存，最终真相 |
| 异步削峰 | Kafka `order.created` | 发完号先去旁边等着，别堵门口 |
| 幂等去重 | Redis SETNX + DB UNIQUE 双保险 | 同一个号不能发两次 |
| 状态机+对账 | 活动/订单双状态机 + SUM(qty) 交叉核对 Redis/DB | 打烊点货，差一分钱都不行 |

### 2. 控制面/数据面分离（Agent 护栏）

- **离线（控制面）**：LLM 分析历史数据 → 生成护栏 DSL（定价区间/调拨量/告警阈值）
- **在线（数据面）**：Drools 确定性执行 DSL，**零 LLM 进热路径**
- **Fail-Close**：DSL 编译不过（REPRICE < 成本 / REALLOCATE < 0）→ 拒绝发布，绝不带病上线
- **同一份库存**：Agent 调拨用 `INCRBY` 写回秒杀同一个 `stock:a:{aid}:s:{skid}`，用户可抢到 AI 调过来的库存

### 3. 自研 Agent 运行时

- 工具注册（JSON Schema 校验）+ ReAct 循环 + 三层记忆
- Critic 反思重试（maxRetry=3）+ BudgetGate 预算上限
- 多智能体编排（Planner → 选品/定价/预热/风控 → Critic 审核 → 产出 DSL）

---

## 文档导航

| 你想…… | 看这个 |
|---------|--------|
| 从零搭建，手把手学 | [`specs/insightforge/tutorials/00-学习路线.md`](https://gitee.com/paper-umbrella/insight-forge/blob/master/specs/insightforge/tutorials/00-%E5%AD%A6%E4%B9%A0%E8%B7%AF%E7%BA%BF.md) |
| 面试前突击 Q&A | [`specs/insightforge/面试讲解稿.md`](https://gitee.com/paper-umbrella/insight-forge/blob/master/specs/insightforge/%E9%9D%A2%E8%AF%95%E8%AE%B2%E8%A7%A3%E7%A8%BF.md) |
| 技术方案/架构决策 | [`specs/insightforge/plan.md`](https://gitee.com/paper-umbrella/insight-forge/blob/master/specs/insightforge/plan.md) |
| 详细设计规格 | [`specs/insightforge/spec.md`](https://gitee.com/paper-umbrella/insight-forge/blob/master/specs/insightforge/spec.md) |
| 任务清单（含完成状态） | [`specs/insightforge/tasks.md`](https://gitee.com/paper-umbrella/insight-forge/blob/master/specs/insightforge/tasks.md) |
| 术语速查 | [`specs/insightforge/glossary.md`](https://gitee.com/paper-umbrella/insight-forge/blob/master/specs/insightforge/glossary.md) |
| 本机怎么跑 | [`specs/insightforge/本机实跑指南.md`](https://gitee.com/paper-umbrella/insight-forge/blob/master/specs/insightforge/%E6%9C%AC%E6%9C%BA%E5%AE%9E%E8%B7%91%E6%8C%87%E5%8D%97.md) |
| 项目体检报告 | [`specs/insightforge/全项目体检报告.md`](https://gitee.com/paper-umbrella/insight-forge/blob/master/specs/insightforge/%E5%85%A8%E9%A1%B9%E7%9B%AE%E4%BD%93%E6%A3%80%E6%8A%A5%E5%91%8A.md) |

---

## 技术栈

| 类别 | 选型 |
|------|------|
| 语言/框架 | Java 21 + Spring Boot 3.3.5 + Maven 多模块 |
| 持久层 | MyBatis-Plus + MySQL 8 |
| 缓存/锁/限流 | Redis 7 + Redisson + Caffeine(L1) |
| 消息队列 | Kafka（削峰 + 销量事件流） |
| 规则引擎 | Drools 9.44.0.Final（确定性执行，零 LLM） |
| Agent 接入 | Spring AI 1.0.0-M6 + DeepSeek（条件启用，默认离线桩） |
| 向量库 | Milvus（桩，待迁移） |
| 前端 | React 18 + TypeScript + Vite + Ant Design + ECharts |
| 测试 | JUnit 5 + AssertJ + Embedded Redis |
| 部署 | Docker Compose + GitHub Actions（配置写就） |

---

## 诚实边界

- ✅ 后端全链路 96 测试实跑通过（`mvn clean package`）
- ⚠️ 集成环境端到端验证需 Docker（Redis/Kafka/Redisson），沙箱无 Docker 守护，待迁移
- ⚠️ 前端大屏 / Grafana / JMeter / CI 配置文件写就，沙箱未实跑，已逐处标注
- ⚠️ A/B 评估指标为硬编码种子（占位演示），真实驱动规则引擎实跑待 Phase 14
- ⚠️ Milvus 向量库 / Canal / Nginx+Lua / Sentinel 为规划超前项，已显式标注未落地
