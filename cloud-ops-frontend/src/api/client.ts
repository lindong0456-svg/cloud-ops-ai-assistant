/**
 * 统一鉴权 & API 客户端
 *
 * 职责：
 *   1. Token 存取（localStorage）
 *   2. 用户信息存取（userId / username / tenantId / deptId / roles / permissions）
 *   3. apiFetch — 自动带 Authorization header，401 自动跳登录
 *   4. login / logout — 登录登出封装
 *   5. getSseUrl — 给 EventSource 拼 URL（SSE 不能设 header，走 ?token=xxx 旁路）
 */

// ========== 类型定义 ==========

export interface PermissionInfo {
  code: string    // "alarm:read"
  name: string    // "告警查看"
}

export interface UserInfo {
  userId: string
  username: string
  tenantId: string
  deptId: string
  roles: string[]
  permissions: PermissionInfo[]   // 后端返回含中文名的权限列表
}

interface LoginResponse extends UserInfo {
  token: string
}

// ========== 存储常量 ==========

const TOKEN_KEY = 'cloud-ops-token'
const USER_KEY = 'cloud-ops-user'

// ========== Token 存取 ==========

export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function isLoggedIn(): boolean {
  return !!getToken()
}

// ========== 用户信息存取 ==========

export function getUser(): UserInfo | null {
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as UserInfo
  } catch {
    return null
  }
}

function setUser(user: UserInfo): void {
  localStorage.setItem(USER_KEY, JSON.stringify(user))
}

// ========== 登录 / 登出 ==========

export async function login(username: string, password: string): Promise<UserInfo> {
  const res = await fetch('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })

  if (!res.ok) {
    const body = await res.json().catch(() => ({}))
    throw new Error(body.error || `登录失败 (HTTP ${res.status})`)
  }

  const data: LoginResponse = await res.json()
  // 存 token
  localStorage.setItem(TOKEN_KEY, data.token)
  // 存用户信息（去掉 token 字段）
  // permissions 已是 PermissionInfo[] 格式（含 code + name）
  const userInfo: UserInfo = {
    userId: data.userId,
    username: data.username,
    tenantId: data.tenantId,
    deptId: data.deptId,
    roles: data.roles || [],
    permissions: data.permissions || [],
  }
  setUser(userInfo)
  return userInfo
}

export function logout(): void {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
  // 通知全局：App.vue 监听此事件，弹出登录框
  window.dispatchEvent(new CustomEvent('auth:logout'))
}

// ========== 统一 fetch 封装 ==========

/**
 * 自动带 Authorization header 的 fetch
 * - 401 时自动 clearToken + 派发 auth:logout 事件
 * - 其余行为与原生 fetch 一致
 */
export async function apiFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const token = getToken()
  const headers: Record<string, string> = {
    ...((init.headers as Record<string, string>) || {}),
  }
  if (!headers['Content-Type'] && init.body) {
    headers['Content-Type'] = 'application/json'
  }
  if (token) {
    headers['Authorization'] = `Bearer ${token}`
  }

  const res = await fetch(path, { ...init, headers })

  if (res.status === 401) {
    logout()
    throw new Error('未登录或 Token 已过期')
  }
  return res
}

/**
 * 给 SSE EventSource 拼 URL — 追加 &token=xxx
 * （EventSource 浏览器 API 不支持设 header，只能走 query 旁路）
 */
export function getSseUrl(baseUrl: string): string {
  const token = getToken()
  if (!token) return baseUrl
  const sep = baseUrl.includes('?') ? '&' : '?'
  return `${baseUrl}${sep}token=${encodeURIComponent(token)}`
}
