import { useEffect, useRef, useState } from 'react';
import { dashboardApi, WS_URL } from '../api/client';
import type { DashboardSnapshot } from '../types';

/**
 * 大屏快照 hook：优先用 WebSocket 实时接收（后端连上即推），
 * WS 不可用时退化为每 3 秒轮询 /api/dashboard/overview——保证「实时刷新」在任何环境都能演示。
 */
export function useDashboardSnapshot(pollMs = 3000) {
  const [snapshot, setSnapshot] = useState<DashboardSnapshot | null>(null);
  const [live, setLive] = useState(false); // 是否走 WS 实时通道
  const wsRef = useRef<WebSocket | null>(null);

  useEffect(() => {
    let cancelled = false;
    let timer: number | undefined;

    const startWs = () => {
      try {
        const ws = new WebSocket(WS_URL);
        wsRef.current = ws;
        ws.onmessage = (e) => {
          try {
            setSnapshot(JSON.parse(e.data) as DashboardSnapshot);
            setLive(true);
          } catch {
            /* 忽略坏帧 */
          }
        };
        ws.onclose = () => setLive(false);
        ws.onerror = () => {
          ws.close();
        };
      } catch {
        /* WS 不可用，走轮询 */
      }
    };

    const startPoll = () => {
      const tick = () =>
        dashboardApi
          .overview()
          .then((s) => !cancelled && setSnapshot(s))
          .catch(() => undefined);
      tick();
      timer = window.setInterval(tick, pollMs);
    };

    startWs();
    // WS 3 秒没推上来就启动轮询兜底
    const fallback = window.setTimeout(() => {
      if (!snapshot && !cancelled) startPoll();
    }, 3000);

    return () => {
      cancelled = true;
      if (timer) window.clearInterval(timer);
      window.clearTimeout(fallback);
      wsRef.current?.close();
    };
  }, [pollMs, snapshot]);

  return { snapshot, live };
}
