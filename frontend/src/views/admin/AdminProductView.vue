<script setup lang="ts">
import { nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { CascaderProps, FormInstance, FormRules } from 'element-plus'
import {
  adminAddProduct,
  adminAddSku,
  adminCategoryTree,
  adminDeleteSku,
  adminProductPage,
  adminUpdateProduct,
  adminUpdateProductStatus,
  adminUpdateSku,
} from '@/api/admin'
import type { AdminProductPayload } from '@/api/admin'
import { productDetail } from '@/api/product'
import type { Category, Product, ProductDetail, Sku } from '@/api/types'

const records = ref<Product[]>([])
const total = ref(0)
const loading = ref(false)
const pageNum = ref(1)
const pageSize = ref(10)
const keyword = ref('')

/* ---------- 商品新增/编辑 ---------- */
const productDialogVisible = ref(false)
const editingProductId = ref<number | null>(null)
const submitting = ref(false)
const productFormRef = ref<FormInstance>()

const productForm = reactive<{
  categoryId: number | null
  name: string
  subTitle: string
  mainPic: string
  detail: string
  price: number
  stock: number
}>({
  categoryId: null,
  name: '',
  subTitle: '',
  mainPic: '',
  detail: '',
  price: 0,
  stock: 0,
})

const productRules: FormRules = {
  categoryId: [{ required: true, message: '请选择商品分类', trigger: 'change' }],
  name: [{ required: true, message: '请输入商品名称', trigger: 'blur' }],
}

/** 只允许选中二级分类（叶子节点），v-model 直接取分类 id */
const categoryOptions = ref<Category[]>([])
const cascaderProps: CascaderProps = {
  value: 'id',
  label: 'name',
  children: 'children',
  emitPath: false,
}

/* ---------- SKU 管理 ---------- */
const skuDialogVisible = ref(false)
const skuLoading = ref(false)
const skuSubmitting = ref(false)
const currentProduct = ref<ProductDetail | null>(null)
const editingSkuId = ref<number | null>(null)
const skuForm = reactive({ skuCode: '', specJson: '', price: 0, stock: 0 })

async function fetchPage() {
  loading.value = true
  try {
    const data = await adminProductPage({
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      keyword: keyword.value.trim() || undefined,
    })
    records.value = data.records
    total.value = data.total
  } catch {
    /* 失败提示由拦截器统一处理 */
  } finally {
    loading.value = false
  }
}

async function fetchCategories() {
  try {
    categoryOptions.value = await adminCategoryTree()
  } catch {
    /* 失败提示由拦截器统一处理 */
  }
}

function onSearch() {
  pageNum.value = 1
  fetchPage()
}

function onSizeChange() {
  pageNum.value = 1
  fetchPage()
}

/** 上下架切换：先确认（变更会同步搜索引擎），失败/取消回滚开关 */
async function onStatusChange(row: Product, val: string | number | boolean) {
  const target = Number(val)
  const text = target === 1 ? '上架' : '下架'
  try {
    await ElMessageBox.confirm(
      `确定${text}「${row.name}」吗？商品上下架会同步搜索引擎。`,
      '上下架确认',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await adminUpdateProductStatus(row.id, target)
    row.status = target
    ElMessage.success(`${text}成功`)
  } catch {
    /* 失败提示由拦截器统一处理，开关回滚 */
  }
}

function resetProductForm() {
  editingProductId.value = null
  Object.assign(productForm, {
    categoryId: null,
    name: '',
    subTitle: '',
    mainPic: '',
    detail: '',
    price: 0,
    stock: 0,
  })
}

function openAdd() {
  resetProductForm()
  productDialogVisible.value = true
  nextTick(() => productFormRef.value?.clearValidate())
}

function openEdit(row: Product) {
  editingProductId.value = row.id
  Object.assign(productForm, {
    categoryId: row.categoryId,
    name: row.name,
    subTitle: row.subTitle ?? '',
    mainPic: row.mainPic ?? '',
    detail: '',
    price: row.price,
    stock: row.stock,
  })
  productDialogVisible.value = true
  nextTick(() => productFormRef.value?.clearValidate())
}

async function submitProduct() {
  if (!productFormRef.value) return
  const valid = await productFormRef.value.validate().catch(() => false)
  if (!valid) return
  const { categoryId } = productForm
  if (categoryId == null) return
  submitting.value = true
  const payload: AdminProductPayload = {
    categoryId,
    name: productForm.name.trim(),
    subTitle: productForm.subTitle || undefined,
    mainPic: productForm.mainPic || undefined,
    detail: productForm.detail || undefined,
    price: productForm.price,
    stock: productForm.stock,
  }
  try {
    if (editingProductId.value == null) {
      await adminAddProduct(payload)
      ElMessage.success('新增商品成功')
    } else {
      await adminUpdateProduct(editingProductId.value, payload)
      ElMessage.success('修改商品成功')
    }
    productDialogVisible.value = false
    await fetchPage()
  } finally {
    submitting.value = false
  }
}

async function loadSkus(productId: number) {
  skuLoading.value = true
  try {
    currentProduct.value = await productDetail(productId)
  } catch {
    /* 失败提示由拦截器统一处理 */
  } finally {
    skuLoading.value = false
  }
}

async function openSkuDialog(row: Product) {
  currentProduct.value = null
  resetSkuForm()
  skuDialogVisible.value = true
  await loadSkus(row.id)
}

function resetSkuForm() {
  editingSkuId.value = null
  Object.assign(skuForm, { skuCode: '', specJson: '', price: 0, stock: 0 })
}

function openEditSku(sku: Sku) {
  editingSkuId.value = sku.id
  Object.assign(skuForm, {
    skuCode: sku.skuCode,
    specJson: sku.specJson ?? '',
    price: sku.price,
    stock: sku.stock,
  })
}

async function submitSku() {
  const product = currentProduct.value
  if (!product) return
  if (!skuForm.skuCode.trim()) {
    ElMessage.warning('请输入 SKU 编码')
    return
  }
  skuSubmitting.value = true
  const data = {
    skuCode: skuForm.skuCode.trim(),
    specJson: skuForm.specJson || undefined,
    price: skuForm.price,
    stock: skuForm.stock,
  }
  try {
    if (editingSkuId.value == null) {
      await adminAddSku(product.id, data)
      ElMessage.success('新增 SKU 成功')
    } else {
      await adminUpdateSku(editingSkuId.value, data)
      ElMessage.success('修改 SKU 成功')
    }
    resetSkuForm()
    await loadSkus(product.id)
    // SKU 变更会回写商品展示价/库存，主列表需同步刷新，否则价格列仍显示旧值
    await fetchPage()
  } finally {
    skuSubmitting.value = false
  }
}

async function onDeleteSku(sku: Sku) {
  try {
    await ElMessageBox.confirm(`确定删除 SKU「${sku.skuCode}」吗？`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  await adminDeleteSku(sku.id)
  ElMessage.success('已删除')
  if (currentProduct.value) {
    await loadSkus(currentProduct.value.id)
  }
  await fetchPage()
}

onMounted(() => {
  fetchPage()
  fetchCategories()
})
</script>

<template>
  <div class="admin-product-page">
    <div class="toolbar">
      <el-input
        v-model="keyword"
        placeholder="商品名称关键词"
        clearable
        style="width: 240px"
        @keyup.enter="onSearch"
        @clear="onSearch"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <el-button type="primary" @click="onSearch">查询</el-button>
      <div class="toolbar-spacer" />
      <el-button type="danger" @click="openAdd">
        <el-icon><Plus /></el-icon>新增商品
      </el-button>
    </div>

    <el-table :data="records" v-loading="loading" border stripe>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="name" label="商品名称" min-width="220" show-overflow-tooltip />
      <el-table-column prop="categoryId" label="分类ID" width="90" align="center" />
      <el-table-column label="价格" width="110" align="right">
        <template #default="{ row }">¥{{ (row as Product).price.toFixed(2) }}</template>
      </el-table-column>
      <el-table-column prop="stock" label="库存" width="90" align="center" />
      <el-table-column prop="sales" label="销量" width="90" align="center" />
      <el-table-column label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-switch
            :model-value="(row as Product).status"
            :active-value="1"
            :inactive-value="0"
            @change="(val: string | number | boolean) => onStatusChange(row as Product, val)"
          />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link size="small" @click="openEdit(row as Product)">编辑</el-button>
          <el-button type="primary" link size="small" @click="openSkuDialog(row as Product)">SKU 管理</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination
        v-model:current-page="pageNum"
        v-model:page-size="pageSize"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next, jumper"
        @current-change="fetchPage"
        @size-change="onSizeChange"
      />
    </div>

    <!-- 新增/编辑商品 -->
    <el-dialog
      v-model="productDialogVisible"
      :title="editingProductId == null ? '新增商品' : '编辑商品'"
      width="560px"
    >
      <el-form ref="productFormRef" :model="productForm" :rules="productRules" label-width="90px">
        <el-form-item label="商品分类" prop="categoryId">
          <el-cascader
            v-model="productForm.categoryId"
            :options="categoryOptions"
            :props="cascaderProps"
            placeholder="请选择二级分类"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="商品名称" prop="name">
          <el-input v-model="productForm.name" placeholder="请输入商品名称" maxlength="100" />
        </el-form-item>
        <el-form-item label="副标题">
          <el-input v-model="productForm.subTitle" placeholder="请输入副标题（可留空）" maxlength="200" />
        </el-form-item>
        <el-form-item label="主图地址">
          <el-input v-model="productForm.mainPic" placeholder="请输入主图 URL（可留空）" />
        </el-form-item>
        <el-form-item label="商品详情">
          <el-input v-model="productForm.detail" type="textarea" :rows="4" placeholder="请输入商品详情" />
        </el-form-item>
        <el-form-item label="价格(元)">
          <el-input-number v-model="productForm.price" :min="0" :precision="2" :step="1" />
        </el-form-item>
        <el-form-item label="库存">
          <el-input-number v-model="productForm.stock" :min="0" :step="1" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="productDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitProduct">保存</el-button>
      </template>
    </el-dialog>

    <!-- SKU 管理 -->
    <el-dialog
      v-model="skuDialogVisible"
      :title="`SKU 管理 - ${currentProduct?.name ?? ''}`"
      width="880px"
    >
      <div v-loading="skuLoading">
        <el-table :data="currentProduct?.skus ?? []" border size="small" max-height="300">
          <el-table-column prop="skuCode" label="SKU 编码" min-width="150" />
          <el-table-column label="规格" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">{{ (row as Sku).specJson || '-' }}</template>
          </el-table-column>
          <el-table-column label="价格" width="110" align="right">
            <template #default="{ row }">¥{{ (row as Sku).price.toFixed(2) }}</template>
          </el-table-column>
          <el-table-column prop="stock" label="库存" width="90" align="center" />
          <el-table-column label="操作" width="130" align="center">
            <template #default="{ row }">
              <el-button type="primary" link size="small" @click="openEditSku(row as Sku)">编辑</el-button>
              <el-button type="danger" link size="small" @click="onDeleteSku(row as Sku)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-divider content-position="left">{{ editingSkuId == null ? '新增 SKU' : '编辑 SKU' }}</el-divider>
        <el-form :model="skuForm" inline label-width="80px" @submit.prevent="submitSku">
          <el-form-item label="SKU 编码">
            <el-input v-model="skuForm.skuCode" placeholder="如 IPHONE14-BLK-256" style="width: 190px" />
          </el-form-item>
          <el-form-item label="规格JSON">
            <el-input v-model="skuForm.specJson" placeholder='如 {"颜色":"黑色"}' style="width: 210px" />
          </el-form-item>
          <el-form-item label="价格">
            <el-input-number v-model="skuForm.price" :min="0" :precision="2" style="width: 130px" />
          </el-form-item>
          <el-form-item label="库存">
            <el-input-number v-model="skuForm.stock" :min="0" style="width: 120px" />
          </el-form-item>
          <el-form-item>
            <el-button @click="resetSkuForm">重置</el-button>
            <el-button type="primary" :loading="skuSubmitting" @click="submitSku">
              {{ editingSkuId == null ? '新增' : '保存' }}
            </el-button>
          </el-form-item>
        </el-form>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.admin-product-page {
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

.toolbar-spacer {
  flex: 1;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}
</style>
