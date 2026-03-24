<template>
  <div class="page-layout">
    <AppHeader ref="appHeaderRef" :isDark="isDark" @search="handleGlobalSearch" @toggleTheme="toggleTheme" />

    <div class="page-body">
      <DomainSidebar
        :domains="domains"
        :active-domain-id="activeDomain?.id"
        :system-stats="systemStats"
        :is-build-active="activePage === 'build'"
        @select="selectDomain"
        @open-build="activePage = 'build'"
      />


      <main class="main-content">
        <!-- ── 编译控制台页 ── -->
        <BuildConsole v-if="activePage === 'build'" :isDark="isDark" />

        <!-- ── 交易分析页（默认） ── -->
        <template v-else>
        <!-- ── 固定头部：面包屑 + 标题栏 + 统计栏（滚动时不动） ── -->
        <div class="sticky-header">
          <!-- 面包屑 -->
          <div class="breadcrumb">
            <span class="breadcrumb-home">首页</span>
            <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
              <path d="M5 3.5L8.5 7L5 10.5" stroke="#C5CBD7" stroke-width="1.5" stroke-linecap="round"/>
            </svg>
            <span class="breadcrumb-current">{{ activeDomain?.name }}</span>
          </div>

          <!-- 页面标题栏 -->
          <div class="content-header">
            <div class="content-title-group">
              <h2 class="content-title">{{ activeDomain?.name }}</h2>
              <span class="tx-count-badge">{{ totalCount || activeDomain?.count || sortedTransactions.length }} 支交易</span>
            </div>
            <div class="content-actions">
              <div class="domain-search">
                <svg width="15" height="15" viewBox="0 0 15 15" fill="none">
                  <circle cx="6.5" cy="6.5" r="5" stroke="#8C94A6" stroke-width="1.4"/>
                  <path d="M10 10L13 13" stroke="#8C94A6" stroke-width="1.4" stroke-linecap="round"/>
                </svg>
                <input
                  v-model="localSearch"
                  type="text"
                  placeholder="搜索当前领域交易..."
                  class="domain-search-input"
                />
              </div>
              <button class="action-btn" @click="expandAll">
                <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
                  <path d="M2 5L7 2L12 5v4L7 12L2 9V5z" stroke="currentColor" stroke-width="1.3"/>
                  <path d="M7 5v4M4.5 6.5L7 5L9.5 6.5" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
                </svg>
                全部展开
              </button>
              <button class="action-btn" @click="collapseAll">
                <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
                  <path d="M2 5L7 2L12 5v4L7 12L2 9V5z" stroke="currentColor" stroke-width="1.3"/>
                  <path d="M7 9V5M4.5 7.5L7 9L9.5 7.5" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
                </svg>
                全部收起
              </button>
            </div>
          </div>

          <!-- 统计栏 -->
          <div class="stats-bar">
            <svg width="15" height="15" viewBox="0 0 15 15" fill="none">
              <path d="M7.5 2L13 5.5v4L7.5 13L2 9.5v-4L7.5 2z" stroke="#4F7CFF" stroke-width="1.4"/>
              <circle cx="7.5" cy="7.5" r="2" fill="#4F7CFF"/>
            </svg>
            <span class="stats-text">
              <template v-if="globalResult">
                精确匹配：<em style="color:#4F7CFF;font-style:normal;font-weight:600;">{{ globalQuery }}</em>
                &nbsp;·&nbsp;{{ globalResult.domain }}
              </template>
              <template v-else-if="globalNotFound">
                交易码「<em style="color:#FF4D4F;font-style:normal;">{{ globalQuery }}</em>」暂无此交易
              </template>
              <template v-else-if="activeKeyword">
                搜索「<em style="color:#4F7CFF;font-style:normal;">{{ activeKeyword }}</em>」共匹配 {{ totalCount }} 支，已加载 {{ allTransactions.length }} 支
              </template>
              <template v-else>
                共 {{ totalCount || activeDomain?.count || 0 }} 支联机交易，已加载 {{ allTransactions.length }} 支
              </template>
            </span>
          </div>
        </div>

        <!-- ── 可滚动区域：只有卡片列表滚动 ── -->
        <div ref="mainRef" class="cards-scroll">
        <!-- 交易卡片列表 -->
        <div class="transaction-list">
          <!-- wrapper div 用于 IntersectionObserver 感知离屏，自动收起远离视口的卡片 -->
          <div
            v-for="tx in sortedTransactions"
            :key="tx.id"
            :ref="el => observeCardWrapper(el, tx.id)"
          >
            <TransactionCard
              :transaction="tx"
              :default-expanded="false"
              :isDark="isDark"
              :ref="el => { if (el) cardRefs[tx.id] = el; else delete cardRefs[tx.id] }"
            />
          </div>

          <!-- 错误提示 -->
          <div v-if="loadError" class="error-state">
            <svg width="32" height="32" viewBox="0 0 32 32" fill="none">
              <circle cx="16" cy="16" r="14" stroke="#FF4D4F" stroke-width="1.5"/>
              <path d="M16 10v8M16 21v1" stroke="#FF4D4F" stroke-width="2" stroke-linecap="round"/>
            </svg>
            <p>{{ loadError }}</p>
          </div>

          <div v-else-if="!isLoading && sortedTransactions.length === 0" class="empty-state">
            <svg width="48" height="48" viewBox="0 0 48 48" fill="none">
              <circle cx="24" cy="24" r="22" stroke="#E8EDF5" stroke-width="2"/>
              <path d="M16 24h16M24 16v16" stroke="#C5CBD7" stroke-width="2" stroke-linecap="round"/>
            </svg>
            <template v-if="globalNotFound">
              <p>暂无交易</p>
              <span>交易码「{{ globalQuery }}」不存在，请确认后重新输入</span>
            </template>
            <template v-else>
              <p>未找到匹配的交易</p>
              <span>尝试调整搜索关键词</span>
            </template>
          </div>

          <!-- 底部哨兵：全局精确搜索结果不需要分页 -->
          <div v-if="!globalResult && !globalNotFound" ref="sentinelRef" class="sentinel">
            <div v-if="isLoading" class="loading-more">
              <svg class="loading-spin" width="18" height="18" viewBox="0 0 18 18" fill="none">
                <circle cx="9" cy="9" r="7" stroke="#E8EDF5" stroke-width="2"/>
                <path d="M9 2a7 7 0 0 1 7 7" stroke="#4F7CFF" stroke-width="2" stroke-linecap="round"/>
              </svg>
              加载中...
            </div>
            <div v-else-if="hasMore" class="load-hint">向下滚动加载更多</div>
            <div v-else-if="allTransactions.length > 0" class="all-loaded">
              全部 {{ totalCount }} 支交易已加载完毕
            </div>
          </div>
        </div>
        </div><!-- /cards-scroll -->
        </template><!-- /transactions page -->
      </main>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, reactive, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import AppHeader from '../components/AppHeader.vue'
import DomainSidebar from '../components/DomainSidebar.vue'
import TransactionCard from '../components/TransactionCard.vue'
import BuildConsole from './BuildConsole.vue'
import { getDomains, getTransactions, getChain, getSystemStats } from '../api/index.js'

const PAGE_SIZE = 5

// ── 页面切换 ──
const activePage = ref('transactions')  // 'transactions' | 'build'

// ── 日/夜模式 ──
const isDark = ref(localStorage.getItem('theme') === 'dark')

const applyTheme = (dark) => {
  document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light')
  localStorage.setItem('theme', dark ? 'dark' : 'light')
}

const toggleTheme = () => {
  isDark.value = !isDark.value
  applyTheme(isDark.value)
}

// ── 状态 ──
const appHeaderRef = ref(null)
const domains      = ref([])
const activeDomain = ref(null)
const systemStats  = ref({ totalDomains: 0, totalTransactions: 0, status: 'normal', statusText: '系统运行正常' })
const localSearch  = ref('')
const cardRefs     = reactive({})

// ── 全局精确搜索状态（顶部搜索框，仅按交易码精确匹配） ──
const globalResult    = ref(null)   // 找到的完整链路对象
const globalNotFound  = ref(false)  // 搜索过但未找到
const globalQuery     = ref('')     // 当前查询的交易码
let suppressDomainWatch = false     // 切换领域时禁止触发 loadFirstPage

const allTransactions = ref([])  // 累积所有已加载的交易
const serverPage      = ref(1)   // 后端分页页码
const totalCount      = ref(0)
const hasMore         = ref(true)
const isLoading       = ref(false)
const loadError       = ref('')
const mainRef         = ref(null)
const sentinelRef     = ref(null)

// ── 卡片 IntersectionObserver（视口外自动收起，进入视口自动展开） ──
let cardObs = null
const cardWrappers = new Map()  // txId → wrapper DOM element

const initCardObserver = () => {
  cardObs?.disconnect()
  cardObs = new IntersectionObserver(
    entries => {
      entries.forEach(entry => {
        const txId = entry.target.dataset.txId
        if (!txId) return
        if (entry.isIntersecting) {
          cardRefs[txId]?.expand()
        } else {
          cardRefs[txId]?.collapse()
        }
      })
    },
    {
      root: mainRef.value,
      // 视口上下各留 600px 缓冲区，进出缓冲区时才触发，防止频繁闪烁
      rootMargin: '600px 0px',
      threshold: 0
    }
  )
}

const observeCardWrapper = (el, txId) => {
  if (el) {
    el.dataset.txId = txId
    cardWrappers.set(txId, el)
    cardObs?.observe(el)
  } else {
    const prev = cardWrappers.get(txId)
    if (prev) cardObs?.unobserve(prev)
    cardWrappers.delete(txId)
  }
}

// ── 搜索关键词（领域内模糊搜索，只用 localSearch） ──
const activeKeyword = computed(() => localSearch.value.trim())

// 全局精确搜索结果覆盖领域列表；否则返回已分页加载列表
const sortedTransactions = computed(() =>
  globalResult.value ? [globalResult.value] : allTransactions.value
)

// ── 系统健康检查（同时更新统计数字） ──
let healthTimer = null
const checkHealth = async () => {
  try {
    systemStats.value = await getSystemStats()
  } catch {
    systemStats.value = {
      ...(systemStats.value || {}),
      status: 'error',
      statusText: '系统运行异常'
    }
  }
}

// ── 初始化：加载领域列表 ──
const initDomains = async () => {
  try {
    const data = await getDomains()
    domains.value = data
    if (data.length) activeDomain.value = data[0]
  } catch (e) {
    loadError.value = '加载领域失败：' + (e.message || '请检查后端服务是否启动')
  }
  await checkHealth()
}

// ── 重置并从第一页开始 ──
const loadFirstPage = async () => {
  if (!activeDomain.value) return
  // 断开所有卡片观察，列表清空后重新绑定
  cardObs?.disconnect()
  cardWrappers.clear()
  allTransactions.value = []
  serverPage.value      = 1
  totalCount.value      = 0
  hasMore.value         = true
  loadError.value       = ''
  mainRef.value?.scrollTo({ top: 0 })
  await loadMore()
}

// ── 加载下一批（追加到列表底部，不影响滚动位置） ──
const loadMore = async () => {
  if (isLoading.value || !hasMore.value || !activeDomain.value) return
  isLoading.value = true
  try {
    const { list, total } = await getTransactions(
      activeDomain.value.id, serverPage.value, PAGE_SIZE, activeKeyword.value
    )
    totalCount.value = total
    const enriched = await Promise.all(list.map(tx => getChain(tx.id || tx.txCode)))
    allTransactions.value.push(...enriched)
    hasMore.value = allTransactions.value.length < total
    serverPage.value++
  } catch (e) {
    loadError.value = '加载交易数据失败：' + (e.message || '请检查后端服务')
    hasMore.value = false
  } finally {
    isLoading.value = false
  }
}

// ── 监听领域/本地搜索词变化 ──
watch(activeDomain, () => {
  if (suppressDomainWatch) return
  // 切换领域时清除全局搜索状态
  globalResult.value   = null
  globalNotFound.value = false
  globalQuery.value    = ''
  loadFirstPage()
})
watch(localSearch, loadFirstPage)

// ── 底部哨兵：滚动到底自动加载更多 ──
let observer = null
const initObserver = () => {
  observer?.disconnect()
  observer = new IntersectionObserver(
    entries => { if (entries[0].isIntersecting && hasMore.value) loadMore() },
    { root: mainRef.value, threshold: 0.1 }
  )
  if (sentinelRef.value) observer.observe(sentinelRef.value)
}

onMounted(async () => {
  applyTheme(isDark.value)
  // 先初始化 observer，再加载数据——确保 loadFirstPage 渲染卡片时
  // cardObs 已就绪，observeCardWrapper 能立即 observe 每张卡片。
  // 若放在 initDomains 之后的 nextTick，首批卡片已渲染完毕，
  // observe 调用全部成为空操作，导致向上滚动时卡片无法自动展开。
  await nextTick()
  initCardObserver()
  initObserver()
  await initDomains()
  healthTimer = setInterval(checkHealth, 30_000)
})
onBeforeUnmount(() => {
  observer?.disconnect()
  cardObs?.disconnect()
  clearInterval(healthTimer)
})
watch(activeDomain, () => nextTick(initObserver))

// 从编译控制台切回交易页时，DOM 已重新挂载，需重建 observer
watch(activePage, (page) => {
  if (page === 'transactions') {
    nextTick(() => {
      initCardObserver()
      initObserver()
    })
  }
})

const selectDomain = (domain) => {
  globalResult.value   = null
  globalNotFound.value = false
  globalQuery.value    = ''
  activeDomain.value   = domain
  localSearch.value    = ''
  activePage.value     = 'transactions'
}

// ── 全局精确搜索：按交易码精确匹配，跨领域查找 ──
const handleGlobalSearch = async (code) => {
  // 清除：恢复当前领域正常视图
  if (!code) {
    globalResult.value   = null
    globalNotFound.value = false
    globalQuery.value    = ''
    appHeaderRef.value?.setResult(true)
    return
  }

  globalQuery.value    = code.trim().toUpperCase()
  globalResult.value   = null
  globalNotFound.value = false

  try {
    const chain = await getChain(globalQuery.value)
    // 找到：切换到对应领域（静默，不触发 loadFirstPage）
    const targetDomain = domains.value.find(d => d.name === chain.domain)
    if (targetDomain && targetDomain.id !== activeDomain.value?.id) {
      suppressDomainWatch = true
      activeDomain.value  = targetDomain
      await nextTick()
      suppressDomainWatch = false
    }
    globalResult.value = chain
    appHeaderRef.value?.setResult(true)
  } catch {
    globalNotFound.value = true
    appHeaderRef.value?.setResult(false)
  }
}

const expandAll   = () => sortedTransactions.value.forEach(tx => cardRefs[tx.id]?.expand())
const collapseAll = () => sortedTransactions.value.forEach(tx => cardRefs[tx.id]?.collapse())
</script>

<style scoped>
.page-layout {
  height: 100vh;
  background: var(--bg-page);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  transition: background 0.3s;
}

.page-body {
  display: flex;
  flex: 1;
  margin-top: 56px;
  height: calc(100vh - 56px);
  overflow: hidden;
}

.main-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  overflow: hidden;
}

/* ── 固定头部 ── */
.sticky-header {
  flex-shrink: 0;
  background: var(--bg-sticky);
  padding: 24px 24px 0;
  z-index: 10;
  transition: background 0.3s;
}

/* ── 卡片滚动区 ── */
.cards-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 12px 24px 24px;
  min-height: 0;
  scrollbar-width: thin;
  scrollbar-color: var(--scrollbar-thumb) transparent;
}

.cards-scroll::-webkit-scrollbar { width: 6px; }
.cards-scroll::-webkit-scrollbar-track { background: transparent; }
.cards-scroll::-webkit-scrollbar-thumb { background: var(--scrollbar-thumb); border-radius: 3px; }
.cards-scroll::-webkit-scrollbar-thumb:hover { background: var(--scrollbar-thumb-hover); }

/* 面包屑 */
.breadcrumb { display: flex; align-items: center; gap: 6px; margin-bottom: 16px; }
.breadcrumb-home { font-size: 13px; color: var(--text-faint); cursor: pointer; transition: color 0.15s; }
.breadcrumb-home:hover { color: #4F7CFF; }
.breadcrumb-current { font-size: 13px; color: var(--text-primary); font-weight: 500; }

/* 标题栏 */
.content-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; gap: 16px; }
.content-title-group { display: flex; align-items: center; gap: 10px; flex-shrink: 0; }
.content-title { font-size: 20px; font-weight: 700; color: var(--text-primary); margin: 0; white-space: nowrap; }
.tx-count-badge { font-size: 12px; color: var(--text-muted); background: var(--bg-badge); padding: 3px 10px; border-radius: 12px; font-weight: 500; }
.content-actions { display: flex; align-items: center; gap: 8px; }

.domain-search { display: flex; align-items: center; gap: 8px; background: var(--bg-input); border: 1px solid var(--border); border-radius: 8px; padding: 0 12px; height: 34px; transition: border-color 0.2s, box-shadow 0.2s; }
.domain-search:focus-within { border-color: #4F7CFF; box-shadow: 0 0 0 3px rgba(79,124,255,0.1); }
.domain-search-input { border: none; outline: none; font-size: 13px; color: var(--text-secondary); background: transparent; width: 180px; }
.domain-search-input::placeholder { color: var(--text-faint); }

.action-btn { display: flex; align-items: center; gap: 5px; padding: 0 12px; height: 34px; background: var(--bg-action-btn); border: 1px solid var(--border); border-radius: 8px; font-size: 12px; color: var(--text-secondary); cursor: pointer; font-weight: 500; font-family: inherit; transition: all 0.15s; white-space: nowrap; }
.action-btn:hover { background: #EEF3FF; border-color: #C5D5FF; color: #4F7CFF; }

/* 统计栏 */
.stats-bar { display: flex; align-items: center; gap: 8px; padding: 10px 14px; background: var(--bg-stats-bar); border: 1px solid var(--border); border-radius: 8px; margin-bottom: 12px; transition: background 0.3s; }
.stats-text { font-size: 13px; color: var(--text-secondary); font-weight: 500; }
.search-hint { font-size: 12px; color: var(--text-faint); }
.search-hint em { font-style: normal; color: #4F7CFF; font-weight: 500; }

/* 列表 */
.transaction-list { display: flex; flex-direction: column; gap: 10px; }

/* 哨兵 & 加载状态 */
.sentinel { padding: 20px 0 40px; display: flex; justify-content: center; }

.loading-more {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--text-faint);
}

@keyframes spin { to { transform: rotate(360deg); } }
.loading-spin { animation: spin 0.8s linear infinite; }

.load-hint { font-size: 12px; color: var(--text-faint); }

.all-loaded {
  font-size: 12px;
  color: var(--text-faint);
  display: flex;
  align-items: center;
  gap: 8px;
}

.all-loaded::before,
.all-loaded::after {
  content: '';
  flex: 1;
  max-width: 60px;
  height: 1px;
  background: var(--border);
}

/* 错误状态 */
.error-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 40px 20px;
  background: var(--bg-card);
  border: 1px dashed #FFCCC7;
  border-radius: 10px;
}
.error-state p { font-size: 13px; color: #FF4D4F; margin: 0; }

/* 空状态 */
.empty-state { display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 60px 20px; gap: 12px; background: var(--bg-empty); border: 1px dashed var(--border-dashed); border-radius: 10px; }
.empty-state p { font-size: 15px; color: var(--text-primary); font-weight: 500; margin: 0; }
.empty-state span { font-size: 13px; color: var(--text-muted); }
</style>
