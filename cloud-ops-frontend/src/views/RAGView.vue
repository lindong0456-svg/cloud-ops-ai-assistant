<template>
  <div class="rag-view">
    <div class="rag-search">
      <input v-model="query" placeholder="搜索知识库..." type="text" @keydown.enter="doSearch">
      <select v-model="mode">
        <option value="hybrid">混合检索</option>
        <option value="vector">向量检索</option>
        <option value="keyword">关键词检索</option>
      </select>
      <button :disabled="loading || !query.trim()" @click="doSearch">{{ loading ? '搜索中...' : '搜索' }}</button>
    </div>
    <div v-if="results.length > 0" class="results">
      <div v-for="(r, i) in results" :key="i" class="result-item">
        <div class="result-header">
          <span class="result-source">📄 {{ r.source }}</span>
          <span class="result-score">得分: {{ (r.score || 0).toFixed(3) }}</span>
        </div>
        <div class="result-content">{{ r.content?.slice(0, 300) }}{{ r.content?.length > 300 ? '...' : '' }}</div>
      </div>
    </div>
    <div v-else-if="!loading && searched" class="empty">未找到相关知识</div>
  </div>
</template>

<script lang="ts" setup>
import {ref} from 'vue'
import {apiFetch} from '../utils/http'

const query = ref('')
const mode = ref('hybrid')
const loading = ref(false)
const results = ref<any[]>([])
const searched = ref(false)

async function doSearch() {
  if (!query.value.trim()) return
  loading.value = true; searched.value = true
  try {
    const data: any = await apiFetch(`/api/rag/search?query=${encodeURIComponent(query.value)}&mode=${mode.value}`)
    results.value = data.results || []
  } catch (e) {
    results.value = []
  } finally { loading.value = false }
}
</script>

<style scoped>
.rag-view { padding: 20px 24px; height: 100%; overflow-y: auto; }
.rag-search { display: flex; gap: 8px; margin-bottom: 16px; max-width: 760px; margin: 0 auto 16px; }
.rag-search input { flex: 1; padding: 8px 12px; background: var(--bg-input); border: 1px solid var(--glass-border); border-radius: var(--radius-sm); color: var(--text-primary); font-size: 13px; outline: none; }
.rag-search input:focus { border-color: var(--accent); }
.rag-search select { padding: 8px; background: var(--bg-input); border: 1px solid var(--glass-border); border-radius: var(--radius-sm); color: var(--text-primary); font-size: 12px; }
.rag-search button { padding: 8px 18px; background: var(--accent-gradient); border: none; border-radius: var(--radius-sm); color: white; font-size: 12px; font-weight: 600; cursor: pointer; }
.rag-search button:disabled { opacity: 0.4; cursor: not-allowed; }
.results { max-width: 760px; margin: 0 auto; display: flex; flex-direction: column; gap: 8px; }
.result-item { background: var(--bg-card); border: 1px solid var(--glass-border); border-radius: var(--radius-sm); padding: 12px 14px; }
.result-header { display: flex; justify-content: space-between; margin-bottom: 6px; }
.result-source { font-size: 12px; font-weight: 600; color: var(--accent-hover); }
.result-score { font-size: 10px; color: var(--text-tertiary); }
.result-content { font-size: 12px; color: var(--text-secondary); line-height: 1.5; white-space: pre-wrap; }
.empty { text-align: center; padding: 40px; color: var(--text-tertiary); }
</style>
