<template>
  <div class="build-console">

    <!-- ── 固定头部 ── -->
    <div class="sticky-header">
      <div class="breadcrumb">
        <span class="breadcrumb-home">首页</span>
        <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
          <path d="M5 3.5L8.5 7L5 10.5" stroke="#C5CBD7" stroke-width="1.5" stroke-linecap="round"/>
        </svg>
        <span class="breadcrumb-current">编译控制台</span>
      </div>

      <div class="content-header">
        <div class="title-group">
          <svg width="20" height="20" viewBox="0 0 20 20" fill="none" style="flex-shrink:0">
            <rect x="2" y="2" width="16" height="16" rx="3" stroke="#4F7CFF" stroke-width="1.4"/>
            <path d="M6 7l3 3-3 3" stroke="#4F7CFF" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>
            <path d="M11 13h3" stroke="#4F7CFF" stroke-width="1.4" stroke-linecap="round"/>
          </svg>
          <h2 class="content-title">编译控制台</h2>
          <span class="status-badge" :class="`badge-${statusKey}`">
            <span class="badge-dot" :class="{ 'badge-dot-pulse': isRunning }"></span>
            {{ statusLabel }}
          </span>
          <span v-if="isRunning && buildStatus.totalBatches" class="progress-chip">
            {{ buildStatus.completedBatches }}/{{ buildStatus.totalBatches }} 批次 · {{ buildStatus.costSeconds }}s
          </span>
        </div>
        <div class="content-actions">
          <button class="action-btn" @click="showUrlPanel = !showUrlPanel" title="配置编译服务地址">
            <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
              <circle cx="7" cy="7" r="5.5" stroke="currentColor" stroke-width="1.3"/>
              <circle cx="7" cy="7" r="2" stroke="currentColor" stroke-width="1.3"/>
              <path d="M7 1.5V3M7 11v1.5M1.5 7H3M11 7h1.5M3.1 3.1l1.05 1.05M9.85 9.85l1.05 1.05M3.1 10.9l1.05-1.05M9.85 4.15l1.05-1.05" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"/>
            </svg>
            服务地址
          </button>
          <button class="action-btn primary-btn" @click="triggerFullBuild" :disabled="isRunning">
            <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
              <path d="M3 2.5l9 4.5-9 4.5V2.5z" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/>
            </svg>
            {{ isRunning ? '编译中...' : '全量编译' }}
          </button>
        </div>
      </div>

      <!-- 服务地址配置面板 -->
      <div v-if="showUrlPanel" class="url-panel">
        <span class="url-label">编译服务地址：</span>
        <input class="url-input" v-model="urlDraft" placeholder="http://localhost:8080" @keydown.enter="saveUrl"/>
        <button class="url-save-btn" @click="saveUrl">保存</button>
        <button class="url-cancel-btn" @click="showUrlPanel = false">取消</button>
        <span class="url-hint">跨域请求需在编译服务端开启 CORS</span>
      </div>

      <!-- 进度条（编译中显示） -->
      <div v-if="isRunning && buildStatus.totalBatches" class="progress-bar-wrap">
        <div class="progress-bar-track">
          <div class="progress-bar-fill"
               :style="{ width: (buildStatus.completedBatches / buildStatus.totalBatches * 100) + '%' }"></div>
        </div>
      </div>

      <!-- 状态栏 -->
      <div class="stats-bar">
        <svg width="15" height="15" viewBox="0 0 15 15" fill="none">
          <path d="M7.5 2L13 5.5v4L7.5 13L2 9.5v-4L7.5 2z" stroke="#4F7CFF" stroke-width="1.4"/>
          <circle cx="7.5" cy="7.5" r="2" fill="#4F7CFF"/>
        </svg>
        <span class="stats-text">
          <template v-if="isRunning">
            正在执行：<em style="color:#4F7CFF;font-style:normal;font-weight:600;">{{ buildStatus.taskLabel }}</em>
            <template v-if="buildStatus.currentBatch">
              &nbsp;·&nbsp;批次: {{ buildStatus.currentBatch }}
            </template>
            <template v-if="buildStatus.currentProject">
              &nbsp;·&nbsp;工程: <em style="color:#4F7CFF;font-style:normal;">{{ buildStatus.currentProject }}</em>
            </template>
          </template>
          <template v-else-if="buildStatus.status === 'SUCCESS'">
            <em style="color:#22C55E;font-style:normal;font-weight:600;">✓ 编译成功</em>
            <template v-if="buildStatus.finishMessage">&nbsp;·&nbsp;{{ buildStatus.finishMessage }}</template>
          </template>
          <template v-else-if="buildStatus.status === 'FAILED'">
            <em style="color:#EF4444;font-style:normal;font-weight:600;">✗ 编译失败</em>
            <template v-if="buildStatus.finishMessage">&nbsp;·&nbsp;{{ buildStatus.finishMessage }}</template>
          </template>
          <template v-else>
            空闲 · 共 {{ batches.length }} 个批次 · 服务: <em style="color:#8C94A6;font-style:normal;">{{ buildBaseUrl }}</em>
          </template>
        </span>
      </div>
    </div><!-- /sticky-header -->

    <!-- ── 批次概览 ── -->
    <div class="batch-area">
      <div class="batch-scroll">
        <template v-if="batches.length === 0 && !loadError">
          <div v-for="i in 4" :key="i" class="batch-card skeleton-card">
            <div class="sk-line"></div>
            <div class="sk-line short"></div>
            <div class="sk-tags"></div>
          </div>
        </template>

        <div
          v-for="batch in batches"
          :key="batch.name"
          class="batch-card"
          :class="`batch-${getBatchStatus(batch)}`"
        >
          <div class="batch-card-top">
            <span class="batch-seq">第 {{ batch.batchIndex + 1 }} 批</span>
            <span class="batch-mode-tag" :class="batch.parallel ? 'tag-parallel' : 'tag-serial'">
              {{ batch.parallel ? '并行' : '串行' }}
            </span>
            <span class="batch-run-dot" :class="`dot-${getBatchStatus(batch)}`"></span>
          </div>
          <div class="batch-card-name">{{ batch.name }}</div>
          <div class="batch-project-list">
            <span v-for="p in batch.projects.slice(0, 4)" :key="p" class="project-chip">{{ p }}</span>
            <span v-if="batch.projects.length > 4" class="project-chip more">+{{ batch.projects.length - 4 }}</span>
          </div>
          <div class="batch-card-footer">
            <span class="batch-proj-count">{{ batch.projectCount }} 个工程</span>
            <button class="batch-trigger-btn" :disabled="isRunning" @click="triggerBatch(batch.name)">
              单独触发 ▶
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- ── 实时日志终端 ── -->
    <div class="log-panel">
      <div class="log-toolbar">
        <div class="log-tb-left">
          <span class="sse-status" :class="{ 'sse-on': isConnected }">
            <span class="sse-dot"></span>
            {{ isConnected ? 'SSE 实时' : '未连接' }}
          </span>
          <span class="log-line-count">{{ logs.length }} 行</span>
        </div>
        <div class="log-tb-right">
          <button class="log-btn" :class="{ 'log-btn-active': autoScroll }" @click="autoScroll = !autoScroll">
            <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
              <path d="M6 2v8M3 7l3 3 3-3" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
            自动滚动
          </button>
          <button class="log-btn" @click="clearLogs">
            <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
              <path d="M2 3h8M5 2h2M4.5 3v6.5h3V3" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
            清空
          </button>
          <button class="log-btn" @click="copyLogs">
            <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
              <rect x="4" y="4" width="6.5" height="6.5" rx="1" stroke="currentColor" stroke-width="1.3"/>
              <path d="M8 4V2.5A.5.5 0 0 0 7.5 2h-5A.5.5 0 0 0 2 2.5v5a.5.5 0 0 0 .5.5H4" stroke="currentColor" stroke-width="1.3"/>
            </svg>
            复制全部
          </button>
        </div>
      </div>

      <div class="log-body" ref="logBodyRef" @scroll="onLogScroll">
        <template v-if="logs.length > 0">
          <div v-for="(line, i) in logs" :key="i" class="log-line" :class="`lc-${line.type}`">{{ line.text }}</div>
        </template>
        <div v-else class="log-empty">
          <svg width="40" height="40" viewBox="0 0 40 40" fill="none" style="opacity:0.3">
            <rect x="6" y="6" width="28" height="28" rx="5" stroke="#64748B" stroke-width="1.5"/>
            <path d="M13 14l5 5-5 5" stroke="#64748B" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
            <path d="M21 24h6" stroke="#64748B" stroke-width="1.5" stroke-linecap="round"/>
          </svg>
          <p>等待编译任务启动</p>
          <span>点击「全量编译」或单独触发某个批次开始编译</span>
        </div>
      </div>
    </div>

  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'

defineProps({
  isDark: { type: Boolean, default: false }
})

// ── 服务地址 ──
const buildBaseUrl = ref(localStorage.getItem('buildBaseUrl') || 'http://localhost:8080')
const showUrlPanel = ref(false)
const urlDraft     = ref(buildBaseUrl.value)

const saveUrl = () => {
  buildBaseUrl.value = urlDraft.value.replace(/\/$/, '')
  localStorage.setItem('buildBaseUrl', buildBaseUrl.value)
  showUrlPanel.value = false
  loadBatches()
  pollStatus()
}

// ── 状态 ──
const batches     = ref([])
const loadError   = ref('')
const buildStatus = ref({
  status: 'IDLE', mode: '', taskLabel: '',
  totalBatches: 0, completedBatches: 0,
  currentBatch: '', currentProject: '',
  costSeconds: 0, finishMessage: '', recentLogs: []
})

const logs        = ref([])
const isConnected = ref(false)
const autoScroll  = ref(true)
const logBodyRef  = ref(null)

let sseRef    = null
let pollTimer = null

const isRunning   = computed(() => buildStatus.value.status === 'RUNNING')
const statusKey   = computed(() => (buildStatus.value.status || 'IDLE').toLowerCase())
const statusLabel = computed(() => {
  const m = { IDLE: '空闲', RUNNING: '编译中', SUCCESS: '成功', FAILED: '失败' }
  return m[buildStatus.value.status] ?? (buildStatus.value.status || '空闲')
})

// ── HTTP 工具 ──
const apiFetch = async (path, method = 'GET') => {
  const res = await fetch(`${buildBaseUrl.value}${path}`, { method })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.json()
}

// ── 加载批次 ──
const loadBatches = async () => {
  loadError.value = ''
  try {
    const res = await apiFetch('/api/build/batches')
    batches.value = res.data ?? res
  } catch (e) {
    loadError.value = e.message
    appendLog(`[AxonLink] 无法连接编译服务 ${buildBaseUrl.value} — ${e.message}`, 'error')
  }
}

// ── 轮询状态 ──
const pollStatus = async () => {
  try {
    const res = await apiFetch('/api/build/all/status')
    buildStatus.value = res.data ?? res
  } catch { /* 静默 */ }
}

// ── 日志工具 ──
const getLogType = (text) => {
  if (!text) return 'normal'
  const u = text.toUpperCase()
  if (u.includes('BUILD SUCCESS') || u.includes('[OK]') || u.includes('✓')) return 'success'
  if (u.includes('BUILD FAILURE') || u.includes('BUILD FAILED') || u.includes('✗')) return 'error'
  if (u.includes('[ERROR]') || /\bERROR\b/.test(u)) return 'error'
  if (u.includes('[WARNING]') || u.includes('[WARN]') || /\bWARN\b/.test(u)) return 'warn'
  if (/^[=\-]{3,}/.test(text.trim()) || u.includes('批次') || text.includes('▶')) return 'batch'
  return 'normal'
}

const appendLog = (text, type) => {
  const t = typeof text === 'string' ? text : String(text)
  logs.value.push({ text: t, type: type ?? getLogType(t) })
  if (autoScroll.value) {
    nextTick(() => {
      if (logBodyRef.value) logBodyRef.value.scrollTop = logBodyRef.value.scrollHeight
    })
  }
}

const clearLogs = () => { logs.value = [] }

const copyLogs = async () => {
  const text = logs.value.map(l => l.text).join('\n')
  try { await navigator.clipboard.writeText(text) } catch { /* ignore */ }
}

const onLogScroll = () => {
  if (!logBodyRef.value) return
  const { scrollTop, scrollHeight, clientHeight } = logBodyRef.value
  autoScroll.value = scrollHeight - scrollTop - clientHeight < 80
}

// ── SSE 连接 ──
const connectSSE = () => {
  if (sseRef) { sseRef.close(); sseRef = null }
  isConnected.value = false
  try {
    const es = new EventSource(`${buildBaseUrl.value}/api/build/all/progress`)
    es.addEventListener('log', e => {
      isConnected.value = true
      appendLog(e.data)
    })
    es.addEventListener('done', e => {
      appendLog(e.data)
      appendLog('─'.repeat(72), 'batch')
      es.close()
      sseRef = null
      isConnected.value = false
      pollStatus()
    })
    es.onerror = () => { isConnected.value = false }
    sseRef = es
  } catch (e) {
    appendLog(`[AxonLink] SSE 连接失败: ${e.message}`, 'error')
  }
}

// ── 触发编译 ──
const triggerFullBuild = async () => {
  if (isRunning.value) return
  logs.value = []
  autoScroll.value = true
  appendLog(`[${new Date().toLocaleTimeString()}] ▶ 触发全量编译...`, 'batch')
  try {
    await apiFetch('/api/build/all/async', 'POST')
    connectSSE()
  } catch (e) {
    appendLog(`[AxonLink] 触发失败: ${e.message}`, 'error')
  }
}

const triggerBatch = async (name) => {
  if (isRunning.value) return
  logs.value = []
  autoScroll.value = true
  appendLog(`[${new Date().toLocaleTimeString()}] ▶ 触发批次: ${name}`, 'batch')
  try {
    await apiFetch(`/api/build/batch/${encodeURIComponent(name)}`, 'POST')
    connectSSE()
  } catch (e) {
    appendLog(`[AxonLink] 批次触发失败: ${e.message}`, 'error')
  }
}

// ── 批次卡片状态推断 ──
const getBatchStatus = (batch) => {
  const s = buildStatus.value
  if (!s || s.status === 'IDLE') return 'idle'
  if (s.status === 'RUNNING') {
    if (s.currentBatch === batch.name) return 'running'
    if (s.completedBatches > batch.batchIndex) return 'success'
    return 'idle'
  }
  if (s.status === 'SUCCESS') return 'success'
  if (s.status === 'FAILED') {
    if (s.completedBatches > batch.batchIndex) return 'success'
    if (s.completedBatches === batch.batchIndex) return 'failed'
    return 'idle'
  }
  return 'idle'
}

// ── 生命周期 ──
onMounted(async () => {
  await loadBatches()
  await pollStatus()
  pollTimer = setInterval(pollStatus, 3000)
})

onBeforeUnmount(() => {
  sseRef?.close()
  clearInterval(pollTimer)
})
</script>

<style scoped>
.build-console {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
}

.sticky-header {
  flex-shrink: 0;
  background: var(--bg-sticky);
  padding: 24px 24px 0;
  z-index: 10;
  transition: background 0.3s;
}

.breadcrumb { display: flex; align-items: center; gap: 6px; margin-bottom: 16px; }
.breadcrumb-home    { font-size: 13px; color: var(--text-faint); }
.breadcrumb-current { font-size: 13px; color: var(--text-primary); font-weight: 500; }

.content-header {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 12px; gap: 16px;
}
.title-group { display: flex; align-items: center; gap: 10px; flex-shrink: 0; }
.content-title { font-size: 20px; font-weight: 700; color: var(--text-primary); margin: 0; }

.status-badge {
  display: inline-flex; align-items: center; gap: 5px;
  font-size: 12px; font-weight: 500;
  padding: 3px 10px; border-radius: 12px;
}
.badge-idle    { background: var(--bg-badge); color: var(--text-muted); }
.badge-running { background: rgba(79,124,255,0.12); color: #4F7CFF; }
.badge-success { background: rgba(34,197,94,0.12); color: #22C55E; }
.badge-failed  { background: rgba(239,68,68,0.12); color: #EF4444; }

.badge-dot {
  width: 6px; height: 6px; border-radius: 50%;
  background: currentColor; flex-shrink: 0;
}
@keyframes pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50%       { opacity: 0.4; transform: scale(1.6); }
}
.badge-dot-pulse { animation: pulse 1.2s ease-in-out infinite; }

.progress-chip {
  font-size: 12px; color: var(--text-muted);
  background: var(--bg-badge); padding: 3px 10px; border-radius: 12px;
}

.content-actions { display: flex; align-items: center; gap: 8px; }

.url-panel {
  display: flex; align-items: center; gap: 8px;
  padding: 10px 14px;
  background: var(--bg-stats-bar);
  border: 1px solid var(--border); border-radius: 8px;
  margin-bottom: 10px; flex-wrap: wrap;
}
.url-label { font-size: 12px; color: var(--text-faint); white-space: nowrap; }
.url-input {
  flex: 1; min-width: 200px; max-width: 320px;
  height: 28px; padding: 0 10px;
  font-size: 12px; color: var(--text-secondary);
  background: var(--bg-input);
  border: 1px solid var(--border); border-radius: 6px;
  outline: none; font-family: 'Consolas', monospace;
}
.url-input:focus { border-color: #4F7CFF; box-shadow: 0 0 0 2px rgba(79,124,255,0.1); }
.url-save-btn {
  height: 28px; padding: 0 12px;
  background: #4F7CFF; color: #fff;
  border: none; border-radius: 6px; font-size: 12px; cursor: pointer;
}
.url-save-btn:hover { background: #3D6AE8; }
.url-cancel-btn {
  height: 28px; padding: 0 10px;
  background: transparent; color: var(--text-muted);
  border: 1px solid var(--border); border-radius: 6px; font-size: 12px; cursor: pointer;
}
.url-hint { font-size: 11px; color: var(--text-faint); }

.progress-bar-wrap { margin-bottom: 8px; }
.progress-bar-track { height: 3px; background: var(--border); border-radius: 2px; overflow: hidden; }
.progress-bar-fill {
  height: 100%; background: #4F7CFF; border-radius: 2px;
  transition: width 0.6s cubic-bezier(0.4, 0, 0.2, 1);
}

.stats-bar {
  display: flex; align-items: center; gap: 8px;
  padding: 10px 14px;
  background: var(--bg-stats-bar);
  border: 1px solid var(--border); border-radius: 8px;
  margin-bottom: 12px; transition: background 0.3s;
}
.stats-text { font-size: 13px; color: var(--text-secondary); font-weight: 500; }

.action-btn {
  display: flex; align-items: center; gap: 5px;
  padding: 0 12px; height: 34px;
  background: var(--bg-action-btn);
  border: 1px solid var(--border); border-radius: 8px;
  font-size: 12px; color: var(--text-secondary);
  cursor: pointer; font-weight: 500; font-family: inherit;
  transition: all 0.15s; white-space: nowrap;
}
.action-btn:hover { background: #EEF3FF; border-color: #C5D5FF; color: #4F7CFF; }
.primary-btn { background: #4F7CFF; color: #fff; border-color: #4F7CFF; }
.primary-btn:hover:not(:disabled) { background: #3D6AE8; border-color: #3D6AE8; color: #fff; }
.primary-btn:disabled { opacity: 0.55; cursor: not-allowed; }

/* ── 批次概览 ── */
.batch-area { flex-shrink: 0; padding: 0 24px 12px; }
.batch-scroll {
  display: flex; gap: 10px;
  overflow-x: auto; padding-bottom: 4px;
  scrollbar-width: thin; scrollbar-color: var(--scrollbar-thumb) transparent;
}
.batch-scroll::-webkit-scrollbar { height: 5px; }
.batch-scroll::-webkit-scrollbar-thumb { background: var(--scrollbar-thumb); border-radius: 3px; }

.batch-card {
  flex-shrink: 0; width: 200px;
  background: var(--bg-card);
  border: 1px solid var(--border); border-radius: 10px;
  padding: 12px 14px;
  transition: border-color 0.2s, box-shadow 0.2s;
  display: flex; flex-direction: column; gap: 8px;
}
.batch-card:hover { border-color: #C5D5FF; }
.batch-running { border-color: #4F7CFF !important; box-shadow: 0 0 0 3px rgba(79,124,255,0.1); }
.batch-success { border-color: rgba(34,197,94,0.4); }
.batch-failed  { border-color: rgba(239,68,68,0.4); }

.batch-card-top { display: flex; align-items: center; gap: 6px; }
.batch-seq { font-size: 11px; color: var(--text-faint); font-weight: 500; }
.batch-mode-tag { font-size: 10px; padding: 1px 6px; border-radius: 4px; font-weight: 600; }
.tag-parallel { background: rgba(251,191,36,0.15); color: #F59E0B; }
.tag-serial   { background: rgba(99,102,241,0.12); color: #6366F1; }
.batch-run-dot { width: 6px; height: 6px; border-radius: 50%; margin-left: auto; flex-shrink: 0; }
.dot-idle    { background: var(--border); }
.dot-running { background: #4F7CFF; animation: pulse 1.2s ease-in-out infinite; }
.dot-success { background: #22C55E; }
.dot-failed  { background: #EF4444; }
.batch-card-name { font-size: 13px; font-weight: 600; color: var(--text-primary); line-height: 1.3; }
.batch-project-list { display: flex; flex-wrap: wrap; gap: 4px; }
.project-chip {
  font-size: 10px; padding: 1px 6px;
  background: var(--bg-badge); color: var(--text-muted);
  border-radius: 4px; font-family: 'Consolas', monospace;
}
.project-chip.more { color: var(--text-faint); }
.batch-card-footer { display: flex; align-items: center; justify-content: space-between; margin-top: auto; }
.batch-proj-count { font-size: 11px; color: var(--text-faint); }
.batch-trigger-btn {
  font-size: 11px; padding: 3px 8px;
  background: transparent; color: #4F7CFF;
  border: 1px solid rgba(79,124,255,0.3); border-radius: 5px;
  cursor: pointer; font-family: inherit; transition: all 0.15s;
}
.batch-trigger-btn:hover:not(:disabled) { background: rgba(79,124,255,0.08); border-color: #4F7CFF; }
.batch-trigger-btn:disabled { opacity: 0.4; cursor: not-allowed; }

/* 骨架屏 */
.skeleton-card { background: var(--bg-empty); }
@keyframes shimmer {
  0%   { background-position: -200% 0; }
  100% { background-position: 200% 0; }
}
.sk-line, .sk-tags {
  height: 12px; border-radius: 4px;
  background: linear-gradient(90deg, var(--border) 25%, var(--bg-badge) 50%, var(--border) 75%);
  background-size: 200% 100%; animation: shimmer 1.5s infinite;
}
.sk-line.short { width: 60%; height: 10px; }
.sk-tags { height: 20px; margin-top: 4px; }

/* ── 日志终端 ── */
.log-panel {
  flex: 1; display: flex; flex-direction: column; min-height: 0;
  margin: 0 24px 24px; border-radius: 10px; overflow: hidden;
  border: 1px solid rgba(255,255,255,0.06);
  box-shadow: 0 2px 12px rgba(0,0,0,0.25);
}

.log-toolbar {
  flex-shrink: 0; display: flex; align-items: center; justify-content: space-between;
  padding: 8px 14px; background: #1E2A3B;
  border-bottom: 1px solid rgba(255,255,255,0.06);
}
.log-tb-left  { display: flex; align-items: center; gap: 12px; }
.log-tb-right { display: flex; align-items: center; gap: 4px; }

.sse-status { display: flex; align-items: center; gap: 5px; font-size: 11px; color: #64748B; font-weight: 500; }
.sse-dot { width: 6px; height: 6px; border-radius: 50%; background: #374151; flex-shrink: 0; }
.sse-on .sse-dot { background: #22C55E; animation: pulse 1.5s ease-in-out infinite; }
.sse-on { color: #22C55E; }
.log-line-count { font-size: 11px; color: #4B5563; }

.log-btn {
  display: flex; align-items: center; gap: 4px;
  padding: 4px 8px; height: 26px;
  background: rgba(255,255,255,0.04);
  border: 1px solid rgba(255,255,255,0.08); border-radius: 5px;
  font-size: 11px; color: #64748B; cursor: pointer; font-family: inherit; transition: all 0.15s;
}
.log-btn:hover { background: rgba(255,255,255,0.08); color: #94A3B8; }
.log-btn-active { color: #4F7CFF; border-color: rgba(79,124,255,0.3); }

.log-body {
  flex: 1; overflow-y: auto; min-height: 0;
  background: #0F172A; padding: 12px 16px;
  scrollbar-width: thin; scrollbar-color: #1E3A5F transparent;
  font-family: 'Consolas', 'Cascadia Code', 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 12px; line-height: 1.7;
}
.log-body::-webkit-scrollbar { width: 5px; }
.log-body::-webkit-scrollbar-thumb { background: #1E3A5F; border-radius: 3px; }

.log-line { white-space: pre-wrap; word-break: break-all; }
.lc-normal  { color: #CBD5E1; }
.lc-success { color: #4ADE80; }
.lc-error   { color: #F87171; }
.lc-warn    { color: #FCD34D; }
.lc-batch   { color: #60A5FA; font-weight: 600; margin: 2px 0; }

.log-empty {
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  height: 100%; gap: 10px; user-select: none;
}
.log-empty p    { font-size: 14px; color: #4B5563; margin: 0; }
.log-empty span { font-size: 12px; color: #374151; }
</style>
