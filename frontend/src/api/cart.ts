import { del, get, post, put } from './request'
import type { CartVO } from './types'

/** 查询购物车 */
export function getCart(): Promise<CartVO> {
  return get<CartVO>('/cart')
}

/** 加入购物车 */
export function addToCart(skuId: number, quantity: number): Promise<void> {
  return post<void>('/cart', { skuId, quantity })
}

/** 修改数量 */
export function updateQuantity(skuId: number, quantity: number): Promise<void> {
  return put<void>('/cart/quantity', { skuId, quantity })
}

/** 勾选/取消勾选单个条目 */
export function updateSelected(skuId: number, selected: boolean): Promise<void> {
  return put<void>('/cart/selected', { skuId, selected })
}

/** 全选/取消全选 */
export function updateSelectedAll(selected: boolean): Promise<void> {
  return put<void>('/cart/selectedAll', { selected })
}

/** 删除购物车条目 */
export function removeCartItem(skuId: number): Promise<void> {
  return del<void>(`/cart/${skuId}`)
}
