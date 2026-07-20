<template>
  <div class="login-overlay" @click.self="onOverlayClick">
    <div class="login-modal">
      <!-- 头部 -->
      <div class="login-header">
        <div class="login-logo">⚡</div>
        <div>
          <div class="login-title">云运维智能助手</div>
          <div class="login-sub">请登录后使用</div>
        </div>
      </div>

      <!-- 表单 -->
      <form class="login-form" @submit.prevent="handleLogin">
        <div class="form-field">
          <label class="form-label">用户名</label>
          <input
            v-model="username"
            :disabled="loading"
            class="form-input"
            placeholder="输入用户名"
            type="text"
            @keydown.escape="$emit('close')"
          />
        </div>
        <div class="form-field">
          <label class="form-label">密码</label>
          <input
            v-model="password"
            :disabled="loading"
            class="form-input"
            placeholder="输入密码"
            type="password"
            @keydown.escape="$emit('close')"
          />
        </div>

        <!-- 错误提示 -->
        <div v-if="errorMsg" class="login-error">
          <span>⚠️</span><span>{{ errorMsg }}</span>
        </div>

        <!-- 登录按钮 -->
        <button :disabled="loading || !username.trim() || !password.trim()" class="login-btn" type="submit">
          <span v-if="loading" class="login-spinner"></span>
          <span>{{ loading ? '登录中...' : '登 录' }}</span>
        </button>
      </form>

      <!-- 快速测试账号 -->
      <div class="quick-accounts">
        <div class="quick-accounts-label">测试账号（点击一键填入）</div>
        <div class="quick-accounts-list">
          <button class="quick-account-btn" type="button" @click="fillTestAccount('admin', 'admin123')">
            <span class="qa-role">超级管理员</span>
            <span class="qa-cred">admin / admin123</span>
          </button>
          <button class="quick-account-btn" type="button" @click="fillTestAccount('ops_eng', 'admin123')">
            <span class="qa-role">运维工程师</span>
            <span class="qa-cred">ops_eng / admin123</span>
          </button>
          <button class="quick-account-btn" type="button" @click="fillTestAccount('finance', 'admin123')">
            <span class="qa-role">财务人员</span>
            <span class="qa-cred">finance / admin123</span>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import {ref} from 'vue'
import {login, type UserInfo} from '../api/client'

const emit = defineEmits<{
  success: [user: UserInfo]
  close: []
}>()

const username = ref('')
const password = ref('')
const loading = ref(false)
const errorMsg = ref('')

function fillTestAccount(u: string, p: string) {
  username.value = u
  password.value = p
  errorMsg.value = ''
}

async function handleLogin() {
  if (loading.value) return
  loading.value = true
  errorMsg.value = ''
  try {
    const user = await login(username.value.trim(), password.value)
    emit('success', user)
  } catch (e) {
    errorMsg.value = (e as Error).message
  } finally {
    loading.value = false
  }
}

function onOverlayClick() {
  // 不允许点击遮罩关闭 — 必须登录才能用
}
</script>

<style scoped>
.login-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.55);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9999;
}

.login-modal {
  width: 380px;
  background: #1a1f2e;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 16px;
  padding: 32px 28px 24px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.5);
}

.login-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 28px;
}

.login-logo {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  background: linear-gradient(135deg, #6366f1, #8b5cf6);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
}

.login-title {
  font-size: 18px;
  font-weight: 600;
  color: #f1f5f9;
}

.login-sub {
  font-size: 13px;
  color: #64748b;
  margin-top: 2px;
}

.login-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.form-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.form-label {
  font-size: 13px;
  color: #94a3b8;
  font-weight: 500;
}

.form-input {
  width: 100%;
  padding: 10px 14px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 8px;
  background: #0f1420;
  color: #f1f5f9;
  font-size: 14px;
  outline: none;
  transition: border-color 0.2s;
  box-sizing: border-box;
}

.form-input:focus {
  border-color: #6366f1;
}

.form-input::placeholder {
  color: #475569;
}

.login-error {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  border-radius: 8px;
  background: rgba(239, 68, 68, 0.1);
  border: 1px solid rgba(239, 68, 68, 0.3);
  color: #fca5a5;
  font-size: 13px;
}

.login-btn {
  width: 100%;
  padding: 11px;
  border: none;
  border-radius: 8px;
  background: linear-gradient(135deg, #6366f1, #8b5cf6);
  color: #fff;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  transition: opacity 0.2s, transform 0.1s;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}

.login-btn:hover:not(:disabled) {
  opacity: 0.9;
}

.login-btn:active:not(:disabled) {
  transform: scale(0.98);
}

.login-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.login-spinner {
  width: 16px;
  height: 16px;
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.quick-accounts {
  margin-top: 24px;
  padding-top: 20px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}

.quick-accounts-label {
  font-size: 12px;
  color: #64748b;
  margin-bottom: 10px;
}

.quick-accounts-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.quick-account-btn {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.02);
  cursor: pointer;
  transition: border-color 0.2s, background 0.2s;
}

.quick-account-btn:hover {
  border-color: rgba(99, 102, 241, 0.4);
  background: rgba(99, 102, 241, 0.08);
}

.qa-role {
  font-size: 13px;
  color: #cbd5e1;
  font-weight: 500;
}

.qa-cred {
  font-size: 12px;
  color: #64748b;
  font-family: 'SF Mono', 'Fira Code', monospace;
}
</style>
