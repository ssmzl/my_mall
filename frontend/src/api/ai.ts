import { getToken } from './http'

/**
 * AI 客服 SSE 封装。
 * <p>后端接口为 POST /api/ai/chat/stream，EventSource 仅支持 GET，
 * 故使用 fetch + ReadableStream 手工解析 SSE 帧（event/data 行，空行分隔）。</p>
 */

/** SSE 各事件的回调集合 */
export interface AiStreamHandlers {
  /** 文本增量（message 事件） */
  onMessage: (chunk: string) => void
  /** 工具调用开始（tool_call 事件，data 为 {"name": "..."}） */
  onToolCall: (toolName: string) => void
  /** 工具执行结果（tool_result 事件，data 为工具结果 JSON 字符串） */
  onToolResult: (resultJson: string) => void
  /** 流正常结束（done 事件） */
  onDone: () => void
  /** 异常终止（error 事件或 HTTP 层错误，message 为用户可读文案） */
  onError: (message: string) => void
}

/* ---------- 工具结果数据结构（与后端 AiToolExecutor 返回 JSON 对齐） ---------- */

export interface AiProduct {
  id: number
  name: string
  subTitle?: string | null
  price: number
  mainPic?: string | null
}

export interface AiOrderSummary {
  orderNo: string
  status: number
  statusText: string
  totalAmount: number
  createTime: string
}

export interface AiOrderItem {
  productName: string
  skuSpec?: string | null
  price: number
  quantity: number
}

export interface AiOrderDetail extends AiOrderSummary {
  payTime?: string | null
  items: AiOrderItem[]
}

/** 工具结果的归类载荷：按 JSON 形状区分商品/订单列表/订单详情 */
export type AiToolPayload =
  | { kind: 'products'; total: number; products: AiProduct[] }
  | { kind: 'orders'; total: number; orders: AiOrderSummary[] }
  | { kind: 'orderDetail'; order: AiOrderDetail }

/**
 * 解析 tool_result 事件的 JSON 字符串，按字段形状归类为卡片载荷；
 * 无法识别（如 {"error": "..."}）返回 null，由调用方决定忽略或仅展示文本。
 */
export function parseToolResult(resultJson: string): AiToolPayload | null {
  let data: Record<string, unknown>
  try {
    data = JSON.parse(resultJson)
  } catch {
    return null
  }
  if (Array.isArray(data.products)) {
    return { kind: 'products', total: Number(data.total ?? 0), products: data.products as AiProduct[] }
  }
  if (Array.isArray(data.orders)) {
    return { kind: 'orders', total: Number(data.total ?? 0), orders: data.orders as AiOrderSummary[] }
  }
  if (data.order && typeof data.order === 'object') {
    return { kind: 'orderDetail', order: data.order as AiOrderDetail }
  }
  return null
}

/**
 * 发起 AI 聊天 SSE 请求并逐事件回调。
 * <p>未进入 SSE 流的错误（401 未登录 / 429 限流 / 参数校验失败）以 Result JSON 返回，
 * 统一转为 onError 回调；流内 error 事件同样转 onError。函数不抛异常。</p>
 *
 * @param message 用户消息
 * @param handlers 各 SSE 事件回调
 * @param signal 可选中止信号（关闭聊天窗时中断流）
 */
export async function createChatStream(
  message: string,
  handlers: AiStreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  let resp: Response
  try {
    resp = await fetch('/api/ai/chat/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${getToken()}`,
      },
      body: JSON.stringify({ message }),
      signal,
    })
  } catch (e) {
    if (signal?.aborted) {
      return // 主动关闭，不按错误处理
    }
    console.error('[AI] SSE 请求失败', e)
    handlers.onError('网络异常，请稍后重试')
    return
  }

  // 未进入 SSE 流：后端返回 Result JSON（401/429/校验失败等）
  const contentType = resp.headers.get('content-type') ?? ''
  if (!resp.ok || contentType.includes('application/json')) {
    let msg = resp.status === 401 ? '请先登录后再与客服对话' : `请求失败（HTTP ${resp.status}）`
    try {
      const result = (await resp.json()) as { code?: number; message?: string }
      if (result?.message) {
        msg = result.message
      }
    } catch {
      // 响应体非 JSON，保留默认文案
    }
    handlers.onError(msg)
    return
  }

  try {
    const reader = resp.body!.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) {
        break
      }
      buffer += decoder.decode(value, { stream: true })
      // SSE 事件以空行（\n\n）分隔，逐帧取出解析
      let sep: number
      while ((sep = buffer.indexOf('\n\n')) >= 0) {
        const frame = buffer.slice(0, sep)
        buffer = buffer.slice(sep + 2)
        handleFrame(frame, handlers)
      }
    }
  } catch (e) {
    if (signal?.aborted) {
      return
    }
    console.error('[AI] SSE 流读取异常', e)
    handlers.onError('连接中断，请稍后重试')
  }
}

/** 解析单个 SSE 帧（若干 event:/data: 行），分发到对应回调 */
function handleFrame(frame: string, handlers: AiStreamHandlers): void {
  let event = 'message'
  const dataLines: string[] = []
  for (const raw of frame.split('\n')) {
    const line = raw.endsWith('\r') ? raw.slice(0, -1) : raw
    if (line.startsWith('event:')) {
      event = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      // Spring SseEmitter data 前带一个空格，多行 data 按行还原
      dataLines.push(line.slice(5).replace(/^ /, ''))
    }
  }
  const data = dataLines.join('\n')
  switch (event) {
    case 'message':
      if (data) {
        handlers.onMessage(data)
      }
      break
    case 'tool_call':
      try {
        handlers.onToolCall(JSON.parse(data).name as string)
      } catch {
        // 名称解析失败不中断流，仅跳过占位提示
      }
      break
    case 'tool_result':
      handlers.onToolResult(data)
      break
    case 'done':
      handlers.onDone()
      break
    case 'error':
      handlers.onError(data || 'AI 服务异常，请稍后重试')
      break
    default:
      // 未知事件类型忽略
      break
  }
}
