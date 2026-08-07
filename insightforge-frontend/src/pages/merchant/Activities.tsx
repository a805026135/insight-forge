import { useEffect, useState } from 'react';
import { activityApi, ReconcileItem } from '../../api';

interface ActivityInfo {
  id: number;
  name: string;
  status: string;
}

const ACTIVITIES: ActivityInfo[] = [
  { id: 1, name: '双11 美妆数码场', status: 'DRAFT' },
];

export default function Activities() {
  const [list, setList] = useState<ActivityInfo[]>([]);
  const [items, setItems] = useState<Record<number, ReconcileItem[]>>({});
  const [passed, setPassed] = useState<Record<number, boolean>>({});
  const [msg, setMsg] = useState('');

  useEffect(() => {
    setList(ACTIVITIES);
    refreshReconcile();
  }, []);

  const refreshReconcile = async () => {
    for (const a of ACTIVITIES) {
      try {
        const r = await activityApi.reconcile(a.id);
        setItems((prev) => ({ ...prev, [a.id]: r.items }));
        setPassed((prev) => ({ ...prev, [a.id]: r.passed }));
      } catch {}
    }
  };

  const doArm = async (id: number) => {
    try {
      const r = await activityApi.arm(id);
      setList((prev) => prev.map((a) => (a.id === id ? { ...a, status: 'ARMED' } : a)));
      setItems((prev) => ({ ...prev, [id]: r.items }));
      setPassed((prev) => ({ ...prev, [id]: r.passed }));
      setMsg('活动已上线（ARMED）');
    } catch (ex: any) { setMsg(ex.message); }
  };

  const doLive = async (id: number) => {
    try {
      await activityApi.live(id);
      setList((prev) => prev.map((a) => (a.id === id ? { ...a, status: 'LIVE' } : a)));
      setMsg('活动已开抢（LIVE）');
    } catch (ex: any) { setMsg(ex.message); }
  };

  const doSettle = async (id: number) => {
    try {
      const r = await activityApi.settle(id);
      setList((prev) => prev.map((a) => (a.id === id ? { ...a, status: 'SETTLING' } : a)));
      setItems((prev) => ({ ...prev, [id]: r.items }));
      setPassed((prev) => ({ ...prev, [id]: r.passed }));
      setMsg('活动已结算');
    } catch (ex: any) { setMsg(ex.message); }
  };

  const doClose = async (id: number) => {
    try {
      await activityApi.close(id);
      setList((prev) => prev.map((a) => (a.id === id ? { ...a, status: 'CLOSED' } : a)));
      setMsg('活动已关闭');
    } catch (ex: any) { setMsg(ex.message); }
  };

  return (
    <div className="page">
      <h2>活动管理</h2>
      {msg && <div className="toast">{msg}</div>}
      <table className="order-table">
        <thead>
          <tr>
            <th>ID</th>
            <th>名称</th>
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
              <td className={`status-${a.status?.toLowerCase()}`}>{a.status}</td>
              <td>
                {passed[a.id] !== undefined
                  ? (passed[a.id] ? '✅ 通过' : '❌ 异常')
                  : '-'}
              </td>
              <td style={{ fontSize: 12, color: 'var(--text-muted)' }}>
                {items[a.id]?.map((it) => (
                  <div key={it.dimension}>
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
    </div>
  );
}
