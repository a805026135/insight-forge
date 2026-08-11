import { useEffect, useState } from 'react';
import { Modal, Input } from 'antd';
import QRCode from 'qrcode';
import { useAuth } from '../../auth/AuthContext';
import { getToken } from '../../auth/token';
import { orderApi, OrderView } from '../../api';

export default function OrderHistory() {
  const { user } = useAuth();
  const [orders, setOrders] = useState<OrderView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [msg, setMsg] = useState('');
  const [payOrder, setPayOrder] = useState<OrderView | null>(null);
  const [qrUrl, setQrUrl] = useState('');
  const [paying, setPaying] = useState(false);
  const [refundOrder, setRefundOrder] = useState<OrderView | null>(null);
  const [refundReason, setRefundReason] = useState('7天无理由退货');
  const [refunding, setRefunding] = useState(false);

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
      CREATE_ORDER: '待支付',
      PAY: '已支付',
      SHIPPED: '已发货',
      DONE: '已完成',
      CANCEL: '已取消',
    };
    return map[s] || s;
  };

  const openPay = async (o: OrderView) => {
    setPayOrder(o);
    setQrUrl('');
    try {
      const r = await fetch(`/api/order/${o.orderId}/pay-request`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${getToken()}` },
      }).then((res) => res.json());
      if (r.code !== 0) throw new Error(r.message);
      const url = await QRCode.toDataURL(
        `${window.location.origin}/pay-confirm?token=${r.data.token}`,
        { width: 200, margin: 1 },
      );
      setQrUrl(url);
    } catch (e: any) {
      setMsg(e.message || '二维码生成失败');
    }
  };

  // 打开支付弹窗后轮询订单状态：手机端确认支付（PAY）后自动关闭并提示
  useEffect(() => {
    if (!payOrder) return;
    const timer = setInterval(async () => {
      try {
        const data = await orderApi.list(user?.id ?? 0);
        const cur = data.find((x) => x.orderId === payOrder.orderId);
        if (cur && cur.status === 'PAY') {
          setMsg('✔ 支付成功（手机端已确认）');
          setPayOrder(null);
          await refresh();
        }
      } catch {
        // 忽略轮询错误
      }
    }, 2000);
    return () => clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [payOrder]);

  const confirmPay = async () => {
    if (!payOrder) return;
    setPaying(true);
    setMsg('');
    try {
      await orderApi.pay(payOrder.orderId);
      setMsg('✔ 支付成功（模拟）');
      setPayOrder(null);
      await refresh();
    } catch (e: any) {
      setMsg(e.message || '支付失败');
    } finally {
      setPaying(false);
    }
  };

  const submitRefund = async () => {
    if (!refundOrder) return;
    setRefunding(true);
    setMsg('');
    try {
      const r = await fetch('/api/buyer/after-sale/apply', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${getToken()}` },
        body: JSON.stringify({ orderId: refundOrder.orderId, reason: refundReason.trim() || '7天无理由退货' }),
      }).then((res) => res.json());
      if (r.code !== 0) throw new Error(r.message);
      setMsg(`✔ 退款申请已提交（工单 #${r.data.id}），待审核`);
      setRefundOrder(null);
    } catch (e: any) {
      setMsg(e.message || '提交失败');
    } finally {
      setRefunding(false);
    }
  };

  const canRefund = (s: string) => s === 'PAY' || s === 'SHIPPED' || s === 'DONE';

  return (
    <div className="order-page">
      <h2>我的订单</h2>
      <p className="welcome">欢迎，{user?.displayName}</p>
      {msg && <div className={`toast ${msg.startsWith('✔') ? 'success' : 'error'}`}>{msg}</div>}

      {loading && <p className="info-msg">加载中…</p>}
      {error && <p className="error-msg">{error}</p>}

      {!loading && !error && orders.length === 0 && (
        <div className="info-msg">
          <p>暂无订单。先去"限时秒杀"页下一单吧！</p>
        </div>
      )}

      {orders.length > 0 && (
        <div className="order-table-wrap">
          <table className="order-table">
            <thead>
              <tr>
                <th>商品</th>
                <th>活动</th>
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
                  <td className="order-product">
                    <img
                      className="order-thumb"
                      src={o.productImage}
                      alt=""
                      onError={(e) => {
                        (e.target as HTMLImageElement).style.visibility = 'hidden';
                      }}
                    />
                    <div>
                      <div className="order-product-name">{o.productName}</div>
                      <div className="order-product-sku">#{o.orderId.slice(-6)}</div>
                    </div>
                  </td>
                  <td>{o.activityName}</td>
                  <td>{o.price != null ? `¥${o.price}` : '—'}</td>
                  <td>{o.qty}</td>
                  <td className={`status-${o.status?.toLowerCase()}`}>{statusLabel(o.status)}</td>
                  <td>{o.createdAt?.replace('T', ' ').slice(0, 19)}</td>
                  <td>
                    <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                      {o.status === 'CREATE_ORDER' && (
                        <>
                          <button
                            className="danger"
                            onClick={async () => {
                              await orderApi.cancel(o.orderId);
                              await refresh();
                            }}
                          >
                            取消
                          </button>
                          <button onClick={() => openPay(o)}>扫码支付</button>
                        </>
                      )}
                      {o.status === 'PAY' && (
                        <button
                          onClick={async () => {
                            await orderApi.pay(o.orderId);
                            await refresh();
                          }}
                        >
                          确认完成
                        </button>
                      )}
                      {canRefund(o.status) && (
                        <button onClick={() => setRefundOrder(o)}>申请退款</button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {orders.length > 0 && (
        <button className="btn-refresh" onClick={refresh} style={{ marginTop: 16 }}>
          刷新
        </button>
      )}

      <Modal
        title="扫码支付（模拟）"
        open={payOrder != null}
        onCancel={() => setPayOrder(null)}
        onOk={confirmPay}
        okText="我已支付，确认付款"
        cancelText="取消"
        confirmLoading={paying}
      >
        {qrUrl ? (
          <div style={{ textAlign: 'center', padding: 8 }}>
            <img src={qrUrl} alt="支付二维码" style={{ width: 200, height: 200 }} />
        <p style={{ marginTop: 8, color: '#888' }}>
              请用手机扫码（演示环境不会真实扣款）。<br />
              <b style={{ color: '#333' }}>扫码本身不会完成支付</b>，请点击下方「我已支付，确认付款」按钮，才算支付成功。
            </p>
            {payOrder && (
              <p style={{ color: '#666' }}>
                订单 {payOrder.orderId}，应付 ¥
                {payOrder.price != null ? (payOrder.price * (payOrder.qty ?? 1)).toLocaleString() : '-'}
              </p>
            )}
          </div>
        ) : (
          <p>二维码生成中…</p>
        )}
      </Modal>

      <Modal
        title={`申请退款 - 订单 ${refundOrder?.orderId ?? ''}`}
        open={refundOrder != null}
        onCancel={() => setRefundOrder(null)}
        onOk={submitRefund}
        okText="提交退款申请"
        cancelText="取消"
        confirmLoading={refunding}
      >
        <p style={{ marginBottom: 12, color: '#888' }}>
          退款金额以订单实付为准，提交后按"待审核 → 已通过 → 已退款"流转，可在客服页"我的售后单"查看。
        </p>
        <Input.TextArea
          rows={3}
          value={refundReason}
          onChange={(e) => setRefundReason(e.target.value)}
          placeholder="退款原因"
        />
      </Modal>
    </div>
  );
}
