import { useEffect, useState } from 'react';
import { Modal, Input } from 'antd';
import { useNavigate } from 'react-router-dom';
import QRCode from 'qrcode';
import { useAuth } from '../../auth/AuthContext';
import { getToken } from '../../auth/token';
import { orderApi, OrderView } from '../../api';

export default function OrderHistory() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const [orders, setOrders] = useState<OrderView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [msg, setMsg] = useState('');
  const [payOrder, setPayOrder] = useState<OrderView | null>(null);
  const [qrUrl, setQrUrl] = useState('');
  const [paying, setPaying] = useState(false);
  const [refundOrder, setRefundOrder] = useState<OrderView | null>(null);
  const [refundReason, setRefundReason] = useState('7\u5929\u65E0\u7406\u7531\u9000\u8D27');
  const [refunding, setRefunding] = useState(false);
  const [logisticsOrder, setLogisticsOrder] = useState<OrderView | null>(null);
  const [logistics, setLogistics] = useState<any>(null);
  const [logisticsLoading, setLogisticsLoading] = useState(false);
  const [reviewOrder, setReviewOrder] = useState<OrderView | null>(null);
  const [reviewRating, setReviewRating] = useState(5);
  const [reviewContent, setReviewContent] = useState('');
  const [reviewing, setReviewing] = useState(false);
  const [confirming, setConfirming] = useState<string | null>(null);

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
      SHIPPED: '待收货',
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

  useEffect(() => {
    if (!payOrder) return;
    const timer = setInterval(async () => {
      try {
        const data = await orderApi.list(user?.id ?? 0);
        const cur = data.find((x) => x.orderId === payOrder.orderId);
        if (cur && cur.status === 'PAY') {
          setMsg('\u2714 支付成功（模拟）');
          setPayOrder(null);
          await refresh();
        }
      } catch {
        // ignore
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
      setMsg('\u2714 支付成功（模拟）');
      setPayOrder(null);
      await refresh();
    } catch (e: any) {
      setMsg(e.message || '二维码生成失败');
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
        body: JSON.stringify({ orderId: refundOrder.orderId, reason: refundReason.trim() || '7\u5929\u65E0\u7406\u7531\u9000\u8D27' }),
      }).then((res) => res.json());
      if (r.code !== 0) throw new Error(r.message);
      setMsg(`\u2714 退款申请已提交（工单#${r.data.id}\uFF09\uFF0C\u5F85\u5BA1\u6838`);
      setRefundOrder(null);
    } catch (e: any) {
      setMsg(e.message || '提交失败');
    } finally {
      setRefunding(false);
    }
  };

  const canRefund = (s: string) => s === 'PAY' || s === 'SHIPPED' || s === 'DONE';

  const openLogistics = async (o: OrderView) => {
    setLogisticsOrder(o);
    setLogistics(null);
    setLogisticsLoading(true);
    try {
      const r = await fetch(`/api/buyer/orders/${o.orderId}/logistics`, {
        headers: { Authorization: `Bearer ${getToken()}` },
      }).then((res) => res.json());
      if (r.code !== 0) throw new Error(r.message);
      setLogistics(r.data);
    } catch (e: any) {
      setMsg(e.message || '物流查询失败');
      setLogistics(null);
    } finally {
      setLogisticsLoading(false);
    }
  };

  const confirmReceipt = async (o: OrderView) => {
    setConfirming(o.orderId);
    setMsg('');
    try {
      const r = await fetch(`/api/buyer/orders/${o.orderId}/confirm`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${getToken()}` },
      }).then((res) => res.json());
      if (r.code !== 0) throw new Error(r.message);
      setMsg('\u2714 已确认收货，交易完成，欢迎评价');
      await refresh();
    } catch (e: any) {
      setMsg(e.message || '确认收货失败');
    } finally {
      setConfirming(null);
    }
  };

  const openReview = (o: OrderView) => {
    setReviewOrder(o);
    setReviewRating(5);
    setReviewContent('');
  };

  const submitReview = async () => {
    if (!reviewOrder) return;
    setReviewing(true);
    setMsg('');
    try {
      const r = await fetch('/api/buyer/review', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${getToken()}` },
        body: JSON.stringify({
          orderId: reviewOrder.orderId,
          productId: reviewOrder.skuId,
          rating: reviewRating,
          content: reviewContent.trim(),
        }),
      }).then((res) => res.json());
      if (r.code !== 0) throw new Error(r.message);
      setMsg('\u2714 评价成功，感谢您的反馈');
      setReviewOrder(null);
      await refresh();
    } catch (e: any) {
      setMsg(e.message || '评价提交失败');
    } finally {
      setReviewing(false);
    }
  };

  return (
    <div className="order-page">
      <h2>我的订单</h2>
      <p className="welcome">欢迏，{user?.displayName}</p>
      {msg && <div className={`toast ${msg.startsWith('\u2714') ? 'success' : 'error'}`}>{msg}</div>}

      {loading && <p className="info-msg">加载中…</p>}
      {error && <p className="error-msg">{error}</p>}

      {!loading && !error && orders.length === 0 && (
        <div className="info-msg">
          <p>暂无订单。先去“限时秒杀”页下一单吧！</p>
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
                  <td>{o.price != null ? `\u00a5${o.price}` : '-'}</td>
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
                      {o.status === 'SHIPPED' && (
                        <button
                          onClick={() => confirmReceipt(o)}
                          disabled={confirming === o.orderId}
                        >
                          {confirming === o.orderId ? '确认中…' : '确认收货'}
                        </button>
                      )}
                      {(o.status === 'SHIPPED' || o.status === 'DONE') && (
                        <button onClick={() => openLogistics(o)}>查看物流</button>
                      )}
                      {o.status === 'DONE' && (
                        <button onClick={() => openReview(o)}>评价</button>
                      )}
                      {canRefund(o.status) && (
                        <button onClick={() => setRefundOrder(o)}>申请退款</button>
                      )}
                      {o.merchantId != null && (
                        <button
                          className="btn-contact"
                          onClick={() => navigate(`/user/support?merchant=${o.merchantId}`)}
                        >
                          联系商家客服
                        </button>
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
            <img src={qrUrl} alt="扫码支付（模拟）" style={{ width: 200, height: 200 }} />
            <p style={{ marginTop: 8, color: '#888' }}>
              请用手机扫码（演示环境不会真实扣款）。<br />
              <b style={{ color: '#333' }}>扫码本身不会完成支付</b>，请点击下方「我已支付，确认付款」按钮，才算支付成功。
            </p>
            {payOrder && (
              <p style={{ color: '#666' }}>
                订单 {payOrder.orderId}\uFF0C应付 \u00a5
                {payOrder.price != null ? (payOrder.price * (payOrder.qty ?? 1)).toLocaleString() : '-'}
              </p>
            )}
          </div>
        ) : (
          <p>加载中…</p>
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
          退款金额以订单实付为准，提交后按“待审核 → 已通过 → 已退款”流转，可在客服页“我的售后单”查看。
        </p>
        <Input.TextArea
          rows={3}
          value={refundReason}
          onChange={(e) => setRefundReason(e.target.value)}
          placeholder="退款原因"
        />
      </Modal>

      <Modal
        title={logisticsOrder ? `物流信息 - ${logisticsOrder.orderId}` : '物流信息'}
        open={logisticsOrder != null}
        onCancel={() => setLogisticsOrder(null)}
        footer={null}
      >
        {logisticsLoading ? (
          <p>加载中…</p>
        ) : logistics ? (
          <div>
            <p style={{ marginBottom: 8, color: '#666' }}>
              {logistics.carrier} 运单号：{logistics.trackingNo}
            </p>
            <div style={{ maxHeight: 320, overflowY: 'auto' }}>
              {(logistics.events ?? []).map((ev: any, idx: number) => (
                <div key={idx} style={{ display: 'flex', gap: 10, padding: '8px 0', borderBottom: '1px solid var(--border)' }}>
                  <div style={{ flex: 1 }}>
                    <div style={{ fontWeight: 600 }}>{ev.description}</div>
                    <div style={{ fontSize: 12, color: '#999' }}>{String(ev.eventTime).replace('T', ' ').slice(0, 19)}</div>
                  </div>
                </div>
              ))}
            </div>
          </div>
        ) : (
          <p>暂无物流信息</p>
        )}
      </Modal>

      <Modal
        title={reviewOrder ? `评价 - ${reviewOrder.productName}` : '评价商品'}
        open={reviewOrder != null}
        onCancel={() => setReviewOrder(null)}
        onOk={submitReview}
        okText="提交评价"
        cancelText="取消"
        confirmLoading={reviewing}
      >
        <div style={{ marginBottom: 12 }}>
          <span style={{ marginRight: 8 }}>评分：</span>
          {[1, 2, 3, 4, 5].map((n) => (
            <span
              key={n}
              onClick={() => setReviewRating(n)}
              style={{ cursor: 'pointer', fontSize: 22, color: n <= reviewRating ? '#f59e0b' : '#ccc' }}
            >
              \u2605
            </span>
          ))}
        </div>
        <Input.TextArea
          rows={3}
          placeholder="说说商品质量、使用体验…"
          value={reviewContent}
          maxLength={500}
          onChange={(e) => setReviewContent(e.target.value)}
        />
      </Modal>
    </div>
  );
}
