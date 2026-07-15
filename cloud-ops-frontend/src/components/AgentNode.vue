<template>
  <div :class="['agent-node', 'status-' + agent.status, { 'is-active': isActive }]">
    <div class="agent-header">
      <span class="agent-icon">{{ agent.icon }}</span>
      <div class="agent-titles">
        <div class="agent-name">{{ agent.name }}</div>
        <div class="agent-name-en">{{ agent.nameEn }}</div>
      </div>
    </div>
    <div class="agent-footer">
      <span :class="agent.status" class="status-dot"></span>
      <span class="status-text">{{ statusText }}</span>
      <span v-if="agent.duration > 0" class="agent-duration">{{ agent.duration }}ms</span>
    </div>
    <div v-if="isActive" class="active-pulse"></div>
    <div v-if="agent.status === 'completed'" class="completed-check">✓</div>
  </div>
</template>

<script lang="ts" setup>
import {computed} from 'vue'

const props = defineProps<{
  agent: { id: string; name: string; nameEn: string; icon: string; status: string; duration: number }
  index: number
  activeIndex: number
}>()

const isActive = computed(() => props.activeIndex === props.index && props.agent.status === 'running')

const statusText = computed(() => {
  const map: Record<string, string> = {
    waiting: '等待中', running: '执行中...', completed: '已完成', error: '失败', skipped: '跳过'
  }
  return map[props.agent.status] || props.agent.status
})
</script>

<style scoped>
.agent-node {
  position: relative;
  width: 100%;
  padding: 12px 14px;
  background: var(--bg-card);
  border: 1px solid var(--glass-border);
  border-radius: 10px;
  transition: all 0.3s;
  overflow: hidden;
}

/* 状态样式 */
.status-waiting { opacity: 0.45; background: var(--agent-waiting); border-color: var(--agent-waiting-border); }
.status-running {
  background: var(--agent-running);
  border-color: var(--agent-running-border);
  box-shadow: 0 0 16px rgba(245,158,11,0.3);
  animation: nodeShake 2s ease-in-out infinite;
}
@keyframes nodeShake {
  0%, 100% { transform: translateX(0); }
  25% { transform: translateX(0.5px); }
  75% { transform: translateX(-0.5px); }
}
.status-completed { background: var(--agent-completed); border-color: var(--agent-completed-border); }
.status-error { background: var(--agent-error); border-color: var(--agent-error-border); }
.status-skipped { opacity: 0.25; background: var(--agent-skipped); }
.status-skipped .agent-name { text-decoration: line-through; }

.agent-header { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; justify-content: center; text-align: center; }
.agent-icon { font-size: 22px; flex-shrink: 0; }
.agent-titles { flex: 1; min-width: 0; text-align: center; }
.agent-name { font-size: 13px; font-weight: 600; color: var(--text-primary); }
.agent-name-en { font-size: 9px; color: var(--text-tertiary); font-family: 'JetBrains Mono', monospace; margin-top: 1px; }

.agent-footer { display: flex; align-items: center; gap: 5px; font-size: 10px; justify-content: center; }
.status-dot { width: 6px; height: 6px; border-radius: 50%; flex-shrink: 0; }
.status-dot.waiting { background: var(--text-tertiary); }
.status-dot.running { background: var(--warning); animation: dotPulse 1s infinite; }
.status-dot.completed { background: var(--success); }
.status-dot.error { background: var(--danger); }
.status-dot.skipped { background: transparent; border: 1px dashed var(--text-tertiary); }
.status-text { color: var(--text-secondary); }
.agent-duration { margin-left: auto; color: var(--text-tertiary); font-size: 10px; font-family: 'JetBrains Mono', monospace; }

@keyframes dotPulse { 0%,100% { opacity: 1; transform: scale(1); } 50% { opacity: 0.5; transform: scale(1.4); } }

/* 活跃光圈 */
.active-pulse {
  position: absolute;
  inset: -1px;
  border: 2px solid var(--warning);
  border-radius: 10px;
  pointer-events: none;
  animation: ringPulse 1.5s infinite;
}
@keyframes ringPulse {
  0%, 100% { box-shadow: 0 0 0 0 rgba(245,158,11,0.4); }
  50% { box-shadow: 0 0 0 6px transparent; }
}

/* 完成勾 */
.completed-check {
  position: absolute;
  top: 8px;
  right: 10px;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: var(--success);
  color: white;
  font-size: 11px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  animation: checkPop 0.3s ease;
}
@keyframes checkPop { from { transform: scale(0); } to { transform: scale(1); } }
</style>
