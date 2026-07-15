<template>
  <div class="chat-view">
    <div ref="messagesRef" class="messages">
      <!-- 欢迎页 -->
      <div v-if="chatState.messages.length === 0" class="welcome">
        <div class="welcome-icon">⚡</div>
        <h2>智能排障对话</h2>
        <p>选择模式 → 输入运维问题 → Agent自主调用工具排障</p>
        <div class="examples">
          <button class="example-card" @click="send('ecs-001 的 CPU 高怎么办？')">
            <div class="title"><span class="icon">🔧</span>CPU 排障</div>
            <div class="desc">从告警到SOP的完整排障</div>
          </button>
          <button class="example-card" @click="send('gpu-002 成本优化建议')">
            <div class="title"><span class="icon">💰</span>成本优化</div>
            <div class="desc">基于账单给出优化建议</div>
          </button>
        </div>
      </div>

      <!-- 消息列表 -->
      <div v-for="(msg, index) in chatState.messages" :key="msg.id" :class="msg.role" class="message">
        <div class="avatar">{{ msg.role === 'user' ? '👤' : '🤖' }}</div>
        <div class="message-content">
          <div class="message-role">
            <span>{{ msg.role === 'user' ? '我' : '运维助手' }}</span>
            <span v-if="msg.mode" class="mode-tag-small">{{ msg.mode }}</span>
          </div>

          <!-- AI消息：ReAct卡片 + Markdown渲染 -->
          <div v-if="msg.role === 'assistant'" class="react-container">
            <!-- 流式中：混合渲染 -->
            <template v-if="msg.streaming">
              <!-- 工具执行进度 -->
              <div v-if="currentTool" class="tool-progress">
                <span class="tool-progress-icon">🔧</span>
                <span class="tool-progress-text">正在调用 <strong>{{ currentTool }}</strong></span>
                <span class="tool-progress-dots"><span>.</span><span>.</span><span>.</span></span>
              </div>
              <!-- 已闭合段落 → ReAct卡片 -->
              <template v-for="(section, sIdx) in getStreamingParsed(msg.content).completed" :key="'sc-'+sIdx">
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
                <div v-else class="markdown-body" v-html="renderMarkdown(section.content)"></div>
              </template>
              <!-- 正在生成段落 → 纯文本+光标 -->
              <div v-if="getStreamingParsed(msg.content).current" class="streaming-text">
                <span v-if="getStreamingParsed(msg.content).current!.type !== 'text'" class="streaming-label">{{ markerLabel(getStreamingParsed(msg.content).current!.type) }}</span>
                {{ getStreamingParsed(msg.content).current!.content }}<span class="streaming-cursor">|</span>
              </div>
              <div v-else class="streaming-text"><span class="streaming-cursor">|</span></div>
            </template>

            <!-- 完成/历史消息：完整渲染 -->
            <template v-else>
              <template v-for="(section, sIdx) in parseReActSections(msg.content)" :key="'done-'+sIdx">
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
                <div v-else-if="section.type === 'conclusion'" class="react-card react-card--conclusion">
                  <div class="react-card-label">📝 排障结论</div>
                  <div class="markdown-body react-card-conclusion-body" v-html="renderMarkdown(section.content)"></div>
                </div>
                <div v-else class="markdown-body" v-html="renderMarkdown(section.content)"></div>
              </template>
            </template>
          </div>

          <!-- 用户消息 -->
          <div v-else class="text-content">{{ msg.content }}</div>

          <!-- 打字指示器 -->
          <div v-if="msg.streaming" class="typing-indicator"><span></span><span></span><span></span></div>

          <!-- 下载报告按钮 -->
          <button
            v-if="msg.role === 'assistant' && !msg.streaming && msg.content && isTroubleshootReport(msg.content)"
            class="download-report-btn"
            @click="downloadReport(msg.content, index)"
          >📥 下载报告</button>

          <!-- 全链路观测统计 -->
          <div v-if="msg.role === 'assistant' && !msg.streaming && msg.stats && msg.stats.totalTokens > 0" class="stats-bar">
            <span class="stats-item">🪙 {{ msg.stats.totalTokens.toLocaleString() }} token</span>
            <span class="stats-divider">·</span>
            <span class="stats-item">🔧 {{ msg.stats.toolCallCount }} 次工具调用</span>
            <span class="stats-divider">·</span>
            <span class="stats-item">⚡ 首字 {{ msg.stats.firstTokenMs }}ms</span>
            <span class="stats-divider">·</span>
            <span class="stats-item">⏱ 总耗 {{ msg.stats.totalMs }}ms</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 输入区 -->
    <div class="input-area">
      <div class="input-wrapper">
        <!-- 模式选择器（下拉） -->
        <div class="mode-selector">
          <button :class="{ active: modeOpen }" class="mode-trigger" @click="modeOpen = !modeOpen">
            <span class="mode-icon">{{ currentModeIcon }}</span>
            <span class="mode-text">{{ currentModeLabel }}</span>
            <span class="mode-arrow">{{ modeOpen ? '▲' : '▼' }}</span>
          </button>
          <div v-if="modeOpen" class="mode-dropdown">
            <div v-for="opt in modeOptions" :key="opt.value" :class="['mode-option', { active: chatState.mode === opt.value }]" @click="selectMode(opt.value)">
              <span class="opt-icon">{{ opt.icon }}</span>
              <div class="opt-text">
                <div class="opt-label">{{ opt.label }}</div>
                <div class="opt-desc">{{ opt.desc }}</div>
              </div>
            </div>
          </div>
        </div>

        <textarea
          v-model="inputText"
          :disabled="chatState.streaming"
          placeholder="描述告警或问题… Enter 发送 · Shift+Enter 换行"
          rows="1"
          @click="modeOpen = false"
          @input="autoResize"
          @keydown.enter.exact.prevent="handleSend"
          @keydown.shift.enter.prevent="inputText += '\n'"
        ></textarea>

        <div class="input-actions">
          <button v-if="chatState.streaming" class="send-btn stop-btn" @click="stopGeneration">⏹ 停止</button>
          <button v-else :disabled="!inputText.trim()" class="send-btn" @click="handleSend">发送</button>
        </div>
      </div>
      <div class="input-hint">
        <span v-if="chatState.mode === 'auto'">🧠 智能模式：自动判断任务复杂度</span>
        <span v-else-if="chatState.mode === 'single'">⚡ 单Agent模式：ReAct推理+流式输出</span>
        <span v-else>🔄 多Agent模式：6个Agent协作分析</span>
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import {computed, onMounted, onUnmounted, ref} from 'vue'
import MarkdownIt from 'markdown-it'
import {fetchSSE} from '../utils/http'
import {getMemoryId, hasPermission} from '../utils/auth'

// === Markdown 渲染器 ===
const md = new MarkdownIt({ html: true, breaks: true, linkify: true, typographer: true })
function normalizeContent(raw: string): string {
  if (!raw) return ''
  let text = raw
  const codeBlocks: string[] = []
  text = text.replace(/```[\s\S]*?```/g, (m) => { codeBlocks.push(m); return '\x00CB' + (codeBlocks.length-1) + '\x00' })
  text = text.replace(/^(#{1,6})([^\s#])/gm, '$1 $2')
  text = text.replace(/([^\n])((#{1,6})\s)/g, '$1\n\n$2')
  text = text.replace(/^(\s*[-*+])([^\s])/gm, '$1 $2')
  text = text.replace(/^(\s*\d+\.)([^\s])/gm, '$1 $2')
  codeBlocks.forEach((b, i) => { text = text.replace('\x00CB'+i+'\x00', b) })
  return text
}
function renderMarkdown(content: string): string { return md.render(normalizeContent(content || '')) }

// === ReAct 推理过程解析 ===
type SectionType = 'thinking' | 'tool' | 'observation' | 'conclusion' | 'text'
interface ReActSection { type: SectionType; content: string }
const REACT_MARKERS: Record<string, SectionType> = {
  '【思考】': 'thinking', '【工具调用】': 'tool', '【观察】': 'observation', '【结论】': 'conclusion',
}
function parseReActSections(raw: string): ReActSection[] {
  if (!raw) return [{ type: 'text', content: '' }]
  const pattern = /【思考】|【工具调用】|【观察】|【结论】/g
  const parts = raw.split(pattern)
  const matches = raw.match(pattern) || []
  const sections: ReActSection[] = []
  if (parts[0] && parts[0].trim()) sections.push({ type: 'text', content: parts[0].trim() })
  for (let i = 0; i < matches.length; i++) {
    const content = (parts[i + 1] || '').trim()
    const type = REACT_MARKERS[matches[i]] || 'text'
    if (content || type === 'conclusion') sections.push({ type, content })
  }
  return sections.length > 0 ? sections : [{ type: 'text', content: raw }]
}
function getStreamingParsed(raw: string): { completed: ReActSection[]; current: ReActSection | null } {
  if (!raw) return { completed: [], current: null }
  const pattern = /【思考】|【工具调用】|【观察】|【结论】/g
  const parts = raw.split(pattern)
  const matches = raw.match(pattern) || []
  const completed: ReActSection[] = []
  let current: ReActSection | null = null
  if (parts[0] && parts[0].trim()) {
    if (matches.length > 0) completed.push({ type: 'text', content: parts[0].trim() })
    else { current = { type: 'text', content: parts[0] }; return { completed, current } }
  }
  for (let i = 0; i < matches.length; i++) {
    const content = (parts[i + 1] || '')
    const type = REACT_MARKERS[matches[i]] || 'text'
    if (i < matches.length - 1) { const t = content.trim(); if (t) completed.push({ type, content: t }) }
    else { if (content || type === 'conclusion') current = { type, content } }
  }
  return { completed, current }
}
function markerLabel(type: SectionType): string {
  const labels: Record<string, string> = { thinking: '🧠 思考中…', tool: '🔧 调用中…', observation: '📊 观察中…', conclusion: '📝 结论中…' }
  return labels[type] || ''
}
function isTroubleshootReport(content: string): boolean {
  if (!content) return false
  return /【思考】|【工具调用】|【观察】|【结论】/.test(content) || /## 排障结论/.test(content)
}

// === 下载报告 ===
function downloadReport(content: string, index: number) {
  const sections = parseReActSections(content)
  const now = new Date()
  const ts = now.toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' })
  let m = `# 排障报告\n\n> 生成时间：${ts}\n> 生成工具：云运维智能助手\n\n---\n\n`
  const userMsg = index > 0 ? props.chatState.messages[index - 1] : null
  if (userMsg && userMsg.role === 'user') m += `## 问题描述\n\n${userMsg.content}\n\n---\n\n`
  const conclusionParts: string[] = []
  for (const s of sections) {
    if (s.type === 'thinking' || s.type === 'tool' || s.type === 'observation') continue
    if (s.content.trim()) conclusionParts.push(s.content.trim())
  }
  m += `## 排障结论\n\n${conclusionParts.join('\n\n') || content.trim()}\n\n---\n\n*本报告由云运维智能助手自动生成*\n`
  const blob = new Blob([m], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url; a.download = `排障报告_${now.toISOString().slice(0,10)}_${now.getHours()}${String(now.getMinutes()).padStart(2,'0')}.md`
  document.body.appendChild(a); a.click(); document.body.removeChild(a); URL.revokeObjectURL(url)
}

// === 组件状态 ===
interface ChatMsg { role: string; content: string; streaming?: boolean; id: number; mode?: string; stats?: any }
interface ChatState { messages: ChatMsg[]; streaming: boolean; mode: 'auto' | 'single' | 'multi'; currentSessionId: string }
const props = defineProps<{ chatState: ChatState; workflowState: any; rightPanelOpen: boolean }>()
const emit = defineEmits<{ toggleWorkflow: []; newSession: [] }>()

const inputText = ref('')
const messagesRef = ref<HTMLElement>()
const currentTool = ref('')
const modeOpen = ref(false)
let msgIdCounter = Date.now()

const modeOptions = [
  { value: 'auto', label: '智能', desc: '自动判断任务复杂度', icon: '🧠' },
  { value: 'single', label: '单Agent', desc: 'ReAct推理+流式输出', icon: '⚡' },
  { value: 'multi', label: '多Agent', desc: '6个Agent协作分析', icon: '🔄' },
]
const currentModeLabel = computed(() => modeOptions.find(o => o.value === props.chatState.mode)?.label || '智能')
const currentModeIcon = computed(() => modeOptions.find(o => o.value === props.chatState.mode)?.icon || '🧠')
function selectMode(value: 'auto' | 'single' | 'multi') { props.chatState.mode = value; modeOpen.value = false }

function detectComplexity(text: string): 'single' | 'multi' {
  const kw = ['排障', '分析', '诊断', '排查', '告警', '为什么', '怎么回事', '怎么办', '优化', '根因', '怎么']
  return kw.some(k => text.includes(k)) ? 'multi' : 'single'
}
function resolveMode(): 'single' | 'multi' { return props.chatState.mode === 'auto' ? detectComplexity(inputText.value) : props.chatState.mode }

function autoResize() {
  const el = document.querySelector('.input-wrapper textarea') as HTMLTextAreaElement | null
  if (!el) return; el.style.height = 'auto'; el.style.height = Math.min(el.scrollHeight, 140) + 'px'
}
function handleSend() { if (inputText.value.trim()) send(inputText.value) }

async function send(text: string) {
  if (props.chatState.streaming) return
  if (!hasPermission('agent:chat')) {
    props.chatState.messages.push({ role: 'assistant', content: '> ⚠️ 权限不足：需要 agent:chat 权限', id: ++msgIdCounter }); return
  }
  const message = text.trim()
  const mode = resolveMode()
  inputText.value = ''; setTimeout(autoResize, 0)
  const userMsg: ChatMsg = { role: 'user', content: message, id: ++msgIdCounter }
  const aiMsg: ChatMsg = { role: 'assistant', content: '', streaming: true, id: ++msgIdCounter, mode: mode === 'multi' ? '多Agent' : '单Agent' }
  props.chatState.messages.push(userMsg, aiMsg)
  props.chatState.streaming = true
  currentTool.value = ''
  await scrollToBottom()

  const memoryId = getMemoryId()
  const startTime = Date.now()
  let firstTokenTime = 0

    if (mode === 'multi') {
      // 多Agent：SSE流式 — 逐阶段推送工作流进度
      emit('toggleWorkflow')
      props.workflowState.agents.forEach((a: any) => { a.status = 'waiting'; a.duration = 0 })
      props.workflowState.trace = []
      props.workflowState.routeLabel = ''
      props.workflowState.finalReport = ''
      props.workflowState.running = true

      const agentNameMap: Record<string, string> = {
        'TriageAgent': 'triage', 'AlarmAnalysisAgent': 'alarm', 'ResourceDiagnosticAgent': 'resource',
        'KnowledgeRetrievalAgent': 'knowledge', 'BillingAnalysisAgent': 'billing', 'SynthesisAgent': 'synthesis'
      }

      // 标记分诊Agent为running（首个）
      if (props.workflowState.agents[0]) props.workflowState.agents[0].status = 'running'

      await fetchSSE('/api/agent/workflow/chat/stream', { userId: memoryId, message },
        (data: string) => {
          try {
            const evt = JSON.parse(data)
            switch (evt.type) {
              case 'workflow-step': {
                const agentName = evt.agentName
                if (agentName === 'route') {
                  props.workflowState.routeLabel = evt.routeLabel || ''
                  break
                }
                const agentId = agentNameMap[agentName]
                const agent = props.workflowState.agents.find((a: any) => a.id === agentId)
                if (agent) {
                  agent.status = evt.status || 'completed'
                  agent.duration = evt.durationMs || 0
                }
                props.workflowState.trace.push({
                  agentName: agentName,
                  durationMs: evt.durationMs || 0,
                  result: ''
                })
                // 标记下一个Agent为running
                const nextIdx = props.workflowState.agents.findIndex(
                  (a: any) => a.status === 'waiting' && a.id !== agentId
                )
                if (nextIdx >= 0) props.workflowState.agents[nextIdx].status = 'running'
                break
              }
              case 'token':
                if (firstTokenTime === 0) firstTokenTime = Date.now() - startTime
                aiMsg.content += evt.content || ''
                scrollToBottom()
                break
              case 'done':
                // 标记所有未完成的Agent为skipped（包括被条件路由跳过的running状态Agent）
                props.workflowState.agents.forEach((a: any) => {
                  if (a.status === 'waiting' || a.status === 'running') a.status = 'skipped'
                })
                props.workflowState.finalReport = aiMsg.content
                props.workflowState.running = false
                aiMsg.streaming = false
                aiMsg.stats = {
                  inputTokens: evt.inputTokens || 0,
                  outputTokens: evt.outputTokens || 0,
                  totalTokens: evt.totalTokens || 0,
                  toolCallCount: evt.toolCallCount || props.workflowState.trace.length,
                  firstTokenMs: evt.firstTokenMs || firstTokenTime,
                  totalMs: evt.totalMs || (Date.now() - startTime)
                }
                break
              case 'error':
                aiMsg.content += '\n\n> ⚠️ ' + (evt.message || '')
                break
            }
          } catch { /* ignore parse errors */ }
        },
        (err: Error) => {
          if (aiMsg.content === '') aiMsg.content = '> ⚠️ 连接失败: ' + err.message
        }
      )
      aiMsg.streaming = false; props.chatState.streaming = false
      if (props.workflowState.running) props.workflowState.running = false
    } else {
    // 单Agent：SSE流式
    console.log('[Chat] 开始SSE流式请求, userId=' + memoryId)
    await fetchSSE('/api/agent/chat/stream', { userId: memoryId, message },
      (data: string) => {
        try {
          const evt = JSON.parse(data)
          switch (evt.type) {
            case 'token':
              if (firstTokenTime === 0) firstTokenTime = Date.now() - startTime
              aiMsg.content += evt.content || ''
              scrollToBottom(); break
            case 'tool-start':
              currentTool.value = evt.toolName || ''
              console.log('[Chat] 工具开始: ' + evt.toolName); break
            case 'tool-end':
              currentTool.value = ''
              console.log('[Chat] 工具完成: ' + evt.toolName); break
            case 'done':
              aiMsg.streaming = false
              aiMsg.stats = {
                inputTokens: evt.inputTokens || 0, outputTokens: evt.outputTokens || 0,
                totalTokens: evt.totalTokens || 0, toolCallCount: evt.toolCallCount || 0,
                firstTokenMs: firstTokenTime, totalMs: Date.now() - startTime
              }
              currentTool.value = ''
              console.log('[Chat] SSE完成, stats=', aiMsg.stats); break
            case 'error':
              aiMsg.content += '\n\n> ⚠️ ' + (evt.message || ''); break
          }
        } catch { /* ignore */ }
      },
      (err: Error) => { if (aiMsg.content === '') aiMsg.content = '> ⚠️ 连接失败: ' + err.message }
    )
    aiMsg.streaming = false; props.chatState.streaming = false; currentTool.value = ''
  }
  await scrollToBottom()
}

function stopGeneration() {
  const last = props.chatState.messages[props.chatState.messages.length - 1]
  if (last && last.streaming) { last.content += '\n\n> ⏹ 已停止生成'; last.streaming = false }
  props.chatState.streaming = false; currentTool.value = ''
}
async function scrollToBottom() {
  await new Promise(r => requestAnimationFrame(r))
  if (messagesRef.value) messagesRef.value.scrollTop = messagesRef.value.scrollHeight
}
function onTroubleshoot(e: Event) { const d = (e as CustomEvent).detail as string; if (d) send(d) }
onMounted(() => window.addEventListener('troubleshoot', onTroubleshoot as EventListener))
onUnmounted(() => window.removeEventListener('troubleshoot', onTroubleshoot as EventListener))
</script>

<style scoped>
.chat-view { display: flex; flex-direction: column; height: 100%; overflow: hidden; }
.messages { flex: 1; overflow-y: auto; padding: 20px 0; scroll-behavior: smooth; }

.welcome { text-align: center; padding: 60px 24px; max-width: 600px; margin: 0 auto; }
.welcome-icon { width: 64px; height: 64px; margin: 0 auto 16px; background: linear-gradient(135deg, #6366f1, #8b5cf6, #ec4899); border-radius: 20px; display: flex; align-items: center; justify-content: center; font-size: 32px; box-shadow: 0 8px 24px rgba(99,102,241,0.4); }
.welcome h2 { font-size: 22px; font-weight: 700; margin-bottom: 6px; color: var(--text-primary); }
.welcome p { font-size: 13px; color: var(--text-secondary); margin-bottom: 24px; }
.examples { display: grid; grid-template-columns: repeat(2, 1fr); gap: 10px; }
.example-card { padding: 12px; background: var(--bg-card); border: 1px solid var(--glass-border); border-radius: 12px; color: var(--text-primary); font-size: 13px; cursor: pointer; text-align: left; transition: all 0.2s; }
.example-card:hover { background: var(--bg-elevated); border-color: var(--accent); transform: translateY(-1px); }
.example-card .title { font-weight: 600; margin-bottom: 2px; }
.example-card .desc { font-size: 11px; color: var(--text-tertiary); }
.example-card .icon { margin-right: 6px; }

.message { display: flex; gap: 12px; padding: 12px 24px; max-width: 820px; margin: 0 auto; }
.message.user { flex-direction: row-reverse; }
.avatar { width: 32px; height: 32px; border-radius: 10px; display: flex; align-items: center; justify-content: center; font-size: 16px; flex-shrink: 0; background: var(--bg-card); border: 1px solid var(--glass-border); }
.message.user .avatar { background: var(--user-bubble); }
.message-content { max-width: calc(100% - 44px); min-width: 0; }
.message-role { font-size: 11px; color: var(--text-tertiary); margin-bottom: 4px; display: flex; align-items: center; gap: 6px; }
.message.user .message-role { text-align: right; }
.mode-tag-small { font-size: 9px; padding: 1px 5px; border-radius: 3px; background: var(--accent-soft); color: var(--accent-hover); }
.text-content { display: inline-block; padding: 9px 12px; background: var(--user-bubble); border-radius: 12px 12px 4px 12px; color: white; font-size: 13px; }

/* ReAct 卡片 */
.react-container { display: flex; flex-direction: column; gap: 8px; width: 100%; }
.react-card { position: relative; padding: 10px 14px; border-radius: 8px; border-left: 3px solid; font-size: 13px; line-height: 1.6; overflow: hidden; transition: all 0.3s; }
.react-card-label { display: inline-flex; align-items: center; gap: 4px; font-size: 11px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.5px; opacity: 0.8; margin-bottom: 4px; }
.react-card-content { color: var(--text-secondary); word-break: break-word; }
.react-code { font-family: 'SF Mono', 'Fira Code', monospace; font-size: 12px; color: var(--text-primary); background: none; padding: 0; }
.react-card--thinking { background: var(--thought-bg); border-left-color: #3b82f6; }
.react-card--thinking .react-card-label { color: #60a5fa; }
.react-card--tool { background: var(--action-bg); border-left-color: #a855f7; }
.react-card--tool .react-card-label { color: #c084fc; }
.react-card--observation { background: var(--observation-bg); border-left-color: #22c55e; }
.react-card--observation .react-card-label { color: #4ade80; }
.react-card--conclusion { background: var(--conclusion-bg, rgba(34,197,94,0.05)); border-left-color: #16a34a; border-left-width: 4px; }
.react-card--conclusion .react-card-label { color: #22c55e; font-weight: 700; }
.react-card-conclusion-body { margin-top: 8px; padding: 0; background: none !important; border: none !important; }
.react-container .markdown-body { margin-top: 4px; padding: 12px 16px; background: var(--ai-bubble); border: 1px solid var(--glass-border); border-left: 3px solid var(--accent); border-radius: 12px 12px 12px 4px; }
.markdown-body { line-height: 1.75; font-size: 14px; word-break: break-word; }
.markdown-body h2 { font-size: 17px; margin: 16px 0 8px; font-weight: 600; }
.markdown-body h3 { font-size: 15px; margin: 12px 0 6px; font-weight: 600; }
.markdown-body p { margin: 8px 0; }
.markdown-body strong { font-weight: 600; }
.markdown-body ul, .markdown-body ol { padding-left: 22px; margin: 8px 0; }
.markdown-body li { margin: 4px 0; }
.markdown-body code { background: var(--code-bg); padding: 2px 6px; border-radius: 4px; font-size: 12px; }
.markdown-body pre { background: var(--code-bg); padding: 14px; border-radius: 8px; overflow-x: auto; margin: 12px 0; }
.markdown-body blockquote { border-left: 3px solid var(--accent); padding: 8px 14px; margin: 10px 0; background: var(--accent-soft); border-radius: 0 6px 6px 0; color: var(--text-secondary); }

/* 流式输出 */
.streaming-text { font-size: 14px; line-height: 1.7; color: var(--text-primary); white-space: pre-wrap; word-break: break-word; }
.streaming-cursor { display: inline-block; margin-left: 2px; animation: blink-cursor 1s step-end infinite; color: var(--accent); font-weight: 300; }
@keyframes blink-cursor { 0%,100% { opacity: 1; } 50% { opacity: 0; } }
.streaming-label { display: inline-block; font-size: 11px; font-weight: 600; color: var(--accent); opacity: 0.7; margin-right: 6px; }

/* 工具执行进度 */
.tool-progress { display: flex; align-items: center; gap: 8px; padding: 8px 14px; margin-bottom: 8px; background: var(--action-bg); border: 1px solid var(--action-border); border-radius: 8px; font-size: 13px; }
.tool-progress-text { color: var(--text-secondary); }
.tool-progress-text strong { color: #c084fc; font-weight: 600; }
.tool-progress-dots span { display: inline-block; animation: toolDot 1.2s infinite; color: #c084fc; font-weight: 700; }
.tool-progress-dots span:nth-child(2) { animation-delay: 0.2s; }
.tool-progress-dots span:nth-child(3) { animation-delay: 0.4s; }
@keyframes toolDot { 0%,60%,100% { opacity: 0.2; } 30% { opacity: 1; } }

/* 打字指示器 */
.typing-indicator { display: inline-flex; gap: 5px; padding: 6px 0; }
.typing-indicator span { width: 6px; height: 6px; border-radius: 50%; background: var(--accent); animation: blink 1.4s infinite; }
.typing-indicator span:nth-child(2) { animation-delay: 0.2s; }
.typing-indicator span:nth-child(3) { animation-delay: 0.4s; }
@keyframes blink { 0%,60%,100% { opacity: 0.3; transform: scale(0.8); } 30% { opacity: 1; transform: scale(1.1); } }

/* 下载报告按钮 */
.download-report-btn { display: inline-flex; align-items: center; gap: 6px; margin-top: 10px; padding: 6px 14px; background: transparent; border: 1px solid var(--glass-border); border-radius: 8px; color: var(--text-secondary); font-size: 12px; cursor: pointer; transition: all 0.2s; }
.download-report-btn:hover { background: var(--accent-soft); border-color: var(--accent); color: var(--accent-hover); transform: translateY(-1px); }

/* 统计栏 */
.stats-bar { display: flex; align-items: center; gap: 6px; margin-top: 8px; font-size: 12px; color: var(--text-tertiary); user-select: none; flex-wrap: wrap; }
.stats-item { white-space: nowrap; }
.stats-divider { font-size: 9px; opacity: 0.5; }

/* 输入区 */
.input-area { padding: 12px 20px 16px; flex-shrink: 0; background: var(--bg-elevated); border-top: 1px solid var(--glass-border); }
.input-wrapper { max-width: 820px; margin: 0 auto; display: flex; align-items: flex-end; gap: 8px; background: var(--bg-input); border: 1px solid var(--glass-border); border-radius: 16px; padding: 6px; position: relative; }
.input-wrapper:focus-within { border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-soft); }
.mode-selector { position: relative; flex-shrink: 0; }
.mode-trigger { display: flex; align-items: center; gap: 4px; padding: 6px 10px; background: var(--bg-card); border: 1px solid var(--glass-border); border-radius: 8px; color: var(--text-primary); font-size: 12px; cursor: pointer; height: 36px; white-space: nowrap; }
.mode-trigger:hover, .mode-trigger.active { background: var(--bg-hover); border-color: var(--accent); }
.mode-icon { font-size: 13px; }
.mode-arrow { font-size: 8px; color: var(--text-tertiary); }
.mode-dropdown { position: absolute; bottom: calc(100% + 6px); left: 0; width: 240px; background: var(--bg-elevated); border: 1px solid var(--glass-border-strong); border-radius: 12px; box-shadow: 0 8px 24px rgba(0,0,0,0.15); overflow: hidden; z-index: 100; }
.mode-option { display: flex; align-items: flex-start; gap: 8px; padding: 10px 12px; cursor: pointer; border-bottom: 1px solid var(--glass-border); }
.mode-option:last-child { border-bottom: none; }
.mode-option:hover { background: var(--bg-hover); }
.mode-option.active { background: var(--accent-soft); }
.opt-icon { font-size: 16px; }
.opt-label { font-size: 12px; font-weight: 600; color: var(--text-primary); }
.opt-desc { font-size: 10px; color: var(--text-tertiary); margin-top: 2px; }
.input-wrapper textarea { flex: 1; background: transparent; border: none; outline: none; color: var(--text-primary); font-size: 13px; resize: none; font-family: inherit; max-height: 140px; min-height: 36px; padding: 8px 6px; line-height: 1.5; }
.input-wrapper textarea::placeholder { color: var(--text-tertiary); }
.input-actions { display: flex; align-items: center; padding: 0 4px; }
.send-btn { padding: 7px 16px; background: var(--user-bubble); border: none; border-radius: 8px; color: white; font-size: 12px; font-weight: 600; cursor: pointer; box-shadow: 0 4px 12px rgba(99,102,241,0.3); height: 36px; }
.send-btn:hover:not(:disabled) { transform: translateY(-1px); }
.send-btn:disabled { opacity: 0.4; }
.send-btn.stop-btn { background: linear-gradient(135deg, #ef4444, #f59e0b); }
.input-hint { text-align: center; font-size: 10px; color: var(--text-tertiary); margin-top: 6px; }

@media (max-width: 768px) { .message { padding: 10px 16px; } .input-area { padding: 10px 12px; } }
</style>
