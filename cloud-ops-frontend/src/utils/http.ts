/**
 * HTTP 工具 — 带自动 JWT 认证的 fetch 封装
 *
 * 功能:
 *   1. 所有请求自动添加 Authorization: Bearer header
 *   2. 401 → 清除 token，触发登录页
 *   3. 403 → 返回错误信息（权限不足）
 *   4. 提供 fetchSSE() 替代 EventSource（支持自定义 Header）
 */

import {clearAuth, getAuthHeaders} from './auth'

const API_BASE = 'http://localhost:8080'

/** 认证失败回调（由 App.vue 设置） */
let onAuthFailed: (() => void) | null = null
export function setAuthFailedHandler(fn: () => void) {
  onAuthFailed = fn
}

/** 通用 JSON 请求 */
export async function apiFetch<T = any>(
  path: string,
  options: RequestInit = {}
): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...getAuthHeaders(),
    ...(options.headers as Record<string, string> || {})
  }

  const res = await fetch(API_BASE + path, { ...options, headers })

  if (res.status === 401) {
    clearAuth()
    onAuthFailed?.()
    throw new Error('未登录或Token已过期')
  }

  if (res.status === 403) {
    const data = await res.json().catch(() => ({ message: '权限不足' }))
    throw new Error(data.message || '权限不足')
  }

  if (!res.ok) {
    const data = await res.json().catch(() => ({ message: `HTTP ${res.status}` }))
    throw new Error(data.message || data.error || `HTTP ${res.status}`)
  }

  return res.json()
}

/**
 * SSE 流式请求 — 用 fetch + ReadableStream 替代 EventSource
 *
 * 为什么不用 EventSource:
 *   EventSource 不支持自定义 Header（Authorization），
 *   JWT 认证后 EventSource 无法携带 Token。
 *   fetch + ReadableStream 可以自由设置 Header，逐行解析 SSE 数据。
 *
 * @param path API 路径
 * @param params 查询参数
 * @param onMessage 每条 SSE 消息的回调
 * @param onError 错误回调
 * @returns AbortController（用于中断请求）
 */
export async function fetchSSE(
  path: string,
  params: Record<string, string>,
  onMessage: (data: string) => void,
  onError?: (err: Error) => void
): Promise<void> {
  const url = new URL(API_BASE + path)
  Object.entries(params).forEach(([k, v]) => url.searchParams.set(k, v))

  console.log('[SSE] 请求URL:', url.toString())

  try {
    const res = await fetch(url.toString(), {
      headers: { ...getAuthHeaders(), 'Accept': 'text/event-stream' },
    })

    console.log('[SSE] 响应状态:', res.status, '类型:', res.headers.get('content-type'))

    if (res.status === 401) {
      clearAuth()
      onAuthFailed?.()
      throw new Error('未登录或Token已过期')
    }

    if (res.status === 403) {
      const data = await res.json().catch(() => ({ message: '权限不足' }))
      throw new Error(data.message || '权限不足，无法访问Agent对话')
    }

    if (!res.ok) throw new Error(`HTTP ${res.status}`)

    if (!res.body) throw new Error('响应体为空')

    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      const chunk = decoder.decode(value, { stream: true })
      buffer += chunk
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''
      for (const line of lines) {
        const trimmed = line.trim()
        if (trimmed.startsWith('data:')) {
          const data = trimmed.slice(5).trim()
          if (data) onMessage(data)
        }
      }
    }
    // 处理buffer中剩余的数据
    if (buffer.trim().startsWith('data:')) {
      const data = buffer.trim().slice(5).trim()
      if (data) onMessage(data)
    }
    console.log('[SSE] 流式读取完成')
  } catch (err) {
    console.error('[SSE] 错误:', err)
    if ((err as Error).name !== 'AbortError') {
      onError?.(err as Error)
    }
  }
}

/** 登录请求 */
export async function login(username: string, password: string) {
  const res = await fetch(API_BASE + '/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password })
  })
  const data = await res.json()
  if (!res.ok) throw new Error(data.error || '登录失败')
  return data
}

/** 获取完整 API 地址 */
export function apiUrl(path: string): string {
  return API_BASE + path
}
