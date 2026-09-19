<script setup lang="ts">
import { nextTick, onMounted, reactive, ref } from 'vue'
import dayjs from 'dayjs'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  adminAddSeckillActivity,
  adminAddSeckillProduct,
  adminDeleteSeckillProduct,
  adminUpdateSeckillActivity,
} from '@/api/admin'
import { seckillActivities } from '@/api/seckill'
import type { SeckillActivity, SeckillProduct } from '@/api/types'

type Phase = 'before' | 'ongoing' | 'ended'

const PHASE_TEXT: Record<Phase, string> = { before: '未开始', ongoing: '进行中', ended: '已结束' }
const PHASE_TAG: Record<Phase, 'info' | 'danger'> = { before: 'info', ongoing: 'danger', ended: 'info' }

const activities = ref<SeckillActivity[]>([])
const loading = ref(false)

/** 活动状态按当前时间推算 */
function phaseOf(act: SeckillActivity): Phase {
  const start = dayjs(act.startTime).valueOf()
  const end = dayjs(act.endTime).valueOf()
  const ts = Date.now()
  if (ts < start) return 'before'
  if (ts > end) return 'ended'
  return 'ongoing'
}

function formatTime(t: string): string {
  return dayjs(t).format('YYYY-MM-DD HH:mm:ss')
}

async function refresh() {
  loading.value = true
  try {
    activities.value = await seckillActivities()
  } catch {
    /* 失败提示由拦截器统一处理 */
  } finally {
    loading.value = false
  }
}

/* ---------- 新增活动 ---------- */
const activityDialogVisible = ref(false)
const submitting = ref(false)
const activityFormRef = ref<FormInstance>()
const activityForm = reactive({ name: '', startTime: '', endTime: '' })

const activityRules: FormRules = {
  name: [{ required: true, message: '请输入活动名称', trigger: 'blur' }],
  startTime: [{ required: true, message: '请选择开始时间', trigger: 'change' }],
  endTime: [{ required: true, message: '请选择结束时间', trigger: 'change' }],
}

function openActivityDialog() {
  Object.assign(activityForm, { name: '', startTime: '', endTime: '' })
  activityDialogVisible.value = true
  nextTick(() => activityFormRef.value?.clearValidate())
}

async function submitActivity() {
  if (!activityFormRef.value) return
  const valid = await activityFormRef.value.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    await adminAddSeckillActivity({ ...activityForm })
    ElMessage.success('新增活动成功')
    activityDialogVisible.value = false
    await refresh()
  } finally {
    submitting.value = false
  }
}

/** 上线/下线（status: 1 上线 0 下线），完成后刷新列表；失败时刷新可回滚开关 */
async function onActivityStatusChange(act: SeckillActivity, val: string | number | boolean) {
  const status = Number(val)
  try {
    await adminUpdateSeckillActivity(act.id, { status })
    ElMessage.success(status === 1 ? '活动已上线' : '活动已下线')
  } catch {
    /* 失败提示由拦截器统一处理 */
  }
  await refresh()
}

/* ---------- 活动商品管理 ---------- */
const productDialogVisible = ref(false)
const productSubmitting = ref(false)
const currentActivityId = ref(0)
const currentActivityName = ref('')
const productForm = reactive({
  productId: 1,
  skuId: 1,
  seckillPrice: 0,
  seckillStock: 0,
  limitPerUser: 1,
})

function openProductDialog(act: SeckillActivity) {
  currentActivityId.value = act.id
  currentActivityName.value = act.name
  Object.assign(productForm, {
    productId: 1,
    skuId: 1,
    seckillPrice: 0,
    seckillStock: 0,
    limitPerUser: 1,
  })
  productDialogVisible.value = true
}

async function submitSeckillProduct() {
  if (productForm.productId < 1 || productForm.skuId < 1) {
    ElMessage.warning('请输入有效的商品 ID 与 SKU ID')
    return
  }
  if (productForm.seckillPrice <= 0) {
    ElMessage.warning('秒杀价必须大于 0')
    return
  }
  if (productForm.seckillStock <= 0) {
    ElMessage.warning('秒杀库存必须大于 0')
    return
  }
  productSubmitting.value = true
  try {
    await adminAddSeckillProduct({ activityId: currentActivityId.value, ...productForm })
    ElMessage.success('添加秒杀商品成功')
    productDialogVisible.value = false
    await refresh()
  } finally {
    productSubmitting.value = false
  }
}

async function onDeleteSeckillProduct(sp: SeckillProduct) {
  try {
    await ElMessageBox.confirm(`确定删除秒杀商品「${sp.productName}」吗？`, '删除确认', {
      type: 'warning',
    })
  } catch {
    return
  }
  await adminDeleteSeckillProduct(sp.id)
  ElMessage.success('已删除')
  await refresh()
}

onMounted(refresh)
</script>

<template>
  <div class="admin-seckill-page">
    <div class="toolbar">
      <span class="page-hint">活动状态按当前时间自动推算</span>
      <div class="toolbar-spacer" />
      <el-button type="danger" @click="openActivityDialog">
        <el-icon><Plus /></el-icon>新增活动
      </el-button>
    </div>

    <el-table :data="activities" v-loading="loading" border>
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="sp-manage">
            <div class="sp-manage-head">
              <span class="sp-manage-title">活动商品</span>
              <el-button type="primary" size="small" @click="openProductDialog(row as SeckillActivity)">
                <el-icon><Plus /></el-icon>添加秒杀商品
              </el-button>
            </div>
            <el-table
              v-if="(row as SeckillActivity).products.length > 0"
              :data="(row as SeckillActivity).products"
              border
              size="small"
            >
              <el-table-column prop="productName" label="商品名称" min-width="180" show-overflow-tooltip />
              <el-table-column label="秒杀价" width="110" align="right">
                <template #default="{ row: sp }">¥{{ (sp as SeckillProduct).seckillPrice.toFixed(2) }}</template>
              </el-table-column>
              <el-table-column prop="seckillStock" label="秒杀库存" width="100" align="center" />
              <el-table-column prop="limitPerUser" label="每人限购" width="100" align="center" />
              <el-table-column label="操作" width="90" align="center">
                <template #default="{ row: sp }">
                  <el-button type="danger" link size="small" @click="onDeleteSeckillProduct(sp as SeckillProduct)">
                    删除
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-else description="暂无秒杀商品" :image-size="50" />
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="name" label="活动名称" min-width="200" show-overflow-tooltip />
      <el-table-column label="开始时间" width="170">
        <template #default="{ row }">{{ formatTime((row as SeckillActivity).startTime) }}</template>
      </el-table-column>
      <el-table-column label="结束时间" width="170">
        <template #default="{ row }">{{ formatTime((row as SeckillActivity).endTime) }}</template>
      </el-table-column>
      <el-table-column label="活动状态" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="PHASE_TAG[phaseOf(row as SeckillActivity)]" size="small">
            {{ PHASE_TEXT[phaseOf(row as SeckillActivity)] }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="上线状态" width="110" align="center">
        <template #default="{ row }">
          <el-switch
            :model-value="(row as SeckillActivity).status"
            :active-value="1"
            :inactive-value="0"
            @change="(val: string | number | boolean) => onActivityStatusChange(row as SeckillActivity, val)"
          />
        </template>
      </el-table-column>
    </el-table>

    <!-- 新增活动 -->
    <el-dialog v-model="activityDialogVisible" title="新增秒杀活动" width="460px">
      <el-form ref="activityFormRef" :model="activityForm" :rules="activityRules" label-width="90px">
        <el-form-item label="活动名称" prop="name">
          <el-input v-model="activityForm.name" placeholder="如：双 11 尖货秒杀" maxlength="50" />
        </el-form-item>
        <el-form-item label="开始时间" prop="startTime">
          <el-date-picker
            v-model="activityForm.startTime"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            placeholder="选择开始时间"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="结束时间" prop="endTime">
          <el-date-picker
            v-model="activityForm.endTime"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            placeholder="选择结束时间"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="activityDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitActivity">保存</el-button>
      </template>
    </el-dialog>

    <!-- 添加秒杀商品 -->
    <el-dialog
      v-model="productDialogVisible"
      :title="`添加秒杀商品 - ${currentActivityName}`"
      width="460px"
    >
      <el-form :model="productForm" label-width="90px">
        <el-form-item label="商品 ID">
          <el-input-number v-model="productForm.productId" :min="1" :step="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="SKU ID">
          <el-input-number v-model="productForm.skuId" :min="1" :step="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="秒杀价(元)">
          <el-input-number v-model="productForm.seckillPrice" :min="0" :precision="2" style="width: 100%" />
        </el-form-item>
        <el-form-item label="秒杀库存">
          <el-input-number v-model="productForm.seckillStock" :min="0" :step="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="每人限购">
          <el-input-number v-model="productForm.limitPerUser" :min="1" :step="1" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="productDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="productSubmitting" @click="submitSeckillProduct">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.admin-seckill-page {
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

.page-hint {
  color: #999;
  font-size: 13px;
}

.sp-manage {
  padding: 8px 24px 16px 48px;
}

.sp-manage-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

.sp-manage-title {
  font-weight: 600;
  color: #333;
}
</style>
