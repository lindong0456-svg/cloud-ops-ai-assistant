<template>
  <div class="workflow-view">
    <div class="wf-header">
      <div class="wf-title">🔄 Agent工作流</div>
      <button :disabled="!hasHistory" class="clear-btn" @click="$emit('onClear')">清空</button>
    </div>

    <div v-if="workflowState.routeLabel" class="route-info">
      <span class="route-badge">{{ workflowState.routeLabel }}</span>
    </div>

    <!-- 流程图：Agent节点 + 流光连线（条件路由 → 知识检索 → 综合分析） -->
    <div class="flow-chart">
      <!-- Step 1: 分诊 -->
      <div class="flow-step">
        <AgentNode :active-index="activeIndex" :agent="workflowState.agents[0]" :index="0" />
        <FlowConnector :active="activeIndex >= 1" :label="workflowState.routeLabel || '分析中'" />
      </div>

      <!-- Step 2: 条件路由分析（三选一，高亮活跃的） -->
      <div class="flow-group flow-group--route">
        <div v-for="idx in [1,2,4]" :key="idx" class="flow-sub-step">
          <AgentNode :active-index="activeIndex" :agent="workflowState.agents[idx]" :index="idx" />
        </div>
      </div>

      <!-- 汇合连接 + 知识检索 -->
      <div class="flow-step">
        <div class="flow-merge-connector">
          <div class="flow-merge-line left"></div>
          <div class="flow-merge-center">
            <FlowConnector :active="activeIndex >= 3" />
          </div>
          <div class="flow-merge-line right"></div>
        </div>
        <AgentNode :active-index="activeIndex" :agent="workflowState.agents[3]" :index="3" />
        <FlowConnector :active="activeIndex >= 5" />
      </div>

      <!-- Step 4: 综合分析 -->
      <div class="flow-step">
        <AgentNode :active-index="activeIndex" :agent="workflowState.agents[5]" :index="5" />
      </div>
    </div>

    <!-- 执行轨迹 -->
    <div v-if="workflowState.trace.length > 0" class="timeline">
      <div class="section-label">执行轨迹</div>
      <div class="timeline-list">
        <div v-for="(t, i) in workflowState.trace" :key="i" :class="{ expanded: expandedIdx === i }" class="timeline-item" @click="toggleDetail(i)">
          <div class="dot completed"></div>
          <div class="timeline-content">
            <div class="timeline-header">
              <span class="tl-name">{{ agentNameCN(t.agentName) }}</span>
              <span class="tl-time">{{ t.durationMs }}ms</span>
            </div>
            <div v-if="expandedIdx === i && t.result" class="tl-detail">
              <pre>{{ t.result }}</pre>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 总耗时 -->
    <div v-if="workflowState.trace.length > 0" class="summary">
      <span>总耗时</span>
      <span class="total">{{ totalDuration }}ms</span>
    </div>

    <!-- 空状态 -->
    <div v-if="workflowState.trace.length === 0" class="empty-state">
      <div class="empty-icon">🔄</div>
      <p>选择多Agent模式发送问题</p>
      <p class="empty-hint">工作流执行时将在此实时展示</p>
    </div>
  </div>
</template>

<script lang="ts" setup>
import {computed, ref} from 'vue'
import AgentNode from '../components/AgentNode.vue'
import FlowConnector from '../components/FlowConnector.vue'

const props = defineProps<{ workflowState: any }>()
defineEmits<{ onClear: [] }>()

// Agent 英文名 → 中文名映射
const AGENT_NAME_CN: Record<string, string> = {
  'TriageAgent': '问题分诊',
  'AlarmAnalysisAgent': '告警分析',
  'ResourceDiagnosticAgent': '资源诊断',
  'KnowledgeRetrievalAgent': '知识检索',
  'BillingAnalysisAgent': '账单分析',
  'SynthesisAgent': '综合分析',
}
function agentNameCN(name: string): string {
  return AGENT_NAME_CN[name] || name
}

const activeIndex = computed(() => {
  const running = props.workflowState.agents.findIndex((a: any) => a.status === 'running')
  if (running >= 0) return running
  let last = -1
  props.workflowState.agents.forEach((a: any, i: number) => { if (a.status === 'completed') last = i })
  return last
})

const hasHistory = computed(() => props.workflowState.trace.length > 0)
const totalDuration = computed(() => props.workflowState.trace.reduce((sum: number, t: any) => sum + t.durationMs, 0))

const expandedIdx = ref(-1)
function toggleDetail(i: number) { expandedIdx.value = expandedIdx.value === i ? -1 : i }
</script>

<style scoped>
.workflow-view { padding: 12px; height: 100%; overflow-y: auto; display: flex; flex-direction: column; gap: 10px; }
.wf-header { display: flex; justify-content: space-between; align-items: center; }
.wf-title { font-size: 13px; font-weight: 600; color: var(--text-primary); }
.clear-btn { padding: 3px 10px; background: transparent; border: 1px solid var(--glass-border); border-radius: 4px; color: var(--text-tertiary); font-size: 10px; cursor: pointer; }
.clear-btn:hover:not(:disabled) { color: var(--text-primary); border-color: var(--accent); }
.clear-btn:disabled { opacity: 0.3; cursor: not-allowed; }

.route-info { text-align: center; }
.route-badge { font-size: 10px; padding: 2px 12px; background: var(--accent-soft); color: var(--accent-hover); border: 1px solid var(--thought-border); border-radius: 10px; font-weight: 500; }

/* 流程图 — 垂直单列布局 */
.flow-chart { display: flex; flex-direction: column; align-items: center; gap: 0; width: 100%; }

/* 条件路由：三个分析Agent并排 */
.flow-group--route { display: flex; gap: 6px; width: 100%; justify-content: center; }
.flow-sub-step { flex: 0 1 100px; min-width: 0; }

/* 汇合区 */
.flow-merge-connector { display: flex; align-items: flex-start; width: 100%; }
.flow-merge-line { flex: 1; height: 1px; background: var(--glass-border); margin-top: 12px; transition: background 0.5s; }
.flow-merge-line.left { background: linear-gradient(to right, transparent, var(--glass-border)); }
.flow-merge-line.right { background: linear-gradient(to left, transparent, var(--glass-border)); }
.flow-merge-center { flex-shrink: 0; }

/* 时间线 */
.timeline { padding: 10px; background: var(--bg-input); border: 1px solid var(--glass-border); border-radius: var(--radius-sm); }
.section-label { font-size: 10px; color: var(--text-tertiary); text-transform: uppercase; letter-spacing: 0.5px; margin-bottom: 8px; }
.timeline-list { display: flex; flex-direction: column; gap: 6px; }
.timeline-item { cursor: pointer; padding: 4px 0; }
.timeline-item:hover .tl-name { color: var(--accent-hover); }
.dot { width: 7px; height: 7px; border-radius: 50%; background: var(--success); flex-shrink: 0; display: inline-block; margin-right: 8px; }
.timeline-content { display: inline-block; width: calc(100% - 15px); vertical-align: top; }
.timeline-header { display: flex; justify-content: space-between; align-items: center; }
.tl-name { font-size: 11px; color: var(--text-primary); font-weight: 500; }
.tl-time { font-size: 10px; color: var(--text-tertiary); }
.tl-detail { margin-top: 4px; }
.tl-detail pre { background: var(--code-bg); padding: 8px; border-radius: 4px; font-size: 10px; white-space: pre-wrap; word-break: break-all; color: var(--text-secondary); max-height: 200px; overflow-y: auto; }

.summary { display: flex; justify-content: space-between; padding: 10px 12px; background: var(--accent-soft); border: 1px solid var(--thought-border); border-radius: var(--radius-sm); }
.summary span:first-child { font-size: 11px; color: var(--text-secondary); }
.total { font-size: 13px; font-weight: 600; color: var(--accent-hover); }

/* 空状态 */
.empty-state { text-align: center; padding: 40px 20px; color: var(--text-tertiary); }
.empty-icon { font-size: 36px; opacity: 0.3; margin-bottom: 12px; }
.empty-state p { font-size: 12px; }
.empty-hint { font-size: 10px; color: var(--text-tertiary); margin-top: 4px; }
</style>
