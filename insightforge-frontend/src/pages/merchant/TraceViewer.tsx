import { useEffect, useState } from 'react';
import { dashboardApi, TraceView, SpanView } from '../../api';

export default function TraceViewer() {
  const [list, setList] = useState<TraceView[]>([]);
  const [selected, setSelected] = useState<TraceView | null>(null);
  const [spans, setSpans] = useState<SpanView[]>([]);
  const [err, setErr] = useState('');
  const [loadingSpans, setLoadingSpans] = useState(false);

  useEffect(() => {
    dashboardApi.traces(20)
      .then(setList)
      .catch((e) => setErr(e.message));
  }, []);

  const statusLabel = (t: TraceView) => {
    if (t.status === 'SUCCESS') return '成功';
    if (t.status === 'CORRECT_FAILURE') return '正确失败';
    return '失败';
  };

  const handleSelect = async (t: TraceView) => {
    setSelected(t);
    setSpans([]);
    setLoadingSpans(true);
    try {
      const spanList = await dashboardApi.spans(t.traceId);
      setSpans(spanList);
    } catch {
      setSpans([]);
    } finally {
      setLoadingSpans(false);
    }
  };

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
              onClick={() => handleSelect(t)}
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
                <div>
                  状态:{' '}
                  <span className={`status-${selected.status?.toLowerCase()}`}>
                    {statusLabel(selected)}
                  </span>
                </div>
                <div>活动ID: {selected.activityId}</div>
                <div>耗时: {selected.totalLatencyMs}ms</div>
                <div>费用: ${selected.budgetCostUsd?.toFixed(6)}</div>
                <div>模型版本: {selected.modelVersion}</div>
                <div>开始时间: {selected.startedAt}</div>
                <div>结束时间: {selected.finishedAt}</div>
                {selected.failureKind && <div>失败类型: {selected.failureKind}</div>}
                {selected.reviewStatus && (
                  <div>审批状态: {selected.reviewStatus === 'APPROVED' ? '已通过' : '已驳回'}</div>
                )}
              </div>
              <div className="verdict-box" style={{ marginTop: 16, padding: 12, background: 'var(--bg-hover)', borderRadius: 8, borderLeft: '4px solid var(--green)' }}>
                <strong>结论：</strong>{selected.verdict}
              </div>

              {/* Span 树展开 */}
              <h4 style={{ marginTop: 24 }}>调用步骤（Span 树）</h4>
              {loadingSpans && <p className="hint">加载中...</p>}
              {!loadingSpans && spans.length === 0 && <p className="hint">无 Span 数据</p>}
              {spans.length > 0 && (
                <div className="span-tree">
                  {spans.map((s) => (
                    <div
                      key={s.spanId}
                      className={`span-item span-${s.status?.toLowerCase()}`}
                      style={{ marginLeft: s.parentSpanId ? 24 : 0 }}
                    >
                      <div className="span-header">
                        <span className={`span-dot span-dot-${s.status?.toLowerCase()}`} />
                        <strong>{s.role}</strong>
                        <span className="span-step">{s.stepName}</span>
                        {s.toolName && <span className="span-tool">🔧 {s.toolName}</span>}
                        <span className="span-latency">{s.latencyMs}ms</span>
                        {s.retryCount > 0 && <span className="span-retry">↻{s.retryCount}</span>}
                      </div>
                      {s.toolOutput && (
                        <details className="span-output">
                          <summary>输出详情</summary>
                          <pre style={{ maxHeight: 200, overflow: 'auto', fontSize: 12 }}>
                            {s.toolOutput}
                          </pre>
                        </details>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </>
          ) : (
            <p className="hint">点击左侧 Trace 查看详情和调用步骤</p>
          )}
        </div>
      </div>
    </div>
  );
}
