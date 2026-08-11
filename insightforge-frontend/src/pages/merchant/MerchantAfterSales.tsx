import { useEffect, useState } from 'react';
import { getToken } from '../../auth/token';

interface AfterSale {
  id: number;
  userId: number;
  orderId: string;
  productName: string | null;
  qty: number | null;
  amount: number | null;
  reason: string;
  appealReason: string | null;
  status: string;
  note: string | null;
  createdAt: string;
  updatedAt: string;
}

const STATUS_LABEL: Record<string, string> = {
  APPLIED: '待审核',
  APPROVED: '已通过',
  REFUNDING: '退款中',
  REFUNDED: '已退款',
  REJECTED: '已驳回',
  APPEAL: '申诉中',
  CANCELLED: '已取消',
};

const TABS = ['ALL', 'APPLIED', 'APPEAL', 'APPROVED', 'REJECTED', 'REFUNDED', 'CANCELLED'];

export default function MerchantAfterSales() {
  const [tab, setTab] = useState('ALL');
  const [list, setList] = useState<AfterSale[]>([]);
  const [msg, setMsg] = useState('');
  const [busy, setBusy] = useState<number | null>(null);

  const load = async () => {
    try {
      const qs = tab === 'ALL' ? '' : `?status=${tab}`;
      const r = await fetch(`/api/merchant/after-sales${qs}`, {
        headers: { Authorization: `Bearer ${getToken()}` },
      }).then((res) => res.json());
      if (r.code !== 0) throw new Error(r.message);
      setList(r.data ?? []);
    } catch (e: any) {
      setMsg(e.message || '加载失败');
    }
  };

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab]);

  const act = async (id: number, url: string, noteText?: string) => {
    setBusy(id);
    setMsg('');
    try {
      const body = noteText != null ? JSON.stringify({ note: noteText }) : null;
      const r = await fetch(url, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${getToken()}`,
        },
        body,
      }).then((res) => res.json());
      if (r.code !== 0) throw new Error(r.message);
      setMsg('操作成功');
      await load();
    } catch (e: any) {
      setMsg(e.message || '操作失败');
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className="page">
      <div className="page-head">
        <h2>售后处理</h2>
        <span className="badge badge-live">按店铺隔离</span>
      </div>
      {msg && <div className={`toast ${msg.includes('成功') ? 'success' : 'error'}`}>{msg}</div>}
      <div className="activity-tab" style={{ marginBottom: 12 }}>
        {TABS.map((s) => (
          <button
            key={s}
            className={tab === s ? 'activity-tab active' : ''}
            onClick={() => setTab(s)}
          >
            {s === 'ALL' ? '全部' : STATUS_LABEL[s] ?? s}
          </button>
        ))}
      </div>
      <div className="order-table-wrap">
        <table className="data-table">
          <thead>
            <tr>
              <th>#</th>
              <th>订单</th>
              <th>商品</th>
              <th>金额</th>
              <th>原因</th>
              <th>申诉</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {list.map((t) => (
              <tr key={t.id}>
                <td>#{t.id}</td>
                <td>{t.orderId}</td>
                <td style={{ maxWidth: 200 }}>{t.productName ?? '-'}</td>
                <td>¥{t.amount ?? '-'}</td>
                <td style={{ maxWidth: 180 }}>{t.reason}</td>
                <td style={{ maxWidth: 180 }}>
                  {t.appealReason ? (
                    <span title={t.note ?? ''}>{t.appealReason}</span>
                  ) : (
                    <span style={{ color: '#aaa' }}>-</span>
                  )}
                </td>
                <td>
                  <span className={`badge ${t.status === 'APPEAL' ? 'badge-live' : ''}`}>
                    {STATUS_LABEL[t.status] ?? t.status}
                  </span>
                  {t.note && <div style={{ fontSize: 11, color: '#999' }}>{t.note}</div>}
                </td>
                <td>
                  <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', maxWidth: 260 }}>
                    {t.status === 'APPLIED' && (
                      <>
                        <button
                          className="btn btn-primary"
                          disabled={busy === t.id}
                          onClick={() => act(t.id, `/api/merchant/after-sales/${t.id}/review?approve=true`)}
                        >
                          同意
                        </button>
                        <button
                          className="btn btn-danger"
                          disabled={busy === t.id}
                          onClick={() => act(t.id, `/api/merchant/after-sales/${t.id}/review?approve=false`, '与用户协商后驳回')}
                        >
                          驳回
                        </button>
                      </>
                    )}
                    {t.status === 'APPEAL' && (
                      <>
                        <button
                          className="btn btn-primary"
                          disabled={busy === t.id}
                          onClick={() => act(t.id, `/api/merchant/after-sales/${t.id}/appeal-review?approve=true`)}
                        >
                          申诉成立
                        </button>
                        <button
                          className="btn btn-danger"
                          disabled={busy === t.id}
                          onClick={() => act(t.id, `/api/merchant/after-sales/${t.id}/appeal-review?approve=false`, '证据不足，维持驳回')}
                        >
                          维持驳回
                        </button>
                      </>
                    )}
                    {t.status === 'APPROVED' && (
                      <button
                        className="btn btn-primary"
                        disabled={busy === t.id}
                        onClick={() => act(t.id, `/api/merchant/after-sales/${t.id}/refund`)}
                      >
                        退款到账
                      </button>
                    )}
                  </div>
                </td>
              </tr>
            ))}
            {list.length === 0 && (
              <tr>
                <td colSpan={8}>暂无售后工单</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
