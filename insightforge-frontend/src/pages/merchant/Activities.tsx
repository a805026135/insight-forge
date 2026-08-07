import { useEffect, useState } from 'react';
import { activityApi, ActivityView, ReconcileItem } from '../../api';

export default function Activities() {
  const [list, setList] = useState<ActivityView[]>([]);
  const [loading, setLoading] = useState(true);
  const [items, setItems] = useState<Record<number, ReconcileItem[]>>({});
  const [passed, setPassed] = useState<Record<number, boolean>>({});
  const [msg, setMsg] = useState('');

  const refresh = async () => {
    setLoading(true);
    try {
      const data = await activityApi.list();
      setList(data);
      // 加载每个活动的对账状态
      for (const a of data) {
        try {
          const r = await activityApi.reconcile(a.id);
          setItems((prev) => ({ ...prev, [a.id]: r.items }));
          setPassed((prev) => ({ ...prev, [a.id]: r.passed }));
        } catch { /* 对账接口可能不可用（如 DRAFT 状态无数据），忽略 */ }
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

  const statusLabel = (s: string) => {
    const map: Record<string, string> = { DRAFT: '草稿', ARMED: '已上线', LIVE: '开抢中', SETTLING: '结算中', CLOSED: '已关闭' };
    return map[s] || s;
  };

  if (loading) return <div className="page"><h2>活动管理</h2><p>加载中…</p></div>;

  return (
    <div className="page">
      <h2>活动管理</h2>
      {msg && <div className="toast">{msg}</div>}
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
              <td>
                {a.status === 'DRAFT' && <button onClick={() => doArm(a.id)}>上线</button>}
                {a.status === 'ARMED' && <button onClick={() => doLive(a.id)}>开抢</button>}
                {a.status === 'LIVE' && <button onClick={() => doSettle(a.id)}>结算</button>}
                {a.status === 'SETTLING' && <button onClick={() => doClose(a.id)}>关闭</button>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <button className="btn-refresh" onClick={refresh} style={{ marginTop: 16 }}>
        刷新
      </button>
    </div>
  );
}
