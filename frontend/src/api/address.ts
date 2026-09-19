import { del, get, post, put } from './request'
import type { Address, AddressPayload } from './types'

/** 地址列表（默认地址置顶） */
export function listAddress(): Promise<Address[]> {
  return get<Address[]>('/address/list')
}

/** 新增地址（首个地址自动设为默认） */
export function addAddress(data: AddressPayload): Promise<void> {
  return post<void>('/address', data)
}

/** 修改地址 */
export function updateAddress(id: number, data: AddressPayload): Promise<void> {
  return put<void>(`/address/${id}`, data)
}

/** 删除地址 */
export function deleteAddress(id: number): Promise<void> {
  return del<void>(`/address/${id}`)
}

/** 设为默认地址 */
export function setDefaultAddress(id: number): Promise<void> {
  return put<void>(`/address/default/${id}`)
}
