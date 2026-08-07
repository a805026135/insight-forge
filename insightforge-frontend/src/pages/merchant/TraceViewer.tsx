import { mockTraces } from '../../mock/data';

const statusBadge: Record<string, string> = {
  SUCCESS: 'badge-success',
  CORRECT_FAILURE: 'badge-correct-fail',
  FAILED: 'badge-fail',
};

const statusLabel: Record<string, string> = {
  SUCCESS: '成功',
  CORRECT_FAILURE: '正确失败',
  FAILED: '失败',
};

export default function TraceViewer() {
  return (
    <div>
      <div className="page-header">
        <h2>🔍 Agent 轨迹</h2>
        <p>每一次 Agent 调用的完整链路追踪——做了什么、花了多久、结果如何</p>
      </div>

      <div className="metrics-grid">
        <div className="metric-tile green">
          <div className="label">成功率</div>
          <div className="value">
            {(mockTraces.filter(t => t.status === 'SUCCESS').length / mockTraces.length * 100).toFixed(0)}%
          </div>
          <div className="sub">{mockTraces.filter(t => t.status === 'SUCCESS').length}/{mockTraces.length}</div>
        </div>
        <div className="metric-tile amber">
          <div className="label">正确失败</div>
          <div className="value">{mockTraces.filter(t => t.status === 'CORRECT_FAILURE').length}</div>
          <div className="sub">被 Critic 拦截，不应算作失败</div>
        </div>
        <div className="metric-tile red">
          <div className="label">真实失败</div>
          <div className="value">{mockTraces.filter(t => t.status === 'FAILED').length}</div>
        </div>
        <div className="metric-tile cyan">
          <div className="label">平均延迟</div>
          <div className="value">{Math.round(mockTraces.reduce((a, t) => a + t.durationMs, 0) / mockTraces.length)}ms</div>
        </div>
      </div>

      <div className="card">
        <div className="card-title" style={{ marginBottom: 16 }}>调用轨迹</div>
        <table className="data-table">
          <thead>
            <tr>
              <th>Trace ID</th>
              <th>Agent</th>
              <th>步骤数</th>
              <th>耗时</th>
              <th>状态</th>
              <th>时间</th>
            </tr>
          </thead>
          <tbody>
            {mockTraces.map(trace => (
              <tr key={trace.traceId}>
                <td style={{ fontFamily: 'monospace', fontSize: 12, color: 'var(--cyan)' }}>{trace.traceId}</td>
                <td style={{ fontWeight: 600 }}>{trace.agent}</td>
                <td>{trace.steps}</td>
                <td>
                  <span style={{ color: trace.durationMs > 500 ? 'var(--amber)' : 'var(--green)' }}>
                    {trace.durationMs}ms
                  </span>
                </td>
                <td><span className={`badge ${statusBadge[trace.status]}`}>{statusLabel[trace.status]}</span></td>
                <td style={{ fontSize: 12, color: 'var(--text-muted)' }}>{trace.createdAt}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
