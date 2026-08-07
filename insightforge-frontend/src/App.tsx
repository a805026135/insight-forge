import {
  BrowserRouter,
  Routes,
  Route,
  Navigate,
  useLocation,
} from 'react-router-dom';
import { AuthProvider, useAuth } from './auth/AuthContext';
import Login from './pages/Login';
import SeckillPage from './pages/user/SeckillPage';
import OrderHistory from './pages/user/OrderHistory';
import Dashboard from './pages/merchant/Dashboard';
import Activities from './pages/merchant/Activities';
import Guardrails from './pages/merchant/Guardrails';
import Hitl from './pages/merchant/Hitl';
import TraceViewer from './pages/merchant/TraceViewer';
import Layout from './components/Layout';

function ProtectedRoute({ children, allowRole }: { children: React.ReactNode; allowRole?: string }) {
  const { user } = useAuth();
  const location = useLocation();

  if (!user) {
    return <Navigate to="/" state={{ from: location }} replace />;
  }
  if (allowRole && user.role !== allowRole) {
    // 角色不匹配：买家不能进商家页面，反之亦然
    return <Navigate to={user.role === 'merchant' ? '/merchant/dashboard' : '/user/seckill'} replace />;
  }
  return <>{children}</>;
}

function PublicRoute({ children }: { children: React.ReactNode }) {
  const { user } = useAuth();
  if (user) {
    return <Navigate to={user.role === 'merchant' ? '/merchant/dashboard' : '/user/seckill'} replace />;
  }
  return <>{children}</>;
}

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Routes>
          <Route
            path="/"
            element={
              <PublicRoute>
                <Login />
              </PublicRoute>
            }
          />
          <Route
            path="/user/*"
            element={
              <ProtectedRoute allowRole="user">
                <Layout />
              </ProtectedRoute>
            }
          >
            <Route path="seckill" element={<SeckillPage />} />
            <Route path="orders" element={<OrderHistory />} />
          </Route>
          <Route
            path="/merchant/*"
            element={
              <ProtectedRoute allowRole="merchant">
                <Layout />
              </ProtectedRoute>
            }
          >
            <Route path="dashboard" element={<Dashboard />} />
            <Route path="activities" element={<Activities />} />
            <Route path="guardrails" element={<Guardrails />} />
            <Route path="hitl" element={<Hitl />} />
            <Route path="traces" element={<TraceViewer />} />
          </Route>
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  );
}
