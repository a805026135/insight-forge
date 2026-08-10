import { useEffect, useState } from 'react';
import { merchantApi, MerchantProductView } from '../../api';

export default function MerchantInventory() {
  const [list, setList] = useState<MerchantProductView[]>([]);
  const [msg, setMsg] = useState('');
  const [deltas, setDeltas] = useState<Record<number, string>>({});

  const load = async () => {
    try {
      setList(await merchantApi.products());
    } catch (e: any) {
      setMsg(e.message);
    }
  };

  useEffect(() => {
    load();
  }, []);

  const adjust = async (id: number) => {
    const delta = parseInt(deltas[id] || '0', 10);
    if (!delta) return;
    setMsg('');
    try {
      const resp = await fetch(`/api/merchant/inventory/${id}/adjust`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${localStorage.getItem('if-token') || ''}` },
        body: JSON.stringify({ delta }),
      });
      const json = await resp.json();
      if (json.code !== 0) throw new Error(json.message);
      setDeltas((prev) => ({ ...prev, [id]: '' }));
      await load();
    } catch (e: any) {
      setMsg(e.message);
    }
  };

  const toggle = async (id: number) => {
    try {
      await merchantApi.toggleProduct(id);
      await load();
    } catch (e: any) {
      setMsg(e.message);
    }
  };

  const onSale = list.filter((p) => p.status === 'ON_SALE').length;
  const offShelf = list.length - onSale;

  return (
    <div className="page">
      <div className="page-head">
        <h2>库存管理</h2>
      </div>
      <div className="kpi-grid" style={{ marginBottom: 16 }}>
        <div className="kpi-card highlight">
          <div className="kpi-value">{onSale}</div>
          <div className="kpi-label">在售商品</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-value">{offShelf}</div>
          <div className="kpi-label">已下架</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-value">
            {list.reduce((s, p) => s + (p.stock ?? 0), 0)}
          </div>
          <div className="kpi-label">总库存</div>
        </div>
      </div>
      {msg && <div className="toast error">{msg}</div>}
      <div className="order-table-wrap">
        <table className="inv-table">
          <thead>
            <tr>
              <th>商品</th>
              <th>分类</th>
              <th>当前库存</th>
              <th>调整库存</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {list.map((p) => (
              <tr key={p.id}>
                <td>{p.name}</td>
                <td>{p.category}</td>
                <td>{p.stock ?? 0}</td>
                <td>
                  <input
                    type="number"
                    value={deltas[p.id] ?? ''}
                    onChange={(e) => setDeltas((prev) => ({ ...prev, [p.id]: e.target.value }))}
                    placeholder="±数量"
                  />
                  <button onClick={() => adjust(p.id)}>调整</button>
                </td>
                <td>
                  <span className={`badge ${p.status === 'ON_SALE' ? 'badge-live' : 'badge-closed'}`}>
                    {p.status === 'ON_SALE' ? '在售' : '下架'}
                  </span>
                </td>
                <td>
                  <button onClick={() => toggle(p.id)}>
                    {p.status === 'ON_SALE' ? '下架' : '上架'}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {list.length === 0 && !msg && <div className="info-msg">暂无商品</div>}
    </div>
  );
}
