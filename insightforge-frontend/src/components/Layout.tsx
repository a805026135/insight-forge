import { NavLink, Outlet } from 'react-router-dom';

export function UserLayout() {
  return (
    <div className="app-layout">
      <aside className="sidebar">
        <div className="sidebar-brand">
          <h1>Deal<span>Pulse</span></h1>
          <p>User Terminal</p>
        </div>
        <nav className="sidebar-nav">
          <NavLink to="/user/seckill" className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}>
            <span className="icon">⚡</span> <span>秒杀抢购</span>
          </NavLink>
          <NavLink to="/user/orders" className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}>
            <span className="icon">📋</span> <span>我的订单</span>
          </NavLink>
        </nav>
        <div className="sidebar-footer">
          <div className="avatar">A</div>
          <div className="user-info">
            <div className="user-name">Alice</div>
            <div className="user-role">普通用户</div>
          </div>
        </div>
      </aside>
      <main className="main-content">
        <Outlet />
      </main>
    </div>
  );
}

export function MerchantLayout() {
  return (
    <div className="app-layout">
      <aside className="sidebar">
        <div className="sidebar-brand">
          <h1>Deal<span>Pulse</span></h1>
          <p>Command Center</p>
        </div>
        <nav className="sidebar-nav">
          <div className="nav-section">监控</div>
          <NavLink to="/merchant/dashboard" className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}>
            <span className="icon">📊</span> <span>运营大屏</span>
          </NavLink>
          <div className="nav-section">业务</div>
          <NavLink to="/merchant/activities" className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}>
            <span className="icon">🎯</span> <span>活动管理</span>
          </NavLink>
          <div className="nav-section">Agent 治理</div>
          <NavLink to="/merchant/guardrails" className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}>
            <span className="icon">🛡️</span> <span>护栏策略</span>
          </NavLink>
          <NavLink to="/merchant/hitl" className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}>
            <span className="icon">✋</span> <span>人工审批</span>
          </NavLink>
          <NavLink to="/merchant/traces" className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}>
            <span className="icon">🔍</span> <span>Agent 轨迹</span>
          </NavLink>
        </nav>
        <div className="sidebar-footer">
          <div className="avatar">M</div>
          <div className="user-info">
            <div className="user-name">Admin</div>
            <div className="user-role">商户管理员</div>
          </div>
        </div>
      </aside>
      <main className="main-content">
        <Outlet />
      </main>
    </div>
  );
}
