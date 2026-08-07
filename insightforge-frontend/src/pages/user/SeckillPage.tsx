import { useEffect, useState } from 'react';
import { useAuth } from '../../auth/AuthContext';
import { seckillApi } from '../../api';

interface Product {
  skuId: number;
  name: string;
  price: number;
  stock: number;
  totalStock: number;
  sold: number;
}

const MOCK_PRODUCTS: Omit<Product, 'stock' | 'sold'>[] = [
  { skuId: 1, name: 'iPhone 16 秒杀款', price: 6299, totalStock: 100 },
];

export default function SeckillPage() {
  const { user } = useAuth();
  const [products, setProducts] = useState<Product[]>([]);
  const [msg, setMsg] = useState('');
  const [loading, setLoading] = useState<number | null>(null);
  const [orderId, setOrderId] = useState('');

  useEffect(() => {
    loadStock();
  }, []);

  const loadStock = async () => {
    const list: Product[] = [];
    for (const p of MOCK_PRODUCTS) {
      try {
        const info = await seckillApi.stock(p.skuId);
        list.push({ ...p, stock: info.stock, sold: info.sold });
      } catch {
        list.push({ ...p, stock: p.totalStock, sold: 0 });
      }
    }
    setProducts(list);
  };

  const handleSeckill = async (skuId: number) => {
    if (!user) return;
    setLoading(skuId);
    setMsg('');
    try {
      const oid = await seckillApi.place({
        activityId: 1,
        skuId,
        userId: user.id,
        qty: 1,
      });
      setOrderId(oid);
      setMsg(`抢购成功！订单号：${oid}`);
      await loadStock();
    } catch (ex: any) {
      setMsg(ex.message || '抢购失败');
    } finally {
      setLoading(null);
    }
  };

  return (
    <div className="seckill-page">
      <h2>限时秒杀</h2>
      <p className="welcome">欢迎，{user?.displayName}！</p>
      <div className="product-grid">
        {products.map((p) => (
          <div key={p.skuId} className="product-card">
            <h3>{p.name}</h3>
            <div className="price">¥{p.price}</div>
            <div className="stock-bar">
              <div
                className="stock-fill"
                style={{ width: `${p.totalStock > 0 ? (p.stock / p.totalStock) * 100 : 0}%` }}
              />
            </div>
            <div className="stock-nums">
              剩余 {p.stock} / 共 {p.totalStock}（已售 {p.sold}）
            </div>
            <button
              onClick={() => handleSeckill(p.skuId)}
              disabled={loading === p.skuId || p.stock <= 0}
            >
              {loading === p.skuId
                ? '抢购中...'
                : p.stock <= 0
                ? '已售罄'
                : '立即抢购'}
            </button>
          </div>
        ))}
      </div>
      {msg && (
        <div className={`toast ${orderId ? 'success' : 'error'}`}>
          {msg}
          {orderId && (
            <button className="cancel-btn" onClick={async () => {
              try {
                await fetch(`/api/order/${orderId}/cancel`, { method: 'POST' });
                setMsg('订单已取消，库存已回补');
                setOrderId('');
                await loadStock();
              } catch (ex: any) {
                setMsg(`取消失败：${ex.message}`);
              }
            }}>取消订单</button>
          )}
        </div>
      )}
    </div>
  );
}
