import {
  BrowserRouter,
  Routes,
  Route,
  Navigate,
  useLocation,
} from 'react-router-dom';
import { lazy, Suspense } from 'react';
import { AuthProvider, useAuth } from './auth/AuthContext';
import Layout from './components/Layout';

const Login = lazy(() => import('./pages/Login'));
const HomePage = lazy(() => import('./pages/user/HomePage'));
const SeckillPage = lazy(() => import('./pages/user/SeckillPage'));
const ProductDetail = lazy(() => import('./pages/user/ProductDetail'));
const CartPage = lazy(() => import('./pages/user/CartPage'));
const CheckoutPage = lazy(() => import('./pages/user/CheckoutPage'));
const ProfilePage = lazy(() => import('./pages/user/ProfilePage'));
const OrderHistory = lazy(() => import('./pages/user/OrderHistory'));
const Dashboard = lazy(() => import('./pages/merchant/Dashboard'));
const Activities = lazy(() => import('./pages/merchant/Activities'));
const Guardrails = lazy(() => import('./pages/merchant/Guardrails'));
const Hitl = lazy(() => import('./pages/merchant/Hitl'));
const TraceViewer = lazy(() => import('./pages/merchant/TraceViewer'));
const AgentWorkbench = lazy(() => import('./pages/merchant/AgentWorkbench'));
const MerchantProducts = lazy(() => import('./pages/merchant/MerchantProducts'));
const MerchantInventory = lazy(() => import('./pages/merchant/MerchantInventory'));
const MerchantOrders = lazy(() => import('./pages/merchant/MerchantOrders'));
const MerchantKb = lazy(() => import('./pages/merchant/MerchantKb'));
const MerchantSupport = lazy(() => import('./pages/merchant/MerchantSupport'));
const MerchantAfterSales = lazy(() => import('./pages/merchant/MerchantAfterSales'));
const AgentEvaluation = lazy(() => import('./pages/merchant/AgentEvaluation'));
const SupportPage = lazy(() => import('./pages/user/SupportPage'));
const PayConfirm = lazy(() => import('./pages/PayConfirm'));

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
        <Suspense fallback={<div className="route-loading"><span />正在加载页面…</div>}>
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
            <Route path="after-sales" element={<MerchantAfterSales />} />
            <Route path="evaluation" element={<AgentEvaluation />} />
          </Route>
          </Routes>
        </Suspense>
      </AuthProvider>
    </BrowserRouter>
  );
}
