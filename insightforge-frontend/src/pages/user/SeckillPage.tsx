import { useState } from 'react';
import { mockActivities, mockSkus, type Sku } from '../../mock/data';

export default function SeckillPage() {
  const liveActivity = mockActivities.find(a => a.status === 'LIVE');
  const skus = mockSkus.filter(s => s.activityId === liveActivity?.id);
  const [grabbed, setGrabbed] = useState<Set<number>>(new Set());
  const [localSkus, setLocalSkus] = useState(skus);
  const [toast, setToast] = useState('');

  const handleGrab = (sku: Sku) => {
    if (sku.stock <= 0 || grabbed.has(sku.id)) return;
    const updated = localSkus.map(s =>
      s.id === sku.id ? { ...s, stock: s.stock - 1, sold: s.sold + 1 } : s
    );
    setLocalSkus(updated);
    setGrabbed(new Set([...grabbed, sku.id]));
    showToast(`✅ 抢到了！${sku.name}  ¥${sku.seckillPrice.toLocaleString()}`);
  };

  const showToast = (msg: string) => {
    setToast(msg);
    setTimeout(() => setToast(''), 2500);
  };

  if (!liveActivity) {
    return (
      <div>
        <div className="page-header">
          <h2>⚡ 限时秒杀</h2>
          <p>当前无进行中的活动，敬请期待下一场</p>
        </div>
      </div>
    );
  }

  const progressPct = (s: Sku) => Math.round((s.sold / s.totalStock) * 100);

  return (
    <div>
      <div className="page-header">
        <h2>⚡ {liveActivity.name}</h2>
        <p>{skus.length} 款商品火热抢购中 · 目标 GMV ¥{(liveActivity.targetGMV / 10000).toFixed(0)}万</p>
      </div>

      <div className="metrics-grid">
        <div className="metric-tile green">
          <div className="label">进行中商品</div>
          <div className="value">{skus.length}</div>
        </div>
        <div className="metric-tile cyan">
          <div className="label">总库存</div>
          <div className="value">{skus.reduce((a, s) => a + s.totalStock, 0)}</div>
        </div>
        <div className="metric-tile amber">
          <div className="label">已售出</div>
          <div className="value">{skus.reduce((a, s) => a + s.sold, 0)}</div>
        </div>
        <div className="metric-tile red">
          <div className="label">剩余库存</div>
          <div className="value">{localSkus.reduce((a, s) => a + s.stock, 0)}</div>
        </div>
      </div>

      <div className="seckill-grid">
        {localSkus.map(sku => (
          <div className="seckill-card" key={sku.id}>
            <div className="sku-image">{sku.image}</div>
            <div className="sku-name">{sku.name}</div>
            <div className="price-row">
              <span className="seckill-price">¥{sku.seckillPrice.toLocaleString()}</span>
              <span className="original-price">¥{sku.originalPrice.toLocaleString()}</span>
            </div>
            <div className="stock-bar">
              <div
                className="stock-bar-fill"
                style={{
                  width: `${Math.max(progressPct(sku), 5)}%`,
                  background: sku.stock === 0 ? 'var(--red)' : progressPct(sku) > 70 ? 'var(--amber)' : 'var(--green)'
                }}
              />
            </div>
            <div className="stock-info">
              <span>已抢 {progressPct(sku)}%</span>
              <span>剩余 {sku.stock}</span>
            </div>
            <button
              className={`btn grab-btn ${sku.stock > 0 && !grabbed.has(sku.id) ? 'btn-primary' : ''}`}
              onClick={() => handleGrab(sku)}
              disabled={sku.stock <= 0 || grabbed.has(sku.id)}
              style={sku.stock <= 0 ? { background: 'var(--border)', color: 'var(--text-muted)', cursor: 'not-allowed' } : {}}
            >
              {sku.stock <= 0 ? '已售罄' : grabbed.has(sku.id) ? '✓ 已抢到' : '立即抢购'}
            </button>
          </div>
        ))}
      </div>

      {toast && (
        <div style={{
          position: 'fixed', bottom: 40, left: '50%', transform: 'translateX(-50%)',
          background: 'var(--green)', color: '#000', padding: '12px 24px', borderRadius: 8,
          fontWeight: 700, letterSpacing: 1, zIndex: 999,
          animation: 'fadeInUp 0.3s ease',
        }}>
          {toast}
        </div>
      )}
    </div>
  );
}
