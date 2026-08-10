import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { userApi, CartItemView } from '../../api';

export default function CartPage() {
  const navigate = useNavigate();
  const [items, setItems] = useState<CartItemView[]>([]);
  const [loading, setLoading] = useState(true);
  const [msg, setMsg] = useState('');
  const [selected, setSelected] = useState<Set<number>>(new Set());

  const refresh = async () => {
    setLoading(true);
    try {
      const data = await userApi.cart();
      setItems(data);
      setSelected(new Set(data.map((i) => i.id)));
    } catch (e: any) {
      setMsg(e.message || '加载购物车失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    refresh();
  }, []);

  const toggle = (id: number) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const changeQty = async (id: number, qty: number) => {
    if (qty < 1 || qty > 99) return;
    try {
      await userApi.updateCartQty(id, qty);
      setItems((prev) => prev.map((i) => (i.id === id ? { ...i, qty } : i)));
    } catch (e: any) {
      setMsg(e.message);
    }
  };

  const remove = async (id: number) => {
    try {
      await userApi.removeCart(id);
      setItems((prev) => prev.filter((i) => i.id !== id));
      setSelected((prev) => {
        const next = new Set(prev);
        next.delete(id);
        return next;
      });
    } catch (e: any) {
      setMsg(e.message);
    }
  };

  const selectedItems = items.filter((i) => selected.has(i.id));
  const total = selectedItems.reduce(
    (sum, i) => sum + (i.price ?? 0) * i.qty,
    0,
  );

  const goCheckout = () => {
    if (selectedItems.length === 0) {
      setMsg('请先选择要结算的商品');
      return;
    }
    navigate('/user/checkout', {
      state: { cartItemIds: selectedItems.map((i) => i.id) },
    });
  };

  return (
    <div className="cart-page">
      <h2>购物车</h2>
      {msg && <div className="toast error">{msg}</div>}
      {loading && <p className="info-msg">加载中…</p>}
      {!loading && items.length === 0 && (
        <div className="info-msg">
          <p>购物车还是空的，去首页逛逛吧。</p>
          <button onClick={() => navigate('/user/home')} className="btn-primary">
            去逛逛
          </button>
        </div>
      )}
      {items.length > 0 && (
        <>
          <div className="cart-list">
            {items.map((i) => (
              <div key={i.id} className="cart-item">
                <input
                  type="checkbox"
                  checked={selected.has(i.id)}
                  onChange={() => toggle(i.id)}
                />
                <img
                  className="cart-img"
                  src={i.productImage}
                  alt=""
                  onClick={() => navigate(`/user/product/${i.productId}`)}
                />
                <div className="cart-info">
                  <div className="cart-name" onClick={() => navigate(`/user/product/${i.productId}`)}>
                    {i.productName}
                  </div>
                  <div className="cart-merchant">🏪 {i.merchantName}</div>
                  <div className="cart-price">¥{i.price}</div>
                </div>
                <div className="cart-qty">
                  <button onClick={() => changeQty(i.id, i.qty - 1)}>-</button>
                  <span>{i.qty}</span>
                  <button onClick={() => changeQty(i.id, i.qty + 1)}>+</button>
                </div>
                <button className="cart-remove" onClick={() => remove(i.id)}>
                  删除
                </button>
              </div>
            ))}
          </div>
          <div className="cart-footer">
            <span>已选 {selectedItems.length} 件，合计：</span>
            <span className="cart-total">¥{total.toLocaleString()}</span>
            <button className="btn-primary" onClick={goCheckout}>
              去结算
            </button>
          </div>
        </>
      )}
    </div>
  );
}
