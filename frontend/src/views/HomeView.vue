<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { categoryTree, productPage } from '@/api/product'
import type { Category, Product } from '@/api/types'

const PAGE_SIZE = 12

const router = useRouter()

const categories = ref<Category[]>([])
const products = ref<Product[]>([])
const total = ref(0)
const pageNum = ref(1)
const loading = ref(false)

async function loadCategories() {
  categories.value = await categoryTree()
}

async function loadProducts() {
  loading.value = true
  try {
    const page = await productPage({ pageNum: pageNum.value, pageSize: PAGE_SIZE })
    products.value = page.records
    total.value = Number(page.total)
  } finally {
    loading.value = false
  }
}

function goCategory(id: number) {
  router.push({ path: '/products', query: { categoryId: String(id) } })
}

function goDetail(id: number) {
  router.push(`/product/${id}`)
}

onMounted(() => {
  loadCategories()
  loadProducts()
})
</script>

<template>
  <div class="home-page">
    <!-- 左侧分类侧栏 -->
    <aside class="cate-side">
      <h3 class="side-title">商品分类</h3>
      <div v-for="c in categories" :key="c.id" class="cate-item">
        <div class="cate-name" @click="goCategory(c.id)">
          <span>{{ c.name }}</span>
          <el-icon><ArrowRight /></el-icon>
        </div>
        <div v-if="c.children?.length" class="cate-children">
          <span v-for="sub in c.children" :key="sub.id" class="cate-sub" @click="goCategory(sub.id)">
            {{ sub.name }}
          </span>
        </div>
      </div>
    </aside>

    <!-- 右侧新品推荐 -->
    <main class="product-area" v-loading="loading">
      <h3 class="area-title">新品推荐</h3>
      <el-empty v-if="!loading && products.length === 0" description="暂无商品" />
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
    </main>
  </div>
</template>

<style scoped>
.home-page {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.cate-side {
  width: 220px;
  flex-shrink: 0;
  background: #fff;
  border-radius: 8px;
  padding: 12px;
}

.side-title,
.area-title {
  margin: 0 0 12px;
  font-size: 16px;
  color: #333;
}

.cate-item {
  padding: 8px 4px;
  border-bottom: 1px solid #f0f0f0;
}

.cate-item:last-child {
  border-bottom: none;
}

.cate-name {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 14px;
  font-weight: 600;
  color: #333;
  cursor: pointer;
}

.cate-name:hover {
  color: #e93b3d;
}

.cate-children {
  margin-top: 6px;
  display: flex;
  flex-wrap: wrap;
  gap: 4px 10px;
}

.cate-sub {
  font-size: 12px;
  color: #666;
  cursor: pointer;
}

.cate-sub:hover {
  color: #e93b3d;
}

.product-area {
  flex: 1;
  min-width: 0;
}

.product-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
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
