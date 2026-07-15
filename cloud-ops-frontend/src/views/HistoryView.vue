<template>
  <div class="history-view">
    <div v-if="sessions.length === 0" class="empty">暂无历史对话</div>
    <div v-else class="history-list">
      <div v-for="s in sessions" :key="s.id" :class="['history-item', { active: s.id === currentId }]" @click="$emit('select', s.id)">
        <div class="history-title">{{ s.title || '新对话' }}</div>
        <div class="history-meta">
          <span class="history-time">{{ formatTime(s.createdAt) }}</span>
          <button class="del-btn" @click.stop="$emit('delete', s.id)">✕</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
defineProps<{ sessions: any[]; currentId: string }>()
defineEmits<{ select: [id: string]; delete: [id: string] }>()

function formatTime(ts: number) {
  const d = new Date(ts)
  return d.toLocaleDateString('zh-CN') + ' ' + d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}
</script>

<style scoped>
.history-view { padding: 12px; height: 100%; overflow-y: auto; display: flex; flex-direction: column; gap: 8px; }
.empty { text-align: center; padding: 30px; color: var(--text-tertiary); font-size: 12px; }
.history-list { display: flex; flex-direction: column; gap: 4px; }
.history-item { padding: 8px 10px; background: var(--bg-card); border: 1px solid var(--glass-border); border-radius: var(--radius-sm); cursor: pointer; transition: all 0.15s; }
.history-item:hover { background: var(--bg-elevated); border-color: var(--accent); }
.history-item.active { background: var(--accent-soft); border-color: var(--thought-border); }
.history-title { font-size: 12px; color: var(--text-primary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.history-meta { display: flex; justify-content: space-between; align-items: center; margin-top: 2px; }
.history-time { font-size: 10px; color: var(--text-tertiary); }
.del-btn { background: none; border: none; color: var(--text-tertiary); font-size: 10px; cursor: pointer; padding: 1px 4px; border-radius: 3px; opacity: 0; transition: all 0.15s; }
.history-item:hover .del-btn { opacity: 0.7; }
.del-btn:hover { opacity: 1 !important; color: var(--danger); }
</style>
