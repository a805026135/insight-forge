import { useState } from 'react';
import { useAuth } from '../auth/AuthContext';
import { useNavigate } from 'react-router-dom';

type Mode = 'login' | 'register';

export default function Login() {
  const { login, register } = useAuth();
  const navigate = useNavigate();
  const [mode, setMode] = useState<Mode>('login');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [confirm, setConfirm] = useState('');
  const [err, setErr] = useState('');
  const [loading, setLoading] = useState(false);

  const switchMode = (m: Mode) => {
    setMode(m);
    setErr('');
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!username.trim() || !password) {
      setErr('请输入用户名和密码');
      return;
    }
    if (mode === 'register' && password !== confirm) {
      setErr('两次输入的密码不一致');
      return;
    }
    setLoading(true);
    setErr('');
    try {
      const user =
        mode === 'register'
          ? await register(username.trim(), password, displayName.trim() || undefined)
          : await login(username.trim(), password);
      navigate(user.role === 'merchant' ? '/merchant/dashboard' : '/user/seckill');
    } catch (ex: any) {
      setErr(ex.message || (mode === 'register' ? '注册失败' : '登录失败'));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="login-page">
      <div className="login-card">
        <h1 className="brand-title">Deal Pulse</h1>
        <p className="brand-sub">智能秒杀运营平台</p>
        <div className="login-tabs">
          <button
            type="button"
            className={`login-tab ${mode === 'login' ? 'active' : ''}`}
            onClick={() => switchMode('login')}
          >
            登录
          </button>
          <button
            type="button"
            className={`login-tab ${mode === 'register' ? 'active' : ''}`}
            onClick={() => switchMode('register')}
          >
            注册
          </button>
        </div>
        <form onSubmit={handleSubmit} className="login-form">
          {mode === 'register' && (
            <input
              type="text"
              placeholder="昵称（选填）"
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
            />
          )}
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
          {mode === 'register' && (
            <input
              type="password"
              placeholder="确认密码"
              value={confirm}
              onChange={(e) => setConfirm(e.target.value)}
            />
          )}
          {err && <div className="login-error">{err}</div>}
          <button type="submit" disabled={loading}>
            {loading ? '处理中...' : mode === 'login' ? '登 录' : '注 册'}
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
