import { useEffect, useRef, useState } from 'react';
import { Modal, Input } from 'antd';
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

interface CustomDoc {
  id: number;
  docKey: string;
  title: string;
  content: string;
}

const SOURCE_LABEL: Record<string, string> = {
  PLAYBOOK: '运营手册',
  FAQ: 'FAQ',
  BUYER_POLICY: '售后政策',
  PRODUCT: '商品目录',
  DECISION: '历史决策',
  ACTIVITY: '活动规则',
  CUSTOM: '自定义',
};

export default function MerchantKb() {
  const [stats, setStats] = useState<Record<string, any> | null>(null);
  const [docs, setDocs] = useState<KbDoc[]>([]);
  const [msg, setMsg] = useState('');
  const [refreshing, setRefreshing] = useState(false);
  const [uploadOpen, setUploadOpen] = useState(false);
  const [upTitle, setUpTitle] = useState('');
  const [upContent, setUpContent] = useState('');
  const [uploading, setUploading] = useState(false);
  const [deleting, setDeleting] = useState<string | null>(null);
  const [viewDoc, setViewDoc] = useState<CustomDoc | null>(null);
  const [viewLoading, setViewLoading] = useState(false);
  const [editDoc, setEditDoc] = useState<CustomDoc | null>(null);
  const [editTitle, setEditTitle] = useState('');
  const [editContent, setEditContent] = useState('');
  const [editing, setEditing] = useState(false);
  const fileRef = useRef<HTMLInputElement | null>(null);

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

  const readFile = (file: File) => {
    const reader = new FileReader();
    reader.onload = () => setUpContent(String(reader.result ?? ''));
    reader.readAsText(file, 'utf-8');
  };

  const upload = async () => {
    if (!upTitle.trim() || !upContent.trim()) {
      setMsg('标题和内容不能为空');
      return;
    }
    setUploading(true);
    setMsg('');
    try {
      const r = await fetch('/api/merchant/kb/custom', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${getToken()}` },
        body: JSON.stringify({ title: upTitle.trim(), content: upContent }),
      }).then((res) => res.json());
      if (r.code !== 0) throw new Error(r.message);
      setMsg(`✔ 已入库 ${r.data.chunks} 个切块，商家助手问答即可检索到这份知识`);
      setUploadOpen(false);
      setUpTitle('');
      setUpContent('');
      load();
    } catch (e: any) {
      setMsg(e.message || '上传失败');
    } finally {
      setUploading(false);
    }
  };

  const view = async (doc: KbDoc) => {
    setViewLoading(true);
    setViewDoc({ id: doc.id, docKey: doc.docKey, title: doc.title, content: '' });
    setMsg('');
    try {
      const r = await fetch(`/api/merchant/kb/custom/detail?docKey=${encodeURIComponent(doc.docKey)}`, {
        headers: { Authorization: `Bearer ${getToken()}` },
      }).then((res) => res.json());
      if (r.code !== 0) throw new Error(r.message);
      setViewDoc(r.data);
    } catch (e: any) {
      setMsg(e.message || '查看失败');
      setViewDoc(null);
    } finally {
      setViewLoading(false);
    }
  };

  const openEdit = (doc: KbDoc) => {
    fetch(`/api/merchant/kb/custom/detail?docKey=${encodeURIComponent(doc.docKey)}`, {
      headers: { Authorization: `Bearer ${getToken()}` },
    })
      .then((res) => res.json())
      .then((r) => {
        if (r.code !== 0) throw new Error(r.message);
        setEditDoc(r.data);
        setEditTitle(r.data.title ?? '');
        setEditContent(r.data.content ?? '');
      })
      .catch((e: any) => setMsg(e.message || '加载失败'));
  };

  const saveEdit = async () => {
    if (!editDoc) return;
    if (!editTitle.trim() || !editContent.trim()) {
      setMsg('标题和内容不能为空');
      return;
    }
    setEditing(true);
    setMsg('');
    try {
      const r = await fetch('/api/merchant/kb/custom', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${getToken()}` },
        body: JSON.stringify({ docKey: editDoc.docKey, title: editTitle.trim(), content: editContent }),
      }).then((res) => res.json());
      if (r.code !== 0) throw new Error(r.message);
      setMsg(`✔ 已更新并重新入库 ${r.data.chunks} 个切块`);
      setEditDoc(null);
      load();
    } catch (e: any) {
      setMsg(e.message || '保存失败');
    } finally {
      setEditing(false);
    }
  };

  const remove = async (doc: KbDoc) => {
    setDeleting(doc.docKey);
    setMsg('');
    try {
      const r = await fetch(`/api/merchant/kb/custom?docKey=${encodeURIComponent(doc.docKey)}`, {
        method: 'DELETE',
        headers: { Authorization: `Bearer ${getToken()}` },
      }).then((res) => res.json());
      if (r.code !== 0) throw new Error(r.message);
      setMsg('✔ 已删除该知识（向量库与切块同步清理）');
      load();
    } catch (e: any) {
      setMsg(e.message || '删除失败');
    } finally {
      setDeleting(null);
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
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12 }}>
          <h3>📚 知识库文档（平台级 + 本店）</h3>
          <div style={{ display: 'flex', gap: 8 }}>
            <button className="btn btn-primary" disabled={refreshing} onClick={reindex}>
              {refreshing ? '刷新中…' : '重建本店知识'}
            </button>
            <button className="btn" onClick={() => setUploadOpen(true)}>
              ＋ 上传我的知识
            </button>
          </div>
        </div>
        <table className="data-table">
          <thead>
            <tr>
              <th>标题</th>
              <th>类型</th>
              <th>归属</th>
              <th>版本</th>
              <th>切块</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {docs.map((d) => (
              <tr key={d.id}>
                <td title={d.docKey}>{d.title}</td>
                <td>
                  <span className="badge">{SOURCE_LABEL[d.sourceType] ?? d.sourceType}</span>
                </td>
                <td>{d.merchantId == null ? '平台级' : '本店'}</td>
                <td>v{d.version ?? 1}</td>
                <td>{d.chunkCount ?? 0}</td>
                <td>
                  <div style={{ display: 'flex', gap: 6 }}>
                    {d.sourceType === 'CUSTOM' ? (
                      <>
                        <button disabled={viewLoading} onClick={() => view(d)}>
                          查看
                        </button>
                        <button onClick={() => openEdit(d)}>编辑</button>
                        <button className="btn btn-danger" disabled={deleting === d.docKey} onClick={() => remove(d)}>
                          {deleting === d.docKey ? '删除中…' : '删除'}
                        </button>
                      </>
                    ) : (
                      <span style={{ color: '#888', fontSize: 12 }}>内置</span>
                    )}
                  </div>
                </td>
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

      <Modal
        title={viewDoc?.title ?? '知识详情'}
        open={viewDoc != null}
        onCancel={() => setViewDoc(null)}
        footer={null}
      >
        {viewLoading ? (
          <p>加载中…</p>
        ) : (
          <pre style={{ whiteSpace: 'pre-wrap', fontSize: 13, lineHeight: 1.7 }}>
            {viewDoc?.content ?? ''}
          </pre>
        )}
      </Modal>

      <Modal
        title="编辑我的知识"
        open={editDoc != null}
        onCancel={() => setEditDoc(null)}
        onOk={saveEdit}
        okText="保存并重新入库"
        cancelText="取消"
        confirmLoading={editing}
      >
        <Input
          placeholder="标题"
          value={editTitle}
          maxLength={100}
          onChange={(e) => setEditTitle(e.target.value)}
          style={{ marginBottom: 12 }}
        />
        <Input.TextArea
          placeholder="知识内容"
          value={editContent}
          rows={8}
          onChange={(e) => setEditContent(e.target.value)}
        />
      </Modal>

      <Modal
        title="上传我的知识"
        open={uploadOpen}
        onCancel={() => setUploadOpen(false)}
        onOk={upload}
        okText="入库"
        cancelText="取消"
        confirmLoading={uploading}
      >
        <p style={{ marginBottom: 12, color: '#888' }}>
          支持粘贴文本或上传 .txt / .md 文件（≤10 万字符）。入库后仅本店可见，商家助手问答会自动引用。
        </p>
        <Input
          placeholder="标题，如：门店运营 SOP / 大促客服话术"
          value={upTitle}
          maxLength={100}
          onChange={(e) => setUpTitle(e.target.value)}
          style={{ marginBottom: 12 }}
        />
        <Input.TextArea
          placeholder="粘贴知识内容…"
          value={upContent}
          rows={8}
          onChange={(e) => setUpContent(e.target.value)}
          style={{ marginBottom: 12 }}
        />
        <input
          ref={fileRef}
          type="file"
          accept=".txt,.md,text/plain,text/markdown"
          onChange={(e) => {
            const f = e.target.files?.[0];
            if (f) readFile(f);
          }}
          style={{ fontSize: 13 }}
        />
      </Modal>
    </div>
  );
}
