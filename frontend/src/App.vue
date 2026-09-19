<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { getCart } from '@/api/cart'

const router = useRouter()
const userStore = useUserStore()

const keyword = ref('')
const cartCount = ref(0)

/** 刷新购物车角标（购物车页数量变化后也会调用） */
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
  <el-container class="mall-container">
    <el-header class="mall-header" height="64px">
      <div class="header-inner">
        <RouterLink to="/" class="logo">
          <el-icon :size="28" color="#e93b3d"><ShoppingBag /></el-icon>
          <span class="logo-text">my_mall</span>
        </RouterLink>

        <div class="search-box">
          <el-input
            v-model="keyword"
            placeholder="搜索商品"
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
          <RouterLink to="/seckill" class="nav-link">
            <el-icon><Lightning /></el-icon> 秒杀
          </RouterLink>
          <RouterLink to="/cart" class="nav-link">
            <el-badge :value="cartCount" :hidden="cartCount === 0">
              <el-icon><ShoppingCart /></el-icon>
            </el-badge>
            购物车
          </RouterLink>
          <RouterLink to="/orders" class="nav-link">我的订单</RouterLink>
        </nav>

        <div class="user-area">
          <template v-if="userStore.isLoggedIn">
            <el-dropdown>
              <span class="user-name">
                <el-icon><UserFilled /></el-icon>
                {{ userStore.displayName }}
              </span>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item @click="router.push('/address')">收货地址</el-dropdown-item>
                  <el-dropdown-item v-if="userStore.isAdmin" @click="router.push('/admin')">后台管理</el-dropdown-item>
                  <el-dropdown-item divided @click="onLogout">退出登录</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
          <template v-else>
            <RouterLink to="/login" class="auth-link">登录</RouterLink>
            <RouterLink to="/register" class="auth-link">注册</RouterLink>
          </template>
        </div>
      </div>
    </el-header>

    <el-main class="mall-main">
      <RouterView @cart-changed="refreshCartCount" />
    </el-main>

    <el-footer class="mall-footer" height="48px">
      my_mall · 简历级全栈商城演示（Spring Boot + Vue3 + Redis + RocketMQ + Elasticsearch）
    </el-footer>
  </el-container>
</template>

<style scoped>
.mall-container {
  min-height: 100vh;
  background-color: #f4f4f4;
}

.mall-header {
  background: #fff;
  border-bottom: 2px solid #e93b3d;
}

.header-inner {
  max-width: 1200px;
  margin: 0 auto;
  height: 100%;
  display: flex;
  align-items: center;
  gap: 24px;
}

.logo {
  display: flex;
  align-items: center;
  gap: 6px;
  text-decoration: none;
}

.logo-text {
  font-size: 22px;
  font-weight: 700;
  color: #e93b3d;
}

.search-box {
  flex: 1;
  max-width: 480px;
}

.nav-links {
  display: flex;
  align-items: center;
  gap: 20px;
  margin-left: auto;
}

.nav-link {
  display: flex;
  align-items: center;
  gap: 4px;
  color: #333;
  text-decoration: none;
  font-size: 14px;
}

.nav-link:hover {
  color: #e93b3d;
}

.user-area {
  display: flex;
  align-items: center;
  gap: 12px;
}

.user-name {
  display: flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  color: #333;
  font-size: 14px;
}

.auth-link {
  color: #333;
  text-decoration: none;
  font-size: 14px;
}

.auth-link:hover {
  color: #e93b3d;
}

.mall-main {
  max-width: 1200px;
  margin: 0 auto;
  width: 100%;
  padding: 20px;
}

.mall-footer {
  display: flex;
  align-items: center;
  justify-content: center;
  color: #999;
  font-size: 12px;
  background: #fff;
}
</style>
