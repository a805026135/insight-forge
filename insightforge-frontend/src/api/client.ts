import type { ApiResult, DashboardSnapshot, GuardrailView, TraceView } from '../types';
import { getToken } from '../auth/token';

// 后端基础路径：开发时由 Vite 代理 /api → localhost:8080；生产由 Nginx 反代。
const BASE = '/api';

async function get<T>(path: string): Promise<T> {
  const token = getToken();
  const resp = await fetch(`${BASE}${path}`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
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

// 大屏实时推送 WebSocket 地址（开发走 Vite 代理 /ws）。
// 登录后才有 token，因此必须在连接时现算（Phase 14.4：握手需携带 JWT）。
export function getWsUrl(): string {
  const token = getToken();
  return `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws/dashboard${
    token ? `?token=${encodeURIComponent(token)}` : ''
  }`;
}
