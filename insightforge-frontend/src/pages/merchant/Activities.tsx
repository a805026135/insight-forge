import { mockActivities } from '../../mock/data';

const statusBadge: Record<string, string> = {
  LIVE: 'badge-live',
  ARMED: 'badge-armed',
  SETTLING: 'badge-settling',
  CLOSED: 'badge-closed',
  DRAFT: 'badge-closed',
};

const statusLabel: Record<string, string> = {
  LIVE: '进行中',
  ARMED: '已上线',
  SETTLING: '结算中',
  CLOSED: '已结束',
  DRAFT: '草稿',
};

export default function Activities() {
  return (
    <div>
      <div className="page-header">
        <h2>🎯 活动管理</h2>
        <p>管理全部大促活动，查看状态与关键指标</p>
      </div>

      <div className="card">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
          <span className="card-title">全部活动</span>
          <button className="btn btn-primary">+ 新建活动</button>
        </div>
        <table className="data-table">
          <thead>
            <tr>
              <th>活动名称</th>
              <th>状态</th>
              <th>开始时间</th>
              <th>目标 GMV</th>
              <th>目标 ROI</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {mockActivities.map(act => (
              <tr key={act.id}>
                <td style={{ fontWeight: 600 }}>{act.name}</td>
                <td><span className={`badge ${statusBadge[act.status]}`}>{statusLabel[act.status]}</span></td>
                <td style={{ color: 'var(--text-muted)', fontSize: 12 }}>{act.startAt}</td>
                <td>¥{(act.targetGMV / 10000).toFixed(0)}万</td>
                <td>{act.targetROI}x</td>
                <td>
                  {act.status === 'ARMED' && <button className="btn btn-primary" style={{ padding: '4px 12px', fontSize: 12 }}>启动</button>}
                  {act.status === 'LIVE' && <button className="btn btn-danger" style={{ padding: '4px 12px', fontSize: 12 }}>暂停</button>}
                  {(act.status === 'SETTLING' || act.status === 'CLOSED') && <button className="btn" style={{ padding: '4px 12px', fontSize: 12 }}>查看报告</button>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
