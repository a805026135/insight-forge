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

async function put<T>(path: string, body?: unknown): Promise<T> {
  const resp = await fetch(`${BASE}${path}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: body ? JSON.stringify(body) : undefined,
  });
  const json = await resp.json();
  if (json.code !== 0) throw new Error(json.message || `PUT ${path} failed: ${resp.status}`);
  return json.data;
}

async function del<T>(path: string): Promise<T> {
  const resp = await fetch(`${BASE}${path}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  const json = await resp.json();
  if (json.code !== 0) throw new Error(json.message || `DELETE ${path} failed: ${resp.status}`);
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

// ---- Phase 17: 用户中心 / 商品目录 / 商家工作台 ----
export const userApi = {
  addresses: () => get<UserAddress[]>('/user/addresses'),
  createAddress: (req: UserAddressInput) => post<UserAddress>('/user/addresses', req),
  updateAddress: (id: number, req: Partial<UserAddressInput>) => put<UserAddress>(`/user/addresses/${id}`, req),
  deleteAddress: (id: number) => del<void>(`/user/addresses/${id}`),
  setDefaultAddress: (id: number) => post<UserAddress>(`/user/addresses/${id}/default`),
  cart: () => get<CartItemView[]>('/user/cart'),
  addCart: (activitySkuId: number, qty: number) => post<void>('/user/cart', { activitySkuId, qty }),
  updateCartQty: (id: number, qty: number) => put<void>(`/user/cart/${id}`, { qty }),
  removeCart: (id: number) => del<void>(`/user/cart/${id}`),
  checkout: (addressId: number, cartItemIds: number[]) =>
    post<string>('/user/checkout', { addressId, cartItemIds }),
  orders: () => get<OrderDetailView[]>('/user/orders'),
  pay: (orderId: string) => post<void>(`/user/orders/${orderId}/pay`),
  cancel: (orderId: string) => post<void>(`/user/orders/${orderId}/cancel`),
  confirm: (orderId: string) => post<void>(`/user/orders/${orderId}/confirm`),
};

export const catalogApi = {
  products: (params: { category?: string; keyword?: string; merchantId?: number; limit?: number }) => {
    const qs = new URLSearchParams();
    if (params.category) qs.set('category', params.category);
    if (params.keyword) qs.set('keyword', params.keyword);
    if (params.merchantId) qs.set('merchantId', String(params.merchantId));
    if (params.limit) qs.set('limit', String(params.limit));
    const s = qs.toString();
    return get<ProductDetailView[]>(`/catalog/products${s ? '?' + s : ''}`);
  },
  detail: (id: number) => get<ProductDetailView>(`/catalog/products/${id}`),
  categories: () => get<string[]>('/catalog/categories'),
  merchants: () => get<MerchantView[]>('/catalog/merchants'),
};

export const merchantApi = {
  profile: () => get<MerchantView>('/merchant/profile'),
  overview: () => get<Record<string, number | string>>('/merchant/overview'),
  products: () => get<MerchantProductView[]>('/merchant/products'),
  createProduct: (req: MerchantProductInput) => post<MerchantProductView>('/merchant/products', req),
  updateProduct: (id: number, req: Partial<MerchantProductInput>) =>
    put<MerchantProductView>(`/merchant/products/${id}`, req),
  toggleProduct: (id: number) => post<MerchantProductView>(`/merchant/products/${id}/toggle`),
  deleteProduct: (id: number) => del<void>(`/merchant/products/${id}`),
  assistant: (question: string) => post<AssistantReply>('/merchant/assistant', { question }),
};

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

// ============================================================
// Phase 17 新增类型
// ============================================================

export interface UserAddress {
  id: number;
  userId: number;
  receiver: string;
  phone: string;
  province: string;
  city: string;
  district: string;
  detail: string;
  isDefault: boolean;
  createdAt: string;
}

export interface UserAddressInput {
  receiver: string;
  phone: string;
  province: string;
  city: string;
  district: string;
  detail: string;
  isDefault?: boolean;
}

export interface CartItemView {
  id: number;
  activitySkuId: number;
  productId: number;
  productName: string;
  productImage: string;
  merchantId: number | null;
  merchantName: string;
  price: number | null;
  marketPrice: number | null;
  qty: number;
  stock: number | null;
  sold: number | null;
  status: string;
}

export interface OrderItemView {
  activitySkuId: number;
  productId: number;
  productName: string;
  productImage: string;
  merchantId: number | null;
  merchantName: string;
  qty: number;
  price: number;
}

export interface OrderDetailView {
  orderId: string;
  status: string;
  totalAmount: number | null;
  receiver: string | null;
  phone: string | null;
  addressSnapshot: string | null;
  createdAt: string;
  shippedAt: string | null;
  items: OrderItemView[];
}

export interface ProductDetailView {
  id: number;
  activitySkuId: number | null;
  name: string;
  sku: string;
  marketPrice: number | null;
  seckillPrice: number | null;
  imageUrl: string;
  category: string;
  description: string;
  status: string;
  merchantId: number | null;
  merchantName: string;
  merchantRating: number | null;
  merchantSalesCount: number | null;
  merchantVerified: boolean | null;
  stock: number | null;
  sold: number | null;
}

export interface MerchantView {
  id: number;
  userId: number;
  name: string;
  logo: string;
  description: string;
  rating: number;
  salesCount: number;
  verified: boolean;
  createdAt: string;
}

export interface MerchantProductView {
  id: number;
  name: string;
  sku: string;
  costPrice: number | null;
  marketPrice: number | null;
  seckillPrice: number | null;
  imageUrl: string;
  category: string;
  description: string;
  status: string;
  merchantId: number;
  merchantName: string;
  stock: number | null;
  sold: number | null;
}

export interface MerchantProductInput {
  name: string;
  sku: string;
  costPrice: number;
  marketPrice: number;
  imageUrl?: string;
  category: string;
  description?: string;
}

export interface AssistantReply {
  answer: string;
  suggestionType: string | null;
  suggestionDetail: string | null;
}
