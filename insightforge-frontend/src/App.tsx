import { useState } from 'react';
import { Layout, Menu, Typography } from 'antd';
import { DashboardOutlined, PartitionOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import Dashboard from './pages/Dashboard';
import TraceViewer from './pages/TraceViewer';
import Hitl from './pages/Hitl';

const { Header, Sider, Content } = Layout;

type PageKey = 'dashboard' | 'traces' | 'hitl';

const menuItems = [
  { key: 'dashboard', icon: <DashboardOutlined />, label: '实时大屏' },
  { key: 'traces', icon: <PartitionOutlined />, label: 'Trace 查看器' },
  { key: 'hitl', icon: <SafetyCertificateOutlined />, label: 'HITL 人工复核' },
];

export default function App() {
  const [page, setPage] = useState<PageKey>('dashboard');

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider theme="dark" breakpoint="lg" collapsible>
        <div
          style={{
            color: '#fff',
            fontWeight: 700,
            fontSize: 18,
            padding: '16px',
            textAlign: 'center',
          }}
        >
          InsightForge 智析
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[page]}
          items={menuItems}
          onClick={(e) => setPage(e.key as PageKey)}
        />
      </Sider>
      <Layout>
        <Header style={{ background: '#fff', paddingLeft: 24 }}>
          <Typography.Title level={4} style={{ margin: '14px 0' }}>
            智能秒杀 / 大促平台 · 控制面可视化
          </Typography.Title>
        </Header>
        <Content style={{ margin: 24 }}>
          {page === 'dashboard' && <Dashboard />}
          {page === 'traces' && <TraceViewer />}
          {page === 'hitl' && <Hitl />}
        </Content>
      </Layout>
    </Layout>
  );
}
