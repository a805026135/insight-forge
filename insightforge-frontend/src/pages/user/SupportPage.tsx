import { useEffect, useRef, useState } from 'react';
import { marked } from 'marked';
import { Modal } from 'antd';
import { getToken } from '../../auth/token';

interface ChatMsg {
  role: 'user' | 'assistant';
  content: string;
  streaming?: boolean;
  drafts?: AfterSaleDraft[];
  human?: { needHuman: boolean; merchants: MerchantContact[] };
}

interface AfterSaleDraft {
  orderId: string;
  amount: number | null;
  reason: string;
  description: string;
}

interface MerchantContact {
  merchantId: number;
  merchantName: string;
}

interface AfterSale {
  id: number;
  orderId: string;
  productName: string | null;
  amount: number | null;
  reason: string;
  status: string;
}

interface SupportTicket {
  id: number;
  merchantId: number;
  question: string;
  answer: string | null;
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

const TICKET_LABEL: Record<string, string> = {
  OPEN: '待商家回复',
  REPLIED: '已回复',
  CLOSED: '已关闭',
};

export default function SupportPage() {
  const [messages, setMessages] = useState<ChatMsg[]>([]);
  const [question, setQuestion] = useState('');
  const [asking, setAsking] = useState(false);
  const [msg, setMsg] = useState('');
  const [tickets, setTickets] = useState<SupportTicket[]>([]);
  const [afterSales, setAfterSales] = useState<AfterSale[]>([]);
  const [submitting, setSubmitting] = useState<string | null>(null);
  const [transferOpen, setTransferOpen] = useState(false);
  const [transferMerchants, setTransferMerchants] = useState<MerchantContact[]>([]);
  const [transferQuestion, setTransferQuestion] = useState('');
  const listRef = useRef<HTMLDivElement | null>(null);
  const streamRef = useRef<AbortController | null>(null);

  const loadTickets = async () => {
    try {
      const r = await fetch('/api/buyer/support-ticket', {
        headers: { Authorization: `Bearer ${getToken()}` },
      }).then((res) => res.json());
      if (r.code === 0) setTickets(r.data ?? []);
    } catch {
      // ignore
    }
  };

  const loadAfterSales = async () => {
    try {
      const r = await fetch('/api/buyer/after-sale', {
        headers: { Authorization: `Bearer ${getToken()}` },
      }).then((res) => res.json());
      if (r.code === 0) setAfterSales(r.data ?? []);
    } catch {
      // ignore
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
      // ignore
    }
  };

  useEffect(() => {
    loadHistory();
    loadTickets();
    loadAfterSales();
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
      // 流式结束后拉取意图：退款草稿 / 转人工商家列表
      let drafts: AfterSaleDraft[] = [];
      let human = { needHuman: false, merchants: [] as MerchantContact[] };
      try {
        const r = await fetch(`/api/buyer/assistant/intent?question=${encodeURIComponent(text)}`, {
          headers: { Authorization: `Bearer ${getToken()}` },
        }).then((res) => res.json());
        if (r.code === 0) {
          drafts = r.data.drafts ?? [];
          human = { needHuman: r.data.needHuman, merchants: r.data.merchants ?? [] };
        }
      } catch {
        // ignore
      }
      setMessages((prev) => {
        const next = [...prev];
        const idx = next.findIndex((m) => m.role === 'assistant' && m.streaming);
        if (idx >= 0) next[idx] = { ...next[idx], content: acc, streaming: false, drafts, human };
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
      loadAfterSales();
    } catch (e: any) {
      setMsg(e.message || '提交失败');
    } finally {
      setSubmitting(null);
    }
  };

  const openTransfer = (questionText: string, merchants: MerchantContact[]) => {
    setTransferQuestion(questionText);
    setTransferMerchants(merchants);
    setTransferOpen(true);
  };

  const submitTransfer = async (m: MerchantContact) => {
    try {
      const r = await fetch('/api/buyer/support-ticket', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${getToken()}` },
        body: JSON.stringify({ merchantId: m.merchantId, question: transferQuestion }),
      }).then((res) => res.json());
      if (r.code !== 0) throw new Error(r.message);
      setMsg(`✔ 已转 ${m.merchantName} 客服，商家回复后你会在这里看到`);
      setTransferOpen(false);
      loadTickets();
    } catch (e: any) {
      setMsg(e.message || '转人工失败');
    }
  };

  const cancelTicket = async (t: SupportTicket) => {
    try {
      const r = await fetch(`/api/buyer/support-ticket/${t.id}/close`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${getToken()}` },
      }).then((res) => res.json());
      if (r.code !== 0) throw new Error(r.message);
      setMsg('✔ 咨询已关闭');
      loadTickets();
    } catch (e: any) {
      setMsg(e.message || '操作失败');
    }
  };

  const cancelAfterSale = async (t: AfterSale) => {
    try {
      const r = await fetch(`/api/buyer/after-sale/${t.id}/cancel`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${getToken()}` },
      }).then((res) => res.json());
      if (r.code !== 0) throw new Error(r.message);
      setMsg('✔ 已取消退款申请');
      loadAfterSales();
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
          回答仅基于平台知识库与你的订单；知识库未收录时自动转商家/人工，绝不编造。
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
                        <span className="action-desc">💳 {d.description}（原因：{d.reason}）</span>
                        <button disabled={submitting === d.orderId} onClick={() => submitRefund(d)}>
                          {submitting === d.orderId ? '提交中…' : '提交退款申请'}
                        </button>
                      </div>
                    ))}
                  </div>
                )}
                {m.human?.needHuman && (
                  <div className="action-card" style={{ marginTop: 10 }}>
                    <span className="action-desc">
                      👨‍💼 知识库暂未收录该问题，已为你转人工通道
                    </span>
                    <button onClick={() => openTransfer(m.content || '', m.human?.merchants ?? [])}>
                      联系商家客服
                    </button>
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
        <h3>💬 我的咨询（转商家客服）</h3>
        <table className="data-table">
          <thead>
            <tr>
              <th>#</th>
              <th>商家</th>
              <th>问题</th>
              <th>商家回复</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {tickets.map((t) => (
              <tr key={t.id}>
                <td>#{t.id}</td>
                <td>商家#{t.merchantId}</td>
                <td style={{ maxWidth: 260 }}>{t.question}</td>
                <td style={{ maxWidth: 260 }}>{t.answer ?? '—'}</td>
                <td><span className="badge">{TICKET_LABEL[t.status] ?? t.status}</span></td>
                <td>
                  {t.status !== 'CLOSED' && (
                    <button className="btn btn-danger" onClick={() => cancelTicket(t)}>关闭</button>
                  )}
                </td>
              </tr>
            ))}
            {tickets.length === 0 && <tr><td colSpan={6}>暂无咨询</td></tr>}
          </tbody>
        </table>
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
            {afterSales.map((t) => (
              <tr key={t.id}>
                <td>#{t.id}</td>
                <td>{t.orderId}</td>
                <td>{t.productName ?? '-'}</td>
                <td>¥{t.amount ?? '-'}</td>
                <td>{t.reason}</td>
                <td><span className="badge">{STATUS_LABEL[t.status] ?? t.status}</span></td>
                <td>
                  {t.status === 'APPLIED' && (
                    <button className="btn btn-danger" onClick={() => cancelAfterSale(t)}>取消</button>
                  )}
                </td>
              </tr>
            ))}
            {afterSales.length === 0 && <tr><td colSpan={7}>暂无售后工单</td></tr>}
          </tbody>
        </table>
      </section>

      <Modal
        title="联系商家客服"
        open={transferOpen}
        onCancel={() => setTransferOpen(false)}
        footer={null}
      >
        <p style={{ marginBottom: 12, color: '#888' }}>
          选择要咨询的商家，你的问题原文将作为工单发给对方：
        </p>
        {transferMerchants.length === 0 && (
          <p style={{ color: '#888' }}>暂无可联系商家（你还没有购买记录）。</p>
        )}
        {transferMerchants.map((m) => (
          <div key={m.merchantId} className="action-card">
            <span className="action-desc">🏬 {m.merchantName}</span>
            <button onClick={() => submitTransfer(m)}>发起咨询</button>
          </div>
        ))}
      </Modal>
    </div>
  );
}
