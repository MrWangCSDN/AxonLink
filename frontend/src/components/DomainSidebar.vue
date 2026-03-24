<template>
  <aside class="domain-sidebar">
    <div class="sidebar-menu-header" @click="expanded = !expanded">
      <span class="sidebar-menu-label">领域交易</span>
      <svg
        class="sidebar-menu-chevron"
        :class="{ collapsed: !expanded }"
        width="14" height="14" viewBox="0 0 14 14" fill="none"
      >
        <path d="M3 5.5l4 4 4-4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
      </svg>
    </div>

    <nav class="domain-list" :class="{ 'domain-list--hidden': !expanded }">
      <div
        v-for="domain in domains"
        :key="domain.id"
        class="domain-item"
        :class="{ active: activeDomainId === domain.id }"
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
import { h, ref } from 'vue'

const props = defineProps({
  domains:       { type: Array,  required: true },
  activeDomainId:{ type: String, default: '' },
  systemStats:   { type: Object, default: () => ({ totalDomains: 0, totalTransactions: 0, status: 'normal', statusText: '系统运行正常' }) }
})
defineEmits(['select'])

const expanded = ref(true)

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
      h('path', { d: 'M1.5 7h13', stroke: 'currentColor', 'stroke-width': 1.5 }),
      h('path', { d: 'M4 10.5h3', stroke: 'currentColor', 'stroke-width': 1.5, 'stroke-linecap': 'round' }),
      h('path', { d: 'M5 3V2', stroke: 'currentColor', 'stroke-width': 1.5, 'stroke-linecap': 'round' }),
      h('path', { d: 'M11 3V2', stroke: 'currentColor', 'stroke-width': 1.5, 'stroke-linecap': 'round' })
    ]),
    deposit: () => h('svg', { width: 16, height: 16, viewBox: '0 0 16 16', fill: 'none' }, [
      h('path', { d: 'M2 6.5h12M2 9.5h12', stroke: 'currentColor', 'stroke-width': 1.5, 'stroke-linecap': 'round' }),
      h('rect', { x: 1.5, y: 3, width: 13, height: 10, rx: 2, stroke: 'currentColor', 'stroke-width': 1.5 }),
      h('circle', { cx: 8, cy: 8, r: 1.5, fill: 'currentColor' })
    ]),
    settlement: () => h('svg', { width: 16, height: 16, viewBox: '0 0 16 16', fill: 'none' }, [
      h('path', { d: 'M3 8h10M9.5 5l3 3-3 3', stroke: 'currentColor', 'stroke-width': 1.5, 'stroke-linecap': 'round', 'stroke-linejoin': 'round' }),
      h('path', { d: 'M13 4.5V3a1.5 1.5 0 0 0-1.5-1.5h-7A1.5 1.5 0 0 0 3 3v10a1.5 1.5 0 0 0 1.5 1.5h7A1.5 1.5 0 0 0 13 13v-1.5', stroke: 'currentColor', 'stroke-width': 1.5 })
    ])
  }
  return icons[id] || icons.public
}
</script>

<style scoped>
.domain-sidebar {
  width: 240px;
  background: var(--bg-sidebar);
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  padding: 20px 0 0;
  flex-shrink: 0;
  transition: background 0.3s, border-color 0.3s;
}

.sidebar-menu-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 14px 10px 16px;
  cursor: pointer;
  user-select: none;
  border-radius: 6px 6px 0 0;
}

.sidebar-menu-header:hover .sidebar-menu-label {
  color: var(--text-primary);
}

.sidebar-menu-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
  letter-spacing: 0;
  transition: color 0.15s;
}

.sidebar-menu-chevron {
  color: var(--text-faint);
  transition: transform 0.22s ease, color 0.15s;
  flex-shrink: 0;
}

.sidebar-menu-chevron.collapsed {
  transform: rotate(-90deg);
}

.domain-list {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 0 8px;
  overflow: hidden;
  max-height: 600px;
  transition: max-height 0.28s ease, opacity 0.22s ease, padding 0.22s ease;
  opacity: 1;
}

.domain-list--hidden {
  max-height: 0;
  opacity: 0;
  padding-top: 0;
  padding-bottom: 0;
  pointer-events: none;
}

.domain-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 10px;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.15s;
  position: relative;
}

.domain-item:hover {
  background: var(--bg-domain-hover);
}

.domain-item.active {
  background: var(--bg-domain-active);
}

.domain-item.active .domain-name {
  color: var(--text-active);
  font-weight: 600;
}

.domain-item.active .domain-icon {
  background: var(--text-active);
  color: white;
}

.domain-item.active .domain-badge {
  background: var(--text-active);
  color: white;
}

.domain-icon {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: all 0.15s;
}

.icon-public { background: #EEF3FF; color: #4F7CFF; }
.icon-loan { background: #FFF4E6; color: #F76707; }
.icon-deposit { background: #E6FCF5; color: #12B886; }
.icon-settlement { background: #F3F0FF; color: #7950F2; }

.domain-info {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.domain-name {
  font-size: 12px;
  color: var(--text-primary);
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.domain-count {
  font-size: 11px;
  color: var(--text-faint);
}

.domain-badge {
  min-width: 20px;
  height: 20px;
  background: var(--bg-badge);
  color: var(--text-badge);
  border-radius: 10px;
  font-size: 11px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0 6px;
  flex-shrink: 0;
  transition: all 0.15s;
}

.sidebar-footer {
  padding: 16px;
  border-top: 1px solid var(--border-subtle);
  margin-top: auto;
}

.stats-row {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 10px;
}

.stat-label {
  font-size: 12px;
  color: var(--text-faint);
}

.system-status {
  display: flex;
  align-items: center;
  gap: 6px;
}

.status-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex-shrink: 0;
}

.status-dot.status-normal {
  background: #12B886;
  animation: heartbeat 2.4s ease-in-out infinite;
}

.status-dot.status-error {
  background: #FA5252;
}

@keyframes heartbeat {
  0%   { transform: scale(1);   box-shadow: 0 0 0 0   rgba(18,184,134,0.8); }
  12%  { transform: scale(1.4); box-shadow: 0 0 0 4px rgba(18,184,134,0.3); }
  24%  { transform: scale(1);   box-shadow: 0 0 0 0   rgba(18,184,134,0);   }
  36%  { transform: scale(1.25);box-shadow: 0 0 0 3px rgba(18,184,134,0.2); }
  55%  { transform: scale(1);   box-shadow: 0 0 0 0   rgba(18,184,134,0);   }
  100% { transform: scale(1);   box-shadow: 0 0 0 0   rgba(18,184,134,0);   }
}

.status-text {
  font-size: 12px;
  color: #12B886;
  font-weight: 500;
}

.status-text.text-error {
  color: #FA5252;
}
</style>
