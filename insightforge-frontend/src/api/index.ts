// ============================================================
// InsightForge 前端 API 层 —— 全部对接真实后端 REST 端点
// 开发时 Vite 代理 /api → localhost:8080，生产由 Nginx 反代。
// 所有类型严格匹配后端 Java record/DTO 的 Jackson 序列化结果。
// ============================================================

import { getToken } from '../auth/token';

const BASE = '/api';

function authHeaders(): Record<string, string> {
  const token = getToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

async function get<T>(path: string): Promise<T> {
  const resp = await fetch(`${BASE}${path}`, { headers: authHeaders() });
  const json = await resp.json();
  if (json.code !== 0) throw new Error(json.message || `GET ${path} failed: ${resp.status}`);
  return json.data;
}

async function post<T>(path: string, body?: unknown): Promise<T> {
  const resp = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
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
  list: () => get<ActivityView[]>('/activity'),
  create: (req: CreateActivityRequest) => post<ActivityView>('/activity', req),
  arm: (id: number) => post<ReconcileReport>(`/activity/${id}/arm`),
  live: (id: number) => post<void>(`/activity/${id}/live`),
  settle: (id: number) => post<ReconcileReport>(`/activity/${id}/settle`),
  close: (id: number) => post<void>(`/activity/${id}/close`),
  reconcile: (id: number) => get<ReconcileReport>(`/activity/${id}/reconcile`),
};

// ---- 订单 ----
export const orderApi = {
  list: (userId: number) => get<OrderView[]>(`/order?userId=${userId}`),
  cancel: (orderId: string) => post<void>(`/order/${orderId}/cancel`),
  pay: (orderId: string) => post<void>(`/order/${orderId}/pay`),
};

// ---- 大屏/可观测 ----
export const dashboardApi = {
  overview: () => get<DashboardSnapshot>('/dashboard/overview'),
  traces: (limit = 20) => get<TraceView[]>(`/dashboard/traces?limit=${limit}`),
  spans: (traceId: string) => get<SpanView[]>(`/dashboard/traces/${traceId}/spans`),
  hitl: () => get<TraceView[]>('/dashboard/hitl'),
  approve: (traceId: string) => post<void>(`/dashboard/hitl/${traceId}/approve`),
  reject: (traceId: string) => post<void>(`/dashboard/hitl/${traceId}/reject`),
  guardrails: (activityId?: number) =>
    get<GuardrailView[]>(
      activityId ? `/dashboard/guardrails?activityId=${activityId}` : '/dashboard/guardrails',
    ),
  skus: (activityId: number) => get<SkuView[]>(`/dashboard/skus?activityId=${activityId}`),
  explain: (params: Record<string, string>) => {
    const qs = new URLSearchParams(params).toString();
    return get<string>(`/dashboard/explain?${qs}`);
  },
};

// ---- Agent 控制面 ----
export const agentApi = {
  plan: (req: { activityId: number; goal: string }) =>
    post<AgentPlanResponse>('/agent/plan', req),
};

// ---- 注册 ----
export const authApi = {
  register: (req: { username: string; password: string; displayName?: string }) =>
    post<{ id: number; username: string; role: string; displayName: string; token: string }>(
      '/register',
      req,
    ),
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

/** 活动视图 —— 匹配 Activity.java (Jackson 序列化) */
export interface ActivityView {
  id: number;
  name: string;
  status: string;
  targetGmv: number;
  targetRoi: number;
  startAt: string;
  endAt: string;
}

/** DashboardSnapshot.SeckillSummary —— 匹配 nested record */
export interface SeckillSummary {
  totalActivities: number;
  liveActivities: number;
  totalStock: number;
  soldStock: number;
  totalOrders: number;
  /** 真实 GMV（成交价×数量，status≠CANCEL） */
  totalGmv: number;
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
  /** HITL 审批结果：null=未复核 / APPROVED=已通过 / REJECTED=已驳回 */
  reviewStatus: string | null;
}

/** 护栏策略视图 —— 匹配 GuardrailView.java (record) */
export interface GuardrailView {
  activityId: number;
  version: string;
  ruleCount: number;
  topRiskLevel: string;
  summary: string;
}

/** 订单视图 —— 匹配 Orders.java (Jackson 序列化) */
export interface OrderView {
  orderId: string;
  userId: number;
  activityId: number;
  skuId: number;
  qty: number;
  /** 成交单价快照（REPRICE 生效后新订单带新价） */
  price: number | null;
  status: string;
  createdAt: string;
  productName: string;
  productImage: string;
  activityName: string;
}

/** Span 视图 —— 匹配 SpanView.java (record) */
export interface SpanView {
  spanId: string;
  traceId: string;
  parentSpanId: string | null;
  role: string;
  stepName: string;
  toolName: string | null;
  toolInput: string | null;
  toolOutput: string | null;
  tokenIn: number;
  tokenOut: number;
  latencyMs: number;
  retryCount: number;
  status: string;
  confidence: number | null;
  artifactRef: string | null;
}

/** SKU 视图 —— 匹配 SkuView.java (record) */
export interface SkuView {
  skuId: number;
  activitySkuId: number;
  activityId: number;
  productName: string;
  skuCode: string;
  productImage: string;
  seckillPrice: number;
  marketPrice: number | null;
  costPrice: number | null;
  totalStock: number;
  stock: number;
  sold: number;
}

/** Agent 规划响应 —— 匹配 AgentPlanResponse.java (record) */
export interface AgentPlanResponse {
  traceId: string;
  status: string;
  failureKind: string | null;
  correctFailure: boolean;
  publishedVersion: string | null;
  totalLatencyMs: number;
  verdict: string;
}

/** 创建活动请求 —— 匹配 CreateActivityRequest.java (record) */
export interface CreateActivityRequest {
  name: string;
  targetGmv: number;
  targetRoi: number;
  startAt: string;
  endAt: string;
  skus: {
    productId: number | null;
    productName: string;
    skuCode: string;
    costPrice: number;
    seckillPrice: number;
    totalStock: number;
  }[];
}
