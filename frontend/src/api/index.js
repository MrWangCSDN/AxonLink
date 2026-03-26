/**
 * API 请求封装
 * 生产环境：请求 /api（Spring Boot 处理）
 * 开发环境：可通过 vite.config.js proxy 转发到后端
 */

const BASE = '/api'

async function request(url, options = {}) {
  const res = await fetch(BASE + url, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  })
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${url}`)
  const json = await res.json()
  if (json.code !== 200) throw new Error(json.message || '请求失败')
  return json.data
}

/** 获取所有领域列表 */
export function getDomains() {
  return request('/domains')
}

/**
 * 获取某领域下的交易列表（分页 + 模糊搜索）
 * @param {string} domainId  领域 key，如 loan
 * @param {number} page      页码，从 1 开始
 * @param {number} size      每页条数，默认 5
 * @param {string} keyword   交易码或交易名称关键词（可选）
 */
export function getTransactions(domainId, page = 1, size = 5, keyword = '') {
  const kw = keyword ? `&keyword=${encodeURIComponent(keyword)}` : ''
  return request(`/domains/${domainId}/transactions?page=${page}&size=${size}${kw}`)
}

/**
 * 获取单笔交易的完整链路
 * @param {string} txCode  交易编码，如 TD0101
 */
export function getChain(txCode) {
  return request(`/transactions/${txCode}/chain`)
}

/** 获取系统统计 */
export function getSystemStats() {
  return request('/system/stats')
}

// ── flowtran 数据驱动接口（替代 getDomains / getTransactions） ──────────────

/**
 * 获取所有业务领域列表（来自 flowtran 表，实时查询）
 */
export function getFlowtranDomains() {
  return request('/flowtran/domains')
}

/**
 * 分页查询某领域下的交易列表
 * @param {string} domainKey   领域标识，如 deposit
 * @param {number} page        页码，从 1 开始
 * @param {number} size        每页条数
 * @param {string} keyword     模糊搜索关键词（可选）
 */
export function getFlowtranTransactions(domainKey, page = 1, size = 20, keyword = '') {
  const kw = keyword ? `&keyword=${encodeURIComponent(keyword)}` : ''
  return request(`/flowtran/domains/${domainKey}/transactions?page=${page}&size=${size}${kw}`)
}

/**
 * 查询某交易的完整调用链路（flow_step + ServiceNodeCache 富化）
 * @param {string} txId  flowtran.id，如 TC0033
 */
export function getFlowtranChain(txId) {
  return request(`/flowtran/transactions/${encodeURIComponent(txId)}/chain`)
}

/**
 * 热重载 ServiceNodeCache（不重启服务）
 */
export function refreshFlowtranCache() {
  return request('/flowtran/cache/refresh', { method: 'POST' })
}

/**
 * 查询 ServiceNodeCache 当前统计
 */
export function getFlowtranCacheStats() {
  return request('/flowtran/cache/stats')
}

/**
 * 查询当前激活的数据源环境
 */
export function getFlowtranEnv() {
  return request('/flowtran/env')
}

/**
 * 获取服务/构件的影响图谱（上游交易 + 下游调用）
 * @param {string} fqn       全限定类名
 * @param {string} className 简单类名（备用）
 * @param {number} limit     截断阈值，默认 10
 */
export function getImpactSummary(fqn, className, limit = 10, nodeCode = '', nodeType = '') {
  const p = new URLSearchParams()
  if (fqn)       p.set('fqn', fqn)
  if (className) p.set('className', className)
  if (nodeCode)  p.set('nodeCode', nodeCode)
  if (nodeType)  p.set('nodeType', nodeType)
  p.set('limit', limit)
  return request('/callgraph/impact-summary?' + p.toString())
}

/**
 * 下载影响分析 Excel
 * 直接触发浏览器下载，不走 fetch
 */
export function downloadImpactExcel(fqn, className, nodeName) {
  const p = new URLSearchParams()
  if (fqn)       p.set('fqn', fqn)
  if (className) p.set('className', className)
  if (nodeName)  p.set('nodeName', nodeName)
  const url = '/api/callgraph/excel?' + p.toString()
  const a = document.createElement('a')
  a.href = url
  a.download = ''
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
}
