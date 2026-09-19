<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import dayjs from 'dayjs'
import { ElMessage, ElMessageBox } from 'element-plus'
import { seckillActivities, seckillOrder, seckillResult } from '@/api/seckill'
import type { SeckillActivity, SeckillProduct } from '@/api/types'
import { useUserStore } from '@/stores/user'

type Phase = 'before' | 'ongoing' | 'ended'

const POLL_INTERVAL_MS = 1500
const MAX_POLL_TIMES = 10

const router = useRouter()
const userStore = useUserStore()

const activities = ref<SeckillActivity[]>([])
const loading = ref(false)
const now = ref(Date.now())
/** 正在排队抢购的秒杀商品 id 集合（按钮 loading + 禁用） */
const queueingIds = ref(new Set<number>())

let clockTimer: number | null = null

async function refresh() {
  loading.value = true
  try {
    activities.value = await seckillActivities()
  } catch {
    /* 失败提示由拦截器统一处理 */
  } finally {
    loading.value = false
  }
}

/** 按当前时间推算活动阶段 */
function phaseOf(act: SeckillActivity): Phase {
  const start = dayjs(act.startTime).valueOf()
  const end = dayjs(act.endTime).valueOf()
  if (now.value < start) return 'before'
  if (now.value > end) return 'ended'
  return 'ongoing'
}

const PHASE_TEXT: Record<Phase, string> = { before: '未开始', ongoing: '进行中', ended: '已结束' }
const PHASE_TAG: Record<Phase, 'info' | 'danger'> = { before: 'info', ongoing: 'danger', ended: 'info' }

function formatDuration(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000))
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(Math.floor(total / 3600))}:${pad(Math.floor((total % 3600) / 60))}:${pad(total % 60)}`
}

function countdownText(act: SeckillActivity): string {
  const phase = phaseOf(act)
  if (phase === 'before') return `距开始 ${formatDuration(dayjs(act.startTime).valueOf() - now.value)}`
  if (phase === 'ongoing') return `距结束 ${formatDuration(dayjs(act.endTime).valueOf() - now.value)}`
  return '活动已结束'
}

/** 剩余库存可视化（无初始总量，按 100 件封顶展示） */
function stockPercent(sp: SeckillProduct): number {
  return Math.max(0, Math.min(100, sp.seckillStock))
}

function isQueueing(sp: SeckillProduct): boolean {
  return queueingIds.value.has(sp.id)
}

function buyText(act: SeckillActivity, sp: SeckillProduct): string {
  if (isQueueing(sp)) return '排队中'
  return PHASE_TEXT[phaseOf(act)] === '进行中' ? '立即抢购' : PHASE_TEXT[phaseOf(act)]
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/** 轮询秒杀结果：QUEUED 继续轮询，最多 MAX_POLL_TIMES 次 */
async function pollResult(taskId: string): Promise<void> {
  for (let i = 0; i < MAX_POLL_TIMES; i++) {
    await sleep(POLL_INTERVAL_MS)
    let result
    try {
      result = await seckillResult(taskId)
    } catch {
      return // 查询异常，拦截器已提示
    }
    if (result.status === 'SUCCESS') {
      ElMessage.success(`抢购成功！订单号 ${result.orderNo ?? ''}`)
      try {
        await ElMessageBox.confirm('抢购成功，是否前往订单列表查看？', '抢购成功', {
          type: 'success',
          confirmButtonText: '去订单列表',
          cancelButtonText: '留在本页',
        })
        router.push('/orders')
      } catch {
        /* 留在本页 */
      }
      return
    }
    if (result.status === 'FAIL') {
      ElMessage.error(result.message || '抢购失败，请稍后再试')
      return
    }
    // QUEUED 继续轮询
  }
  ElMessage.warning('排队超时，请稍后前往订单列表查看结果')
}

async function onBuy(sp: SeckillProduct) {
  if (!userStore.isLoggedIn) {
    router.push('/login')
    return
  }
  if (queueingIds.value.has(sp.id)) return
  queueingIds.value.add(sp.id)
  try {
    const { taskId } = await seckillOrder(sp.id)
    await pollResult(taskId)
  } catch {
    /* 下单失败，拦截器已提示 */
  } finally {
    queueingIds.value.delete(sp.id)
  }
}

onMounted(() => {
  refresh()
  clockTimer = window.setInterval(() => {
    now.value = Date.now()
  }, 1000)
})

onUnmounted(() => {
  if (clockTimer !== null) {
    window.clearInterval(clockTimer)
    clockTimer = null
  }
})
</script>

<template>
  <div class="seckill-page">
    <h2 class="page-title"><el-icon><AlarmClock /></el-icon> 秒杀专区</h2>

    <div v-loading="loading">
      <template v-if="activities.length > 0">
        <el-card v-for="act in activities" :key="act.id" class="act-card" shadow="never">
          <template #header>
            <div class="act-head">
              <div class="act-info">
                <span class="act-name">{{ act.name }}</span>
                <el-tag :type="PHASE_TAG[phaseOf(act)]" size="small">{{ PHASE_TEXT[phaseOf(act)] }}</el-tag>
                <span v-if="phaseOf(act) !== 'ended'" class="act-countdown">
                  <el-icon><Timer /></el-icon>{{ countdownText(act) }}
                </span>
              </div>
              <span class="act-time">
                {{ dayjs(act.startTime).format('MM-DD HH:mm') }} ~ {{ dayjs(act.endTime).format('MM-DD HH:mm') }}
              </span>
            </div>
          </template>

          <div v-if="act.products.length > 0" class="sp-grid">
            <div v-for="sp in act.products" :key="sp.id" class="sp-card">
              <el-image v-if="sp.mainPic" :src="sp.mainPic" fit="cover" class="sp-pic">
                <template #error>
                  <div class="pic-placeholder">暂无图片</div>
                </template>
              </el-image>
              <div v-else class="sp-pic pic-placeholder">暂无图片</div>

              <div class="sp-name" :title="sp.productName">{{ sp.productName }}</div>
              <div class="sp-price-row">
                <span class="sp-price">¥{{ sp.seckillPrice.toFixed(2) }}</span>
                <span class="sp-original">¥{{ sp.originalPrice.toFixed(2) }}</span>
              </div>
              <el-progress
                :percentage="stockPercent(sp)"
                :stroke-width="8"
                :format="() => `剩余 ${sp.seckillStock} 件`"
              />
              <div class="sp-limit">每人限购 {{ sp.limitPerUser }} 件</div>
              <el-button
                type="danger"
                class="sp-buy"
                :disabled="phaseOf(act) !== 'ongoing'"
                :loading="isQueueing(sp)"
                @click="onBuy(sp)"
              >
                {{ buyText(act, sp) }}
              </el-button>
            </div>
          </div>
          <el-empty v-else description="该活动暂无秒杀商品" :image-size="60" />
        </el-card>
      </template>

      <el-empty v-else-if="!loading" description="暂无秒杀活动，敬请期待" />
    </div>
  </div>
</template>

<style scoped>
.seckill-page {
  max-width: 1200px;
  margin: 0 auto;
  padding: 16px;
}

.page-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 16px;
  font-size: 22px;
  color: #e4393c;
}

.act-card {
  margin-bottom: 16px;
  border-radius: 8px;
}

.act-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.act-info {
  display: flex;
  align-items: center;
  gap: 10px;
}

.act-name {
  font-size: 16px;
  font-weight: 600;
  color: #333;
}

.act-countdown {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: #e4393c;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.act-time {
  color: #999;
  font-size: 13px;
}

.sp-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 14px;
}

.sp-card {
  border: 1px solid #f0f0f0;
  border-radius: 8px;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.sp-pic {
  width: 100%;
  height: 140px;
  border-radius: 6px;
  overflow: hidden;
}

.pic-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f7fa;
  color: #bbb;
  font-size: 13px;
}

.sp-name {
  font-size: 14px;
  color: #333;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sp-price-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.sp-price {
  color: #e4393c;
  font-size: 20px;
  font-weight: 700;
}

.sp-original {
  color: #999;
  font-size: 13px;
  text-decoration: line-through;
}

.sp-limit {
  font-size: 12px;
  color: #999;
}

.sp-buy {
  width: 100%;
}

.sp-buy :deep(span) {
  color: inherit;
}
</style>
