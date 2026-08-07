import { useEffect, useState } from 'react';
import { dashboardApi, GuardrailView } from '../../api';

const RISK_COLORS: Record<string, string> = { '高': '#ef4444', '中': '#f59e0b', '低': '#22c55e' };

export default function Guardrails() {
  const [list, setList] = useState<GuardrailView[]>([]);
  const [err, setErr] = useState('');

  useEffect(() => {
    dashboardApi.guardrails()
      .then(setList)
      .catch((e) => setErr(e.message));
  }, []);

  return (
    <div className="page">
      <h2>护栏策略</h2>
      {err && <div className="error">{err}</div>}
      {list.length === 0 && !err && <p>暂无护栏策略数据</p>}
      <div className="guardrail-grid">
        {list.map((g, i) => {
          const risk = g.topRiskLevel || '低';
          return (
            <div key={`${g.activityId}-${g.version}-${i}`} className="guardrail-card">
              <div
                className="risk-badge"
                style={{ background: RISK_COLORS[risk] || RISK_COLORS['低'] }}
              >
                {risk}
              </div>
              <h4>活动 {g.activityId} · v{g.version}</h4>
              <div className="policy-snippet">
                规则数: <strong>{g.ruleCount}</strong>
              </div>
              <div className="policy-snippet">
                {g.summary}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
