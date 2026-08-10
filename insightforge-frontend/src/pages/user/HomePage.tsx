import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { catalogApi, ProductDetailView } from '../../api';

export default function HomePage() {
  const [categories, setCategories] = useState<string[]>([]);
  const [activeCategory, setActiveCategory] = useState('');
  const [keyword, setKeyword] = useState('');
  const [products, setProducts] = useState<ProductDetailView[]>([]);
  const [loading, setLoading] = useState(true);
  const [msg, setMsg] = useState('');

  const load = async (category: string, kw: string) => {
    setLoading(true);
    setMsg('');
    try {
      const data = await catalogApi.products({
        category: category || undefined,
        keyword: kw || undefined,
        limit: 60,
      });
      setProducts(data);
    } catch (e: any) {
      setMsg(e.message || '加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    catalogApi.categories().then(setCategories).catch(() => {});
    load('', '');
  }, []);

  const pickCategory = (c: string) => {
    setActiveCategory(c);
    load(c, keyword);
  };

  const doSearch = () => {
    load(activeCategory, keyword);
  };

  const hot = [...products].sort((a, b) => (b.sold ?? 0) - (a.sold ?? 0)).slice(0, 4);

  return (
    <div className="home-page">
      <section className="hero-banner">
        <div className="hero-content">
          <h2>双11 · 618 数码狂欢节</h2>
          <p>官方旗舰店直营 · AI 智能控价 · 限量秒杀</p>
          <div className="hero-search">
            <input
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && doSearch()}
              placeholder="搜索商品 / 商家，如 iPhone、华为、耳机"
            />
            <button onClick={doSearch}>搜索</button>
          </div>
        </div>
      </section>

      {categories.length > 0 && (
        <div className="category-tabs">
          <button
            className={`category-tab ${activeCategory === '' ? 'active' : ''}`}
            onClick={() => pickCategory('')}
          >
            全部
          </button>
          {categories.map((c) => (
            <button
              key={c}
              className={`category-tab ${activeCategory === c ? 'active' : ''}`}
              onClick={() => pickCategory(c)}
            >
              {c}
            </button>
          ))}
        </div>
      )}

      {hot.length > 0 && (
        <div className="hot-section">
          <h3>🔥 热卖榜</h3>
          <div className="hot-strip">
            {hot.map((p, i) => (
              <Link key={p.id} to={`/user/product/${p.id}`} className="hot-item">
                <span className="hot-rank">{i + 1}</span>
                <img src={p.imageUrl} alt={p.name} loading="lazy" />
                <div className="hot-name">{p.name}</div>
                <div className="hot-price">¥{p.seckillPrice ?? p.marketPrice}</div>
              </Link>
            ))}
          </div>
        </div>
      )}

      <h3 className="section-title">全部商品</h3>
      {msg && <div className="toast error">{msg}</div>}
      {loading && <p className="info-msg">加载中…</p>}
      <div className="product-grid">
        {products.map((p) => (
          <Link key={p.id} to={`/user/product/${p.id}`} className="product-card product-link">
            <div className="product-img-wrap">
              <img className="product-img" src={p.imageUrl} alt={p.name} loading="lazy" />
              {p.seckillPrice != null && p.marketPrice != null && p.seckillPrice < p.marketPrice && (
                <span className="discount-badge">
                  {Math.round((1 - p.seckillPrice / p.marketPrice) * 100)}% OFF
                </span>
              )}
            </div>
            <div className="product-body">
              <h3 className="product-name" title={p.name}>{p.name}</h3>
              <div className="merchant-tag">🏪 {p.merchantName}</div>
              <div className="price-row">
                <span className="price">¥{p.seckillPrice ?? p.marketPrice}</span>
                {p.marketPrice != null && p.seckillPrice != null && p.marketPrice > p.seckillPrice && (
                  <span className="market-price">¥{p.marketPrice}</span>
                )}
              </div>
            </div>
          </Link>
        ))}
      </div>
      {!loading && products.length === 0 && !msg && (
        <div className="info-msg">没有找到相关商品，换个关键词试试吧。</div>
      )}
    </div>
  );
}
