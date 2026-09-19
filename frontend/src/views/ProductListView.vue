<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { categoryTree, productPage } from '@/api/product'
import type { Category, Product } from '@/api/types'

const PAGE_SIZE = 12

const route = useRoute()
const router = useRouter()

const categories = ref<Category[]>([])
const products = ref<Product[]>([])
const total = ref(0)
const pageNum = ref(1)
const loading = ref(false)

/** 从 query 解析 categoryId（可能是数组或非法值） */
const categoryId = computed<number | undefined>(() => {
  const raw = route.query.categoryId
  const s = Array.isArray(raw) ? (raw[0] ?? '') : (raw ?? '')
  const n = Number(s)
  return s !== '' && Number.isFinite(n) ? n : undefined
})

const categoryName = computed(() => {
  if (categoryId.value === undefined) return '全部商品'
  return findCategoryName(categories.value, categoryId.value) ?? '商品列表'
})

/** 在分类树中递归查找名称 */
function findCategoryName(list: Category[], id: number): string | null {
  for (const c of list) {
    if (c.id === id) return c.name
    if (c.children?.length) {
      const found = findCategoryName(c.children, id)
      if (found) return found
    }
  }
  return null
}

async function loadProducts() {
  loading.value = true
  try {
    const page = await productPage({
      pageNum: pageNum.value,
      pageSize: PAGE_SIZE,
      categoryId: categoryId.value,
    })
    products.value = page.records
    total.value = Number(page.total)
  } finally {
    loading.value = false
  }
}

function goDetail(id: number) {
  router.push(`/product/${id}`)
}

watch(categoryId, () => {
  pageNum.value = 1
  loadProducts()
})

onMounted(async () => {
  categories.value = await categoryTree()
  loadProducts()
})
</script>

<template>
  <div class="list-page" v-loading="loading">
    <div class="page-header">
      <h3 class="page-title">{{ categoryName }}</h3>
    </div>
    <el-empty v-if="!loading && products.length === 0" description="该分类下暂无商品" />
    <div class="product-grid">
      <el-card
        v-for="p in products"
        :key="p.id"
        shadow="hover"
        class="product-card"
        :body-style="{ padding: '0' }"
        @click="goDetail(p.id)"
      >
        <el-image class="card-pic" :src="p.mainPic ?? undefined" fit="cover">
          <template #error>
            <div class="pic-placeholder">暂无图片</div>
          </template>
        </el-image>
        <div class="card-info">
          <div class="card-name" :title="p.name">{{ p.name }}</div>
          <div class="card-sub" :title="p.subTitle ?? ''">{{ p.subTitle ?? '' }}</div>
          <div class="card-bottom">
            <span class="card-price">￥{{ p.price.toFixed(2) }}</span>
            <span class="card-sales">已售 {{ p.sales }}</span>
          </div>
        </div>
      </el-card>
    </div>
    <div class="pagination-wrap">
      <el-pagination
        v-model:current-page="pageNum"
        :page-size="PAGE_SIZE"
        :total="total"
        layout="total, prev, pager, next, jumper"
        @current-change="loadProducts"
      />
    </div>
  </div>
</template>

<style scoped>
.page-header {
  background: #fff;
  border-radius: 8px;
  padding: 14px 20px;
  margin-bottom: 16px;
}

.page-title {
  margin: 0;
  font-size: 18px;
  color: #333;
  border-left: 4px solid #e93b3d;
  padding-left: 10px;
}

.product-grid {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 12px;
}

.product-card {
  cursor: pointer;
  border-radius: 8px;
  overflow: hidden;
}

.card-pic {
  display: block;
  width: 100%;
  aspect-ratio: 1 / 1;
}

.pic-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  background: #f5f5f5;
  color: #999;
  font-size: 13px;
}

.card-info {
  padding: 10px 12px;
}

.card-name {
  font-size: 14px;
  color: #333;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-sub {
  margin-top: 4px;
  font-size: 12px;
  color: #999;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-height: 16px;
}

.card-bottom {
  margin-top: 8px;
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.card-price {
  color: #e93b3d;
  font-size: 16px;
  font-weight: 700;
}

.card-sales {
  font-size: 12px;
  color: #999;
}

.pagination-wrap {
  margin-top: 20px;
  display: flex;
  justify-content: center;
}
</style>
