import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'

// mock SSE 封装（由测试用例手动驱动回调），parseToolResult 保留真实实现
vi.mock('@/api/ai', async (importOriginal) => {
  const mod = await importOriginal<typeof import('@/api/ai')>()
  return { ...mod, createChatStream: vi.fn() }
})

import { createChatStream } from '@/api/ai'
import AiChatBot from '../AiChatBot.vue'

const mockedCreate = vi.mocked(createChatStream)

/** 挂载组件并打开聊天窗 */
async function mountOpenedBot() {
  const wrapper = mount(AiChatBot, {
    global: {
      plugins: [ElementPlus],
      components: {
        RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' },
      },
    },
  })
  await wrapper.find('.ai-fab').trigger('click')
  return wrapper
}

/** 让 mock 的 createChatStream 捕获回调集合 */
async function captureHandlers() {
  let handlers: Awaited<Parameters<typeof createChatStream>>[1] | undefined
  mockedCreate.mockImplementation(async (_message, h) => {
    handlers = h
  })
  return {
    get: () => {
      if (!handlers) throw new Error('createChatStream 尚未被调用')
      return handlers
    },
  }
}

describe('AiChatBot', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('默认隐藏聊天窗，点击浮动按钮后显示欢迎语', async () => {
    const wrapper = mount(AiChatBot, { global: { plugins: [ElementPlus] } })
    expect(wrapper.find('.ai-panel').exists()).toBe(false)

    await wrapper.find('.ai-fab').trigger('click')
    expect(wrapper.find('.ai-panel').exists()).toBe(true)
    expect(wrapper.find('.ai-empty').text()).toContain('智能客服')

    // 再点关闭
    await wrapper.find('.ai-fab').trigger('click')
    expect(wrapper.find('.ai-panel').exists()).toBe(false)
  })

  it('发送消息后展示用户气泡并携带消息与回调发起 SSE', async () => {
    const wrapper = await mountOpenedBot()
    const cap = await captureHandlers()

    const input = wrapper.find('textarea')
    await input.setValue('有什么手机')
    await input.trigger('keydown.enter')
    await flushPromises()

    expect(mockedCreate).toHaveBeenCalledTimes(1)
    expect(mockedCreate.mock.calls[0]![0]).toBe('有什么手机')
    expect(typeof mockedCreate.mock.calls[0]![1].onMessage).toBe('function')
    // 用户气泡展示、输入框清空
    const bubbles = wrapper.findAll('.ai-msg-user .ai-bubble')
    expect(bubbles[bubbles.length - 1]!.text()).toBe('有什么手机')
    expect((wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe('')
  })

  it('流式增量渲染 Markdown 且流结束后关闭打字光标', async () => {
    const wrapper = await mountOpenedBot()
    const cap = await captureHandlers()

    const input = wrapper.find('textarea')
    await input.setValue('你好')
    await input.trigger('keydown.enter')
    await flushPromises()

    cap.get().onMessage('**推荐**商品')
    await flushPromises()
    // Markdown 渲染（加粗）
    expect(wrapper.find('.ai-md strong').text()).toBe('推荐')

    cap.get().onDone()
    await flushPromises()
    expect(wrapper.find('.ai-cursor').exists()).toBe(false)
  })

  it('商品工具结果渲染商品卡片并可跳详情', async () => {
    const wrapper = await mountOpenedBot()
    const cap = await captureHandlers()

    const input = wrapper.find('textarea')
    await input.setValue('有什么手机')
    await input.trigger('keydown.enter')
    await flushPromises()

    cap.get().onToolResult(
      JSON.stringify({
        total: 2,
        products: [
          { id: 990001, name: '手机A', subTitle: '旗舰', price: 1999.0, mainPic: 'http://img/a.jpg' },
          { id: 990002, name: '手机B', subTitle: null, price: 999.0, mainPic: null },
        ],
      }),
    )
    await flushPromises()

    const cards = wrapper.findAll('.ai-product-card')
    expect(cards).toHaveLength(2)
    expect(cards[0]!.attributes('href')).toBe('/product/990001')
    expect(cards[0]!.text()).toContain('手机A')
    expect(cards[0]!.text()).toContain('￥1999')
  })

  it('订单工具结果渲染订单卡片，tool_call 期间显示查询占位', async () => {
    const wrapper = await mountOpenedBot()
    const cap = await captureHandlers()

    const input = wrapper.find('textarea')
    await input.setValue('我最近的订单')
    await input.trigger('keydown.enter')
    await flushPromises()

    // tool_call → 占位提示出现
    cap.get().onToolCall('queryUserOrders')
    await flushPromises()
    expect(wrapper.find('.ai-querying').text()).toContain('正在查询订单')

    // tool_result → 占位消失、订单卡片出现
    cap.get().onToolResult(
      JSON.stringify({
        total: 1,
        orders: [
          {
            orderNo: '20260901120000000001',
            status: 2,
            statusText: '已发货',
            totalAmount: 99.5,
            createTime: '2026-09-01 12:30',
          },
        ],
      }),
    )
    await flushPromises()
    expect(wrapper.find('.ai-querying').exists()).toBe(false)
    const card = wrapper.find('.ai-order-item')
    expect(card.exists()).toBe(true)
    expect(card.text()).toContain('20260901120000000001')
    expect(card.text()).toContain('已发货')
    expect(card.text()).toContain('￥99.5')
  })

  it('流内 error 事件以错误样式展示且不影响继续输入', async () => {
    const wrapper = await mountOpenedBot()
    const cap = await captureHandlers()

    const input = wrapper.find('textarea')
    await input.setValue('你好')
    await input.trigger('keydown.enter')
    await flushPromises()

    cap.get().onError('AI 服务异常，请稍后重试')
    await flushPromises()

    const bubble = wrapper.find('.ai-bubble-error')
    expect(bubble.exists()).toBe(true)
    expect(bubble.text()).toContain('AI 服务异常')
    // sending 复位：可以继续发送下一条
    await input.setValue('在吗')
    await input.trigger('keydown.enter')
    await flushPromises()
    expect(mockedCreate).toHaveBeenCalledTimes(2)
  })
})
