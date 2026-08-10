import { useEffect, useState } from 'react';
import { useAuth } from '../../auth/AuthContext';
import { seckillApi, dashboardApi, SkuView } from '../../api';

export default function SeckillPage() {
  const { user } = useAuth();
  const [skus, setSkus] = useState<SkuView[]>([]);
  const [msg, setMsg] = useState('');
  const [loading, setLoading] = useState<number | null>(null);
  const [orderId, setOrderId] = useState('');

  useEffect(() => {
    loadSkus();
  }, []);

  const loadSkus = async () => {
    try {
      // 从后端动态加载活动 1 的全部 SKU（含商品名/价格/库存）
      const list = await dashboardApi.skus(1);
      setSkus(list);
    } catch {
      setMsg('加载商品列表失败，请检查后端是否启动');
    }
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
      await loadSkus();
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
        {skus.map((p) => (
          <div key={p.skuId} className="product-card">
            <h3>{p.productName}</h3>
            <div className="price">¥{p.seckillPrice}</div>
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
      {skus.length === 0 && !msg && <p className="hint">加载商品中...</p>}
      {msg && (
        <div className={`toast ${orderId ? 'success' : 'error'}`}>
          {msg}
          {orderId && (
            <button className="cancel-btn" onClick={async () => {
              try {
                await fetch(`/api/order/${orderId}/cancel`, { method: 'POST' });
                setMsg('订单已取消，库存已回补');
                setOrderId('');
                await loadSkus();
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
