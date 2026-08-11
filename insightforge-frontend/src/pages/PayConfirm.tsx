import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';

export default function PayConfirm() {
  const [params] = useSearchParams();
  const token = params.get('token') ?? '';
  const [info, setInfo] = useState<any>(null);
  const [msg, setMsg] = useState('');
  const [done, setDone] = useState(false);
  const [confirming, setConfirming] = useState(false);

  useEffect(() => {
    if (!token) {
      setMsg('缺少支付令牌');
      return;
    }
    fetch(`/api/order/pay-info?token=${encodeURIComponent(token)}`)
      .then((r) => r.json())
      .then((r) => {
        if (r.code !== 0) throw new Error(r.message);
        setInfo(r.data);
      })
      .catch((e: any) => setMsg(e.message || '加载失败'));
  }, [token]);

  const confirm = async () => {
    setConfirming(true);
    setMsg('');
    try {
      const r = await fetch('/api/order/pay-confirm', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token }),
      }).then((res) => res.json());
      if (r.code !== 0) throw new Error(r.message);
      setDone(true);
    } catch (e: any) {
      setMsg(e.message || '确认失败');
    } finally {
      setConfirming(false);
    }
  };

  return (
    <div className="checkout-page" style={{ maxWidth: 420, margin: '48px auto' }}>
      <h2 style={{ textAlign: 'center' }}>扫码支付确认</h2>
      {msg && <div className="toast error">{msg}</div>}
      {done ? (
        <div className="checkout-section" style={{ textAlign: 'center' }}>
          <p style={{ fontSize: 22, color: '#34c77b' }}>✔ 支付成功（模拟）</p>
          <p style={{ color: '#888' }}>可返回电脑端查看订单状态。</p>
        </div>
      ) : info ? (
        <div className="checkout-section">
          <p>
            商品：<b>{info.productName}</b>
          </p>
          <p>订单号：{info.orderId}</p>
          <p>
            金额：<b style={{ color: '#ef4444' }}>¥{info.amount ?? '-'}</b>
          </p>
          <p style={{ color: '#888' }}>
            点击下方按钮确认后，订单将标记为已支付（演示环境不产生真实扣款）。
          </p>
          <button
            className="btn-primary"
            disabled={confirming || info.status !== 'CREATE_ORDER'}
            onClick={confirm}
            style={{ width: '100%', marginTop: 12 }}
          >
            {info.status !== 'CREATE_ORDER'
              ? '该订单已处理'
              : confirming
              ? '确认中…'
              : '确认支付'}
          </button>
        </div>
      ) : (
        <p style={{ textAlign: 'center' }}>加载中…</p>
      )}
    </div>
  );
}
