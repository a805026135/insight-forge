import { mockPolicies } from '../../mock/data';

export default function Guardrails() {
  return (
    <div>
      <div className="page-header">
        <h2>🛡️ 护栏策略</h2>
        <p>Agent 离线生成的定价/库存护栏 DSL，由 Drools 在线确定性执行</p>
      </div>

      <div className="metrics-grid">
        <div className="metric-tile purple">
          <div className="label">策略总数</div>
          <div className="value">{mockPolicies.length}</div>
        </div>
        <div className="metric-tile green">
          <div className="label">生效中</div>
          <div className="value">{mockPolicies.filter(p => p.status === 'ACTIVE').length}</div>
        </div>
        <div className="metric-tile cyan">
          <div className="label">总规则数</div>
          <div className="value">{mockPolicies.reduce((a, p) => a + p.ruleCount, 0)}</div>
        </div>
      </div>

      <div className="card">
        <div className="card-title" style={{ marginBottom: 16 }}>策略列表</div>
        <table className="data-table">
          <thead>
            <tr>
              <th>策略 ID</th>
              <th>关联活动</th>
              <th>版本</th>
              <th>规则数</th>
              <th>风险等级</th>
              <th>状态</th>
              <th>生成时间</th>
              <th>策略摘要</th>
            </tr>
          </thead>
          <tbody>
            {mockPolicies.map(p => (
              <tr key={p.id}>
                <td style={{ fontFamily: 'monospace', fontWeight: 600 }}>#POL-{p.id}</td>
                <td>活动 #{p.activityId}</td>
                <td>v{p.version}</td>
                <td>{p.ruleCount}</td>
                <td>
                  <span className={`badge badge-${p.riskLevel === '高' ? 'high' : p.riskLevel === '中' ? 'medium' : 'low'}`}>
                    {p.riskLevel}风险
                  </span>
                </td>
                <td>
                  <span className={`badge ${p.status === 'ACTIVE' ? 'badge-live' : 'badge-closed'}`}>
                    {p.status === 'ACTIVE' ? '生效中' : '已归档'}
                  </span>
                </td>
                <td style={{ fontSize: 12, color: 'var(--text-muted)' }}>{p.createdAt}</td>
                <td style={{ fontSize: 12, color: 'var(--text-secondary)', maxWidth: 300 }}>{p.summary}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
