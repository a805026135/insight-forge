-- ============================================================
-- InsightForge 建表脚本
-- ============================================================

CREATE TABLE IF NOT EXISTS users (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    username     VARCHAR(64) UNIQUE,
    password     VARCHAR(128),
    role         VARCHAR(16),              -- "user" or "merchant"
    display_name VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS product (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(128),
    sku         VARCHAR(128),
    cost_price  DECIMAL(12, 2)
);

CREATE TABLE IF NOT EXISTS activity (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(128),
    status      VARCHAR(32),
    target_gmv  DECIMAL(14, 2),
    target_roi  DECIMAL(8, 4),
    start_at    DATETIME,
    end_at      DATETIME
);

CREATE TABLE IF NOT EXISTS activity_sku (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    activity_id  BIGINT,
    sku_id       BIGINT,
    seckill_price DECIMAL(12, 2),
    total_stock  INT,
    stock        INT,
    sold         INT,
    version      INT
);

CREATE TABLE IF NOT EXISTS orders (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    -- Phase 4 幂等锚点：order_id 由 (activityId,skuId,userId) 确定性推导（见 SeckillService.buildOrderId），
    -- 唯一约束保证「同一用户同一活动同一 SKU」无论请求几次、Kafka 重投几次，都只落一张单（exactly-once）。
    -- 用标准 inline UNIQUE（H2 默认模式与 MySQL 8 都支持，无需 MODE=MySQL）。
    order_id     VARCHAR(64) UNIQUE,
    user_id      BIGINT,
    activity_id  BIGINT,
    sku_id       BIGINT,
    qty          INT,
    status       VARCHAR(32),
    created_at   DATETIME
);

-- ============================================================
-- Agent 控制面监测（Phase 7）：Trace + Span 持久化
-- 源真相存 MySQL（离线可跑、零外部依赖）；status/kind 用 VARCHAR 兼容 H2 与 MySQL。
-- 详见 agent-observability-evaluation.md §2。
-- ============================================================
CREATE TABLE IF NOT EXISTS agent_trace (
    trace_id         VARCHAR(64)  PRIMARY KEY,   -- = Orchestrator run id；在线规则引擎运行也透传此 id 归因
    activity_id      BIGINT,
    started_at       DATETIME,
    finished_at      DATETIME,
    status           VARCHAR(16),                -- SUCCESS / CORRECT_FAILURE / FAILED
    failure_kind     VARCHAR(16),                -- ASK / REFUSE / ESCALATE / null
    model_version    VARCHAR(64),
    prompt_version   VARCHAR(64),
    budget_tokens    INT,
    budget_cost_usd  DECIMAL(10,4),
    total_latency_ms BIGINT,
    correct_failure  BOOLEAN,
    review_status    VARCHAR(32)                   -- null=未复核 / APPROVED=已通过 / REJECTED=已驳回（HITL 审批结果）
);

CREATE TABLE IF NOT EXISTS agent_span (
    span_id        VARCHAR(64) PRIMARY KEY,
    trace_id       VARCHAR(64) NOT NULL,         -- FK -> agent_trace
    parent_span_id VARCHAR(64),
    role           VARCHAR(32),                  -- Planner / Data / Viz / Writer / Critic / Orchestrator / Tool
    step_name      VARCHAR(64),
    tool_name      VARCHAR(64),
    tool_input     VARCHAR(4000),                -- 入参 JSON（截断存储）
    tool_output    VARCHAR(4000),                -- 出参 JSON（截断存储）
    token_in       INT,
    token_out      INT,
    latency_ms     BIGINT,
    retry_count    INT,                          -- 反思重试次数（≤3）
    status         VARCHAR(16),                  -- OK / RETRY / FAIL
    confidence     DECIMAL(3,2),
    artifact_ref   VARCHAR(128)
);

-- ============================================================
-- Agent 控制面：护栏策略版本化（Phase 8 产出物的真相源）
-- 每次发布一条新版本（activity_id + version 唯一）；在线规则引擎（Phase 9）读最新版编译执行。
-- policy_json 存完整 DSL（VARCHAR(8000) 兼容 H2 与 MySQL）。
-- ============================================================
CREATE TABLE IF NOT EXISTS guardrail_policy (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    activity_id  BIGINT,
    version      VARCHAR(32),
    policy_json  VARCHAR(8000),
    created_at   DATETIME,
    UNIQUE (activity_id, version)
);
