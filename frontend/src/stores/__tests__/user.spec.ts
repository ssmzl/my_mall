import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

// mock 底层 http（token 存取）与用户接口，store 逻辑单独验证
vi.mock('@/api/http', () => ({
  getToken: vi.fn(() => ''),
  setToken: vi.fn(),
  clearToken: vi.fn(),
}))
vi.mock('@/api/user', () => ({
  login: vi.fn(),
  logout: vi.fn(),
}))

import { getToken, setToken, clearToken } from '@/api/http'
import { login as loginApi, logout as logoutApi } from '@/api/user'
import { useUserStore } from '../user'

const mockedGetToken = vi.mocked(getToken)
const mockedLogin = vi.mocked(loginApi)
const mockedLogout = vi.mocked(logoutApi)

const fakeLoginVO = {
  token: 'test-token',
  userId: 1001,
  username: 'user',
  nickname: '测试用户',
  role: 0,
}

describe('useUserStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    vi.clearAllMocks()
    mockedGetToken.mockReturnValue('')
  })

  it('登录成功后保存 token 与用户信息', async () => {
    mockedLogin.mockResolvedValue(fakeLoginVO)
    const store = useUserStore()

    await store.login('user', '123456')

    expect(mockedLogin).toHaveBeenCalledWith({ username: 'user', password: '123456' })
    expect(store.token).toBe('test-token')
    expect(store.userId).toBe(1001)
    expect(store.username).toBe('user')
    expect(store.role).toBe(0)
    expect(setToken).toHaveBeenCalledWith('test-token')
    expect(store.isLoggedIn).toBe(true)
    expect(store.isAdmin).toBe(false)
    expect(store.displayName).toBe('测试用户')
    // 用户信息持久化到 localStorage（刷新后 restore 用）
    expect(JSON.parse(localStorage.getItem('mall_user') ?? '{}')).toMatchObject({
      userId: 1001,
      username: 'user',
    })
  })

  it('管理员登录后 isAdmin 为 true', async () => {
    mockedLogin.mockResolvedValue({ ...fakeLoginVO, username: 'admin', role: 1, nickname: null })
    const store = useUserStore()

    await store.login('admin', '123456')

    expect(store.isAdmin).toBe(true)
    // nickname 为空时展示用户名
    expect(store.displayName).toBe('admin')
  })

  it('restore 从 localStorage 恢复用户信息', () => {
    mockedGetToken.mockReturnValue('persisted-token')
    localStorage.setItem(
      'mall_user',
      JSON.stringify({ userId: 1, username: 'admin', nickname: null, role: 1 }),
    )
    const store = useUserStore()

    store.restore()

    expect(store.userId).toBe(1)
    expect(store.username).toBe('admin')
    expect(store.role).toBe(1)
    expect(store.isAdmin).toBe(true)
  })

  it('无 token 时 restore 不恢复', () => {
    localStorage.setItem(
      'mall_user',
      JSON.stringify({ userId: 1, username: 'admin', nickname: null, role: 1 }),
    )
    const store = useUserStore()

    store.restore()

    expect(store.username).toBe('')
    expect(store.userId).toBe(0)
  })

  it('登出调用接口并清理本地状态', async () => {
    mockedLogin.mockResolvedValue(fakeLoginVO)
    mockedLogout.mockResolvedValue(undefined)
    const store = useUserStore()
    await store.login('user', '123456')

    await store.logout()

    expect(mockedLogout).toHaveBeenCalledTimes(1)
    expect(store.token).toBe('')
    expect(store.userId).toBe(0)
    expect(store.isLoggedIn).toBe(false)
    expect(clearToken).toHaveBeenCalled()
    expect(localStorage.getItem('mall_user')).toBeNull()
  })

  it('登出接口失败也必须清理本地状态', async () => {
    mockedLogin.mockResolvedValue(fakeLoginVO)
    mockedLogout.mockRejectedValue(new Error('network error'))
    const store = useUserStore()
    await store.login('user', '123456')

    await expect(store.logout()).resolves.toBeUndefined()

    expect(store.token).toBe('')
    expect(store.isLoggedIn).toBe(false)
    expect(localStorage.getItem('mall_user')).toBeNull()
  })
})
