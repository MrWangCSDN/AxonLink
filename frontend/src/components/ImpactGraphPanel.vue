<template>
  <Teleport to="body">
    <div v-if="visible" class="igp-backdrop" @click.self="$emit('close')">
      <div class="igp-panel" :class="{ 'igp-dark': isDark }">

        <!-- 头部 -->
        <div class="igp-header">
          <div class="igp-header-left">
            <span class="igp-node-type-badge" :class="`igp-badge-${nodeType}`">
              {{ nodeType === 'service' ? '服务' : '构件' }}
            </span>
            <div class="igp-title-block">
              <span class="igp-node-code">{{ node?.code }}</span>
              <span class="igp-node-name">{{ node?.name }}</span>
            </div>
          </div>
          <div class="igp-header-actions">
            <button class="igp-excel-btn" :disabled="loading || noData" @click="downloadExcel" title="下载 Excel">
              <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
                <rect x="1.5" y="1.5" width="11" height="11" rx="1.5" stroke="currentColor" stroke-width="1.3"/>
                <path d="M4.5 5.5L7 8.5L9.5 5.5" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/>
                <path d="M7 8.5V3" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
                <path d="M3.5 11h7" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
              </svg>
              下载 Excel
            </button>
            <button class="igp-close-btn" @click="$emit('close')">
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
                <path d="M4 4L12 12M12 4L4 12" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
              </svg>
            </button>
          </div>
        </div>

        <!-- 内容区 -->
        <div class="igp-body">

          <!-- 加载中 -->
          <div v-if="loading" class="igp-loading">
            <div class="igp-spinner"/>
            <span>正在分析调用关系…</span>
          </div>

          <!-- 未找到数据 -->
          <div v-else-if="noData" class="igp-empty">
            <svg width="48" height="48" viewBox="0 0 48 48" fill="none">
              <circle cx="24" cy="24" r="20" stroke="currentColor" stroke-width="1.5" opacity="0.3"/>
              <path d="M24 16v9M24 30v2" stroke="currentColor" stroke-width="2" stroke-linecap="round" opacity="0.5"/>
            </svg>
            <p>未在调用图谱中找到该节点</p>
            <span class="igp-empty-hint">可能原因：未完成全量扫描，或该节点尚未建立调用边</span>
          </div>

          <!-- 图谱内容 -->
          <template v-else>
            <!-- 两列布局 -->
            <div class="igp-columns">

              <!-- 左列：上游影响 -->
              <div class="igp-col igp-col-upstream">
                <div class="igp-col-title igp-col-title-up">
                  <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
                    <path d="M7 11V3M7 3L3.5 6.5M7 3l3.5 3.5" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>
                  </svg>
                  上游影响
                </div>

                <!-- 上游服务 -->
                <div class="igp-section">
                  <div class="igp-section-header">
                    <span class="igp-section-label igp-label-service">APS 服务层</span>
                    <span class="igp-count-badge">{{ upstream.servicesTotalCount }}</span>
                  </div>
                  <div v-if="upstream.services?.length" class="igp-list">
                    <div v-for="svc in upstream.services" :key="svc.service_code" class="igp-item igp-item-service">
                      <span class="igp-item-code">{{ svc.service_code }}</span>
                      <span class="igp-item-name">{{ svc.service_name }}</span>
                    </div>
                    <div v-if="upstream.servicesTruncated" class="igp-truncated">
                      … 共 {{ upstream.servicesTotalCount }} 条，下载 Excel 查看全部
                    </div>
                  </div>
                  <div v-else class="igp-list-empty">暂无上游服务数据</div>
                </div>

                <!-- 影响交易 -->
                <div class="igp-section igp-section-tx">
                  <div class="igp-section-header">
                    <span class="igp-section-label igp-label-tx">影响交易</span>
                    <span class="igp-count-badge igp-count-tx">{{ upstream.transactionsTotalCount }}</span>
                  </div>
                  <div v-if="upstream.transactions?.length" class="igp-list">
                    <div v-for="tx in upstream.transactions" :key="tx.tx_code" class="igp-item igp-item-tx">
                      <span class="igp-item-code igp-tx-code">{{ tx.tx_code }}</span>
                      <span class="igp-item-name">{{ tx.tx_name }}</span>
                      <span class="igp-item-domain">{{ tx.domain_name }}</span>
                    </div>
                    <div v-if="upstream.transactionsTruncated" class="igp-truncated">
                      … 共 {{ upstream.transactionsTotalCount }} 条，下载 Excel 查看全部
                    </div>
                  </div>
                  <div v-else class="igp-list-empty">暂无影响交易数据</div>
                </div>
              </div>

              <!-- 中间竖线 + 节点 -->
              <div class="igp-center">
                <div class="igp-center-line igp-line-up"/>
                <div class="igp-center-node" :class="`igp-node-${nodeType}`">
                  <span class="igp-cn-prefix">{{ node?.prefix || (nodeType === 'service' ? 'pbs' : 'pbc') }}</span>
                  <span class="igp-cn-code">{{ node?.code }}</span>
                  <span class="igp-cn-name">{{ node?.name }}</span>
                </div>
                <div class="igp-center-line igp-line-down"/>
              </div>

              <!-- 右列：下游调用 -->
              <div class="igp-col igp-col-downstream">
                <div class="igp-col-title igp-col-title-down">
                  <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
                    <path d="M7 3v8M7 11L3.5 7.5M7 11l3.5-3.5" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>
                  </svg>
                  下游调用
                </div>
                <div class="igp-section">
                  <div class="igp-section-header">
                    <span class="igp-section-label igp-label-comp">构件/服务</span>
                    <span class="igp-count-badge igp-count-down">{{ downstream.totalCount }}</span>
                  </div>
                  <div v-if="downstream.components?.length" class="igp-list">
                    <div v-for="comp in downstream.components" :key="comp.class_fqn" class="igp-item igp-item-comp">
                      <span class="igp-item-layer-badge" :class="`igp-layer-${comp.layer?.toLowerCase()}`">
                        {{ comp.layer }}
                      </span>
                      <span class="igp-item-code">{{ comp.class_name }}</span>
                    </div>
                    <div v-if="downstream.truncated" class="igp-truncated">
                      … 共 {{ downstream.totalCount }} 条，下载 Excel 查看全部
                    </div>
                  </div>
                  <div v-else class="igp-list-empty">暂无下游调用数据</div>
                </div>
              </div>

            </div><!-- /igp-columns -->
          </template>
        </div><!-- /igp-body -->

      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { ref, watch } from 'vue'
import { getImpactSummary, downloadImpactExcel } from '../api/index.js'

const props = defineProps({
  visible:  { type: Boolean, default: false },
  node:     { type: Object,  default: null  },
  nodeType: { type: String,  default: 'service' }, // 'service' | 'component'
  reg:      { type: Object,  default: null  },      // NODE_FILE_REGISTRY 条目
  isDark:   { type: Boolean, default: false },
})
defineEmits(['close'])

const loading  = ref(false)
const noData   = ref(false)
const upstream   = ref({ services:[], servicesTotalCount:0, servicesTruncated:false, transactions:[], transactionsTotalCount:0, transactionsTruncated:false })
const downstream = ref({ components:[], totalCount:0, truncated:false })

watch(() => props.visible, async (v) => {
  if (!v || !props.node) return
  loading.value = true
  noData.value  = false
  upstream.value   = { services:[], servicesTotalCount:0, servicesTruncated:false, transactions:[], transactionsTotalCount:0, transactionsTruncated:false }
  downstream.value = { components:[], totalCount:0, truncated:false }
  try {
    const fqn       = props.reg?.fqn       || ''
    const className = props.reg?.className || props.node?.code || ''
    const nodeCode  = props.node?.code     || ''
    const data = await getImpactSummary(fqn, className, 10, nodeCode, props.nodeType)
    noData.value     = data.noData
    upstream.value   = data.upstream   || upstream.value
    downstream.value = data.downstream || downstream.value
  } catch (e) {
    noData.value = true
  } finally {
    loading.value = false
  }
})

function downloadExcel() {
  const fqn       = props.reg?.fqn       || ''
  const className = props.reg?.className || props.node?.code || ''
  const nodeName  = props.node?.name     || props.node?.code || '节点'
  downloadImpactExcel(fqn, className, nodeName)
}
</script>

<style scoped>
/* ── 遮罩 ── */
.igp-backdrop {
  position: fixed; inset: 0; z-index: 2100;
  background: rgba(0,0,0,.5);
  display: flex; align-items: center; justify-content: center;
  backdrop-filter: blur(3px);
}

/* ── 面板 ── */
.igp-panel {
  width: min(900px, 96vw);
  max-height: 85vh;
  background: #fff;
  border-radius: 12px;
  display: flex; flex-direction: column;
  box-shadow: 0 20px 60px rgba(0,0,0,.18);
  overflow: hidden;
}
.igp-dark.igp-panel { background: #1a1d27; color: #e2e8f0; }

/* ── 头部 ── */
.igp-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 16px 20px 14px;
  border-bottom: 1px solid #eef0f5;
  flex-shrink: 0;
}
.igp-dark .igp-header { border-color: #2d3148; }
.igp-header-left { display: flex; align-items: center; gap: 12px; }
.igp-node-type-badge {
  font-size: 11px; font-weight: 600; padding: 3px 8px; border-radius: 4px; flex-shrink: 0;
}
.igp-badge-service  { background: #e8f5e9; color: #2e7d32; }
.igp-badge-component{ background: #fff3e0; color: #e65100; }
.igp-dark .igp-badge-service   { background: rgba(46,125,50,.2);  color: #81c784; }
.igp-dark .igp-badge-component { background: rgba(230,81,0,.2);   color: #ffb74d; }
.igp-title-block { display: flex; align-items: baseline; gap: 8px; }
.igp-node-code { font-size: 14px; font-weight: 700; color: #1a1d27; }
.igp-dark .igp-node-code { color: #e2e8f0; }
.igp-node-name { font-size: 13px; color: #6b7280; }

.igp-header-actions { display: flex; align-items: center; gap: 8px; }
.igp-excel-btn {
  display: flex; align-items: center; gap: 5px;
  font-size: 12px; font-weight: 500;
  padding: 5px 12px; border-radius: 6px; cursor: pointer;
  background: #f0fdf4; color: #16a34a;
  border: 1px solid #bbf7d0;
  transition: all .15s;
}
.igp-excel-btn:hover:not(:disabled) { background: #dcfce7; border-color: #86efac; }
.igp-excel-btn:disabled { opacity: .4; cursor: not-allowed; }
.igp-dark .igp-excel-btn { background: rgba(22,163,74,.1); color: #4ade80; border-color: rgba(74,222,128,.2); }
.igp-close-btn {
  width: 28px; height: 28px; border-radius: 6px; border: none; cursor: pointer;
  background: transparent; color: #9ca3af;
  display: flex; align-items: center; justify-content: center;
  transition: all .15s;
}
.igp-close-btn:hover { background: #f3f4f6; color: #374151; }
.igp-dark .igp-close-btn:hover { background: #2d3148; color: #e2e8f0; }

/* ── Body ── */
.igp-body {
  flex: 1; overflow-y: auto; padding: 20px;
  min-height: 300px;
}

/* ── 加载 / 空 ── */
.igp-loading, .igp-empty {
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  height: 260px; gap: 12px; color: #9ca3af;
}
.igp-spinner {
  width: 32px; height: 32px; border-radius: 50%;
  border: 3px solid #e5e7eb; border-top-color: #4F7CFF;
  animation: igp-spin .8s linear infinite;
}
@keyframes igp-spin { to { transform: rotate(360deg); } }
.igp-empty p { font-size: 14px; font-weight: 500; margin: 0; }
.igp-empty-hint { font-size: 12px; color: #c0c4cc; }

/* ── 三列布局 ── */
.igp-columns {
  display: grid;
  grid-template-columns: 1fr 120px 1fr;
  gap: 0;
  align-items: start;
}
.igp-col { display: flex; flex-direction: column; gap: 12px; }
.igp-col-title {
  display: flex; align-items: center; gap: 6px;
  font-size: 13px; font-weight: 600; padding: 0 0 8px;
  border-bottom: 2px solid;
  margin-bottom: 4px;
}
.igp-col-title-up   { color: #2563eb; border-color: #93c5fd; }
.igp-col-title-down { color: #d97706; border-color: #fcd34d; }

/* ── 中间节点 ── */
.igp-center {
  display: flex; flex-direction: column; align-items: center;
  padding: 0 8px;
  padding-top: 30px;
}
.igp-center-line {
  width: 2px; flex: 1; min-height: 40px;
  background: linear-gradient(to bottom, #e2e8f0, #cbd5e1);
}
.igp-center-node {
  display: flex; flex-direction: column; align-items: center; gap: 3px;
  padding: 10px 12px; border-radius: 8px; text-align: center;
  flex-shrink: 0; min-width: 90px;
  box-shadow: 0 2px 8px rgba(0,0,0,.08);
}
.igp-node-service   { background: #f0fdf4; border: 1.5px solid #86efac; }
.igp-node-component { background: #fff7ed; border: 1.5px solid #fed7aa; }
.igp-dark .igp-node-service   { background: rgba(22,163,74,.12); border-color: rgba(74,222,128,.3); }
.igp-dark .igp-node-component { background: rgba(217,119,6,.12); border-color: rgba(252,211,77,.3); }
.igp-cn-prefix { font-size: 10px; font-weight: 700; color: #6b7280; letter-spacing: .5px; }
.igp-cn-code   { font-size: 11px; font-weight: 700; color: #374151; word-break: break-all; }
.igp-cn-name   { font-size: 10px; color: #9ca3af; }
.igp-dark .igp-cn-code { color: #e2e8f0; }

/* ── Section ── */
.igp-section { display: flex; flex-direction: column; gap: 6px; }
.igp-section-tx { margin-top: 12px; }
.igp-section-header { display: flex; align-items: center; gap: 6px; margin-bottom: 2px; }
.igp-section-label {
  font-size: 11px; font-weight: 600; padding: 2px 7px; border-radius: 4px;
}
.igp-label-service { background: #dbeafe; color: #1e40af; }
.igp-label-tx      { background: #fce7f3; color: #9d174d; }
.igp-label-comp    { background: #fef3c7; color: #92400e; }
.igp-dark .igp-label-service { background: rgba(30,64,175,.2); color: #93c5fd; }
.igp-dark .igp-label-tx      { background: rgba(157,23,77,.2); color: #f9a8d4; }
.igp-dark .igp-label-comp    { background: rgba(146,64,14,.2); color: #fcd34d; }
.igp-count-badge {
  font-size: 11px; font-weight: 700; padding: 1px 7px; border-radius: 10px;
  background: #e5e7eb; color: #374151;
}
.igp-count-tx   { background: #fce7f3; color: #9d174d; }
.igp-count-down { background: #fef3c7; color: #92400e; }

/* ── 列表 ── */
.igp-list { display: flex; flex-direction: column; gap: 4px; }
.igp-list-empty { font-size: 12px; color: #c0c4cc; padding: 8px 0; }
.igp-item {
  display: flex; align-items: center; gap: 6px;
  padding: 5px 8px; border-radius: 6px;
  font-size: 12px;
  background: #f9fafb;
  border: 1px solid #f3f4f6;
  transition: background .12s;
}
.igp-item:hover { background: #f3f4f6; }
.igp-dark .igp-item { background: #242840; border-color: #2d3148; }
.igp-dark .igp-item:hover { background: #2d3148; }

.igp-item-code {
  font-weight: 600; color: #374151; white-space: nowrap;
  overflow: hidden; text-overflow: ellipsis; max-width: 120px;
}
.igp-dark .igp-item-code { color: #d1d5db; }
.igp-item-name { color: #6b7280; flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.igp-item-domain { color: #9ca3af; font-size: 11px; flex-shrink: 0; }
.igp-tx-code { color: #db2777; }

.igp-item-layer-badge {
  font-size: 10px; font-weight: 700; padding: 1px 5px; border-radius: 3px; flex-shrink: 0;
}
.igp-layer-aps   { background: #dbeafe; color: #1d4ed8; }
.igp-layer-bcs   { background: #f3e8ff; color: #7c3aed; }
.igp-layer-dao   { background: #d1fae5; color: #065f46; }
.igp-layer-pojo  { background: #f5f5f5; color: #737373; }
.igp-layer-other { background: #f5f5f5; color: #737373; }
.igp-dark .igp-layer-aps  { background: rgba(29,78,216,.2);  color: #93c5fd; }
.igp-dark .igp-layer-bcs  { background: rgba(124,58,237,.2); color: #c4b5fd; }
.igp-dark .igp-layer-dao  { background: rgba(6,95,70,.2);    color: #6ee7b7; }
.igp-dark .igp-layer-other{ background: rgba(115,115,115,.1);color: #a3a3a3; }

.igp-truncated {
  font-size: 11px; color: #9ca3af; padding: 4px 8px;
  border: 1px dashed #e5e7eb; border-radius: 4px; text-align: center;
}
.igp-dark .igp-truncated { border-color: #374151; }
</style>
