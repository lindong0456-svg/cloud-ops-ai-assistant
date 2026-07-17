<template>
  <div class="user-panel" ref="panelRef">
    <!-- 触发器：头像 + 用户名 -->
    <div class="user-trigger" @click.stop="expanded = !expanded">
      <div class="user-avatar">{{ avatarText }}</div>
      <span class="user-name">{{ user.username }}</span>
      <span class="chevron" :class="{ expanded }">▾</span>
    </div>

    <!-- 下拉菜单 -->
    <transition name="dropdown">
      <div v-if="expanded" class="user-dropdown">
        <!-- 角色标签 -->
        <div class="dropdown-header">
          <span class="role-badge">{{ user.roles[0] || 'USER' }}</span>
          <span class="status-text">已登录</span>
        </div>

        <!-- 详细信息列表 -->
        <div class="detail-list">
          <div class="detail-item">
            <span class="label">用户ID</span>
            <span class="value mono">{{ user.userId }}</span>
          </div>
          <div class="detail-item">
            <span class="label">租户</span>
            <span class="value mono">{{ user.tenantId }}</span>
          </div>
          <div class="detail-item">
            <span class="label">部门</span>
            <span class="value mono">{{ user.deptId }}</span>
          </div>
        </div>

        <!-- 权限标签（显示中文） -->
        <div v-if="user.permissions?.length" class="perm-section">
          <span class="perm-label">权限</span>
          <div class="perm-tags">
            <span
              v-for="perm in user.permissions"
              :key="perm.code"
              class="tag tag-perm"
              :class="{ 'tag-active': activePermission === perm.code }"
            >{{ perm.name }}</span>
          </div>
        </div>

        <!-- 分割线 + 退出 -->
        <div class="dropdown-divider"></div>
        <button class="logout-btn" @click.stop="$emit('logout')">
          <span>⏻</span><span>退出登录</span>
        </button>
      </div>
    </transition>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue'
import type { UserInfo } from '../api/client'

const props = defineProps<{
  user: UserInfo
  activePermission?: string
}>()

defineEmits<{
  logout: []
}>()

const expanded = ref(false)
const panelRef = ref<HTMLElement | null>(null)

const avatarText = props.user.username.slice(0, 2).toUpperCase()

// 点击外部关闭
function handleClickOutside(e: MouseEvent) {
  if (panelRef.value && !panelRef.value.contains(e.target as Node)) {
    expanded.value = false
  }
}

onMounted(() => document.addEventListener('click', handleClickOutside))
onBeforeUnmount(() => document.removeEventListener('click', handleClickOutside))
</script>

<style scoped>
.user-panel {
  position: relative;
}

/* ====== 头像触发器（嵌入顶栏） ====== */
.user-trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 10px 5px 6px;
  border-radius: var(--radius-md, 10px);
  cursor: pointer;
  transition: background 0.2s;
  border: 1px solid transparent;
}
.user-trigger:hover {
  background: var(--bg-hover, rgba(255,255,255,0.06));
  border-color: var(--border, rgba(255,255,255,0.08));
}

.user-avatar {
  width: 30px; height: 30px;
  border-radius: 50%;
  background: var(--accent-gradient, linear-gradient(135deg, #6366f1, #8b5cf6));
  display: flex; align-items: center; justify-content: center;
  font-size: 11px; font-weight: 700; color: #fff;
  flex-shrink: 0;
}

.user-name {
  font-size: 13px; font-weight: 600;
  color: var(--text-primary, #e8eaf0);
  max-width: 90px;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}

.chevron {
  font-size: 12px;
  color: var(--text-tertiary, #6b7088);
  transition: transform 0.25s cubic-bezier(0.16, 1, 0.3, 1);
  flex-shrink: 0;
}
.chevron.expanded { transform: rotate(180deg); }

/* ====== 下拉菜单 ====== */
.user-dropdown {
  position: absolute;
  top: calc(100% + 6px);
  right: 0;
  width: 260px;
  padding: 4px;
  background: var(--bg-elevated, rgba(20,22,35,0.95));
  backdrop-filter: blur(var(--glass-blur,24px)) saturate(180%);
  -webkit-backdrop-filter: blur(var(--glass-blur,24px)) saturate(180%);
  border: 1px solid var(--glass-border-strong, rgba(255,255,255,0.14));
  border-radius: var(--radius-lg, 16px);
  box-shadow: 0 16px 48px rgba(0,0,0,0.45);
  z-index: 9999;
}

.dropdown-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 10px 12px 6px;
}

.role-badge {
  font-size: 11px; font-weight: 700; letter-spacing: 0.04em;
  padding: 3px 9px; border-radius: 6px;
  background: var(--accent-soft, rgba(99,102,241,0.16));
  color: var(--accent-hover, #818cf8);
}

.status-text {
  font-size: 11px; color: var(--success, #10b981);
  font-weight: 500;
}

.detail-list {
  padding: 6px 12px;
  display: flex; flex-direction: column; gap: 8px;
}

.detail-item {
  display: flex; justify-content: space-between; align-items: center;
}
.detail-item .label {
  font-size: 11px; color: var(--text-tertiary, #6b7088); flex-shrink: 0;
}
.detail-item .value {
  font-size: 12px; color: var(--text-secondary, #9ea3b3);
  text-align: right; max-width: 160px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.detail-item .value.mono { font-family: 'SF Mono','Fira Code',monospace; }

.perm-section {
  padding: 6px 12px;
}
.perm-label {
  font-size: 11px; color: var(--text-tertiary, #6b7088);
  margin-bottom: 6px; display: block;
}
.perm-tags {
  display: flex; flex-wrap: wrap; gap: 4px;
}
.tag {
  font-size: 10px; padding: 2px 7px; border-radius: 4px;
  font-family: 'SF Mono','Fira Code',monospace;
}
.tag-perm {
  background: rgba(34,197,94,0.1);
  color: #86efac;
  border: 1px solid rgba(34,197,94,0.2);
}
.tag-active {
  background: rgba(34,197,94,0.3);
  color: #4ade80;
  border-color: rgba(34,197,94,0.5);
}

.dropdown-divider {
  height: 1px; margin: 4px 12px;
  background: var(--border, rgba(255,255,255,0.08));
}

.logout-btn {
  display: flex; align-items: center; justify-content: center;
  gap: 6px; width: calc(100% - 24px); margin: 4px 12px 8px;
  padding: 7px; border: 1px solid rgba(239,68,68,0.2);
  border-radius: var(--radius-sm, 8px); background: transparent;
  color: #f87171; font-size: 12px; cursor: pointer;
  transition: all 0.2s;
}
.logout-btn:hover {
  background: rgba(239,68,68,0.1);
  border-color: rgba(239,68,68,0.35);
}

/* 动画 */
.dropdown-enter-active,
.dropdown-leave-active {
  transition: opacity 0.18s ease, transform 0.18s cubic-bezier(0.16,1,0.3,1);
  transform-origin: top right;
}
.dropdown-enter-from,
.dropdown-leave-to {
  opacity: 0;
  transform: scale(0.96) translateY(-4px);
}
</style>
