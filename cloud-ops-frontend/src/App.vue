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
      <div class="sidebar-section sidebar-section--alarms">
        <div class="section-header">
          <h3>实时告警 <span v-if="alarms.length > 0" class="alarm-count-badge">{{ alarms.length }}</span></h3>
          <button :disabled="streaming" class="refresh-btn" title="刷新告警" @click="fetchAlarms">↻</button>
        </div>
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

      <!-- 快捷操作 -->
      <div class="sidebar-section">
        <div class="section-header section-header--collapsible" @click="toggleQuickActions">
          <h3>快捷操作</h3>
          <span :class="{ expanded: isQuickActionsExpanded }" class="collapse-icon">▶</span>
        </div>
        <div v-show="isQuickActionsExpanded" class="tools-content">
          <button
            v-for="item in quickActions"
            :key="item.text"
            :class="{ active: lastQuickAction === item.text }"
            :disabled="streaming"
            class="quick-btn"
            @click="sendMessage(item.text)"
          >
            <span class="icon">{{ item.icon }}</span><span>{{ item.label }}</span>
          </button>
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

      <div class="sidebar-section sidebar-section--tools">
        <div class="section-header section-header--collapsible" @click="toggleTools">
          <h3>工具能力</h3>
          <span :class="{ expanded: isToolsExpanded }" class="collapse-icon">▶</span>
        </div>
        <div v-show="isToolsExpanded" class="tools-content">
          <button class="quick-btn" disabled style="opacity:.5;cursor:default">
            <span class="icon">🔔</span><span>告警查询</span><span class="badge">Tool</span>
          </button>
          <button class="quick-btn" disabled style="opacity:.5;cursor:default">
            <span class="icon">🕸️</span><span>资源拓扑</span><span class="badge">Tool</span>
          </button>
          <button class="quick-btn" disabled style="opacity:.5;cursor:default">
            <span class="icon">📊</span><span>负载分析</span><span class="badge">Tool</span>
          </button>
          <button class="quick-btn" disabled style="opacity:.5;cursor:default">
            <span class="icon">🧾</span><span>账单查询</span><span class="badge">Tool</span>
          </button>
          <button class="quick-btn" disabled style="opacity:.5;cursor:default">
            <span class="icon">📚</span><span>SOP 检索</span><span class="badge">RAG</span>
          </button>
        </div>
      </div>

      <div class="sidebar-footer">
        <div class="status-row">
          <span :class="{ online: backendOnline }" class="status-dot"></span>
          <span>{{ backendOnline ? '后端已连接' : '正在连接后端...' }}</span>
        </div>
        <div style="margin-top:6px;opacity:.6">userId: {{ userId }}</div>
      </div>
    </aside>

    <!-- ==================== 右侧主区 ==================== -->
    <main class="main">
      <header class="main-header">
        <div class="left">
          <span class="left-title">智能排障对话</span>
          <span class="model-tag">DeepSeek · ReAct</span>
        </div>
        <div class="right">
          <span>{{ messages.length }} 条消息</span>
        </div>
      </header>

      <div ref="messagesRef" class="messages">
        <!-- 欢迎页 -->
        <div v-if="messages.length === 0" class="welcome">
          <div class="welcome-icon">⚡</div>
          <h2>云运维智能助手</h2>
          <p>基于 ReAct 的云资源排障 Agent，支持告警查询、资源拓扑、负载分析、SOP 检索</p>
          <div class="examples">
            <button class="example-card" @click="sendMessage('有哪些告警？')">
              <div class="title"><span class="icon">🚨</span>查看告警</div>
              <div class="desc">查询当前未处理告警列表</div>
            </button>
            <button class="example-card" @click="sendMessage('ecs-001 的 CPU 高怎么办？')">
              <div class="title"><span class="icon">🔧</span>CPU 排障</div>
              <div class="desc">从告警到 SOP 的完整排障</div>
            </button>
            <button class="example-card" @click="sendMessage('ebm-001 是不是内存泄漏？')">
              <div class="title"><span class="icon">🧠</span>内存排查</div>
              <div class="desc">分析内存指标定位泄漏</div>
            </button>
            <button class="example-card" @click="sendMessage('gpu-002 成本优化建议')">
              <div class="title"><span class="icon">💰</span>成本优化</div>
              <div class="desc">基于账单给出优化建议</div>
            </button>
          </div>
        </div>

        <!-- 消息列表 -->
        <div v-for="(msg, index) in messages" :key="index" :class="msg.role" class="message">
          <div class="avatar">{{ msg.role === 'user' ? '👤' : '🤖' }}</div>
          <div class="message-content">
            <div class="message-role">{{ msg.role === 'user' ? '我' : '运维助手' }}</div>
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
              <span class="stats-divider">·</span>
              <span class="stats-item">⚡ 首字 {{ (msg as any).stats.firstTokenMs }}ms</span>
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
  </div>
</template>

<script lang="ts" setup>
import {nextTick, onMounted, reactive, ref} from 'vue'
import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js'

const inputText = ref('')
const messages = ref<Array<{ role: string; content: string; streaming?: boolean }>>([])
const streaming = ref(false)
const backendOnline = ref(false)
const messagesRef = ref<HTMLElement>()
const lastQuickAction = ref('')
const toast = ref('')
// 工具执行进度：排障过程透明化
const currentTool = ref('')
// 流式连接断开后的重试信息
const needsRetry = ref(false)
const retryMessage = ref('')
const retryUserId = ref('')

// 工具能力折叠面板状态（默认收起）
const isToolsExpanded = ref(false)
function toggleTools() { isToolsExpanded.value = !isToolsExpanded.value }

// 快捷操作折叠：默认折叠，腾出空间给告警区域
const isQuickActionsExpanded = ref(false)
function toggleQuickActions() { isQuickActionsExpanded.value = !isQuickActionsExpanded.value }

// 历史对话折叠（默认展开）
const isHistoryExpanded = ref(true)
function toggleHistory() { isHistoryExpanded.value = !isHistoryExpanded.value }

const userId = 'web-user-' + Math.random().toString(36).substring(2, 8)
// SSE 连接：EventSource 直连后端
let eventSource: EventSource | null = null

const quickActions = [
  { icon: '🚨', label: '查看告警', text: '有哪些告警？' },
  { icon: '🔧', label: 'CPU 排障', text: 'ecs-001 的 CPU 高怎么办？' },
  { icon: '🧠', label: '内存排查', text: 'ebm-001 是不是内存泄漏？' },
  { icon: '💰', label: '成本优化', text: 'gpu-002 成本优化建议' }
]

// ========== 对话历史管理（localStorage 持久化） ==========
interface ChatSession {
  id: string
  title: string
  messages: Array<{ role: string; content: string; streaming?: boolean }>
  createdAt: number
}
const STORAGE_KEY = 'cloud-ops-sessions'
const currentSessionId = ref('')
const sessions = ref<ChatSession[]>(loadSessions())

function loadSessions(): ChatSession[] {
  try {
    return JSON.parse(localStorage.getItem(STORAGE_KEY) || '[]')
  } catch { return [] }
}
function saveSessions() {
  // 只保留 messages 的基本信息（strip streaming flag）
  const clean = sessions.value.map(s => ({
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
  const existing = sessions.value.find(s => s.id === currentSessionId.value)
  if (existing) {
    existing.messages = [...messages.value]
    existing.title = title
  } else {
    currentSessionId.value = 'sess-' + Date.now()
    sessions.value.unshift({
      id: currentSessionId.value,
      title,
      messages: [...messages.value],
      createdAt: Date.now()
    })
  }
  // 最多保留 20 条历史
  if (sessions.value.length > 20) sessions.value = sessions.value.slice(0, 20)
  saveSessions()
}
function switchSession(id: string) {
  if (streaming.value) return
  archiveCurrentSession()
  const s = sessions.value.find(x => x.id === id)
  if (s) {
    currentSessionId.value = s.id
    messages.value = s.messages.map(m => ({ ...m, streaming: false }))
  }
}
function deleteSession(id: string) {
  sessions.value = sessions.value.filter(s => s.id !== id)
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
  return sections.length > 0 ? sections : [{ type: 'text', content: raw }]
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
  lastQuickAction.value = ''
  inputText.value = ''
  needsRetry.value = false
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
  const matched = quickActions.find(a => a.text === message)
  lastQuickAction.value = matched ? matched.text : ''
  await scrollToBottom()

  if (!isRetry) retryMessage.value = message
  if (!isRetry) retryUserId.value = userId

  const url = 'http://localhost:8080/api/agent/chat/stream?userId=' + userId + '&message=' + encodeURIComponent(message)
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
        ;(aiMessage as any).stats = { inputTokens: (evt as any).inputTokens || 0, outputTokens: (evt as any).outputTokens || 0, totalTokens: (evt as any).totalTokens || 0, toolCallCount: (evt as any).toolCallCount || 0, firstTokenMs: (evt as any).firstTokenMs || 0 }
        streaming.value = false
        backendOnline.value = true
        currentTool.value = ''
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
        aiMessage.content = '> ⚠️ 连接失败，请确认后端服务已启动（localhost:8080）'
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
    const res = await fetch('/api/sop/' + encodeURIComponent(docName))
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
  alarmsLoading.value = true
  try {
    const res = await fetch('/api/alarms?limit=10')
    if (!res.ok) throw new Error('HTTP ' + res.status)
    const data = await res.json()
    alarms.value = data.alarms || []
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
  // 全局键盘事件
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('keydown', onGlobalShortcut)
  // 拉取告警列表
  fetchAlarms()
  try {
    const res = await fetch('/health')
    if (res.ok) backendOnline.value = true
  } catch {
    backendOnline.value = false
  }
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

/* ========== 响应统计面板 ========== */
.stats-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 6px;
  font-size: 11px;
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
</style>
