<template>
  <header class="app-header">
    <div class="header-left">
      <div class="logo">
        <svg width="32" height="32" viewBox="0 0 32 32" fill="none" xmlns="http://www.w3.org/2000/svg">
          <defs>
            <linearGradient id="axon-bg" x1="0" y1="0" x2="32" y2="32" gradientUnits="userSpaceOnUse">
              <stop offset="0%" stop-color="#2448E8"/>
              <stop offset="100%" stop-color="#8B28F5"/>
            </linearGradient>
          </defs>
          <rect width="32" height="32" rx="8" fill="url(#axon-bg)"/>
          <circle cx="9" cy="9.5" r="3.5" fill="white"/>
          <path d="M12 12 L18.5 18.5" stroke="white" stroke-width="2.2" stroke-linecap="round"/>
          <circle cx="23" cy="14" r="4" fill="white" opacity="0.18"/>
          <circle cx="23" cy="22" r="4" fill="white" opacity="0.18"/>
          <circle cx="15" cy="26" r="4" fill="white" opacity="0.18"/>
          <path d="M18.5 18.5 L23 14" stroke="white" stroke-width="1.6" stroke-linecap="round"/>
          <path d="M18.5 18.5 L23 22" stroke="white" stroke-width="1.6" stroke-linecap="round"/>
          <path d="M18.5 18.5 L15 26" stroke="white" stroke-width="1.6" stroke-linecap="round"/>
          <circle cx="23" cy="14" r="2.2" fill="white" opacity="0.95"/>
          <circle cx="23" cy="22" r="2.2" fill="white" opacity="0.95"/>
          <circle cx="15" cy="26" r="2.2" fill="white" opacity="0.95"/>
        </svg>
      </div>
      <span class="platform-name">
        <span class="name-axon">Axon</span><span class="name-link">Link</span>
      </span>
    </div>

    <div class="header-center">
      <div class="search-box" :class="{ 'search-error': isNotFound, 'search-loading': isLoading }">
        <svg v-if="!isLoading" class="search-icon" width="16" height="16" viewBox="0 0 16 16" fill="none">
          <circle cx="6.5" cy="6.5" r="5" stroke="#8C94A6" stroke-width="1.5"/>
          <path d="M10.5 10.5L14 14" stroke="#8C94A6" stroke-width="1.5" stroke-linecap="round"/>
        </svg>
        <svg v-else class="search-icon search-spin" width="16" height="16" viewBox="0 0 16 16" fill="none">
          <circle cx="8" cy="8" r="6" stroke="rgba(255,255,255,0.2)" stroke-width="2"/>
          <path d="M8 2a6 6 0 0 1 6 6" stroke="#4F7CFF" stroke-width="2" stroke-linecap="round"/>
        </svg>

        <input
          v-model="searchText"
          type="text"
          placeholder="输入交易码精确查找（如 TD0101），按 Enter 确认"
          class="search-input"
          @keydown.enter="doSearch"
          @keydown.esc="clearSearch"
          @input="onInput"
        />

        <span v-if="isNotFound" class="search-error-tip">暂无此交易</span>
        <button v-if="searchText && !isLoading" class="search-clear" @click="clearSearch" title="清除搜索">
          <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
            <path d="M2 2l8 8M10 2l-8 8" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
          </svg>
        </button>
        <kbd v-else-if="!searchText && !isLoading" class="search-kbd">⌘K</kbd>
      </div>
    </div>

    <div class="header-right">
      <!-- 日/夜模式切换按钮 -->
      <button class="icon-btn theme-toggle" :title="isDark ? '当前夜间模式，点击切换日间' : '当前日间模式，点击切换夜间'" @click="$emit('toggleTheme')">
        <transition name="theme-icon" mode="out-in">
          <!-- 白天模式时显示太阳（当前是日间） -->
          <svg v-if="!isDark" key="sun" width="18" height="18" viewBox="0 0 24 24" fill="none" class="sun-icon">
            <circle cx="12" cy="12" r="4.5" stroke="currentColor" stroke-width="1.5"/>
            <path d="M12 2v2M12 20v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M2 12h2M20 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42"
              stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
          </svg>
          <!-- 夜间模式时显示月亮（当前是夜间） -->
          <svg v-else key="moon" width="18" height="18" viewBox="0 0 24 24" fill="none" class="moon-icon">
            <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"
              stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </transition>
      </button>

      <div class="user-info" @click="toggleUserMenu">
        <div class="avatar">管</div>
        <span class="username">管理员</span>
        <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
          <path d="M3 4.5L6 7.5L9 4.5" stroke="#8C94A6" stroke-width="1.5" stroke-linecap="round"/>
        </svg>

        <div v-if="showUserMenu" class="user-dropdown">
          <div class="dropdown-item">
            <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
              <circle cx="7" cy="5" r="3" stroke="currentColor" stroke-width="1.3"/>
              <path d="M1.5 13c0-3.04 2.46-5.5 5.5-5.5s5.5 2.46 5.5 5.5" stroke="currentColor" stroke-width="1.3"/>
            </svg>
            个人信息
          </div>
          <div class="dropdown-item">
            <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
              <circle cx="7" cy="7" r="5.5" stroke="currentColor" stroke-width="1.3"/>
              <path d="M7 4v3l2 2" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
            </svg>
            操作日志
          </div>
          <div class="dropdown-divider"></div>
          <div class="dropdown-item logout">
            <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
              <path d="M5 2H2.5A1.5 1.5 0 0 0 1 3.5v7A1.5 1.5 0 0 0 2.5 12H5" stroke="currentColor" stroke-width="1.3"/>
              <path d="M9 4.5L12.5 7 9 9.5" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
              <path d="M5.5 7H12" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
            </svg>
            退出登录
          </div>
        </div>
      </div>
    </div>
  </header>
</template>

<script setup>
import { ref } from 'vue'

const props = defineProps({
  isDark: { type: Boolean, default: false }
})

const emit = defineEmits(['search', 'toggleTheme'])

const searchText   = ref('')
const showUserMenu = ref(false)
const isLoading    = ref(false)
const isNotFound   = ref(false)

const setResult = (found) => {
  isLoading.value  = false
  isNotFound.value = !found
}

const onInput = () => { isNotFound.value = false }

const doSearch = () => {
  const code = searchText.value.trim()
  if (!code) return
  isLoading.value  = true
  isNotFound.value = false
  emit('search', code)
}

const clearSearch = () => {
  searchText.value  = ''
  isLoading.value   = false
  isNotFound.value  = false
  emit('search', '')
}

const toggleUserMenu = () => { showUserMenu.value = !showUserMenu.value }

defineExpose({ setResult })
</script>

<style scoped>
.app-header {
  height: 56px;
  background: #1B2B4B;
  display: flex;
  align-items: center;
  padding: 0 20px;
  gap: 16px;
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  z-index: 100;
  box-shadow: 0 1px 0 rgba(255,255,255,0.06);
}

.header-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 240px;
}

.logo {
  display: flex;
  align-items: center;
}

.platform-name {
  font-size: 16px;
  font-weight: 700;
  letter-spacing: 0.5px;
  white-space: nowrap;
  font-family: 'SF Pro Display', 'Segoe UI', system-ui, -apple-system, sans-serif;
}

.name-axon { color: #FFFFFF; }
.name-link  { color: #7EB8FF; }

.header-center {
  flex: 1;
  max-width: 480px;
  margin: 0 auto;
}

.search-box {
  display: flex;
  align-items: center;
  background: rgba(255,255,255,0.08);
  border: 1px solid rgba(255,255,255,0.12);
  border-radius: 8px;
  padding: 0 12px;
  height: 36px;
  gap: 8px;
  transition: all 0.2s;
}

.search-box:focus-within {
  background: rgba(255,255,255,0.12);
  border-color: rgba(79,124,255,0.6);
  box-shadow: 0 0 0 3px rgba(79,124,255,0.15);
}

.search-icon { flex-shrink: 0; }

.search-input {
  flex: 1;
  background: none;
  border: none;
  outline: none;
  color: #E8EDFB;
  font-size: 13px;
}

.search-input::placeholder { color: #5C6E8C; }

.search-kbd {
  font-size: 10px;
  color: #5C6E8C;
  background: rgba(255,255,255,0.06);
  border: 1px solid rgba(255,255,255,0.1);
  border-radius: 4px;
  padding: 2px 5px;
  font-family: inherit;
  flex-shrink: 0;
}

.search-error { border-color: rgba(255,77,79,0.6) !important; }
.search-error .search-icon circle { stroke: rgba(255,77,79,0.6); }
.search-error .search-icon path  { stroke: rgba(255,77,79,0.6); }

.search-error-tip {
  font-size: 11px;
  color: #FF6B6B;
  white-space: nowrap;
  flex-shrink: 0;
}

.search-clear {
  width: 18px; height: 18px;
  display: flex; align-items: center; justify-content: center;
  background: rgba(255,255,255,0.1);
  border: none; border-radius: 50%;
  cursor: pointer; color: #8C94A6;
  flex-shrink: 0;
  transition: all 0.15s;
  padding: 0;
}
.search-clear:hover { background: rgba(255,255,255,0.2); color: #E8EDFB; }

@keyframes spin { to { transform: rotate(360deg); } }
.search-spin { animation: spin 0.8s linear infinite; flex-shrink: 0; }

.header-right {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
}

.icon-btn {
  position: relative;
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(255,255,255,0.06);
  border: 1px solid rgba(255,255,255,0.08);
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.2s, border-color 0.2s;
  color: #8C94A6;
}

.icon-btn:hover {
  background: rgba(255,255,255,0.12);
  color: #E8EDFB;
}

/* 主题切换按钮 */
.theme-toggle {
  overflow: hidden;
}

.sun-icon {
  color: #FFD166;
  filter: drop-shadow(0 0 4px rgba(255,209,102,0.5));
}

.moon-icon {
  color: #A8C4E8;
}

/* 切换图标过渡动画 */
.theme-icon-enter-active,
.theme-icon-leave-active {
  transition: opacity 0.2s, transform 0.3s;
}
.theme-icon-enter-from {
  opacity: 0;
  transform: rotate(-90deg) scale(0.6);
}
.theme-icon-leave-to {
  opacity: 0;
  transform: rotate(90deg) scale(0.6);
}

.user-info {
  position: relative;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.2s;
}

.user-info:hover { background: rgba(255,255,255,0.08); }

.avatar {
  width: 30px;
  height: 30px;
  background: linear-gradient(135deg, #4F7CFF, #6B4FFF);
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
  color: white;
}

.username { font-size: 13px; color: #C8D3E8; }

.user-dropdown {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  background: var(--bg-dropdown);
  border-radius: 10px;
  box-shadow: 0 8px 32px rgba(0,0,0,0.2), 0 2px 8px rgba(0,0,0,0.12);
  min-width: 160px;
  padding: 6px;
  border: 1px solid var(--border);
  z-index: 200;
}

.dropdown-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 12px;
  font-size: 13px;
  color: var(--text-secondary);
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.15s;
}

.dropdown-item:hover { background: var(--bg-domain-hover); }
.dropdown-item.logout { color: #FF4D4F; }

.dropdown-divider {
  height: 1px;
  background: var(--border-subtle);
  margin: 4px 0;
}
</style>
