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
