<script setup lang="ts">
/**
 * 用户模块（登录/注册）共用的页面外壳：
 * 渐变背景 + 装饰光斑 + 品牌卡片容器，表单内容通过默认插槽传入。
 */
defineProps<{
  title: string
  subtitle?: string
}>()
</script>

<template>
  <div class="auth-shell">
    <div class="deco deco-1" />
    <div class="deco deco-2" />
    <div class="auth-card">
      <div class="brand">
        <el-icon :size="40" color="#e93b3d"><ShoppingBag /></el-icon>
        <span class="brand-name">my_mall</span>
      </div>
      <h2 class="title">{{ title }}</h2>
      <p v-if="subtitle" class="subtitle">{{ subtitle }}</p>
      <slot />
    </div>
  </div>
</template>

<style scoped>
.auth-shell {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: calc(100vh - 64px - 120px);
  padding: 40px 16px;
  background: linear-gradient(135deg, #fff1f0 0%, #f7f8fa 45%, #eef3ff 100%);
  border-radius: 12px;
  overflow: hidden;
}

/* 装饰光斑（纯视觉，不响应交互） */
.deco {
  position: absolute;
  border-radius: 50%;
  filter: blur(60px);
  opacity: 0.55;
  pointer-events: none;
}

.deco-1 {
  width: 320px;
  height: 320px;
  background: #ffd6d6;
  top: -80px;
  left: -60px;
}

.deco-2 {
  width: 280px;
  height: 280px;
  background: #d6e4ff;
  bottom: -70px;
  right: -40px;
}

.auth-card {
  position: relative;
  width: 400px;
  max-width: 100%;
  background: #fff;
  border-radius: 12px;
  padding: 36px 32px 28px;
  box-shadow: 0 8px 30px rgba(0, 0, 0, 0.08);
}

.brand {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin-bottom: 20px;
}

.brand-name {
  font-size: 24px;
  font-weight: 800;
  color: #e93b3d;
}

.title {
  margin: 0 0 6px;
  text-align: center;
  font-size: 20px;
  color: #303133;
}

.subtitle {
  margin: 0 0 20px;
  text-align: center;
  font-size: 13px;
  color: #909399;
}
</style>
