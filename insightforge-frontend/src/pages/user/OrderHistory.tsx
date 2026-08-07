import { useEffect, useState } from 'react';
import { useAuth } from '../../auth/AuthContext';
import { orderApi } from '../../api';

interface Order {
  orderId: string;
  activityId: number;
  skuId: number;
  qty: number;
  status: string;
  createdAt: string;
}

export default function OrderHistory() {
  const { user } = useAuth();
  const [orders, setOrders] = useState<Order[]>([]);
  const [msg, setMsg] = useState('');

  const refresh = async () => {
    // 后端目前没有"查我的订单"接口，先通过已知 orderId 模式查询
    // 实际项目中应有 GET /api/order?userId= 接口
    setMsg('订单列表功能需后端补充 GET /api/order?userId= 接口。');
    setMsg('当前支持：抢购后在秒杀页直接取消订单，或通过命令行 curl 操作。');
  };

  useEffect(() => {
    refresh();
  }, []);

  return (
    <div className="order-page">
      <h2>我的订单</h2>
      <p className="welcome">欢迎，{user?.displayName}！</p>
      <div className="info-msg">
        <p>{msg}</p>
        {orders.length === 0 && (
          <p className="hint">
            提示：先在「秒杀抢购」页下单，然后在页面底部可以看到订单号并操作取消/支付。
          </p>
        )}
      </div>
      {orders.length > 0 && (
        <table className="order-table">
          <thead>
            <tr>
              <th>订单号</th>
              <th>SKU</th>
              <th>数量</th>
              <th>状态</th>
              <th>时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {orders.map((o) => (
              <tr key={o.orderId}>
                <td>{o.orderId}</td>
                <td>{o.skuId}</td>
                <td>{o.qty}</td>
                <td className={`status-${o.status?.toLowerCase()}`}>{o.status}</td>
                <td>{o.createdAt}</td>
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
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
