import { useEffect, useState } from 'react';
import { dashboardApi, TraceView } from '../../api';

export default function Hitl() {
  const [list, setList] = useState<TraceView[]>([]);
  const [err, setErr] = useState('');
  const [actionMsg, setActionMsg] = useState('');

  const refresh = () => {
    dashboardApi.hitl()
      .then(setList)
      .catch((e) => setErr(e.message));
  };

  useEffect(() => {
    refresh();
  }, []);

  const handleApprove = async (traceId: string) => {
    try {
      await dashboardApi.approve(traceId);
      setActionMsg(`Trace ${traceId.slice(0, 8)}... 已通过审批`);
      refresh();
    } catch (ex: any) {
      setActionMsg(`审批失败：${ex.message}`);
    }
  };

  const handleReject = async (traceId: string) => {
    try {
      await dashboardApi.reject(traceId);
      setActionMsg(`Trace ${traceId.slice(0, 8)}... 已驳回`);
      refresh();
    } catch (ex: any) {
      setActionMsg(`驳回失败：${ex.message}`);
    }
  };

  const reviewLabel = (t: TraceView) => {
    if (t.reviewStatus === 'APPROVED') return '已通过';
    if (t.reviewStatus === 'REJECTED') return '已驳回';
    return '待审批';
  };

  return (
    <div className="page">
      <h2>人工审批队列</h2>
      {err && <div className="error">{err}</div>}
      {actionMsg && <div className="toast success">{actionMsg}</div>}
      {list.length === 0 && !err && <p>当前无待审批任务</p>}
      <table className="order-table">
        <thead>
          <tr>
            <th>Trace ID</th>
            <th>活动</th>
            <th>结论</th>
            <th>耗时</th>
            <th>时间</th>
            <th>审批状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          {list.map((t) => (
            <tr key={t.traceId}>
              <td title={t.traceId}>{t.traceId?.slice(0, 8)}...</td>
              <td>{t.activityId}</td>
              <td title={t.verdict} style={{ maxWidth: 280, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {t.verdict}
              </td>
              <td>{t.totalLatencyMs}ms</td>
              <td>{t.startedAt}</td>
              <td>
                <span className={`review-badge review-${t.reviewStatus?.toLowerCase() || 'pending'}`}>
                  {reviewLabel(t)}
                </span>
              </td>
              <td>
                <button
                  onClick={() => handleApprove(t.traceId)}
                  disabled={t.reviewStatus !== null}
                >
                  通过
                </button>
                <button
                  className="danger"
                  onClick={() => handleReject(t.traceId)}
                  disabled={t.reviewStatus !== null}
                >
                  驳回
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
