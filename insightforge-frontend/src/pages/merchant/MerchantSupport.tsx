import { useEffect, useState } from 'react';
import { getToken } from '../../auth/token';

interface Ticket {
  id: number;
  userId: number;
  question: string;
  answer: string | null;
  status: string;
  createdAt: string;
}

const STATUS_LABEL: Record<string, string> = {
  OPEN: '待回复',
  REPLIED: '已回复',
  CLOSED: '已关闭',
};

export default function MerchantSupport() {
  const [tickets, setTickets] = useState<Ticket[]>([]);
  const [msg, setMsg] = useState('');
  const [replies, setReplies] = useState<Record<number, string>>({});
  const [replying, setReplying] = useState<number | null>(null);
  const [tab, setTab] = useState('OPEN');

  const load = async () => {
    try {
      const r = await fetch(`/api/merchant/support-tickets?status=${tab}`, {
        headers: { Authorization: `Bearer ${getToken()}` },
      }).then((res) => res.json());
      if (r.code !== 0) throw new Error(r.message);
      setTickets(r.data ?? []);
    } catch (e: any) {
      setMsg(e.message || '加载失败');
    }
  };

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab]);

  const reply = async (t: Ticket) => {
    const answer = (replies[t.id] ?? '').trim();
    if (!answer) {
      setMsg('回复内容不能为空');
      return;
    }
    setReplying(t.id);
    setMsg('');
    try {
      const r = await fetch(`/api/merchant/support-tickets/${t.id}/reply`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${getToken()}` },
        body: JSON.stringify({ answer }),
      }).then((res) => res.json());
      if (r.code !== 0) throw new Error(r.message);
      setMsg('✔ 已回复买家');
      load();
    } catch (e: any) {
      setMsg(e.message || '回复失败');
    } finally {
      setReplying(null);
    }
  };

  return (
    <div className="agent-workbench">
      <div className="page-head">
        <h2>售后咨询</h2>
        <span className="badge badge-live">买家转人工工单</span>
      </div>
      {msg && <div className={`toast ${msg.startsWith('✔') ? 'success' : 'error'}`}>{msg}</div>}
      <div className="activity-tab" style={{ marginBottom: 12 }}>
        {['OPEN', 'REPLIED', 'CLOSED'].map((s) => (
          <button key={s} className={tab === s ? 'activity-tab active' : ''} onClick={() => setTab(s)}>
            {STATUS_LABEL[s]}
          </button>
        ))}
      </div>
      <section className="assistant-panel">
        <table className="data-table">
          <thead>
            <tr>
              <th>#</th>
              <th>买家ID</th>
              <th>问题</th>
              <th>商家回复</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {tickets.map((t) => (
              <tr key={t.id}>
                <td>#{t.id}</td>
                <td>用户#{t.userId}</td>
                <td style={{ maxWidth: 280 }}>{t.question}</td>
                <td style={{ maxWidth: 260 }}>{t.answer ?? '—'}</td>
                <td><span className="badge">{STATUS_LABEL[t.status] ?? t.status}</span></td>
                <td>
                  {t.status !== 'CLOSED' && (
                    <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
                      <input
                        style={{ width: 180 }}
                        placeholder="回复买家…"
                        value={replies[t.id] ?? ''}
                        onChange={(e) => setReplies((p) => ({ ...p, [t.id]: e.target.value }))}
                      />
                      <button className="btn btn-primary" disabled={replying === t.id} onClick={() => reply(t)}>
                        {replying === t.id ? '发送中…' : '回复'}
                      </button>
                    </div>
                  )}
                </td>
              </tr>
            ))}
            {tickets.length === 0 && <tr><td colSpan={6}>暂无{tickets.length ? '' : '待'}处理工单</td></tr>}
          </tbody>
        </table>
      </section>
    </div>
  );
}
