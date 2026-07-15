<template>
  <div class="alarms-view">
    <div class="alarms-toolbar">
      <span class="count">共 {{ alarms.length }} 条告警</span>
      <button :disabled="alarmsLoading" class="refresh-btn" @click="$emit('refresh')">↻ 刷新</button>
    </div>
    <div v-if="alarmsLoading" class="loading"><div class="mini-spinner"></div><span>加载中...</span></div>
    <div v-else-if="alarms.length === 0" class="empty"><span>✅ 暂无未处理告警</span></div>
    <div v-else class="alarm-list">
      <div v-for="alarm in alarms" :key="alarm.alertId" :class="'severity-' + (alarm.severity || 'info').toLowerCase()" class="alarm-card" @click="$emit('troubleshoot', `${alarm.resourceId} 出现告警：${alarm.msg}，请帮我排查根因`)">
        <div class="alarm-main">
          <div class="alarm-resource">{{ alarm.resourceId }}</div>
          <div class="alarm-msg">{{ alarm.msg }}</div>
          <div class="alarm-meta">
            <span class="alarm-type">{{ alarm.resourceType }}</span>
            <span class="alarm-severity">{{ alarm.severity }}</span>
          </div>
        </div>
        <button class="action-btn" @click.stop="$emit('troubleshoot', `${alarm.resourceId} 出现告警：${alarm.msg}，请帮我排查根因`)">🔧 排障</button>
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
defineProps<{ alarms: any[]; alarmsLoading: boolean }>()
defineEmits<{ refresh: []; troubleshoot: [msg: string] }>()
</script>

<style scoped>
.alarms-view { padding: 20px 24px; height: 100%; overflow-y: auto; }
.alarms-toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; }
.count { font-size: 12px; color: var(--text-tertiary); }
.refresh-btn { padding: 5px 10px; background: var(--bg-input); border: 1px solid var(--glass-border); border-radius: 6px; color: var(--text-secondary); font-size: 11px; cursor: pointer; }
.refresh-btn:hover:not(:disabled) { background: var(--bg-hover); color: var(--text-primary); }
.refresh-btn:disabled { opacity: 0.4; cursor: not-allowed; }
.loading, .empty { display: flex; align-items: center; justify-content: center; gap: 8px; padding: 60px 0; color: var(--text-tertiary); font-size: 13px; }
.mini-spinner { width: 14px; height: 14px; border: 2px solid rgba(255,255,255,0.1); border-top-color: var(--accent); border-radius: 50%; animation: spin 0.8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

.alarm-list { max-width: 760px; margin: 0 auto; display: flex; flex-direction: column; gap: 8px; }
.alarm-card { display: flex; align-items: center; gap: 12px; padding: 12px 14px; background: var(--bg-card); border: 1px solid var(--glass-border); border-left: 3px solid var(--info); border-radius: var(--radius-sm); cursor: pointer; transition: all 0.2s; }
.alarm-card:hover { transform: translateX(2px); border-color: var(--accent); }
.severity-critical { border-left-color: #ef4444 !important; background: rgba(239,68,68,0.08) !important; }
.severity-warning { border-left-color: #f59e0b !important; }
.alarm-main { flex: 1; }
.alarm-resource { font-size: 13px; font-weight: 600; color: var(--text-primary); }
.alarm-msg { font-size: 12px; color: var(--text-secondary); margin: 2px 0 4px; }
.alarm-meta { display: flex; gap: 8px; }
.alarm-type, .alarm-severity { font-size: 10px; padding: 1px 6px; border-radius: 3px; background: var(--bg-input); color: var(--text-tertiary); }
.action-btn { padding: 5px 10px; background: rgba(168,85,247,0.12); border: 1px solid rgba(168,85,247,0.3); border-radius: 4px; color: #c084fc; font-size: 11px; cursor: pointer; }
.action-btn:hover { background: rgba(168,85,247,0.25); }
</style>
