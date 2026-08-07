import { mockOrders } from '../../mock/data';

const statusMap: Record<string, { label: string; cls: string }> = {
  PAY: { label: '已支付', cls: 'badge-success' },
  DONE: { label: '已完成', cls: 'badge-live' },
  CANCEL: { label: '已取消', cls: 'badge-closed' },
  CREATE_ORDER: { label: '待支付', cls: 'badge-armed' },
};

export default function OrderHistory() {
  const orders = mockOrders;

  return (
    <div>
      <div className="page-header">
        <h2>📋 我的订单</h2>
        <p>共 {orders.length} 笔订单</p>
      </div>

      <div className="metrics-grid">
        <div className="metric-tile green">
          <div className="label">总消费</div>
          <div className="value">¥{orders.filter(o => o.status !== 'CANCEL').reduce((a, o) => a + o.amount, 0).toLocaleString()}</div>
        </div>
        <div className="metric-tile cyan">
          <div className="label">成功订单</div>
          <div className="value">{orders.filter(o => o.status !== 'CANCEL').length}</div>
        </div>
        <div className="metric-tile amber">
          <div className="label">退款/取消</div>
          <div className="value">{orders.filter(o => o.status === 'CANCEL').length}</div>
        </div>
      </div>

      <div className="card">
        <table className="data-table">
          <thead>
            <tr>
              <th>订单号</th>
              <th>商品</th>
              <th>金额</th>
              <th>状态</th>
              <th>时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {orders.map(order => {
              const st = statusMap[order.status] || { label: order.status, cls: '' };
              return (
                <tr key={order.orderId}>
                  <td style={{ fontFamily: 'monospace', fontSize: 12 }}>{order.orderId}</td>
                  <td>{order.skuName}</td>
                  <td style={{ fontWeight: 600, color: 'var(--red)' }}>¥{order.amount.toLocaleString()}</td>
                  <td><span className={`badge ${st.cls}`}>{st.label}</span></td>
                  <td style={{ color: 'var(--text-muted)', fontSize: 12 }}>{order.createdAt}</td>
                  <td>
                    {order.status === 'CREATE_ORDER' && <button className="btn btn-danger" style={{ padding: '4px 10px', fontSize: 12 }}>取消</button>}
                    {order.status === 'PAY' && <button className="btn" style={{ padding: '4px 10px', fontSize: 12 }}>申请退款</button>}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}
