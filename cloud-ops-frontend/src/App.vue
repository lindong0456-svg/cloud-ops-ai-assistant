<template>
  <div class="app">
    <!-- 登录页 -->
    <div v-if="!isLoggedIn" class="login-overlay">
      <div class="login-box">
        <div class="login-logo">⚡</div>
        <div class="login-title">云运维智能助手</div>
        <div class="login-sub">Agent协作监控平台</div>
        <div class="form-group">
          <label class="form-label">用户名</label>
          <input v-model="loginUser" class="form-input" placeholder="admin" type="text" @keydown.enter="focusPass">
        </div>
        <div class="form-group">
          <label class="form-label">密码</label>
          <input v-model="loginPass" class="form-input" placeholder="admin123" type="password" @keydown.enter="doLogin">
        </div>
        <button :disabled="loginLoading" class="login-btn" @click="doLogin">
          {{ loginLoading ? '登录中...' : '登录' }}
        </button>
        <div class="login-err">{{ loginErr }}</div>
        <div class="login-hint">
          Mock用户: <code>admin</code> <code>ops_eng</code> <code>finance</code><br>密码均为 <code>admin123</code>
        </div>
      </div>
    </div>

    <!-- 主应用 -->
    <template v-else>
      <!-- 左侧导航栏（宽栏：图标+文字） -->
      <aside class="sidebar-left">
        <div class="brand-area">
          <div class="brand-logo">⚡</div>
          <div class="brand-text">
            <div class="brand-title">云运维助手</div>
            <div class="brand-sub">Agent 协作平台</div>
          </div>
        </div>

        <nav class="nav-list">
          <button :class="['nav-item', { active: currentTab === 'chat' }]" @click="switchTab('chat')">
            <span class="icon">💬</span>
            <span class="label">智能对话</span>
            <span v-if="chatState.messages.length > 0" class="nav-badge">{{ chatState.messages.length }}</span>
          </button>
          <button class="nav-item nav-item-new" @click="newSession">
            <span class="icon">✨</span>
            <span class="label">新建对话</span>
          </button>
        </nav>

        <!-- 实时告警区（放左侧栏，支持展开排障） -->
        <div v-if="canViewAlarms" class="alarm-section">
          <div class="alarm-section-header">
            <h3>实时告警 <span v-if="alarmCount > 0" class="alarm-badge">{{ alarmCount }}</span></h3>
            <button :disabled="chatState.streaming" class="refresh-btn" title="刷新" @click="fetchAlarms">↻</button>
          </div>
          <div v-if="alarmsLoading" class="alarm-loading"><div class="mini-spinner"></div><span>加载中...</span></div>
          <div v-else-if="alarms.length > 0" class="alarm-list-sidebar">
            <div v-for="alarm in alarms.slice(0, 8)" :key="alarm.alertId" :class="'severity-' + (alarm.severity || 'info').toLowerCase()" class="alarm-card-sidebar" @click="onTroubleshoot(`${alarm.resourceId} 出现告警：${alarm.msg}，请帮我排查根因`)">
              <div class="alarm-resource">{{ alarm.resourceId }}</div>
              <div class="alarm-msg">{{ alarm.msg }}</div>
              <div class="alarm-meta"><span class="alarm-type">{{ alarm.resourceType }}</span><span class="alarm-sev">{{ alarm.severity }}</span></div>
            </div>
          </div>
          <div v-else class="alarm-empty"><span>✅ 暂无告警</span></div>
        </div>

        <!-- 登录用户信息卡 -->
        <div v-if="userInfo" class="user-card">
          <div class="user-avatar">{{ userInfo.username[0].toUpperCase() }}</div>
          <div class="user-info-text">
            <div class="user-name">{{ userInfo.username }}</div>
            <div class="user-tenant">{{ userInfo.tenantId }} · {{ userInfo.deptId }}</div>
            <div class="user-roles">
              <span v-for="r in userInfo.roles" :key="r" class="role-tag">{{ r }}</span>
            </div>
          </div>
          <button class="logout-btn" title="退出登录" @click="doLogout">⏻</button>
        </div>
      </aside>

      <!-- 中间主区 -->
      <main class="main">
        <header class="main-header">
          <div class="left">
            <span class="left-title">{{ tabTitle }}</span>
            <span class="model-tag">{{ userInfo?.roles[0] }}</span>
          </div>
          <div class="right">
            <div v-if="userInfo" class="tenant-info">
              <div class="info-item">
                <span class="info-label">租户</span>
                <span class="info-value">{{ userInfo.tenantId }}</span>
              </div>
              <div class="info-item">
                <span class="info-label">部门</span>
                <span class="info-value">{{ userInfo.deptId }}</span>
              </div>
              <div class="info-item">
                <span class="info-label">权限</span>
                <span class="info-value">{{ userInfo.permissions.length }}项</span>
              </div>
            </div>
            <button :title="'切换到' + (theme === 'dark' ? '日间' : '夜间') + '模式'" class="theme-toggle" @click="toggleTheme">
              {{ theme === 'dark' ? '☀️' : '🌙' }}
            </button>
            <button :title="rightPanelOpen ? '隐藏右栏' : '显示右栏'" class="toggle-right" @click="rightPanelOpen = !rightPanelOpen">
              {{ rightPanelOpen ? '▶' : '◀' }}
            </button>
          </div>
        </header>

        <div class="content">
          <ChatView v-if="currentTab === 'chat'" :chat-state="chatState" :right-panel-open="rightPanelOpen" :workflow-state="workflowState" @toggle-workflow="showWorkflow" @new-session="newSession" />
        </div>
      </main>

      <!-- 右侧面板 -->
      <aside :class="['sidebar-right', { open: rightPanelOpen }]">
        <div class="right-tabs">
          <button :class="['right-tab', { active: rightTab === 'history' }]" @click="rightTab = 'history'">
            <span>💬</span> 历史
          </button>
          <button :class="['right-tab', { active: rightTab === 'workflow' }]" @click="rightTab = 'workflow'">
            <span>🔄</span> 工作流
            <span v-if="workflowRunning" class="running-dot"></span>
          </button>
        </div>
        <div class="right-content">
          <HistoryView v-if="rightTab === 'history'" :current-id="chatState.currentSessionId" :sessions="chatSessions" @delete="deleteSession" @select="loadSession" />
          <WorkflowView v-else-if="rightTab === 'workflow'" :workflow-state="workflowState" @on-clear="clearWorkflow" />
        </div>
      </aside>
    </template>

    <div v-if="toast" class="toast">{{ toast }}</div>
  </div>
</template>

<script lang="ts" setup>
import {computed, onMounted, reactive, ref} from 'vue'
import ChatView from './views/ChatView.vue'
import HistoryView from './views/HistoryView.vue'
import WorkflowView from './views/WorkflowView.vue'
import {clearAuth, getUserInfo, hasPermission, isLoggedIn as checkLogin, setAuth} from './utils/auth'
import {apiFetch, login as apiLogin, setAuthFailedHandler} from './utils/http'

// === 主题管理 ===
type Theme = 'light' | 'dark'
function getInitialTheme(): Theme {
  const stored = localStorage.getItem('theme') as Theme | null
  if (stored) return stored
  // 根据时间自动判断：6:00-18:00 日间
  const hour = new Date().getHours()
  return (hour >= 6 && hour < 18) ? 'light' : 'dark'
}
const theme = ref<Theme>(getInitialTheme())
function applyTheme(t: Theme) {
  document.documentElement.setAttribute('data-theme', t)
  localStorage.setItem('theme', t)
}
function toggleTheme() {
  theme.value = theme.value === 'dark' ? 'light' : 'dark'
  applyTheme(theme.value)
}
applyTheme(theme.value)
// 每分钟检查时间，自动切换（仅在用户未手动设置时）
setInterval(() => {
  if (!localStorage.getItem('theme')) {
    const newTheme = (new Date().getHours() >= 6 && new Date().getHours() < 18) ? 'light' : 'dark'
    if (newTheme !== theme.value) {
      theme.value = newTheme
      applyTheme(newTheme)
    }
  }
}, 60000)

const isLoggedIn = ref(checkLogin())
const userInfo = ref(getUserInfo())
const currentTab = ref('chat')
const rightPanelOpen = ref(true)
const rightTab = ref('workflow')
const toast = ref('')
const loginUser = ref('admin')
const loginPass = ref('admin123')
const loginErr = ref('')
const loginLoading = ref(false)

const alarms = ref<any[]>([])
const alarmsLoading = ref(false)
const alarmCount = computed(() => alarms.value.length)

interface ChatMsg { role: string; content: string; streaming?: boolean; id: number; mode?: string }
interface ChatSession { id: string; title: string; messages: ChatMsg[]; createdAt: number }
const chatState = reactive({
  messages: [] as ChatMsg[],
  streaming: false,
  mode: 'auto' as 'auto' | 'single' | 'multi',
  currentSessionId: '',
})

const chatSessions = ref<ChatSession[]>(loadSessions())
function loadSessions(): ChatSession[] {
  try { const raw = localStorage.getItem('chat_sessions'); return raw ? JSON.parse(raw) : [] } catch { return [] }
}
function saveSessions() {
  const clean = chatSessions.value.map(s => ({ ...s, messages: s.messages.map(m => ({ ...m, streaming: false })) }))
  localStorage.setItem('chat_sessions', JSON.stringify(clean))
}

const workflowState = reactive({
  running: false,
  agents: [
    { id: 'triage', name: '问题分诊', nameEn: 'TriageAgent', icon: '🎯', status: 'waiting', duration: 0 },
    { id: 'alarm', name: '告警分析', nameEn: 'AlarmAnalysisAgent', icon: '🚨', status: 'waiting', duration: 0 },
    { id: 'resource', name: '资源诊断', nameEn: 'ResourceDiagnosticAgent', icon: '📊', status: 'waiting', duration: 0 },
    { id: 'knowledge', name: '知识检索', nameEn: 'KnowledgeRetrievalAgent', icon: '📚', status: 'waiting', duration: 0 },
    { id: 'billing', name: '账单分析', nameEn: 'BillingAnalysisAgent', icon: '💰', status: 'waiting', duration: 0 },
    { id: 'synthesis', name: '综合分析', nameEn: 'SynthesisAgent', icon: '📋', status: 'waiting', duration: 0 },
  ],
  trace: [] as Array<{ agentName: string; durationMs: number; result: string }>,
  routeLabel: '',
  finalReport: '',
  activeTransition: -1,
})
const workflowRunning = computed(() => workflowState.running)

const canViewAlarms = computed(() => hasPermission('alarm:read'))
const canViewRAG = computed(() => hasPermission('rag:read'))

const tabTitle = computed(() => {
  const titles: Record<string, string> = { chat: '智能排障对话', alarms: '告警列表', rag: '知识库' }
  return titles[currentTab.value] || ''
})

function switchTab(tab: string) { currentTab.value = tab; if (tab === 'alarms') fetchAlarms() }
function showWorkflow() { rightTab.value = 'workflow'; rightPanelOpen.value = true }
function clearWorkflow() {
  workflowState.agents = workflowState.agents.map(a => ({ ...a, status: 'waiting', duration: 0 }))
  workflowState.trace = []; workflowState.routeLabel = ''; workflowState.finalReport = ''; workflowState.activeTransition = -1
}
function showToast(msg: string) { toast.value = msg; setTimeout(() => { toast.value = '' }, 2500) }

async function doLogin() {
  loginLoading.value = true; loginErr.value = ''
  try {
    const data = await apiLogin(loginUser.value, loginPass.value)
    setAuth(data); userInfo.value = data; isLoggedIn.value = true
    showToast('登录成功')
  } catch (e) { loginErr.value = (e as Error).message }
  finally { loginLoading.value = false }
}
function doLogout() { clearAuth(); userInfo.value = null; isLoggedIn.value = false }
function focusPass() { document.querySelector('input[type="password"]')?.focus() }

async function fetchAlarms() {
  if (!hasPermission('alarm:read')) return
  alarmsLoading.value = true
  try { const data = await apiFetch('/api/alarms?limit=20'); alarms.value = data.alarms || [] }
  catch (e) { console.error(e) } finally { alarmsLoading.value = false }
}

function onTroubleshoot(msg: string) {
  currentTab.value = 'chat'
  setTimeout(() => window.dispatchEvent(new CustomEvent('troubleshoot', { detail: msg })), 100)
}

function newSession() {
  if (chatState.messages.length > 0) {
    const firstUser = chatState.messages.find(m => m.role === 'user')
    const title = firstUser ? firstUser.content.slice(0, 30) : '新对话'
    chatSessions.value.unshift({ id: 'sess-' + Date.now(), title, messages: [...chatState.messages], createdAt: Date.now() })
    if (chatSessions.value.length > 20) chatSessions.value = chatSessions.value.slice(0, 20)
    saveSessions()
  }
  chatState.messages = []; chatState.currentSessionId = ''
}
function loadSession(id: string) {
  if (chatState.streaming) return
  const s = chatSessions.value.find(x => x.id === id)
  if (s) {
    newSession()
    chatState.currentSessionId = s.id
    chatState.messages = s.messages.map(m => ({ ...m, streaming: false, id: Date.now() + Math.random() }))
  }
}
function deleteSession(id: string) {
  chatSessions.value = chatSessions.value.filter(s => s.id !== id)
  if (chatState.currentSessionId === id) chatState.currentSessionId = ''
  saveSessions()
}

setAuthFailedHandler(() => { isLoggedIn.value = false; userInfo.value = null; loginErr.value = 'Token已过期，请重新登录' })
onMounted(() => { if (isLoggedIn.value && canViewAlarms.value) fetchAlarms() })
</script>

<style scoped>
/* === 3栏布局 === */
.app { display: grid; grid-template-columns: 240px 1fr 360px; height: 100vh; transition: grid-template-columns 0.3s; }
.app:has(.sidebar-right:not(.open)) { grid-template-columns: 240px 1fr 0; }

/* === 左侧导航栏（宽栏） === */
.sidebar-left { background: var(--bg-elevated); border-right: 1px solid var(--glass-border); display: flex; flex-direction: column; backdrop-filter: blur(24px); overflow: hidden; }
.brand-area { padding: 20px 18px; border-bottom: 1px solid var(--glass-border); display: flex; align-items: center; gap: 12px; }
.brand-logo { width: 40px; height: 40px; background: linear-gradient(135deg, #6366f1 0%, #8b5cf6 50%, #ec4899 100%); border-radius: 10px; display: flex; align-items: center; justify-content: center; font-size: 20px; flex-shrink: 0; box-shadow: 0 4px 16px rgba(99,102,241,0.4); }
.brand-text { flex: 1; }
.brand-title { font-size: 15px; font-weight: 700; background: linear-gradient(135deg, #6366f1, #ec4899); -webkit-background-clip: text; background-clip: text; -webkit-text-fill-color: transparent; }
.brand-sub { font-size: 10px; color: var(--text-tertiary); margin-top: 2px; }

.nav-list { flex-shrink: 0; padding: 12px 10px; display: flex; flex-direction: column; gap: 4px; }
.nav-item { display: flex; align-items: center; gap: 10px; padding: 10px 12px; background: transparent; border: 1px solid transparent; border-radius: var(--radius-sm); color: var(--text-secondary); font-size: 13px; cursor: pointer; text-align: left; transition: all 0.2s; }
.nav-item:hover { background: var(--bg-hover); color: var(--text-primary); }
.nav-item.active { background: var(--accent-soft); color: var(--accent-hover); border-color: var(--thought-border); }
.nav-item .icon { font-size: 16px; width: 24px; text-align: center; flex-shrink: 0; }
.nav-item .label { flex: 1; font-weight: 500; }
.nav-badge { font-size: 10px; padding: 1px 6px; border-radius: 8px; background: var(--accent-soft); color: var(--accent-hover); font-weight: 600; }
.nav-badge.danger { background: var(--danger); color: white; }
.nav-item-new { color: var(--accent-hover); border: 1px dashed var(--thought-border); }
.nav-item-new:hover { background: var(--accent-soft); border-color: var(--accent); border-style: solid; }

/* 告警区（左侧栏内嵌） */
.alarm-section { flex: 1; padding: 10px 12px; overflow-y: auto; min-height: 0; }
.alarm-section-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.alarm-section-header h3 { font-size: 11px; font-weight: 600; color: var(--text-tertiary); text-transform: uppercase; letter-spacing: 0.08em; }
.alarm-badge { font-size: 10px; padding: 1px 6px; border-radius: 8px; background: var(--danger); color: white; margin-left: 4px; }
.refresh-btn { background: none; border: 1px solid var(--glass-border); color: var(--text-secondary); font-size: 12px; width: 22px; height: 22px; border-radius: 5px; cursor: pointer; }
.refresh-btn:hover:not(:disabled) { background: var(--bg-hover); }
.alarm-loading { display: flex; align-items: center; gap: 6px; padding: 16px 0; color: var(--text-tertiary); font-size: 11px; justify-content: center; }
.mini-spinner { width: 12px; height: 12px; border: 2px solid rgba(255,255,255,0.1); border-top-color: var(--accent); border-radius: 50%; animation: spin 0.8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.alarm-list-sidebar { display: flex; flex-direction: column; gap: 6px; }
.alarm-card-sidebar { padding: 8px 10px; background: var(--bg-card); border: 1px solid var(--glass-border); border-left: 3px solid var(--info); border-radius: 6px; cursor: pointer; transition: all 0.15s; }
.alarm-card-sidebar:hover { transform: translateX(2px); border-color: var(--accent); }
.severity-critical { border-left-color: #ef4444 !important; background: rgba(239,68,68,0.08) !important; }
.severity-warning { border-left-color: #f59e0b !important; }
.alarm-card-sidebar .alarm-resource { font-size: 11px; font-weight: 600; color: var(--text-primary); }
.alarm-card-sidebar .alarm-msg { font-size: 10px; color: var(--text-secondary); margin: 1px 0 3px; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.alarm-card-sidebar .alarm-meta { display: flex; gap: 6px; }
.alarm-card-sidebar .alarm-type, .alarm-card-sidebar .alarm-sev { font-size: 9px; padding: 1px 4px; border-radius: 3px; background: var(--bg-input); color: var(--text-tertiary); }
.alarm-empty { text-align: center; padding: 16px 0; color: var(--text-tertiary); font-size: 11px; }

/* 用户卡片 */
.user-card { margin: 10px 12px; padding: 12px; background: var(--bg-card); border: 1px solid var(--glass-border); border-radius: var(--radius-md); display: flex; gap: 10px; align-items: flex-start; position: relative; backdrop-filter: blur(16px); }
.user-avatar { width: 36px; height: 36px; border-radius: 50%; background: linear-gradient(135deg, #6366f1, #8b5cf6); display: flex; align-items: center; justify-content: center; font-size: 14px; color: white; font-weight: 600; flex-shrink: 0; }
.user-info-text { flex: 1; min-width: 0; }
.user-name { font-size: 13px; font-weight: 600; color: var(--text-primary); }
.user-tenant { font-size: 10px; color: var(--text-tertiary); margin-top: 2px; }
.user-roles { margin-top: 6px; display: flex; flex-wrap: wrap; gap: 3px; }
.role-tag { font-size: 9px; padding: 1px 5px; border-radius: 3px; background: var(--accent-soft); color: var(--accent-hover); }
.logout-btn { position: absolute; top: 8px; right: 8px; background: none; border: none; color: var(--text-tertiary); font-size: 13px; cursor: pointer; padding: 2px 4px; }
.logout-btn:hover { color: var(--danger); }

/* === 中间主区 === */
.main { display: flex; flex-direction: column; min-width: 0; overflow: hidden; }
.main-header { display: flex; align-items: center; justify-content: space-between; padding: 12px 24px; border-bottom: 1px solid (var(--glass-border)); background: var(--bg-elevated); backdrop-filter: blur(24px); }
.main-header .left { display: flex; align-items: center; gap: 10px; }
.left-title { font-size: 15px; font-weight: 600; color: var(--text-primary); }
.model-tag { font-size: 10px; padding: 2px 8px; border-radius: 4px; background: var(--accent-soft); color: var(--accent-hover); font-weight: 500; }
.main-header .right { display: flex; align-items: center; gap: 10px; }
.tenant-info { display: flex; gap: 12px; padding: 6px 12px; background: var(--bg-input); border: 1px solid var(--glass-border); border-radius: 8px; }
.info-item { display: flex; flex-direction: column; gap: 1px; min-width: 0; }
.info-label { font-size: 9px; color: var(--text-tertiary); text-transform: uppercase; letter-spacing: 0.5px; }
.info-value { font-size: 11px; color: var(--text-primary); font-weight: 500; font-family: 'JetBrains Mono', monospace; }
.theme-toggle, .toggle-right { width: 30px; height: 30px; background: var(--bg-input); border: 1px solid var(--glass-border); border-radius: 6px; color: var(--text-secondary); cursor: pointer; font-size: 14px; transition: all 0.2s; }
.theme-toggle:hover, .toggle-right:hover { background: var(--bg-hover); color: var(--text-primary); }

.content { flex: 1; overflow: hidden; }

/* === 右侧面板 === */
.sidebar-right { background: var(--bg-elevated); border-left: 1px solid var(--glass-border); display: flex; flex-direction: column; overflow: hidden; backdrop-filter: blur(24px); transition: all 0.3s; }
.sidebar-right:not(.open) { display: none; }
.right-tabs { display: flex; border-bottom: 1px solid var(--glass-border); padding: 8px; gap: 4px; }
.right-tab { flex: 1; padding: 7px 10px; background: none; border: 1px solid transparent; border-radius: 6px; color: var(--text-tertiary); font-size: 11px; cursor: pointer; display: flex; align-items: center; justify-content: center; gap: 5px; transition: all 0.2s; position: relative; }
.right-tab:hover { background: var(--bg-hover); color: var(--text-primary); }
.right-tab.active { background: var(--accent-soft); color: var(--accent-hover); border-color: var(--thought-border); }
.running-dot { width: 6px; height: 6px; background: var(--warning); border-radius: 50%; animation: pulse 1s infinite; }
.right-content { flex: 1; overflow: hidden; display: flex; flex-direction: column; }

/* === 登录页 === */
.login-overlay { position: fixed; inset: 0; display: flex; align-items: center; justify-content: center; background: var(--bg-gradient); z-index: 999; }
.login-box { width: 380px; background: var(--bg-elevated); border: 1px solid var(--glass-border); border-radius: var(--radius-xl); padding: 40px; backdrop-filter: blur(24px) saturate(180%); box-shadow: var(--glass-shadow); }
.login-logo { text-align: center; font-size: 44px; margin-bottom: 16px; }
.login-title { font-size: 20px; font-weight: 700; text-align: center; margin-bottom: 4px; color: var(--text-primary); }
.login-sub { font-size: 12px; color: var(--text-tertiary); text-align: center; margin-bottom: 28px; }
.form-group { margin-bottom: 16px; }
.form-label { font-size: 12px; color: var(--text-tertiary); margin-bottom: 6px; display: block; }
.form-input { width: 100%; padding: 10px 12px; background: var(--bg-input); border: 1px solid var(--glass-border); border-radius: var(--radius-sm); color: var(--text-primary); font-size: 14px; outline: none; transition: border 0.15s; }
.form-input:focus { border-color: var(--accent); }
.login-btn { width: 100%; padding: 10px; background: var(--user-bubble); color: white; border: none; border-radius: var(--radius-sm); font-size: 14px; font-weight: 600; cursor: pointer; margin-top: 8px; box-shadow: 0 4px 14px rgba(99,102,241,0.35); transition: all 0.2s; }
.login-btn:hover:not(:disabled) { transform: translateY(-1px); box-shadow: 0 6px 18px rgba(99,102,241,0.5); }
.login-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.login-err { color: var(--danger); font-size: 12px; text-align: center; margin-top: 8px; min-height: 16px; }
.login-hint { font-size: 11px; color: var(--text-tertiary); text-align: center; margin-top: 16px; line-height: 1.6; }
.login-hint code { background: var(--bg-input); padding: 2px 6px; border-radius: 4px; color: var(--accent-hover); }

@keyframes pulse { 0%,100% { opacity: 1; } 50% { opacity: 0.5; } }

@media (max-width: 1200px) {
  .app { grid-template-columns: 200px 1fr 320px; }
}
@media (max-width: 1024px) {
  .app { grid-template-columns: 200px 1fr 0; }
  .sidebar-right { position: fixed; right: 0; top: 0; bottom: 0; width: 320px; z-index: 100; box-shadow: -8px 0 32px rgba(0,0,0,0.2); }
  .sidebar-right:not(.open) { transform: translateX(100%); }
  .tenant-info { gap: 8px; padding: 4px 10px; }
}
@media (max-width: 768px) {
  .app { grid-template-columns: 60px 1fr; }
  .sidebar-left { padding: 0; }
  .brand-text, .nav-item .label, .nav-badge, .user-info-text, .logout-btn { display: none; }
  .brand-area { justify-content: center; padding: 16px 0; }
  .nav-item { justify-content: center; padding: 10px; }
  .user-card { margin: 10px 8px; padding: 8px; justify-content: center; }
  .tenant-info { display: none; }
}
</style>
