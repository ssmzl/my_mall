<script setup lang="ts">
import { nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  adminAddCategory,
  adminCategoryTree,
  adminDeleteCategory,
  adminUpdateCategory,
} from '@/api/admin'
import type { Category } from '@/api/types'

const tree = ref<Category[]>([])
const loading = ref(false)

/* ---------- 新增/编辑 ---------- */
const dialogVisible = ref(false)
/** null = 新增，非空 = 编辑的分类 id */
const editingId = ref<number | null>(null)
const submitting = ref(false)
const formRef = ref<FormInstance>()

const form = reactive<{ parentId: number | null; name: string; sort: number }>({
  parentId: null,
  name: '',
  sort: 0,
})

const rules: FormRules = {
  parentId: [{ required: true, message: '请选择上级分类', trigger: 'change' }],
  name: [
    { required: true, message: '请输入分类名称', trigger: 'blur' },
    { max: 50, message: '分类名称长度不能超过 50', trigger: 'blur' },
  ],
}

async function fetchTree() {
  loading.value = true
  try {
    tree.value = await adminCategoryTree()
  } catch {
    /* 失败提示由拦截器统一处理 */
  } finally {
    loading.value = false
  }
}

function resetForm() {
  editingId.value = null
  Object.assign(form, { parentId: null, name: '', sort: 0 })
}

/** 新增一级分类 */
function openAddRoot() {
  resetForm()
  form.parentId = 0
  dialogVisible.value = true
  nextTick(() => formRef.value?.clearValidate())
}

/** 在某个一级分类下新增子分类 */
function openAddChild(parent: Category) {
  resetForm()
  form.parentId = parent.id
  dialogVisible.value = true
  nextTick(() => formRef.value?.clearValidate())
}

/** 编辑：契约仅支持改名称/排序（状态用列表开关，层级不可变） */
function openEdit(row: Category) {
  resetForm()
  editingId.value = row.id
  form.parentId = row.parentId
  form.name = row.name
  form.sort = row.sort
  dialogVisible.value = true
  nextTick(() => formRef.value?.clearValidate())
}

async function submit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    if (editingId.value == null) {
      await adminAddCategory({ parentId: form.parentId!, name: form.name.trim(), sort: form.sort })
      ElMessage.success('新增分类成功')
    } else {
      await adminUpdateCategory(editingId.value, { name: form.name.trim(), sort: form.sort })
      ElMessage.success('修改分类成功')
    }
    dialogVisible.value = false
    await fetchTree()
  } finally {
    submitting.value = false
  }
}

/** 启用/禁用：先确认，失败或取消时开关自动回滚（未直接改 row.status） */
async function onStatusChange(row: Category, val: string | number | boolean) {
  const target = Number(val)
  const text = target === 1 ? '启用' : '禁用'
  try {
    await ElMessageBox.confirm(`确定${text}分类「${row.name}」吗？`, '状态确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await adminUpdateCategory(row.id, { status: target })
    row.status = target
    ElMessage.success(`${text}成功`)
  } catch {
    /* 失败提示由拦截器统一处理 */
  }
}

async function onDelete(row: Category) {
  try {
    await ElMessageBox.confirm(
      `确定删除分类「${row.name}」吗？存在子分类或分类下有商品时将无法删除。`,
      '删除确认',
      { type: 'warning' },
    )
  } catch {
    return
  }
  await adminDeleteCategory(row.id)
  ElMessage.success('已删除')
  await fetchTree()
}

onMounted(fetchTree)
</script>

<template>
  <div class="admin-category-page">
    <div class="toolbar">
      <span class="toolbar-tip">层级固定两级：一级分类下挂二级分类，商品必须挂二级分类</span>
      <div class="toolbar-spacer" />
      <el-button type="primary" @click="openAddRoot">
        <el-icon><Plus /></el-icon>新增一级分类
      </el-button>
    </div>

    <el-table
      :data="tree"
      v-loading="loading"
      border
      row-key="id"
      :tree-props="{ children: 'children' }"
      default-expand-all
    >
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="name" label="分类名称" min-width="240" />
      <el-table-column label="层级" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="(row as Category).level === 1 ? 'danger' : 'info'" size="small">
            {{ (row as Category).level === 1 ? '一级' : '二级' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="sort" label="排序" width="90" align="center" />
      <el-table-column label="状态" width="100" align="center">
        <template #default="{ row }">
          <el-switch
            :model-value="(row as Category).status"
            :active-value="1"
            :inactive-value="0"
            @change="(val: string | number | boolean) => onStatusChange(row as Category, val)"
          />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="(row as Category).level === 1"
            type="primary"
            link
            size="small"
            @click="openAddChild(row as Category)"
          >
            添加子分类
          </el-button>
          <el-button type="primary" link size="small" @click="openEdit(row as Category)">编辑</el-button>
          <el-button type="danger" link size="small" @click="onDelete(row as Category)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新增/编辑分类 -->
    <el-dialog
      v-model="dialogVisible"
      :title="editingId == null ? (form.parentId === 0 ? '新增一级分类' : '新增子分类') : '编辑分类'"
      width="440px"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="上级分类">
          <el-select
            v-model="form.parentId"
            :disabled="editingId != null"
            placeholder="请选择上级分类"
            style="width: 100%"
          >
            <el-option label="无（作为一级分类）" :value="0" />
            <el-option v-for="c in tree" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="分类名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入分类名称" maxlength="50" show-word-limit />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sort" :min="0" :step="1" />
          <span class="sort-tip">越小越靠前</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.admin-category-page {
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

.toolbar-tip {
  font-size: 13px;
  color: #999;
}

.toolbar-spacer {
  flex: 1;
}

.sort-tip {
  margin-left: 8px;
  font-size: 12px;
  color: #999;
}
</style>
