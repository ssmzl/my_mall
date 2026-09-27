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

/* ==================== 用户模块 ==================== */

/** 登录响应 */
export interface LoginVO {
  token: string
  userId: number
  username: string
  nickname: string | null
  role: number // 0 普通用户 1 管理员
}

/** 收货地址 */
export interface Address {
  id: number
  userId: number
  receiverName: string
  receiverPhone: string
  province: string
  city: string
  district: string
  detailAddress: string
  isDefault: number // 0 否 1 是
  createTime: string
  updateTime: string
}

/** 新增/修改地址参数 */
export interface AddressPayload {
  receiverName: string
  receiverPhone: string
  province: string
  city: string
  district: string
  detailAddress: string
  isDefault?: boolean
}

/* ==================== 商品模块 ==================== */

/** 分类（树形；status: 0 禁用 1 启用，管理端树接口含禁用分类） */
export interface Category {
  id: number
  parentId: number
  name: string
  level: number
  sort: number
  status: number
  children?: Category[]
}

/** 商品（列表项） */
export interface Product {
  id: number
  categoryId: number
  name: string
  subTitle: string | null
  mainPic: string | null
  price: number
  stock: number
  sales: number
  status: number // 0 下架 1 上架
  createTime: string
}

/** SKU */
export interface Sku {
  id: number
  productId: number
  skuCode: string
  specJson: string | null // JSON 字符串，如 {"颜色":"黑色","存储":"256GB"}
  price: number
  stock: number
}

/** 商品详情（含 SKU 列表） */
export interface ProductDetail extends Product {
  detail: string | null
  skus: Sku[]
}

/* ==================== 搜索模块 ==================== */

/** 搜索结果商品（ES 文档，name/subTitle 可能含 <em> 高亮标签） */
export interface SearchProduct {
  id: number
  categoryId: number
  name: string
  subTitle: string | null
  mainPic: string | null
  price: number
  sales: number
}

/** 搜索参数 */
export interface SearchQuery extends PageQuery {
  keyword: string
  categoryId?: number
  minPrice?: number
  maxPrice?: number
  /** 综合排序 default / 销量 sales / 价格升序 priceAsc / 价格降序 priceDesc */
  sort?: 'default' | 'sales' | 'priceAsc' | 'priceDesc'
}

/* ==================== 购物车模块 ==================== */

/** 购物车条目 */
export interface CartItem {
  skuId: number
  productId: number
  productName: string
  skuSpec: string | null
  mainPic: string | null
  price: number
  quantity: number
  selected: boolean
  subtotal: number
}

/** 购物车（Redis Hash 聚合视图） */
export interface CartVO {
  items: CartItem[]
  totalCount: number
  selectedCount: number
  selectedAmount: number
}

/* ==================== 订单模块 ==================== */

/** 订单状态：0 待支付 1 已支付 2 已发货 3 已完成 4 已取消 */
export type OrderStatus = 0 | 1 | 2 | 3 | 4

/** 订单明细 */
export interface OrderItem {
  id: number
  orderId: number
  productId: number
  skuId: number
  productName: string
  skuSpec: string | null
  price: number
  quantity: number
}

/** 订单 */
export interface Order {
  id: number
  orderNo: string
  userId: number
  totalAmount: number
  status: OrderStatus
  addressSnapshot: string | null // JSON 字符串
  payTime: string | null
  createTime: string
  items: OrderItem[]
}

/** 下单请求 */
export interface CreateOrderPayload {
  /** 防重 Token（GET /order/token 获取，一次性） */
  token: string
  addressId: number
  items: { skuId: number; quantity: number }[]
}

/** 下单响应 */
export interface CreateOrderVO {
  orderId: number
  orderNo: string
  payAmount: number
}

/* ==================== 秒杀模块 ==================== */

/** 秒杀商品 */
export interface SeckillProduct {
  id: number
  activityId: number
  productId: number
  skuId: number
  productName: string
  mainPic: string | null
  seckillPrice: number
  originalPrice: number
  seckillStock: number
  limitPerUser: number
}

/** 秒杀活动（含活动商品） */
export interface SeckillActivity {
  id: number
  name: string
  startTime: string
  endTime: string
  status: number // 0 下线 1 上线
  products: SeckillProduct[]
}

/** 秒杀下单响应（异步，前端凭 taskId 轮询结果） */
export interface SeckillOrderVO {
  taskId: string
}

/** 秒杀结果 */
export interface SeckillResultVO {
  /** QUEUED 排队中 / SUCCESS 成功 / FAIL 失败 */
  status: 'QUEUED' | 'SUCCESS' | 'FAIL'
  message: string
  orderNo: string | null
}

/* ==================== 后台管理模块 ==================== */

/** 订单状态文案 */
export const ORDER_STATUS_TEXT: Record<OrderStatus, string> = {
  0: '待支付',
  1: '已支付',
  2: '已发货',
  3: '已完成',
  4: '已取消',
}

/** 管理端订单查询参数 */
export interface AdminOrderQuery extends PageQuery {
  orderNo?: string
  status?: OrderStatus
}

/** 管理端数据看板汇总（销售口径：status IN (1,2,3) 且按 pay_time；AI 轮数：user 消息数；token：prompt+completion 求和） */
export interface AdminStatsSummary {
  todaySales: number
  todayAmount: number
  totalSales: number
  totalAmount: number
  todayAiRounds: number
  totalAiRounds: number
  todayAiTokens: number
  totalAiTokens: number
}

/** Top10 商品销量榜行（growth 为 null 表示上期销量为 0，前端显示 NEW） */
export interface AdminTopProduct {
  productId: number
  productName: string
  sales: number
  amount: number
  growth: number | null
}

/** 管理端 AI 配置（api-key 脱敏：仅返回 hasApiKey 标志，不返回明文） */
export interface AdminAiConfig {
  enabled: boolean
  baseUrl: string
  hasApiKey: boolean
  model: string
  temperature: number
  maxRounds: number
  ttlMinutes: number
  rateLimitPerMinute: number
  updateTime: string | null
}

/** 修改 AI 配置参数（apiKey 留空/不传表示保留原值） */
export interface AdminAiConfigPayload {
  enabled: boolean
  baseUrl: string
  apiKey?: string
  model: string
  temperature: number
  maxRounds: number
  ttlMinutes: number
  rateLimitPerMinute: number
}
