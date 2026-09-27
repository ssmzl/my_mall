<script setup lang="ts">
import { useRoute } from 'vue-router'

const route = useRoute()
</script>

<template>
  <el-container class="admin-layout">
    <el-aside width="220px" class="admin-aside">
      <div class="admin-logo">my_mall 后台管理</div>
      <el-menu router :default-active="route.path" class="admin-menu">
        <el-menu-item index="/admin/dashboard">
          <el-icon><DataLine /></el-icon>
          <span>数据看板</span>
        </el-menu-item>
        <el-menu-item index="/admin/categories">
          <el-icon><Menu /></el-icon>
          <span>分类管理</span>
        </el-menu-item>
        <el-menu-item index="/admin/products">
          <el-icon><Goods /></el-icon>
          <span>商品管理</span>
        </el-menu-item>
        <el-menu-item index="/admin/orders">
          <el-icon><Tickets /></el-icon>
          <span>订单管理</span>
        </el-menu-item>
        <el-menu-item index="/admin/seckill">
          <el-icon><AlarmClock /></el-icon>
          <span>秒杀管理</span>
        </el-menu-item>
        <el-menu-item index="/admin/ai-config">
          <el-icon><Setting /></el-icon>
          <span>AI 配置</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="admin-header">
        <span class="admin-header-title">商城运营管理系统</span>
        <router-link to="/" class="back-link">
          <el-icon><Back /></el-icon>
          返回商城
        </router-link>
      </el-header>
      <el-main class="admin-main">
        <RouterView />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.admin-layout {
  min-height: 100vh;
}

.admin-aside {
  background: #001529;
}

.admin-logo {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 16px;
  font-weight: 600;
  letter-spacing: 1px;
}

.admin-menu {
  border-right: none;
  background: #001529;
}

.admin-menu :deep(.el-menu-item) {
  color: rgba(255, 255, 255, 0.65);
}

.admin-menu :deep(.el-menu-item.is-active) {
  color: #fff;
  background: #e4393c;
}

.admin-menu :deep(.el-menu-item:hover) {
  background: rgba(255, 255, 255, 0.08);
}

.admin-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #f0f0f0;
  background: #fff;
}

.admin-header-title {
  font-size: 16px;
  font-weight: 600;
  color: #333;
}

.back-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: #409eff;
  text-decoration: none;
  font-size: 14px;
}

.back-link:hover {
  opacity: 0.8;
}

.admin-main {
  background: #f5f7fa;
}
</style>
