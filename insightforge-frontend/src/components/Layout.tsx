import { Link, Outlet, useNavigate, useLocation } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

const MERCHANT_NAV = [
  { to: '/merchant/dashboard', label: '运营大屏' },
  { to: '/merchant/activities', label: '活动管理' },
  { to: '/merchant/guardrails', label: '护栏策略' },
  { to: '/merchant/hitl', label: '人工审批' },
  { to: '/merchant/traces', label: '调用轨迹' },
];

const USER_NAV = [
  { to: '/user/seckill', label: '秒杀抢购' },
  { to: '/user/orders', label: '我的订单' },
];

export default function Layout() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const isMerchant = user?.role === 'merchant';
  const navItems = isMerchant ? MERCHANT_NAV : USER_NAV;

  const handleLogout = () => {
    logout();
    navigate('/');
  };

  return (
    <div className="app-layout">
      <aside className="sidebar">
        <Link to={isMerchant ? '/merchant/dashboard' : '/user/seckill'} className="sidebar-brand">
          Deal Pulse
        </Link>
        <nav className="sidebar-nav">
          {navItems.map((item) => (
            <Link
              key={item.to}
              to={item.to}
              className={`nav-item ${location.pathname === item.to ? 'active' : ''}`}
            >
              {item.label}
            </Link>
          ))}
        </nav>
        <div className="sidebar-footer">
          <div className="user-info">
            <span className="user-name">{user?.displayName}</span>
            <span className="user-role">{isMerchant ? '商家' : '买家'}</span>
          </div>
          <button onClick={handleLogout} className="logout-btn">
            退出登录
          </button>
        </div>
      </aside>
      <main className="main-content">
        <Outlet />
      </main>
    </div>
  );
}
