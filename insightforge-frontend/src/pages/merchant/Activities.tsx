import { useEffect, useState } from 'react';
import { activityApi, agentApi, ActivityView, ReconcileItem, AgentPlanResponse } from '../../api';

export default function Activities() {
  const [list, setList] = useState<ActivityView[]>([]);
  const [loading, setLoading] = useState(true);
  const [items, setItems] = useState<Record<number, ReconcileItem[]>>({});
  const [passed, setPassed] = useState<Record<number, boolean>>({});
  const [msg, setMsg] = useState('');
  const [showCreate, setShowCreate] = useState(false);
  const [planning, setPlanning] = useState<number | null>(null);
  const [planResult, setPlanResult] = useState<AgentPlanResponse | null>(null);

  // 创建活动表单状态
  const [form, setForm] = useState({
    name: '',
    targetGmv: '1000000',
    targetRoi: '1.5',
    startAt: '2026-11-11T00:00',
    endAt: '2026-11-11T23:59',
    skuName: '',
    skuCode: '',
    costPrice: '5000',
    seckillPrice: '5999',
    totalStock: '100',
  });

  const refresh = async () => {
    setLoading(true);
    try {
      const data = await activityApi.list();
      setList(data);
      for (const a of data) {
        try {
          const r = await activityApi.reconcile(a.id);
          setItems((prev) => ({ ...prev, [a.id]: r.items }));
          setPassed((prev) => ({ ...prev, [a.id]: r.passed }));
        } catch { /* DRAFT 状态无对账数据，忽略 */ }
      }
    } catch (ex: any) {
      setMsg(ex.message || '加载活动列表失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { refresh(); }, []);

  const doArm = async (id: number) => {
    try {
      const r = await activityApi.arm(id);
      setList((prev) => prev.map((a) => (a.id === id ? { ...a, status: 'ARMED' } : a)));
      setItems((prev) => ({ ...prev, [id]: r.items }));
      setPassed((prev) => ({ ...prev, [id]: r.passed }));
      setMsg(`活动 #${id} 已上线（ARMED）`);
    } catch (ex: any) { setMsg(ex.message); }
  };

  const doLive = async (id: number) => {
    try {
      await activityApi.live(id);
      setList((prev) => prev.map((a) => (a.id === id ? { ...a, status: 'LIVE' } : a)));
      setMsg(`活动 #${id} 已开抢（LIVE）`);
    } catch (ex: any) { setMsg(ex.message); }
  };

  const doSettle = async (id: number) => {
    try {
      const r = await activityApi.settle(id);
      setList((prev) => prev.map((a) => (a.id === id ? { ...a, status: 'SETTLING' } : a)));
      setItems((prev) => ({ ...prev, [id]: r.items }));
      setPassed((prev) => ({ ...prev, [id]: r.passed }));
      setMsg(`活动 #${id} 已结算`);
    } catch (ex: any) { setMsg(ex.message); }
  };

  const doClose = async (id: number) => {
    try {
      await activityApi.close(id);
      setList((prev) => prev.map((a) => (a.id === id ? { ...a, status: 'CLOSED' } : a)));
      setMsg(`活动 #${id} 已关闭`);
    } catch (ex: any) { setMsg(ex.message); }
  };

  const handleCreate = async () => {
    try {
      await activityApi.create({
        name: form.name,
        targetGmv: parseFloat(form.targetGmv),
        targetRoi: parseFloat(form.targetRoi),
        startAt: form.startAt,
        endAt: form.endAt,
        skus: [{
          productId: null,
          productName: form.skuName,
          skuCode: form.skuCode,
          costPrice: parseFloat(form.costPrice),
          seckillPrice: parseFloat(form.seckillPrice),
          totalStock: parseInt(form.totalStock),
        }],
      });
      setMsg(`活动「${form.name}」创建成功`);
      setShowCreate(false);
      setForm({ ...form, name: '', skuName: '', skuCode: '' });
      await refresh();
    } catch (ex: any) {
      setMsg(`创建失败：${ex.message}`);
    }
  };

  const handlePlan = async (activityId: number) => {
    setPlanning(activityId);
    setPlanResult(null);
    try {
      const result = await agentApi.plan({
        activityId,
        goal: `为活动 ${activityId} 制定控盘预案：目标 GMV 最优、防超卖、合理调价与调拨`,
      });
      setPlanResult(result);
      setMsg(`Agent 规划完成：${result.verdict}（trace: ${result.traceId.slice(0, 8)}...）`);
    } catch (ex: any) {
      setMsg(`规划失败：${ex.message}`);
    } finally {
      setPlanning(null);
    }
  };

  const statusLabel = (s: string) => {
    const map: Record<string, string> = { DRAFT: '草稿', ARMED: '已上线', LIVE: '开抢中', SETTLING: '结算中', CLOSED: '已关闭' };
    return map[s] || s;
  };

  if (loading) return <div className="page"><h2>活动管理</h2><p>加载中…</p></div>;

  return (
    <div className="page">
      <h2>活动管理</h2>
      {msg && <div className="toast">{msg}</div>}

      {/* Agent 规划结果 */}
      {planResult && (
        <div className="plan-result" style={{ marginBottom: 16, padding: 16, background: 'var(--bg-hover)', borderRadius: 8, borderLeft: '4px solid var(--blue)' }}>
          <h4>Agent 规划结果</h4>
          <div className="detail-grid" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
            <div>Trace ID: {planResult.traceId.slice(0, 16)}...</div>
            <div>状态: <span className={`status-${planResult.status?.toLowerCase()}`}>{planResult.status}</span></div>
            <div>耗时: {planResult.totalLatencyMs}ms</div>
            <div>发布版本: {planResult.publishedVersion || '未发布'}</div>
            <div style={{ gridColumn: '1 / -1' }}>结论: {planResult.verdict}</div>
          </div>
        </div>
      )}

      <table className="order-table">
        <thead>
          <tr>
            <th>ID</th>
            <th>名称</th>
            <th>目标 GMV</th>
            <th>状态</th>
            <th>对账</th>
            <th>明细</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          {list.map((a) => (
            <tr key={a.id}>
              <td>{a.id}</td>
              <td>{a.name}</td>
              <td>¥{(a.targetGmv ?? 0).toLocaleString()}</td>
              <td className={`status-${a.status?.toLowerCase()}`}>{statusLabel(a.status)}</td>
              <td>
                {passed[a.id] !== undefined
                  ? (passed[a.id] ? '✅ 通过' : '❌ 异常')
                  : '-'}
              </td>
              <td style={{ fontSize: 12, color: 'var(--text-muted)' }}>
                {items[a.id]?.map((it, i) => (
                  <div key={i}>
                    {it.ok ? '✅' : '❌'} {it.dimension}: {it.detail}
                  </div>
                ))}
              </td>
              <td style={{ whiteSpace: 'nowrap' }}>
                {a.status === 'DRAFT' && <button onClick={() => doArm(a.id)}>上线</button>}
                {a.status === 'ARMED' && <button onClick={() => doLive(a.id)}>开抢</button>}
                {a.status === 'LIVE' && <button onClick={() => doSettle(a.id)}>结算</button>}
                {a.status === 'SETTLING' && <button onClick={() => doClose(a.id)}>关闭</button>}
                <button
                  onClick={() => handlePlan(a.id)}
                  disabled={planning === a.id}
                  style={{ marginLeft: 4 }}
                >
                  {planning === a.id ? '规划中...' : 'AI 规划'}
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <div style={{ marginTop: 16, display: 'flex', gap: 8 }}>
        <button className="btn-refresh" onClick={refresh}>刷新</button>
        <button onClick={() => setShowCreate(!showCreate)}>
          {showCreate ? '收起' : '+ 创建活动'}
        </button>
      </div>

      {/* 创建活动表单 */}
      {showCreate && (
        <div className="create-form" style={{ marginTop: 16, padding: 16, background: 'var(--bg-card)', borderRadius: 8 }}>
          <h4>创建新活动</h4>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
            <label>活动名称<input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} placeholder="如：双12 数码场" /></label>
            <label>目标 GMV<input value={form.targetGmv} onChange={(e) => setForm({ ...form, targetGmv: e.target.value })} /></label>
            <label>目标 ROI<input value={form.targetRoi} onChange={(e) => setForm({ ...form, targetRoi: e.target.value })} /></label>
            <label>开始时间<input value={form.startAt} onChange={(e) => setForm({ ...form, startAt: e.target.value })} /></label>
            <label>结束时间<input value={form.endAt} onChange={(e) => setForm({ ...form, endAt: e.target.value })} /></label>
            <label>商品名<input value={form.skuName} onChange={(e) => setForm({ ...form, skuName: e.target.value })} placeholder="如：MacBook 秒杀款" /></label>
            <label>SKU 编码<input value={form.skuCode} onChange={(e) => setForm({ ...form, skuCode: e.target.value })} placeholder="如：SKU-MB14" /></label>
            <label>成本价<input value={form.costPrice} onChange={(e) => setForm({ ...form, costPrice: e.target.value })} /></label>
            <label>秒杀价<input value={form.seckillPrice} onChange={(e) => setForm({ ...form, seckillPrice: e.target.value })} /></label>
            <label>库存数量<input value={form.totalStock} onChange={(e) => setForm({ ...form, totalStock: e.target.value })} /></label>
          </div>
          <button onClick={handleCreate} style={{ marginTop: 12 }}>确认创建</button>
        </div>
      )}
    </div>
  );
}
