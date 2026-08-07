// ============================================================
// InsightForge 前端 API 层 —— 全部对接真实后端 REST 端点
// 开发时 Vite 代理 /api → localhost:8080，生产由 Nginx 反代。
// 所有类型严格匹配后端 Java record/DTO 的 Jackson 序列化结果。
// ============================================================

const BASE = '/api';

async function get<T>(path: string): Promise<T> {
  const resp = await fetch(`${BASE}${path}`);
  const json = await resp.json();
  if (json.code !== 0) throw new Error(json.message || `GET ${path} failed: ${resp.status}`);
  return json.data;
}

async function post<T>(path: string, body?: unknown): Promise<T> {
  const resp = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  });
  const json = await resp.json();
  if (json.code !== 0) throw new Error(json.message || `POST ${path} failed: ${resp.status}`);
  return json.data;
}

// ---- 秒杀 ----
export const seckillApi = {
  place: (req: { activityId: number; skuId: number; userId: number; qty: number }) =>
    post<string>('/seckill', req),
  stock: (skuId: number) => get<StockInfo>(`/seckill/stock/${skuId}`),
  preheat: (skuId: number) => post<void>(`/seckill/admin/preheat/${skuId}`),
};

// ---- 活动状态机 ----
export const activityApi = {
  arm: (id: number) => post<ReconcileReport>(`/activity/${id}/arm`),
  live: (id: number) => post<void>(`/activity/${id}/live`),
  settle: (id: number) => post<ReconcileReport>(`/activity/${id}/settle`),
  close: (id: number) => post<void>(`/activity/${id}/close`),
  reconcile: (id: number) => get<ReconcileReport>(`/activity/${id}/reconcile`),
};

// ---- 订单 ----
export const orderApi = {
  cancel: (orderId: string) => post<void>(`/order/${orderId}/cancel`),
  pay: (orderId: string) => post<void>(`/order/${orderId}/pay`),
};

// ---- 大屏/可观测 ----
export const dashboardApi = {
  overview: () => get<DashboardSnapshot>('/dashboard/overview'),
  traces: (limit = 20) => get<TraceView[]>(`/dashboard/traces?limit=${limit}`),
  hitl: () => get<TraceView[]>('/dashboard/hitl'),
  guardrails: (activityId?: number) =>
    get<GuardrailView[]>(
      activityId ? `/dashboard/guardrails?activityId=${activityId}` : '/dashboard/guardrails',
    ),
  explain: (params: Record<string, string>) => {
    const qs = new URLSearchParams(params).toString();
    return get<string>(`/dashboard/explain?${qs}`);
  },
};

// ============================================================
// 类型定义 —— 严格匹配后端 Java record/DTO 的 Jackson 序列化
// ============================================================

/** POST /api/seckill/stock/{skuId} → ApiResult<StockInfo>.data */
export interface StockInfo {
  skuId: number;
  totalStock: number;
  stock: number;
  sold: number;
}

/** 对账明细项 —— 匹配 ReconcileItem.java */
export interface ReconcileItem {
  dimension: string;
  ok: boolean;
  detail: string;
}

/** 对账报告 —— 匹配 ReconcileReport.java */
export interface ReconcileReport {
  passed: boolean;
  activityStatus: string;
  items: ReconcileItem[];
}

/** DashboardSnapshot.SeckillSummary —— 匹配 nested record */
export interface SeckillSummary {
  totalActivities: number;
  liveActivities: number;
  totalStock: number;
  soldStock: number;
  totalOrders: number;
}

/** DashboardSnapshot.AgentSummary */
export interface AgentSummary {
  totalRuns: number;
  successRuns: number;
  correctFailureRuns: number;
  failedRuns: number;
  correctFailureRate: number; // 0~1
  totalLatencyMsMax: number;
  totalCostUsd: number;
}

/** DashboardSnapshot.GuardrailSummary */
export interface GuardrailSummary {
  policyRows: number;
  activitiesWithPolicy: number;
  topRiskLevel: string;
}

/** GET /api/dashboard/overview → ApiResult<DashboardSnapshot>.data */
export interface DashboardSnapshot {
  seckill: SeckillSummary;
  agent: AgentSummary;
  guardrail: GuardrailSummary;
  generatedAt: string;
}

/** Agent 运行轨迹 —— 匹配 TraceView.java (record) */
export interface TraceView {
  traceId: string;
  activityId: number;
  status: string;
  failureKind: string | null;
  correctFailure: boolean;
  startedAt: string;
  finishedAt: string;
  totalLatencyMs: number;
  budgetCostUsd: number;
  modelVersion: string;
  /** 运营可读结论（如「规划成功并落库策略」「主动询问运营（正确失败）」） */
  verdict: string;
}

/** 护栏策略视图 —— 匹配 GuardrailView.java (record) */
export interface GuardrailView {
  activityId: number;
  version: string;
  ruleCount: number;
  topRiskLevel: string;
  summary: string;
}
