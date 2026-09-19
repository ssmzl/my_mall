import { get, post, put } from './request'
import type {
  AdminOrderQuery,
  CreateOrderPayload,
  CreateOrderVO,
  Order,
  PageData,
} from './types'

/** 获取下单防重 Token（一次性） */
export function getOrderToken(): Promise<string> {
  return get<string>('/order/token')
}

/** 下单 */
export function createOrder(data: CreateOrderPayload): Promise<CreateOrderVO> {
  return post<CreateOrderVO>('/order', data)
}

/** 模拟支付回调 */
export function payOrder(orderNo: string): Promise<void> {
  return post<void>(`/order/pay/${orderNo}`)
}

/** 主动取消订单 */
export function cancelOrder(orderNo: string): Promise<void> {
  return put<void>(`/order/cancel/${orderNo}`)
}

/** 我的订单分页列表 */
export function orderPage(params: AdminOrderQuery): Promise<PageData<Order>> {
  return get<PageData<Order>>('/order/page', params as unknown as Record<string, unknown>)
}

/** 订单详情 */
export function orderDetail(orderNo: string): Promise<Order> {
  return get<Order>(`/order/${orderNo}`)
}
