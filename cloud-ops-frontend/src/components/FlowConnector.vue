<template>
  <div class="flow-connector">
    <div :class="['connector-line', { active }]">
      <div v-if="active" class="flow-particle"></div>
      <div v-if="active" class="flow-particle delay1"></div>
      <div v-if="active" class="flow-particle delay2"></div>
    </div>
    <div v-if="label" :class="{ active }" class="connector-label">{{ label }}</div>
    <div :class="['connector-arrow', { active }]">▼</div>
  </div>
</template>

<script lang="ts" setup>
defineProps<{ active: boolean; label?: string }>()
</script>

<style scoped>
.flow-connector { display: flex; flex-direction: column; align-items: center; gap: 2px; padding: 2px 0; }

.connector-line {
  position: relative;
  width: 2px;
  height: 24px;
  background: var(--glass-border);
  border-radius: 1px;
  transition: background 0.6s ease, box-shadow 0.6s ease;
  overflow: hidden;
}
.connector-line.active {
  background: var(--accent);
  box-shadow: 0 0 8px rgba(99,102,241,0.4);
}

/* 流光粒子动画 */
.flow-particle {
  position: absolute;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--accent-hover);
  box-shadow: 0 0 10px var(--accent), 0 0 20px var(--accent);
  left: -1.5px;
  animation: particleFlow 1.2s linear infinite;
}
.flow-particle.delay1 { animation-delay: 0.4s; }
.flow-particle.delay2 { animation-delay: 0.8s; }

@keyframes particleFlow {
  0% { top: 0; opacity: 0; transform: scale(0.3); }
  15% { opacity: 1; transform: scale(1); }
  80% { opacity: 1; transform: scale(1); }
  100% { top: 100%; opacity: 0; transform: scale(0.3); }
}

.connector-label {
  font-size: 10px;
  padding: 2px 10px;
  border-radius: 8px;
  background: var(--bg-input);
  color: var(--text-tertiary);
  border: 1px solid var(--glass-border);
  opacity: 0.5;
  transition: all 0.5s ease;
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.connector-label.active {
  background: var(--accent-soft);
  color: var(--accent-hover);
  border-color: var(--thought-border);
  opacity: 1;
  transform: scale(1.05);
}

.connector-arrow {
  font-size: 10px;
  color: var(--text-tertiary);
  transition: color 0.5s ease, transform 0.5s ease;
  line-height: 1;
}
.connector-arrow.active {
  color: var(--accent);
  transform: translateY(1px);
}
</style>
