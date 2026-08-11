import { useEffect, useState } from 'react';
import { getToken } from '../../auth/token';

interface KbDoc {
  id: number;
  docKey: string;
  title: string;
  sourceType: string;
  merchantId: number | null;
  version: number | null;
  chunkCount: number | null;
}

const SOURCE_LABEL: Record<string, string> = {
  PLAYBOOK: '运营手册',
  FAQ: 'FAQ',
  BUYER_POLICY: '售后政策',
  PRODUCT: '商品目录',
  DECISION: '历史决策',
  ACTIVITY: '活动规则',
};

export default function MerchantKb() {
  const [stats, setStats] = useState<Record<string, any> | null>(null);
  const [docs, setDocs] = useState<KbDoc[]>([]);
  const [msg, setMsg] = useState('');
  const [refreshing, setRefreshing] = useState(false);

  const load = async () => {
    try {
      const [s, d] = await Promise.all([
        fetch('/api/merchant/kb/stats', { headers: { Authorization: `Bearer ${getToken()}` } }).then((r) => r.json()),
        fetch('/api/merchant/kb/docs', { headers: { Authorization: `Bearer ${getToken()}` } }).then((r) => r.json()),
      ]);
      if (s.code !== 0 || d.code !== 0) throw new Error('加载失败');
      setStats(s.data);
      setDocs(d.data.documents ?? []);
    } catch (e: any) {
      setMsg(e.message || '加载失败');
    }
  };

  useEffect(() => {
    load();
  }, []);

  const reindex = async () => {
    setRefreshing(true);
    setMsg('');
    try {
      const r = await fetch('/api/merchant/kb/reindex', {
        method: 'POST',
        headers: { Authorization: `Bearer ${getToken()}` },
      }).then((res) => res.json());
      if (r.code !== 0) throw new Error(r.message);
      setMsg(
        `✔ 刷新完成：商品 ${r.data.productChunks ?? 0} 块 / 决策 ${r.data.decisionChunks ?? 0} 块 / 活动 ${r.data.activityChunks ?? 0} 块`
      );
      load();
    } catch (e: any) {
      setMsg(e.message || '刷新失败');
    } finally {
      setRefreshing(false);
    }
  };

  return (
    <div className="agent-workbench">
      <div className="page-head">
        <h2>知识库管理</h2>
        <span className="badge badge-live">RAG 向量检索</span>
      </div>
      {msg && <div className={`toast ${msg.startsWith('✔') ? 'success' : 'error'}`}>{msg}</div>}
      <div className="kpi-grid">
        <div className="kpi-card highlight">
          <div className="kpi-value">{stats?.documents ?? '-'}</div>
          <div className="kpi-label">文档数</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-value">{stats?.chunks ?? '-'}</div>
          <div className="kpi-label">切块数</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-value">{stats?.dimensions ?? '-'}</div>
          <div className="kpi-label">向量维度</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-value" style={{ fontSize: 14 }}>{stats?.vectorStore ?? '-'}</div>
          <div className="kpi-label">向量库</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-value" style={{ fontSize: 13 }}>{stats?.provider ?? '-'}</div>
          <div className="kpi-label">Embedding</div>
        </div>
      </div>
      <section className="assistant-panel">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <h3>📚 知识库文档（平台级 + 本店）</h3>
          <button className="btn btn-primary" disabled={refreshing} onClick={reindex}>
            {refreshing ? '刷新中…' : '立即刷新本店知识'}
          </button>
        </div>
        <table className="data-table">
          <thead>
            <tr>
              <th>标题</th>
              <th>类型</th>
              <th>归属</th>
              <th>版本</th>
              <th>切块</th>
              <th>docKey</th>
            </tr>
          </thead>
          <tbody>
            {docs.map((d) => (
              <tr key={d.id}>
                <td>{d.title}</td>
                <td>
                  <span className="badge">{SOURCE_LABEL[d.sourceType] ?? d.sourceType}</span>
                </td>
                <td>{d.merchantId == null ? '平台级' : '本店'}</td>
                <td>v{d.version ?? 1}</td>
                <td>{d.chunkCount ?? 0}</td>
                <td style={{ fontSize: 12, color: '#888' }}>{d.docKey}</td>
              </tr>
            ))}
            {docs.length === 0 && (
              <tr>
                <td colSpan={6}>暂无文档</td>
              </tr>
            )}
          </tbody>
        </table>
      </section>
    </div>
  );
}
