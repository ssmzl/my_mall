import { get, post } from './request'
import type { SeckillActivity, SeckillOrderVO, SeckillResultVO } from './types'

/** 秒杀活动列表（含活动商品，含实时剩余库存） */
export function seckillActivities(): Promise<SeckillActivity[]> {
  return get<SeckillActivity[]>('/seckill/activities')
}

/** 秒杀下单（异步，返回 taskId 供轮询） */
export function seckillOrder(seckillProductId: number): Promise<SeckillOrderVO> {
  return post<SeckillOrderVO>(`/seckill/${seckillProductId}`)
}

/** 查询秒杀下单结果（前端轮询，QUEUED 继续轮询） */
export function seckillResult(taskId: string): Promise<SeckillResultVO> {
  return get<SeckillResultVO>('/seckill/result', { taskId })
}
