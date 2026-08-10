import { useEffect, useState } from 'react';
import { getToken } from '../../auth/token';
import { OrderDetailView } from '../../api';

const STATUS_LABEL: Record<string, string> = {
  CREATE_ORDER: '待支付',
  PAY: '待发货',
  SHIPPED: '待收货',
  DONE: '已完成',
  CANCEL: '已取消',
};

export default function MerchantOrders() {
  const [orders, setOrders] = useState<OrderDetailView[]>([]);
  const [msg, setMsg] = useState('');

  const load = async () => {
    try {
      const resp = await fetch('/api/merchant/orders', {
        headers: { Authorization: `Bearer ${getToken()}` },
      });
      const json = await resp.json();
      if (json.code !== 0) throw new Error(json.message);
      setOrders(json.data);
    } catch (e: any) {
      setMsg(e.message);
    }
  };

  useEffect(() => {
    load();
  }, []);

  const ship = async (orderId: string) => {
    setMsg('');
    try {
      const resp = await fetch(`/api/merchant/orders/${orderId}/ship`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${getToken()}` },
      });
      const json = await resp.json();
      if (json.code !== 0) throw new Error(json.message);
      await load();
    } catch (e: any) {
      setMsg(e.message);
    }
  };

  return (
    <div className="page">
      <div className="page-head">
        <h2>订单发货</h2>
        <button onClick={load}>刷新</button>
      </div>
      {msg && <div className="toast error">{msg}</div>}
      {orders.length === 0 && !msg && <div className="info-msg">暂无订单</div>}
      {orders.map((o) => (
        <div key={o.orderId} className="order-card">
          <div className="order-card-head">
            <span>订单号 {o.orderId}</span>
            <span className={`status-${o.status?.toLowerCase()}`}>
              {STATUS_LABEL[o.status] ?? o.status}
            </span>
          </div>
          {o.items.map((it, idx) => (
            <div key={idx} className="order-card-item">
              <img src={it.productImage} alt="" />
              <div className="oc-name">
                {it.productName}
                <div className="oc-merchant">数量 {it.qty} × ¥{it.price}</div>
              </div>
            </div>
          ))}
          <div className="order-card-foot">
            <span>
              {o.receiver} {o.phone} · {o.addressSnapshot ?? ''}
            </span>
            {o.status === 'PAY' && (
              <button onClick={() => ship(o.orderId)}>发货</button>
            )}
          </div>
        </div>
      ))}
    </div>
  );
}
