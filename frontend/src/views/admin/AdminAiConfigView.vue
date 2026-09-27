<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { adminGetAiConfig, adminUpdateAiConfig } from '@/api/admin'

const formRef = ref<FormInstance>()
const loading = ref(false)
const submitting = ref(false)
const hasApiKey = ref(false)
const updateTime = ref<string | null>(null)

const form = reactive({
  enabled: false,
  baseUrl: '',
  apiKey: '',
  model: '',
  temperature: 0.7,
  maxRounds: 10,
  ttlMinutes: 60,
  rateLimitPerMinute: 10,
})

const rules: FormRules = {
  baseUrl: [{ required: true, message: '请输入 LLM 端点 URL', trigger: 'blur' }],
  model: [{ required: true, message: '请输入模型名称', trigger: 'blur' }],
  temperature: [{ required: true, message: '请输入温度', trigger: 'blur' }],
  maxRounds: [{ required: true, message: '请输入上下文保留轮数', trigger: 'blur' }],
  ttlMinutes: [{ required: true, message: '请输入上下文 TTL', trigger: 'blur' }],
  rateLimitPerMinute: [{ required: true, message: '请输入限流阈值', trigger: 'blur' }],
}

async function load() {
  loading.value = true
  try {
    const config = await adminGetAiConfig()
    form.enabled = config.enabled
    form.baseUrl = config.baseUrl
    form.model = config.model
    form.temperature = config.temperature
    form.maxRounds = config.maxRounds
    form.ttlMinutes = config.ttlMinutes
    form.rateLimitPerMinute = config.rateLimitPerMinute
    // api-key 脱敏：明文不回传，表单留空表示保留原值
    form.apiKey = ''
    hasApiKey.value = config.hasApiKey
    updateTime.value = config.updateTime
  } catch {
    /* 失败提示由拦截器统一处理 */
  } finally {
    loading.value = false
  }
}

async function submit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    await adminUpdateAiConfig({
      enabled: form.enabled,
      baseUrl: form.baseUrl,
      apiKey: form.apiKey.trim() === '' ? undefined : form.apiKey.trim(),
      model: form.model,
      temperature: form.temperature,
      maxRounds: form.maxRounds,
      ttlMinutes: form.ttlMinutes,
      rateLimitPerMinute: form.rateLimitPerMinute,
    })
    ElMessage.success('保存成功，配置已即时生效')
    await load()
  } catch {
    /* 失败提示由拦截器统一处理 */
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<template>
  <el-card shadow="never" v-loading="loading" class="config-card">
    <template #header>
      <div class="config-header">
        <span class="config-title">AI 客服配置</span>
        <span class="config-meta">
          <el-tag :type="hasApiKey ? 'success' : 'warning'" size="small">
            {{ hasApiKey ? '已配置 api-key' : '未配置 api-key' }}
          </el-tag>
          <span v-if="updateTime" class="update-time">更新于 {{ updateTime }}</span>
        </span>
      </div>
    </template>

    <el-form ref="formRef" :model="form" :rules="rules" label-width="160px" class="config-form">
      <el-form-item label="启用 AI 客服">
        <el-switch v-model="form.enabled" />
      </el-form-item>
      <el-form-item label="LLM 端点 URL" prop="baseUrl">
        <el-input v-model="form.baseUrl" placeholder="如 https://api.example.com/v1/chat/completions" maxlength="512" />
      </el-form-item>
      <el-form-item label="API Key" prop="apiKey">
        <el-input
          v-model="form.apiKey"
          type="password"
          show-password
          :placeholder="hasApiKey ? '留空表示保留原值' : '请输入 API Key'"
          maxlength="512"
        />
      </el-form-item>
      <el-form-item label="模型名称" prop="model">
        <el-input v-model="form.model" placeholder="如 gpt-4o-mini" maxlength="64" />
      </el-form-item>
      <el-form-item label="温度（0-2）" prop="temperature">
        <el-input-number v-model="form.temperature" :min="0" :max="2" :step="0.1" />
      </el-form-item>
      <el-form-item label="上下文保留轮数" prop="maxRounds">
        <el-input-number v-model="form.maxRounds" :min="1" :max="999" />
      </el-form-item>
      <el-form-item label="上下文 TTL（分钟）" prop="ttlMinutes">
        <el-input-number v-model="form.ttlMinutes" :min="1" :max="1440" />
      </el-form-item>
      <el-form-item label="用户限流（条/分钟）" prop="rateLimitPerMinute">
        <el-input-number v-model="form.rateLimitPerMinute" :min="1" :max="999" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
      </el-form-item>
    </el-form>
  </el-card>
</template>

<style scoped>
.config-card {
  max-width: 720px;
}

.config-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.config-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}

.config-meta {
  display: inline-flex;
  align-items: center;
  gap: 10px;
}

.update-time {
  font-size: 12px;
  color: #909399;
}
</style>
