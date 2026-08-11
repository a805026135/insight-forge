import { useEffect, useRef, useState } from 'react';
import { marked } from 'marked';
import { getToken } from '../../auth/token';

interface ChatMsg {
  role: 'user' | 'assistant';
  content: string;
  streaming?: boolean;
}

const PRESETS = [
  '怎么申请退货退款？',
  '发货要多久？',
  '我最近订单什么状态？',
  '怎么开发票？',
  '客服电话是多少？',
];

export default function SupportPage() {
  const [messages, setMessages] = useState<ChatMsg[]>([]);
  const [question, setQuestion] = useState('');
  const [asking, setAsking] = useState(false);
  const [msg, setMsg] = useState('');
  const listRef = useRef<HTMLDivElement | null>(null);
  const streamRef = useRef<AbortController | null>(null);

  useEffect(() => {
    listRef.current?.scrollTo({ top: listRef.current.scrollHeight, behavior: 'smooth' });
  }, [messages]);

  useEffect(() => () => streamRef.current?.abort(), []);

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
      setMessages((prev) => {
        const next = [...prev];
        const idx = next.findIndex((m) => m.role === 'assistant' && m.streaming);
        if (idx >= 0) next[idx] = { ...next[idx], content: acc, streaming: false };
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
          基于平台售后政策/FAQ 与你的订单信息流式回答；订单数据仅你本人可见。
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
    </div>
  );
}
