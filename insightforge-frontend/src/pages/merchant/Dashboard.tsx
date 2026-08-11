import { useEffect, useState } from 'react';
import ReactECharts from 'echarts-for-react';
import { useAuth } from '../../auth/AuthContext';
import { getToken } from '../../auth/token';

export default function Dashboard() {
  const { user } = useAuth();
  const [overview, setOverview] = useState<Record<string, any> | null>(null);
  const [ana, setAna] = useState<Record<string, any> | null>(null);
  const [err, setErr] = useState('');

  const load = async () => {
    try {
      const auth = { Authorization: `Bearer ${getToken()}` };
      const [o, a] = await Promise.all([
        fetch('/api/merchant/overview', { headers: auth }).then((r) => r.json()),
        fetch('/api/merchant/dashboard/analytics', { headers: auth }).then((r) => r.json()),
      ]);
      if (o.code !== 0 || a.code !== 0) throw new Error('数据加载失败');
      setOverview(o.data);
      setAna(a.data);
    } catch (e: any) {
      setErr(e.message || '加载失败');
    }
  };

  useEffect(() => {
    load();
  }, []);

  if (err) return <div className="page"><h2>商家运营大屏</h2><div className="error">{err}</div></div>;
  if (!overview || !ana) return <div className="page"><h2>商家运营大屏</h2><p>加载中…</p></div>;

  const trend = ana.trend ?? [];
  const dates = trend.map((t: any) => t.date);
  const trendOption = {
    tooltip: { trigger: 'axis' },
    legend: { data: ['销量', 'GMV(元)'] },
    grid: { left: 40, right: 60, top: 40, bottom: 30 },
    xAxis: { type: 'category', data: dates },
    yAxis: [
      { type: 'value', name: '销量' },
      { type: 'value', name: 'GMV' },
    ],
    series: [
      { name: '销量', type: 'line', smooth: true, data: trend.map((t: any) => t.qty), areaStyle: {} },
      { name: 'GMV(元)', type: 'line', yAxisIndex: 1, smooth: true, data: trend.map((t: any) => t.gmv) },
    ],
  };

  const categoryOption = {
    tooltip: { trigger: 'item', formatter: '{b}: {c} 元 ({d}%)' },
    series: [
      {
        type: 'pie',
        radius: ['35%', '68%'],
        data: (ana.category ?? []).map((c: any) => ({ name: c.name, value: c.gmv })),
        label: { formatter: '{b}\n{d}%' },
      },
    ],
  };

  const stock = ana.stock ?? {};
  const stockOption = {
    tooltip: {},
    xAxis: { type: 'category', data: ['在售', '下架', '低库存(<100)'] },
    yAxis: { type: 'value' },
    series: [
      {
        type: 'bar',
        barWidth: 48,
        itemStyle: { borderRadius: [6, 6, 0, 0] },
        data: [
          { value: stock.onSale ?? 0, itemStyle: { color: '#34c77b' } },
          { value: stock.offShelf ?? 0, itemStyle: { color: '#f59e0b' } },
          { value: stock.lowStock ?? 0, itemStyle: { color: '#ef4444' } },
        ],
      },
    ],
  };

  return (
    <div className="dashboard-page">
      <h2>商家运营大屏</h2>
      <p className="welcome">
        欢迎，{user?.displayName}（商家）&nbsp;&nbsp;|&nbsp;&nbsp;数据均为本店维度，实时聚合
      </p>

      <div className="kpi-grid">
        <div className="kpi-card highlight">
          <div className="kpi-value">{overview.productCount ?? '-'}</div>
          <div className="kpi-label">在售商品</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-value">{overview.totalSold ?? '-'}</div>
          <div className="kpi-label">已售件数</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-value">¥{(overview.totalGmv ?? 0).toLocaleString('zh-CN')}</div>
          <div className="kpi-label">累计 GMV</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-value">{overview.sellThroughRate ?? '-'}%</div>
          <div className="kpi-label">售罄率</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-value">{overview.totalStock ?? '-'}</div>
          <div className="kpi-label">剩余库存</div>
        </div>
      </div>

      <div className="chart-grid">
        <div className="chart-card">
          <h3 className="section-title">近 7 日销售趋势</h3>
          <ReactECharts option={trendOption} style={{ height: 280 }} />
        </div>
        <div className="chart-card">
          <h3 className="section-title">类目销售占比（GMV）</h3>
          <ReactECharts option={categoryOption} style={{ height: 280 }} />
        </div>
        <div className="chart-card">
          <h3 className="section-title">库存状态</h3>
          <ReactECharts option={stockOption} style={{ height: 280 }} />
        </div>
        <div className="chart-card">
          <h3 className="section-title">活动效果</h3>
          <table className="data-table">
            <thead>
              <tr>
                <th>活动</th>
                <th>商品</th>
                <th>秒杀价/原价</th>
                <th>折扣</th>
                <th>已售/剩余</th>
              </tr>
            </thead>
            <tbody>
              {(ana.activities ?? []).map((a: any, i: number) => (
                <tr key={i}>
                  <td>{a.activity}</td>
                  <td>{a.product}</td>
                  <td>¥{a.seckillPrice ?? '-'}/{a.marketPrice ? `¥${a.marketPrice}` : '-'}</td>
                  <td>{Math.round(a.discount * 10) / 10} 折</td>
                  <td>{a.sold}/{a.stock}</td>
                </tr>
              ))}
              {(ana.activities ?? []).length === 0 && (
                <tr><td colSpan={5}>暂无活动</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
