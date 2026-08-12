# 可复现性能基准

`http_capacity.py` 用Python标准库执行分阶并发，只测登录后的只读API。它回答“单实例在当前机器、当前数据量下，随并发提高，吞吐和尾延迟如何变化”，不能替代秒杀写路径JMeter、Redis/Kafka故障演练或云端SLA。

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
