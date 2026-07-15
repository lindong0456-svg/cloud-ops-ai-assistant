/**
 * JWT 认证工具 — Token 管理 + 用户信息
 */

export interface UserInfo {
  token: string
  userId: string
  username: string
  tenantId: string
  deptId: string
  roles: string[]
  permissions: string[]
}

const TOKEN_KEY = 'jwt_token'
const USER_KEY = 'user_info'

export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function getUserInfo(): UserInfo | null {
  try {
    const raw = localStorage.getItem(USER_KEY)
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}

export function setAuth(info: UserInfo): void {
  localStorage.setItem(TOKEN_KEY, info.token)
  localStorage.setItem(USER_KEY, JSON.stringify(info))
}

export function clearAuth(): void {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

export function isLoggedIn(): boolean {
  return !!getToken()
}

export function hasPermission(code: string): boolean {
  const user = getUserInfo()
  if (!user) return false
  if (user.roles.includes('SUPER_ADMIN')) return true
  return user.permissions.includes(code)
}

export function getMemoryId(): string {
  const user = getUserInfo()
  if (!user) return 'anonymous'
  return `${user.tenantId}:${user.userId}`
}

export function getAuthHeaders(): Record<string, string> {
  const token = getToken()
  return token ? { Authorization: 'Bearer ' + token } : {}
}
