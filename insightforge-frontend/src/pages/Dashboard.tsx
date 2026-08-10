import ReactECharts from 'echarts-for-react';
import { useDashboardSnapshot } from '../hooks/useDashboardSnapshot';

/**
 * 运营大屏（Phase 18 美化）：KPI 磁贴 + 图表化经营/Agent/护栏三块数据。
 */
export default function Dashboard() {
  const { snapshot, live } = useDashboardSnapshot();
  const s = snapshot?.seckill;
  const a = snapshot?.agent;
  const g = snapshot?.guardrail;

  const riskColor =
    g?.topRiskLevel === '高' ? 'var(--red)' : g?.topRiskLevel === '中' ? 'var(--amber)' : 'var(--green)';

  const stockOption = {
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 20, top: 30, bottom: 30 },
    xAxis: { type: 'category', data: ['已售', '剩余'], axisLabel: { color: 'var(--text-secondary)' } },
    yAxis: { type: 'value', axisLabel: { color: 'var(--text-muted)' }, splitLine: { lineStyle: { color: 'var(--border)' } } },
    series: [
      {
        type: 'bar',
        data: [
          { value: s?.soldStock ?? 0, itemStyle: { color: 'var(--amber)' } },
          { value: (s?.totalStock ?? 0) - (s?.soldStock ?? 0), itemStyle: { color: 'var(--green)' } },
        ],
        barWidth: 48,
        label: { show: true, position: 'top', color: 'var(--text-secondary)' },
      },
    ],
  };

  const agentOption = {
    tooltip: { trigger: 'item' },
    legend: { bottom: 0, textStyle: { color: 'var(--text-secondary)' } },
    series: [
      {
        type: 'pie',
        radius: ['42%', '68%'],
        center: ['50%', '45%'],
        label: { color: 'var(--text-secondary)' },
        data: [
          { value: a?.successRuns ?? 0, name: '规划成功', itemStyle: { color: 'var(--green)' } },
          { value: a?.correctFailureRuns ?? 0, name: '正确失败', itemStyle: { color: 'var(--cyan)' } },
          { value: a?.failedRuns ?? 0, name: '运行失败', itemStyle: { color: 'var(--red)' } },
        ],
      },
    ],
  };

  const gaugeOption = {
    series: [
      {
        type: 'gauge',
        startAngle: 200,
        endAngle: -20,
        min: 0,
        max: 1,
        progress: { show: true, width: 18, itemStyle: { color: 'var(--cyan)' } },
        axisLine: { lineStyle: { width: 18, color: [[1, 'var(--border)']] } },
        axisLabel: { color: 'var(--text-muted)', distance: 24, formatter: (v: number) => `${(v * 100).toFixed(0)}%` },
        pointer: { show: false },
        detail: {
          valueAnimation: true,
          formatter: (v: number) => `${(v * 100).toFixed(1)}%`,
          color: 'var(--text-primary)',
          fontSize: 26,
        },
        data: [{ value: a?.correctFailureRate ?? 0, name: '正确失败率' }],
      },
    ],
  };

  const gmvOption = {
    tooltip: { trigger: 'axis' },
    grid: { left: 50, right: 20, top: 30, bottom: 30 },
    xAxis: { type: 'category', data: ['GMV', '目标 GMV'], axisLabel: { color: 'var(--text-secondary)' } },
    yAxis: { type: 'value', axisLabel: { color: 'var(--text-muted)', formatter: (v: number) => `${(v / 10000).toFixed(0)}万` }, splitLine: { lineStyle: { color: 'var(--border)' } } },
    series: [
      {
        type: 'bar',
        data: [
          { value: s?.totalGmv ?? 0, itemStyle: { color: 'var(--purple)' } },
          { value: (snapshot as any)?.targetGmv ?? 0, itemStyle: { color: 'var(--border-bright)' } },
        ],
        barWidth: 44,
        label: { show: true, position: 'top', color: 'var(--text-secondary)', formatter: (p: any) => `${(p.value / 10000).toFixed(1)}万` },
      },
    ],
  };

  return (
    <div className="dashboard-page">
      <div className="page-head">
        <h2>运营大屏</h2>
        <span className={`badge ${live ? 'badge-live' : 'badge-closed'}`}>
          {live ? '实时通道 · WebSocket 已连接' : '兜底通道 · 每 3 秒轮询'}
        </span>
      </div>

      <div className="metrics-grid">
        <div className="metric-tile-big">
          <div className="mt-label">进行中活动</div>
          <div className="mt-value">{s?.liveActivities ?? 0}<span style={{ fontSize: 16, color: 'var(--text-muted)' }}> / {s?.totalActivities ?? 0}</span></div>
          <div className="mt-sub">平台大促场次</div>
        </div>
        <div className="metric-tile-big">
          <div className="mt-label">累计订单</div>
          <div className="mt-value">{s?.totalOrders ?? 0}</div>
          <div className="mt-sub">含已取消的全部订单</div>
        </div>
        <div className="metric-tile-big">
          <div className="mt-label">真实 GMV</div>
          <div className="mt-value" style={{ color: 'var(--purple)' }}>¥{((s?.totalGmv ?? 0) / 10000).toFixed(1)}万</div>
          <div className="mt-sub">成交价 × 数量（status≠CANCEL）</div>
        </div>
        <div className="metric-tile-big">
          <div className="mt-label">最高护栏风险</div>
          <div className="mt-value" style={{ color: riskColor }}>{g?.topRiskLevel ?? '低'}</div>
          <div className="mt-sub">{g?.activitiesWithPolicy ?? 0} 个活动已生效策略</div>
        </div>
      </div>

      <div className="chart-grid">
        <div className="chart-card">
          <h4>秒杀库存水位（已售 vs 剩余）</h4>
          <ReactECharts option={stockOption} style={{ height: 240 }} />
        </div>
        <div className="chart-card">
          <h4>Agent 运行分布</h4>
          <ReactECharts option={agentOption} style={{ height: 240 }} />
        </div>
        <div className="chart-card">
          <h4>Agent 正确失败率（红线指标）</h4>
          <ReactECharts option={gaugeOption} style={{ height: 240 }} />
          <div className="mt-sub" style={{ textAlign: 'center' }}>
            规划运行 {a?.totalRuns ?? 0} 次 · 正确失败 {a?.correctFailureRuns ?? 0} 次 · 失败 {a?.failedRuns ?? 0} 次
          </div>
        </div>
        <div className="chart-card">
          <h4>GMV 达成（真实 vs 目标）</h4>
          <ReactECharts option={gmvOption} style={{ height: 240 }} />
        </div>
      </div>
    </div>
  );
}
