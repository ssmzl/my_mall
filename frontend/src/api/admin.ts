import { del, get, post, put } from './request'
import type {
  AdminOrderQuery,
  Category,
  Order,
  PageData,
  PageQuery,
  Product,
  Sku,
} from './types'

/* ---------- 分类维护 ---------- */

export function adminAddCategory(data: { parentId: number; name: string; sort?: number }): Promise<void> {
  return post<void>('/admin/category', data)
}

export function adminUpdateCategory(id: number, data: { name: string; sort?: number; status?: number }): Promise<void> {
  return put<void>(`/admin/category/${id}`, data)
}

export function adminDeleteCategory(id: number): Promise<void> {
  return del<void>(`/admin/category/${id}`)
}

/* ---------- 商品维护 ---------- */

export function adminProductPage(params: PageQuery & { keyword?: string; status?: number }): Promise<PageData<Product>> {
  return get<PageData<Product>>('/admin/product/page', params as unknown as Record<string, unknown>)
}

export interface AdminProductPayload {
  categoryId: number
  name: string
  subTitle?: string
  mainPic?: string
  detail?: string
  price: number
  stock: number
}

export function adminAddProduct(data: AdminProductPayload): Promise<void> {
  return post<void>('/admin/product', data)
}

export function adminUpdateProduct(id: number, data: Partial<AdminProductPayload>): Promise<void> {
  return put<void>(`/admin/product/${id}`, data)
}

/** 上架/下架（status: 0 下架 1 上架），变更触发 ES 同步 */
export function adminUpdateProductStatus(id: number, status: number): Promise<void> {
  return put<void>(`/admin/product/status/${id}`, { status })
}

/* ---------- SKU 维护 ---------- */

export function adminAddSku(productId: number, data: { skuCode: string; specJson?: string; price: number; stock: number }): Promise<void> {
  return post<void>(`/admin/product/${productId}/sku`, data)
}

export function adminUpdateSku(id: number, data: Partial<Sku>): Promise<void> {
  return put<void>(`/admin/sku/${id}`, data)
}

export function adminDeleteSku(id: number): Promise<void> {
  return del<void>(`/admin/sku/${id}`)
}

/* ---------- 订单管理 ---------- */

export function adminOrderPage(params: AdminOrderQuery): Promise<PageData<Order>> {
  return get<PageData<Order>>('/admin/order/page', params as unknown as Record<string, unknown>)
}

/** 发货（待支付→不可发货；已支付→已发货） */
export function adminDeliverOrder(orderNo: string): Promise<void> {
  return put<void>(`/admin/order/deliver/${orderNo}`)
}

/* ---------- 秒杀活动管理 ---------- */

export interface AdminSeckillActivityPayload {
  name: string
  startTime: string
  endTime: string
  status?: number
}

export function adminAddSeckillActivity(data: AdminSeckillActivityPayload): Promise<void> {
  return post<void>('/admin/seckill/activity', data)
}

export function adminUpdateSeckillActivity(id: number, data: Partial<AdminSeckillActivityPayload>): Promise<void> {
  return put<void>(`/admin/seckill/activity/${id}`, data)
}

export interface AdminSeckillProductPayload {
  activityId: number
  productId: number
  skuId: number
  seckillPrice: number
  seckillStock: number
  limitPerUser?: number
}

export function adminAddSeckillProduct(data: AdminSeckillProductPayload): Promise<void> {
  return post<void>('/admin/seckill/product', data)
}

export function adminDeleteSeckillProduct(id: number): Promise<void> {
  return del<void>(`/admin/seckill/product/${id}`)
}

/* ---------- 管理端辅助 ---------- */

/** 管理端分类树（含禁用分类） */
export function adminCategoryTree(): Promise<Category[]> {
  return get<Category[]>('/admin/category/tree')
}
