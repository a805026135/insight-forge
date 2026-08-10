import { useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { userApi, UserAddress, CartItemView } from '../../api';

export default function CheckoutPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const cartItemIds: number[] = (location.state as any)?.cartItemIds ?? [];
  const [addresses, setAddresses] = useState<UserAddress[]>([]);
  const [items, setItems] = useState<CartItemView[]>([]);
  const [selectedAddressId, setSelectedAddressId] = useState<number | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [msg, setMsg] = useState('');

  useEffect(() => {
    userApi.addresses().then((a) => {
      setAddresses(a);
      const def = a.find((x) => x.isDefault) ?? a[0];
      setSelectedAddressId(def ? def.id : null);
    }).catch((e) => setMsg(e.message));
    userApi.cart().then((c) => {
      setItems(c.filter((i) => cartItemIds.includes(i.id)));
    }).catch((e) => setMsg(e.message));
  }, []);

  const total = items.reduce((sum, i) => sum + (i.price ?? 0) * i.qty, 0);

  const submit = async () => {
    if (!selectedAddressId) {
      setMsg('请先选择或添加收货地址');
      return;
    }
    setSubmitting(true);
    setMsg('');
    try {
      const orderId = await userApi.checkout(selectedAddressId, cartItemIds);
      navigate('/user/orders', { state: { orderId } });
    } catch (e: any) {
      setMsg(e.message || '下单失败');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="checkout-page">
      <h2>确认订单</h2>
      {msg && <div className="toast error">{msg}</div>}
      <section className="checkout-section">
        <h3>收货地址</h3>
        {addresses.length === 0 && (
          <div className="info-msg">
            还没有收货地址，<button onClick={() => navigate('/user/profile')}>去添加</button>
          </div>
        )}
        <div className="address-list">
          {addresses.map((a) => (
            <div
              key={a.id}
              className={`address-card ${selectedAddressId === a.id ? 'active' : ''}`}
              onClick={() => setSelectedAddressId(a.id)}
            >
              <div className="address-line1">
                {a.receiver} {a.phone}
                {a.isDefault && <span className="tag-default">默认</span>}
              </div>
              <div className="address-line2">
                {a.province} {a.city} {a.district} {a.detail}
              </div>
            </div>
          ))}
        </div>
        <button onClick={() => navigate('/user/profile')} className="btn-secondary">
          + 管理地址
        </button>
      </section>

      <section className="checkout-section">
        <h3>商品清单</h3>
        {items.map((i) => (
          <div key={i.id} className="checkout-item">
            <img src={i.productImage} alt="" />
            <div>
              <div className="checkout-name">{i.productName}</div>
              <div className="checkout-merchant">🏪 {i.merchantName}</div>
            </div>
            <div className="checkout-line">¥{i.price} × {i.qty}</div>
          </div>
        ))}
      </section>

      <div className="checkout-footer">
        <span>应付：</span>
        <span className="cart-total">¥{total.toLocaleString()}</span>
        <button className="btn-primary" disabled={submitting} onClick={submit}>
          {submitting ? '提交中…' : '提交订单'}
        </button>
      </div>
    </div>
  );
}
