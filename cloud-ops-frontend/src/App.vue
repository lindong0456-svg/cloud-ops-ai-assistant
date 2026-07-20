<template>
  <div class="app">
    <!-- ==================== 左侧栏 ==================== -->
    <aside class="sidebar">
      <div class="sidebar-header">
        <div class="brand">
          <div class="brand-logo">⚡</div>
          <div>
            <div class="brand-title">云运维智能助手</div>
            <div class="brand-sub">ReAct Agent · v1.0</div>
          </div>
        </div>
      </div>

      <!-- 新建对话按钮 -->
      <button :disabled="streaming" class="new-chat-btn" @click="newChat">
        <span>✨</span><span>新建对话</span>
      </button>

      <!-- 实时告警区（提至快捷操作上方，日常使用最关注） -->
      <!-- 权限门控：仅拥有 alarm:read 的角色（admin/ops_eng）可见；其余角色显示无权限占位 -->
      <div v-if="canViewAlarms" :class="{ 'is-expanded': isAlarmsExpanded }" class="sidebar-section sidebar-section--alarms">
        <div class="section-header section-header--collapsible" @click="toggleAlarms">
          <h3>实时告警 <span v-if="alarms.length > 0" class="alarm-count-badge">{{ alarms.length }}</span></h3>
          <span class="header-actions">
            <button :disabled="streaming" class="refresh-btn" title="刷新告警" @click.stop="fetchAlarms">↻</button>
            <span :class="{ expanded: isAlarmsExpanded }" class="collapse-icon">▶</span>
          </span>
        </div>
        <div v-show="isAlarmsExpanded">
          <!-- 告警加载中 -->
          <div v-if="alarmsLoading" class="alarm-loading">
            <div class="mini-spinner"></div>
            <span>加载中...</span>
          </div>
          <!-- 告警列表 -->
          <div v-else-if="alarms.length > 0" class="alarm-list">
            <div
              v-for="alarm in alarms"
              :key="alarm.alertId"
              :class="'severity-' + (alarm.severity || 'info').toLowerCase()"
              class="alarm-card"
            >
              <div class="alarm-severity-bar"></div>
              <div class="alarm-body">
                <div class="alarm-resource">{{ alarm.resourceId }}</div>
                <div class="alarm-msg">{{ alarm.msg }}</div>
                <div class="alarm-meta">
                  <span class="alarm-type">{{ alarm.resourceType }}</span>
                  <button
                    :disabled="streaming"
                    class="alarm-action-btn"
                    @click="startTroubleshoot(alarm)"
                  >
                    🔧 排障
                  </button>
                </div>
              </div>
            </div>
          </div>
          <!-- 无告警 -->
          <div v-else class="alarm-empty">
            <span>✅ 暂无未处理告警</span>
          </div>
        </div>
      </div>
      <!-- 无 alarm:read 权限：展示占位，不调用接口 -->
      <div v-else class="sidebar-section sidebar-section--alarms">
        <div class="alarm-empty alarm-empty--locked">
          <span>🔒 当前角色无权限查看告警</span>
        </div>
      </div>

      <!-- 历史对话列表 -->
      <div v-if="sessions.length > 0" class="sidebar-section sidebar-section--history">
        <div class="section-header section-header--collapsible" @click="toggleHistory">
          <h3>历史对话</h3>
          <span :class="{ expanded: isHistoryExpanded }" class="collapse-icon">▶</span>
        </div>
        <div v-show="isHistoryExpanded" class="history-list">
          <div
            v-for="s in sessions"
            :key="s.id"
            :class="{ active: s.id === currentSessionId }"
            class="history-item"
            @click="switchSession(s.id)"
          >
            <div class="history-title">{{ s.title || '新对话' }}</div>
            <div class="history-meta">
              <span class="history-time">{{ formatTime(s.createdAt) }}</span>
              <button class="history-del" title="删除" @click.stop="deleteSession(s.id)">✕</button>
            </div>
          </div>
        </div>
      </div>
    </aside>

    <!-- ==================== 右侧主区 ==================== -->
    <main class="main">
      <header class="main-header">
        <div class="left">
          <span class="left-title">智能排障对话</span>
          <!-- 后端连接状态（紧凑行内） -->
          <span :class="{ online: backendOnline }" :title="backendOnline ? '后端已连接' : '正在连接后端...'" class="conn-status"></span>
          <span class="model-tag">{{ activeModelLabel }} · ReAct</span>
          <!-- 管理员：模型切换下拉（仅多模型时显示） -->
          <select
            v-if="isAdmin && modelOptions.length > 1"
            :disabled="streaming || switchingModel"
            :value="activeModelKey"
            class="model-select"
            title="切换对话模型"
            @change="switchModel(($event.target as HTMLSelectElement).value)"
          >
            <option v-for="m in modelOptions" :key="m.key" :value="m.key">{{ m.label }}</option>
          </select>
        </div>
        <div class="right">
          <!-- 主题切换按钮 -->
          <button class="theme-btn" :title="themeMode === 'dark' ? '切换到日间模式' : '切换到夜间模式'" @click="toggleTheme">
            {{ themeMode === 'dark' ? '☀️' : '🌙' }}
          </button>
          <!-- 用户面板 -->
          <UserPanel v-if="currentUser" :user="currentUser" active-permission="agent:chat" @logout="handleLogout" />
          <span v-else class="msg-count">{{ messages.length }} 条消息</span>
        </div>
      </header>

      <div ref="messagesRef" class="messages">
        <!-- 欢迎页 -->
        <div v-if="messages.length === 0" class="welcome">
          <div class="welcome-icon">⚡</div>
          <h2>云运维智能助手</h2>
          <p>基于 ReAct 的云资源排障 Agent，支持告警查询、资源拓扑、负载分析、SOP 检索</p>
          <div class="examples">
            <button v-for="card in welcomeCards" :key="card.text" class="example-card" @click="sendMessage(card.text)">
              <div class="title"><span class="icon">{{ card.icon }}</span>{{ card.title }}</div>
              <div class="desc">{{ card.desc }}</div>
            </button>
          </div>
        </div>

        <!-- 消息列表 -->
        <div v-for="(msg, index) in messages" :key="index" :class="msg.role" class="message">
          <div class="avatar">{{ msg.role === 'user' ? '👤' : '🤖' }}</div>
          <div class="message-content">
            <div class="message-role">{{ msg.role === 'user' ? '我' : (msg.streaming ? '思考中' : '运维助手') }}</div>
            <div v-if="msg.role === 'assistant'" class="react-container">
              <!--
                ★ 流式期间：混合渲染模式
                - 已闭合段落（后面出现了新标记）：渲染为 ReAct 卡片/Markdown，内容不再变
                - 最后一段（正在生成中）：保持为轻量纯文本，避免每 token 重新 parse+compile
                效果：卡片逐步"长出来"，最后一段平滑过渡到完成态，不会出现整体跳变
              -->
              <template v-if="msg.streaming">
                <!-- 工具执行中提示（排障过程透明化） -->
                <div v-if="currentTool" class="tool-progress">
                  <span class="tool-progress-icon">🔧</span>
                  <span class="tool-progress-text">正在调用 <strong>{{ currentTool }}</strong>…</span>
                  <span class="tool-progress-dots"><span>.</span><span>.</span><span>.</span></span>
                </div>
                <!-- 已闭合段落 → ReAct 卡片 / Markdown 渲染 -->
                <template v-for="(section, sIdx) in getStreamingParsed(msg.content).completed" :key="'sc-' + sIdx">
                  <div v-if="section.type === 'thinking'" class="react-card react-card--thinking stream-card">
                    <div class="react-card-label">🧠 思考</div>
                    <div class="react-card-content">{{ section.content }}</div>
                  </div>
                  <div v-else-if="section.type === 'tool'" class="react-card react-card--tool stream-card">
                    <div class="react-card-label">🔧 工具调用</div>
                    <code class="react-card-content react-code">{{ section.content }}</code>
                  </div>
                  <div v-else-if="section.type === 'observation'" class="react-card react-card--observation stream-card">
                    <div class="react-card-label">📊 观察结果</div>
                    <div class="react-card-content">{{ section.content }}</div>
                  </div>
                  <div v-else class="markdown-body stream-card" @click="handleDocClick" v-html="renderMarkdown(section.content)"></div>
                </template>
                <!-- 最后一段（正在生成）→ 轻量纯文本 + 光标 -->
                <div v-if="getStreamingParsed(msg.content).current" class="streaming-text">
                  <span v-if="getStreamingParsed(msg.content).current!.type !== 'text'" class="streaming-label">{{ markerLabel(getStreamingParsed(msg.content).current!.type) }}</span>
                  {{ getStreamingParsed(msg.content).current!.content }}<span class="streaming-cursor">|</span>
                </div>
                <div v-else class="streaming-text"><span class="streaming-cursor">|</span></div>
              </template>

              <!-- ★ 完成/历史消息：完整 ReAct 卡片 + Markdown 渲染 -->
              <template v-else>
                <!-- 有结论 + 中间步骤 → 支持折叠 -->
                <template v-if="hasCollapsibleSections(msg.content)">
                  <!-- 折叠触发条：点击展开/收起 -->
                  <div
                    :class="{ expanded: isExpanded(index) }"
                    class="react-collapse-trigger"
                    @click="toggleExpand(index)"
                  >
                    <span class="collapse-gem">✦</span>
                    <span class="collapse-label">思考完成</span>
                    <span class="collapse-count">{{ getIntermediateCount(msg.content) }} 步推理过程</span>
                    <span class="collapse-arrow">{{ isExpanded(index) ? '▾' : '▸' }}</span>
                  </div>
                  <!-- 可折叠的中间步骤（默认收起） -->
                  <div v-show="isExpanded(index)" class="react-collapse-body">
                    <template v-for="(section, sIdx) in getIntermediateSections(msg.content)" :key="'int-' + index + '-' + sIdx">
                      <div v-if="section.type === 'thinking'" class="react-card react-card--thinking">
                        <div class="react-card-label">🧠 思考</div>
                        <div class="react-card-content">{{ section.content }}</div>
                      </div>
                      <div v-else-if="section.type === 'tool'" class="react-card react-card--tool">
                        <div class="react-card-label">🔧 工具调用</div>
                        <code class="react-card-content react-code">{{ section.content }}</code>
                      </div>
                      <div v-else-if="section.type === 'observation'" class="react-card react-card--observation">
                        <div class="react-card-label">📊 观察结果</div>
                        <div class="react-card-content">{{ section.content }}</div>
                      </div>
                    </template>
                  </div>
                  <!-- 结论始终展示，不参与折叠 -->
                  <template v-for="(section, sIdx) in getConclusionSections(msg.content)" :key="'concl-' + index + '-' + sIdx">
                    <div class="markdown-body" @click="handleDocClick" v-html="renderMarkdown(section.content)"></div>
                  </template>
                </template>

                <!-- 无结论或无中间步骤 → 原始渲染，不折叠 -->
                <template v-else>
                  <template v-for="(section, sIdx) in parseReActSections(msg.content)" :key="'done-' + sIdx">
                    <div v-if="section.type === 'thinking'" class="react-card react-card--thinking">
                      <div class="react-card-label">🧠 思考</div>
                      <div class="react-card-content">{{ section.content }}</div>
                    </div>
                    <div v-else-if="section.type === 'tool'" class="react-card react-card--tool">
                      <div class="react-card-label">🔧 工具调用</div>
                      <code class="react-card-content react-code">{{ section.content }}</code>
                    </div>
                    <div v-else-if="section.type === 'observation'" class="react-card react-card--observation">
                      <div class="react-card-label">📊 观察结果</div>
                      <div class="react-card-content">{{ section.content }}</div>
                    </div>
                    <div v-else class="markdown-body" @click="handleDocClick" v-html="renderMarkdown(section.content)"></div>
                  </template>
                </template>
              </template>
            </div>
            <div v-else class="text-content">{{ msg.content }}</div>
            <div v-if="msg.streaming" class="typing-indicator"><span></span><span></span><span></span></div>
            <!-- 流式断开后的重试按钮 -->
            <button
              v-if="needsRetry && msg.role === 'assistant' && !msg.streaming && index === messages.length - 1"
              class="retry-btn"
              @click="retrySend"
            >
              🔄 重新连接
            </button>
            <!-- T29: 下载报告按钮 -->
            <button
              v-if="msg.role === 'assistant' && !msg.streaming && msg.content && isTroubleshootReport(msg.content)"
              class="download-report-btn"
              @click="downloadReport(msg.content, index)"
            >
              📥 下载报告
            </button>
            <!-- 响应统计面板 -->
            <div v-if="msg.role === 'assistant' && !msg.streaming && (msg as any).stats && (msg as any).stats.totalTokens > 0" class="stats-bar">
              <span class="stats-item">🪙 {{ ((msg as any).stats.totalTokens).toLocaleString() }} token</span>
              <span class="stats-divider">·</span>
              <span class="stats-item">🔧 {{ (msg as any).stats.toolCallCount }} 次工具调用</span>
              <template v-if="((msg as any).stats.toolSuccess || 0) + ((msg as any).stats.toolFail || 0) > 0">
                <span class="stats-divider">·</span>
                <span class="stats-item">✅ {{ (msg as any).stats.toolSuccess }}✓ / {{ (msg as any).stats.toolFail }}✗</span>
              </template>
              <template v-if="((msg as any).stats.ragRecallCount || 0) > 0">
                <span class="stats-divider">·</span>
                <span class="stats-item">🔍 RAG 召回 {{ (msg as any).stats.ragRecallCount }} 条 ({{ (msg as any).stats.ragLatencyMs }}ms)</span>
              </template>
              <span class="stats-divider">·</span>
              <span class="stats-item">🧠 {{ (msg as any).stats.model || activeModelLabel }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 输入区 -->
      <div class="input-area">
        <div class="input-wrapper">
          <textarea
            v-model="inputText"
            :disabled="streaming"
            placeholder="描述告警或问题… Enter 发送 · Shift+Enter 换行"
            rows="1"
            @input="autoResize"
            @keydown.enter.exact.prevent="handleSend"
            @keydown.shift.enter.prevent="inputText += '\n'"
          ></textarea>
          <!-- 生成中显示停止按钮，否则显示发送按钮 -->
          <button v-if="streaming" class="send-btn stop-btn" @click="stopGeneration">
            ⏹ 停止
          </button>
          <button v-else :disabled="!inputText.trim()" class="send-btn" @click="handleSend">
            发送
          </button>
        </div>
        <div class="input-hint">
          {{ streaming ? '⏳ Agent 正在生成中，点击"停止"可中断...' : '按 Enter 发送 · Agent 会自主调用工具排障 · 回复支持 Markdown 排版' }}
        </div>
      </div>
    </main>

    <!-- SOP 文档弹窗 -->
    <div v-if="sopModalVisible" class="sop-modal-overlay" @click.self="closeSop">
      <div class="sop-modal">
        <div class="sop-modal-header">
          <div class="sop-modal-title">
            <span class="sop-modal-icon">📄</span>
            <span>{{ sopModalTitle }}</span>
          </div>
          <button class="sop-modal-close" @click="closeSop">✕</button>
        </div>
        <div class="sop-modal-body">
          <div v-if="sopLoading" class="sop-loading">
            <div class="spinner"></div>
            <span>加载中...</span>
          </div>
          <div v-else class="markdown-body" v-html="renderMarkdown(sopModalContent)"></div>
        </div>
      </div>
    </div>

    <!-- Toast -->
    <div v-if="toast" class="toast">{{ toast }}</div>

    <!-- 登录弹窗 -->
    <LoginModal v-if="showLogin" @success="handleLoginSuccess" />
  </div>
</template>

<script lang="ts" setup>
import {computed, nextTick, onMounted, reactive, ref} from 'vue'
import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js'
import LoginModal from './components/LoginModal.vue'
import UserPanel from './components/UserPanel.vue'
import {apiFetch, getSseUrl, getUser, isLoggedIn, logout, type UserInfo} from './api/client'

const inputText = ref('')
const messages = ref<Array<{ role: string; content: string; streaming?: boolean }>>([])
const streaming = ref(false)
const backendOnline = ref(false)
const messagesRef = ref<HTMLElement>()
const toast = ref('')
// 工具执行进度：排障过程透明化
const currentTool = ref('')
// 流式连接断开后的重试信息
const needsRetry = ref(false)
const retryMessage = ref('')
const retryUserId = ref('')

// ★ 登录状态管理
const showLogin = ref(!isLoggedIn())
const currentUser = ref<UserInfo | null>(getUser())

// ★ 当前模型信息（动态徽标 + 切换下拉）
const activeModelLabel = ref('DeepSeek')      // 当前模型展示名
const activeModelKey = ref('deepseek')        // 当前模型 key
const modelOptions = ref<Array<{ key: string; label: string }>>([])  // 可选模型
const switchingModel = ref(false)
// 仅 SUPER_ADMIN / TENANT_ADMIN 可切换模型（复用已有角色体系）
const isAdmin = computed(() => {
  const r = currentUser.value?.roles || []
  return r.includes('SUPER_ADMIN') || r.includes('TENANT_ADMIN')
})

/** 拉取当前模型 + 可选模型列表（header 徽标 + 切换下拉） */
async function fetchModelInfo() {
  try {
    const res = await apiFetch('/api/agent/model')
    if (!res.ok) return
    const data = await res.json()
    if (data.label) activeModelLabel.value = data.label
    if (data.active) activeModelKey.value = data.active
    if (Array.isArray(data.models)) modelOptions.value = data.models
  } catch {
    // 忽略：保持默认 DeepSeek
  }
}

/** 切换对话模型（管理员） */
async function switchModel(key: string) {
  if (switchingModel.value || !key) return
  switchingModel.value = true
  try {
    const res = await apiFetch('/api/agent/model/switch?key=' + encodeURIComponent(key), { method: 'POST' })
    if (!res.ok) {
      const d = await res.json().catch(() => ({}))
      showToast('切换失败：' + (d.error || ('HTTP ' + res.status)))
      return
    }
    const data = await res.json()
    activeModelLabel.value = data.label || key
    activeModelKey.value = data.active || key
    showToast('已切换模型：' + (data.label || key) + ' · ReAct')
  } catch (e) {
    showToast('切换失败：' + (e as Error).message)
  } finally {
    switchingModel.value = false
  }
}

// ★ 主题管理：dark / light，根据时间自动切换（6:00-18:00=light）
type ThemeMode = 'dark' | 'light'
const THEME_KEY = 'cloud-ops-theme'
const themeMode = ref<ThemeMode>((localStorage.getItem(THEME_KEY) as ThemeMode) || detectThemeByTime())

function detectThemeByTime(): ThemeMode {
  const h = new Date().getHours()
  return (h >= 6 && h < 18) ? 'light' : 'dark'
}

function applyTheme(mode: ThemeMode) {
  document.documentElement.classList.add('theme-transitioning')
  document.documentElement.setAttribute('data-theme', mode)
  // 清除过渡 class（让后续交互不再有过渡延迟）
  setTimeout(() => {
    document.documentElement.classList.remove('theme-transitioning')
  }, 400)
}

function toggleTheme() {
  themeMode.value = themeMode.value === 'dark' ? 'light' : 'dark'
  localStorage.setItem(THEME_KEY, themeMode.value)
  applyTheme(themeMode.value)
}

// 登录成功回调
function handleLoginSuccess(user: UserInfo) {
  currentUser.value = user
  showLogin.value = false
  backendOnline.value = true
  // 仅当拥有 alarm:read 权限时才拉取告警列表
  if (canViewAlarms.value) fetchAlarms()
  fetchModelInfo()
  showToast('登录成功，欢迎 ' + user.username)
}

// 退出登录
function handleLogout() {
  logout()
  currentUser.value = null
  showLogin.value = true
  messages.value = []
  showToast('已退出登录')
}

// 监听 auth:logout 事件（apiFetch 401 时触发）
window.addEventListener('auth:logout', () => {
  currentUser.value = null
  showLogin.value = true
  messages.value = []
})

// 工具能力折叠面板状态（默认收起）
const isToolsExpanded = ref(false)
function toggleTools() { isToolsExpanded.value = !isToolsExpanded.value }

// 历史对话折叠（默认展开）
const isHistoryExpanded = ref(true)
function toggleHistory() { isHistoryExpanded.value = !isHistoryExpanded.value }

// 实时告警折叠（默认展开）：折叠后告警区缩为标题高度，历史对话自动上移
const isAlarmsExpanded = ref(true)
function toggleAlarms() { isAlarmsExpanded.value = !isAlarmsExpanded.value }

let userId = 'web-user-' + Math.random().toString(36).substring(2, 8)
// SSE 连接：EventSource 直连后端
let eventSource: EventSource | null = null

/**
 * 首页欢迎卡片 — 按登录用户权限动态展示
 * 财务看到账单/成本类，运维看到排障类，无权限的卡片不展示
 */
interface WelcomeCard {
  icon: string
  title: string
  desc: string
  text: string
  perm: string
}
const ALL_WELCOME_CARDS: WelcomeCard[] = [
  // 运维场景（需 alarm:read 或 agent:chat）
  { icon: '🚨', title: '查看告警', desc: '查询当前未处理告警列表', text: '有哪些告警？', perm: 'alarm:read' },
  { icon: '🔧', title: 'CPU 排障', desc: '从告警到 SOP 的完整排障', text: 'ecs-001 的 CPU 高怎么办？', perm: 'agent:chat' },
  { icon: '🧠', title: '内存排查', desc: '分析内存指标定位泄漏', text: 'ebm-001 是不是内存泄漏？', perm: 'agent:chat' },
  // 财务/账单场景（需 bill:read）
  { icon: '💰', title: '成本优化', desc: '基于账单给出优化建议', text: 'gpu-002 成本优化建议', perm: 'bill:read' },
  { icon: '📊', title: '账单查询', desc: '查询本月资源费用明细', text: '本月账单明细有哪些？', perm: 'bill:read' },
  { icon: '💳', title: '资源费用', desc: '查询指定资源的费用情况', text: 'ecs-001 这个月花了多少钱？', perm: 'bill:read' },
]
const welcomeCards = computed(() => {
  const user = currentUser.value
  if (!user) return []
  return ALL_WELCOME_CARDS.filter(c => user.permissions.some(p => p.code === c.perm))
})

// ========== 对话历史管理（localStorage 持久化） ==========
interface ChatSession {
  id: string
  title: string
  messages: Array<{ role: string; content: string; streaming?: boolean }>
  createdAt: number
  userId?: string
  username?: string   // ★ 登录用户名，用于按用户隔离历史对话
}
const STORAGE_KEY = 'cloud-ops-sessions'
const currentSessionId = ref('')
// ★ 全量会话（本地不做过滤），历史列表按当前登录用户过滤展示
const allSessions = ref<ChatSession[]>(loadSessions())
// ★ 按登录用户隔离：不同账号登录只看到自己的历史对话
const sessions = computed(() => {
  const uname = currentUser.value?.username
  if (!uname) return []
  return allSessions.value.filter(s => s.username === uname)
})

function loadSessions(): ChatSession[] {
  try {
    return JSON.parse(localStorage.getItem(STORAGE_KEY) || '[]')
  } catch { return [] }
}
function saveSessions() {
  // 只保留 messages 的基本信息（strip streaming flag）
  const clean = allSessions.value.map(s => ({
    ...s,
    messages: s.messages.map(m => ({ role: m.role, content: m.content }))
  }))
  localStorage.setItem(STORAGE_KEY, JSON.stringify(clean))
}
function formatTime(ts: number): string {
  const d = new Date(ts)
  return d.toLocaleDateString('zh-CN') + ' ' + d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}
function archiveCurrentSession() {
  if (messages.value.length === 0) return
  const firstUser = messages.value.find(m => m.role === 'user')
  const title = firstUser ? (firstUser.content || '').slice(0, 30) : '新对话'
  // 更新已有 session 或新建
  const existing = allSessions.value.find(s => s.id === currentSessionId.value)
  if (existing) {
    existing.messages = [...messages.value]
    existing.title = title
  } else {
    currentSessionId.value = 'sess-' + Date.now()
    allSessions.value.unshift({
      id: currentSessionId.value,
      title,
      messages: [...messages.value],
      createdAt: Date.now(),
      userId: userId,
      username: currentUser.value?.username || 'unknown',
    })
  }
  // 最多保留 20 条历史
  if (allSessions.value.length > 20) allSessions.value = allSessions.value.slice(0, 20)
  saveSessions()
}
function switchSession(id: string) {
  if (streaming.value) return
  archiveCurrentSession()
  const s = allSessions.value.find(x => x.id === id)
  if (s) {
    currentSessionId.value = s.id
    userId = s.userId || userId
    messages.value = s.messages.map(m => ({ ...m, streaming: false }))
  }
}
function deleteSession(id: string) {
  allSessions.value = allSessions.value.filter(s => s.id !== id)
  if (currentSessionId.value === id) {
    currentSessionId.value = ''
    messages.value = []
  }
  saveSessions()
}

// Markdown 渲染器：启用表格、链接、代码高亮
const md = new MarkdownIt({
  html: true,
  breaks: true,
  linkify: true,
  typographer: true,
  highlight(str: string, lang: string): string {
    if (lang && hljs.getLanguage(lang)) {
      try {
        return '<pre><code class="hljs">' + hljs.highlight(str, { language: lang }).value + '</code></pre>'
      } catch {
        // ignore
      }
    }
    return '<pre><code class="hljs">' + md.utils.escapeHtml(str) + '</code></pre>'
  }
})

/**
 * 内容预处理 — 在交给 markdown-it 渲染之前，修复常见的格式问题
 *
 * 设计原则：只修明确的格式错误，不改变已有的正确格式。
 * 之前的版本过于激进（自动分段、自动加列表符、拆 **bold**），
 * 导致模型输出的正确格式反而被改坏。
 *
 * 只修复两种问题：
 *   1. `##标题` 缺少空格 → `## 标题`
 *   2. `-列表项` 缺少空格 → `- 列表项`
 *   3. `1.列表项` 缺少空格 → `1. 列表项`
 */
function normalizeContent(raw: string): string {
  if (!raw) return ''

  let text = raw

  // 1. 保护代码块（``` ... ``` 之间的内容不做任何处理）
  const codeBlocks: string[] = []
  text = text.replace(/```[\s\S]*?```/g, (match) => {
    codeBlocks.push(match)
    return '\x00CODEBLOCK' + (codeBlocks.length - 1) + '\x00'
  })

  // 2. 修复 ATX 标题缺少空格：`##标题` → `## 标题`
  // CommonMark 规范要求：# 后面必须有一个空格才是标题
  text = text.replace(/^(#{1,6})([^\s#])/gm, '$1 $2')

  // 2b. 修复行内 ## 标题：模型常输出 `...内容。## 标题` 在同一行
  // markdown-it 要求 ## 必须在行首才识别为标题，否则显示为字面文本
  // 在 ## 前插入 \n\n，使其独立成行
  text = text.replace(/([^\n])((#{1,6})\s)/g, '$1\n\n$2')

  // 3. 修复无序列表缺少空格：`-item` → `- item`
  text = text.replace(/^(\s*[-*+])([^\s])/gm, '$1 $2')

  // 4. 修复有序列表缺少空格：`1.item` → `1. item`
  text = text.replace(/^(\s*\d+\.)([^\s])/gm, '$1 $2')

  // 5. 恢复代码块
  codeBlocks.forEach((block, i) => {
    text = text.replace('\x00CODEBLOCK' + i + '\x00', block)
  })

  return text
}

function renderMarkdown(content: string): string {
  return md.render(normalizeContent(content || ''))
}

/**
 * ReAct 推理过程解析器
 *
 * 把 Agent 输出的带标记文本切分成多个 section，前端渲染成不同颜色的卡片。
 *
 * 标记类型：
 *   【思考】    → thinking     蓝色卡片
 *   【工具调用】 → tool         紫色卡片
 *   【观察】    → observation   绿色卡片
 *   【结论】    → conclusion    正常 Markdown 气泡
 *
 * 流式输出时，每次 token 追加都会重新解析整个内容。
 * Vue 的 v-for + key 复用已有 DOM，新 section 追加渲染，不会闪烁。
 */
type SectionType = 'thinking' | 'tool' | 'observation' | 'conclusion' | 'text'
interface ReActSection {
  type: SectionType
  content: string
}

const REACT_MARKERS: Record<string, SectionType> = {
  '【思考】': 'thinking',
  '【工具调用】': 'tool',
  '【观察】': 'observation',
  '【结论】': 'conclusion',
}

function parseReActSections(raw: string): ReActSection[] {
  if (!raw) return [{ type: 'text', content: '' }]

  // 匹配所有标记
  const markerPattern = /【思考】|【工具调用】|【观察】|【结论】/g

  // split 按标记切分，match 取出所有标记
  const parts = raw.split(markerPattern)
  const matches = raw.match(markerPattern) || []

  const sections: ReActSection[] = []

  // parts[0] 是第一个标记之前的内容（通常是空字符串）
  if (parts[0] && parts[0].trim()) {
    sections.push({ type: 'text', content: parts[0].trim() })
  }

  // 后续 parts 按标记分类
  for (let i = 0; i < matches.length; i++) {
    const marker = matches[i]
    const content = (parts[i + 1] || '').trim()
    const type = REACT_MARKERS[marker] || 'text'
    // conclusion 类型即使内容为空也要保留（标记后面可能还在流式中）
    // 其他类型内容为空就跳过
    if (content || type === 'conclusion') {
      sections.push({ type, content })
    }
  }

  // 如果没有任何标记，返回原始内容作为 text
  const result = sections.length > 0 ? sections : [{ type: 'text', content: raw }]
  // ★ 兜底：模型偶发丢失【结论】标记，把结论塞进最后一个【观察】
  //   若全篇无 conclusion 段，但某个 observation/text 段含结论模板标题（## 排障结论 / ## 查询结果），
  //   则把标题及其之后内容拆成独立 conclusion 段，前面保留为 observation。
  return splitLostConclusion(result)
}

/**
 * 兜底拆分：模型漏打【结论】标记时，把误入观察结果的结论救回来
 *
 * 现象：DeepSeek 长输出或多轮工具调用后，偶尔把最终结论（## 排障结论 / ## 查询结果 模板）
 *       写进最后一个【观察】段，导致前端没有独立结论卡片（截图3问题）。
 * 策略：若解析结果里没有 conclusion 段，则从后往前找第一个带结论模板标题的
 *       observation/text 段，将其按标题位置拆成「observation（前文）+ conclusion（标题起全文）」。
 */
function splitLostConclusion(sections: ReActSection[]): ReActSection[] {
  if (sections.some(s => s.type === 'conclusion')) return sections
  const CONCL_HEADING = /##\s+(排障结论|查询结果)/
  for (let i = sections.length - 1; i >= 0; i--) {
    const sec = sections[i]
    if ((sec.type === 'observation' || sec.type === 'text') && CONCL_HEADING.test(sec.content)) {
      const idx = sec.content.search(/##\s+(排障结论|查询结果)/)
      const obsPart = sec.content.slice(0, idx).trim()
      const conclPart = sec.content.slice(idx).trim()
      const out: ReActSection[] = []
      for (let j = 0; j < sections.length; j++) {
        if (j === i) {
          if (obsPart) out.push({ type: sec.type, content: obsPart })
          out.push({ type: 'conclusion', content: conclPart })
        } else {
          out.push(sections[j])
        }
      }
      return out
    }
  }
  return sections
}

// ========== 结论后收缩中间步骤卡片 ==========

/** 已收缩的消息索引集合（默认全部收缩，有结论时自动触发） */
const collapsedMessages = ref<Set<number>>(new Set())

/**
 * 判断一条完成的消息是否可收缩：
 * 必须同时存在"中间步骤"(thinking/tool/observation) 和"结论"(conclusion)
 */
function hasCollapsibleSections(content: string): boolean {
  const sections = parseReActSections(content)
  const hasIntermediate = sections.some(s => s.type === 'thinking' || s.type === 'tool' || s.type === 'observation')
  const hasConclusion = sections.some(s => s.type === 'conclusion')
  return hasIntermediate && hasConclusion
}

/** 提取中间步骤（thinking / tool / observation），用于折叠区域 */
function getIntermediateSections(content: string): ReActSection[] {
  return parseReActSections(content).filter(s =>
    s.type === 'thinking' || s.type === 'tool' || s.type === 'observation'
  )
}

/** 提取结论/正文段落，始终可见不参与折叠 */
function getConclusionSections(content: string): ReActSection[] {
  return parseReActSections(content).filter(s =>
    s.type === 'conclusion' || s.type === 'text'
  )
}

/** 中间步骤数量（显示在折叠触发条上） */
function getIntermediateCount(content: string): number {
  return getIntermediateSections(content).length
}

/** 切换某条消息的展开/收起状态 */
function toggleExpand(index: number) {
  const next = new Set(collapsedMessages.value)
  if (next.has(index)) { next.delete(index) } else { next.add(index) }
  collapsedMessages.value = next
}

/** 判断某条消息是否处于展开状态（未收缩=展示完整内容） */
function isExpanded(index: number): boolean {
  return !collapsedMessages.value.has(index)
}

/**
 * 流式渲染专用的混合解析器
 *
 * 与 parseReActSections 不同，它区分「已闭合段落」和「正在写的段落」：
 *   - 已闭合段落：该段落后面出现了新的标记（【思考】→【工具调用】说明思考已写完）
 *     → 渲染为 ReAct 卡片，内容稳定不再变化
 *   - 最后一段：没有后续标记 → 仍在流式生成中
 *     → 保持为轻量纯文本 + 光标
 *
 * 这样做的好处：
 *   1. 已完成段落提前以卡片形式展示，视觉上逐步构建
 *   2. 只有最后一段是纯文本，Markdown 编译延迟到该段闭合后才触发
 *   3. 流式完成时，最后一段加入已完成列表，过渡自然
 */
function getStreamingParsed(raw: string): { completed: ReActSection[]; current: ReActSection | null } {
  if (!raw) return { completed: [], current: null }

  const markerPattern = /【思考】|【工具调用】|【观察】|【结论】/g
  const parts = raw.split(markerPattern)
  const matches = raw.match(markerPattern) || []

  const completed: ReActSection[] = []
  let current: ReActSection | null = null

  // 处理第一个标记之前的文本
  if (parts[0] && parts[0].trim()) {
    if (matches.length > 0) {
      // 后面有标记 → 这段已闭合
      completed.push({ type: 'text', content: parts[0].trim() })
    } else {
      // 没有标记 → 全部内容都在流式中
      current = { type: 'text', content: parts[0] }
      return { completed, current }
    }
  }

  // 处理每个标记 + 内容对
  for (let i = 0; i < matches.length; i++) {
    const marker = matches[i]
    const content = (parts[i + 1] || '')
    const type = REACT_MARKERS[marker] || 'text'

    if (i < matches.length - 1) {
      // 不是最后一个标记 → 该段已闭合
      const trimmed = content.trim()
      if (trimmed) {
        completed.push({ type, content: trimmed })
      }
    } else {
      // 最后一个标记 → 正在生成中
      // conclusion 即使为空也保留（标记本身可能刚出现）
      if (content || type === 'conclusion') {
        current = { type, content }
      }
    }
  }

  return { completed, current }
}

/**
 * 流式段落标签映射
 */
function markerLabel(type: SectionType): string {
  const labels: Record<string, string> = {
    thinking: '🧠 思考中…',
    tool: '🔧 调用中…',
    observation: '📊 观察中…',
    conclusion: '📝 结论中…',
  }
  return labels[type] || ''
}

/**
 * 判断一条消息是否是排障结论（应该显示"下载报告"按钮）
 *
 * 判断依据：消息内容包含 ReAct 推理标记（【思考】/【工具调用】/【观察】/【结论】）。
 * 普通问答（如"有哪些告警？"→ 告警列表）不含这些标记，不显示下载按钮。
 */
function isTroubleshootReport(content: string): boolean {
  if (!content) return false
  return /【思考】|【工具调用】|【观察】|【结论】/.test(content)
}

function showToast(msg: string) {
  toast.value = msg
  setTimeout(() => { toast.value = '' }, 2500)
}

function newChat() {
  if (streaming.value) { showToast('请等待当前回复完成'); return }
  archiveCurrentSession()
  currentSessionId.value = ''
  messages.value = []
  inputText.value = ''
  needsRetry.value = false
  userId = 'web-user-' + Math.random().toString(36).substring(2, 8)  // ★ 新会话 = 新 Memory key
  showToast('已开启新对话')
}

// textarea 自适应高度
function autoResize() {
  const el = document.querySelector('.input-wrapper textarea') as HTMLTextAreaElement | null
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 160) + 'px'
}

async function sendMessage(text: string, isRetry = false) {
  if (streaming.value && !isRetry) { showToast('正在生成中，请稍候...'); return }
  if (!text.trim()) return

  const message = text.trim()
  if (!isRetry) {
    inputText.value = ''
    nextTick(autoResize)
    messages.value.push({ role: 'user', content: message })
  }
  // 重试时复用已有的 aiMessage，否则新建
  let aiMessage = isRetry
    ? messages.value[messages.value.length - 1]
    : null
  if (!aiMessage || aiMessage.role !== 'assistant' || !aiMessage.streaming) {
    aiMessage = reactive({ role: 'assistant', content: isRetry ? messages.value[messages.value.length - 1]?.content || '' : '', streaming: true })
    if (!isRetry) messages.value.push(aiMessage)
  }
  aiMessage.streaming = true
  streaming.value = true
  needsRetry.value = false
  retryMessage.value = ''
  currentTool.value = ''
  const matched = welcomeCards.value.find(c => c.text === message)
  await scrollToBottom()

  if (!isRetry) retryMessage.value = message
  if (!isRetry) retryUserId.value = userId

  const baseUrl = '/api/agent/chat/stream?userId=' + userId + '&message=' + encodeURIComponent(message)
  const url = getSseUrl(baseUrl)  // ★ 追加 &token=xxx（SSE 不能设 header）
  eventSource = new EventSource(url)

  // ★ SSE 结构化事件处理（token / tool-start / tool-end / done / error）
  eventSource.onmessage = function (event) {
    let evt: { type: string; content?: string; toolName?: string; args?: string; result?: string; message?: string }
    try {
      evt = JSON.parse(event.data)
    } catch {
      return // 忽略无法解析的数据
    }

    switch (evt.type) {
      case 'token':
        aiMessage.content += (evt.content || '')
        scrollToBottom()
        break
      case 'tool-start':
        currentTool.value = evt.toolName || ''
        break
      case 'tool-end':
        currentTool.value = ''
        break
      case 'done':
        aiMessage.streaming = false
        ;(aiMessage as any).stats = {
          inputTokens: (evt as any).inputTokens || 0,
          outputTokens: (evt as any).outputTokens || 0,
          totalTokens: (evt as any).totalTokens || 0,
          toolCallCount: (evt as any).toolCallCount || 0,
          firstTokenMs: (evt as any).firstTokenMs || 0,
          ragRecallCount: (evt as any).ragRecallCount || 0,
          ragLatencyMs: (evt as any).ragLatencyMs || 0,
          toolSuccess: (evt as any).toolSuccess || 0,
          toolFail: (evt as any).toolFail || 0,
          model: (evt as any).model || activeModelLabel.value
        }
        streaming.value = false
        backendOnline.value = true
        currentTool.value = ''
        // ★ 模型切换提示：若本次响应携带的模型名与当前展示不一致，弹提示并同步徽标
        if ((evt as any).model && (evt as any).model !== activeModelLabel.value) {
          activeModelLabel.value = (evt as any).model
          const opt = modelOptions.value.find(o => o.label === (evt as any).model)
          if (opt) activeModelKey.value = opt.key
          showToast('已切换模型：' + (evt as any).model + ' · ReAct')
        }
        // ★ 结论后自动收缩中间步骤：有结论+中间步骤的消息默认收起
        const msgIdx = messages.value.length - 1
        if (hasCollapsibleSections(aiMessage.content)) {
          collapsedMessages.value = new Set([...collapsedMessages.value, msgIdx])
        }
        archiveCurrentSession()
        eventSource?.close()
        eventSource = null
        break
      case 'error':
        aiMessage.content += '\n\n> ⚠️ ' + (evt.message || '')
        break
    }
  }

  eventSource.onerror = function () {
    if (aiMessage.streaming) {
      // 连接异常断开 → 显示重试按钮
      streaming.value = false
      currentTool.value = ''
      needsRetry.value = true
      aiMessage.streaming = false
      if (aiMessage.content === '') {
        aiMessage.content = '> ⚠️ 连接已断开，未收到任何响应。\n> 请检查：① 后端是否运行在 localhost:8080；② 浏览器控制台是否有 CORS / 401 报错；③ 当前账号是否有 agent:chat 权限。'
        backendOnline.value = false
        needsRetry.value = false
      }
    }
    eventSource?.close()
    eventSource = null
  }
}

/** 重试：用之前的问题重新连接 */
function retrySend() {
  if (!retryMessage.value) return
  // 移除最后一条 assistant 消息（如果有的话，保留前缀内容）
  const last = messages.value[messages.value.length - 1]
  if (last && last.role === 'assistant') {
    last.content = last.content.replace(/\n\n> ⚠️ 连接已断开.*$/, '')
  }
  sendMessage(retryUserId.value ? retryMessage.value : retryMessage.value, true)
}

// 停止生成
function stopGeneration() {
  if (eventSource) {
    eventSource.close()
    eventSource = null
    const last = messages.value[messages.value.length - 1]
    if (last && last.streaming) {
      last.content += '\n\n> ⏹ 已停止生成'
      last.streaming = false
    }
    streaming.value = false
    currentTool.value = ''
    needsRetry.value = false
    archiveCurrentSession()
    showToast('已停止生成')
  }
}

function handleSend() {
  if (inputText.value.trim()) {
    sendMessage(inputText.value)
  }
}

async function scrollToBottom() {
  // ⚠️ 关键：用 requestAnimationFrame 代替 nextTick
  // nextTick() 是微任务，浏览器在同一帧内执行完所有微任务才绘制，
  // 导致 for 循环里 100 个 token 的 100 次 nextTick 全在同一帧完成 → 一股脑全出
  // requestAnimationFrame 是宏任务，每次调用等一帧（约16ms），
  // 强制浏览器在两个 token 之间绘制一次 → 逐字流式效果
  await new Promise(resolve => requestAnimationFrame(resolve))
  if (messagesRef.value) {
    messagesRef.value.scrollTop = messagesRef.value.scrollHeight
  }
}

// ========== SOP 文档弹窗 ==========
const sopModalVisible = ref(false)
const sopModalTitle = ref('')
const sopModalContent = ref('')
const sopLoading = ref(false)

/**
 * 拦截 markdown-body 内 .md 链接的点击
 * markdown-it 的 linkify 会把 xxx.md 识别成链接（.md 被当作 TLD）
 * 这里拦截点击，阻止浏览器跳转 404，改为弹窗显示文档内容
 */
function handleDocClick(e: MouseEvent) {
  const target = e.target as HTMLElement
  // 向上找最近的 a 标签
  const a = target.closest('a') as HTMLAnchorElement | null
  if (!a) return
  const href = a.getAttribute('href') || ''
  // 只处理 .md 结尾的链接
  if (!href.endsWith('.md')) return
  e.preventDefault()
  // 提取文件名（去掉路径前缀，只留文件名）
  const docName = href.split('/').pop() || href
  openSopDoc(docName)
}

/**
 * 打开 SOP 文档弹窗
 * 调用后端 /api/sop/{docName} 获取文档全文
 */
async function openSopDoc(docName: string) {
  sopModalVisible.value = true
  sopModalTitle.value = docName
  sopModalContent.value = ''
  sopLoading.value = true

  try {
    const res = await apiFetch('/api/sop/' + encodeURIComponent(docName))
    if (!res.ok) throw new Error('HTTP ' + res.status)
    const text = await res.text()
    if (text.startsWith('[错误]')) {
      sopModalContent.value = '> ⚠️ ' + text
    } else {
      sopModalContent.value = text
    }
  } catch (err) {
    sopModalContent.value = '> ⚠️ 文档加载失败: ' + (err as Error).message
  } finally {
    sopLoading.value = false
  }
}

function closeSop() {
  sopModalVisible.value = false
}

// 按 ESC 关闭弹窗
function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape' && sopModalVisible.value) closeSop()
}

// ========== 告警列表 ==========

/**
 * 是否有权查看告警列表 — 基于登录用户的 alarm:read 权限动态判断
 *
 * 设计：
 *   - 后端 AlarmController 已用 @PreAuthorize("hasAuthority('alarm:read')") 拦截无权限请求（403）
 *   - 前端在此做"前置判定"：无权限直接不调用 API，避免 403 被吞成静默空白
 *   - 后端 DataPermissionInterceptor 已对 mock_alarm 表按 tenant_id/dept_id 隔离
 *     → 有权限的用户也只会看到自己租户/部门的告警（admin 等 SUPER_ADMIN 看全部）
 *
 * 这样不同角色登录后左侧告警区会"动态展示"：
 *   - admin / ops_eng（有 alarm:read）→ 展示告警列表（按租户隔离）
 *   - finance / ops_viewer（无 alarm:read）→ 展示"无权限"占位，不调用 API
 */
const canViewAlarms = computed(() => {
  const user = currentUser.value
  if (!user || !user.permissions) return false
  return user.permissions.some(p => p.code === 'alarm:read')
})

interface Alarm {
  alertId: string
  resourceId: string
  resourceType: string
  severity: string
  msg: string
  status: string
  triggerTime: string
}
const alarms = ref<Alarm[]>([])
const alarmsLoading = ref(false)

async function fetchAlarms() {
  // 防御：无 alarm:read 权限不调用接口（避免 403 被吞成静默空白）
  if (!canViewAlarms.value) return
  alarmsLoading.value = true
  try {
    const res = await apiFetch('/api/alarms?limit=10')
    if (!res.ok) throw new Error('HTTP ' + res.status)
    const data = await res.json()
    alarms.value = (data.alarms || []).sort((a: Alarm, b: Alarm) => {
      // 严重级别优先：CRITICAL > WARNING > INFO（兜底排序，与后端一致）
      const order: Record<string, number> = { CRITICAL: 0, WARNING: 1, INFO: 2 }
      const pa = order[(a.severity || '').toUpperCase()] ?? 99
      const pb = order[(b.severity || '').toUpperCase()] ?? 99
      if (pa !== pb) return pa - pb
      // 同级别按时间倒序
      return new Date(b.triggerTime).getTime() - new Date(a.triggerTime).getTime()
    })
  } catch (err) {
    console.error('告警加载失败:', err)
  } finally {
    alarmsLoading.value = false
  }
}

/**
 * 一键排障：点击告警卡片上的"排障"按钮
 * 自动构造排障消息发给 Agent
 */
function startTroubleshoot(alarm: Alarm) {
  const message = `${alarm.resourceId} 出现告警：${alarm.msg}，请帮我排查根因并给出处置建议`
  sendMessage(message)
}

/**
 * T29: 下载排障报告
 *
 * 把对话中的 ReAct 推理过程（思考 → 工具调用 → 观察结果）+ 结论
 * 拼装成结构化 Markdown 文件，通过 Blob 触发浏览器下载。
 *
 * 纯前端实现，不依赖后端接口。
 */
function downloadReport(content: string, index: number) {
  const sections = parseReActSections(content)
  const now = new Date()
  const ts = now.toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' })

  let md = `# 排障报告\n\n`
  md += `> 生成时间：${ts}\n`
  md += `> 生成工具：云运维智能助手 (ReAct Agent)\n\n`
  md += `---\n\n`

  // 找到对应的用户提问（assistant 消息的前一条通常是 user）
  const userMsg = index > 0 ? messages.value[index - 1] : null
  if (userMsg && userMsg.role === 'user') {
    md += `## 问题描述\n\n${userMsg.content}\n\n---\n\n`
  }

  // ★ 仅提取排障结论，过滤掉所有 ReAct 推理链中间步骤
  //   （思考/工具调用/观察结果 属于 Agent 内部推理过程，不写入最终报告）
  const conclusionParts: string[] = []
  for (const section of sections) {
    if (section.type === 'thinking' || section.type === 'tool' || section.type === 'observation') {
      continue  // 跳过推理链中间步骤
    }
    // conclusion / text 类型 → 排障结论
    if (section.content.trim()) {
      conclusionParts.push(section.content.trim())
    }
  }

  // 拼接所有结论片段
  let conclusionText = conclusionParts.join('\n\n')

  // 兜底：如果存在 ReAct 标记但没解析出结论内容（解析异常），
  //       或者完全没有 ReAct 标记，则用原始 content 作为结论
  if (!conclusionText) {
    conclusionText = content.trim()
  }

  md += `## 排障结论\n\n${conclusionText}\n\n`
  md += `---\n\n*本报告由云运维智能助手自动生成*\n`

  // 触发下载
  const blob = new Blob([md], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `排障报告_${now.toISOString().slice(0, 10)}_${now.getHours()}${String(now.getMinutes()).padStart(2, '0')}.md`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
  showToast('报告已下载')
}

onMounted(async () => {
  // ★ 初始化主题
  applyTheme(themeMode.value)

  // 全局键盘事件
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('keydown', onGlobalShortcut)
  // 已登录且有 alarm:read 权限才拉取告警列表（未登录时 LoginModal 成功后会按权限自动拉取）
  if (isLoggedIn() && canViewAlarms.value) fetchAlarms()
  if (isLoggedIn()) fetchModelInfo()
  // 后端健康检查（actuator 路径 + 定时轮询）
  async function checkHealth() {
    try {
      const res = await fetch('/actuator/health')
      backendOnline.value = res.ok
    } catch {
      backendOnline.value = false
    }
  }
  checkHealth()
  setInterval(checkHealth, 15000) // 每15秒自动检测
})

/** 全局快捷键：Ctrl+K 新建对话 · Ctrl+/ 聚焦输入框 · Ctrl+Enter 发送 */
function onGlobalShortcut(e: KeyboardEvent) {
  const target = e.target as HTMLElement
  const isInput = target.tagName === 'TEXTAREA' || target.tagName === 'INPUT'
  // Ctrl+K：新建对话
  if (e.ctrlKey && e.key === 'k') {
    e.preventDefault()
    newChat()
  }
  // Ctrl+/：聚焦输入框
  if (e.ctrlKey && e.key === '/') {
    e.preventDefault()
    const el = document.querySelector('.input-wrapper textarea') as HTMLTextAreaElement | null
    el?.focus()
  }
  // Ctrl+Enter：发送（当焦点在输入框时）
  if (e.ctrlKey && e.key === 'Enter' && isInput) {
    e.preventDefault()
    handleSend()
  }
}
</script>

<style scoped>
/* 组件级样式：textarea 自适应 */
.input-wrapper textarea {
  width: 100%;
}

/* ========== 主题切换按钮 ========== */
.theme-btn {
  width: 34px; height: 34px;
  border-radius: var(--radius-sm, 8px);
  background: transparent;
  border: 1px solid var(--border, rgba(255,255,255,0.08));
  display: flex; align-items: center; justify-content: center;
  font-size: 16px;
  cursor: pointer;
  transition: all 0.2s;
  flex-shrink: 0;
}
.theme-btn:hover {
  background: var(--bg-hover, rgba(255,255,255,0.06));
  border-color: var(--glass-border-strong, rgba(255,255,255,0.14));
  transform: scale(1.05);
}

/* ========== 模型切换下拉（管理员） ========== */
.model-select {
  height: 30px;
  border-radius: var(--radius-sm, 8px);
  background: var(--bg-hover, rgba(255, 255, 255, 0.06));
  border: 1px solid var(--border, rgba(255, 255, 255, 0.08));
  color: var(--text-secondary, #c7ccde);
  font-size: 12px;
  padding: 0 6px;
  cursor: pointer;
  outline: none;
  flex-shrink: 0;
}
.model-select:hover:not(:disabled) {
  border-color: var(--glass-border-strong, rgba(255, 255, 255, 0.14));
}
.model-select:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* ========== 后端连接状态（header 行内） ========== */
.conn-status {
  display: inline-block;
  width: 8px; height: 8px;
  border-radius: 50%;
  background: #6b7088;
  flex-shrink: 0;
  margin-left: 10px;
  margin-right: 4px;
  transition: background 0.3s;
}
.conn-status.online {
  background: #22c55e;
  box-shadow: 0 0 6px rgba(34,197,94,0.4);
}

/* ========== 消息计数（未登录时显示） ========== */
.msg-count {
  font-size: 12px;
  color: var(--text-tertiary, #6b7088);
}

/* ========== 响应统计面板 ========== */
.stats-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 6px;
  font-size: 13px;
  color: #94a3b8;
  user-select: none;
}
.stats-item {
  white-space: nowrap;
}
.stats-divider {
  font-size: 9px;
  opacity: 0.5;
}

.section-header h3 {
  font-size: 14px;
  margin: 0;
}
.section-header h3 .alarm-count-badge {
  font-size: 13px;
}

/* ========== 结论后收缩：中间步骤折叠区 ========== */
.react-collapse-trigger {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 14px;
  margin-bottom: 8px;
  border-radius: 20px;
  background: rgba(99, 102, 241, 0.08);
  border: 1px solid rgba(99, 102, 241, 0.18);
  color: #6366f1;
  cursor: pointer;
  user-select: none;
  font-size: 13px;
  transition: all 0.25s ease;
  line-height: 1.4;
}
.react-collapse-trigger:hover {
  background: rgba(99, 102, 241, 0.14);
  border-color: rgba(99, 102, 241, 0.35);
}
.react-collapse-trigger .collapse-gem {
  font-size: 14px;
  letter-spacing: -1px;
}
.react-collapse-trigger .collapse-label {
  font-weight: 500;
}
.react-collapse-trigger .collapse-count {
  color: #94a3b8;
  font-size: 12px;
}
.react-collapse-trigger .collapse-arrow {
  transition: transform 0.2s ease;
  font-size: 11px;
  margin-left: 2px;
}
.react-collapse-trigger.expanded .collapse-arrow {
  transform: rotate(0deg); /* ▾ already points down */
}

/* 折叠内容区：展开时有动画 */
.react-collapse-body {
  overflow: hidden;
}
</style>
