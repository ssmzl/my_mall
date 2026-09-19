<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { addToCart } from '@/api/cart'
import { productDetail } from '@/api/product'
import { useUserStore } from '@/stores/user'
import type { ProductDetail, Sku } from '@/api/types'

const emit = defineEmits<{ (e: 'cart-changed'): void }>()

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const detail = ref<ProductDetail | null>(null)
const loading = ref(false)
const selectedSkuId = ref<number | null>(null)
const quantity = ref(1)
const adding = ref(false)

/** 解析 SKU 规格为展示文案（JSON 解析失败时回退 skuCode） */
function specText(sku: Sku): string {
  if (!sku.specJson) return sku.skuCode
  try {
    const obj = JSON.parse(sku.specJson) as Record<string, unknown>
    const values = Object.values(obj).map((v) => String(v))
    return values.length > 0 ? values.join(' / ') : sku.skuCode
  } catch {
    return sku.skuCode
  }
}

const selectedSku = computed<Sku | null>(() => {
  if (selectedSkuId.value === null) return null
  return detail.value?.skus.find((s) => s.id === selectedSkuId.value) ?? null
})

const displayPrice = computed(() => (selectedSku.value ? selectedSku.value.price : detail.value?.price ?? 0))

const displayStock = computed(() => {
  if (selectedSku.value) return selectedSku.value.stock
  return detail.value?.stock ?? 0
})

const maxQuantity = computed(() => Math.max(displayStock.value, 1))

function onSkuChange() {
  quantity.value = 1
}

function ensureLogin(): boolean {
  if (userStore.isLoggedIn) return true
  router.push({ path: '/login', query: { redirect: route.fullPath } })
  return false
}

async function onAddCart() {
  if (!selectedSku.value) {
    ElMessage.warning('请先选择商品规格')
    return
  }
  if (!ensureLogin()) return
  adding.value = true
  try {
    await addToCart(selectedSku.value.id, quantity.value)
    emit('cart-changed')
    ElMessage.success('已加入购物车')
  } catch {
    // 错误提示由 axios 拦截器统一处理
  } finally {
    adding.value = false
  }
}

function onBuyNow() {
  if (!selectedSku.value) {
    ElMessage.warning('请先选择商品规格')
    return
  }
  if (!ensureLogin()) return
  router.push({
    path: '/checkout',
    query: { skuId: String(selectedSku.value.id), quantity: String(quantity.value) },
  })
}

onMounted(async () => {
  loading.value = true
  try {
    const data = await productDetail(Number(route.params.id))
    detail.value = data
    // 默认选中第一个 SKU
    const first = data.skus[0]
    if (first) selectedSkuId.value = first.id
  } catch {
    // 错误提示由 axios 拦截器统一处理
  } finally {
    loading.value = false
  }
})

watch(selectedSkuId, onSkuChange)
</script>

<template>
  <div class="detail-page" v-loading="loading">
    <template v-if="detail">
      <div class="main-area">
        <el-image class="main-pic" :src="detail.mainPic ?? undefined" fit="cover">
          <template #error>
            <div class="pic-placeholder">暂无图片</div>
          </template>
        </el-image>

        <div class="info-area">
          <h1 class="name">{{ detail.name }}</h1>
          <p class="sub-title">{{ detail.subTitle ?? '' }}</p>

          <div class="price-box">
            <span class="price-label">价格</span>
            <span class="price">￥{{ displayPrice.toFixed(2) }}</span>
          </div>

          <div class="meta-row">
            <span>销量 {{ detail.sales }}</span>
            <span>库存 {{ displayStock }}</span>
          </div>

          <div class="sku-section">
            <div class="section-label">选择规格</div>
            <el-radio-group v-model="selectedSkuId">
              <el-radio-button
                v-for="sku in detail.skus"
                :key="sku.id"
                :value="sku.id"
                :disabled="sku.stock <= 0"
              >
                {{ specText(sku) }}
              </el-radio-button>
            </el-radio-group>
          </div>

          <div class="quantity-section">
            <div class="section-label">数量</div>
            <el-input-number v-model="quantity" :min="1" :max="maxQuantity" />
            <span class="stock-tip">最多可买 {{ maxQuantity }} 件</span>
          </div>

          <div class="action-row">
            <el-button type="danger" size="large" :loading="adding" @click="onAddCart">
              <el-icon class="btn-icon"><ShoppingCart /></el-icon>
              加入购物车
            </el-button>
            <el-button type="warning" size="large" @click="onBuyNow">立即购买</el-button>
          </div>
        </div>
      </div>

      <div class="detail-area">
        <h3 class="detail-title">商品详情</h3>
        <div v-if="detail.detail" class="rich-text" v-html="detail.detail"></div>
        <el-empty v-else description="暂无详情" />
      </div>
    </template>
    <el-empty v-else-if="!loading" description="商品不存在或已下架" />
  </div>
</template>

<style scoped>
.main-area {
  display: flex;
  gap: 24px;
  background: #fff;
  border-radius: 8px;
  padding: 24px;
}

.main-pic {
  width: 400px;
  height: 400px;
  flex-shrink: 0;
  border-radius: 8px;
  background: #f5f5f5;
}

.pic-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  color: #999;
  font-size: 14px;
}

.info-area {
  flex: 1;
  min-width: 0;
}

.name {
  margin: 0;
  font-size: 22px;
  color: #333;
  line-height: 1.4;
}

.sub-title {
  margin: 8px 0 16px;
  font-size: 14px;
  color: #e93b3d;
}

.price-box {
  background: #fff5f5;
  padding: 14px 16px;
  border-radius: 6px;
  display: flex;
  align-items: baseline;
  gap: 12px;
}

.price-label {
  font-size: 13px;
  color: #999;
}

.price {
  color: #e93b3d;
  font-size: 30px;
  font-weight: 700;
}

.meta-row {
  display: flex;
  gap: 24px;
  margin: 14px 0;
  font-size: 13px;
  color: #999;
}

.sku-section,
.quantity-section {
  margin: 16px 0;
}

.section-label {
  font-size: 13px;
  color: #999;
  margin-bottom: 8px;
}

.stock-tip {
  margin-left: 12px;
  font-size: 12px;
  color: #999;
}

.action-row {
  margin-top: 24px;
  display: flex;
  gap: 12px;
}

.btn-icon {
  margin-right: 4px;
}

.detail-area {
  margin-top: 16px;
  background: #fff;
  border-radius: 8px;
  padding: 24px;
}

.detail-title {
  margin: 0 0 16px;
  font-size: 18px;
  color: #333;
  border-left: 4px solid #e93b3d;
  padding-left: 10px;
}

/* 富文本详情（v-html 内容不受 scoped 限制，用 :deep 覆盖） */
.rich-text {
  line-height: 1.8;
  color: #333;
  word-break: break-word;
}

.rich-text :deep(img) {
  max-width: 100%;
}
</style>
