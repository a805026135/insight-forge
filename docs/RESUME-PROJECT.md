# InsightForge 简历项目成稿

本文内容已经按中文技术简历格式压缩，可以直接复制。推荐优先使用“一页简历推荐版”，再根据岗位替换为 Agent 或 Java 后端版本。

项目仓库：[https://github.com/a805026135/insight-forge](https://github.com/a805026135/insight-forge)

在线演示：[http://47.99.63.190/](http://47.99.63.190/)

国内镜像：[https://gitee.com/paper-umbrella/insight-forge](https://gitee.com/paper-umbrella/insight-forge)

## 一、一页简历推荐版

### InsightForge——Agent 驱动的智能电商与高并发秒杀平台

个人项目｜核心开发者｜2026.07—2026.08

技术栈：Java 21、Spring Boot 3.3、Spring AI、MyBatis-Plus、MySQL、Redis/Redisson、Kafka、Drools、Milvus、DeepSeek、Langfuse、React、TypeScript、Docker Compose、Nginx、Prometheus、Grafana

项目描述：面向买家客服、商家运营及限时秒杀场景，设计并实现完整电商业务闭环；将 LLM 置于离线控制面，通过商家隔离 RAG、受限策略 DSL、Critic 护栏和人工审批生成可审计策略，在线交易路径由确定性规则和状态机执行。

项目成果：

- 实现风险与复杂度自适应 Agent 图，根据经营目标动态选择选品、定价、库存和风控角色；简单调价只执行定价+风控，跨域、模糊或安全回放任务自动升级完整图，路由原因和风险写入 Trace；聚焦任务角色调用数由 4 降至 2，同时保留独立审查、人工确认与版本化 DSL。
- 构建 MySQL 主数据与 Milvus 向量索引分离的多租户 RAG，使用意图路由、安全门和 Hybrid RRF 改进检索；同云环境 40 条受控集消融中，Dense 基线 Recall@4/事实正确率为 74.29%/65.71%，当前方案提升至 100%/100%，拒答判定由 0% 提升至 100%，检索 P95 为 611ms。
- 建设 50 条真实模型 Agent 评测，基于 DeepSeek usage 与 Langfuse/MySQL 同 traceId 记录任务成功、安全拦截、P95、token 和成本；Single/Multi-Agent 均通过 30 条正向与 20 条安全样本，Single P95 6.887s、平均 475.22 token，Multi P95 55.008s、平均 4237.02 token，据此将简单任务默认路由至 Single-Agent。
- 实现退款客服“真实订单查询—退款草稿—用户确认—售后状态机—商家审核/申诉”闭环，结构化订单、金额和归属由数据库校验，RAG 仅解释政策；通过后端幂等和状态条件拦截重复提交与越权，限制 Agent 对资金类动作的直接执行权限。
- 针对“Redis 扣减成功但消息/落库失败”设计可恢复库存预留协议：Lua 原子完成幂等、扣库存、恢复载荷和 ZSET 超时登记，Kafka 仅负责削峰，消费者与补偿器复用同一 MySQL 短事务写模型；以确定性 orderId、唯一索引和库存条件更新实现可重放、无重复副作用的最终一致性。
- 使用 Docker Compose 在阿里云部署 API、规则 Worker、MySQL、Redis、Kafka、Milvus、Nginx 和监控组件；通过 Flyway 管理 Schema，Prometheus/Grafana 观察系统指标，Langfuse 追踪模型 token/成本，并设计 Redis/Kafka 故障恢复和服务降级路径。

如果简历空间有限，保留前五条并删除第六条；如果只能保留四条，优先保留 Agent 闭环、RAG 消融、Agent A/B、秒杀幂等。

## 二、Agent 应用工程师定向版

### InsightForge——可控、可评测的电商 Agent 应用平台

个人项目｜Agent 应用工程/Java 后端｜2026.07—2026.08

技术栈：Java 21、Spring Boot、Spring AI、DeepSeek、Milvus、MySQL、Redis、Kafka、Drools、Langfuse、Prometheus、React、Docker

项目描述：针对普通聊天机器人缺乏真实业务闭环、工具调用风险高和效果不可量化的问题，构建覆盖知识检索、策略生成、人工审批、规则执行、退款客服和 Agent 评测的电商智能体平台。

项目成果：

- 将固定四 Agent 升级为风险与复杂度自适应角色图：路由器按目标选择最小必要角色，简单调价仅调用定价/风控，跨域和模糊目标 Fail-Safe 升级选品/定价/库存/风控完整图；决策证据写入 Trace，并继续经过程序护栏、独立审查和人工确认。
- 实现多租户 RAG 数据链路，以 MySQL 保存文档主数据和不可变版本、Milvus 保存可重建向量索引，通过 merchantId 元数据过滤、意图路由、安全门和 Hybrid RRF 减少跨租户召回、无证据回答与精确实体漏召回。
- 建立 40 条 RAG 评测集并完成同环境 A/B/C/D 消融，量化 Recall@4、事实正确率、拒答判定和 P95；当前方案在受控集达到 100%/100%/100%，相对普通 Dense 基线分别提升 25.71、34.29、100 个百分点。
- 建立 50 条 Agent 任务集，基于最终业务状态区分正常任务成功与危险任务正确失败；真实调用 `deepseek-v4-flash` 对比 Single/Multi-Agent，发现质量持平但 Multi 平均 token 约为 8.9 倍、P95 约为 8 倍，形成“简单任务 Single、复杂规划 Multi”的路由依据。
- 实现 Prompt/知识库不可变版本、SHA-256、Checkpoint、失败回放、步骤/token/成本预算及 Trace/Span；将模型 usage 同时写入 MySQL 与 Langfuse，支持按 traceId 核对模型、版本、延迟和费用。
- 完成退款客服可控工具调用：Agent 仅查询 JWT 用户真实订单并生成草稿，用户确认后才进入事务接口和售后状态机，重复申请、越权订单和不允许状态由程序校验拒绝，避免用 Prompt 充当权限边界。

### Agent 岗自我介绍中的项目段落

我最有代表性的项目是 InsightForge。最初商家规划固定执行四个 Agent，但云端对照发现简单任务没有质量收益，反而增加 token 和等待时间。因此我实现了风险与复杂度自适应路由：简单调价只调用定价和风控，跨域或模糊任务自动升级完整角色图；路由原因、实际角色和成本都写入 Trace。模型最终只生成受限 DSL，经程序校验、独立审查和人工确认后才由规则引擎执行。

## 三、Java 后端工程师定向版

### InsightForge——智能电商与高并发秒杀系统

个人项目｜Java 后端核心开发｜2026.07—2026.08

技术栈：Java 21、Spring Boot 3.3、MyBatis-Plus、MySQL、Redis/Redisson、Kafka、Drools、Flyway、Docker Compose、Nginx、Prometheus、Grafana

项目描述：实现商品、购物车、订单、支付、物流、评价、售后和商家运营功能，并围绕热点库存、重复请求、异步消息和跨资源一致性设计秒杀及智能运营链路。

项目成果：

- 按 API、持久化、秒杀核心、Agent 核心和规则引擎划分 Maven 模块，并将 API 与规则 Worker 分进程部署；使用 Flyway 版本化数据库结构，通过 profile 区分 H2 零依赖开发环境与 MySQL/Redis/Kafka 集成环境。
- 设计可恢复库存预留协议：一段 Lua 原子完成重复判断、库存扣减、恢复载荷和 ZSET 超时索引，消除 SETNX 与扣减分离的宕机窗口；补偿器按截止时间分批重放，避免 `KEYS` 全量扫描阻塞 Redis。
- 将 Kafka 定位为削峰通道而非唯一可靠性来源，消费者、发送失败兜底与补偿器复用同一事务写模型；确定性 orderId + MySQL UNIQUE 保证重放幂等，`stock >= qty` 更新失败时回滚订单并保留预留凭证等待对账。
- 设计买家、商家双端订单及售后状态机，实现退款申请、审核、驳回、申诉和退款流转；所有资源查询绑定认证用户或商家身份，前端按钮状态仅改善体验，后端事务和归属校验作为安全边界。
- 编写数据库并发和真实 Redis Lua 回归：300 请求竞争 100 库存时成功数 100、剩余库存 0、无超卖；Redis 热路径 P95 35.33ms、吞吐 3114.82 RPS。该结果仅代表隔离组件基线，不作为整站 QPS。
- 在阿里云以 Docker Compose 部署 MySQL、Redis、Kafka、Milvus、API、Worker、Nginx 及监控栈；使用 Actuator、Prometheus/Grafana 和 traceId 观察健康、延迟、错误与消息链路，并针对 2C4G 环境设置 JVM/容器资源边界。

### Java 岗自我介绍中的项目段落

我做过一个 Java 21 和 Spring Boot 的智能电商秒杀项目。后端最核心的是把性能与正确性分层：Redis Lua 做热点库存的原子预扣和快速拒绝，Kafka 把落单移出请求峰值，数据库唯一键和库存条件更新负责最终正确；重复投递和取消通过确定性订单号、状态条件、补偿和对账处理。同时项目不是只有秒杀接口，还实现了支付、物流、评价、售后申诉和 Agent 调价的完整状态闭环，并部署到阿里云真实中间件环境。

## 四、招聘平台精简版

适用于 BOSS 直聘、拉勾等项目描述字数较少的场景：

InsightForge 是基于 Java 21/Spring Boot 的智能电商与秒杀平台。实现风险与复杂度自适应 Agent 图，按目标动态选择选品、定价、库存和风控角色，模糊/高风险任务自动升级完整协作；结合商家隔离 RAG、受限策略 DSL、独立审查和人工确认，实现可控调价与退款闭环。使用 Redis Lua、Kafka 和 MySQL 约束实现秒杀防超卖及幂等。

注意：这里的“降至”来自同模型、同评测集的架构方案选择，不应说成线上全流量优化。

## 五、技能清单建议

### 投递 Agent 应用工程师

Java、Spring Boot、Spring AI、LLM Agent、RAG、Milvus、Prompt Engineering、Tool Calling、HITL、Guardrail、Agent Evaluation、Langfuse、MySQL、Redis、Kafka、Docker、React/TypeScript

### 投递 Java 后端工程师

Java、Spring Boot、Spring MVC、MyBatis-Plus、MySQL、Redis、Redisson、Kafka、分布式幂等、缓存一致性、并发编程、JVM、Flyway、Docker Compose、Nginx、Prometheus/Grafana

技能栏只保留自己能解释原理、指出项目落点并回答故障场景的技术。不要为了 ATS 搜索堆入从未使用过的 Kubernetes、GraphRAG、Seata 或 Elasticsearch。

## 六、面试时每个数字如何解释

### RAG 的 100%

正确说法：同一云环境、同一 40 条受控回归集上，当前 Hybrid RRF 方案三项判定为 100%；相对 Dense 基线有明显提升。

必须补充：数据集参与过优化，不是隐藏集，不能外推线上准确率；自然语言生成质量还需要人工或校准后的 Judge。

### Agent 的 50/50

正确说法：30 条正常任务达到预期终态，20 条危险表达全部触发同一类低于成本价护栏，没有发布副作用。

必须补充：这是 20 种表达，不是 20 类风险；跨租户、负调拨、绕过 HITL、超预算和 Prompt Injection 仍需单独扩集。

### Redis 的 3114.82 RPS

正确说法：它是 Redis 协议加 Lua 的隔离热路径结果，300 请求、100 工作线程、100 库存，P95 35.33ms。

必须补充：不包含 HTTP、JWT、Kafka、MySQL 和公网网络，绝不能写成整站吞吐或生产 SLA。

### 云端容量

不能放进简历成绩。公网只读阶梯测试仍有 16.67%～43.33% 错误率，说明当时公网/Nginx/上游链路存在瓶颈。它可以在面试中作为“保留失败证据并制定分层定位计划”的例子，但不能包装成高可用成果。

## 七、最容易被追问的五句话

### “为什么 LLM 不直接调价？”

因为模型输出具有概率性，调价影响真实成交和毛利。模型只负责生成受限 DSL，程序护栏校验成本、权限与版本，高风险动作进入 HITL，线上由规则引擎确定性执行并保留审计。

### “为什么 RAG 还要 MySQL？”

MySQL 保存文档原文、版本和审计，是可恢复真相源；Milvus 只是可重建的向量索引。订单、金额和权限等结构化事实直接查询 MySQL，不交给向量检索。

### “为什么 Redis 和数据库都扣库存？”

Redis 负责在热点前快速拒绝和削减数据库压力，数据库负责最终业务事实和正确性。Redis 可能丢数据或出现跨资源窗口，所以必须保留唯一键、条件更新和对账。

### “为什么用了 Kafka 仍会重复？”

消费者可能在数据库提交后、offset 提交前崩溃，消息就会重投。Kafka 的幂等生产者不能覆盖任意数据库副作用，因此消费者必须用稳定业务键和数据库约束实现幂等。

### “Multi-Agent 为什么反而不用？”

保留多 Agent 是为了复杂任务的角色边界、独立审查和可替换性，但固定执行全部角色会增加延迟和 token。因此产品采用风险与复杂度自适应角色图：简单任务只调用最小必要角色，复杂跨域或高风险任务自动升级完整协作图。

## 八、最终投递检查

- 根据 JD 只选择 Agent 版或 Java 版，不同时粘贴两套。
- 一页简历最多保留 4～5 条项目成果，每条控制在两行左右。
- 仓库中不能出现 API Key、密码、个人隐私或测试账号密码。
- 投递前验证 GitHub 主仓库与在线演示可访问、README 首页清晰、项目地址正确；Gitee 仅作为国内同步镜像。
- 准备现场画出总架构、退款状态机和秒杀幂等时序。
- 能说明每个百分比、延迟和吞吐的样本、环境、分母及局限。
- 不声称百万 QPS、严格 exactly-once、GraphRAG、生产 100% 准确率或最新云端全量测试全部通过；本地 154 条通过不能替代云端中间件复测。

更完整的追问答案见 [项目面试题与回答](INTERVIEW-QA.md) 和 [后端面试答辩手册](BACKEND-INTERVIEW-DEFENSE.md)。
