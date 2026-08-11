import { useEffect, useState } from 'react';
import { merchantApi, MerchantProductView, MerchantProductInput } from '../../api';

export default function MerchantProducts() {
  const [list, setList] = useState<MerchantProductView[]>([]);
  const [msg, setMsg] = useState('');
  const [showForm, setShowForm] = useState(false);
  const [form, setForm] = useState<MerchantProductInput>({
    name: '',
    sku: '',
    costPrice: 0,
    marketPrice: 0,
    category: '',
    description: '',
  });

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

  const create = async () => {
    setMsg('');
    try {
      await merchantApi.createProduct(form);
      setShowForm(false);
      setForm({ name: '', sku: '', costPrice: 0, marketPrice: 0, category: '', description: '' });
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

  const remove = async (id: number) => {
    if (!window.confirm('确定删除该商品？')) return;
    try {
      await merchantApi.deleteProduct(id);
      await load();
    } catch (e: any) {
      setMsg(e.message);
    }
  };

  return (
    <div className="page">
      <div className="page-head">
        <h2>商品管理</h2>
        <button onClick={() => setShowForm(!showForm)}>
          {showForm ? '收起' : '+ 新增商品'}
        </button>
      </div>
      <div className="kpi-grid" style={{ marginBottom: 16 }}>
        <div className="kpi-card highlight">
          <div className="kpi-value">{list.length}</div>
          <div className="kpi-label">商品总数</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-value">{list.filter((p) => p.status === 'ON_SALE').length}</div>
          <div className="kpi-label">在售</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-value">{list.filter((p) => p.status !== 'ON_SALE').length}</div>
          <div className="kpi-label">已下架</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-value">{list.reduce((s, p) => s + (p.stock ?? 0), 0)}</div>
          <div className="kpi-label">总库存</div>
        </div>
      </div>
      {msg && <div className="toast error">{msg}</div>}
      {showForm && (
        <div className="create-form">
          <h4>新增商品</h4>
          <div className="form-grid">
            <input placeholder="商品名称" value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value })} />
            <input placeholder="SKU 编码" value={form.sku}
              onChange={(e) => setForm({ ...form, sku: e.target.value })} />
            <input placeholder="分类" value={form.category}
              onChange={(e) => setForm({ ...form, category: e.target.value })} />
            <input type="number" placeholder="成本价" value={form.costPrice || ''}
              onChange={(e) => setForm({ ...form, costPrice: parseFloat(e.target.value) || 0 })} />
            <input type="number" placeholder="市场价" value={form.marketPrice || ''}
              onChange={(e) => setForm({ ...form, marketPrice: parseFloat(e.target.value) || 0 })} />
            <input placeholder="商品描述" value={form.description}
              onChange={(e) => setForm({ ...form, description: e.target.value })} />
          </div>
          <button className="btn-primary" onClick={create}>确认新增</button>
        </div>
      )}
      <div className="merchant-product-grid">
        {list.map((p) => (
          <div key={p.id} className="merchant-product-card">
            <img src={p.imageUrl} alt="" />
            <div className="mp-body">
              <div className="mp-name">{p.name}</div>
              <div className="mp-sku">{p.sku}</div>
              <div className="mp-cat">{p.category}</div>
              <div className="mp-price">¥{p.marketPrice ?? '-'}</div>
              <div className="mp-stock">库存 {p.stock ?? 0} 件</div>
              <span className={`badge ${p.status === 'ON_SALE' ? 'badge-live' : 'badge-closed'}`}>
                {p.status === 'ON_SALE' ? '在售' : '已下架'}
              </span>
              <div className="mp-actions">
                <button onClick={() => toggle(p.id)}>
                  {p.status === 'ON_SALE' ? '下架' : '上架'}
                </button>
                <button className="danger" onClick={() => remove(p.id)}>删除</button>
              </div>
            </div>
          </div>
        ))}
      </div>
      {list.length === 0 && !msg && <div className="info-msg">还没有商品，点击右上角新增。</div>}
    </div>
  );
}
