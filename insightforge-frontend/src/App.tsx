import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { UserLayout, MerchantLayout } from './components/Layout';
import Login from './pages/Login';
import SeckillPage from './pages/user/SeckillPage';
import OrderHistory from './pages/user/OrderHistory';
import Dashboard from './pages/merchant/Dashboard';
import Activities from './pages/merchant/Activities';
import Guardrails from './pages/merchant/Guardrails';
import Hitl from './pages/merchant/Hitl';
import TraceViewer from './pages/merchant/TraceViewer';

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<Login />} />
        <Route path="/user" element={<UserLayout />}>
          <Route index element={<Navigate to="/user/seckill" replace />} />
          <Route path="seckill" element={<SeckillPage />} />
          <Route path="orders" element={<OrderHistory />} />
        </Route>
        <Route path="/merchant" element={<MerchantLayout />}>
          <Route index element={<Navigate to="/merchant/dashboard" replace />} />
          <Route path="dashboard" element={<Dashboard />} />
          <Route path="activities" element={<Activities />} />
          <Route path="guardrails" element={<Guardrails />} />
          <Route path="hitl" element={<Hitl />} />
          <Route path="traces" element={<TraceViewer />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}
