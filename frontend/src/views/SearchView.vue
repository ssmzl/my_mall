<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { searchProducts } from '@/api/search'
import { categoryTree } from '@/api/product'
import type { Category, SearchProduct, SearchQuery } from '@/api/types'

const PAGE_SIZE = 12

const route = useRoute()
const router = useRouter()

const keyword = ref('')
const categoryId = ref<number | undefined>(undefined)
const minPriceText = ref('')
const maxPriceText = ref('')
const sort = ref<SearchQuery['sort']>('default')

const categoryOptions = ref<{ id: number; name: string }[]>([])
const products = ref<SearchProduct[]>([])
const total = ref(0)
const pageNum = ref(1)
const loading = ref(false)

/** 分类树扁平化：取二级分类，名称带父级前缀 */
function flattenCategories(list: Category[]): { id: number; name: string }[] {
  const out: { id: number; name: string }[] = []
  for (const top of list) {
    if (top.children?.length) {
      for (const child of top.children) {
        out.push({ id: child.id, name: `${top.name} / ${child.name}` })
      }
    } else {
      out.push({ id: top.id, name: top.name })
    }
  }
  return out
}

/** 价格输入转数字（空/非法返回 undefined） */
function toNumber(v: string): number | undefined {
  const t = v.trim()
  if (!t) return undefined
  const n = Number(t)
  return Number.isFinite(n) ? n : undefined
}

/** HTML 转义后恢复后端高亮 <em> 标签为带样式的 <em class="highlight"> */
function renderHighlight(raw: string | null): string {
  if (!raw) return ''
  const escaped = raw
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
  return escaped
    .replace(/&lt;em&gt;/g, '<em class="highlight">')
    .replace(/&lt;\/em&gt;/g, '</em>')
}

async function doSearch() {
  if (!keyword.value) {
    products.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    const params: SearchQuery = {
      keyword: keyword.value,
      sort: sort.value,
      pageNum: pageNum.value,
      pageSize: PAGE_SIZE,
    }
    const min = toNumber(minPriceText.value)
    const max = toNumber(maxPriceText.value)
    if (categoryId.value !== undefined) params.categoryId = categoryId.value
    if (min !== undefined) params.minPrice = min
    if (max !== undefined) params.maxPrice = max
    const page = await searchProducts(params)
    products.value = page.records
    total.value = Number(page.total)
  } finally {
    loading.value = false
  }
}

function onFilterChange() {
  pageNum.value = 1
  doSearch()
}

function goDetail(id: number) {
  router.push(`/product/${id}`)
}

/** 从 query 读取关键词（兼容数组形式） */
function readKeyword() {
  const raw = route.query.keyword
  const s = Array.isArray(raw) ? (raw[0] ?? '') : (raw ?? '')
  keyword.value = String(s).trim()
}

watch(keyword, () => {
  pageNum.value = 1
  categoryId.value = undefined
  doSearch()
})

onMounted(async () => {
  readKeyword()
  const tree = await categoryTree()
  categoryOptions.value = flattenCategories(tree)
  doSearch()
})
</script>

<template>
  <div class="search-page">
    <!-- 筛选条 -->
    <el-card class="filter-bar" shadow="never" :body-style="{ padding: '14px 20px' }">
      <div class="filter-row">
        <span class="filter-label">关键词：</span>
        <el-tag v-if="keyword" type="danger" effect="plain" size="large">{{ keyword }}</el-tag>
        <span v-else class="filter-empty">未指定关键词</span>
      </div>
      <div class="filter-row">
        <span class="filter-label">分类：</span>
        <el-select
          v-model="categoryId"
          placeholder="全部分类"
          clearable
          style="width: 220px"
          @change="onFilterChange"
        >
          <el-option
            v-for="opt in categoryOptions"
            :key="opt.id"
            :label="opt.name"
            :value="opt.id"
          />
        </el-select>

        <span class="filter-label price-label">价格：</span>
        <el-input v-model="minPriceText" placeholder="最低价" style="width: 110px" clearable />
        <span class="price-sep">-</span>
        <el-input v-model="maxPriceText" placeholder="最高价" style="width: 110px" clearable />
        <el-button type="danger" plain @click="onFilterChange">确定</el-button>

        <span class="filter-label sort-label">排序：</span>
        <el-radio-group v-model="sort" @change="onFilterChange">
          <el-radio-button value="default">综合</el-radio-button>
          <el-radio-button value="sales">销量</el-radio-button>
          <el-radio-button value="priceAsc">价格升</el-radio-button>
          <el-radio-button value="priceDesc">价格降</el-radio-button>
        </el-radio-group>
      </div>
    </el-card>

    <!-- 结果区 -->
    <div v-loading="loading">
      <el-empty v-if="!loading && products.length === 0" description="未找到相关商品" />
      <div v-else class="product-grid">
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
            <!-- 后端返回的 name/subTitle 含 <em> 高亮标签，转义后安全渲染 -->
            <div class="card-name" :title="p.name" v-html="renderHighlight(p.name)"></div>
            <div class="card-sub" :title="p.subTitle ?? ''" v-html="renderHighlight(p.subTitle)"></div>
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
          @current-change="doSearch"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.filter-bar {
  margin-bottom: 16px;
  border-radius: 8px;
}

.filter-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.filter-row + .filter-row {
  margin-top: 12px;
}

.filter-label {
  font-size: 14px;
  color: #666;
}

.price-label {
  margin-left: 10px;
}

.sort-label {
  margin-left: auto;
}

.price-sep {
  color: #999;
}

.filter-empty {
  font-size: 14px;
  color: #999;
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

.card-name :deep(em.highlight),
.card-sub :deep(em.highlight) {
  font-style: normal;
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
