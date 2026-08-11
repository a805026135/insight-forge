import { useEffect, useRef, useState } from 'react';
import { marked } from 'marked';
import { getToken } from '../../auth/token';

interface ChatMsg {
  role: 'user' | 'assistant';
  content: string;
  streaming?: boolean;
  drafts?: AfterSaleDraft[];
}

interface AfterSaleDraft {
  orderId: string;
  amount: number | null;
  reason: string;
  description: string;
}

interface AfterSale {
  id: number;
  orderId: string;
  productName: string | null;
  amount: number | null;
  reason: string;
  status: string;
  createdAt: string;
}

const PRESETS = [
  '怎么申请退货退款？',
  '发货要多久？',
  '我最近订单什么状态？',
  '怎么开发票？',
  '客服电话是多少？',
];

const STATUS_LABEL: Record<string, string> = {
  APPLIED: '待审核',
  APPROVED: '已通过',
  REFUNDED: '已退款',
  REJECTED: '已驳回',
  CANCELLED: '已取消',
};

export default function SupportPage() {
  const [messages, setMessages] = useState<ChatMsg[]>([]);
  const [question, setQuestion] = useState('');
  const [asking, setAsking] = useState(false);
  const [msg, setMsg] = useState('');
  const [tickets, setTickets] = useState<AfterSale[]>([]);
  const [submitting, setSubmitting] = useState<string | null>(null);
  const listRef = useRef<HTMLDivElement | null>(null);
  const streamRef = useRef<AbortController | null>(null);

  const loadTickets = async () => {
    try {
      const r = await fetch('/api/buyer/after-sale', {
        headers: { Authorization: `Bearer ${getToken()}` },
      }).then((res) => res.json());
      if (r.code === 0) setTickets(r.data ?? []);
    } catch {
      // 忽略历史加载失败
    }
  };

  const loadHistory = async () => {
    try {
      const r = await fetch('/api/buyer/chat/history?limit=50', {
        headers: { Authorization: `Bearer ${getToken()}` },
      }).then((res) => res.json());
      if (r.code === 0) {
        setMessages((r.data ?? []).map((m: any) => ({ role: m.role, content: m.content })));
      }
    } catch {
      // 忽略历史加载失败
    }
  };

  useEffect(() => {
    loadHistory();
    loadTickets();
    return () => streamRef.current?.abort();
  }, []);

  useEffect(() => {
    listRef.current?.scrollTo({ top: listRef.current.scrollHeight, behavior: 'smooth' });
  }, [messages]);

  const askStream = async (q?: string) => {
    const text = (q ?? question).trim();
    if (!text || asking) return;
    setAsking(true);
    setMsg('');
    setMessages((prev) => [...prev, { role: 'user', content: text }]);
    setMessages((prev) => [...prev, { role: 'assistant', content: '', streaming: true }]);
    let acc = '';
    try {
      const controller = new AbortController();
      streamRef.current = controller;
      const resp = await fetch('/api/buyer/assistant/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${getToken()}` },
        body: JSON.stringify({ question: text }),
        signal: controller.signal,
      });
      if (!resp.ok || !resp.body) throw new Error('流式请求失败');
      const reader = resp.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const events = buffer.split('\n\n');
        buffer = events.pop() ?? '';
        for (const ev of events) {
          const dataLine = ev.split('\n').find((l) => l.startsWith('data:'));
          if (!dataLine) continue;
          const data = dataLine.slice(5).trim();
          if (!data) continue;
          acc += data;
          setMessages((prev) => {
            const next = [...prev];
            const idx = next.findIndex((m) => m.role === 'assistant' && m.streaming);
            if (idx >= 0) next[idx] = { ...next[idx], content: acc };
            return next;
          });
        }
      }
      // 流式回答结束后，单独拉取退款草稿（只识别、不重复记录历史）
      let drafts: AfterSaleDraft[] = [];
      try {
        const r = await fetch(`/api/buyer/after-sale/drafts?question=${encodeURIComponent(text)}`, {
          headers: { Authorization: `Bearer ${getToken()}` },
        }).then((res) => res.json());
        if (r.code === 0) drafts = r.data.drafts ?? [];
      } catch {
        // 草稿获取失败不阻塞
      }
      setMessages((prev) => {
        const next = [...prev];
        const idx = next.findIndex((m) => m.role === 'assistant' && m.streaming);
        if (idx >= 0) next[idx] = { ...next[idx], content: acc, streaming: false, drafts };
        return next;
      });
    } catch (e: any) {
      if (e.name !== 'AbortError') {
        setMessages((prev) => {
          const next = [...prev];
          const idx = next.findIndex((m) => m.role === 'assistant' && m.streaming);
          if (idx >= 0) next[idx] = { ...next[idx], content: acc || '（回答获取失败，请重试）', streaming: false };
          return next;
        });
        setMsg(e.message || '提问失败');
      }
    } finally {
      setAsking(false);
    }
  };

  const submitRefund = async (d: AfterSaleDraft) => {
    setSubmitting(d.orderId);
    setMsg('');
    try {
      const r = await fetch('/api/buyer/after-sale/apply', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${getToken()}` },
        body: JSON.stringify({ orderId: d.orderId, reason: d.reason }),
      }).then((res) => res.json());
      if (r.code !== 0) throw new Error(r.message);
      setMsg(`✔ 退款申请已提交（工单 #${r.data.id}），状态：待审核`);
      loadTickets();
    } catch (e: any) {
      setMsg(e.message || '提交失败');
    } finally {
      setSubmitting(null);
    }
  };

  const cancelTicket = async (t: AfterSale) => {
    try {
      const r = await fetch(`/api/buyer/after-sale/${t.id}/cancel`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${getToken()}` },
      }).then((res) => res.json());
      if (r.code !== 0) throw new Error(r.message);
      setMsg('✔ 已取消退款申请');
      loadTickets();
    } catch (e: any) {
      setMsg(e.message || '取消失败');
    }
  };

  return (
    <div className="agent-workbench">
      <div className="page-head">
        <h2>在线客服</h2>
        <span className="badge badge-live">7×24 在线</span>
      </div>
      {msg && <div className={`toast ${msg.startsWith('✔') ? 'success' : 'error'}`}>{msg}</div>}
      <section className="assistant-panel">
        <h3>🤖 平台智能客服</h3>
        <p className="assistant-hint">
          基于平台售后政策/FAQ 与你的订单信息回答；退款由系统生成草稿，你确认后真实提交。
        </p>
        <div className="chat-list" ref={listRef}>
          {messages.length === 0 && (
            <div className="chat-empty">问问退货退款、物流发货、发票或你的订单吧。</div>
          )}
          {messages.map((m, idx) => (
            <div key={idx} className={`chat-bubble ${m.role}`}>
              <div className="chat-avatar">{m.role === 'user' ? '🛒' : '🤖'}</div>
              <div className="chat-body">
                {m.role === 'assistant' ? (
                  <div className="md-body" dangerouslySetInnerHTML={{ __html: marked.parse(m.content || '…') }} />
                ) : (
                  <div className="chat-text">{m.content}</div>
                )}
                {m.role === 'assistant' && m.streaming && <span className="cursor-blink">▍</span>}
                {m.drafts && m.drafts.length > 0 && (
                  <div style={{ marginTop: 10 }}>
                    {m.drafts.map((d, di) => (
                      <div key={di} className="action-card">
                        <span className="action-desc">
                          💳 {d.description}（原因：{d.reason}）
                        </span>
                        <button
                          disabled={submitting === d.orderId}
                          onClick={() => submitRefund(d)}
                        >
                          {submitting === d.orderId ? '提交中…' : '提交退款申请'}
                        </button>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>
          ))}
        </div>
        <div className="assistant-input">
          <input
            value={question}
            onChange={(e) => setQuestion(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && askStream()}
            placeholder="如：怎么申请退货退款？"
          />
          <button disabled={asking} onClick={() => askStream()}>
            {asking ? '思考中…' : '提问'}
          </button>
        </div>
        <div className="preset-questions">
          {PRESETS.map((p) => (
            <button key={p} disabled={asking} onClick={() => askStream(p)}>
              {p}
            </button>
          ))}
        </div>
      </section>

      <section className="assistant-panel" style={{ marginTop: 16 }}>
        <h3>🧾 我的售后单</h3>
        <table className="data-table">
          <thead>
            <tr>
              <th>工单号</th>
              <th>订单号</th>
              <th>商品</th>
              <th>金额</th>
              <th>原因</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {tickets.map((t) => (
              <tr key={t.id}>
                <td>#{t.id}</td>
                <td>{t.orderId}</td>
                <td>{t.productName ?? '-'}</td>
                <td>¥{t.amount ?? '-'}</td>
                <td>{t.reason}</td>
                <td>
                  <span className="badge">{STATUS_LABEL[t.status] ?? t.status}</span>
                </td>
                <td>
                  {t.status === 'APPLIED' && (
                    <button className="btn btn-danger" onClick={() => cancelTicket(t)}>
                      取消
                    </button>
                  )}
                </td>
              </tr>
            ))}
            {tickets.length === 0 && (
              <tr><td colSpan={7}>暂无售后工单</td></tr>
            )}
          </tbody>
        </table>
      </section>
    </div>
  );
}
