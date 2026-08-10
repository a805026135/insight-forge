import { useEffect, useState } from 'react';
import { userApi, UserAddress, OrderDetailView } from '../../api';

const STATUS_LABEL: Record<string, string> = {
  CREATE_ORDER: '待支付',
  PAY: '待发货',
  SHIPPED: '待收货',
  DONE: '已完成',
  CANCEL: '已取消',
};

export default function ProfilePage() {
  const [tab, setTab] = useState<'orders' | 'addresses'>('orders');
  const [orders, setOrders] = useState<OrderDetailView[]>([]);
  const [addresses, setAddresses] = useState<UserAddress[]>([]);
  const [statusFilter, setStatusFilter] = useState('');
  const [msg, setMsg] = useState('');
  const [form, setForm] = useState({
    receiver: '',
    phone: '',
    province: '',
    city: '',
    district: '',
    detail: '',
    isDefault: false,
  });
  const [editing, setEditing] = useState<number | null>(null);
  const [showForm, setShowForm] = useState(false);

  const loadOrders = async () => {
    try {
      const data = await userApi.orders();
      setOrders(data);
    } catch (e: any) {
      setMsg(e.message);
    }
  };

  const loadAddresses = async () => {
    try {
      const data = await userApi.addresses();
      setAddresses(data);
    } catch (e: any) {
      setMsg(e.message);
    }
  };

  useEffect(() => {
    loadOrders();
    loadAddresses();
  }, []);

  const filtered = statusFilter ? orders.filter((o) => o.status === statusFilter) : orders;

  const doOrderAction = async (orderId: string, action: 'pay' | 'cancel' | 'confirm') => {
    setMsg('');
    try {
      if (action === 'pay') await userApi.pay(orderId);
      if (action === 'cancel') await userApi.cancel(orderId);
      if (action === 'confirm') await userApi.confirm(orderId);
      await loadOrders();
    } catch (e: any) {
      setMsg(e.message);
    }
  };

  const submitAddress = async () => {
    setMsg('');
    try {
      if (editing != null) {
        await userApi.updateAddress(editing, form);
      } else {
        await userApi.createAddress(form);
      }
      setShowForm(false);
      setEditing(null);
      setForm({ receiver: '', phone: '', province: '', city: '', district: '', detail: '', isDefault: false });
      await loadAddresses();
    } catch (e: any) {
      setMsg(e.message);
    }
  };

  const startEdit = (a: UserAddress) => {
    setEditing(a.id);
    setForm({
      receiver: a.receiver,
      phone: a.phone,
      province: a.province,
      city: a.city,
      district: a.district,
      detail: a.detail,
      isDefault: a.isDefault,
    });
    setShowForm(true);
  };

  return (
    <div className="profile-page">
      <div className="profile-tabs">
        <button className={tab === 'orders' ? 'active' : ''} onClick={() => setTab('orders')}>
          我的订单
        </button>
        <button className={tab === 'addresses' ? 'active' : ''} onClick={() => setTab('addresses')}>
          收货地址
        </button>
      </div>
      {msg && <div className="toast error">{msg}</div>}

      {tab === 'orders' && (
        <>
          <div className="order-filters">
            {['', 'CREATE_ORDER', 'PAY', 'SHIPPED', 'DONE', 'CANCEL'].map((s) => (
              <button
                key={s}
                className={statusFilter === s ? 'active' : ''}
                onClick={() => setStatusFilter(s)}
              >
                {s === '' ? '全部' : STATUS_LABEL[s]}
              </button>
            ))}
          </div>
          {filtered.length === 0 && <div className="info-msg">暂无订单</div>}
          {filtered.map((o) => (
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
                    <div className="oc-merchant">🏪 {it.merchantName}</div>
                  </div>
                  <div className="oc-price">¥{it.price} × {it.qty}</div>
                </div>
              ))}
              <div className="order-card-foot">
                <span>
                  实付 ¥{o.totalAmount ?? 0} · {o.addressSnapshot ?? '无地址快照'}
                </span>
                <div className="order-actions">
                  {o.status === 'CREATE_ORDER' && (
                    <>
                      <button onClick={() => doOrderAction(o.orderId, 'cancel')}>取消</button>
                      <button onClick={() => doOrderAction(o.orderId, 'pay')}>去支付</button>
                    </>
                  )}
                  {o.status === 'SHIPPED' && (
                    <button onClick={() => doOrderAction(o.orderId, 'confirm')}>确认收货</button>
                  )}
                </div>
              </div>
            </div>
          ))}
        </>
      )}

      {tab === 'addresses' && (
        <>
          <div className="address-manage">
            {addresses.map((a) => (
              <div key={a.id} className="address-card">
                <div className="address-line1">
                  {a.receiver} {a.phone}
                  {a.isDefault && <span className="tag-default">默认</span>}
                </div>
                <div className="address-line2">
                  {a.province} {a.city} {a.district} {a.detail}
                </div>
                <div className="address-actions">
                  <button onClick={() => startEdit(a)}>编辑</button>
                  {!a.isDefault && (
                    <button onClick={async () => {
                      await userApi.setDefaultAddress(a.id);
                      await loadAddresses();
                    }}>
                      设为默认
                    </button>
                  )}
                  <button onClick={async () => {
                    await userApi.deleteAddress(a.id);
                    await loadAddresses();
                  }}>
                    删除
                  </button>
                </div>
              </div>
            ))}
          </div>
          <button
            className="btn-secondary"
            onClick={() => {
              setEditing(null);
              setForm({ receiver: '', phone: '', province: '', city: '', district: '', detail: '', isDefault: false });
              setShowForm(!showForm);
            }}
          >
            {showForm ? '收起' : '+ 新增地址'}
          </button>
          {showForm && (
            <div className="address-form">
              <h4>{editing != null ? '编辑地址' : '新增地址'}</h4>
              <div className="form-grid">
                <input placeholder="收件人" value={form.receiver}
                  onChange={(e) => setForm({ ...form, receiver: e.target.value })} />
                <input placeholder="手机号" value={form.phone}
                  onChange={(e) => setForm({ ...form, phone: e.target.value })} />
                <input placeholder="省" value={form.province}
                  onChange={(e) => setForm({ ...form, province: e.target.value })} />
                <input placeholder="市" value={form.city}
                  onChange={(e) => setForm({ ...form, city: e.target.value })} />
                <input placeholder="区/县" value={form.district}
                  onChange={(e) => setForm({ ...form, district: e.target.value })} />
                <input placeholder="详细地址" value={form.detail}
                  onChange={(e) => setForm({ ...form, detail: e.target.value })} />
              </div>
              <label className="default-check">
                <input
                  type="checkbox"
                  checked={form.isDefault}
                  onChange={(e) => setForm({ ...form, isDefault: e.target.checked })}
                />
                设为默认地址
              </label>
              <button className="btn-primary" onClick={submitAddress}>保存</button>
            </div>
          )}
        </>
      )}
    </div>
  );
}
