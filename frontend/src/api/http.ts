import axios, { AxiosError, type AxiosInstance, type AxiosResponse } from 'axios'
import { ElMessage } from 'element-plus'
import type { ApiResult } from './types'

const TOKEN_KEY = 'mall_token'

export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) ?? ''
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY)
}

const http: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 15000,
})

// 请求拦截器：携带 token
http.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

let onUnauthorized: (() => void) | null = null

/** 注册 401 回调（由 router/main 注入，避免循环依赖） */
export function setUnauthorizedHandler(handler: () => void): void {
  onUnauthorized = handler
}

// 响应拦截器：统一处理 code != 200 与 401
http.interceptors.response.use(
  (response: AxiosResponse<ApiResult>) => {
    const res = response.data
    if (res && typeof res === 'object' && 'code' in res) {
      if (res.code === 200) {
        return res.data as never
      }
      if (res.code === 401) {
        handleUnauthorized(res.message)
        return Promise.reject(new Error(res.message || '未登录'))
      }
      ElMessage.error(res.message || '请求失败')
      return Promise.reject(new Error(res.message || '请求失败'))
    }
    return response.data as never
  },
  (error: AxiosError<ApiResult>) => {
    if (error.response?.status === 401 || error.response?.data?.code === 401) {
      handleUnauthorized(error.response?.data?.message)
    } else {
      ElMessage.error(error.response?.data?.message || error.message || '网络异常')
    }
    return Promise.reject(error)
  },
)

function handleUnauthorized(message?: string): void {
  clearToken()
  if (message) {
    ElMessage.error(message)
  }
  onUnauthorized?.()
}

export default http
