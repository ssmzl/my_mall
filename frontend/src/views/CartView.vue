<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { CheckboxValueType } from 'element-plus'
import {
  getCart,
  removeCartItem,
  updateQuantity,
  updateSelected,
  updateSelectedAll,
} from '@/api/cart'
import type { CartVO } from '@/api/types'

const emit = defineEmits<{ (e: 'cart-changed'): void }>()
const router = useRouter()

const cart = ref<CartVO | null>(null)

const items = computed(() => cart.value?.items ?? [])
const selectedCount = computed(() => cart.value?.selectedCount ?? 0)
const selectedAmount = computed(() => cart.value?.selectedAmount ?? 0)
const isAllSelected = computed(
  () => items.value.length > 0 && items.value.every((it) => it.selected),
)

async function refresh() {
  try {
    cart.value = await getCart()
  } catch {
    /* 失败提示由拦截器统一处理 */
  }
}

async function onQuantityChange(skuId: number, quantity: number | undefined) {
  if (!quantity || quantity < 1) return
  await updateQuantity(skuId, quantity)
  await refresh()
  emit('cart-changed')
}

async function onSelectedChange(skuId: number, selected: boolean) {
  await updateSelected(skuId, selected)
  await refresh()
  emit('cart-changed')
}

async function onToggleAll(selected: boolean) {
  await updateSelectedAll(selected)
  await refresh()
  emit('cart-changed')
}

async function onRemove(skuId: number) {
  try {
    await ElMessageBox.confirm('确定要删除该商品吗？', '删除确认', { type: 'warning' })
  } catch {
    return
  }
  await removeCartItem(skuId)
  ElMessage.success('已删除')
  await refresh()
  emit('cart-changed')
}

function goSettle() {
  if (selectedCount.value === 0) {
    ElMessage.warning('请先勾选要结算的商品')
    return
  }
  router.push('/checkout')
}

function formatMoney(n: number): string {
  return `¥${n.toFixed(2)}`
}

onMounted(refresh)
</script>

<template>
  <div class="cart-page">
    <h2 class="page-title">购物车</h2>

    <template v-if="items.length > 0">
      <div class="cart-list">
        <div class="cart-head">
          <el-checkbox
            :model-value="isAllSelected"
            @change="(v: CheckboxValueType) => onToggleAll(v === true)"
          />
          <span>商品信息</span>
          <span class="cell-right">单价</span>
          <span class="cell-center">数量</span>
          <span class="cell-right">小计</span>
          <span class="cell-center">操作</span>
        </div>

        <div v-for="item in items" :key="item.skuId" class="cart-row">
          <el-checkbox
            :model-value="item.selected"
            @change="(v: CheckboxValueType) => onSelectedChange(item.skuId, v === true)"
          />
          <div class="goods">
            <el-image :src="item.mainPic ?? undefined" fit="cover" class="goods-pic">
              <template #error>
                <div class="pic-placeholder">暂无图片</div>
              </template>
            </el-image>
            <div class="goods-info">
              <div class="goods-name">{{ item.productName }}</div>
              <div class="goods-spec">{{ item.skuSpec || '默认规格' }}</div>
            </div>
          </div>
          <span class="cell-right">{{ formatMoney(item.price) }}</span>
          <div class="cell-center">
            <el-input-number
              :model-value="item.quantity"
              :min="1"
              :max="999"
              size="small"
              @change="(val: number | undefined) => onQuantityChange(item.skuId, val)"
            />
          </div>
          <span class="cell-right subtotal">{{ formatMoney(item.subtotal) }}</span>
          <div class="cell-center">
            <el-button type="danger" link @click="onRemove(item.skuId)">删除</el-button>
          </div>
        </div>
      </div>

      <div class="settle-bar">
        <div class="settle-info">
          已选 <span class="hl">{{ selectedCount }}</span> 件商品，合计：
          <span class="hl amount">{{ formatMoney(selectedAmount) }}</span>
        </div>
        <el-button type="danger" size="large" class="settle-btn" @click="goSettle">去结算</el-button>
      </div>
    </template>

    <el-empty v-else description="购物车空空如也，快去挑选心仪的商品吧">
      <el-button type="danger" @click="router.push('/')">去逛逛</el-button>
    </el-empty>
  </div>
</template>

<style scoped>
.page-title {
  margin: 0 0 16px;
  font-size: 20px;
}

.cart-list {
  background: #fff;
  border-radius: 8px;
  overflow: hidden;
}

.cart-head,
.cart-row {
  display: grid;
  grid-template-columns: 60px minmax(0, 1fr) 110px 160px 110px 70px;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
}

.cart-head {
  background: #fafafa;
  color: #999;
  font-size: 13px;
}

.cart-row {
  border-top: 1px solid #f0f0f0;
}

.cell-right {
  text-align: right;
}

.cell-center {
  text-align: center;
}

.goods {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.goods-pic {
  width: 64px;
  height: 64px;
  border-radius: 4px;
  flex-shrink: 0;
}

.pic-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: #999;
  background: #f5f5f5;
}

.goods-info {
  min-width: 0;
}

.goods-name {
  color: #333;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.goods-spec {
  margin-top: 4px;
  font-size: 12px;
  color: #999;
}

.subtotal {
  color: #e93b3d;
  font-weight: 600;
}

.settle-bar {
  position: sticky;
  bottom: 0;
  margin-top: 16px;
  background: #fff;
  border-radius: 8px;
  padding: 12px 16px;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 16px;
  box-shadow: 0 -2px 8px rgba(0, 0, 0, 0.04);
}

.settle-info {
  color: #666;
}

.hl {
  color: #e93b3d;
  font-weight: 600;
}

.amount {
  font-size: 20px;
}

.settle-btn {
  min-width: 120px;
}
</style>
