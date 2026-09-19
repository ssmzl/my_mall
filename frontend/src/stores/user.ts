import { defineStore } from 'pinia'
import { clearToken, getToken, setToken } from '@/api/http'
import { login as loginApi, logout as logoutApi } from '@/api/user'
import type { LoginVO } from '@/api/types'

/** 本地持久化的用户信息（token 在 api/http 中管理） */
const USER_KEY = 'mall_user'

interface StoredUser {
  userId: number
  username: string
  nickname: string | null
  role: number
}

function loadStoredUser(): StoredUser | null {
  try {
    const raw = localStorage.getItem(USER_KEY)
    return raw ? (JSON.parse(raw) as StoredUser) : null
  } catch {
    return null
  }
}

export const useUserStore = defineStore('user', {
  state: () => ({
    token: getToken(),
    userId: 0,
    username: '',
    nickname: '' as string | null,
    role: 0,
  }),
  getters: {
    isLoggedIn: (state) => state.token !== '',
    isAdmin: (state) => state.role === 1,
    displayName: (state) => state.nickname || state.username,
  },
  actions: {
    /** 登录：保存 token 与用户信息 */
    async login(username: string, password: string): Promise<void> {
      const vo: LoginVO = await loginApi({ username, password })
      this.applyLogin(vo)
    },
    /** 将登录响应写入 store 与本地存储（供单测直接复用） */
    applyLogin(vo: LoginVO): void {
      this.token = vo.token
      this.userId = vo.userId
      this.username = vo.username
      this.nickname = vo.nickname
      this.role = vo.role
      setToken(vo.token)
      localStorage.setItem(
        USER_KEY,
        JSON.stringify({ userId: vo.userId, username: vo.username, nickname: vo.nickname, role: vo.role }),
      )
    },
    /** 从本地存储恢复用户信息（页面刷新场景） */
    restore(): void {
      if (!this.token) return
      const user = loadStoredUser()
      if (user) {
        this.userId = user.userId
        this.username = user.username
        this.nickname = user.nickname
        this.role = user.role
      }
    },
    /** 登出：先调接口使 token 失效，再清理本地状态（接口失败也必须清理，输出诊断信息） */
    async logout(): Promise<void> {
      try {
        if (this.token) {
          await logoutApi()
        }
      } catch (error) {
        console.error('[userStore] 登出接口调用失败，仍将清理本地登录态:', error)
      } finally {
        this.reset()
      }
    },
    /** 清空本地登录态（401 时也会走到这里） */
    reset(): void {
      this.token = ''
      this.userId = 0
      this.username = ''
      this.nickname = null
      this.role = 0
      clearToken()
      localStorage.removeItem(USER_KEY)
    },
  },
})
