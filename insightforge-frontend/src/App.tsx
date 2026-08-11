import {
  BrowserRouter,
  Routes,
  Route,
  Navigate,
  useLocation,
} from 'react-router-dom';
import { AuthProvider, useAuth } from './auth/AuthContext';
import Login from './pages/Login';
import HomePage from './pages/user/HomePage';
import SeckillPage from './pages/user/SeckillPage';
import ProductDetail from './pages/user/ProductDetail';
import CartPage from './pages/user/CartPage';
import CheckoutPage from './pages/user/CheckoutPage';
import ProfilePage from './pages/user/ProfilePage';
import OrderHistory from './pages/user/OrderHistory';
import Dashboard from './pages/merchant/Dashboard';
import Activities from './pages/merchant/Activities';
import Guardrails from './pages/merchant/Guardrails';
import Hitl from './pages/merchant/Hitl';
import TraceViewer from './pages/merchant/TraceViewer';
import AgentWorkbench from './pages/merchant/AgentWorkbench';
import MerchantProducts from './pages/merchant/MerchantProducts';
import MerchantInventory from './pages/merchant/MerchantInventory';
import MerchantOrders from './pages/merchant/MerchantOrders';
import MerchantKb from './pages/merchant/MerchantKb';
import MerchantSupport from './pages/merchant/MerchantSupport';
import SupportPage from './pages/user/SupportPage';
import PayConfirm from './pages/PayConfirm';
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
          <Route path="/pay-confirm" element={<PayConfirm />} />
          <Route
            path="/user/*"
            element={
              <ProtectedRoute allowRole="user">
                <Layout />
              </ProtectedRoute>
            }
          >
            <Route index element={<HomePage />} />
            <Route path="home" element={<HomePage />} />
            <Route path="seckill" element={<SeckillPage />} />
            <Route path="product/:id" element={<ProductDetail />} />
            <Route path="cart" element={<CartPage />} />
            <Route path="checkout" element={<CheckoutPage />} />
            <Route path="orders" element={<OrderHistory />} />
            <Route path="support" element={<SupportPage />} />
            <Route path="profile" element={<ProfilePage />} />
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
            <Route path="agent" element={<AgentWorkbench />} />
            <Route path="products" element={<MerchantProducts />} />
            <Route path="inventory" element={<MerchantInventory />} />
            <Route path="orders" element={<MerchantOrders />} />
            <Route path="activities" element={<Activities />} />
            <Route path="guardrails" element={<Guardrails />} />
            <Route path="hitl" element={<Hitl />} />
            <Route path="traces" element={<TraceViewer />} />
            <Route path="kb" element={<MerchantKb />} />
            <Route path="support" element={<MerchantSupport />} />
          </Route>
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  );
}
