<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { getCart } from '@/api/cart'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const keyword = ref('')
const cartCount = ref(0)

/** 刷新购物车角标（购物车页数量变化后由 App 通过 defineExpose 调用） */
async function refreshCartCount() {
  if (!userStore.isLoggedIn) {
    cartCount.value = 0
    return
  }
  try {
    const cart = await getCart()
    cartCount.value = cart.totalCount
  } catch {
    /* 未登录/网络异常不阻塞主流程 */
  }
}
onMounted(refreshCartCount)
defineExpose({ refreshCartCount })

function onSearch() {
  const kw = keyword.value.trim()
  if (!kw) {
    ElMessage.warning('请输入搜索关键词')
    return
  }
  router.push({ path: '/search', query: { keyword: kw } })
}

async function onLogout() {
  await userStore.logout()
  ElMessage.success('已退出登录')
  router.push('/login')
}
</script>

<template>
  <header class="mall-header">
    <div class="header-inner">
      <RouterLink to="/" class="logo">
        <el-icon :size="30" color="#e93b3d"><ShoppingBag /></el-icon>
        <span class="logo-text">my_mall</span>
      </RouterLink>

      <div class="search-box">
        <el-input
          v-model="keyword"
          placeholder="搜索商品，如：手机 / 耳机"
          size="large"
          clearable
          @keyup.enter="onSearch"
        >
          <template #append>
            <el-button :icon="'Search'" @click="onSearch" />
          </template>
        </el-input>
      </div>

      <nav class="nav-links">
        <RouterLink to="/seckill" class="nav-link" :class="{ active: route.path === '/seckill' }">
          <el-icon><Lightning /></el-icon> 秒杀
        </RouterLink>
        <RouterLink to="/orders" class="nav-link" :class="{ active: route.path === '/orders' }">
          <el-icon><Tickets /></el-icon> 我的订单
        </RouterLink>
        <RouterLink to="/cart" class="nav-link cart-link" :class="{ active: route.path === '/cart' }">
          <el-badge :value="cartCount" :hidden="cartCount === 0" :max="99">
            <el-icon :size="18"><ShoppingCart /></el-icon>
          </el-badge>
          购物车
        </RouterLink>
      </nav>

      <div class="user-area">
        <template v-if="userStore.isLoggedIn">
          <el-dropdown trigger="click">
            <span class="user-name">
              <el-avatar :size="28" class="user-avatar">
                {{ userStore.displayName.slice(0, 1).toUpperCase() }}
              </el-avatar>
              <span class="user-nickname">{{ userStore.displayName }}</span>
              <el-icon class="arrow"><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item :icon="'Location'" @click="router.push('/address')">收货地址</el-dropdown-item>
                <el-dropdown-item v-if="userStore.isAdmin" :icon="'Setting'" @click="router.push('/admin')">
                  后台管理
                </el-dropdown-item>
                <el-dropdown-item divided :icon="'SwitchButton'" @click="onLogout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </template>
        <template v-else>
          <RouterLink to="/login" class="auth-link">登录</RouterLink>
          <RouterLink to="/register" class="auth-link register">注册</RouterLink>
        </template>
      </div>
    </div>
  </header>
</template>

<style scoped>
.mall-header {
  position: sticky;
  top: 0;
  z-index: 100;
  height: 64px;
  background: #fff;
  border-top: 3px solid #e93b3d;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.06);
}

.header-inner {
  max-width: 1200px;
  margin: 0 auto;
  height: 100%;
  display: flex;
  align-items: center;
  gap: 32px;
  padding: 0 20px;
}

.logo {
  display: flex;
  align-items: center;
  gap: 6px;
  text-decoration: none;
  flex-shrink: 0;
}

.logo-text {
  font-size: 24px;
  font-weight: 800;
  letter-spacing: 0.5px;
  color: #e93b3d;
}

.search-box {
  flex: 1;
  max-width: 460px;
}

.search-box :deep(.el-input__wrapper) {
  border-radius: 20px 0 0 20px;
}

.search-box :deep(.el-input-group__append) {
  border-radius: 0 20px 20px 0;
  background: #e93b3d;
  border-color: #e93b3d;
  color: #fff;
}

.search-box :deep(.el-input-group__append .el-icon) {
  color: #fff;
}

.nav-links {
  display: flex;
  align-items: center;
  gap: 24px;
  margin-left: auto;
}

.nav-link {
  display: flex;
  align-items: center;
  gap: 4px;
  color: #333;
  text-decoration: none;
  font-size: 14px;
  padding: 6px 2px;
  border-bottom: 2px solid transparent;
  transition: color 0.2s;
}

.nav-link:hover,
.nav-link.active {
  color: #e93b3d;
}

.nav-link.active {
  border-bottom-color: #e93b3d;
  font-weight: 600;
}

.cart-link :deep(.el-badge__content) {
  background: #e93b3d;
}

.user-area {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
}

.user-name {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  outline: none;
}

.user-avatar {
  background: #e93b3d;
  color: #fff;
  font-size: 14px;
  font-weight: 600;
}

.user-nickname {
  color: #333;
  font-size: 14px;
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.arrow {
  font-size: 12px;
  color: #999;
}

.auth-link {
  color: #333;
  text-decoration: none;
  font-size: 14px;
  transition: color 0.2s;
}

.auth-link:hover {
  color: #e93b3d;
}

.auth-link.register {
  color: #e93b3d;
  font-weight: 600;
}
</style>
