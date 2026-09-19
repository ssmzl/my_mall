<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getCart } from '@/api/cart'
import { listAddress } from '@/api/address'
import { createOrder, getOrderToken } from '@/api/order'
import type { Address, CartItem } from '@/api/types'

const emit = defineEmits<{ (e: 'cart-changed'): void }>()
const route = useRoute()
const router = useRouter()

const isBuyNow = ref(false)
const buyNowCount = ref(0)
const cartItems = ref<CartItem[]>([])
const cartAmount = ref(0)

const items = ref<{ skuId: number; quantity: number }[]>([])
const totalCount = computed(() =>
  isBuyNow.value
    ? buyNowCount.value
    : items.value.reduce((sum, it) => sum + it.quantity, 0),
)

const addresses = ref<Address[]>([])
const selectedAddressId = ref<number | null>(null)
const submitting = ref(false)

onMounted(async () => {
  // 立即购买：/checkout?skuId=xx&quantity=n；否则走购物车勾选结算
  const skuIdQuery = route.query.skuId
  const quantityQuery = route.query.quantity
  if (
    typeof skuIdQuery === 'string' &&
    skuIdQuery !== '' &&
    typeof quantityQuery === 'string' &&
    quantityQuery !== ''
  ) {
    isBuyNow.value = true
    buyNowCount.value = Number(quantityQuery)
    items.value = [{ skuId: Number(skuIdQuery), quantity: Number(quantityQuery) }]
  } else {
    try {
      const cart = await getCart()
      cartItems.value = cart.items.filter((it) => it.selected)
      cartAmount.value = cart.selectedAmount
      items.value = cartItems.value.map((it) => ({ skuId: it.skuId, quantity: it.quantity }))
    } catch {
      /* 失败提示由拦截器统一处理 */
    }
  }

  try {
    const list = await listAddress()
    addresses.value = list
    // 接口默认地址置顶，预选第一个
    selectedAddressId.value = list[0]?.id ?? null
  } catch {
    /* 失败提示由拦截器统一处理 */
  }
})

async function onSubmit() {
  if (items.value.length === 0) {
    ElMessage.warning('没有可结算的商品')
    return
  }
  if (selectedAddressId.value == null) {
    ElMessage.warning('请选择收货地址')
    return
  }
  submitting.value = true
  try {
    const token = await getOrderToken()
    await createOrder({ token, addressId: selectedAddressId.value, items: items.value })
    ElMessage.success('下单成功')
    emit('cart-changed')
    router.push('/orders')
  } finally {
    submitting.value = false
  }
}

function formatMoney(n: number): string {
  return `¥${n.toFixed(2)}`
}
</script>

<template>
  <div class="checkout-page">
    <h2 class="page-title">确认订单</h2>

    <el-card class="block" header="收货地址">
      <template #header>
        <div class="card-header">
          <span>收货地址</span>
          <el-link type="primary" :underline="false" @click="router.push('/address')">
            管理地址
          </el-link>
        </div>
      </template>

      <div v-if="addresses.length > 0" class="address-list">
        <div
          v-for="addr in addresses"
          :key="addr.id"
          class="address-card"
          :class="{ active: selectedAddressId === addr.id }"
          @click="selectedAddressId = addr.id"
        >
          <div class="addr-line">
            <span class="addr-name">{{ addr.receiverName }}</span>
            <span>{{ addr.receiverPhone }}</span>
            <el-tag v-if="addr.isDefault === 1" type="danger" size="small">默认</el-tag>
          </div>
          <div class="addr-detail">
            {{ addr.province }}{{ addr.city }}{{ addr.district }}{{ addr.detailAddress }}
          </div>
        </div>
      </div>
      <el-empty v-else description="暂无收货地址，请先添加" :image-size="60" />
    </el-card>

    <el-card class="block" header="商品清单">
      <template v-if="isBuyNow">
        <el-empty description="立即购买" :image-size="60" />
        <div class="buy-now-tip">共 {{ totalCount }} 件商品</div>
      </template>
      <template v-else-if="cartItems.length > 0">
        <div v-for="it in cartItems" :key="it.skuId" class="co-item">
          <el-image :src="it.mainPic ?? undefined" fit="cover" class="co-pic">
            <template #error>
              <div class="pic-placeholder">暂无图片</div>
            </template>
          </el-image>
          <div class="co-info">
            <div class="co-name">{{ it.productName }}</div>
            <div class="co-spec">{{ it.skuSpec || '默认规格' }}</div>
          </div>
          <div class="co-price">
            {{ formatMoney(it.price) }} × {{ it.quantity }}
          </div>
          <div class="co-subtotal">{{ formatMoney(it.subtotal) }}</div>
        </div>
      </template>
      <el-empty v-else description="没有已勾选的商品">
        <el-button type="danger" @click="router.push('/cart')">去购物车勾选</el-button>
      </el-empty>
    </el-card>

    <div class="submit-bar">
      <div class="submit-info">
        共 <span class="hl">{{ totalCount }}</span> 件商品
        <template v-if="!isBuyNow && cartItems.length > 0">
          ，应付总额：<span class="hl amount">{{ formatMoney(cartAmount) }}</span>
        </template>
      </div>
      <el-button
        type="danger"
        size="large"
        class="submit-btn"
        :loading="submitting"
        @click="onSubmit"
      >
        提交订单
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.page-title {
  margin: 0 0 16px;
  font-size: 20px;
}

.block {
  margin-bottom: 16px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.address-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
}

.address-card {
  border: 2px solid #eee;
  border-radius: 6px;
  padding: 12px 14px;
  cursor: pointer;
  transition: border-color 0.2s;
}

.address-card:hover {
  border-color: #f3b2b2;
}

.address-card.active {
  border-color: #e93b3d;
  background: #fff7f7;
}

.addr-line {
  display: flex;
  align-items: center;
  gap: 8px;
}

.addr-name {
  font-weight: 600;
  color: #333;
}

.addr-detail {
  margin-top: 6px;
  font-size: 13px;
  color: #666;
}

.co-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid #f0f0f0;
}

.co-item:last-child {
  border-bottom: none;
}

.co-pic {
  width: 56px;
  height: 56px;
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

.co-info {
  flex: 1;
  min-width: 0;
}

.co-name {
  color: #333;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.co-spec {
  margin-top: 4px;
  font-size: 12px;
  color: #999;
}

.co-price {
  width: 140px;
  text-align: right;
  color: #666;
}

.co-subtotal {
  width: 100px;
  text-align: right;
  font-weight: 600;
  color: #e93b3d;
}

.buy-now-tip {
  text-align: center;
  color: #666;
  margin-top: -12px;
  padding-bottom: 8px;
}

.submit-bar {
  position: sticky;
  bottom: 0;
  background: #fff;
  border-radius: 8px;
  padding: 12px 16px;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 16px;
  box-shadow: 0 -2px 8px rgba(0, 0, 0, 0.04);
}

.submit-info {
  color: #666;
}

.hl {
  color: #e93b3d;
  font-weight: 600;
}

.amount {
  font-size: 20px;
}

.submit-btn {
  min-width: 140px;
}
</style>
