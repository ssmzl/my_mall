<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { register } from '@/api/user'
import AuthShell from '@/components/user/AuthShell.vue'

const router = useRouter()

const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive({
  username: '',
  password: '',
  confirmPassword: '',
  nickname: '',
})

const rules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 4, max: 20, message: '用户名长度为 4-20 个字符', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码至少 6 位', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        if (value !== form.password) {
          callback(new Error('两次输入的密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur',
    },
  ],
}

async function onSubmit() {
  const valid = await formRef.value?.validate().then(() => true).catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    await register({
      username: form.username.trim(),
      password: form.password,
      nickname: form.nickname.trim() || undefined,
    })
    ElMessage.success('注册成功，请登录')
    router.push({ path: '/login', query: { username: form.username.trim() } })
  } catch {
    // 错误提示由 axios 拦截器统一处理
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <AuthShell title="注册账号" subtitle="新用户注册，开启购物之旅">
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top" size="large" @submit.prevent="onSubmit">
      <el-form-item label="用户名" prop="username">
        <el-input v-model="form.username" placeholder="4-20 个字符" :prefix-icon="'User'" clearable />
      </el-form-item>
      <el-form-item label="密码" prop="password">
        <el-input v-model="form.password" type="password" placeholder="至少 6 位" :prefix-icon="'Lock'" show-password />
      </el-form-item>
      <el-form-item label="确认密码" prop="confirmPassword">
        <el-input v-model="form.confirmPassword" type="password" placeholder="再次输入密码" :prefix-icon="'Lock'" show-password />
      </el-form-item>
      <el-form-item label="昵称（选填）" prop="nickname">
        <el-input v-model="form.nickname" placeholder="用于展示，可不填" :prefix-icon="'Postcard'" maxlength="20" clearable />
      </el-form-item>
      <el-button type="danger" size="large" class="submit-btn" :loading="loading" native-type="submit">
        注 册
      </el-button>
    </el-form>
    <div class="auth-footer">
      已有账号？
      <RouterLink to="/login" class="link">去登录</RouterLink>
    </div>
  </AuthShell>
</template>

<style scoped>
.submit-btn {
  width: 100%;
  margin-top: 4px;
  border-radius: 22px;
  font-weight: 600;
  letter-spacing: 4px;
}

.auth-footer {
  margin-top: 18px;
  text-align: center;
  font-size: 14px;
  color: var(--mall-text-secondary);
}

.link {
  color: var(--mall-brand);
  font-weight: 600;
}
</style>
