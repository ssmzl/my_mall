import { get } from './request'
import type { Category, PageData, PageQuery, Product, ProductDetail } from './types'

/** 分类树 */
export function categoryTree(): Promise<Category[]> {
  return get<Category[]>('/product/category/tree')
}

/** 商品分页列表 */
export function productPage(params: PageQuery & { categoryId?: number; keyword?: string }): Promise<PageData<Product>> {
  return get<PageData<Product>>('/product/page', params)
}

/** 商品详情（含 SKU 列表） */
export function productDetail(id: number): Promise<ProductDetail> {
  return get<ProductDetail>(`/product/${id}`)
}
