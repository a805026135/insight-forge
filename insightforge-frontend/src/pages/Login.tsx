import { useState } from 'react';
import { useAuth } from '../auth/AuthContext';
import { useNavigate } from 'react-router-dom';

export default function Login() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [err, setErr] = useState('');
  const [loading, setLoading] = useState(false);

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!username.trim() || !password.trim()) {
      setErr('请输入用户名和密码');
      return;
    }
    setLoading(true);
    setErr('');
    try {
      const user = await login(username.trim(), password);
      navigate(user.role === 'merchant' ? '/merchant/dashboard' : '/user/seckill');
    } catch (ex: any) {
      setErr(ex.message || '登录失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="login-page">
      <div className="login-card">
        <h1 className="brand-title">Deal Pulse</h1>
        <p className="brand-sub">交易作战指挥中心</p>
        <form onSubmit={handleLogin} className="login-form">
          <input
            type="text"
            placeholder="用户名"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoFocus
          />
          <input
            type="password"
            placeholder="密码"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
          {err && <div className="login-error">{err}</div>}
          <button type="submit" disabled={loading}>
            {loading ? '登录中...' : '登 录'}
          </button>
        </form>
        <div className="login-hint">
          <span>演示账号：</span>
          <div>商家 — admin / admin123</div>
          <div>买家 — user1 / 123456</div>
        </div>
      </div>
    </div>
  );
}
