import { get, post } from './request'
import type { LoginVO } from './types'

export interface LoginPayload {
  username: string
  password: string
}

export interface RegisterPayload {
  username: string
  password: string
  nickname?: string
}

/** 注册 */
export function register(data: RegisterPayload): Promise<void> {
  return post<void>('/user/register', data)
}

/** 登录（成功后由调用方负责写入 token） */
export function login(data: LoginPayload): Promise<LoginVO> {
  return post<LoginVO>('/user/login', data)
}

/** 登出（使当前 token 失效） */
export function logout(): Promise<void> {
  return post<void>('/user/logout')
}

/** 健康检查（联调用） */
export function health(): Promise<string> {
  return get<string>('/health')
}
