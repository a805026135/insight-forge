# InsightForge QPS 与容量设计（面试可追问版）

## 1. 先给结论，不虚报数字

当前有证据的数字是：隔离的 Redis Lua 热路径在本地 300 请求、100 工作线程、100 库存下为 **3114.82 RPS，P95 35.33ms**，且恰好 100 次成功、无超卖。它不含 HTTP、JWT、活动校验、Kafka 和 MySQL，**不能回答整站 QPS**。

此前云端商品查询容量结果错误率为 16.67%～43.33%，也不能作为可用 QPS。Phase 21 已完成 QPS 结构优化和正确的端到端压测脚本，但在同一套云端数据上完成“同步 ACK 基线 / 异步 Reservation 优化”A/B 前，简历与面试不得填写新的整站 QPS 数字。

面试时可以直接说：

> 我把容量拆成秒杀入口接受 QPS 与数据库最终落单 TPS。组件基线里 Redis Lua 做到 3114.82 RPS，但我不会把它说成整站 QPS。原链路每单同步等待 Kafka ACK，且 3 个分区只有 1 个消费线程；我将可靠性边界前移到可恢复 Reservation 后改成非阻塞批量投递，并将消费并行度提升到 3。最终数字需要在云服务器做同数据 A/B，观察 P95、错误率、Kafka lag、DB TPS 和库存对账后再回答。

## 2. 为什么不能只说一个 QPS

- **Offered QPS**：压测器每秒发出的请求数。
- **Admission QPS**：API 每秒完成校验并返回的请求数，包括成功、重复、售罄和限流。
- **Accepted QPS**：每秒真正创建新 Reservation 的订单数，不能把重复请求或售罄快速失败算成“下单成功”。
- **Settlement TPS**：消费者每秒最终写入 MySQL 的新订单数。
- **Recovery TPS / drain time**：Kafka 故障后补偿器每秒收敛多少预留、多久清空 backlog。

入口可以依靠 Redis 与 Kafka 削峰而短时大于数据库 TPS，差值会形成 Kafka backlog。系统是否健康不取决于入口数字最大，而取决于队列是否能在承诺时间内排空、错误率是否达标、库存与订单是否最终对平。

Little's Law 给出近似关系 `并发在途数 L = QPS λ × 平均延迟 W`。同步等待 20ms ACK 时，一个串行发送槽理论上最多约 50 次/秒；并发线程虽可掩盖等待，但会占用更多请求线程和内存。非阻塞投递将请求线程的等待从 broker 往返缩短为本地序列化与 producer 入队。

## 3. Phase 21 做了什么

### 3.1 可靠性前移：Reservation-first

Redis Lua 在扣库存时原子完成四件事：幂等判断、库存预扣、完整订单恢复载荷、pending ZSET 超时索引。它成为“请求已经被接受”的可恢复证据。Kafka 即使异步 ACK 失败，定时补偿也能按 ZSET 有界扫描并幂等落单。

因此生产者不再执行 `send().get(2s)`：

1. 请求线程把消息放入 Kafka producer 本地缓冲后立即返回；
2. ACK 失败由异步回调记录；
3. 补偿器依据 Reservation 重放，不依赖日志人工救单；
4. 同步序列化或本地入队异常仍立即走数据库短事务兜底。

这不是普通 fire-and-forget。普通异步发送在“Redis 已扣、Kafka 丢失”窗口可能永久丢单；本项目因为先持久化可恢复业务载荷，才具备安全异步的前提。

### 3.2 producer 批处理

`linger.ms=5`、`batch.size=64KiB`、LZ4 压缩让多个小订单事件合并成更少的网络请求。`acks=all` 与幂等 producer 保留，没有用牺牲可靠性换数字。5ms 是吞吐与尾延迟的折中，正式环境应通过 A/B 决定，而不是越大越好。

### 3.3 消费端并行

`order.created` 已有 3 个分区，旧配置却只有 1 个监听线程，另外两个分区不能并行落单。现在默认消费者并发为 3，并可通过 `INSIGHTFORGE_ORDER_CONSUMER_CONCURRENCY` 配置。

消费者并发不是越大越好：同一消费组可用并行度上限是分区数；继续加线程只会空闲。即使增加分区，也会受 MySQL 连接池、索引维护、redo log 和行锁约束。扩容应以 Kafka lag 下降且 DB P95、锁等待仍健康为准。

## 4. 云端 A/B 测试方法

使用 `deploy/benchmarks/seckill_capacity.py`。每个请求必须使用不同用户 JWT，因为服务端以登录身份覆盖 body 中的 userId；复用一个 token 测到的是幂等重复命中，不是新订单吞吐。token 文件不得提交 Git。

基线版本保留同步 ACK，优化版本使用异步 Reservation；两组必须满足：同一台云服务器、同一压测机、同 JVM 参数、同库存与用户数据、相同预热、相同并发阶梯。每轮先预热，再至少稳定运行 3～5 分钟，重复 3 次取中位数。

示例：

```bash
python3 deploy/benchmarks/seckill_capacity.py \
  --base-url http://127.0.0.1:8080 \
  --tokens-file /tmp/insightforge-jwts.txt \
  --activity-id 1 --sku-id 1 --concurrency 100 \
  --requests 5000 --variant async-reservation \
  --output /tmp/seckill-async-c100.json
```

压测前必须将库存设置到不小于请求数并预热 Redis，否则大部分请求会快速售罄，得到的是“拒绝 QPS”。正式记录以下数据：

- offered / admission / accepted QPS，业务拒绝数与基础设施错误数；
- P50、P95、P99，错误率；
- API CPU、GC、Tomcat 活跃线程与队列；
- Redis command latency、CPU、连接数；
- Kafka producer batch-size、request-latency、consumer lag；
- MySQL 新订单 TPS、连接池使用率、慢查询、锁等待；
- 压测结束后订单数、Redis 库存、DB 库存、重复订单数和 pending backlog。

发布门建议：基础设施错误率小于 0.1%，P95 达到业务目标，无超卖、无重复业务副作用，停止流量后 backlog 能在约定时间内归零。若这些条件没同时满足，峰值 QPS 再高也不算“可承载”。

## 5. 面试追问

**为什么不直接把 Tomcat 线程调大？** 线程只能掩盖阻塞，不能消除 Kafka RTT、DB 写入和锁竞争；过大会增加上下文切换和堆内存。先去掉同步等待、削峰并找到实际饱和资源，再按 CPU 核数和下游容量调整。

**异步返回后用户马上查订单，查不到怎么办？** 返回的是已接受的 Reservation 订单号，订单状态查询应能表达“处理中”；消费者或补偿完成后变为已创建。不能把暂时不可见误报为失败并让用户重复支付。

**Kafka 挂了为什么还敢返回成功？** 因为成功语义是“库存已原子预留且恢复载荷已持久化”，不是“数据库订单已写完”。Kafka 负责低延迟搬运，ZSET 补偿负责最终收敛。若 Redis Reservation 都没有成功，则不能返回接受成功。

**怎么继续扩展？** 单热点先做 Redis Cluster hash-tag 与热点分片评估；入口多实例无状态扩容；Kafka 增加分区并同步增加消费者实例；数据库按业务键分库分表或批量落单。每一步都要重新验证顺序、幂等、补偿和对账，不能只看吞吐。
