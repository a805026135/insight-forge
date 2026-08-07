// Deal Pulse — Mock Data Layer
// All data is simulated. Replace with real API calls for production.

export type Role = 'user' | 'merchant';

export interface Activity {
  id: number;
  name: string;
  status: 'DRAFT' | 'ARMED' | 'LIVE' | 'SETTLING' | 'CLOSED';
  startAt: string;
  targetGMV: number;
  targetROI: string;
}

export interface Sku {
  id: number;
  activityId: number;
  name: string;
  image: string;
  seckillPrice: number;
  originalPrice: number;
  totalStock: number;
  stock: number;
  sold: number;
}

export interface Order {
  orderId: string;
  userId: number;
  activityId: number;
  skuId: number;
  skuName: string;
  qty: number;
  amount: number;
  status: string;
  createdAt: string;
}

export interface AgentMetrics {
  totalRuns: number;
  successRuns: number;
  correctFailureRuns: number;
  failedRuns: number;
  correctFailureRate: number;
  totalLatencyMsMax: number;
  totalCostUsd: number;
}

export interface GuardrailPolicy {
  id: number;
  activityId: number;
  version: number;
  status: string;
  ruleCount: number;
  riskLevel: string;
  createdAt: string;
  summary: string;
}

export interface HitlTask {
  id: number;
  activityId: number;
  type: string;
  severity: 'low' | 'medium' | 'high' | 'critical';
  description: string;
  agentDecision: string;
  createdAt: string;
}

export interface Trace {
  traceId: string;
  agent: string;
  status: 'SUCCESS' | 'CORRECT_FAILURE' | 'FAILED';
  durationMs: number;
  steps: number;
  createdAt: string;
}

// ─── Users ──────────────────────────────────────────────
export const users = [
  { id: 1001, name: 'Alice', role: 'user' as Role },
  { id: 1002, name: 'Bob', role: 'user' as Role },
  { id: 2001, name: 'Merchant Admin', role: 'merchant' as Role },
];

// ─── Activities ─────────────────────────────────────────
export const mockActivities: Activity[] = [
  { id: 1, name: '618 年中大促·数码专场', status: 'LIVE', startAt: '2026-06-18 00:00', targetGMV: 5000000, targetROI: '4.2' },
  { id: 2, name: '双11 全球狂欢·美妆日', status: 'ARMED', startAt: '2026-11-11 00:00', targetGMV: 12000000, targetROI: '5.8' },
  { id: 3, name: '开学季·图书文具', status: 'SETTLING', startAt: '2026-03-01 10:00', targetGMV: 800000, targetROI: '3.1' },
  { id: 4, name: '年货节·食品生鲜', status: 'CLOSED', startAt: '2026-01-20 08:00', targetGMV: 3500000, targetROI: '3.9' },
];

// ─── SKUs ───────────────────────────────────────────────
export const mockSkus: Sku[] = [
  // Activity 1 (LIVE - 数码)
  { id: 1, activityId: 1, name: 'iPhone 16 Pro 256GB', image: '📱', seckillPrice: 6999, originalPrice: 8999, totalStock: 100, stock: 97, sold: 3 },
  { id: 2, activityId: 1, name: 'AirPods Pro 2', image: '🎧', seckillPrice: 1299, originalPrice: 1899, totalStock: 200, stock: 156, sold: 44 },
  { id: 3, activityId: 1, name: 'MacBook Air M4 15寸', image: '💻', seckillPrice: 8499, originalPrice: 10999, totalStock: 50, stock: 48, sold: 2 },
  { id: 4, activityId: 1, name: 'Apple Watch Ultra 3', image: '⌚', seckillPrice: 4999, originalPrice: 6499, totalStock: 80, stock: 62, sold: 18 },
  // Activity 2 (ARMED - 美妆)
  { id: 5, activityId: 2, name: '雅诗兰黛小棕瓶 50ml', image: '🧴', seckillPrice: 399, originalPrice: 680, totalStock: 500, stock: 500, sold: 0 },
  { id: 6, activityId: 2, name: 'SK-II 神仙水 230ml', image: '✨', seckillPrice: 899, originalPrice: 1370, totalStock: 300, stock: 300, sold: 0 },
  // Activity 3 (SETTLING - 图书)
  { id: 7, activityId: 3, name: '《深入理解Java虚拟机》', image: '📖', seckillPrice: 49, originalPrice: 99, totalStock: 1000, stock: 2, sold: 998 },
  // Activity 4 (CLOSED - 食品)
  { id: 8, activityId: 4, name: '三只松鼠坚果大礼包', image: '🥜', seckillPrice: 69, originalPrice: 139, totalStock: 2000, stock: 0, sold: 2000 },
];

// ─── Orders (User 1001) ─────────────────────────────────
export const mockOrders: Order[] = [
  { orderId: 'OF-1-1-1001', userId: 1001, activityId: 1, skuId: 1, skuName: 'iPhone 16 Pro', qty: 1, amount: 6999, status: 'PAY', createdAt: '2026-06-18 00:00:01' },
  { orderId: 'OF-1-2-1001', userId: 1001, activityId: 1, skuId: 2, skuName: 'AirPods Pro 2', qty: 1, amount: 1299, status: 'DONE', createdAt: '2026-06-18 00:00:03' },
  { orderId: 'OF-3-7-1001', userId: 1001, activityId: 3, skuId: 7, skuName: '《深入理解Java虚拟机》', qty: 1, amount: 49, status: 'DONE', createdAt: '2026-03-01 10:00:05' },
  { orderId: 'OF-4-8-1001', userId: 1001, activityId: 4, skuId: 8, skuName: '三只松鼠坚果大礼包', qty: 1, amount: 69, status: 'CANCEL', createdAt: '2026-01-20 08:00:02' },
];

// ─── Agent Metrics ──────────────────────────────────────
export const mockAgentMetrics: AgentMetrics = {
  totalRuns: 1247,
  successRuns: 1103,
  correctFailureRuns: 89,
  failedRuns: 55,
  correctFailureRate: 7.1,
  totalLatencyMsMax: 1240,
  totalCostUsd: 18.47,
};

export const mockAgentTimeline = [
  { time: '08:00', runs: 23, latency: 320 },
  { time: '09:00', runs: 45, latency: 280 },
  { time: '10:00', runs: 67, latency: 410 },
  { time: '11:00', runs: 89, latency: 380 },
  { time: '12:00', runs: 54, latency: 290 },
  { time: '13:00', runs: 42, latency: 310 },
  { time: '14:00', runs: 78, latency: 450 },
  { time: '15:00', runs: 112, latency: 520 },
  { time: '16:00', runs: 98, latency: 390 },
  { time: '17:00', runs: 134, latency: 610 },
  { time: '18:00', runs: 156, latency: 720 },
  { time: '19:00', runs: 142, latency: 680 },
];

// ─── Guardrail Policies ─────────────────────────────────
export const mockPolicies: GuardrailPolicy[] = [
  { id: 1, activityId: 1, version: 3, status: 'ACTIVE', ruleCount: 5, riskLevel: '中', createdAt: '2026-06-17 22:30', summary: '定价下限≥成本*1.2，库存水位<20%触发调拨，毛利率<15%告警' },
  { id: 2, activityId: 2, version: 1, status: 'ACTIVE', ruleCount: 4, riskLevel: '低', createdAt: '2026-06-17 20:00', summary: '美妆品类限价策略，SK-II 不得低于 800¥，库存分 3 段预留' },
  { id: 3, activityId: 3, version: 2, status: 'ARCHIVED', ruleCount: 3, riskLevel: '低', createdAt: '2026-02-28 18:00', summary: '图书品类基础护栏，已归档' },
];

// ─── HITL Tasks ─────────────────────────────────────────
export const mockHitlTasks: HitlTask[] = [
  { id: 1, activityId: 1, type: 'REPRICE_APPROVAL', severity: 'high', description: 'iPhone 16 Pro 建议降价至 ¥6599（低于成本线 5%），需人工审批', agentDecision: 'AI 建议降价以提升售罄率（当前 3%，目标 80%）', createdAt: '2026-06-18 10:30' },
  { id: 2, activityId: 1, type: 'REALLOCATE_APPROVAL', severity: 'medium', description: 'AirPods Pro 2 库存告急（剩余 156/200），建议从备用仓调拨 100 件', agentDecision: 'AI 预测未来 2h 销量 80 件，建议调拨 100 件到段 2', createdAt: '2026-06-18 11:15' },
  { id: 3, activityId: 2, type: 'POLICY_REVIEW', severity: 'low', description: '双11 活动护栏策略 v1 已生成，请审核 4 条规则', agentDecision: 'AI 基于历史双11 数据生成，毛利率底线 25%', createdAt: '2026-06-17 20:05' },
  { id: 4, activityId: 1, type: 'ALERT_REVIEW', severity: 'critical', description: 'MacBook Air M4 毛利率降至 8%，触发严重告警', agentDecision: 'AI 建议立即冻结当前定价，恢复至 ¥9499', createdAt: '2026-06-18 12:00' },
];

// ─── Traces ─────────────────────────────────────────────
export const mockTraces: Trace[] = [
  { traceId: 'trace-a1b2', agent: 'PricingAgent', status: 'SUCCESS', durationMs: 340, steps: 4, createdAt: '2026-06-18 10:30' },
  { traceId: 'trace-c3d4', agent: 'InventoryAgent', status: 'SUCCESS', durationMs: 280, steps: 3, createdAt: '2026-06-18 11:15' },
  { traceId: 'trace-e5f6', agent: 'PlannerAgent', status: 'CORRECT_FAILURE', durationMs: 890, steps: 5, createdAt: '2026-06-18 11:45' },
  { traceId: 'trace-g7h8', agent: 'PricingAgent', status: 'SUCCESS', durationMs: 210, steps: 3, createdAt: '2026-06-18 12:00' },
  { traceId: 'trace-i9j0', agent: 'GuardrailAgent', status: 'FAILED', durationMs: 1200, steps: 2, createdAt: '2026-06-18 12:30' },
  { traceId: 'trace-k1l2', agent: 'InventoryAgent', status: 'SUCCESS', durationMs: 195, steps: 3, createdAt: '2026-06-18 13:00' },
  { traceId: 'trace-m3n4', agent: 'CriticAgent', status: 'CORRECT_FAILURE', durationMs: 450, steps: 4, createdAt: '2026-06-18 13:30' },
];

// ─── Revenue Chart Data ─────────────────────────────────
export const mockRevenueData = [
  { hour: '08:00', revenue: 12500, orders: 18 },
  { hour: '09:00', revenue: 28900, orders: 42 },
  { hour: '10:00', revenue: 56200, orders: 78 },
  { hour: '11:00', revenue: 78900, orders: 112 },
  { hour: '12:00', revenue: 45100, orders: 65 },
  { hour: '13:00', revenue: 38900, orders: 54 },
  { hour: '14:00', revenue: 67800, orders: 96 },
];
