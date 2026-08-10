import { useEffect, useState } from 'react';
import { merchantApi, AssistantReply } from '../../api';

export default function AgentWorkbench() {
  const [overview, setOverview] = useState<Record<string, number | string> | null>(null);
  const [question, setQuestion] = useState('');
  const [asking, setAsking] = useState(false);
  const [reply, setReply] = useState<AssistantReply | null>(null);
  const [msg, setMsg] = useState('');
  const [presets] = useState([
    '帮我分析当前库存和销售情况',
    '给我一份双11 活动页文案',
    '热销商品要不要调价？',
    '哪些商品有售罄风险，需要补货？',
  ]);

  useEffect(() => {
    merchantApi
      .overview()
      .then(setOverview)
      .catch((e) => setMsg(e.message));
  }, []);

  const ask = async (q?: string) => {
    const text = (q ?? question).trim();
    if (!text) return;
    setAsking(true);
    setMsg('');
    try {
      const r = await merchantApi.assistant(text);
      setReply(r);
    } catch (e: any) {
      setMsg(e.message);
    } finally {
      setAsking(false);
    }
  };

  const o = overview ?? {};

  return (
    <div className="agent-workbench">
      <div className="page-head">
        <h2>Agent 运营工作台</h2>
        <span className="badge badge-live">AI 店长团队在线</span>
      </div>
      {msg && <div className="toast error">{msg}</div>}

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
          基于你店铺的真实经营数据回答问题；调价/补货等操作给出建议，需人工确认后执行（HITL）。
        </p>
        <div className="assistant-input">
          <input
            value={question}
            onChange={(e) => setQuestion(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && ask()}
            placeholder="问我任何经营问题，如：为什么 iPhone 卖得这么快？"
          />
          <button disabled={asking} onClick={() => ask()}>
            {asking ? '思考中…' : '提问'}
          </button>
        </div>
        <div className="preset-questions">
          {presets.map((p) => (
            <button key={p} onClick={() => ask(p)}>
              {p}
            </button>
          ))}
        </div>
        {reply && (
          <div className="assistant-reply">
            <div className="reply-badge">
              {reply.suggestionType === 'REPRICE' && '💰 定价建议'}
              {reply.suggestionType === 'RESTOCK' && '📦 库存建议'}
              {reply.suggestionType === 'COPY' && '✍️ 文案建议'}
              {reply.suggestionType === 'ANALYSIS' && '📊 经营分析'}
            </div>
            <p>{reply.answer}</p>
          </div>
        )}
      </section>
    </div>
  );
}
