import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import type { Role } from '../mock/data';

export default function Login() {
  const [role, setRole] = useState<Role | null>(null);
  const navigate = useNavigate();

  const handleLogin = () => {
    if (!role) return;
    navigate(role === 'user' ? '/user/seckill' : '/merchant/dashboard');
  };

  return (
    <div className="login-page">
      <div className="login-box">
        <h1>Deal<span>Pulse</span></h1>
        <p className="subtitle">InsightForge · 智能秒杀平台</p>

        <div className="role-selector">
          <div
            className={`role-card ${role === 'user' ? 'selected' : ''}`}
            onClick={() => setRole('user')}
          >
            <div className="icon">🛒</div>
            <div className="label">我是买家</div>
            <div className="desc">秒杀抢购 / 查看订单</div>
          </div>
          <div
            className={`role-card ${role === 'merchant' ? 'selected' : ''}`}
            onClick={() => setRole('merchant')}
          >
            <div className="icon">🏢</div>
            <div className="label">我是商家</div>
            <div className="desc">运营大屏 / 活动管理 / Agent 监控</div>
          </div>
        </div>

        <button onClick={handleLogin} disabled={!role}>
          {role ? (role === 'user' ? '进入买家端' : '进入商家端') : '请选择身份'}
        </button>
      </div>
    </div>
  );
}
