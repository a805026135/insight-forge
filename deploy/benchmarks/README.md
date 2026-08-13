# 可复现性能基准

`http_capacity.py` 用Python标准库执行分阶并发，只测登录后的只读API。它回答“单实例在当前机器、当前数据量下，随并发提高，吞吐和尾延迟如何变化”，不能替代秒杀写路径JMeter、Redis/Kafka故障演练或云端SLA。

`redis_lua_fault.py` 使用 Python 标准库直连一个隔离 Redis，对真实 Lua 扣减做并发无超卖测试，并在容器重启后验证 AOF 数据仍在。它是组件热路径基准，不包含 HTTP、JWT、Kafka 和 MySQL，不得称为整站 QPS。

```powershell
docker run -d --name if-eval-redis -p 127.0.0.1:16379:6379 redis:7-alpine redis-server --appendonly yes
python deploy/benchmarks/redis_lua_fault.py --port 16379 --requests 300 --stock 100 --workers 100 --output redis-lua.json
docker restart if-eval-redis
python deploy/benchmarks/redis_lua_fault.py --port 16379 --verify-restart-only --output redis-recovery.json
docker rm -f if-eval-redis
```

```powershell
python deploy/benchmarks/http_capacity.py `
  --base-url http://127.0.0.1:8080 `
  --concurrency 1,10,25,50 `
  --requests-per-step 500 `
  --output capacity-result.json
```

正式环境不要把密码写进仓库；通过命令行或CI Secret传入。压测应针对隔离环境，先预热，再逐级加压，并同时采集CPU、内存、GC、数据库连接池、Redis、Kafka lag及压测后的业务对账。

秒杀写路径使用 `deploy/jmeter/seckill-load-test.jmx`，遵循JMeter官方建议以CLI运行并生成HTML报告：

```text
jmeter -n -t deploy/jmeter/seckill-load-test.jmx -l result.jtl -e -o report
```
