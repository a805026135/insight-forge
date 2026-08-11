import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { catalogApi, userApi, ProductDetailView } from '../../api';

export default function ProductDetail() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [p, setP] = useState<ProductDetailView | null>(null);
  const [msg, setMsg] = useState('');
  const [adding, setAdding] = useState(false);
  const [reviews, setReviews] = useState<any[]>([]);
  const [reviewStat, setReviewStat] = useState<{ ratingCount: number; ratingTotal: number } | null>(null);

  useEffect(() => {
    if (!id) return;
    catalogApi
      .detail(Number(id))
      .then(setP)
      .catch((e) => setMsg(e.message || '加载失败'));
    fetch(`/api/buyer/review?productId=${Number(id)}`)
      .then((r) => r.json())
      .then((r) => {
        if (r.code === 0) {
          setReviewStat({ ratingCount: r.data.ratingCount ?? 0, ratingTotal: r.data.ratingTotal ?? 0 });
          setReviews(r.data.reviews ?? []);
        }
      })
      .catch(() => {});
  }, [id]);

  const addCart = async (qty: number) => {
    if (!p) return;
    setAdding(true);
    setMsg('');
    try {
      await userApi.addCart(p.id, qty);
      setMsg('已加入购物车 ✅');
      navigate('/user/cart');
    } catch (e: any) {
      setMsg(e.message || '加购失败');
    } finally {
      setAdding(false);
    }
  };

  const buyNow = async () => {
    if (!p) return;
    setAdding(true);
    setMsg('');
    try {
      await userApi.addCart(p.id, 1);
      const cart = await userApi.cart();
      const ids = cart.filter((i) => i.productId === p.id).map((i) => i.id);
      navigate('/user/checkout', { state: { cartItemIds: ids } });
    } catch (e: any) {
      setMsg(e.message || '购买失败');
    } finally {
      setAdding(false);
    }
  };

  if (!p) {
    return (
      <div className="page">
        <h2>商品详情</h2>
        {msg ? <div className="toast error">{msg}</div> : <p className="info-msg">加载中…</p>}
      </div>
    );
  }

  const discount =
    p.seckillPrice != null && p.marketPrice != null && p.seckillPrice < p.marketPrice
      ? Math.round((1 - p.seckillPrice / p.marketPrice) * 100)
      : 0;

  return (
    <div className="product-detail">
      <div className="detail-layout">
        <div className="detail-gallery">
          <img src={p.imageUrl} alt={p.name} />
        </div>
        <div className="detail-info">
          <h2>{p.name}</h2>
          <div className="merchant-box">
            <span className="merchant-name">🏪 {p.merchantName}</span>
            {p.merchantVerified && <span className="merchant-verified">官方认证</span>}
            <span className="merchant-rating">评分 {p.merchantRating ?? '-'}</span>
            <span className="merchant-sales">累计销量 {p.merchantSalesCount ?? '-'}</span>
          </div>
          <div className="detail-price">
            <span className="price-big">¥{p.seckillPrice ?? p.marketPrice}</span>
            {p.marketPrice != null && (
              <span className="market-price">市场价 ¥{p.marketPrice}</span>
            )}
            {discount > 0 && <span className="discount-badge">{discount}% OFF</span>}
          </div>
          <div className="detail-stock">
            {p.stock != null ? (
              <>库存 {p.stock} 件 · 已售 {p.sold ?? 0} 件</>
            ) : (
              <>当前未参与秒杀活动，可关注大促</>
            )}
          </div>
          <p className="detail-desc">{p.description || '暂无描述'}</p>
          <div className="detail-actions">
            <button disabled={adding} onClick={() => addCart(1)}>
              {adding ? '加入中…' : '加入购物车'}
            </button>
            <button className="btn-buy" disabled={adding} onClick={buyNow}>
              立即购买
            </button>
          </div>
          {msg && <div className={`toast ${msg.includes('✅') ? 'success' : 'error'}`}>{msg}</div>}
        </div>
      </div>

      <section className="assistant-panel" style={{ marginTop: 20 }}>
        <h3>????</h3>
        {reviewStat && reviewStat.ratingCount > 0 ? (
          <>
            <p style={{ color: '#666', marginBottom: 12 }}>
              ?????<b style={{ color: '#f59e0b', fontSize: 18 }}>
                {(reviewStat.ratingTotal / reviewStat.ratingCount).toFixed(1)}
              </b> / 5?{reviewStat.ratingCount} ????
            </p>
            {reviews.map((r, idx) => (
              <div key={idx} style={{ padding: '10px 0', borderBottom: '1px solid var(--border)' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <b>{r.userDisplayName ?? '????'}</b>
                  <span style={{ color: '#f59e0b' }}>{'?'.repeat(Math.max(0, Math.min(5, r.rating ?? 0)))}</span>
                  <span style={{ fontSize: 12, color: '#999' }}>{String(r.createdAt ?? '').replace('T', ' ').slice(0, 19)}</span>
                </div>
                {r.content ? <p style={{ marginTop: 6, color: 'var(--text)' }}>{r.content}</p> : null}
              </div>
            ))}
          </>
        ) : (
          <p style={{ color: '#999' }}>????????????</p>
        )}
      </section>
    </div>
  );
}
