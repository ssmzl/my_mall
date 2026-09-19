/** 统一响应结构 */
export interface ApiResult<T = unknown> {
  code: number
  message: string
  data: T
}

/** 分页响应 data */
export interface PageData<T> {
  records: T[]
  total: number
  current: number
  size: number
}

/** 分页请求参数 */
export interface PageQuery {
  pageNum?: number
  pageSize?: number
}
