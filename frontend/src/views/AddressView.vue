<script setup lang="ts">
import { nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  addAddress,
  deleteAddress,
  listAddress,
  setDefaultAddress,
  updateAddress,
} from '@/api/address'
import type { Address, AddressPayload } from '@/api/types'

const list = ref<Address[]>([])
const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const submitting = ref(false)
const formRef = ref<FormInstance>()

const form = reactive<AddressPayload>({
  receiverName: '',
  receiverPhone: '',
  province: '',
  city: '',
  district: '',
  detailAddress: '',
  isDefault: false,
})

const rules: FormRules = {
  receiverName: [{ required: true, message: '请输入收货人姓名', trigger: 'blur' }],
  receiverPhone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' },
  ],
  province: [{ required: true, message: '请输入省份', trigger: 'blur' }],
  city: [{ required: true, message: '请输入城市', trigger: 'blur' }],
  district: [{ required: true, message: '请输入所在区/县', trigger: 'blur' }],
  detailAddress: [{ required: true, message: '请输入详细地址', trigger: 'blur' }],
}

async function refresh() {
  try {
    list.value = await listAddress()
  } catch {
    /* 失败提示由拦截器统一处理 */
  }
}

function resetForm(payload?: AddressPayload) {
  Object.assign(form, {
    receiverName: '',
    receiverPhone: '',
    province: '',
    city: '',
    district: '',
    detailAddress: '',
    isDefault: false,
    ...payload,
  })
}

function openAdd() {
  editingId.value = null
  resetForm()
  dialogVisible.value = true
  nextTick(() => formRef.value?.clearValidate())
}

function openEdit(addr: Address) {
  editingId.value = addr.id
  resetForm({
    receiverName: addr.receiverName,
    receiverPhone: addr.receiverPhone,
    province: addr.province,
    city: addr.city,
    district: addr.district,
    detailAddress: addr.detailAddress,
    isDefault: addr.isDefault === 1,
  })
  dialogVisible.value = true
  nextTick(() => formRef.value?.clearValidate())
}

async function onSubmit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    if (editingId.value == null) {
      await addAddress({ ...form })
      ElMessage.success('新增地址成功')
    } else {
      await updateAddress(editingId.value, { ...form })
      ElMessage.success('修改地址成功')
    }
    dialogVisible.value = false
    await refresh()
  } finally {
    submitting.value = false
  }
}

async function onDelete(addr: Address) {
  try {
    await ElMessageBox.confirm(
      `确定删除「${addr.receiverName}」的收货地址吗？`,
      '删除确认',
      { type: 'warning' },
    )
  } catch {
    return
  }
  await deleteAddress(addr.id)
  ElMessage.success('已删除')
  await refresh()
}

async function onSetDefault(addr: Address) {
  await setDefaultAddress(addr.id)
  ElMessage.success('已设为默认地址')
  await refresh()
}

onMounted(refresh)
</script>

<template>
  <div class="address-page">
    <div class="page-head">
      <h2 class="page-title">收货地址</h2>
      <el-button type="danger" @click="openAdd">新增地址</el-button>
    </div>

    <div v-if="list.length > 0" class="addr-grid">
      <el-card
        v-for="addr in list"
        :key="addr.id"
        class="addr-card"
        :class="{ 'is-default': addr.isDefault === 1 }"
        shadow="hover"
      >
        <div class="addr-head">
          <el-icon :size="18" class="addr-icon"><Location /></el-icon>
          <span class="addr-name">{{ addr.receiverName }}</span>
          <span class="addr-phone">{{ addr.receiverPhone }}</span>
          <el-tag v-if="addr.isDefault === 1" type="danger" size="small" effect="light">默认</el-tag>
        </div>
        <div class="addr-detail">
          {{ addr.province }}{{ addr.city }}{{ addr.district }}{{ addr.detailAddress }}
        </div>
        <div class="addr-actions">
          <el-button
            v-if="addr.isDefault !== 1"
            type="primary"
            link
            size="small"
            @click="onSetDefault(addr)"
          >
            设为默认
          </el-button>
          <el-button type="primary" link size="small" @click="openEdit(addr)">编辑</el-button>
          <el-button type="danger" link size="small" @click="onDelete(addr)">删除</el-button>
        </div>
      </el-card>
    </div>

    <el-empty v-else description="还没有收货地址，点击右上角新增" />
    <el-dialog
      v-model="dialogVisible"
      :title="editingId == null ? '新增收货地址' : '编辑收货地址'"
      width="480px"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="收货人" prop="receiverName">
          <el-input v-model="form.receiverName" placeholder="请输入收货人姓名" maxlength="20" />
        </el-form-item>
        <el-form-item label="手机号" prop="receiverPhone">
          <el-input v-model="form.receiverPhone" placeholder="请输入11位手机号" maxlength="11" />
        </el-form-item>
        <el-form-item label="省份" prop="province">
          <el-input v-model="form.province" placeholder="如：广东省" maxlength="20" />
        </el-form-item>
        <el-form-item label="城市" prop="city">
          <el-input v-model="form.city" placeholder="如：深圳市" maxlength="20" />
        </el-form-item>
        <el-form-item label="区/县" prop="district">
          <el-input v-model="form.district" placeholder="如：南山区" maxlength="20" />
        </el-form-item>
        <el-form-item label="详细地址" prop="detailAddress">
          <el-input
            v-model="form.detailAddress"
            type="textarea"
            :rows="2"
            placeholder="街道、门牌号、小区等"
            maxlength="100"
          />
        </el-form-item>
        <el-form-item label="默认地址">
          <el-switch v-model="form.isDefault" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="onSubmit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.page-title {
  margin: 0;
  font-size: 20px;
}

.addr-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 16px;
}

.addr-card {
  border-radius: 8px;
}

/* 默认地址卡片：品牌色描边突出 */
.addr-card.is-default {
  border-color: var(--mall-brand);
}

.addr-icon {
  color: var(--mall-brand);
}

.addr-head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.addr-name {
  font-size: 16px;
  font-weight: 600;
  color: #333;
}

.addr-phone {
  color: #666;
}

.addr-detail {
  margin: 10px 0;
  font-size: 13px;
  color: #666;
  line-height: 1.6;
  min-height: 40px;
}

.addr-actions {
  display: flex;
  justify-content: flex-end;
  gap: 4px;
  border-top: 1px solid #f0f0f0;
  padding-top: 8px;
}
</style>
