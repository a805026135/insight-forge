import { useEffect, useRef, useState } from 'react';
import { marked } from 'marked';
import { getToken } from '../../auth/token';
import { merchantApi, AssistantReply } from '../../api';

export default function AgentWorkbench() {
  const [overview, setOverview] = useState<Record<string, number | string> | null>(null);
  const [question, setQuestion] = useState('');
  const [asking, setAsking] = useState(false);
  const [reply, setReply] = useState<AssistantReply | null>(null);
  const [streamText, setStreamText] = useState('');
  const [msg, setMsg] = useState('');
  const [approving, setApproving] = useState<string | null>(null);
  const streamRef = useRef<AbortController | null>(null);
  const [presets] = useState([
    '帮我分析当前库存和销售情况',
    '给我一份双11 活动页文案',
    '热销商品要不要调价？',
    '给 iPhone 16 Pro 增加 50 件库存',
    '把 MacBook Air 下架',
  ]);

  useEffect(() => {
    merchantApi
      .overview()
      .then(setOverview)
      .catch((e) => setMsg(e.message));
    return () => streamRef.current?.abort();
  }, []);

  const askStream = async (q?: string) => {
    const text = (q ?? question).trim();
    if (!text || asking) return;
    setAsking(true);
    setMsg('');
    setReply(null);
    setStreamText('');
    try {
      const controller = new AbortController();
      streamRef.current = controller;
      const resp = await fetch('/api/merchant/assistant/stream', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${getToken()}`,
        },
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
          if (data) {
            setStreamText((prev) => prev + data);
          }
        }
      }
      // 流结束后走一次普通接口拿动作列表
      const r = await merchantApi.assistant(text);
      setReply(r);
    } catch (e: any) {
      if (e.name !== 'AbortError') setMsg(e.message || '提问失败');
    } finally {
      setAsking(false);
    }
  };

  const approve = async (action: NonNullable<AssistantReply['actions']>[number]) => {
    setApproving(action.type + action.productId);
    setMsg('');
    try {
      await fetch('/api/merchant/actions/execute', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${getToken()}` },
        body: JSON.stringify({
          type: action.type,
          productId: action.productId,
          amount: action.amount,
          price: action.price,
        }),
      }).then((r) => r.json());
      setMsg('✅ 操作已执行');
      merchantApi.overview().then(setOverview).catch(() => {});
    } catch (e: any) {
      setMsg(e.message || '执行失败');
    } finally {
      setApproving(null);
    }
  };

  const o = overview ?? {};
  const html = streamText || reply?.answer ? marked.parse(streamText || reply?.answer || '') : '';

  return (
    <div className="agent-workbench">
      <div className="page-head">
        <h2>Agent 运营工作台</h2>
        <span className="badge badge-live">AI 店长团队在线</span>
      </div>
      {msg && <div className={`toast ${msg.startsWith('✅') ? 'success' : 'error'}`}>{msg}</div>}

      <div className="kpi-grid">
        <div className="kpi-card highlight">
          <div className="kpi-value">{o.productCount ?? '-'}</div>
          <div className="kpi-label">在售商品</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-value">{o.totalSold ?? '-'}</div>
          <div className="kpi-label">已售件数</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-value">¥{(o.totalGmv ?? 0).toLocaleString?.() ?? o.totalGmv}</div>
          <div className="kpi-label">累计 GMV</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-value">{o.sellThroughRate ?? '-'}%</div>
          <div className="kpi-label">售罄率</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-value">{o.totalStock ?? '-'}</div>
          <div className="kpi-label">剩余库存</div>
        </div>
      </div>

      <section className="assistant-panel">
        <h3>🤖 AI 运营助手</h3>
        <p className="assistant-hint">
          基于你店铺的真实经营数据流式回答；加库存/上下架/调价等操作由 Agent 生成建议，
          你批准后才执行（HITL 人工审批）。
        </p>
        <div className="assistant-input">
          <input
            value={question}
            onChange={(e) => setQuestion(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && askStream()}
            placeholder="如：给 iPhone 16 Pro 增加 50 件库存 / 为什么卖得这么快？"
          />
          <button disabled={asking} onClick={() => askStream()}>
            {asking ? '思考中…' : '提问'}
          </button>
        </div>
        <div className="preset-questions">
          {presets.map((p) => (
            <button key={p} onClick={() => askStream(p)}>
              {p}
            </button>
          ))}
        </div>
        {(streamText || reply) && (
          <div className="assistant-reply">
            <div className="md-body" dangerouslySetInnerHTML={{ __html: html }} />
            {reply?.actions && reply.actions.length > 0 && (
              <div style={{ marginTop: 12 }}>
                {reply.actions.map((a, idx) => (
                  <div key={idx} className="action-card">
                    <span className="action-desc">⚡ {a.description}</span>
                    <button
                      disabled={approving === a.type + a.productId}
                      onClick={() => approve(a)}
                    >
                      {approving === a.type + a.productId ? '执行中…' : '批准执行'}
                    </button>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}
      </section>
    </div>
  );
}
