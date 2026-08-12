import { Link, Outlet, useNavigate, useLocation } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { useEffect, useState } from 'react';
import { getToken } from '../auth/token';

const MERCHANT_NAV = [
  { to: '/merchant/agent', label: 'Agent 工作台', icon: '✦' },
  { to: '/merchant/kb', label: '知识库管理', icon: '▤' },
  { to: '/merchant/products', label: '商品管理', icon: '◇' },
  { to: '/merchant/inventory', label: '库存管理', icon: '▦' },
  { to: '/merchant/orders', label: '订单发货', icon: '▣' },
  { to: '/merchant/activities', label: '活动管理', icon: '◷' },
  { to: '/merchant/hitl', label: '人工审批', icon: '✓' },
  { to: '/merchant/support', label: '售后咨询', icon: '◌' },
  { to: '/merchant/after-sales', label: '售后处理', icon: '↺' },
  { to: '/merchant/traces', label: '调用轨迹', icon: '⌁' },
  { to: '/merchant/evaluation', label: 'Agent 评测', icon: '◎' },
];

const USER_NAV = [
  { to: '/user/home', label: '首页', icon: '⌂' },
  { to: '/user/seckill', label: '限时秒杀', icon: 'ϟ' },
  { to: '/user/cart', label: '购物车', icon: '▱' },
  { to: '/user/orders', label: '我的订单', icon: '▣' },
  { to: '/user/support', label: '在线客服', icon: '✦' },
  { to: '/user/profile', label: '个人中心', icon: '○' },
];

export default function Layout() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [shopName, setShopName] = useState<string | null>(null);
  const [shopPhone, setShopPhone] = useState('');

  const isMerchant = user?.role === 'merchant';
  const navItems = isMerchant ? MERCHANT_NAV : USER_NAV;

  useEffect(() => {
    if (!isMerchant) return;
    fetch('/api/merchant/profile', { headers: { Authorization: `Bearer ${getToken()}` } })
      .then((r) => r.json())
      .then((r) => {
        if (r.code === 0) {
          setShopName(r.data?.name ?? null);
          setShopPhone(r.data?.servicePhone ?? '');
        }
      })
      .catch(() => {});
  }, [isMerchant]);

  const editShop = () => {
    if (!isMerchant) return;
    const name = window.prompt('店铺名称', shopName ?? '');
    if (name == null || !name.trim()) return;
    const phone = window.prompt('客服电话（买家咨询时会公开引用）', shopPhone);
    if (phone == null || !phone.trim()) return;
    fetch('/api/merchant/store-info', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${getToken()}` },
      body: JSON.stringify({ name: name.trim(), servicePhone: phone.trim() }),
    })
      .then((r) => r.json())
      .then((r) => {
        if (r.code === 0) {
          setShopName(r.data?.name ?? name.trim());
          setShopPhone(r.data?.servicePhone ?? phone.trim());
        } else {
          window.alert(r.message || '保存失败');
        }
      })
      .catch(() => window.alert('保存失败'));
  };

  const handleLogout = () => {
    logout();
    navigate('/');
  };

  return (
    <div className="app-layout">
      <aside className="sidebar">
        <Link to={isMerchant ? '/merchant/dashboard' : '/user/home'} className="sidebar-brand">
          <span className="brand-mark">D</span>
          <span><strong>Deal Pulse</strong><small>智能商业中枢</small></span>
        </Link>
        <nav className="sidebar-nav">
          {navItems.map((item) => (
            <Link
              key={item.to}
              to={item.to}
              className={`nav-item ${location.pathname === item.to ? 'active' : ''}`}
            >
              <span className="nav-icon">{item.icon}</span>
              {item.label}
            </Link>
          ))}
        </nav>
        <div className="sidebar-footer">
          <div className="user-info">
            <span className="user-name">{user?.displayName}</span>
            <span className="user-role">{isMerchant ? '商家' : '买家'}</span>
            {isMerchant && shopName && (
              <button className="shop-name" onClick={editShop} title="点击修改店铺名/客服电话">
                🏪 {shopName} ✎
              </button>
            )}
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
            {document.documentElement.getAttribute('data-theme') === 'dark' ? '深色' : '浅色'}
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
