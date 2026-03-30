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

/** 获取系统统计 */
export function getSystemStats() {
  return request('/system/stats')
}

// ── flowtran 数据驱动接口（替代 getDomains / getTransactions） ──────────────

/**
 * 获取所有业务领域列表（来自 Neo4j 交易图，实时查询）
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
 * 查询某交易的完整调用链路（交易编排 + Neo4j 调用关系 + 元数据缓存富化）
 * @param {string} txId  flowtran.id，如 TC0033
 */
export function getFlowtranChain(txId) {
  return request(`/flowtran/transactions/${encodeURIComponent(txId)}/chain`)
}

/**
 * 热重载元数据缓存（不重启服务）
 */
export function refreshFlowtranCache() {
  return request('/flowtran/cache/refresh', { method: 'POST' })
}

/**
 * 查询元数据缓存当前统计
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
