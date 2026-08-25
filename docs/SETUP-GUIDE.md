# InsightForge 零基础搭建与验收

这份文档的目标不是“把命令复制完”，而是让你知道每一步启动了什么、成功的证据是什么、失败后该查哪里。建议第一次严格按顺序执行。

## 一、先选择运行模式

| 模式 | 适合场景 | 依赖 | 能验证什么 | 不能证明什么 |
| --- | --- | --- | --- | --- |
| A：本地最小模式 | 第一次学习、日常改页面 | JDK、Maven、Node | 商城页面、H2 数据、主要 API、本地 Agent 兜底 | Redis/Kafka/Milvus/真实模型链路 |
| B：本地生产拓扑 | 集成验证、面试演示 | Docker Desktop，建议 8GB+ 可用内存 | MySQL、Redis、Kafka、Milvus、Nginx、监控 | 公网、TLS、云防火墙 |
| C：云服务器 | 部署作品 | Linux、Docker、域名可选 | 公网完整体验和运维能力 | 不能只凭页面判断并发正确性 |

第一次先完成 A。A 验收通过后，再进入 B 或 C。

## 二、安装工具

### 1. Git

安装 Git 2.40+，Windows 可使用 Git for Windows。验证：

```powershell
git --version
```

能输出版本号即成功。Git 用于下载代码、查看变化和保存你的学习成果。

### 2. JDK 21

项目使用 Java 21，不能用 Java 8/11/17 代替。安装 Temurin、Oracle JDK 或其他兼容发行版，然后验证：

```powershell
java -version
javac -version
```

两条命令都应显示 21。如果版本不一致，检查 `JAVA_HOME` 和 `Path`，并重新打开终端。

### 3. Maven 3.9+

```powershell
mvn -version
```

重点看 Maven 版本和它实际使用的 Java，Java home 必须指向 JDK 21。

### 4. Node.js 20+

```powershell
node --version
npm --version
```

推荐 Node 20 或 22 LTS。若 PowerShell 阻止 `npm.ps1`，可以在系统执行策略允许后重开终端，或临时使用 `npm.cmd`。

### 5. Docker Desktop（模式 B/C 才需要）

```powershell
docker version
docker compose version
```

两条命令都要成功，且 `docker version` 必须同时显示 Client 和 Server。只有 Client 通常表示 Docker Desktop 没启动。

## 三、获取代码并认识 Git

```powershell
git clone https://github.com/a805026135/insight-forge.git
cd insight-forge
git status
git branch --show-current
```

如果 GitHub 网络不稳定，可改用同步镜像：

```powershell
git clone https://gitee.com/paper-umbrella/insight-forge.git
```

第一次应位于 `master`，且工作区干净。学习或修改功能时建立自己的分支：

```powershell
git switch -c learn/setup-notes
```

日常只需掌握这个安全闭环：

```powershell
git status                 # 看哪些文件变化了
git diff                   # 阅读尚未暂存的修改
git add docs/文件名.md      # 只暂存确认过的文件
git diff --cached          # 再检查一次将提交的内容
git commit -m "docs: 补充学习笔记"
git push -u origin learn/setup-notes
```

不要执行不理解的 `git reset --hard`，也不要提交 `deploy/.env`、`PROJECT-HANDOVER.md`、日志、`target`、`node_modules` 或 `dist`。

## 四、模式 A：启动零依赖后端

### 1. 编译和测试

```powershell
cd insightforge
mvn clean package
```

第一次会从 Maven Central 下载依赖，耗时取决于网络。成功标志是最后出现 `BUILD SUCCESS`，且测试没有 failures/errors。`target/` 下会生成各模块产物。

如果只想快速验证代码编译，可执行 `mvn -DskipTests package`，但它不能作为提交或面试演示前的质量证明。

### 2. 启动 API

```powershell
java -jar insightforge-api/target/insightforge-api-0.1.0-SNAPSHOT.jar `
  --server.port=8090 `
  --spring.profiles.active=local
```

这里的两个参数分别表示：

- `8090`：与前端 Vite 当前代理端口一致。
- `local`：使用 H2 内存数据库，并关闭 Redis、Kafka、Redisson 自动配置。

不要关闭这个终端。看到 `Started InsightForgeApplication` 后，另开终端验证：

```powershell
Invoke-RestMethod http://localhost:8090/actuator/health
Invoke-RestMethod http://localhost:8090/api/ping
```

健康检查应为 `UP`。H2 是内存库，停止后数据会清空，下次启动由 Flyway 重新建表并写入种子数据，这是预期行为。

## 五、启动前端并完成第一次业务验收

另开一个终端，从仓库根目录执行：

```powershell
cd insightforge-frontend
npm install
npm run dev
```

Vite 默认监听 `http://localhost:5173`，并把 `/api` 与 `/ws` 代理到 `http://localhost:8090`。

按以下顺序人工验收：

1. 打开首页，确认商品卡片、价格和中文文案正常。
2. 用买家 `user1 / 123456` 登录，打开商品详情、购物车和订单页。
3. 在客服页选择真实订单，生成退款草稿；确认后应创建真实售后单，而不是只返回聊天文字。
4. 打开限时秒杀。同活动同 SKU 已购买后，按钮应禁用或提示已购买，这是业务限购规则。
5. 退出后用商家 `admin / admin123` 登录，查看商品、活动、售后处理、Agent 工作台和 Agent 评测页。
6. 浏览器开发者工具 Console 不应有持续报错，Network 中业务请求不应出现未解释的 4xx/5xx。

注意：最小模式不会证明真实 Redis/Kafka/Milvus 正常，只用于快速学习和页面联调。

## 六、理解数据库初始化

数据库变更由 `insightforge/insightforge-api/src/main/resources/db/migration/` 下的 Flyway 脚本管理。启动新数据库时按版本执行 V1、V2……V21。

规则：

- 已提交的迁移不要改内容；新增变化创建下一个版本。
- 不要手工改生产表后假装迁移已存在。
- 本地 H2 和集成 MySQL 共享主迁移，因此 SQL 要注意兼容性。
- 遇到迁移失败先读异常中的版本和 SQL，不要直接删生产库。

## 七、模式 B：Docker 完整拓扑

### 1. 配置环境变量

在仓库根目录：

```powershell
Copy-Item deploy/.env.example deploy/.env
```

打开 `deploy/.env`，必须修改 JWT 密钥与 Grafana 管理员密码。两者都被生产编排声明为必填，并且不要使用同一个值。JWT 密钥可用 PowerShell 生成：

```powershell
$bytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
[Convert]::ToHexString($bytes).ToLower()
```

把输出填到 `INSIGHTFORGE_AUTH_JWT_SECRET=` 后，并为 `GF_SECURITY_ADMIN_PASSWORD=` 设置独立强密码。模型密钥可留空，此时相关能力应降级，而不是阻止交易系统启动。`.env` 已被 Git 忽略，仍应在提交前用 `git status` 再确认。

### 2. 先构建前端

Compose 中 Nginx 挂载本地 `insightforge-frontend/dist`：

```powershell
cd insightforge-frontend
npm install
npm run build
cd ..
```

### 3. 启动所有容器

```powershell
docker compose --env-file deploy/.env -f deploy/docker-compose.prod.yml up -d --build
docker compose -f deploy/docker-compose.prod.yml ps
```

主要服务包括 MySQL、Redis、Kafka、API、规则 Worker、Nginx、etcd、MinIO、Milvus、Prometheus 和 Grafana。首次拉镜像及构建可能需要数分钟。

### 4. 验收

```powershell
Invoke-RestMethod http://localhost/actuator/health
docker compose -f deploy/docker-compose.prod.yml logs --tail 100 api
docker compose -f deploy/docker-compose.prod.yml logs --tail 100 rule-engine-worker
```

然后访问：

- 应用：`http://localhost`
- Grafana：`http://localhost:3001`（只绑定本机回环）

验收不应只看容器 `running`。至少完成一次买家下单/退款和一次商家处理，并检查 API、Worker 日志无循环异常。

### 5. 停止与数据保留

```powershell
docker compose -f deploy/docker-compose.prod.yml stop
```

`stop` 保留容器和 volume。`down` 会删除容器但默认保留命名 volume；`down -v` 会删除数据库等持久数据，除非你明确要重置本地环境，否则不要使用。

## 八、真实 Agent、Embedding 与 RAG

`deploy/.env` 中的关键项：

```dotenv
INSIGHTFORGE_AGENT_DEEPSEEK_ENABLED=true
INSIGHTFORGE_AGENT_DEEPSEEK_API_KEY=你的模型密钥
INSIGHTFORGE_AGENT_DEEPSEEK_MODEL=deepseek-v4-flash
INSIGHTFORGE_AGENT_EMBEDDING_API_KEY=你的Embedding密钥
INSIGHTFORGE_AGENT_VECTORSTORE_TYPE=milvus
```

学习时要分清三层：

1. 结构化业务数据：订单、退款金额和状态来自 MySQL，不能由 RAG 猜。
2. 非结构化知识：政策、商品说明、商家 FAQ 经切块、向量化后检索。
3. 模型生成：基于检索上下文组织回答或策略，之后仍受工具权限、Critic 和 HITL 约束。

商家知识必须带 merchantId 隔离；秘密、跨商家问题和缺乏证据的问题应拒答或转人工。

## 九、执行 40 条 RAG 评测

数据集位于：

```text
insightforge/insightforge-agent-core/src/main/resources/evaluation/rag-eval-dataset.json
```

登录商家端后可在 Agent 评测页触发，也可带商家 JWT 调用：

```text
POST /api/dashboard/rag-evaluation/run
```

报告包含 Recall@4、答案正确率、拒答准确率和 P95。不要只保存一个百分比；同时记录数据集版本、Prompt 版本、知识库版本、Embedding/向量库、时间和失败样本。修改检索路由、阈值或切块后，重跑并对比失败项。

详细口径与当前云端基线见 [RAG-EVALUATION-REPORT.md](RAG-EVALUATION-REPORT.md)。

## 十、云服务器部署

推荐 Ubuntu 22.04/24.04、Docker Engine + Compose plugin，2C4G 仅适合演示且内存较紧，4C8G 更稳妥。

通用步骤：

```bash
git clone https://github.com/a805026135/insight-forge.git
cd insight-forge
cp deploy/.env.example deploy/.env
# 编辑 deploy/.env：填强 JWT 密钥、Grafana 密码和需要的模型密钥
cd insightforge-frontend && npm ci && npm run build && cd ..
docker compose --env-file deploy/.env -f deploy/docker-compose.prod.yml up -d --build
docker compose -f deploy/docker-compose.prod.yml ps
curl -f http://127.0.0.1/actuator/health
```

部署前还要完成：

- 云安全组只开放实际需要的 80/443/SSH；MySQL、Redis、Kafka、Milvus、Prometheus 不对公网开放。
- 给域名配置 DNS 和 TLS；若在中国大陆提供网站服务，按云厂商与监管要求完成备案。
- 备份 `mysql-data`，记录恢复演练；不要把 `.env` 打进镜像或上传 Git。
- 更新前记录当前 commit 和镜像，先备份，再构建，健康检查失败就回滚旧版本。
- 上线后验收登录、商品、下单、支付、取消、退款、商家处理、Agent 评测和日志。

## 十一、常见故障

### `java -version` 不是 21

原因通常是旧 Java 排在 Path 前面。修改 `JAVA_HOME` 后重开终端，再用 `mvn -version` 确认 Maven 也使用 Java 21。

### 前端请求 502 / `ECONNREFUSED 8090`

Vite 代理固定指向 8090。确认后端以 `--server.port=8090` 启动，且健康检查可访问。若你改端口，也要同步修改 `insightforge-frontend/vite.config.ts`。

### 8080 或 8090 被占用

```powershell
Get-NetTCPConnection -LocalPort 8090 -ErrorAction SilentlyContinue
```

找到占用进程后决定关闭它，或成对修改后端端口和 Vite 代理。不要只改一边。

### `npm.ps1 cannot be loaded`

先尝试 `npm.cmd install` / `npm.cmd run dev`。长期方案是按组织安全策略正确配置 PowerShell 执行策略，不要从网页复制未知脚本绕过。

### Maven 把 `-Dmaven.repo.local=...` 当成插件名

PowerShell 中带路径的 Maven `-D` 参数建议整体加双引号，例如：

```powershell
mvn "-Dmaven.repo.local=C:\dev\m2repo" clean test
```

普通开发机不需要自定义本地仓库，直接使用 `mvn clean test` 即可。

### Docker 容器一直 `unhealthy`

```powershell
docker compose -f deploy/docker-compose.prod.yml ps
docker compose -f deploy/docker-compose.prod.yml logs --tail 200 服务名
docker stats --no-stream
```

优先看第一个异常，而不是后续连锁报错。2C4G 主机若 OOM，可先停止 Grafana/Prometheus用于诊断，但正式环境应提升资源或做容量规划。

### Flyway 校验失败

通常是已执行迁移被修改。恢复原迁移，另建新版本修复。不要在不了解后果时执行 repair 或删除 schema history。

### Agent 能聊天但没有真正退款/调价

聊天文本不是事务成功证据。检查是否生成草稿、是否经过用户/HITL 确认、真实工具接口是否返回成功、数据库状态机是否变化，以及 trace 中是否记录工具调用。

### RAG 返回跨商家内容

这是严重隔离问题。检查索引 metadata、检索过滤条件和缓存 key 是否都包含 merchantId，并新增对应越权评测样本；不能只在 Prompt 里要求模型“不要泄露”。

## 十二、提交前最终验收

```powershell
cd insightforge
mvn clean test

cd ../insightforge-frontend
npm run typecheck
npm run build

cd ..
git status
git diff --check
```

你应能独立回答：服务如何启动、请求经过哪些模块、数据存在哪里、Agent 为什么不进热路径、退款为何需要确认、秒杀幂等由哪几层保证、评测数字如何复现、部署失败如何回滚。如果这些问题还答不出，回到[项目学习路线](LEARNING-PATH.md)完成对应练习。
