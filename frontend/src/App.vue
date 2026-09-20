<script setup lang="ts">
import { ref } from 'vue'
import AppHeader from '@/components/layout/AppHeader.vue'
import AppFooter from '@/components/layout/AppFooter.vue'

// 头部通过 defineExpose 暴露 refreshCartCount；购物车页数量变化后经 cart-changed 事件刷新角标
const headerRef = ref<InstanceType<typeof AppHeader>>()
</script>

<template>
  <!-- AppHeader/AppFooter 是自定义组件，el-container 无法自动识别为纵向布局，需显式指定 -->
  <el-container direction="vertical" class="mall-container">
    <AppHeader ref="headerRef" />

    <el-main class="mall-main">
      <RouterView @cart-changed="headerRef?.refreshCartCount()" />
    </el-main>

    <AppFooter />
  </el-container>
</template>

<style scoped>
.mall-container {
  min-height: 100vh;
  background-color: #f5f6f8;
}

.mall-main {
  max-width: 1200px;
  margin: 0 auto;
  width: 100%;
  padding: 20px;
}
</style>
