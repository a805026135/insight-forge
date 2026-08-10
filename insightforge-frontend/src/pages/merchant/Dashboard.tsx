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

  const { seckill, agent, guardrail, generatedAt } = data;
  const successRate = agent.totalRuns > 0
    ? ((agent.successRuns / agent.totalRuns) * 100).toFixed(1)
    : '0.0';

  return (
    <div className="dashboard-page">
      <h2>运营大屏</h2>
      <p className="welcome">
        欢迎，{user?.displayName}（商家）&nbsp;&nbsp;|&nbsp;&nbsp;
        快照时间：{generatedAt}
      </p>

      {/* ─── 秒杀实时经营 ─── */}
      <h3 className="section-title">秒杀经营</h3>
      <div className="kpi-grid">
        <div className="kpi-card">
          <div className="kpi-value">{seckill.totalActivities}</div>
          <div className="kpi-label">活动总数</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-value">{seckill.liveActivities}</div>
          <div className="kpi-label">进行中</div>
        </div>
        <div className="kpi-card highlight">
          <div className="kpi-value">{seckill.totalStock}</div>
          <div className="kpi-label">总库存</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-value">{seckill.soldStock}</div>
          <div className="kpi-label">已售</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-value">{seckill.totalOrders}</div>
          <div className="kpi-label">总订单</div>
        </div>
        <div className="kpi-card highlight">
          <div className="kpi-value">¥{seckill.totalGmv.toLocaleString('zh-CN')}</div>
          <div className="kpi-label">真实 GMV（成交价快照）</div>
        </div>
      </div>

      {/* ─── Agent 运行时 ─── */}
      <h3 className="section-title" style={{ marginTop: 28 }}>Agent 运行时</h3>
      <div className="kpi-grid">
        <div className="kpi-card">
          <div className="kpi-value">{agent.totalRuns}</div>
          <div className="kpi-label">总运行次数</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-value">{successRate}%</div>
          <div className="kpi-label">成功率（{agent.successRuns}/{agent.totalRuns}）</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-value">{agent.correctFailureRuns}</div>
          <div className="kpi-label">正确失败（该拒绝就拒绝）</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-value">{agent.failedRuns}</div>
          <div className="kpi-label">真实失败</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-value">{(agent.correctFailureRate * 100).toFixed(1)}%</div>
          <div className="kpi-label">正确失败率</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-value">{agent.totalLatencyMsMax}ms</div>
          <div className="kpi-label">最大延迟</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-value">${agent.totalCostUsd.toFixed(4)}</div>
          <div className="kpi-label">累计成本 (USD)</div>
        </div>
      </div>

      {/* ─── 护栏策略 ─── */}
      <h3 className="section-title" style={{ marginTop: 28 }}>护栏策略</h3>
      <div className="kpi-grid">
        <div className="kpi-card">
          <div className="kpi-value">{guardrail.policyRows}</div>
          <div className="kpi-label">策略总条数</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-value">{guardrail.activitiesWithPolicy}</div>
          <div className="kpi-label">已配置活动</div>
        </div>
        <div className="kpi-card">
          <div className={`kpi-value risk-${guardrail.topRiskLevel}`}>{guardrail.topRiskLevel}</div>
          <div className="kpi-label">当前最高风险</div>
        </div>
      </div>
    </div>
  );
}
