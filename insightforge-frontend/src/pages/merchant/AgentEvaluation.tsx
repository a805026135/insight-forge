import { useEffect, useState } from 'react';
import { dashboardApi, AgentOpsSummary, RagEvaluationReport } from '../../api';

export default function AgentEvaluation() {
  const [ops, setOps] = useState<AgentOpsSummary | null>(null);
  const [report, setReport] = useState<RagEvaluationReport | null>(null);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState('');
  const [replaying, setReplaying] = useState(false);
  const [replayResult, setReplayResult] = useState<Array<{sample:{name:string};actualStatus:string;passed:boolean}>>([]);

  useEffect(() => { dashboardApi.agentOps().then(setOps).catch((e) => setError(e.message)); }, []);
  const run = async () => {
    setRunning(true); setError('');
    try { setReport(await dashboardApi.runRagEvaluation()); }
    catch (e: any) { setError(e.message || '评测执行失败'); }
    finally { setRunning(false); }
  };
  const pct = (v: number) => `${(v * 100).toFixed(1)}%`;
  const replay = async () => {
    setReplaying(true); setError(''); setReplayResult([]);
    try {
      let job = await dashboardApi.replayFailures();
      while (job.status === 'RUNNING') {
        await new Promise((resolve) => setTimeout(resolve, 2500));
        job = await dashboardApi.failureReplayStatus(job.jobId);
      }
      if (job.status === 'FAILED') throw new Error(job.error || '回放失败');
      setReplayResult(job.results);
    } catch(e:any) { setError(e.message); } finally { setReplaying(false); }
  };

  return <div className="evaluation-page">
    <div className="page-head">
      <div><span className="section-eyebrow">AGENT QUALITY GATE</span><h2>Agent 评测与成本</h2></div>
      <button className="btn btn-primary" disabled={running} onClick={run}>{running ? '评测运行中…' : '运行 40 条 RAG 评测'}</button>
    </div>
    {error && <div className="toast error">{error}</div>}
    {ops && <>
      <div className="kpi-grid evaluation-kpis">
        <div className="kpi-card"><span>真实 Trace</span><b>{ops.traces}</b><small>{ops.spans} spans</small></div>
        <div className="kpi-card"><span>Token 用量</span><b>{(ops.inputTokens + ops.outputTokens).toLocaleString()}</b><small>输入 {ops.inputTokens.toLocaleString()} / 输出 {ops.outputTokens.toLocaleString()}</small></div>
        <div className="kpi-card"><span>累计成本</span><b>${Number(ops.totalCostUsd).toFixed(4)}</b><small>平均 ${Number(ops.averageCostUsd).toFixed(6)} / trace</small></div>
        <div className="kpi-card"><span>规划 P95</span><b>{ops.p95LatencyMs} ms</b><small>来自真实 trace</small></div>
      </div>
      <div className="evaluation-split">
        <section className="assistant-panel"><h3>版本分布</h3>
          <div className="version-list"><b>Prompt</b>{Object.entries(ops.promptVersions).map(([k,v]) => <p key={k}><code>{k}</code><span>{v} traces</span></p>)}</div>
          <div className="version-list"><b>模型</b>{Object.entries(ops.modelVersions).map(([k,v]) => <p key={k}><code>{k}</code><span>{v} traces</span></p>)}</div>
        </section>
        <section className="assistant-panel"><div className="card-header"><h3>失败回放队列</h3><button className="btn" disabled={replaying} onClick={replay}>{replaying ? '回放中…' : '回放 5 条红线样本'}</button></div>
          {ops.replayCandidates.slice(0,6).map((r) => <div className="replay-row" key={r.traceId}><span className="badge">{r.status}</span><code>{r.traceId}</code><small>{r.failureKind || 'ERROR'} · {r.latencyMs}ms</small></div>)}
          {ops.replayCandidates.length === 0 && <p className="hint">当前没有失败 trace</p>}
          {replayResult.map(r => <div className="replay-row" key={r.sample.name}><span>{r.sample.name}</span><small>{r.actualStatus}</small><b>{r.passed ? '通过' : '失败'}</b></div>)}
        </section>
      </div>
    </>}
    {report && <section className="eval-report">
      <div className="eval-report-head"><div><h3>RAG 离线评测结果</h3><p>{report.datasetVersion} · {report.totalCases} 条 · {report.embeddingProvider} / {report.vectorStore}</p></div><span className="badge badge-live">本次实测</span></div>
      <div className="metrics-grid">
        <div className="metric-tile green"><div className="label">Recall@4</div><div className="value">{pct(report.recallAt4)}</div></div>
        <div className="metric-tile cyan"><div className="label">答案正确率</div><div className="value">{pct(report.answerAccuracy)}</div></div>
        <div className="metric-tile purple"><div className="label">拒答准确率</div><div className="value">{pct(report.refusalAccuracy)}</div></div>
        <div className="metric-tile amber"><div className="label">检索 P95</div><div className="value">{report.p95LatencyMs}ms</div></div>
      </div>
      {report.variants?.length > 0 && <div className="order-table-wrap"><table className="data-table"><thead><tr><th>消融方案</th><th>Recall@4</th><th>答案正确率</th><th>拒答准确率</th><th>P95</th></tr></thead><tbody>{report.variants.map(v => <tr key={v.variant}><td>{v.variant}</td><td>{pct(v.recallAt4)}</td><td>{pct(v.answerAccuracy)}</td><td>{pct(v.refusalAccuracy)}</td><td>{v.p95LatencyMs}ms</td></tr>)}</tbody></table></div>}
      <div className="category-bars">{Object.entries(report.categories).map(([name,m]) => <div key={name}><span>{name}</span><div><i style={{width:pct(m.passRate)}} /></div><b>{pct(m.passRate)}</b></div>)}</div>
      <details><summary>查看逐条结果</summary><div className="order-table-wrap"><table className="data-table"><thead><tr><th>ID</th><th>类别</th><th>问题</th><th>命中文档</th><th>延迟</th><th>结果</th></tr></thead><tbody>{report.cases.map(c => <tr key={c.id}><td>{c.id}</td><td>{c.category}</td><td>{c.question}</td><td>{c.retrievedDocKeys.join(', ') || '拒答'}</td><td>{c.latencyMs}ms</td><td>{c.answerable ? (c.retrievedExpected && c.keywordsCovered ? '通过' : '失败') : (c.refused ? '正确拒答' : '误答')}</td></tr>)}</tbody></table></div></details>
    </section>}
  </div>;
}
