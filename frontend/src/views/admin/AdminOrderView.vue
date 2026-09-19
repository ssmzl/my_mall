<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminDeliverOrder, adminOrderPage } from '@/api/admin'
import { ORDER_STATUS_TEXT } from '@/api/types'
import type { AdminOrderQuery, Order, OrderStatus } from '@/api/types'

const STATUS_TAG_TYPES: Record<OrderStatus, 'primary' | 'success' | 'warning' | 'info' | 'danger'> = {
  0: 'warning',
  1: 'success',
  2: 'primary',
  3: 'success',
  4: 'info',
}

const STATUS_VALUES: OrderStatus[] = [0, 1, 2, 3, 4]
const statusOptions = STATUS_VALUES.map((value) => ({ value, label: ORDER_STATUS_TEXT[value] }))

const orders = ref<Order[]>([])
const total = ref(0)
const loading = ref(false)

const query = reactive<{ pageNum: number; pageSize: number; orderNo: string; status: OrderStatus | '' }>({
  pageNum: 1,
  pageSize: 10,
  orderNo: '',
  status: '',
})

async function fetchOrders() {
  loading.value = true
  try {
    const params: AdminOrderQuery = { pageNum: query.pageNum, pageSize: query.pageSize }
    const orderNo = query.orderNo.trim()
    if (orderNo) params.orderNo = orderNo
    if (query.status !== '') params.status = query.status
    const data = await adminOrderPage(params)
    orders.value = data.records
    total.value = data.total
  } catch {
    /* 失败提示由拦截器统一处理 */
  } finally {
    loading.value = false
  }
}

function onSearch() {
  query.pageNum = 1
  fetchOrders()
}

function onReset() {
  query.orderNo = ''
  query.status = ''
  onSearch()
}

function onSizeChange() {
  query.pageNum = 1
  fetchOrders()
}

async function onDeliver(order: Order) {
  try {
    await ElMessageBox.confirm(`确定对订单 ${order.orderNo} 执行发货吗？`, '发货确认', {
      type: 'warning',
      confirmButtonText: '确认发货',
    })
  } catch {
    return
  }
  await adminDeliverOrder(order.orderNo)
  ElMessage.success('发货成功')
  await fetchOrders()
}

onMounted(fetchOrders)
</script>

<template>
  <div class="admin-order-page">
    <div class="toolbar">
      <el-input
        v-model="query.orderNo"
        placeholder="订单号"
        clearable
        style="width: 240px"
        @keyup.enter="onSearch"
        @clear="onSearch"
      />
      <el-select
        v-model="query.status"
        placeholder="全部状态"
        clearable
        style="width: 140px"
        @change="onSearch"
      >
        <el-option v-for="opt in statusOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
      </el-select>
      <el-button type="primary" @click="onSearch">查询</el-button>
      <el-button @click="onReset">重置</el-button>
    </div>

    <el-table :data="orders" v-loading="loading" border stripe>
      <el-table-column prop="orderNo" label="订单号" min-width="190" show-overflow-tooltip />
      <el-table-column prop="userId" label="用户ID" width="90" align="center" />
      <el-table-column label="订单总额" width="120" align="right">
        <template #default="{ row }">¥{{ (row as Order).totalAmount.toFixed(2) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="STATUS_TAG_TYPES[(row as Order).status]" size="small">
            {{ ORDER_STATUS_TEXT[(row as Order).status] }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="下单时间" width="170">
        <template #default="{ row }">{{ (row as Order).createTime }}</template>
      </el-table-column>
      <el-table-column label="支付时间" width="170">
        <template #default="{ row }">{{ (row as Order).payTime ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="110" fixed="right" align="center">
        <template #default="{ row }">
          <el-button
            v-if="(row as Order).status === 1"
            type="primary"
            size="small"
            @click="onDeliver(row as Order)"
          >
            发货
          </el-button>
          <span v-else class="no-action">-</span>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next, jumper"
        @current-change="fetchOrders"
        @size-change="onSizeChange"
      />
    </div>
  </div>
</template>

<style scoped>
.admin-order-page {
  background: #fff;
  padding: 16px;
  border-radius: 8px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}

.no-action {
  color: #ccc;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}
</style>
