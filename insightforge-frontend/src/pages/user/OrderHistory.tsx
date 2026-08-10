import { useEffect, useState } from 'react';
import { useAuth } from '../../auth/AuthContext';
import { orderApi, OrderView } from '../../api';

export default function OrderHistory() {
  const { user } = useAuth();
  const [orders, setOrders] = useState<OrderView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const refresh = async () => {
    if (!user?.id) return;
    setLoading(true);
    setError('');
    try {
      const data = await orderApi.list(user.id);
      setOrders(data);
    } catch (e: any) {
      setError(e.message || '加载订单失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    refresh();
  }, []);

  const statusLabel = (s: string) => {
    const map: Record<string, string> = {
      CREATE_ORDER: '已下单',
      PAY: '已支付',
      DONE: '已完成',
      CANCEL: '已取消',
    };
    return map[s] || s;
  };

  return (
    <div className="order-page">
      <h2>我的订单</h2>
      <p className="welcome">欢迎，{user?.displayName}！</p>

      {loading && <p className="info-msg">加载中…</p>}
      {error && <p className="error-msg">{error}</p>}

      {!loading && !error && orders.length === 0 && (
        <div className="info-msg">
          <p>暂无订单。先去「秒杀抢购」页下一单吧！</p>
        </div>
      )}

      {orders.length > 0 && (
        <table className="order-table">
          <thead>
            <tr>
              <th>订单号</th>
              <th>活动</th>
              <th>SKU</th>
              <th>单价</th>
              <th>数量</th>
              <th>状态</th>
              <th>时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {orders.map((o) => (
              <tr key={o.orderId}>
                <td title={o.orderId}>{o.orderId.slice(0, 12)}…</td>
                <td>{o.activityId}</td>
                <td>{o.skuId}</td>
                <td>{o.price != null ? `¥${o.price}` : '—'}</td>
                <td>{o.qty}</td>
                <td className={`status-${o.status?.toLowerCase()}`}>{statusLabel(o.status)}</td>
                <td>{o.createdAt?.replace('T', ' ').slice(0, 19)}</td>
                <td>
                  {o.status === 'CREATE_ORDER' && (
                    <>
                      <button onClick={async () => {
                        await orderApi.cancel(o.orderId);
                        await refresh();
                      }}>取消</button>
                      <button onClick={async () => {
                        await orderApi.pay(o.orderId);
                        await refresh();
                      }}>支付</button>
                    </>
                  )}
                  {o.status === 'PAY' && (
                    <button onClick={async () => {
                      await orderApi.pay(o.orderId);
                      await refresh();
                    }}>确认完成</button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {orders.length > 0 && (
        <button className="btn-refresh" onClick={refresh} style={{ marginTop: 16 }}>
          刷新
        </button>
      )}
    </div>
  );
}
