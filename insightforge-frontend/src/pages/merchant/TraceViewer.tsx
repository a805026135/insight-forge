import { useEffect, useState } from 'react';
import { dashboardApi, TraceView } from '../../api';

export default function TraceViewer() {
  const [list, setList] = useState<TraceView[]>([]);
  const [selected, setSelected] = useState<TraceView | null>(null);
  const [err, setErr] = useState('');

  useEffect(() => {
    dashboardApi.traces(20)
      .then(setList)
      .catch((e) => setErr(e.message));
  }, []);

  return (
    <div className="page">
      <h2>Agent 调用轨迹</h2>
      {err && <div className="error">{err}</div>}
      <div className="trace-layout">
        <div className="trace-list">
          {list.length === 0 && !err && <p>暂无调用记录</p>}
          {list.map((t) => (
            <div
              key={t.traceId}
              className={`trace-item ${selected?.traceId === t.traceId ? 'selected' : ''}`}
              onClick={() => setSelected(t)}
            >
              <span className={`dot dot-${t.status?.toLowerCase()}`} />
              <span className="trace-id">{t.traceId?.slice(0, 10)}...</span>
              <span className="trace-meta">活动{t.activityId} · {t.totalLatencyMs}ms</span>
            </div>
          ))}
        </div>
        <div className="trace-detail">
          {selected ? (
            <>
              <h4>Trace: {selected.traceId}</h4>
              <div className="detail-grid">
                <div>状态: <span className={`status-${selected.status?.toLowerCase()}`}>{selected.status}</span></div>
                <div>活动ID: {selected.activityId}</div>
                <div>耗时: {selected.totalLatencyMs}ms</div>
                <div>Token: {selected.budgetTokens}</div>
                <div>模型: {selected.modelVersion}</div>
                <div>时间: {selected.startedAt}</div>
              </div>
              <h5 style={{ marginTop: 16 }}>执行 Spans</h5>
              {selected.spans?.map((s) => (
                <div key={s.spanId} className="span-item">
                  <span className="span-role">{s.role}</span>
                  <span className="span-step">{s.stepName || s.toolName || '-'}</span>
                  <span className="span-latency">{s.latencyMs}ms</span>
                  <span className={`span-status span-status-${s.status?.toLowerCase()}`}>{s.status}</span>
                </div>
              ))}
            </>
          ) : (
            <p className="hint">点击左侧 Trace 查看详情</p>
          )}
        </div>
      </div>
    </div>
  );
}
