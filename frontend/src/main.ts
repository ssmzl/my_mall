import './assets/main.css'

import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

import App from './App.vue'
import router from './router'
import { setUnauthorizedHandler } from './api/http'
import { useUserStore } from './stores/user'

const app = createApp(App)

// 注册 Element Plus 图标为全局组件（组件内 <el-icon><ShoppingCart /></el-icon> 直接使用）
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

// 401 时清理登录态并跳转登录页，记录回跳地址（避免 api/http 与 router/store 循环依赖，此处注入）
setUnauthorizedHandler(() => {
  useUserStore().reset()
  router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
})

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })

app.mount('#app')
