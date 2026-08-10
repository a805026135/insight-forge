import { useEffect, useState } from 'react';
import { useAuth } from '../../auth/AuthContext';
import { seckillApi, dashboardApi, activityApi, SkuView, ActivityView } from '../../api';

export default function SeckillPage() {
  const { user } = useAuth();
  const [activities, setActivities] = useState<ActivityView[]>([]);
  const [activeId, setActiveId] = useState<number>(1);
  const [skus, setSkus] = useState<SkuView[]>([]);
  const [msg, setMsg] = useState('');
  const [loading, setLoading] = useState<number | null>(null);
  const [orderId, setOrderId] = useState('');

  const activeActivity = activities.find((a) => a.id === activeId);

  useEffect(() => {
    activityApi
      .list()
      .then((list) => {
        setActivities(list);
        if (list.length > 0) {
          // 优先选正在进行的活动，否则选第一个
          const live = list.find((a) => a.status === 'LIVE');
          setActiveId((live ?? list[0]).id);
        }
      })
      .catch(() => setMsg('加载活动列表失败，请检查后端是否启动'));
  }, []);

  const loadSkus = async (activityId: number) => {
    try {
      const list = await dashboardApi.skus(activityId);
      setSkus(list);
    } catch {
      setMsg('加载商品列表失败，请检查后端是否启动');
    }
  };

  useEffect(() => {
    loadSkus(activeId);
  }, [activeId]);

  const handleSeckill = async (sku: SkuView) => {
    if (!user) return;
    setLoading(sku.activitySkuId);
    setMsg('');
    try {
      const oid = await seckillApi.place({
        activityId: activeId,
        skuId: sku.activitySkuId, // 后端以 activity_sku.id 解析库存（勿传 product.id）
        userId: user.id,
        qty: 1,
      });
      setOrderId(oid);
      setMsg(`抢购成功！订单号：${oid}`);
      await loadSkus(activeId);
    } catch (ex: any) {
      setMsg(ex.message || '抢购失败');
    } finally {
      setLoading(null);
    }
  };

  const handleCancel = async () => {
    try {
      await fetch(`/api/order/${orderId}/cancel`, { method: 'POST' });
      setMsg('订单已取消，库存已回补');
      setOrderId('');
      await loadSkus(activeId);
    } catch (ex: any) {
      setMsg(`取消失败：${ex.message}`);
    }
  };

  const discount = (p: SkuView) =>
    p.marketPrice != null && p.marketPrice > 0 && p.seckillPrice < p.marketPrice
      ? Math.round((1 - p.seckillPrice / p.marketPrice) * 100)
      : 0;

  return (
    <div className="seckill-page">
      <div className="page-head">
        <h2>限时秒杀</h2>
        {activeActivity && (
          <span
            className={`badge ${activeActivity.status === 'LIVE' ? 'badge-live' : 'badge-closed'}`}
          >
            {activeActivity.status === 'LIVE' ? '热卖中' : activeActivity.status}
          </span>
        )}
      </div>
      <p className="welcome">欢迎，{user?.displayName}</p>
      {activities.length > 0 && (
        <div className="activity-switch">
          {activities.map((a) => (
            <button
              key={a.id}
              className={`activity-tab ${a.id === activeId ? 'active' : ''}`}
              onClick={() => setActiveId(a.id)}
            >
              {a.name}
              {a.status === 'LIVE' && <span className="activity-live-dot" />}
            </button>
          ))}
        </div>
      )}
      <div className="product-grid">
        {skus.map((p) => (
          <div key={p.activitySkuId} className="product-card">
            <div className="product-img-wrap">
              <img
                className="product-img"
                src={p.productImage}
                alt={p.productName}
                loading="lazy"
                onError={(e) => {
                  (e.target as HTMLImageElement).style.visibility = 'hidden';
                }}
              />
              {discount(p) > 0 && <span className="discount-badge">{discount(p)}% OFF</span>}
            </div>
            <div className="product-body">
              <h3 className="product-name" title={p.productName}>
                {p.productName}
              </h3>
              <div className="product-sku">{p.skuCode}</div>
              <div className="price-row">
                <span className="price">¥{p.seckillPrice}</span>
                {p.marketPrice != null && p.marketPrice > p.seckillPrice && (
                  <span className="market-price">¥{p.marketPrice}</span>
                )}
              </div>
              <div className="stock-bar">
                <div
                  className="stock-fill"
                  style={{
                    width: `${p.totalStock > 0 ? (p.stock / p.totalStock) * 100 : 0}%`,
                  }}
                />
              </div>
              <div className="stock-nums">
                已售 {p.sold} · 剩余 {p.stock} / {p.totalStock}
              </div>
              <button
                onClick={() => handleSeckill(p)}
                disabled={
                  loading === p.activitySkuId || p.stock <= 0 || activeActivity?.status !== 'LIVE'
                }
              >
                {loading === p.activitySkuId
                  ? '抢购中...'
                  : p.stock <= 0
                  ? '已售罄'
                  : activeActivity?.status === 'LIVE'
                  ? '立即抢购'
                  : '未开抢'}
              </button>
            </div>
          </div>
        ))}
      </div>
      {skus.length === 0 && !msg && <p className="hint">加载商品中...</p>}
      {msg && (
        <div className={`toast ${orderId ? 'success' : 'error'}`}>
          {msg}
          {orderId && <button className="cancel-btn" onClick={handleCancel}>取消订单</button>}
        </div>
      )}
    </div>
  );
}
