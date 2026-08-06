import type { ApiResult, DashboardSnapshot, GuardrailView, TraceView } from '../types';

// 后端基础路径：开发时由 Vite 代理 /api → localhost:8080；生产由 Nginx 反代。
const BASE = '/api';

async function get<T>(path: string): Promise<T> {
  const resp = await fetch(`${BASE}${path}`);
  if (!resp.ok) {
    throw new Error(`GET ${path} failed: ${resp.status}`);
  }
  const wrap = (await resp.json()) as ApiResult<T>;
  if (wrap.code !== 0) {
    throw new Error(`API error: ${wrap.message}`);
  }
  return wrap.data;
}

export const dashboardApi = {
  overview: () => get<DashboardSnapshot>('/dashboard/overview'),
  traces: (limit = 20) => get<TraceView[]>(`/dashboard/traces?limit=${limit}`),
  hitl: () => get<TraceView[]>('/dashboard/hitl'),
  guardrails: (activityId?: number) =>
    get<GuardrailView[]>(
      activityId ? `/dashboard/guardrails?activityId=${activityId}` : '/dashboard/guardrails',
    ),
  // 控盘指令可解释性：把决策事实交给后端模板翻成人话（非 LLM）
  explain: (params: Record<string, string>) => {
    const qs = new URLSearchParams(params).toString();
    return get<string>(`/dashboard/explain?${qs}`);
  },
};

// 大屏实时推送 WebSocket 地址（开发走 Vite 代理 /ws）
export const WS_URL = `${location.protocol === 'https:' ? 'wss' : 'ws'}://${
  location.host
}/ws/dashboard`;
