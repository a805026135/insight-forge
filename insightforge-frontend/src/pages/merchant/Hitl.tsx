import { mockHitlTasks } from '../../mock/data';

const severityBadge: Record<string, string> = {
  critical: 'badge-critical',
  high: 'badge-high',
  medium: 'badge-medium',
  low: 'badge-low',
};

const severityLabel: Record<string, string> = {
  critical: '严重',
  high: '高',
  medium: '中',
  low: '低',
};

export default function Hitl() {
  return (
    <div>
      <div className="page-header">
        <h2>✋ 人工审批</h2>
        <p>Agent 无法自动决策的任务，需人工介入确认。这是控制面的最后一道防线。</p>
      </div>

      <div className="metrics-grid">
        <div className="metric-tile red">
          <div className="label">待审批</div>
          <div className="value">{mockHitlTasks.length}</div>
        </div>
        <div className="metric-tile amber">
          <div className="label">严重/高风险</div>
          <div className="value">{mockHitlTasks.filter(t => t.severity === 'critical' || t.severity === 'high').length}</div>
        </div>
        <div className="metric-tile green">
          <div className="label">今日已处理</div>
          <div className="value">12</div>
        </div>
      </div>

      <div className="card">
        <div className="card-title" style={{ marginBottom: 16 }}>审批队列</div>
        {mockHitlTasks.map(task => (
          <div key={task.id} style={{
            background: 'var(--bg-hover)', borderRadius: 8, padding: 16, marginBottom: 12,
            borderLeft: `3px solid ${task.severity === 'critical' ? 'var(--red)' : task.severity === 'high' ? 'var(--amber)' : 'var(--green)'}`
          }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 8 }}>
              <div>
                <span className={`badge ${severityBadge[task.severity]}`} style={{ marginRight: 8 }}>
                  {severityLabel[task.severity]}
                </span>
                <span style={{ fontWeight: 600 }}>{task.type}</span>
              </div>
              <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>{task.createdAt}</span>
            </div>
            <p style={{ fontSize: 13, marginBottom: 8, color: 'var(--text-primary)' }}>{task.description}</p>
            <div style={{ background: 'var(--bg-card)', padding: 8, borderRadius: 4, marginBottom: 12, fontSize: 12, color: 'var(--text-muted)' }}>
              🤖 {task.agentDecision}
            </div>
            <div style={{ display: 'flex', gap: 8 }}>
              <button className="btn btn-primary" style={{ padding: '4px 16px', fontSize: 12 }}>批准</button>
              <button className="btn btn-danger" style={{ padding: '4px 16px', fontSize: 12 }}>驳回</button>
              <button className="btn" style={{ padding: '4px 16px', fontSize: 12 }}>修改后批准</button>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
