<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { orderDetail, payOrder } from '@/api/order'
import { ORDER_STATUS_TEXT } from '@/api/types'
import type { Order } from '@/api/types'

const route = useRoute()

const orderNoParam = route.params.orderNo
const orderNo = Array.isArray(orderNoParam) ? (orderNoParam[0] ?? '') : (orderNoParam ?? '')

const order = ref<Order | null>(null)

const isCancelled = computed(() => order.value?.status === 4)

/** 步骤条进度：提交订单(0) → 付款(1) → 发货(2) → 完成(3) */
const stepActive = computed(() => {
  switch (order.value?.status) {
    case 0:
      return 1
    case 1:
      return 2
    case 2:
      return 3
    case 3:
      return 4
    default:
      return 1
  }
})

interface AddressSnapshot {
  receiverName: string
  receiverPhone: string
  province: string
  city: string
  district: string
  detailAddress: string
}

const snapshot = ref<AddressSnapshot | null>(null)
const snapshotRaw = ref('')

async function fetchOrder() {
  try {
    order.value = await orderDetail(orderNo)
  } catch {
    /* 失败提示由拦截器统一处理 */
    return
  }
  const raw = order.value?.addressSnapshot ?? null
  snapshotRaw.value = raw ?? ''
  snapshot.value = null
  if (raw) {
    try {
      snapshot.value = JSON.parse(raw) as AddressSnapshot
    } catch {
      /* 解析失败时展示原文 */
    }
  }
}

async function onPay() {
  if (!order.value) return
  await payOrder(order.value.orderNo)
  ElMessage.success('支付成功')
  fetchOrder()
}

function formatMoney(n: number): string {
  return `¥${n.toFixed(2)}`
}

onMounted(fetchOrder)
</script>

<template>
  <div class="order-detail-page">
    <h2 class="page-title">订单详情</h2>

    <template v-if="order">
      <el-card class="block">
        <el-steps
          :active="stepActive"
          :process-status="isCancelled ? 'error' : 'process'"
          finish-status="success"
          align-center
        >
          <el-step title="提交订单" />
          <el-step title="付款" />
          <el-step title="发货" />
          <el-step title="完成" />
        </el-steps>
        <div class="status-line" :class="{ cancelled: isCancelled }">
          当前状态：<span class="status-text">{{ ORDER_STATUS_TEXT[order.status] }}</span>
        </div>
      </el-card>

      <el-card class="block" header="收货信息">
        <template v-if="snapshot">
          <div class="info-line">收货人：{{ snapshot.receiverName }}</div>
          <div class="info-line">联系电话：{{ snapshot.receiverPhone }}</div>
          <div class="info-line">
            收货地址：{{ snapshot.province }}{{ snapshot.city }}{{ snapshot.district
            }}{{ snapshot.detailAddress }}
          </div>
        </template>
        <div v-else-if="snapshotRaw" class="info-line">{{ snapshotRaw }}</div>
        <div v-else class="info-line muted">暂无地址信息</div>
      </el-card>

      <el-card class="block" header="商品清单">
        <el-table :data="order.items">
          <el-table-column prop="productName" label="商品名称" min-width="220" />
          <el-table-column label="规格" min-width="120">
            <template #default="{ row }">{{ row.skuSpec || '默认规格' }}</template>
          </el-table-column>
          <el-table-column label="单价" width="110" align="right">
            <template #default="{ row }">{{ formatMoney(row.price) }}</template>
          </el-table-column>
          <el-table-column prop="quantity" label="数量" width="80" align="center" />
          <el-table-column label="小计" width="110" align="right">
            <template #default="{ row }">{{ formatMoney(row.price * row.quantity) }}</template>
          </el-table-column>
        </el-table>
        <div class="amount-line">
          合计金额：<span class="amount">{{ formatMoney(order.totalAmount) }}</span>
        </div>
      </el-card>

      <el-card class="block" header="订单信息">
        <div class="info-line">订单号：{{ order.orderNo }}</div>
        <div class="info-line">创建时间：{{ order.createTime }}</div>
        <div class="info-line">支付时间：{{ order.payTime ?? '—' }}</div>
        <div v-if="order.status === 0" class="pay-wrap">
          <el-button type="danger" size="large" @click="onPay">去支付</el-button>
        </div>
      </el-card>
    </template>

    <el-empty v-else description="订单加载中或不存在" />
  </div>
</template>

<style scoped>
.page-title {
  margin: 0 0 16px;
  font-size: 20px;
}

.block {
  margin-bottom: 16px;
  border-radius: 8px;
}

.status-line {
  margin-top: 16px;
  text-align: center;
  color: #666;
}

.status-text {
  color: #e93b3d;
  font-weight: 600;
}

.status-line.cancelled .status-text {
  color: #999;
}

.info-line {
  line-height: 1.8;
  color: #333;
}

.muted {
  color: #999;
}

.amount-line {
  margin-top: 12px;
  text-align: right;
  color: #666;
}

.amount {
  color: #e93b3d;
  font-size: 20px;
  font-weight: 600;
}

.pay-wrap {
  margin-top: 12px;
  text-align: right;
}
</style>
