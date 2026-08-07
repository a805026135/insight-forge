// ============================================================
// InsightForge 前端 API 层 —— 全部对接真实后端 REST 端点
// 开发时 Vite 代理 /api → localhost:8080，生产由 Nginx 反代。
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
  /** 用户抢购 */
  place: (req: { activityId: number; skuId: number; userId: number; qty: number }) =>
    post<string>('/seckill', req),
  /** 查库存 */
  stock: (skuId: number) => get<StockInfo>(`/seckill/stock/${skuId}`),
  /** 库存预热（管理员） */
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

// ---- 类型 ----
export interface StockInfo {
  stock: number;
  totalStock: number;
  sold: number;
}

export interface ReconcileReport {
  passed: boolean;
  expectedStock: number;
  actualStock: number;
  expectedSold: number;
  actualSold: number;
  message: string;
}

export interface DashboardSnapshot {
  totalActivities: number;
  totalOrders: number;
  totalRevenue: number;
  activeActivities: number;
  sellThroughRate: number;
  agentTotalRuns: number;
  agentSuccessRate: number;
  agentAvgLatencyMs: number;
  guardrailsActive: number;
  guardrailsArchived: number;
}

export interface TraceView {
  traceId: string;
  activityId: number;
  status: string;
  startedAt: string;
  totalLatencyMs: number;
  modelVersion: string;
  budgetTokens: number;
  spans: SpanView[];
}

export interface SpanView {
  spanId: string;
  role: string;
  stepName: string;
  status: string;
  latencyMs: number;
  confidence: number;
  toolName: string;
}

export interface GuardrailView {
  id: number;
  activityId: number;
  version: string;
  policyJson: string;
  createdAt: string;
  riskLevel: string;
}
