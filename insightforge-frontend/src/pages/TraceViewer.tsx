import { useEffect, useState } from 'react';
import { Card, Table, Tag, Typography } from 'antd';
import { dashboardApi } from '../api/client';
import type { TraceView } from '../types';

const statusColor: Record<string, string> = {
  SUCCESS: 'green',
  CORRECT_FAILURE: 'orange',
  FAILED: 'red',
};

/**
 * Trace 查看器（对应监测设计文档 L1/L2）：把 Agent 每次离线规划运行以 span 树 + verdict 展示。
 * 这里用列表呈现最近 trace；span 树在后端 agent_trace/agent_span 已落库，前端按需展开即可。
 */
export default function TraceViewer() {
  const [rows, setRows] = useState<TraceView[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    let alive = true;
    const load = () =>
      dashboardApi
        .traces(50)
        .then((r) => alive && setRows(r))
        .catch(() => undefined)
        .finally(() => alive && setLoading(false));
    setLoading(true);
    load();
    const t = setInterval(load, 5000);
    return () => {
      alive = false;
      clearInterval(t);
    };
  }, []);

  return (
    <Card title="Agent 规划运行 Trace（最近 50 条）">
      <Typography.Paragraph type="secondary">
        每条 trace = 一次 Orchestrator 运行；verdict 把三态状态机翻成人话。正确失败（橙）代表
        Agent 主动询问/拒绝/升级——是受控的好信号。
      </Typography.Paragraph>
      <Table<TraceView>
        rowKey="traceId"
        loading={loading}
        dataSource={rows}
        pagination={{ pageSize: 10 }}
        columns={[
          { title: 'Trace ID', dataIndex: 'traceId' },
          { title: '活动', dataIndex: 'activityId' },
          {
            title: '状态',
            dataIndex: 'status',
            render: (s: string) => <Tag color={statusColor[s] ?? 'default'}>{s}</Tag>,
          },
          { title: '结论', dataIndex: 'verdict' },
          { title: '耗时(ms)', dataIndex: 'totalLatencyMs' },
          { title: '成本($)', dataIndex: 'budgetCostUsd' },
          { title: '模型', dataIndex: 'modelVersion' },
        ]}
      />
    </Card>
  );
}
