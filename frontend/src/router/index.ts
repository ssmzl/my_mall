import { createRouter, createWebHistory } from 'vue-router'
import { getToken } from '@/api/http'
import { useUserStore } from '@/stores/user'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    // ---------- 前台 ----------
    { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue'), meta: { title: '登录' } },
    { path: '/register', name: 'register', component: () => import('@/views/RegisterView.vue'), meta: { title: '注册' } },
    { path: '/', name: 'home', component: () => import('@/views/HomeView.vue'), meta: { title: '首页' } },
    { path: '/products', name: 'products', component: () => import('@/views/ProductListView.vue'), meta: { title: '商品列表' } },
    { path: '/search', name: 'search', component: () => import('@/views/SearchView.vue'), meta: { title: '搜索' } },
    { path: '/product/:id', name: 'product-detail', component: () => import('@/views/ProductDetailView.vue'), meta: { title: '商品详情' } },
    { path: '/seckill', name: 'seckill', component: () => import('@/views/SeckillView.vue'), meta: { title: '秒杀' } },
    // ---------- 需登录 ----------
    { path: '/cart', name: 'cart', component: () => import('@/views/CartView.vue'), meta: { title: '购物车', requiresAuth: true } },
    { path: '/checkout', name: 'checkout', component: () => import('@/views/CheckoutView.vue'), meta: { title: '确认订单', requiresAuth: true } },
    { path: '/orders', name: 'orders', component: () => import('@/views/OrderListView.vue'), meta: { title: '我的订单', requiresAuth: true } },
    { path: '/orders/:orderNo', name: 'order-detail', component: () => import('@/views/OrderDetailView.vue'), meta: { title: '订单详情', requiresAuth: true } },
    { path: '/address', name: 'address', component: () => import('@/views/AddressView.vue'), meta: { title: '收货地址', requiresAuth: true } },
    // ---------- 后台管理（需管理员） ----------
    { path: '/admin', name: 'admin', component: () => import('@/views/admin/AdminLayout.vue'), meta: { title: '后台管理', requiresAuth: true, requiresAdmin: true }, children: [
      { path: '', redirect: { name: 'admin-dashboard' } },
      { path: 'dashboard', name: 'admin-dashboard', component: () => import('@/views/admin/AdminDashboardView.vue'), meta: { title: '数据看板', requiresAuth: true, requiresAdmin: true } },
      { path: 'ai-config', name: 'admin-ai-config', component: () => import('@/views/admin/AdminAiConfigView.vue'), meta: { title: 'AI 配置', requiresAuth: true, requiresAdmin: true } },
      { path: 'categories', name: 'admin-categories', component: () => import('@/views/admin/AdminCategoryView.vue'), meta: { title: '分类管理', requiresAuth: true, requiresAdmin: true } },
      { path: 'products', name: 'admin-products', component: () => import('@/views/admin/AdminProductView.vue'), meta: { title: '商品管理', requiresAuth: true, requiresAdmin: true } },
      { path: 'orders', name: 'admin-orders', component: () => import('@/views/admin/AdminOrderView.vue'), meta: { title: '订单管理', requiresAuth: true, requiresAdmin: true } },
      { path: 'seckill', name: 'admin-seckill', component: () => import('@/views/admin/AdminSeckillView.vue'), meta: { title: '秒杀管理', requiresAuth: true, requiresAdmin: true } },
    ] },
    { path: '/:pathMatch(.*)*', name: 'not-found', redirect: '/' },
  ],
})

// 全局守卫：登录校验 + 管理员校验 + 页面标题
router.beforeEach((to) => {
  if (to.meta.title) {
    document.title = `${to.meta.title} - my_mall`
  }
  const userStore = useUserStore()
  // 刷新后从 localStorage 恢复用户信息（token 存在与否以 api/http 为准）
  userStore.restore()

  const hasToken = getToken() !== ''
  if (to.meta.requiresAuth && !hasToken) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.meta.requiresAdmin && userStore.role !== 1) {
    // 非管理员访问后台：回首页
    return { path: '/' }
  }
  // 已登录访问登录/注册页：回首页
  if ((to.name === 'login' || to.name === 'register') && hasToken) {
    return { path: '/' }
  }
  return true
})

export default router
