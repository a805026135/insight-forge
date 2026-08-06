import { Card, Col, Row, Statistic, Tag, Typography, Alert } from 'antd';
import ReactECharts from 'echarts-for-react';
import { useDashboardSnapshot } from '../hooks/useDashboardSnapshot';

/**
 * 实时大屏：秒杀经营 + Agent 规划质量 + 护栏风险三块。
 * 数据来自 useDashboardSnapshot（WS 优先，轮询兜底），天然「实时刷新」。
 */
export default function Dashboard() {
  const { snapshot, live } = useDashboardSnapshot();
  const s = snapshot?.seckill;
  const a = snapshot?.agent;
  const g = snapshot?.guardrail;

  const riskColor =
    g?.topRiskLevel === '高' ? 'red' : g?.topRiskLevel === '中' ? 'orange' : 'green';

  return (
    <div>
      <Alert
        style={{ marginBottom: 16 }}
        type={live ? 'success' : 'info'}
        showIcon
        message={live ? '实时通道：WebSocket 已连接' : '兜底通道：每 3 秒轮询刷新'}
      />

      <Row gutter={16}>
        <Col span={6}>
          <Card>
            <Statistic title="进行中活动" value={s?.liveActivities ?? 0} suffix={`/ ${s?.totalActivities ?? 0}`} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title="累计订单" value={s?.totalOrders ?? 0} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title="已售 / 总库存" value={s?.soldStock ?? 0} suffix={`/ ${s?.totalStock ?? 0}`} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic
              title="最高护栏风险"
              value={g?.topRiskLevel ?? '低'}
              valueStyle={{ color: riskColor === 'red' ? '#cf1322' : riskColor === 'orange' ? '#fa8c16' : '#3f8600' }}
            />
            <Tag color={riskColor} style={{ marginTop: 8 }}>
              {g?.activitiesWithPolicy ?? 0} 个活动已生效策略
            </Tag>
          </Card>
        </Col>
      </Row>

      <Row gutter={16} style={{ marginTop: 16 }}>
        <Col span={12}>
          <Card title="秒杀库存水位（已售 vs 剩余）">
            <ReactECharts
              option={{
                tooltip: { trigger: 'axis' },
                xAxis: { type: 'category', data: ['已售', '剩余'] },
                yAxis: { type: 'value' },
                series: [
                  {
                    type: 'bar',
                    data: [s?.soldStock ?? 0, (s?.totalStock ?? 0) - (s?.soldStock ?? 0)],
                    itemStyle: { color: '#1677ff' },
                  },
                ],
              }}
            />
          </Card>
        </Col>
        <Col span={12}>
          <Card title="Agent 正确失败率（红线指标）">
            <ReactECharts
              option={{
                series: [
                  {
                    type: 'gauge',
                    progress: { show: true },
                    detail: { formatter: (v: number) => `${(v * 100).toFixed(1)}%` },
                    data: [{ value: a?.correctFailureRate ?? 0, name: '正确失败率' }],
                    min: 0,
                    max: 1,
                  },
                ],
              }}
            />
            <Typography.Paragraph style={{ marginTop: 8 }}>
              规划运行 {a?.totalRuns ?? 0} 次，其中正确失败（ASK/REFUSE/ESCALATE）{a?.correctFailureRuns ?? 0} 次，
              失败 {a?.failedRuns ?? 0} 次。正确失败率越高，说明 Agent 在该拒绝时越克制——这是好信号。
            </Typography.Paragraph>
          </Card>
        </Col>
      </Row>
    </div>
  );
}
