<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { adminStatsSummary, adminTopProducts } from '@/api/admin'
import type { AdminStatsSummary, AdminTopProduct } from '@/api/types'

type Range = 'today' | '7d' | '30d'

const RANGE_TEXT: Record<Range, string> = { today: '今日', '7d': '近 7 天', '30d': '近 30 天' }

/* ---------- 汇总卡片 ---------- */
const summary = ref<AdminStatsSummary | null>(null)
const summaryLoading = ref(false)

async function loadSummary() {
  summaryLoading.value = true
  try {
    summary.value = await adminStatsSummary()
  } catch {
    /* 失败提示由拦截器统一处理 */
  } finally {
    summaryLoading.value = false
  }
}

function formatAmount(n: number): string {
  return `¥${Number(n ?? 0).toFixed(2)}`
}

/* ---------- Top10 销量榜 ---------- */
const range = ref<Range>('today')
const topProducts = ref<AdminTopProduct[]>([])
const topLoading = ref(false)

async function loadTopProducts() {
  topLoading.value = true
  try {
    topProducts.value = await adminTopProducts(range.value)
  } catch {
    /* 失败提示由拦截器统一处理 */
  } finally {
    topLoading.value = false
  }
}

/** 增幅 tag：正绿负红，null（上期无销量）显示 NEW */
function growthTagType(growth: number | null): 'success' | 'danger' | 'info' {
  if (growth === null) return 'info'
  return growth >= 0 ? 'success' : 'danger'
}

function growthText(growth: number | null): string {
  if (growth === null) return 'NEW'
  return `${growth > 0 ? '+' : ''}${growth}%`
}

async function onRangeChange() {
  await loadTopProducts()
}

onMounted(() => {
  loadSummary()
  loadTopProducts()
})
</script>

<template>
  <div v-loading="summaryLoading">
    <!-- 汇总卡片：今日 / 累计 -->
    <el-row :gutter="16" class="summary-row">
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-title">销量（件）</div>
          <div class="stat-line">
            <span class="stat-label">今日</span>
            <span class="stat-value">{{ summary?.todaySales ?? '-' }}</span>
          </div>
          <div class="stat-line">
            <span class="stat-label">累计</span>
            <span class="stat-value">{{ summary?.totalSales ?? '-' }}</span>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-title">销售额</div>
          <div class="stat-line">
            <span class="stat-label">今日</span>
            <span class="stat-value">{{ summary ? formatAmount(summary.todayAmount) : '-' }}</span>
          </div>
          <div class="stat-line">
            <span class="stat-label">累计</span>
            <span class="stat-value">{{ summary ? formatAmount(summary.totalAmount) : '-' }}</span>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-title">AI 对话轮数</div>
          <div class="stat-line">
            <span class="stat-label">今日</span>
            <span class="stat-value">{{ summary?.todayAiRounds ?? '-' }}</span>
          </div>
          <div class="stat-line">
            <span class="stat-label">累计</span>
            <span class="stat-value">{{ summary?.totalAiRounds ?? '-' }}</span>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-title">AI Token 消耗</div>
          <div class="stat-line">
            <span class="stat-label">今日</span>
            <span class="stat-value">{{ summary?.todayAiTokens ?? '-' }}</span>
          </div>
          <div class="stat-line">
            <span class="stat-label">累计</span>
            <span class="stat-value">{{ summary?.totalAiTokens ?? '-' }}</span>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- Top10 销量榜 -->
    <el-card shadow="never" class="top-card">
      <template #header>
        <div class="top-header">
          <span class="top-title">Top10 商品销量榜</span>
          <el-tabs v-model="range" class="top-tabs" @tab-change="onRangeChange">
            <el-tab-pane v-for="(text, key) in RANGE_TEXT" :key="key" :label="text" :name="key" />
          </el-tabs>
        </div>
      </template>

      <el-table :data="topProducts" v-loading="topLoading" stripe>
        <el-table-column type="index" label="排名" width="80" align="center" />
        <el-table-column prop="productName" label="商品名称" min-width="260" show-overflow-tooltip />
        <el-table-column prop="sales" label="销量（件）" width="110" align="right" />
        <el-table-column label="销售额" width="130" align="right">
          <template #default="{ row }">{{ formatAmount(row.amount) }}</template>
        </el-table-column>
        <el-table-column label="销量增幅" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="growthTagType(row.growth)" size="small">{{ growthText(row.growth) }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.summary-row {
  margin-bottom: 16px;
}

.stat-title {
  font-size: 14px;
  color: #909399;
  margin-bottom: 10px;
}

.stat-line {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 6px;
}

.stat-line:last-child {
  margin-bottom: 0;
}

.stat-label {
  font-size: 13px;
  color: #909399;
  width: 32px;
}

.stat-value {
  font-size: 20px;
  font-weight: 600;
  color: #303133;
}

.top-card {
  margin-top: 4px;
}

.top-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.top-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}

.top-tabs {
  margin-bottom: -18px;
}
</style>
