-- ============================================================
-- Phase 19d：终止原因显式化 + Agent 运行 checkpoint（暂停/继续）
--  - agent_trace 增加 termination_reason：每次 run 都以明确原因收尾
--  - agent_checkpoint：暂停时保存运行快照（任务/阶段/进度），支持 /resume 继续
-- ============================================================

ALTER TABLE agent_trace ADD COLUMN termination_reason VARCHAR(32);

CREATE TABLE agent_checkpoint (
    run_id       VARCHAR(64) PRIMARY KEY,
    activity_id  BIGINT,
    phase        VARCHAR(32),
    step_index   INT,
    task_json    TEXT,
    context_json TEXT,
    status       VARCHAR(32),
    created_at   DATETIME,
    updated_at   DATETIME
);
