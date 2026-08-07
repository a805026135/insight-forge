import { useEffect, useState } from 'react';
import { useAuth } from '../../auth/AuthContext';
import { dashboardApi, DashboardSnapshot } from '../../api';

export default function Dashboard() {
  const { user } = useAuth();
  const [data, setData] = useState<DashboardSnapshot | null>(null);
  const [err, setErr] = useState('');

  useEffect(() => {
    dashboardApi.overview().then(setData).catch((e) => setErr(e.message));
  }, []);

  if (err) return <div className="page"><h2>运营大屏</h2><div className="error">{err}</div></div>;
  if (!data) return <div className="page"><h2>运营大屏</h2><p>加载中...</p></div>;

  return (
    <div className="dashboard-page">
      <h2>运营大屏</h2>
      <p className="welcome">欢迎，{user?.displayName}（商家）</p>

      <div className="kpi-grid">
        <div className="kpi-card">
          <div className="kpi-value">{data.totalActivities}</div>
          <div className="kpi-label">活动总数</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-value">{data.totalOrders}</div>
          <div className="kpi-label">总订单</div>
        </div>
        <div className="kpi-card highlight">
          <div className="kpi-value">¥{data.totalRevenue?.toLocaleString() ?? 0}</div>
          <div className="kpi-label">GMV</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-value">{((data.sellThroughRate ?? 0) * 100).toFixed(1)}%</div>
          <div className="kpi-label">售罄率</div>
        </div>
      </div>

      <h3 style={{ marginTop: 32 }}>Agent 运行时</h3>
      <div className="kpi-grid">
        <div className="kpi-card">
          <div className="kpi-value">{data.agentTotalRuns ?? 0}</div>
          <div className="kpi-label">总运行次数</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-value">{((data.agentSuccessRate ?? 0) * 100).toFixed(1)}%</div>
          <div className="kpi-label">成功率</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-value">{data.agentAvgLatencyMs}ms</div>
          <div className="kpi-label">平均延迟</div>
        </div>
      </div>

      <h3 style={{ marginTop: 32 }}>护栏策略</h3>
      <div className="kpi-grid">
        <div className="kpi-card">
          <div className="kpi-value">{data.guardrailsActive}</div>
          <div className="kpi-label">生效中</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-value">{data.guardrailsArchived}</div>
          <div className="kpi-label">已归档</div>
        </div>
      </div>
    </div>
  );
}
