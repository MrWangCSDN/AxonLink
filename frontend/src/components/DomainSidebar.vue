<template>
  <aside class="domain-sidebar">
    <div class="sidebar-title">业务领域</div>

    <nav class="domain-list">
      <div
        v-for="domain in domains"
        :key="domain.id"
        class="domain-item"
        :class="{ active: !isBuildActive && activeDomainId === domain.id }"
        @click="$emit('select', domain)"
      >
        <div class="domain-icon" :class="`icon-${domain.id}`">
          <component :is="getDomainIcon(domain.id)" />
        </div>
        <div class="domain-info">
          <span class="domain-name">{{ domain.name }}</span>
          <span class="domain-count">{{ domain.count }} 支交易</span>
        </div>
        <span class="domain-badge">{{ domain.count }}</span>
      </div>
    </nav>

    <!-- 编译控制台入口 -->
    <div class="build-divider"></div>
    <div
      class="build-console-item"
      :class="{ active: isBuildActive }"
      @click="$emit('open-build')"
    >
      <div class="build-console-icon">
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
          <rect x="1.5" y="1.5" width="13" height="13" rx="2.5" stroke="currentColor" stroke-width="1.3"/>
          <path d="M4.5 6L7 8.5L4.5 11" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/>
          <path d="M8.5 11H11.5" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
        </svg>
      </div>
      <span class="build-console-label">编译控制台</span>
    </div>

    <div class="sidebar-footer">
      <div class="stats-row">
        <div class="stat-item">
          <span class="stat-label">共 {{ systemStats.totalDomains }} 个领域</span>
        </div>
        <div class="stat-item">
          <span class="stat-label">共 {{ systemStats.totalTransactions }} 支交易</span>
        </div>
      </div>
      <div class="system-status">
        <span class="status-dot" :class="systemStats.status === 'error' ? 'status-error' : 'status-normal'"></span>
        <span class="status-text" :class="systemStats.status === 'error' ? 'text-error' : ''">{{ systemStats.statusText }}</span>
      </div>
    </div>
  </aside>
</template>

<script setup>
import { h } from 'vue'

const props = defineProps({
  domains:        { type: Array,   required: true },
  activeDomainId: { type: String,  default: '' },
  systemStats:    { type: Object,  default: () => ({ totalDomains: 0, totalTransactions: 0, status: 'normal', statusText: '系统运行正常' }) },
  isBuildActive:  { type: Boolean, default: false }
})
defineEmits(['select', 'open-build'])

const getDomainIcon = (id) => {
  const icons = {
    public: () => h('svg', { width: 16, height: 16, viewBox: '0 0 16 16', fill: 'none' }, [
      h('circle', { cx: 8, cy: 8, r: 6.5, stroke: 'currentColor', 'stroke-width': 1.5 }),
      h('path', { d: 'M8 1.5C8 1.5 5.5 4 5.5 8s2.5 6.5 2.5 6.5', stroke: 'currentColor', 'stroke-width': 1.5 }),
      h('path', { d: 'M8 1.5C8 1.5 10.5 4 10.5 8s-2.5 6.5-2.5 6.5', stroke: 'currentColor', 'stroke-width': 1.5 }),
      h('path', { d: 'M1.5 8h13', stroke: 'currentColor', 'stroke-width': 1.5, 'stroke-linecap': 'round' })
    ]),
    loan: () => h('svg', { width: 16, height: 16, viewBox: '0 0 16 16', fill: 'none' }, [
      h('rect', { x: 1.5, y: 4, width: 13, height: 9, rx: 1.5, stroke: 'currentColor', 'stroke-width': 1.5 }),
      h('path', { d: 'M4.5 4V3A1.5 1.5 0 0 1 6 1.5h4A1.5 1.5 0 0 1 11.5 3v1', stroke: 'currentColor', 'stroke-width': 1.5 }),
      h('circle', { cx: 8, cy: 8.5, r: 1.5, stroke: 'currentColor', 'stroke-width': 1.3 })
    ]),
    deposit: () => h('svg', { width: 16, height: 16, viewBox: '0 0 16 16', fill: 'none' }, [
      h('path', { d: 'M2 5.5C2 4.12 3.12 3 4.5 3h7C12.88 3 14 4.12 14 5.5v5C14 11.88 12.88 13 11.5 13h-7C3.12 13 2 11.88 2 10.5v-5z', stroke: 'currentColor', 'stroke-width': 1.4 }),
      h('path', { d: 'M5.5 8h5M8 5.5v5', stroke: 'currentColor', 'stroke-width': 1.4, 'stroke-linecap': 'round' })
    ]),
    settlement: () => h('svg', { width: 16, height: 16, viewBox: '0 0 16 16', fill: 'none' }, [
      h('path', { d: 'M2.5 5.5h11M10.5 3l3 2.5-3 2.5', stroke: 'currentColor', 'stroke-width': 1.5, 'stroke-linecap': 'round', 'stroke-linejoin': 'round' }),
      h('path', { d: 'M13.5 10.5h-11M5.5 8l-3 2.5 3 2.5', stroke: 'currentColor', 'stroke-width': 1.5, 'stroke-linecap': 'round', 'stroke-linejoin': 'round' })
    ])
  }
  const fn = icons[id]
  return fn ? fn() : h('svg', { width: 16, height: 16, viewBox: '0 0 16 16', fill: 'none' }, [
    h('rect', { x: 2, y: 2, width: 12, height: 12, rx: 2, stroke: 'currentColor', 'stroke-width': 1.4 })
  ])
}
</script>

<style scoped>
.domain-sidebar {
  width: 200px;
  flex-shrink: 0;
  background: var(--bg-sidebar);
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  transition: background 0.3s;
}

.sidebar-title {
  font-size: 11px;
  font-weight: 600;
  color: var(--text-faint);
  letter-spacing: 0.08em;
  text-transform: uppercase;
  padding: 20px 16px 10px;
}

.domain-list {
  flex: 1;
  overflow-y: auto;
  padding: 0 8px;
  scrollbar-width: thin;
  scrollbar-color: var(--scrollbar-thumb) transparent;
}
.domain-list::-webkit-scrollbar { width: 4px; }
.domain-list::-webkit-scrollbar-thumb { background: var(--scrollbar-thumb); border-radius: 2px; }

.domain-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 8px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s;
}
.domain-item:hover { background: var(--bg-domain-hover); }
.domain-item.active { background: var(--bg-domain-active); }
.domain-item.active .domain-name { color: var(--text-primary); font-weight: 600; }

.domain-icon {
  width: 28px; height: 28px;
  border-radius: 7px;
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
  transition: background 0.15s;
}
.icon-public     { background: rgba(79,124,255,0.1);  color: #4F7CFF; }
.icon-loan       { background: rgba(251,146,60,0.1);  color: #F97316; }
.icon-deposit    { background: rgba(34,197,94,0.1);   color: #22C55E; }
.icon-settlement { background: rgba(168,85,247,0.1);  color: #A855F7; }
.domain-item.active .icon-public     { background: rgba(79,124,255,0.18); }
.domain-item.active .icon-loan       { background: rgba(251,146,60,0.18); }
.domain-item.active .icon-deposit    { background: rgba(34,197,94,0.18); }
.domain-item.active .icon-settlement { background: rgba(168,85,247,0.18); }

.domain-info { flex: 1; min-width: 0; }
.domain-name  { display: block; font-size: 13px; color: var(--text-secondary); font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.domain-count { display: block; font-size: 11px; color: var(--text-faint); margin-top: 1px; }

.domain-badge {
  font-size: 11px; font-weight: 600;
  color: var(--text-faint);
  background: var(--bg-badge);
  min-width: 22px; height: 18px;
  display: flex; align-items: center; justify-content: center;
  border-radius: 9px; padding: 0 5px;
  flex-shrink: 0;
}
.domain-item.active .domain-badge { background: rgba(79,124,255,0.12); color: #4F7CFF; }

/* ── 编译控制台入口 ── */
.build-divider {
  height: 1px;
  background: var(--border);
  margin: 8px 16px;
  opacity: 0.5;
}

.build-console-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 16px;
  cursor: pointer;
  transition: background 0.15s;
  margin: 0 8px;
  border-radius: 8px;
}
.build-console-item:hover { background: var(--bg-domain-hover); }
.build-console-item.active { background: var(--bg-domain-active); }

.build-console-icon {
  width: 28px; height: 28px;
  border-radius: 7px;
  background: rgba(99,102,241,0.1);
  color: #6366F1;
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
}
.build-console-item.active .build-console-icon { background: rgba(99,102,241,0.18); }

.build-console-label {
  font-size: 13px;
  color: var(--text-secondary);
  font-weight: 500;
}
.build-console-item.active .build-console-label { color: var(--text-primary); font-weight: 600; }

/* ── 底部统计 ── */
.sidebar-footer {
  flex-shrink: 0;
  padding: 12px 16px 16px;
  border-top: 1px solid var(--border);
}
.stats-row { display: flex; flex-direction: column; gap: 3px; margin-bottom: 8px; }
.stat-label { font-size: 11px; color: var(--text-faint); }

.system-status { display: flex; align-items: center; gap: 6px; }
.status-dot { width: 6px; height: 6px; border-radius: 50%; flex-shrink: 0; }
.status-normal { background: #22C55E; }
.status-error  { background: #EF4444; }
.status-text   { font-size: 11px; color: var(--text-faint); }
.text-error    { color: #EF4444; }
</style>
