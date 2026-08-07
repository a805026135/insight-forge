import { useEffect, useState } from 'react';
import { dashboardApi, TraceView } from '../../api';

export default function Hitl() {
  const [list, setList] = useState<TraceView[]>([]);
  const [err, setErr] = useState('');

  useEffect(() => {
    dashboardApi.hitl()
      .then(setList)
      .catch((e) => setErr(e.message));
  }, []);

  return (
    <div className="page">
      <h2>人工审批队列</h2>
      {err && <div className="error">{err}</div>}
      {list.length === 0 && !err && <p>当前无待审批任务</p>}
      <table className="order-table">
        <thead>
          <tr>
            <th>Trace ID</th>
            <th>活动</th>
            <th>状态</th>
            <th>延迟</th>
            <th>时间</th>
            <th>审批</th>
          </tr>
        </thead>
        <tbody>
          {list.map((t) => (
            <tr key={t.traceId}>
              <td title={t.traceId}>{t.traceId?.slice(0, 8)}...</td>
              <td>{t.activityId}</td>
              <td className={`status-${t.status?.toLowerCase()}`}>{t.status}</td>
              <td>{t.totalLatencyMs}ms</td>
              <td>{t.startedAt}</td>
              <td>
                <button onClick={() => alert('审批通过（演示：无实际操作）')}>通过</button>
                <button className="danger" onClick={() => alert('已驳回（演示）')}>驳回</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
