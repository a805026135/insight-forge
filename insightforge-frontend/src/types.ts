// 与后端 com.insightforge.api.observability.dto 一一对应（Phase 10）。
// 前端只消费这些「已外化」的结构，绝不触碰规则引擎内部类型——控制面/数据面分离在前端也成立。

export interface SeckillSummary {
  totalActivities: number;
  liveActivities: number;
  totalStock: number;
  soldStock: number;
  totalOrders: number;
}

export interface AgentSummary {
  totalRuns: number;
  successRuns: number;
  correctFailureRuns: number;
  failedRuns: number;
  correctFailureRate: number; // 0~1
  totalLatencyMsMax: number;
  totalCostUsd: number;
}

export interface GuardrailSummary {
  policyRows: number;
  activitiesWithPolicy: number;
  topRiskLevel: string;
}

export interface DashboardSnapshot {
  seckill: SeckillSummary;
  agent: AgentSummary;
  guardrail: GuardrailSummary;
  generatedAt: string;
}

export interface TraceView {
  traceId: string;
  activityId: number | null;
  status: string;
  failureKind: string | null;
  correctFailure: boolean | null;
  startedAt: string | null;
  finishedAt: string | null;
  totalLatencyMs: number | null;
  budgetCostUsd: number | null;
  modelVersion: string | null;
  verdict: string;
}

export interface GuardrailView {
  activityId: number;
  version: string;
  ruleCount: number;
  topRiskLevel: string;
  summary: string;
}

// 后端统一返回体 ApiResult<T>
export interface ApiResult<T> {
  code: number;
  message: string;
  data: T;
}
