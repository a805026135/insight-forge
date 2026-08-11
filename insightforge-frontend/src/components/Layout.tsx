import { Link, Outlet, useNavigate, useLocation } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

const MERCHANT_NAV = [
  { to: '/merchant/agent', label: 'Agent 工作台' },
  { to: '/merchant/kb', label: '知识库管理' },
  { to: '/merchant/products', label: '商品管理' },
  { to: '/merchant/inventory', label: '库存管理' },
  { to: '/merchant/orders', label: '订单发货' },
  { to: '/merchant/activities', label: '活动管理' },
  { to: '/merchant/hitl', label: '人工审批' },
  { to: '/merchant/support', label: '售后咨询' },
  { to: '/merchant/traces', label: '调用轨迹' },
];

const USER_NAV = [
  { to: '/user/home', label: '首页' },
  { to: '/user/seckill', label: '限时秒杀' },
  { to: '/user/cart', label: '购物车' },
  { to: '/user/orders', label: '我的订单' },
  { to: '/user/support', label: '在线客服' },
  { to: '/user/profile', label: '个人中心' },
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
        <Link to={isMerchant ? '/merchant/dashboard' : '/user/home'} className="sidebar-brand">
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
          <button
            className="theme-toggle"
            onClick={() => {
              const cur = document.documentElement.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
              document.documentElement.setAttribute('data-theme', cur);
              localStorage.setItem('if-theme', cur);
              window.location.reload();
            }}
          >
            {document.documentElement.getAttribute('data-theme') === 'dark' ? '🌙 深色' : '☀️ 浅色'}
          </button>
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
