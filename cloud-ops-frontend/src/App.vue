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

      <div class="sidebar-section sidebar-section--alarms">
        <div class="section-header">
          <h3>实时告警</h3>
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
              <!-- ★ 流式期间：轻量纯文本显示（不做 ReAct 解析 + Markdown 编译）
                   原因：每来一个 token 都全文 parse+compile 会阻塞 UI 主线程，
                         Vue 合并多次 DOM 更新 → 用户看到"攒一堆后突然全出"
                  -->
              <div v-if="msg.streaming" class="streaming-text">{{ msg.content }}<span class="streaming-cursor">|</span></div>

              <!-- ★ 完成/历史消息：完整 ReAct 卡片 + Markdown 渲染 -->
              <template v-for="(section, sIdx) in parseReActSections(msg.content)" v-else :key="sIdx">
                <!-- 思考卡片 -->
                <div v-if="section.type === 'thinking'" class="react-card react-card--thinking">
                  <div class="react-card-label">🧠 思考</div>
                  <div class="react-card-content">{{ section.content }}</div>
                </div>
                <!-- 工具调用卡片 -->
                <div v-else-if="section.type === 'tool'" class="react-card react-card--tool">
                  <div class="react-card-label">🔧 工具调用</div>
                  <code class="react-card-content react-code">{{ section.content }}</code>
                </div>
                <!-- 观察结果卡片 -->
                <div v-else-if="section.type === 'observation'" class="react-card react-card--observation">
                  <div class="react-card-label">📊 观察结果</div>
                  <div class="react-card-content">{{ section.content }}</div>
                </div>
                <!-- 结论 / 纯文本：Markdown 渲染 -->
                <div v-else class="markdown-body" @click="handleDocClick" v-html="renderMarkdown(section.content)"></div>
              </template>
            </div>
            <div v-else class="text-content">{{ msg.content }}</div>
            <div v-if="msg.streaming" class="typing-indicator"><span></span><span></span><span></span></div>
            <!-- T29: 下载报告按钮 — 仅在排障结论上显示（含 ReAct 标记的消息） -->
            <button
              v-if="msg.role === 'assistant' && !msg.streaming && msg.content && isTroubleshootReport(msg.content)"
              class="download-report-btn"
              @click="downloadReport(msg.content, index)"
            >
              📥 下载报告
            </button>
          </div>
        </div>
      </div>

      <!-- 输入区 -->
      <div class="input-area">
        <div class="input-wrapper">
          <textarea
            v-model="inputText"
            :disabled="streaming"
            placeholder="描述告警或问题，如：ecs-001 的 CPU 高怎么办？"
            rows="1"
            @input="autoResize"
            @keydown.enter.exact.prevent="handleSend"
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
// 工具能力折叠面板状态（默认收起）
const isToolsExpanded = ref(false)
function toggleTools() {
  isToolsExpanded.value = !isToolsExpanded.value
}

// 快捷操作折叠：默认折叠，腾出空间给告警区域
const isQuickActionsExpanded = ref(false)
function toggleQuickActions() {
  isQuickActionsExpanded.value = !isQuickActionsExpanded.value
}
const userId = 'web-user-' + Math.random().toString(36).substring(2, 8)
// SSE 连接：EventSource 直连后端，绕过 Vite proxy 避免 SSE 缓冲
let eventSource: EventSource | null = null

const quickActions = [
  { icon: '🚨', label: '查看告警', text: '有哪些告警？' },
  { icon: '🔧', label: 'CPU 排障', text: 'ecs-001 的 CPU 高怎么办？' },
  { icon: '🧠', label: '内存排查', text: 'ebm-001 是不是内存泄漏？' },
  { icon: '💰', label: '成本优化', text: 'gpu-002 成本优化建议' }
]

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
  if (streaming.value) {
    showToast('请等待当前回复完成')
    return
  }
  messages.value = []
  lastQuickAction.value = ''
  inputText.value = ''
  showToast('已开启新对话')
}

// textarea 自适应高度
function autoResize() {
  const el = document.querySelector('.input-wrapper textarea') as HTMLTextAreaElement | null
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 160) + 'px'
}

async function sendMessage(text: string) {
  if (streaming.value) {
    showToast('正在生成中，请稍候...')
    return
  }
  if (!text.trim()) return

  const message = text.trim()
  inputText.value = ''
  nextTick(autoResize)
  messages.value.push({ role: 'user', content: message })
  // ★ 必须用 reactive() 包装：push 进 ref 数组后，
  //   数组内存的是代理对象，但 aiMessage 变量指向原始对象，
  //   直接改原始对象的 content 属性不会触发响应式更新 → 全部 token 攒到 [DONE] 才一次性渲染
  //   用 reactive() 让 aiMessage 本身就是代理对象，改 content 立即触发视图更新
  const aiMessage = reactive({ role: 'assistant', content: '', streaming: true })
  messages.value.push(aiMessage)
  streaming.value = true
  const matched = quickActions.find(a => a.text === message)
  lastQuickAction.value = matched ? matched.text : ''
  await scrollToBottom()

  // ★ 直连后端，绕过 Vite proxy（http-proxy 会缓冲 SSE 响应）
  const url = 'http://localhost:8080/api/agent/chat/stream?userId=' + userId + '&message=' + encodeURIComponent(message)

  eventSource = new EventSource(url)

  eventSource.onmessage = function (event) {
    // 后端 JSON 编码每个 token，避免 \n 破坏 SSE 分行
    let data: string
    try {
      data = JSON.parse(event.data)
    } catch {
      data = event.data
    }
    if (data === '[DONE]') {
      aiMessage.streaming = false
      streaming.value = false
      backendOnline.value = true
      eventSource?.close()
      eventSource = null
      return
    }
    if (data.startsWith('[ERROR]')) {
      aiMessage.content += '\n\n> ⚠️ ' + data
    } else if (data.startsWith('[护轨拦截]')) {
      aiMessage.content += '> ⚠️ ' + data
    } else {
      aiMessage.content += data
    }
    scrollToBottom()
  }

  eventSource.onerror = function () {
    if (aiMessage.streaming) {
      // 连接异常断开
      if (aiMessage.content === '') {
        aiMessage.content = '> ⚠️ 连接失败，请确认后端服务已启动（localhost:8080）'
        backendOnline.value = false
      } else {
        aiMessage.content += '\n\n> ⚠️ 连接已断开'
      }
      aiMessage.streaming = false
      streaming.value = false
    }
    eventSource?.close()
    eventSource = null
  }
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
  // ESC 关闭弹窗
  window.addEventListener('keydown', onKeydown)
  // 拉取告警列表
  fetchAlarms()
  try {
    const res = await fetch('/health')
    if (res.ok) backendOnline.value = true
  } catch {
    backendOnline.value = false
  }
})
</script>

<style scoped>
/* 组件级样式：textarea 自适应 */
.input-wrapper textarea {
  width: 100%;
}
</style>
