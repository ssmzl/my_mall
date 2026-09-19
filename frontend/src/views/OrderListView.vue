<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { cancelOrder, orderPage, payOrder } from '@/api/order'
import { ORDER_STATUS_TEXT } from '@/api/types'
import type { AdminOrderQuery, Order, OrderStatus } from '@/api/types'

const router = useRouter()

const tabs: { label: string; name: string; status?: OrderStatus }[] = [
  { label: '全部', name: 'all' },
  { label: '待支付', name: '0', status: 0 },
  { label: '已支付', name: '1', status: 1 },
  { label: '已发货', name: '2', status: 2 },
  { label: '已完成', name: '3', status: 3 },
  { label: '已取消', name: '4', status: 4 },
]

const activeTab = ref('all')
const loading = ref(false)
const orders = ref<Order[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)

async function fetchOrders() {
  const status = tabs.find((t) => t.name === activeTab.value)?.status
  const params: AdminOrderQuery = { pageNum: pageNum.value, pageSize: pageSize.value }
  if (status !== undefined) params.status = status
  loading.value = true
  try {
    const data = await orderPage(params)
    orders.value = data.records
    total.value = data.total
  } catch {
    /* 失败提示由拦截器统一处理 */
  } finally {
    loading.value = false
  }
}

function onTabChange() {
  pageNum.value = 1
  fetchOrders()
}

function onSizeChange() {
  pageNum.value = 1
  fetchOrders()
}

/** 商品摘要：前两件 + 多余数量 */
function goodsSummary(order: Order): string {
  const head = order.items
    .slice(0, 2)
    .map((it) => `${it.productName} × ${it.quantity}`)
    .join('；')
  return order.items.length > 2 ? `${head} +${order.items.length - 2}` : head
}

function statusTagType(status: OrderStatus): 'warning' | 'info' | 'success' {
  if (status === 0) return 'warning'
  if (status === 4) return 'info'
  return 'success'
}

async function onPay(order: Order) {
  try {
    await ElMessageBox.confirm(`确认支付订单 ${order.orderNo} 吗？`, '支付确认', {
      type: 'warning',
      confirmButtonText: '确认支付',
    })
  } catch {
    return
  }
  await payOrder(order.orderNo)
  ElMessage.success('支付成功')
  fetchOrders()
}

async function onCancel(order: Order) {
  try {
    await ElMessageBox.confirm(
      `确认取消订单 ${order.orderNo} 吗？取消后不可恢复。`,
      '取消订单',
      { type: 'warning', confirmButtonText: '确认取消' },
    )
  } catch {
    return
  }
  await cancelOrder(order.orderNo)
  ElMessage.success('订单已取消')
  fetchOrders()
}

function goDetail(orderNo: string) {
  router.push(`/orders/${orderNo}`)
}

function formatMoney(n: number): string {
  return `¥${n.toFixed(2)}`
}

onMounted(fetchOrders)
</script>

<template>
  <div class="order-list-page">
    <h2 class="page-title">我的订单</h2>

    <el-card class="list-card" shadow="never">
      <el-tabs v-model="activeTab" @tab-change="onTabChange">
        <el-tab-pane v-for="t in tabs" :key="t.name" :label="t.label" :name="t.name" />
      </el-tabs>

      <el-table :data="orders" v-loading="loading">
        <el-table-column prop="orderNo" label="订单号" min-width="180" />
        <el-table-column label="商品" min-width="260">
          <template #default="{ row }">{{ goodsSummary(row as Order) }}</template>
        </el-table-column>
        <el-table-column label="总额" width="110" align="right">
          <template #default="{ row }">{{ formatMoney(row.totalAmount) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status as OrderStatus)" size="small">
              {{ ORDER_STATUS_TEXT[row.status as OrderStatus] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="下单时间" min-width="160" />
        <el-table-column label="操作" width="200" align="center" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 0">
              <el-button type="danger" link @click="onPay(row as Order)">去支付</el-button>
              <el-button type="warning" link @click="onCancel(row as Order)">取消订单</el-button>
            </template>
            <el-button type="primary" link @click="goDetail(row.orderNo)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrap">
        <el-pagination
          v-model:current-page="pageNum"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="fetchOrders"
          @size-change="onSizeChange"
        />
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.page-title {
  margin: 0 0 16px;
  font-size: 20px;
}

.list-card {
  border-radius: 8px;
}

.pagination-wrap {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
</style>
