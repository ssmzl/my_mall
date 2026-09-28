<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { ChatDotRound, Close, Position, Loading } from '@element-plus/icons-vue'
import { createChatStream, parseToolResult } from '@/api/ai'
import type { AiOrderDetail, AiOrderSummary, AiProduct } from '@/api/ai'

/**
 * 全局 AI 客服组件：右下角浮动按钮 + 抽屉式聊天窗。
 * <p>经 App.vue 全局挂载，所有页面可唤起；SSE 流式输出由 api/ai.ts 封装。</p>
 */

/** 聊天消息（AI 消息可携带工具结果卡片） */
interface ChatMsg {
  id: number
  role: 'user' | 'assistant'
  content: string
  /** 工具结果卡片（随 tool_result 事件追加） */
  cards: ToolCard[]
  /** 流式输出中：显示打字光标 */
  typing: boolean
  /** 出错的消息：内容以错误样式展示 */
  failed: boolean
}

type ToolCard =
  | { type: 'products'; products: AiProduct[] }
  | { type: 'orders'; orders: AiOrderSummary[] }
  | { type: 'orderDetail'; order: AiOrderDetail }

/** 工具名 → 占位提示文案 */
const TOOL_LABELS: Record<string, string> = {
  queryProduct: '正在查询商品…',
  queryUserOrders: '正在查询订单…',
  queryOrderDetail: '正在查询订单详情…',
}

let msgSeq = 0
function nextMsgId(): number {
  return ++msgSeq
}

const visible = ref(false)
const input = ref('')
const sending = ref(false)
/** 正在执行的工具占位文案（tool_call → tool_result 之间显示） */
const queryingLabel = ref('')
const messages = ref<ChatMsg[]>([])
const listRef = ref<HTMLElement>()
let abortController: AbortController | null = null

/** 切换聊天窗；关闭时中断进行中的流 */
function toggle(): void {
  visible.value = !visible.value
  if (!visible.value && abortController) {
    abortController.abort()
  }
}

/** Markdown 渲染（marked 转 HTML 后经 DOMPurify 消毒，防 LLM 回显注入 XSS） */
function renderMarkdown(text: string): string {
  const html = marked.parse(text, { async: false, breaks: true, gfm: true })
  return DOMPurify.sanitize(html)
}

/** 订单状态 → el-tag 类型 */
function statusTagType(status: number): 'warning' | 'primary' | 'success' | 'info' {
  if (status === 0) return 'warning'
  if (status === 1 || status === 3) return 'success'
  if (status === 2) return 'primary'
  return 'info'
}

/** 发送消息：追加 user/AI 气泡后发起 SSE 流 */
async function send(): Promise<void> {
  const message = input.value.trim()
  if (!message || sending.value) {
    return
  }
  input.value = ''
  sending.value = true
  messages.value.push({ id: nextMsgId(), role: 'user', content: message, cards: [], typing: false, failed: false })
  messages.value.push({ id: nextMsgId(), role: 'assistant', content: '', cards: [], typing: true, failed: false })
  // 从响应式数组取回代理引用：SSE 回调里直接改 push 前的原始对象不会触发渲染
  const aiMsg = messages.value[messages.value.length - 1]!
  scrollToBottom()

  abortController = new AbortController()
  try {
    await createChatStream(
      message,
      {
        onMessage: (chunk) => {
          aiMsg.content += chunk
        },
        onToolCall: (toolName) => {
          queryingLabel.value = TOOL_LABELS[toolName] ?? '正在查询…'
        },
        onToolResult: (resultJson) => {
          queryingLabel.value = ''
          const payload = parseToolResult(resultJson)
          if (payload) {
            if (payload.kind === 'products') {
              aiMsg.cards.push({ type: 'products', products: payload.products })
            } else if (payload.kind === 'orders') {
              aiMsg.cards.push({ type: 'orders', orders: payload.orders })
            } else {
              aiMsg.cards.push({ type: 'orderDetail', order: payload.order })
            }
          }
        },
        onDone: () => {
          aiMsg.typing = false
        },
        onError: (msg) => {
          aiMsg.typing = false
          queryingLabel.value = ''
          // 流中已有内容则追加提示，否则整条按错误展示
          if (aiMsg.content) {
            aiMsg.content += `\n\n> ${msg}`
          } else {
            aiMsg.content = msg
            aiMsg.failed = true
          }
        },
      },
      abortController.signal,
    )
  } finally {
    aiMsg.typing = false
    sending.value = false
    queryingLabel.value = ''
    abortController = null
  }
}

/** 输入框 Enter 发送（Shift+Enter 换行由 textarea 默认行为提供） */
function onEnterKey(e: KeyboardEvent): void {
  if (!e.shiftKey) {
    e.preventDefault()
    void send()
  }
}

function scrollToBottom(): void {
  void nextTick(() => {
    const el = listRef.value
    if (el) {
      el.scrollTop = el.scrollHeight
    }
  })
}

// 消息内容/卡片/占位变化时自动滚到底部
watch(() => [messages.value.map((m) => [m.content, m.cards.length]), queryingLabel.value], scrollToBottom, { deep: true })
</script>

<template>
  <!-- 全局浮动按钮（右下角，品牌色） -->
  <button class="ai-fab" type="button" aria-label="AI 客服" @click="toggle">
    <el-icon :size="26"><component :is="visible ? Close : ChatDotRound" /></el-icon>
  </button>

  <!-- 抽屉式聊天窗 -->
  <transition name="ai-panel">
    <div v-if="visible" class="ai-panel">
      <div class="ai-panel-header">
        <span class="ai-panel-title">AI 客服</span>
        <span class="ai-panel-sub">智能查询商品与订单</span>
      </div>

      <div ref="listRef" class="ai-msg-list">
        <div v-if="messages.length === 0" class="ai-empty">
          <p>你好，我是 my_mall 智能客服 👋</p>
          <p>可以问我「有什么手机」「我最近的订单」等问题</p>
        </div>

        <div
          v-for="msg in messages"
          :key="msg.id"
          class="ai-msg"
          :class="msg.role === 'user' ? 'ai-msg-user' : 'ai-msg-assistant'"
        >
          <div class="ai-bubble" :class="{ 'ai-bubble-error': msg.failed }">
            <!-- AI 消息 Markdown 渲染；用户消息纯文本 -->
            <template v-if="msg.role === 'assistant'">
              <div class="ai-md" v-html="renderMarkdown(msg.content)"></div>
              <!-- 工具结果卡片 -->
              <div v-for="(card, ci) in msg.cards" :key="ci" class="ai-cards">
                <!-- 商品卡片 -->
                <RouterLink
                  v-for="p in card.type === 'products' ? card.products : []"
                  :key="p.id"
                  :to="`/product/${p.id}`"
                  class="ai-product-card"
                >
                  <el-image class="ai-product-pic" :src="p.mainPic ?? ''" fit="cover">
                    <template #error>
                      <div class="ai-pic-fallback">无图</div>
                    </template>
                  </el-image>
                  <div class="ai-product-info">
                    <div class="ai-product-name">{{ p.name }}</div>
                    <div class="ai-product-sub">{{ p.subTitle }}</div>
                    <div class="ai-product-price">￥{{ p.price }}</div>
                  </div>
                </RouterLink>

                <!-- 订单列表卡片 -->
                <div v-if="card.type === 'orders'" class="ai-order-list">
                  <div v-for="o in card.orders" :key="o.orderNo" class="ai-order-item">
                    <div class="ai-order-row">
                      <span class="ai-order-no">订单 {{ o.orderNo }}</span>
                      <el-tag :type="statusTagType(o.status)" size="small">{{ o.statusText }}</el-tag>
                    </div>
                    <div class="ai-order-row ai-order-meta">
                      <span>￥{{ o.totalAmount }}</span>
                      <span>{{ o.createTime }}</span>
                    </div>
                  </div>
                </div>

                <!-- 订单详情卡片 -->
                <div v-if="card.type === 'orderDetail'" class="ai-order-item">
                  <div class="ai-order-row">
                    <span class="ai-order-no">订单 {{ card.order.orderNo }}</span>
                    <el-tag :type="statusTagType(card.order.status)" size="small">{{ card.order.statusText }}</el-tag>
                  </div>
                  <div
                    v-for="(it, ii) in card.order.items"
                    :key="ii"
                    class="ai-order-row ai-order-item-line"
                  >
                    <span>{{ it.productName }}<template v-if="it.skuSpec">（{{ it.skuSpec }}）</template> ×{{ it.quantity }}</span>
                    <span>￥{{ it.price }}</span>
                  </div>
                  <div class="ai-order-row ai-order-meta">
                    <span>合计 ￥{{ card.order.totalAmount }}</span>
                    <span>下单 {{ card.order.createTime }}</span>
                  </div>
                </div>
              </div>
              <!-- 流式输出中的打字光标 -->
              <span v-if="msg.typing" class="ai-cursor"></span>
            </template>
            <template v-else>{{ msg.content }}</template>
          </div>
        </div>

        <!-- 工具查询占位 -->
        <div v-if="queryingLabel" class="ai-msg ai-msg-assistant">
          <div class="ai-bubble ai-querying">
            <el-icon class="is-loading"><Loading /></el-icon>
            <span>{{ queryingLabel }}</span>
          </div>
        </div>
      </div>

      <div class="ai-input-area">
        <el-input
          v-model="input"
          type="textarea"
          :rows="2"
          resize="none"
          placeholder="输入消息，Enter 发送"
          :disabled="sending"
          @keydown.enter="onEnterKey"
        />
        <el-button type="primary" :loading="sending" :disabled="!input.trim()" @click="send">
          发送
        </el-button>
      </div>
    </div>
  </transition>
</template>

<style scoped>
/* 浮动按钮：固定右下角，品牌色背景 */
.ai-fab {
  position: fixed;
  right: 28px;
  bottom: 32px;
  z-index: 2000;
  width: 56px;
  height: 56px;
  border: none;
  border-radius: 50%;
  background-color: var(--mall-brand);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  box-shadow: 0 4px 16px rgba(233, 59, 61, 0.4);
  transition: transform 0.2s ease, background-color 0.2s ease;
}

.ai-fab:hover {
  background-color: var(--mall-brand-dark);
  transform: scale(1.06);
}

/* 聊天窗：右下角抽屉式面板 */
.ai-panel {
  position: fixed;
  right: 28px;
  bottom: 100px;
  z-index: 2000;
  width: 400px;
  height: 70vh;
  min-width: 320px;
  min-height: 360px;
  max-width: calc(100vw - 40px);
  max-height: 92vh;
  display: flex;
  flex-direction: column;
  background-color: #fff;
  border-radius: 12px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.16);
  /* 允许拖拽右下角调整聊天窗大小 */
  resize: both;
  overflow: hidden;
}

.ai-panel-header {
  padding: 14px 16px;
  background-color: var(--mall-brand);
  color: #fff;
}

.ai-panel-title {
  font-size: 16px;
  font-weight: 600;
}

.ai-panel-sub {
  margin-left: 10px;
  font-size: 12px;
  opacity: 0.85;
}

/* 消息列表 */
.ai-msg-list {
  flex: 1;
  overflow-y: auto;
  padding: 14px;
  background-color: var(--mall-bg);
}

.ai-empty {
  text-align: center;
  color: var(--mall-text-secondary);
  font-size: 13px;
  line-height: 1.8;
  margin-top: 40px;
}

.ai-msg {
  display: flex;
  margin-bottom: 12px;
}

.ai-msg-user {
  justify-content: flex-end;
}

.ai-msg-assistant {
  justify-content: flex-start;
}

.ai-bubble {
  max-width: 85%;
  padding: 8px 12px;
  border-radius: 10px;
  font-size: 14px;
  line-height: 1.6;
  word-break: break-word;
}

/* 用户消息：右对齐灰色气泡 */
.ai-msg-user .ai-bubble {
  background-color: #e8eaed;
  color: var(--mall-text);
  white-space: pre-wrap;
}

/* AI 消息：左对齐白色气泡 */
.ai-msg-assistant .ai-bubble {
  background-color: #fff;
  color: var(--mall-text);
  border: 1px solid var(--mall-border);
}

.ai-bubble-error {
  color: var(--mall-brand-dark);
  background-color: var(--mall-brand-light);
  border-color: #fbc4c4 !important;
}

/* Markdown 渲染基础排版 */
.ai-md :deep(p) {
  margin: 0 0 6px;
}

.ai-md :deep(p:last-child) {
  margin-bottom: 0;
}

.ai-md :deep(ul),
.ai-md :deep(ol) {
  margin: 4px 0;
  padding-left: 20px;
}

.ai-md :deep(code) {
  background-color: #f4f4f5;
  padding: 1px 5px;
  border-radius: 4px;
  font-size: 13px;
}

.ai-md :deep(blockquote) {
  margin: 6px 0;
  padding: 2px 10px;
  border-left: 3px solid var(--mall-brand);
  color: var(--mall-text-secondary);
}

/* 打字光标闪烁动画 */
.ai-cursor {
  display: inline-block;
  width: 2px;
  height: 14px;
  margin-left: 2px;
  vertical-align: -2px;
  background-color: var(--mall-brand);
  animation: ai-blink 0.9s step-end infinite;
}

@keyframes ai-blink {
  50% {
    opacity: 0;
  }
}

/* 工具查询占位 */
.ai-querying {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--mall-text-secondary);
}

/* 商品卡片 */
.ai-cards {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.ai-product-card {
  display: flex;
  gap: 10px;
  padding: 8px;
  border: 1px solid var(--mall-border);
  border-radius: 8px;
  transition: border-color 0.2s ease;
}

.ai-product-card:hover {
  border-color: var(--mall-brand);
}

.ai-product-pic {
  width: 64px;
  height: 64px;
  flex-shrink: 0;
  border-radius: 6px;
  background-color: #f7f8fa;
}

.ai-pic-fallback {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: var(--mall-text-secondary);
}

.ai-product-info {
  min-width: 0;
}

.ai-product-name {
  font-size: 13px;
  color: var(--mall-text);
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
}

.ai-product-sub {
  font-size: 12px;
  color: var(--mall-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-product-price {
  margin-top: 2px;
  font-size: 15px;
  font-weight: 600;
  color: var(--mall-brand);
}

/* 订单卡片 */
.ai-order-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.ai-order-item {
  padding: 8px 10px;
  border: 1px solid var(--mall-border);
  border-radius: 8px;
}

.ai-order-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.ai-order-row + .ai-order-row {
  margin-top: 6px;
}

.ai-order-no {
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-order-item-line {
  color: var(--mall-text-regular);
}

.ai-order-meta {
  font-size: 12px;
  color: var(--mall-text-secondary);
}

/* 输入区 */
.ai-input-area {
  display: flex;
  gap: 8px;
  padding: 10px;
  border-top: 1px solid var(--mall-border);
  background-color: #fff;
}

.ai-input-area :deep(.el-button) {
  align-self: flex-end;
  background-color: var(--mall-brand);
  border-color: var(--mall-brand);
}

.ai-input-area :deep(.el-button:hover) {
  background-color: var(--mall-brand-dark);
  border-color: var(--mall-brand-dark);
}

/* 面板出入场动画 */
.ai-panel-enter-active,
.ai-panel-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}

.ai-panel-enter-from,
.ai-panel-leave-to {
  opacity: 0;
  transform: translateY(16px);
}

/* 移动端适配：聊天窗占满宽度 */
@media (max-width: 768px) {
  .ai-panel {
    right: 0;
    bottom: 0;
    width: 100vw;
    height: 80vh;
    min-width: 0;
    max-width: none;
    border-radius: 12px 12px 0 0;
    /* 移动端全屏展示，禁用拖拽调整 */
    resize: none;
  }

  .ai-fab {
    right: 16px;
    bottom: 20px;
  }
}
</style>
