<template>
  <footer class="mall-footer">
    <div class="footer-inner">
      <span class="brand">my_mall</span>
      <span class="divider">|</span>
      <span>简历级全栈商城演示 · Spring Boot + Vue3 + Redis + RocketMQ + Elasticsearch</span>
    </div>
  </footer>
</template>

<style scoped>
.mall-footer {
  margin-top: 40px;
  background: #fff;
  border-top: 1px solid #f0f0f0;
  padding: 20px 0;
}

.footer-inner {
  max-width: 1200px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  color: #999;
  font-size: 12px;
}

.brand {
  color: #e93b3d;
  font-weight: 700;
  font-size: 14px;
}

.divider {
  color: #e0e0e0;
}
</style>
