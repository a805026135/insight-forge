import { useEffect, useState } from 'react';
import { dashboardApi, GuardrailView } from '../../api';

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
        {list.map((g) => {
          let policy: any = {};
          try { policy = JSON.parse(g.policyJson); } catch {}
          const risk = g.riskLevel || policy.riskLevel || 'medium';
          return (
            <div key={g.id} className="guardrail-card">
              <div className={`risk-badge risk-${risk}`}>{risk?.toUpperCase()}</div>
              <h4>活动 {g.activityId} · v{g.version}</h4>
              <div className="policy-snippet">
                策略ID: {policy.ruleId ?? policy.id ?? '-'}
              </div>
              <div className="policy-snippet">
                阈值: {policy.threshold ?? '-'}
              </div>
              <div className="policy-time">{g.createdAt}</div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
