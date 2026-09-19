import { get } from './request'
import type { PageData, SearchProduct, SearchQuery } from './types'

/** 商品搜索（结果 name/subTitle 含 <em> 高亮标签，由页面 v-html 渲染） */
export function searchProducts(params: SearchQuery): Promise<PageData<SearchProduct>> {
  return get<PageData<SearchProduct>>('/search', params as unknown as Record<string, unknown>)
}
