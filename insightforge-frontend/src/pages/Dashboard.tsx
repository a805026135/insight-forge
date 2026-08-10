import { useEffect, useState } from 'react';
import ReactECharts from 'echarts-for-react';
import { merchantApi, MerchantProductView } from '../api';

/**
 * 商家运营大屏（Phase 18 美化）：商家维度经营数据 + 图表化展示。
 */
export default function Dashboard() {
  const [overview, setOverview] = useState<Record<string, number | string> | null>(null);
  const [products, setProducts] = useState<MerchantProductView[]>([]);
  const [msg, setMsg] = useState('');

  const load = async () => {
    try {
      const [ov, ps] = await Promise.all([merchantApi.overview(), merchantApi.products()]);
      setOverview(ov);
      setProducts(ps);
    } catch (e: any) {
      setMsg(e.message);
    }
  };

  useEffect(() => {
    load();
    const t = window.setInterval(load, 5000);
    return () => window.clearInterval(t);
  }, []);

  const o = overview ?? {};
  const onSale = products.filter((p) => p.status === 'ON_SALE').length;
  const offShelf = products.length - onSale;
  const totalStock = products.reduce((s, p) => s + (p.stock ?? 0), 0);
  const sold = o.totalSold ?? 0;
  const stockLeft = o.totalStock ?? 0;

  const statusOption = {
    tooltip: { trigger: 'item' },
    legend: { bottom: 0, textStyle: { color: 'var(--text-secondary)' } },
    series: [
      {
        type: 'pie',
        radius: ['42%', '68%'],
        center: ['50%', '45%'],
        label: { color: 'var(--text-secondary)' },
        data: [
          { value: onSale, name: '在售', itemStyle: { color: 'var(--green)' } },
          { value: offShelf, name: '下架', itemStyle: { color: 'var(--red)' } },
        ],
      },
    ],
  };

  const stockOption = {
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 20, top: 30, bottom: 30 },
    xAxis: { type: 'category', data: ['已售', '剩余'], axisLabel: { color: 'var(--text-secondary)' } },
    yAxis: { type: 'value', axisLabel: { color: 'var(--text-muted)' }, splitLine: { lineStyle: { color: 'var(--border)' } } },
    series: [
      {
        type: 'bar',
        barWidth: 48,
        label: { show: true, position: 'top', color: 'var(--text-secondary)' },
        data: [
          { value: sold, itemStyle: { color: 'var(--amber)' } },
          { value: stockLeft, itemStyle: { color: 'var(--green)' } },
        ],
      },
    ],
  };

  const topOption = {
    tooltip: { trigger: 'axis' },
    grid: { left: 130, right: 30, top: 20, bottom: 30 },
    xAxis: { type: 'value', axisLabel: { color: 'var(--text-muted)' }, splitLine: { lineStyle: { color: 'var(--border)' } } },
    yAxis: {
      type: 'category',
      data: products.slice(0, 8).map((p) => p.name.length > 12 ? p.name.slice(0, 12) + '…' : p.name).reverse(),
      axisLabel: { color: 'var(--text-secondary)', fontSize: 11 },
    },
    series: [
      {
        type: 'bar',
        itemStyle: { color: 'var(--cyan)' },
        data: products.slice(0, 8).map((p) => p.stock ?? 0).reverse(),
        label: { show: true, position: 'right', color: 'var(--text-secondary)' },
      },
    ],
  };

  const catCount = new Map<string, number>();
  products.forEach((p) => catCount.set(p.category || '未分类', (catCount.get(p.category || '未分类') ?? 0) + 1));
  const catOption = {
    tooltip: { trigger: 'item' },
    legend: { bottom: 0, textStyle: { color: 'var(--text-secondary)' } },
    series: [
      {
        type: 'pie',
        radius: ['35%', '62%'],
        center: ['50%', '45%'],
        label: { color: 'var(--text-secondary)' },
        data: Array.from(catCount.entries()).map(([name, value], i) => ({
          name,
          value,
          itemStyle: { color: ['var(--green)', 'var(--cyan)', 'var(--amber)', 'var(--purple)', 'var(--red)'][i % 5] },
        })),
      },
    ],
  };

  return (
    <div className="dashboard-page">
      <div className="page-head">
        <h2>店铺经营大屏</h2>
        <span className="badge badge-live">每 5 秒自动刷新</span>
      </div>
      {msg && <div className="toast error">{msg}</div>}

      <div className="metrics-grid">
        <div className="metric-tile-big">
          <div className="mt-label">在售商品</div>
          <div className="mt-value">{onSale}<span style={{ fontSize: 16, color: 'var(--text-muted)' }}> / {products.length}</span></div>
          <div className="mt-sub">已下架 {offShelf} 款</div>
        </div>
        <div className="metric-tile-big">
          <div className="mt-label">已售件数</div>
          <div className="mt-value">{sold}</div>
          <div className="mt-sub">活动秒杀销量</div>
        </div>
        <div className="metric-tile-big">
          <div className="mt-label">累计 GMV</div>
          <div className="mt-value" style={{ color: 'var(--purple)' }}>¥{((Number(o.totalGmv) ?? 0) / 10000).toFixed(1)}万</div>
          <div className="mt-sub">成交价 × 数量</div>
        </div>
        <div className="metric-tile-big">
          <div className="mt-label">库存总量</div>
          <div className="mt-value" style={{ color: 'var(--cyan)' }}>{totalStock}</div>
          <div className="mt-sub">商品独立库存 + 活动库存 {stockLeft}</div>
        </div>
      </div>

      <div className="chart-grid">
        <div className="chart-card">
          <h4>商品上下架分布</h4>
          <ReactECharts option={statusOption} style={{ height: 220 }} />
        </div>
        <div className="chart-card">
          <h4>销量 vs 剩余库存</h4>
          <ReactECharts option={stockOption} style={{ height: 220 }} />
        </div>
        <div className="chart-card">
          <h4>商品库存 Top（普通库存）</h4>
          <ReactECharts option={topOption} style={{ height: 260 }} />
        </div>
        <div className="chart-card">
          <h4>商品分类分布</h4>
          <ReactECharts option={catOption} style={{ height: 220 }} />
        </div>
      </div>
    </div>
  );
}
