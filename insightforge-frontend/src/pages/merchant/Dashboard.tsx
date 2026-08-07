import { mockActivities, mockSkus, mockAgentMetrics, mockAgentTimeline, mockPolicies, mockOrders, mockRevenueData } from '../../mock/data';
import ReactECharts from 'echarts-for-react';

export default function Dashboard() {
  const liveActivity = mockActivities.find(a => a.status === 'LIVE');
  const liveSkus = mockSkus.filter(s => s.activityId === liveActivity?.id);
  const totalStock = liveSkus.reduce((a, s) => a + s.totalStock, 0);
  const totalSold = liveSkus.reduce((a, s) => a + s.sold, 0);
  const totalRevenue = mockOrders.filter(o => o.status !== 'CANCEL').reduce((a, o) => a + o.amount, 0);

  const revenueChart = {
    tooltip: { trigger: 'axis' as const },
    grid: { left: 40, right: 20, top: 10, bottom: 20 },
    xAxis: { type: 'category' as const, data: mockRevenueData.map(d => d.hour), axisLine: { lineStyle: { color: '#1e2d45' } }, axisLabel: { color: '#556070', fontSize: 10 } },
    yAxis: { type: 'value' as const, splitLine: { lineStyle: { color: '#1e2d45' } }, axisLabel: { color: '#556070', fontSize: 10 } },
    series: [{
      data: mockRevenueData.map(d => d.revenue),
      type: 'bar',
      itemStyle: { borderRadius: [4, 4, 0, 0], color: '#00e676' },
      barWidth: 16,
    }],
  };

  const agentTimelineChart = {
    tooltip: { trigger: 'axis' as const },
    grid: { left: 40, right: 40, top: 10, bottom: 20 },
    xAxis: { type: 'category' as const, data: mockAgentTimeline.map(d => d.time), axisLine: { lineStyle: { color: '#1e2d45' } }, axisLabel: { color: '#556070', fontSize: 10 } },
    yAxis: [
      { type: 'value' as const, name: 'runs', splitLine: { lineStyle: { color: '#1e2d45' } }, axisLabel: { color: '#556070', fontSize: 10 } },
      { type: 'value' as const, name: 'ms', splitLine: { show: false }, axisLabel: { color: '#556070', fontSize: 10 } },
    ],
    series: [
      {
        data: mockAgentTimeline.map(d => d.runs),
        type: 'line',
        smooth: true,
        itemStyle: { color: '#7c4dff' },
        lineStyle: { width: 2 },
        areaStyle: { color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1, colorStops: [{ offset: 0, color: 'rgba(124,77,255,0.2)' }, { offset: 1, color: 'rgba(124,77,255,0)' }] } },
      },
      {
        data: mockAgentTimeline.map(d => d.latency),
        type: 'line',
        smooth: true,
        yAxisIndex: 1,
        itemStyle: { color: '#00bcd4' },
        lineStyle: { width: 2, type: 'dashed' as const },
      },
    ],
  };

  return (
    <div>
      <div className="page-header">
        <h2>📊 运营大屏</h2>
        <p>实时数据概览 · {liveActivity?.name || '无进行中活动'}</p>
      </div>

      {/* Core Metrics */}
      <div className="metrics-grid">
        <div className="metric-tile green">
          <div className="label">今日 GMV</div>
          <div className="value">¥{(totalRevenue / 10000).toFixed(1)}万</div>
          <div className="sub">目标 ¥500万</div>
        </div>
        <div className="metric-tile cyan">
          <div className="label">实时售罄率</div>
          <div className="value">{totalStock > 0 ? ((totalSold / totalStock) * 100).toFixed(1) : 0}%</div>
          <div className="sub">{totalSold}/{totalStock} 件</div>
        </div>
        <div className="metric-tile amber">
          <div className="label">订单总量</div>
          <div className="value">{mockOrders.length}</div>
          <div className="sub">取消 {mockOrders.filter(o => o.status === 'CANCEL').length} 笔</div>
        </div>
        <div className="metric-tile purple">
          <div className="label">Agent 调用</div>
          <div className="value">{mockAgentMetrics.totalRuns}</div>
          <div className="sub">成功率 {(mockAgentMetrics.successRuns / mockAgentMetrics.totalRuns * 100).toFixed(1)}%</div>
        </div>
      </div>

      {/* Charts */}
      <div className="grid-2 section">
        <div className="card">
          <div className="card-header">
            <span className="card-title">实时收入</span>
            <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>单位: ¥</span>
          </div>
          <ReactECharts option={revenueChart} style={{ height: 240 }} />
        </div>
        <div className="card">
          <div className="card-header">
            <span className="card-title">Agent 运行时</span>
            <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>紫色=调用量 / 青色=延迟(ms)</span>
          </div>
          <ReactECharts option={agentTimelineChart} style={{ height: 240 }} />
        </div>
      </div>

      {/* Agent Metrics */}
      <div className="section">
        <div className="section-title">Agent 运行概况</div>
        <div className="grid-2">
          <div className="card">
            <table className="data-table">
              <thead><tr><th>指标</th><th>数值</th></tr></thead>
              <tbody>
                <tr><td>总运行次数</td><td style={{ fontWeight: 700 }}>{mockAgentMetrics.totalRuns}</td></tr>
                <tr><td>成功次数</td><td style={{ color: 'var(--green)' }}>{mockAgentMetrics.successRuns}</td></tr>
                <tr><td>正确失败次数</td><td style={{ color: 'var(--amber)' }}>{mockAgentMetrics.correctFailureRuns}</td></tr>
                <tr><td>失败次数</td><td style={{ color: 'var(--red)' }}>{mockAgentMetrics.failedRuns}</td></tr>
                <tr><td>正确失败率</td><td>{mockAgentMetrics.correctFailureRate}%</td></tr>
              </tbody>
            </table>
          </div>
          <div className="card">
            <div className="card-title" style={{ marginBottom: 12 }}>活跃护栏策略</div>
            {mockPolicies.filter(p => p.status === 'ACTIVE').map(p => (
              <div key={p.id} style={{ marginBottom: 12, padding: 10, background: 'var(--bg-hover)', borderRadius: 6 }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
                  <span style={{ fontWeight: 600, fontSize: 13 }}>活动 #{p.activityId} v{p.version}</span>
                  <span className={`badge badge-${p.riskLevel === '高' ? 'high' : p.riskLevel === '中' ? 'medium' : 'low'}`}>{p.riskLevel}风险</span>
                </div>
                <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>{p.summary}</div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
