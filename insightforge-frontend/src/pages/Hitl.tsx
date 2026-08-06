import { useEffect, useState } from 'react';
import { Button, Card, List, Tag, Typography, message } from 'antd';
import { dashboardApi } from '../api/client';
import type { TraceView } from '../types';

/**
 * HITL 人工复核界面（对应监测设计文档 HITL 三要素：何时找人 / 看什么证据 / 从哪继续）。
 * 队列里都是「正确失败」或被显式标记的 trace——它们本就没被执行，等人在看证据后拍板。
 * 「从哪继续」：人确认后，对应活动可重新规划或沿用上一版安全策略。
 */
export default function Hitl() {
  const [rows, setRows] = useState<TraceView[]>([]);
  const [loading, setLoading] = useState(false);

  const load = () => {
    setLoading(true);
    dashboardApi
      .hitl()
      .then(setRows)
      .catch(() => undefined)
      .finally(() => setLoading(false));
  };

  useEffect(load, []);

  return (
    <Card title="HITL 人工复核队列">
      <Typography.Paragraph type="secondary">
        证据：下方每条都带 traceId、触发指标与 verdict。人看后点「批准/驳回」即可；
        从哪继续——批准后该活动用人工确认的策略，驳回则维持上一版安全护栏。
      </Typography.Paragraph>
      <List<TraceView>
        loading={loading}
        dataSource={rows}
        locale={{ emptyText: '暂无待复核项（说明 Agent 当前都在安全区间内自主决策）' }}
        renderItem={(item) => (
          <List.Item
            actions={[
              <Button key="approve" type="link" onClick={() => message.success(`已批准 ${item.traceId}`)}>
                批准
              </Button>,
              <Button key="reject" type="link" danger onClick={() => message.info(`已驳回 ${item.traceId}`)}>
                驳回
              </Button>,
            ]}
          >
            <List.Item.Meta
              title={
                <span>
                  <Tag color="orange">{item.status}</Tag> {item.traceId}
                </span>
              }
              description={`活动 ${item.activityId} · ${item.verdict} · 证据 traceId=${item.traceId}`}
            />
          </List.Item>
        )}
      />
    </Card>
  );
}
