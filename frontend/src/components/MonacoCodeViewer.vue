<template>
  <div class="mcv-root">
    <!-- 标题栏 -->
    <div class="mcv-header">
      <div class="mcv-title">
        <span v-if="node?.prefix" class="node-prefix" :class="`prefix-${node.prefix}`">{{ node.prefix }}</span>
        <span class="mcv-node-name">{{ node?.code }}</span>
        <span class="mcv-sep">·</span>
        <span class="mcv-desc">{{ node?.name }}</span>
      </div>
      <div class="mcv-actions">
        <!-- 定位入口方法 + 关闭所有附加标签，恢复初始状态 -->
        <button v-if="entryMethod" class="mcv-btn" title="定位入口方法并清除其他文件" @click="locateAndClean">
          <svg width="13" height="13" viewBox="0 0 13 13" fill="none">
            <circle cx="6.5" cy="6.5" r="5.5" stroke="currentColor" stroke-width="1.2"/>
            <circle cx="6.5" cy="6.5" r="2"   fill="currentColor"/>
            <path d="M6.5 1v1.5M6.5 10.5V12M1 6.5h1.5M10.5 6.5H12" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"/>
          </svg>
        </button>
        <!-- 全屏 / 退出全屏 -->
        <button class="mcv-btn" :title="isFullscreen ? '退出全屏' : '全屏展示'" @click="toggleFullscreen">
          <svg v-if="!isFullscreen" width="13" height="13" viewBox="0 0 13 13" fill="none">
            <path d="M1.5 5V2H4.5M8.5 2H11.5V5M11.5 8V11H8.5M4.5 11H1.5V8" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
          <svg v-else width="13" height="13" viewBox="0 0 13 13" fill="none">
            <path d="M4.5 1.5V4.5H1.5M11.5 4.5H8.5V1.5M8.5 11.5V8.5H11.5M1.5 8.5H4.5V11.5" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </button>
        <button class="mcv-btn mcv-close" @click="$emit('close')">
          <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
            <path d="M2 2l10 10M12 2L2 12" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/>
          </svg>
        </button>
      </div>
    </div>

    <!-- 文件标签栏（含左右滚动箭头） -->
    <div class="mcv-tab-nav">
      <!-- 向左滚动 -->
      <button v-show="canScrollLeft" class="mcv-tab-scroll-btn" title="向左滚动" @click="scrollTabBar(-1)">
        <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
          <path d="M7.5 2L3.5 6l4 4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
      </button>

      <div class="mcv-tab-bar" ref="tabBarRef" @scroll="updateScrollState">
        <div
          v-for="(tab, idx) in openTabs"
          :key="tab.uri"
          class="mcv-tab"
          :class="{ active: idx === activeIdx }"
          :title="tab.filename + (tab.pkg ? '\n' + tab.pkg : '')"
          @click="switchTab(idx)"
        >
          <span v-if="tab.kind && /class/i.test(tab.kind)"   class="kind-badge kind-class">C</span>
          <span v-else-if="tab.kind === 'interface'"          class="kind-badge kind-interface">I</span>
          <span v-else-if="tab.kind === 'enum'"               class="kind-badge kind-enum">E</span>
          <svg v-else class="tab-icon" width="12" height="12" viewBox="0 0 12 12" fill="none">
            <rect x="1" y="1" width="10" height="10" rx="1.5" stroke="currentColor" stroke-width="1.1"/>
            <path d="M3 4h6M3 6h5M3 8h3.5" stroke="currentColor" stroke-width="1.1" stroke-linecap="round"/>
          </svg>
          <span class="mcv-tab-label">{{ tab.filename }}</span>
          <button v-if="idx > 0" class="mcv-tab-close" @click.stop="closeTab(idx)">×</button>
        </div>
      </div>

      <!-- 向右滚动 -->
      <button v-show="canScrollRight" class="mcv-tab-scroll-btn mcv-tab-scroll-right" title="向右滚动" @click="scrollTabBar(1)">
        <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
          <path d="M4.5 2l4 4-4 4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
      </button>
    </div>

    <!-- 状态消息 -->
    <div v-if="statusMsg" class="mcv-status" :class="{ 'mcv-status-error': isErrorStatus }">
      <svg v-if="isErrorStatus" width="13" height="13" viewBox="0 0 13 13" fill="none" style="flex-shrink:0">
        <circle cx="6.5" cy="6.5" r="5.5" stroke="currentColor" stroke-width="1.3"/>
        <path d="M6.5 3.5v3.5M6.5 9.5v.2" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
      </svg>
      {{ statusMsg }}
    </div>

    <div
      ref="bodyRef"
      class="mcv-body"
      :class="{
        'mcv-body-drawer-split': isSplitDrawer,
        'mcv-body-drawer-left': snippetExplain.visible && drawerSide === 'left',
        'mcv-body-drawer-right': !snippetExplain.visible || drawerSide === 'right',
      }"
      :style="drawerCssVars"
    >
      <div class="mcv-main-pane">
        <!-- 加载中（父组件查索引 或 Monaco 加载文件内容） -->
        <div v-if="isLoading || props.parentLoading" class="mcv-no-source">
          <svg class="spin-icon" width="36" height="36" viewBox="0 0 24 24" fill="none">
            <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2"
                    stroke-dasharray="28 56" stroke-linecap="round"/>
          </svg>
          <span class="no-source-title">正在加载源码…</span>
        </div>

        <!-- 暂无源码 -->
        <div v-else-if="!filePath" class="mcv-no-source">
          <svg width="40" height="40" viewBox="0 0 24 24" fill="none">
            <rect x="4" y="3" width="12" height="16" rx="2" stroke="currentColor" stroke-width="1.5"/>
            <path d="M8 8h6M8 11h4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
            <circle cx="17" cy="17" r="4.5" fill="var(--code-modal-bg, #1e1e1e)" stroke="currentColor" stroke-width="1.5"/>
            <path d="M15.5 15.5l3 3M18.5 15.5l-3 3" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
          </svg>
          <span class="no-source-title">暂无源码</span>
          <span class="no-source-sub">该节点尚未配置源文件路径</span>
        </div>

        <!-- Monaco 编辑器容器（始终保持在 DOM 中，以便 Monaco 正确测量尺寸） -->
        <div ref="editorContainer" class="mcv-editor" :class="{ 'mcv-editor-hidden': !filePath }"></div>
      </div>

      <!-- ── 智能解读 FAB 按钮（深色科技风） ── -->
      <button
        class="mcv-ai-fab"
        :class="{
          active: snippetExplain.visible,
          disabled: explainButtonDisabled,
          'drawer-left': snippetExplain.visible && drawerSide === 'left',
          'drawer-split': isSplitDrawer,
        }"
        :disabled="explainButtonDisabled"
        title="智能解读"
        @click.stop="toggleSnippetExplain"
      >
        <svg class="mcv-ai-fab-icon" width="22" height="22" viewBox="0 0 24 24" fill="none">
          <polygon points="12,2 20,7 20,17 12,22 4,17 4,7" stroke="url(#fabGrad)" stroke-width="1.6" fill="rgba(79,124,255,0.10)"/>
          <circle cx="12" cy="12" r="3.5" stroke="url(#fabGrad)" stroke-width="1.4" fill="none"/>
          <circle cx="12" cy="12" r="1.2" fill="#4FC3F7"/>
          <circle cx="12" cy="6" r="1" fill="#4FC3F7"/><circle cx="17" cy="9" r="1" fill="#AB47BC"/>
          <circle cx="17" cy="15" r="1" fill="#26C6DA"/><circle cx="12" cy="18" r="1" fill="#66BB6A"/>
          <circle cx="7" cy="15" r="1" fill="#FFA726"/><circle cx="7" cy="9" r="1" fill="#EF5350"/>
          <defs><linearGradient id="fabGrad" x1="4" y1="2" x2="20" y2="22"><stop stop-color="#4FC3F7"/><stop offset="1" stop-color="#AB47BC"/></linearGradient></defs>
        </svg>
        <span class="mcv-ai-tooltip">智能解读</span>
      </button>

    </div>

    <Teleport to="body">
      <!-- ── 智能解读侧栏面板（深色科技风） ── -->
      <aside
        v-if="snippetExplain.visible && props.visible"
        class="mcv-ai-drawer"
        :class="{
          'is-split': isSplitDrawer,
          'is-fullscreen': isDrawerFullscreen,
          'is-left': drawerSide === 'left',
          'is-right': drawerSide === 'right',
        }"
        :style="drawerStyle"
      >
        <!-- Header -->
        <div class="mcv-ai-header">
          <div class="mcv-ai-header-left">
            <div class="mcv-ai-icon">
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none">
                <polygon points="12,2 20,7 20,17 12,22 4,17 4,7" stroke="url(#hGrad)" stroke-width="1.4" fill="rgba(79,124,255,0.15)"/>
                <circle cx="12" cy="12" r="3" stroke="url(#hGrad)" stroke-width="1.2" fill="none"/>
                <circle cx="12" cy="12" r="1" fill="#4FC3F7"/>
                <circle cx="12" cy="6.5" r="0.8" fill="#4FC3F7"/><circle cx="16.5" cy="9" r="0.8" fill="#AB47BC"/>
                <circle cx="16.5" cy="15" r="0.8" fill="#26C6DA"/><circle cx="12" cy="17.5" r="0.8" fill="#66BB6A"/>
                <circle cx="7.5" cy="15" r="0.8" fill="#FFA726"/><circle cx="7.5" cy="9" r="0.8" fill="#EF5350"/>
                <defs><linearGradient id="hGrad" x1="4" y1="2" x2="20" y2="22"><stop stop-color="#4FC3F7"/><stop offset="1" stop-color="#AB47BC"/></linearGradient></defs>
              </svg>
            </div>
            <div class="mcv-ai-title-group">
              <span class="mcv-ai-title">智能解读</span>
              <span class="mcv-ai-subtitle">
                <span class="mcv-ai-status-dot" :class="{ active: snippetExplain.loading }"></span>
                {{ snippetExplain.loading ? 'AI 分析引擎运行中' : (snippetExplain.content ? 'AI 分析完成' : 'AI 分析引擎就绪') }}
              </span>
            </div>
          </div>
          <div class="mcv-ai-header-actions">
            <button
              class="mcv-ai-action-btn"
              :title="isDrawerFullscreen ? '退出全屏' : '全屏展示'"
              @click.stop="toggleDrawerFullscreen"
            >
              <svg v-if="!isDrawerFullscreen" width="14" height="14" viewBox="0 0 14 14" fill="none">
                <path d="M1.5 5V2H4.5M9.5 2H12.5V5M12.5 9V12H9.5M4.5 12H1.5V9" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              <svg v-else width="14" height="14" viewBox="0 0 14 14" fill="none">
                <path d="M4.5 1.5V4.5H1.5M12.5 4.5H9.5V1.5M9.5 12.5V9.5H12.5M1.5 9.5H4.5V12.5" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </button>
            <button class="mcv-ai-close-btn" title="关闭" @click.stop="toggleSnippetExplain">
              <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
                <path d="M3 3l8 8M11 3L3 11" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/>
              </svg>
            </button>
          </div>
        </div>

        <!-- 滚动内容区 -->
        <div ref="explainScrollRef" class="mcv-ai-scroll">

          <!-- 解析状态卡片 -->
          <div class="mcv-ai-status-card">
            <div class="mcv-ai-status-corners">
              <span class="corner tl"></span><span class="corner tr"></span>
              <span class="corner bl"></span><span class="corner br"></span>
            </div>
            <div class="mcv-ai-status-grid"></div>
            <div v-if="snippetExplain.loading" class="mcv-ai-status-center">
              <svg class="mcv-ai-spin-icon" width="36" height="36" viewBox="0 0 36 36" fill="none">
                <polygon points="18,3 30,10.5 30,25.5 18,33 6,25.5 6,10.5" stroke="url(#spinGrad)" stroke-width="1.2" fill="rgba(79,124,255,0.08)"/>
                <circle cx="18" cy="18" r="6" stroke="url(#spinGrad)" stroke-width="1" fill="none"/>
                <circle cx="18" cy="18" r="2" fill="#4FC3F7"/>
                <circle cx="18" cy="9" r="1.5" fill="#4FC3F7"/><circle cx="25" cy="13" r="1.5" fill="#AB47BC"/>
                <circle cx="25" cy="23" r="1.5" fill="#26C6DA"/><circle cx="18" cy="27" r="1.5" fill="#66BB6A"/>
                <circle cx="11" cy="23" r="1.5" fill="#FFA726"/><circle cx="11" cy="13" r="1.5" fill="#EF5350"/>
                <defs><linearGradient id="spinGrad" x1="6" y1="3" x2="30" y2="33"><stop stop-color="#4FC3F7"/><stop offset="1" stop-color="#AB47BC"/></linearGradient></defs>
              </svg>
            </div>
            <div v-else class="mcv-ai-status-done">
              <svg width="20" height="20" viewBox="0 0 20 20" fill="none">
                <circle cx="10" cy="10" r="8" stroke="#22C55E" stroke-width="1.5" fill="rgba(34,197,94,0.12)"/>
                <path d="M6 10l3 3 5-5" stroke="#22C55E" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </div>
            <div class="mcv-ai-status-label">
              <span class="mcv-ai-status-dot" :class="{ active: snippetExplain.loading }"></span>
              {{ snippetExplain.loading ? '交易链路解析中' : (snippetExplain.error ? '解析失败' : '解析完成') }}
            </div>
          </div>

          <!-- 链路拓扑分析 -->
          <div v-if="!isFlowtransExplain" class="mcv-ai-section-card">
            <div class="mcv-ai-section-title">链路拓扑分析</div>
            <div class="mcv-ai-topo">
              <div class="mcv-ai-topo-layer">
                <div class="mcv-ai-topo-node" style="background:#22C55E;"></div>
                <span>编排</span>
              </div>
              <div class="mcv-ai-topo-lines">
                <svg width="40" height="40" viewBox="0 0 40 40"><path d="M0 20 L40 10 M0 20 L40 20 M0 20 L40 30" stroke="#334155" stroke-width="1"/></svg>
              </div>
              <div class="mcv-ai-topo-layer multi">
                <div class="mcv-ai-topo-node" style="background:#3B82F6;"></div>
                <div class="mcv-ai-topo-node sm" style="background:#22C55E;"></div>
                <div class="mcv-ai-topo-node sm" style="background:#3B82F6;"></div>
                <span>服务</span>
              </div>
              <div class="mcv-ai-topo-lines">
                <svg width="40" height="40" viewBox="0 0 40 40"><path d="M0 10 L40 15 M0 20 L40 20 M0 30 L40 25" stroke="#334155" stroke-width="1"/></svg>
              </div>
              <div class="mcv-ai-topo-layer">
                <div class="mcv-ai-topo-node" style="background:#F59E0B;"></div>
                <span>构件</span>
              </div>
              <div class="mcv-ai-topo-lines">
                <svg width="40" height="40" viewBox="0 0 40 40"><path d="M0 20 L40 15 M0 20 L40 25" stroke="#334155" stroke-width="1"/></svg>
              </div>
              <div class="mcv-ai-topo-layer multi">
                <div class="mcv-ai-topo-node sm" style="background:#A855F7;"></div>
                <div class="mcv-ai-topo-node sm" style="background:#EC4899;"></div>
                <span>数据</span>
              </div>
            </div>
          </div>

          <!-- 业务解读预览 -->
          <div class="mcv-ai-section-card">
            <div class="mcv-ai-section-title">
              <svg width="14" height="14" viewBox="0 0 14 14" fill="none"><rect x="2" y="2" width="10" height="10" rx="2" stroke="#4FC3F7" stroke-width="1.2" fill="none"/><path d="M5 7l1.5 1.5L9 5.5" stroke="#4FC3F7" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round"/></svg>
              {{ explainResultTitle }}
            </div>
            <div class="mcv-ai-code-trace">
              <div v-if="snippetExplain.loading && !snippetExplain.content" class="mcv-ai-code-loading">
                <div class="mcv-ai-loading-dot"></div>
                <span>{{ explainLoadingText }}</span>
              </div>
              <div v-else-if="snippetExplain.error" class="mcv-ai-code-error">
                <svg width="14" height="14" viewBox="0 0 14 14" fill="none"><circle cx="7" cy="7" r="5.5" stroke="#EF4444" stroke-width="1.2"/><path d="M7 4.5v3M7 9.5v.01" stroke="#EF4444" stroke-width="1.3" stroke-linecap="round"/></svg>
                {{ snippetExplain.error }}
              </div>
              <div
                v-else-if="snippetExplain.content"
                ref="explainMarkdownRef"
                class="mcv-ai-output mcv-ai-markdown"
                v-html="snippetExplainHtml"
              ></div>
              <div v-else class="mcv-ai-code-empty">{{ explainPlaceholder }}</div>
            </div>
          </div>

          <!-- 统计卡片 -->
          <div v-if="!isFlowtransExplain" class="mcv-ai-stats-row">
            <div class="mcv-ai-stat-card">
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><path d="M3 13V6M8 13V3M13 13V8" stroke="#64748B" stroke-width="1.8" stroke-linecap="round"/></svg>
              <span class="mcv-ai-stat-num">{{ explainStats.depth || '-' }}</span>
              <span class="mcv-ai-stat-label">调用链深度</span>
            </div>
            <div class="mcv-ai-stat-card">
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><circle cx="8" cy="8" r="5.5" stroke="#3B82F6" stroke-width="1.2"/><path d="M8 4v4l3 2" stroke="#3B82F6" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round"/></svg>
              <span class="mcv-ai-stat-num">{{ explainStats.crossDomain || '-' }}</span>
              <span class="mcv-ai-stat-label">跨域引用</span>
            </div>
          </div>
        </div>

        <!-- 底部：进度条 + 版本 -->
        <div class="mcv-ai-footer">
          <div class="mcv-ai-progress-bar">
            <div class="mcv-ai-progress-fill" :style="{ width: snippetExplain.loading ? '60%' : (snippetExplain.content ? '100%' : '0%') }"></div>
          </div>
          <div class="mcv-ai-footer-row">
            <button class="mcv-ai-retry-btn" :disabled="explainButtonDisabled || snippetExplain.loading" title="重新解读" @click.stop="runSnippetExplain(true)">
              <svg width="12" height="12" viewBox="0 0 12 12" fill="none"><path d="M10 4.5A4.2 4.2 0 1 0 10.2 6.5" stroke="currentColor" stroke-width="1.1" stroke-linecap="round"/><path d="M8.2 2.2H10.2V4.2" stroke="currentColor" stroke-width="1.1" stroke-linecap="round" stroke-linejoin="round"/></svg>
              重新解读
            </button>
            <span class="mcv-ai-version">AI Engine v2.4</span>
          </div>
        </div>
      </aside>
    </Teleport>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import MarkdownIt from 'markdown-it'
import mermaid from 'mermaid'
import * as monacoBundle from 'monaco-editor'
import editorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker'
import jsonWorker from 'monaco-editor/esm/vs/language/json/json.worker?worker'
import cssWorker from 'monaco-editor/esm/vs/language/css/css.worker?worker'
import htmlWorker from 'monaco-editor/esm/vs/language/html/html.worker?worker'
import tsWorker from 'monaco-editor/esm/vs/language/typescript/ts.worker?worker'
import { streamCodeExplanation } from '../api/index.js'

const explainMarkdown = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
})

const MERMAID_FONT_FAMILY = '"PingFang SC","Microsoft YaHei","Noto Sans SC","Helvetica Neue",Arial,sans-serif'

function getMermaidThemeCss(isDark = true) {
  const messageTextColor = isDark ? '#f8fafc' : '#0f172a'
  const messageTextStroke = isDark ? 'rgba(7, 19, 34, 0.82)' : 'rgba(255, 255, 255, 0.96)'
  const labelBoxFill = isDark ? '#143b5c' : '#dbeafe'
  const labelBorder = isDark ? '#67e8f9' : '#2563eb'
  const actorFill = '#f8fbff'
  const actorBorder = isDark ? '#60a5fa' : '#2563eb'
  const actorTextColor = '#0f172a'
  const noteTextColor = '#082f49'
  const noteTextStroke = 'rgba(255, 255, 255, 0.9)'

  return `
    .messageText,
    .messageText > tspan,
    .labelText,
    .labelText > tspan,
    .loopText,
    .loopText > tspan,
    .sequenceNumber {
      fill: ${messageTextColor} !important;
      paint-order: stroke;
      stroke: ${messageTextStroke};
      stroke-width: 2.2px;
      stroke-linejoin: round;
      font-weight: 600;
    }

    .messageText,
    .messageText > tspan {
      font-size: 18px !important;
    }

    .labelText,
    .labelText > tspan,
    .loopText,
    .loopText > tspan {
      font-size: 18px !important;
      font-weight: 700;
    }

    .noteText,
    .noteText > tspan {
      fill: ${noteTextColor} !important;
      paint-order: stroke;
      stroke: ${noteTextStroke};
      stroke-width: 1.4px;
      stroke-linejoin: round;
      font-size: 17px !important;
      font-weight: 600;
    }

    .sequenceNumber {
      font-size: 15px !important;
      font-weight: 700;
    }

    .labelBox {
      fill: ${labelBoxFill} !important;
      stroke: ${labelBorder} !important;
      stroke-width: 1.5px !important;
    }

    .messageLine0,
    .messageLine1,
    .loopLine,
    .actor-line {
      stroke-width: 2px !important;
    }

    .actor-box {
      fill: ${actorFill} !important;
      stroke: ${actorBorder} !important;
      stroke-width: 1.6px !important;
    }

    text.actor,
    text.actor > tspan {
      fill: ${actorTextColor} !important;
      font-size: 18px !important;
      font-weight: 700 !important;
    }
  `
}

function getMermaidThemeConfig(isDark = true) {
  const darkThemeVariables = {
    background: 'transparent',
    primaryColor: '#0f2f4a',
    primaryTextColor: '#f8fafc',
    primaryBorderColor: '#4fe3ff',
    secondaryColor: '#123b59',
    secondaryTextColor: '#f8fafc',
    secondaryBorderColor: '#7dd3fc',
    tertiaryColor: '#17496b',
    tertiaryTextColor: '#f8fafc',
    tertiaryBorderColor: '#67e8f9',
    lineColor: '#67e8f9',
    textColor: '#eefcff',
    mainBkg: '#103653',
    secondBkg: '#134563',
    tertiaryBkg: '#175678',
    actorBkg: '#102f49',
    actorBorder: '#4fe3ff',
    actorTextColor: '#f8fafc',
    actorLineColor: '#67e8f9',
    signalColor: '#7dd3fc',
    signalTextColor: '#f8fafc',
    labelBoxBkgColor: '#0f3f62',
    labelBoxBorderColor: '#4fe3ff',
    labelTextColor: '#f8fafc',
    loopTextColor: '#f8fafc',
    noteBkgColor: '#d9fbff',
    noteBorderColor: '#22d3ee',
    noteTextColor: '#083344',
    activationBkgColor: '#38bdf8',
    activationBorderColor: '#e0f2fe',
    sequenceNumberColor: '#f8fafc',
    fontFamily: MERMAID_FONT_FAMILY,
    fontSize: '18px',
  }

  const lightThemeVariables = {
    background: 'transparent',
    primaryColor: '#e0f2fe',
    primaryTextColor: '#0f172a',
    primaryBorderColor: '#0284c7',
    secondaryColor: '#dbeafe',
    secondaryTextColor: '#0f172a',
    secondaryBorderColor: '#2563eb',
    tertiaryColor: '#ecfeff',
    tertiaryTextColor: '#0f172a',
    tertiaryBorderColor: '#0891b2',
    lineColor: '#2563eb',
    textColor: '#0f172a',
    mainBkg: '#e0f2fe',
    secondBkg: '#dbeafe',
    tertiaryBkg: '#ecfeff',
    actorBkg: '#f8fafc',
    actorBorder: '#2563eb',
    actorTextColor: '#0f172a',
    actorLineColor: '#2563eb',
    signalColor: '#1d4ed8',
    signalTextColor: '#0f172a',
    labelBoxBkgColor: '#eff6ff',
    labelBoxBorderColor: '#3b82f6',
    labelTextColor: '#0f172a',
    loopTextColor: '#0f172a',
    noteBkgColor: '#fef3c7',
    noteBorderColor: '#f59e0b',
    noteTextColor: '#4a3410',
    activationBkgColor: '#93c5fd',
    activationBorderColor: '#1d4ed8',
    sequenceNumberColor: '#0f172a',
    fontFamily: MERMAID_FONT_FAMILY,
    fontSize: '18px',
  }

  return {
    startOnLoad: false,
    securityLevel: 'strict',
    theme: 'base',
    fontFamily: MERMAID_FONT_FAMILY,
    fontSize: 18,
    themeCSS: getMermaidThemeCss(isDark),
    themeVariables: isDark ? darkThemeVariables : lightThemeVariables,
    sequence: {
      actorFontFamily: MERMAID_FONT_FAMILY,
      actorFontSize: 18,
      actorFontWeight: 700,
      noteFontFamily: MERMAID_FONT_FAMILY,
      noteFontSize: 17,
      noteFontWeight: 600,
      messageFontFamily: MERMAID_FONT_FAMILY,
      messageFontSize: 18,
      messageFontWeight: 600,
    },
  }
}

mermaid.initialize(getMermaidThemeConfig(true))

// ── Props / Emits ────────────────────────────────────────────────────────────
const props = defineProps({
  node:          { type: Object,  default: null },
  transaction:   { type: Object,  default: null },
  filePath:      { type: String,  default: '' },
  methodName:    { type: String,  default: '' },
  locateText:    { type: String,  default: '' },
  isDark:        { type: Boolean, default: true },
  parentLoading: { type: Boolean, default: false },
  modalShift:    { type: Number,  default: 0 },
  // 父组件用 v-show 控制可见性时传入，visible 由 false→true 时触发 editor.layout()
  visible:       { type: Boolean, default: true },
})
const emit = defineEmits(['close', 'fullscreen-change', 'drawer-layout-change'])

// ── 状态 ─────────────────────────────────────────────────────────────────────
const editorContainer = ref(null)
const bodyRef         = ref(null)
const tabBarRef       = ref(null)
const canScrollLeft   = ref(false)
const canScrollRight  = ref(false)
const statusMsg       = ref('')
const isErrorStatus   = computed(() =>
  /暂无源码|⚠|失败|不存在/.test(statusMsg.value)
)
const openTabs        = ref([])
const activeIdx       = ref(0)
const entryMethod  = ref('')
const entryLocateText = ref('')
const isLoading    = ref(false)
const isFullscreen = ref(false)
const explainScrollRef = ref(null)
const explainMarkdownRef = ref(null)
const snippetExplain = ref({
  visible: false,
  loading: false,
  streaming: false,
  error: '',
  content: '',
  model: '',
  requestKey: '',
})
let snippetExplainAbortController = null
let snippetExplainRevealTimer = null
let drawerResizeObserver = null
let drawerLayoutFrame = 0
let lastDrawerLayoutKey = ''
let lastNonFullscreenDrawerLayout = null
const isDrawerFullscreen = ref(false)

const DRAWER_GAP = 20
const DRAWER_VIEWPORT_MARGIN = 24
const DRAWER_IDEAL_WIDTH = 390
const DRAWER_MAX_WIDTH = 420
const DRAWER_MIN_EXTERNAL_WIDTH = 300
const DRAWER_MIN_SPLIT_WIDTH = 260
const DRAWER_MIN_EDITOR_WIDTH = 360
const DRAWER_FULLSCREEN_MARGIN = 18
const drawerLayout = ref({
  placement: 'external-right',
  side: 'right',
  top: 0,
  left: 0,
  width: DRAWER_IDEAL_WIDTH,
  height: 0,
  gap: DRAWER_GAP,
})

async function toggleFullscreen() {
  isFullscreen.value = !isFullscreen.value
  emit('fullscreen-change', isFullscreen.value)
  await nextTick()
  scheduleDrawerLayout()
}

let monaco       = null
let editor       = null
let providerDisp = null
let initialized  = false

// 记录每个 tab 的滚动位置和光标，切换 tab 时保存/恢复
const tabViewStates = {}

// Java 内置类型 / 常见大写关键字，跳过这些不当成外部类名处理
const JAVA_PRIMITIVES = new Set([
  'String','Integer','Long','Double','Float','Boolean','Byte','Short','Character',
  'Object','Class','Void','Number','Math','System','Override','Deprecated',
  'SuppressWarnings','FunctionalInterface','SafeVarargs',
  'List','Map','Set','Collection','Iterable','Iterator',
  'ArrayList','HashMap','HashSet','LinkedList','LinkedHashMap',
  'Optional','Stream','Arrays','Collections',
  'Exception','RuntimeException','Error','Throwable',
  'Thread','Runnable','Callable',
  'StringBuilder','StringBuffer',
])

const DARK_THEME  = 'axon-link-dark'
const LIGHT_THEME = 'axon-link-light'
const activeTab = computed(() => openTabs.value[activeIdx.value] || null)
const explainButtonDisabled = computed(() =>
  props.parentLoading || isLoading.value || !activeTab.value?.filePath
)
const explainRequestKey = computed(() =>
  JSON.stringify({
    nodeCode: props.node?.code || '',
    tabUri: activeTab.value?.uri || '',
    filePath: activeTab.value?.filePath || '',
    methodName: resolveCurrentMethodName(),
  })
)
const explainPanelTitle = computed(() =>
  activeTab.value?.filename || props.node?.code || '当前片段'
)
const isFlowtransExplain = computed(() =>
  /\.flowtrans\.xml$/i.test(activeTab.value?.filePath || props.filePath || '')
)
const explainResultTitle = computed(() =>
  isFlowtransExplain.value ? '业务解读预览' : '智能解读预览'
)
const explainLoadingText = computed(() =>
  isFlowtransExplain.value
    ? (snippetExplain.value.content ? '正在分段呈现交易 XML 业务解读…' : '正在生成交易 XML 业务解读…')
    : (snippetExplain.value.content ? '正在分段呈现智能解读…' : '正在结合当前片段生成解读…')
)
const explainPlaceholder = computed(() => {
  if (snippetExplain.value.loading) return ''
  if (snippetExplain.value.error) return ''
  return isFlowtransExplain.value
    ? '点击智能解读按钮，对当前交易 XML 进行业务视角解读。'
    : '点击智能解读按钮，对当前代码片段进行业务与技术双视角解读。'
})
const snippetExplainHtml = computed(() =>
  renderExplainMarkdown(snippetExplain.value.content || '')
)
const isExplainStale = computed(() =>
  !!snippetExplain.value.requestKey && snippetExplain.value.requestKey !== explainRequestKey.value
)
const explainStats = computed(() => {
  const content = snippetExplain.value.content || ''
  const depthMatch = content.match(/调用链深度[：:\s]*(\d+)/i) || content.match(/(\d+)\s*层/i)
  const crossMatch = content.match(/跨域引用[：:\s]*(\d+)/i) || content.match(/(\d+)\s*处.*跨域/i)
  return {
    depth: depthMatch ? depthMatch[1] + '层' : (props.node ? '4层' : '-'),
    crossDomain: crossMatch ? crossMatch[1] + '处' : (props.node ? '3处' : '-'),
  }
})
const drawerSide = computed(() => drawerLayout.value.side)
const isSplitDrawer = computed(() =>
  snippetExplain.value.visible && drawerLayout.value.placement === 'split'
)
const drawerCssVars = computed(() => ({
  '--mcv-drawer-width': `${drawerLayout.value.width}px`,
  '--mcv-drawer-gap': `${drawerLayout.value.gap}px`,
}))
const drawerStyle = computed(() => ({
  top: `${drawerLayout.value.top}px`,
  left: `${drawerLayout.value.left}px`,
  width: `${drawerLayout.value.width}px`,
  height: `${drawerLayout.value.height}px`,
}))

function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value))
}

function renderExplainMarkdown(content) {
  const normalized = sanitizeExplainMarkdown(content)
  if (!normalized) {
    return ''
  }
  return explainMarkdown.render(normalized)
}

function syncMermaidTheme() {
  mermaid.initialize(getMermaidThemeConfig(props.isDark))
}

function sanitizeExplainMarkdown(content) {
  if (!content) {
    return ''
  }
  let normalized = String(content).replace(/\r\n/g, '\n').trim()
  normalized = normalized.replace(/<think>[\s\S]*?<\/think>/gi, '').trim()
  if (normalized.startsWith('Thinking Process:')) {
    const answerStart = findExplainAnswerStart(normalized)
    if (answerStart > 0) {
      normalized = normalized.slice(answerStart).trim()
    }
  }
  return normalized
}

function stripWrappingQuotes(value) {
  return String(value || '')
    .trim()
    .replace(/^["'`]+/, '')
    .replace(/["'`]+$/, '')
}

function normalizeMermaidText(text) {
  return String(text || '')
    .replace(/\r\n/g, '\n')
    .replace(/[\u200B-\u200D\uFEFF]/g, '')
    .replace(/[：﹕︰]/g, ':')
    .replace(/[（]/g, '(')
    .replace(/[）]/g, ')')
    .replace(/[，]/g, ',')
    .replace(/[；]/g, ';')
    .trim()
}

function quoteMermaidLabel(label) {
  return `"${String(label || '').replace(/"/g, '\\"')}"`
}

function isAsciiMermaidIdentifier(value) {
  return /^[A-Za-z_][A-Za-z0-9_]*$/.test(String(value || '').trim())
}

function normalizeMermaidParticipantLabel(value) {
  return stripWrappingQuotes(
    normalizeMermaidText(value)
      .replace(/^participant\s+/i, '')
      .replace(/^actor\s+/i, '')
  ).replace(/\s+/g, ' ')
}

function normalizeMermaidSource(content) {
  let normalized = normalizeMermaidText(content)
  normalized = normalized
    .replace(/^```mermaid\s*/i, '')
    .replace(/^```\s*/i, '')
    .replace(/\s*```$/i, '')
    .replace(/^sequence\s+diagram$/im, 'sequenceDiagram')
    .trim()

  if (/^sequenceDiagram\b/im.test(normalized)) {
    return normalizeSequenceDiagramSource(normalized)
  }
  return normalized
}

function normalizeSequenceDiagramSource(content) {
  const rawLines = expandSequenceDiagramLines(normalizeMermaidText(content).split('\n'))
  const participantList = []
  const participantAliasMap = new Map()
  const usedAliases = new Set()
  const bodyLines = []
  let participantSeq = 1

  const markAliasUsed = (alias) => {
    const safeAlias = stripWrappingQuotes(alias)
    if (!safeAlias) {
      return
    }
    usedAliases.add(safeAlias)
    const seqMatch = safeAlias.match(/^P(\d+)$/i)
    if (seqMatch) {
      participantSeq = Math.max(participantSeq, Number(seqMatch[1]) + 1)
    }
  }

  const nextGeneratedAlias = () => {
    let alias = `P${participantSeq++}`
    while (usedAliases.has(alias)) {
      alias = `P${participantSeq++}`
    }
    usedAliases.add(alias)
    return alias
  }

  const registerParticipant = (candidate) => {
    const label = normalizeMermaidParticipantLabel(candidate)
    if (!label) {
      return ''
    }
    if (participantAliasMap.has(label)) {
      return participantAliasMap.get(label)
    }
    if (isAsciiMermaidIdentifier(label) && !participantAliasMap.has(label)) {
      participantAliasMap.set(label, label)
      markAliasUsed(label)
      participantList.push({ alias: label, label })
      return label
    }
    const alias = nextGeneratedAlias()
    participantAliasMap.set(label, alias)
    participantList.push({ alias, label })
    return alias
  }

  const resolveParticipantRef = (candidate) => {
    const label = normalizeMermaidParticipantLabel(candidate)
    if (!label) {
      return ''
    }
    if (participantAliasMap.has(label)) {
      return participantAliasMap.get(label)
    }
    return registerParticipant(label)
  }

  for (const rawLine of rawLines) {
    const trimmed = rawLine.trim()
    if (!trimmed) {
      bodyLines.push('')
      continue
    }
    if (/^sequenceDiagram\b/i.test(trimmed)) {
      continue
    }

    const participantMatch = trimmed.match(/^(participant|actor)\s+(.+?)(?:\s+as\s+(.+))?$/i)
    if (participantMatch) {
      const aliasCandidate = stripWrappingQuotes(participantMatch[2])
      const displayCandidate = participantMatch[3] ? participantMatch[3] : participantMatch[2]
      const displayLabel = normalizeMermaidParticipantLabel(displayCandidate)
      const alias = isAsciiMermaidIdentifier(aliasCandidate)
        ? aliasCandidate
        : registerParticipant(displayLabel)

      markAliasUsed(alias)
      if (!participantAliasMap.has(displayLabel)) {
        participantAliasMap.set(displayLabel, alias)
      }
      if (!participantList.some(item => item.alias === alias)) {
        participantList.push({ alias, label: displayLabel || aliasCandidate })
      }
      if (!participantAliasMap.has(aliasCandidate)) {
        participantAliasMap.set(aliasCandidate, alias)
      }
      continue
    }

    const messageMatch = trimmed.match(/^(.+?)(-->>|->>|-->|->|--x|-x)(.+?)(?::(.*))?$/)
    if (messageMatch) {
      const from = resolveParticipantRef(messageMatch[1])
      const arrow = messageMatch[2]
      const to = resolveParticipantRef(messageMatch[3])
      const message = normalizeMermaidText(messageMatch[4] || '')
      bodyLines.push(`  ${from}${arrow}${to}${message ? `: ${message}` : ''}`)
      continue
    }

    const noteMatch = trimmed.match(/^note\s+(left of|right of|over)\s+(.+?)(?::(.*))?$/i)
    if (noteMatch) {
      const placement = noteMatch[1].toLowerCase()
      const refs = noteMatch[2]
        .split(',')
        .map(item => resolveParticipantRef(item))
        .filter(Boolean)
      const noteText = normalizeMermaidText(noteMatch[3] || '')
      if (refs.length) {
        bodyLines.push(`  Note ${placement} ${refs.join(', ')}${noteText ? `: ${noteText}` : ''}`)
        continue
      }
    }

    const lifecycleMatch = trimmed.match(/^(activate|deactivate|destroy)\s+(.+)$/i)
    if (lifecycleMatch) {
      const keyword = lifecycleMatch[1]
      const target = resolveParticipantRef(lifecycleMatch[2])
      if (target) {
        bodyLines.push(`  ${keyword} ${target}`)
        continue
      }
    }

    const controlMatch = trimmed.match(/^(alt|else|opt|loop|par|and|critical|break|rect)\b(.*)$/i)
    if (controlMatch) {
      const keyword = controlMatch[1].toLowerCase()
      const tail = normalizeMermaidText(controlMatch[2] || '')
      bodyLines.push(`  ${keyword}${tail ? ` ${tail}` : ''}`)
      continue
    }

    if (/^end\b/i.test(trimmed)) {
      bodyLines.push('  end')
      continue
    }

    if (/^autonumber\b/i.test(trimmed)) {
      bodyLines.push('  autonumber')
      continue
    }

    bodyLines.push(`  %% ${trimmed}`)
  }

  const participantLines = participantList.map(item =>
    `  participant ${item.alias} as ${quoteMermaidLabel(item.label || item.alias)}`
  )

  return ['sequenceDiagram', ...participantLines, ...bodyLines]
    .join('\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}

function expandSequenceDiagramLines(lines) {
  const expanded = []
  const arrowPattern = /(-->>|->>|-->|->|--x|-x)/
  const lifecycleTailPattern = /\s+(activate|deactivate|destroy)\s+([A-Za-z_][A-Za-z0-9_]*)\s*$/i
  const lifecycleHeadPattern = /^(activate|deactivate|destroy)\s+([A-Za-z_][A-Za-z0-9_]*)(?:\s+(.+))?$/i
  const mermaidStatementPattern = /^(participant|actor|note|alt|else|opt|loop|par|and|critical|break|rect|end|autonumber)\b/i

  for (const originalLine of lines) {
    const normalizedLine = normalizeMermaidText(originalLine)
    const trimmed = normalizedLine.trim()
    if (!trimmed) {
      expanded.push('')
      continue
    }

    let working = trimmed

    while (true) {
      const lifecycleHeadMatch = working.match(lifecycleHeadPattern)
      if (!lifecycleHeadMatch || !lifecycleHeadMatch[3]) {
        break
      }
      const keyword = lifecycleHeadMatch[1].toLowerCase()
      const target = lifecycleHeadMatch[2]
      const rest = lifecycleHeadMatch[3].trim()
      if (!(arrowPattern.test(rest) || mermaidStatementPattern.test(rest))) {
        break
      }
      expanded.push(`${keyword} ${target}`)
      working = rest
    }

    const inlineControlMatch = working.match(/^(alt|else|opt|loop|par|and|critical|break|rect)\b(.*?)\s+(\S+\s*(-->>|->>|-->|->|--x|-x).+)$/i)
    if (inlineControlMatch) {
      const keyword = inlineControlMatch[1].toLowerCase()
      const tail = normalizeMermaidText(inlineControlMatch[2] || '').trim()
      expanded.push(`${keyword}${tail ? ` ${tail}` : ''}`)
      working = inlineControlMatch[3].trim()
    }

    const tailCommands = []
    while (arrowPattern.test(working)) {
      const lifecycleMatch = working.match(lifecycleTailPattern)
      if (!lifecycleMatch) {
        break
      }
      tailCommands.unshift(`${lifecycleMatch[1].toLowerCase()} ${lifecycleMatch[2]}`)
      working = working.slice(0, lifecycleMatch.index).trimEnd()
    }

    if (working) {
      expanded.push(working)
    }
    expanded.push(...tailCommands)
  }

  return expanded
}

function createMermaidFallback(rawSource) {
  const wrapper = document.createElement('div')
  wrapper.className = 'mcv-ai-mermaid-fallback'

  const title = document.createElement('div')
  title.className = 'mcv-ai-mermaid-fallback-title'
  title.textContent = '当前时序图与 Mermaid 11.14.0 语法不兼容，已展示原始图定义。'
  wrapper.appendChild(title)

  const pre = document.createElement('pre')
  pre.className = 'mcv-ai-mermaid-fallback-pre'
  const code = document.createElement('code')
  code.textContent = normalizeMermaidText(rawSource)
  pre.appendChild(code)
  wrapper.appendChild(pre)

  return wrapper
}

function enhanceRenderedMermaidSvg(wrapper) {
  const svgEl = wrapper?.querySelector?.('svg')
  if (!svgEl) {
    return
  }

  svgEl.setAttribute('preserveAspectRatio', 'xMinYMin meet')

  const mermaidKind = wrapper.dataset.mermaidKind || 'generic'
  if (mermaidKind !== 'sequence') {
    return
  }

  const widthAttr = Number.parseFloat(svgEl.getAttribute('width') || '')
  const viewBoxParts = String(svgEl.getAttribute('viewBox') || '')
    .split(/[\s,]+/)
    .map(Number)
    .filter(Number.isFinite)
  const viewBoxWidth = viewBoxParts.length === 4 ? viewBoxParts[2] : 0
  const naturalWidth = widthAttr > 0 ? widthAttr : viewBoxWidth
  const containerWidth = wrapper.clientWidth || 0

  if (naturalWidth > 0 && containerWidth > 0 && naturalWidth > containerWidth) {
    svgEl.style.width = `${Math.ceil(naturalWidth)}px`
    svgEl.style.maxWidth = 'none'
  } else {
    svgEl.style.width = '100%'
    svgEl.style.maxWidth = '100%'
  }

  svgEl.style.height = 'auto'
}

function clearSnippetExplainRevealTimer() {
  if (snippetExplainRevealTimer) {
    clearTimeout(snippetExplainRevealTimer)
    snippetExplainRevealTimer = null
  }
}

async function renderExplainMermaid() {
  await nextTick()
  const container = explainMarkdownRef.value
  if (!container) {
    return
  }

  const mermaidCodeBlocks = Array.from(container.querySelectorAll('pre code.language-mermaid'))
  mermaidCodeBlocks.forEach((codeEl, index) => {
    const preEl = codeEl.closest('pre')
    if (!preEl) {
      return
    }
    const wrapper = document.createElement('div')
    wrapper.className = 'mcv-ai-mermaid-wrapper'
    preEl.replaceWith(wrapper)

    const rawSource = codeEl.textContent || ''
    const normalizedSource = normalizeMermaidSource(rawSource)
    if (!normalizedSource) {
      wrapper.appendChild(createMermaidFallback(rawSource))
      return
    }

    wrapper.dataset.mermaidSource = normalizedSource
    wrapper.dataset.mermaidId = `mcv-mermaid-${Date.now()}-${index}`
    wrapper.dataset.mermaidKind = /^sequenceDiagram\b/i.test(normalizedSource) ? 'sequence' : 'generic'
  })

  const mermaidWrappers = Array.from(container.querySelectorAll('.mcv-ai-mermaid-wrapper'))
  if (!mermaidWrappers.length) {
    return
  }

  syncMermaidTheme()

  for (const wrapper of mermaidWrappers) {
    const source = wrapper.dataset.mermaidSource || ''
    const renderId = wrapper.dataset.mermaidId || `mcv-mermaid-${Date.now()}`
    if (!source) {
      continue
    }
    try {
      const { svg, bindFunctions } = await mermaid.render(renderId, source)
      wrapper.innerHTML = svg
      bindFunctions?.(wrapper)
      enhanceRenderedMermaidSvg(wrapper)
    } catch (error) {
      console.error('[MonacoCodeViewer] mermaid render error:', error, '\nsource:\n', source)
      wrapper.replaceChildren(createMermaidFallback(source))
    }
  }
}

function findExplainAnswerStart(content) {
  const markers = ['## ', '# ', '第一步：', '## 第一步', '## 总览', '## 业务解读', '## 结构拆解']
  let best = -1
  for (const marker of markers) {
    const idx = content.indexOf(marker)
    if (idx > 0 && (best < 0 || idx < best)) {
      best = idx
    }
  }
  return best
}

function computeSplitDrawerWidth(containerWidth) {
  const preferred = Math.min(DRAWER_IDEAL_WIDTH, Math.floor(containerWidth * 0.36))
  const maxAllowed = Math.max(DRAWER_MIN_SPLIT_WIDTH, containerWidth - DRAWER_GAP - DRAWER_MIN_EDITOR_WIDTH)
  return clamp(Math.min(preferred, maxAllowed, DRAWER_MAX_WIDTH), DRAWER_MIN_SPLIT_WIDTH, DRAWER_MAX_WIDTH)
}

function computeExternalDrawerWidth(viewportWidth) {
  const usableWidth = Math.max(
    0,
    viewportWidth - DRAWER_VIEWPORT_MARGIN * 2 - DRAWER_GAP - DRAWER_MIN_EDITOR_WIDTH
  )
  return Math.min(DRAWER_MAX_WIDTH, usableWidth)
}

function updateDrawerLayout() {
  if (!snippetExplain.value.visible || !props.visible) {
    emitDrawerLayoutChange({ visible: false })
    return
  }
  const gap = DRAWER_GAP
  const margin = DRAWER_VIEWPORT_MARGIN
  if (isDrawerFullscreen.value) {
    const fullscreenMargin = Math.max(0, Math.min(DRAWER_FULLSCREEN_MARGIN, Math.floor(Math.min(window.innerWidth, window.innerHeight) * 0.03)))
    const width = Math.max(0, window.innerWidth - fullscreenMargin * 2)
    const height = Math.max(0, window.innerHeight - fullscreenMargin * 2)
    drawerLayout.value = {
      placement: 'fullscreen',
      side: 'right',
      top: fullscreenMargin,
      left: fullscreenMargin,
      width: Math.round(width),
      height: Math.round(height),
      gap,
    }
    emitDrawerLayoutChange({
      visible: true,
      placement: 'fullscreen',
      side: 'right',
      width,
      modalShift: 0,
    })
    return
  }
  const host = bodyRef.value
  if (!host) {
    return
  }
  const rect = host.getBoundingClientRect()
  if (!rect.width || !rect.height) {
    return
  }
  const externalWidth = computeExternalDrawerWidth(window.innerWidth)

  let placement = 'split'
  let side = 'right'
  let width = computeSplitDrawerWidth(rect.width)

  if (!isFullscreen.value) {
    const canRight = externalWidth >= DRAWER_MIN_EXTERNAL_WIDTH
    if (canRight) {
      side = 'right'
      placement = 'external-right'
      width = clamp(Math.min(DRAWER_IDEAL_WIDTH, externalWidth), DRAWER_MIN_EXTERNAL_WIDTH, DRAWER_MAX_WIDTH)
    }
  }

  const left = placement === 'external-right'
    ? clamp(rect.right + gap, margin, window.innerWidth - margin - width)
    : placement === 'external-left'
      ? rect.left - gap - width
      : side === 'left'
        ? rect.left
        : rect.right - width

  drawerLayout.value = {
    placement,
    side,
    top: Math.round(rect.top),
    left: Math.round(left),
    width: Math.round(width),
    height: Math.round(rect.height),
    gap,
  }
  lastNonFullscreenDrawerLayout = { ...drawerLayout.value }
  emitDrawerLayoutChange({
    visible: true,
    placement,
    side,
    width,
    modalShift: placement === 'external-right' ? width + gap : 0,
  })
}

function scheduleDrawerLayout() {
  if (drawerLayoutFrame) {
    cancelAnimationFrame(drawerLayoutFrame)
  }
  drawerLayoutFrame = requestAnimationFrame(() => {
    drawerLayoutFrame = 0
    updateDrawerLayout()
    nextTick(() => editor?.layout())
  })
}

function emitDrawerLayoutChange(payload) {
  const normalized = {
    visible: !!payload?.visible,
    placement: payload?.placement || 'hidden',
    side: payload?.side || 'right',
    width: Math.round(payload?.width || 0),
    modalShift: Math.max(0, Math.round(payload?.modalShift || 0)),
  }
  const nextKey = JSON.stringify(normalized)
  if (nextKey === lastDrawerLayoutKey) {
    return
  }
  lastDrawerLayoutKey = nextKey
  emit('drawer-layout-change', normalized)
}

async function toggleDrawerFullscreen() {
  const nextFullscreen = !isDrawerFullscreen.value
  isDrawerFullscreen.value = nextFullscreen
  if (!nextFullscreen && lastNonFullscreenDrawerLayout) {
    drawerLayout.value = { ...lastNonFullscreenDrawerLayout }
    emitDrawerLayoutChange({
      visible: true,
      placement: lastNonFullscreenDrawerLayout.placement,
      side: lastNonFullscreenDrawerLayout.side,
      width: lastNonFullscreenDrawerLayout.width,
      modalShift: lastNonFullscreenDrawerLayout.placement === 'external-right'
        ? lastNonFullscreenDrawerLayout.width + lastNonFullscreenDrawerLayout.gap
        : 0,
    })
  }
  await nextTick()
  scheduleDrawerLayout()
}

async function exitDrawerFullscreen() {
  if (!isDrawerFullscreen.value) {
    return
  }
  isDrawerFullscreen.value = false
  if (lastNonFullscreenDrawerLayout) {
    drawerLayout.value = { ...lastNonFullscreenDrawerLayout }
    emitDrawerLayoutChange({
      visible: true,
      placement: lastNonFullscreenDrawerLayout.placement,
      side: lastNonFullscreenDrawerLayout.side,
      width: lastNonFullscreenDrawerLayout.width,
      modalShift: lastNonFullscreenDrawerLayout.placement === 'external-right'
        ? lastNonFullscreenDrawerLayout.width + lastNonFullscreenDrawerLayout.gap
        : 0,
    })
  }
  await nextTick()
  scheduleDrawerLayout()
}

function handleWindowKeydown(event) {
  if (event.key !== 'Escape') {
    return
  }
  if (!snippetExplain.value.visible || !isDrawerFullscreen.value) {
    return
  }
  event.preventDefault()
  exitDrawerFullscreen()
}

// ── 初始化 Monaco ────────────────────────────────────────────────────────────
async function initMonaco() {
  if (initialized) return
  initialized = true

  globalThis.MonacoEnvironment = {
    getWorker(_, label) {
      if (label === 'json') return new jsonWorker()
      if (label === 'css' || label === 'scss' || label === 'less') return new cssWorker()
      if (label === 'html' || label === 'handlebars' || label === 'razor' || label === 'xml') return new htmlWorker()
      if (label === 'typescript' || label === 'javascript') return new tsWorker()
      return new editorWorker()
    }
  }

  monaco = monacoBundle

  // macOS/Linux 绝对路径（'/path/to/file'）转 Monaco URI 的辅助函数
  // 正确格式：file:// + /path/to/file = file:///path/to/file（三斜杠）
  // ⚠ 不能用 monaco.Uri.file()，CDN 版本对 macOS 路径校验有 bug
  window.__monacoFileUri = (absPath) =>
    monaco.Uri.parse('file://' + absPath.replace(/#/g, '%23').replace(/\?/g, '%3F'))

  // 注册主题
  monaco.editor.defineTheme(DARK_THEME, {
    base: 'vs-dark', inherit: true,
    rules: [
      { token: 'keyword',    foreground: '569CD6', fontStyle: 'bold' },
      { token: 'type',       foreground: '4EC9B0' },
      { token: 'annotation', foreground: 'DCDCAA' },
      { token: 'string',     foreground: 'CE9178' },
      { token: 'comment',    foreground: '6A9955', fontStyle: 'italic' },
      { token: 'number',     foreground: 'B5CEA8' },
    ],
    colors: {
      'editor.background':                 '#1E1E1E',
      'editor.lineHighlightBackground':    '#2A2D2E',
      'editorLineNumber.foreground':       '#858585',
      'editorIndentGuide.background':      '#404040',
    }
  })
  monaco.editor.defineTheme(LIGHT_THEME, {
    base: 'vs', inherit: true,
    rules: [],
    colors: { 'editor.background': '#FAFAFA' }
  })

  // 创建编辑器实例
  editor = monaco.editor.create(editorContainer.value, {
    language:            'java',
    theme:               props.isDark ? DARK_THEME : LIGHT_THEME,
    readOnly:            true,
    minimap:             { enabled: false },
    scrollBeyondLastLine: false,
    automaticLayout:     true,
    fontSize:            13,
    lineHeight:          22,
    renderLineHighlight: 'line',
    occurrencesHighlight: false,
    scrollbar:           { verticalScrollbarSize: 8, horizontalScrollbarSize: 8 },
    folding:             true,
    wordWrap:            'off',
  })

  // ── DefinitionProvider：仅提供位置信息（Ctrl/Cmd 悬停时显示下划线），不执行跳转 ──
  providerDisp = monaco.languages.registerDefinitionProvider('java', {
    provideDefinition(model, position) {
      const word = model.getWordAtPosition(position)
      if (!word) return null
      const tab = openTabs.value[activeIdx.value]
      if (!tab) return null

      const lineText   = model.getLineContent(position.lineNumber)
      const beforeWord = lineText.substring(0, word.startColumn - 1)
      const dotMatch   = beforeWord.match(/(\w+)\s*\.\s*$/)
      const w          = word.word

      // 占位 range（当前单词位置），让 Monaco 显示下划线
      const selfRange = {
        startLineNumber: position.lineNumber, startColumn: word.startColumn,
        endLineNumber:   position.lineNumber, endColumn:   word.endColumn,
      }

      if (dotMatch) {
        const objName = dotMatch[1]
        if (objName === 'this' || objName === 'super') {
          const sym = (tab.symbols || []).find(s => s.name === w)
          return sym && sym.line > 0 ? [{ uri: model.uri, range: makeLine(sym.line) }] : null
        }
        // 跨文件方法调用：用 deriveClassName 推断，给占位让下划线显示
        // (真正跳转在 onMouseDown 中完成)
        const cls = deriveClassName(objName, tab.typeMap)
        return cls ? [{ uri: model.uri, range: selfRange }] : null
      } else {
        // 本文件方法/本文件内已知符号
        const sym = (tab.symbols || []).find(s => s.name === w)
        if (sym && sym.line > 0) return [{ uri: model.uri, range: makeLine(sym.line) }]
        // 首字母大写且不在本文件符号里 → 可能是外部类名，给占位让下划线显示
        if (/^[A-Z]/.test(w) && !JAVA_PRIMITIVES.has(w)) {
          return [{ uri: model.uri, range: selfRange }]
        }
        return null
      }
    }
  })

  // ── 从对象名称推断类名（处理局部变量 + 字段两种情况）────────────────────────
  // 策略：① typeMap 字段声明 → ② 首字母大写（直接是类名） → ③ 剥离 cpl/io 前缀
  // 例：cplDpCalIntSingleWithPreInPojo → 剥离 cpl → DpCalIntSingleWithPreInPojo
  function deriveClassName(objName, typeMap) {
    if (typeMap && typeMap[objName]) return typeMap[objName]
    if (/^[A-Z]/.test(objName)) return objName
    // 剥离 cpl 前缀（本工程局部变量命名惯例：cplXxxYyyPojo）
    if (objName.startsWith('cpl') && objName.length > 3) {
      const rest = objName.charAt(3).toUpperCase() + objName.slice(4)
      if (/^[A-Z]/.test(rest)) return rest
    }
    // 剥离 io 前缀
    if (objName.startsWith('io') && objName.length > 2) {
      const rest = objName.charAt(2).toUpperCase() + objName.slice(3)
      if (/^[A-Z]/.test(rest)) return rest
    }
    // 兜底：首字母大写
    const cap = objName.charAt(0).toUpperCase() + objName.slice(1)
    return cap
  }

  // ── onMouseDown：Ctrl/Cmd + 左键点击 才真正跳转 ──
  editor.onMouseDown(async (e) => {
    if (!(e.event.ctrlKey || e.event.metaKey) || !e.event.leftButton) return

    const position = e.target.position
    if (!position) return
    const model = editor.getModel()
    if (!model) return

    const word = model.getWordAtPosition(position)
    if (!word) return
    const clickedWord = word.word
    const tab = openTabs.value[activeIdx.value]
    if (!tab) return

    const lineText   = model.getLineContent(position.lineNumber)
    const beforeWord = lineText.substring(0, word.startColumn - 1)
    const dotMatch   = beforeWord.match(/(\w+)\s*\.\s*$/)

    if (dotMatch) {
      // ── 有 obj. 前缀：跨文件「方法」导航 ──
      const objName = dotMatch[1]
      if (objName === 'this' || objName === 'super') return  // 本文件，Monaco 默认处理

      const className = deriveClassName(objName, tab.typeMap)
      await openFileAndReveal(className, clickedWord, tab)

    } else if (/^[A-Z]/.test(clickedWord) && !JAVA_PRIMITIVES.has(clickedWord)) {
      // ── 无 obj. 前缀 + 首字母大写：可能是类名，先查本文件符号 ──
      const localSym = (tab.symbols || []).find(s => s.name === clickedWord)
      if (localSym && localSym.line > 0) return  // 本文件已有（如类自身），Monaco 默认处理

      // 在索引中查找该类名
      await openFileAndReveal(clickedWord, null, tab)
    }
    // 其他情况（小写方法直接调用）→ Monaco 默认处理
  })

  // 跳转后同步 Tab 选中态（本文件跳转时 model 不变，仅跨文件时触发）
  editor.onDidChangeModel(e => {
    if (!e.newModelUrl) return
    const uriStr = e.newModelUrl.toString()
    const idx    = openTabs.value.findIndex(t => t.uri === uriStr)
    if (idx >= 0) activeIdx.value = idx
  })
}

// ── 加载文件 ──────────────────────────────────────────────────────────────────
async function loadFile(filePath, methodName = '', locateText = '') {
  if (!monaco) return
  isLoading.value = true
  setStatus('正在加载源码…')
  try {
    const params = new URLSearchParams({ filePath })
    if (methodName) params.set('methodName', methodName)
    const res  = await fetch(`/api/source?${params}`)
    if (!res.ok) { setStatus('⚠ 暂无源码：文件不存在'); isLoading.value = false; return }
    const data = await res.json()
    setStatus('')

    entryMethod.value = methodName || (locateText ? '__xml_locate__' : '')
    entryLocateText.value = locateText || ''

    const fileUri = window.__monacoFileUri(data.filePath)
    let   model   = monaco.editor.getModel(fileUri)
    const language = data.language || inferLanguage(data.filePath)
    if (!model)   model = monaco.editor.createModel(data.source, language, fileUri)

    openTabs.value = [{
      uri:      fileUri.toString(),
      filename: data.filename,
      kind:     data.kind || null,
      language,
      pkg:      data.pkg  || null,
      filePath: data.filePath,
      typeMap:  data.typeMap  || {},
      symbols:  data.symbols || [],
    }]
    activeIdx.value = 0
    isLoading.value = false
    editor.setModel(model)
    // 强制刷新布局（Monaco 在弹窗动画后可能未正确测量容器尺寸）
    await nextTick()
    editor.layout()

    if (methodName) {
      await nextTick(() => revealMethod(methodName))
    } else if (entryLocateText.value) {
      await nextTick(() => revealText(entryLocateText.value))
    }
  } catch (e) {
    console.error('[MonacoCodeViewer] loadFile error:', e)
    setStatus(`⚠ 加载失败：${e.message}`)
    isLoading.value = false
  }
}

// ── 通用：打开目标类文件并定位到指定符号（方法名或类名），供跨文件跳转复用 ──────
// className: 要打开的类简单名；symbolName: 要定位的符号名（null 时定位到类声明行）
async function openFileAndReveal(className, symbolName, fromTab) {
  const label = symbolName ? `${className}.${symbolName}()` : `${className}`
  setStatus(`🔍 正在查找 ${label} …`)
  try {
    const params = new URLSearchParams({ className })
    if (symbolName)       params.set('methodName', symbolName)
    if (fromTab?.filePath) params.set('currentFilePath', fromTab.filePath)
    const res  = await fetch(`/api/source/find?${params}`)
    if (!res.ok) { setStatus('暂无源码，如需查看请至 IDE 中查看'); return }
    const data = await res.json()
    setStatus('')

    const fileUri    = window.__monacoFileUri(data.filePath)
    const fileUriStr = fileUri.toString()
    let   targetModel = monaco.editor.getModel(fileUri)
    const language = data.language || inferLanguage(data.filePath)
    if (!targetModel) targetModel = monaco.editor.createModel(data.source, language, fileUri)

    const existIdx = openTabs.value.findIndex(t => t.uri === fileUriStr)
    if (existIdx < 0) {
      openTabs.value.push({
        uri:      fileUriStr,
        filename: data.filename,
        kind:     data.kind || 'class',
        language,
        pkg:      data.pkg  || '',
        filePath: data.filePath,
        typeMap:  data.typeMap  || {},
        symbols:  data.symbols || [],
      })
    }
    const targetIdx = existIdx >= 0 ? existIdx : openTabs.value.length - 1

    // 保存当前 tab 视图状态
    const curUri = openTabs.value[activeIdx.value]?.uri
    if (curUri) {
      tabViewStates[curUri] = { scrollTop: editor.getScrollTop(), position: editor.getPosition() }
    }

    activeIdx.value = targetIdx
    scrollActiveTabIntoView()   // 确保新/切换的 Tab 在标签栏中可见
    editor.setModel(targetModel)
    await nextTick()
    editor.layout()

    // 优先按 symbolName 找符号；若无则找与 className 同名的类声明符号
    const lookupName = symbolName || className
    const sym  = (data.symbols || []).find(s => s.name === lookupName)
    const line = sym ? sym.line : 1
    editor.revealLineInCenter(line)
    editor.setPosition({ lineNumber: line, column: 1 })
    const decId = editor.deltaDecorations([], [{
      range:   new monaco.Range(line, 1, line, 1),
      options: { isWholeLine: true, className: 'mcv-line-highlight', stickiness: 1 },
    }])
    setTimeout(() => editor.deltaDecorations(decId, []), 1500)
  } catch (err) {
    setStatus('暂无源码，如需查看请至 IDE 中查看')
  }
}

// ── 跳转到方法定义行 ──────────────────────────────────────────────────────────
function revealMethod(name) {
  if (!editor || !monaco) return
  const tab = openTabs.value[activeIdx.value]
  const sym = (tab?.symbols || []).find(s => s.name === name)
  if (!sym || sym.line < 1) return
  editor.revealLineInCenter(sym.line)
  editor.setPosition({ lineNumber: sym.line, column: 1 })
  const decId = editor.deltaDecorations([], [{
    range: new monaco.Range(sym.line, 1, sym.line, 1),
    options: { isWholeLine: true, className: 'mcv-line-highlight', stickiness: 1 }
  }])
  setTimeout(() => editor.deltaDecorations(decId, []), 1500)
}

function revealText(text) {
  if (!editor || !monaco || !text) return
  const model = editor.getModel()
  if (!model) return
  for (let line = 1; line <= model.getLineCount(); line++) {
    if (!model.getLineContent(line).includes(text)) continue
    editor.revealLineInCenter(line)
    editor.setPosition({ lineNumber: line, column: 1 })
    const decId = editor.deltaDecorations([], [{
      range: new monaco.Range(line, 1, line, 1),
      options: { isWholeLine: true, className: 'mcv-line-highlight', stickiness: 1 }
    }])
    setTimeout(() => editor.deltaDecorations(decId, []), 1500)
    break
  }
}

// ── Tab 切换 / 关闭 ───────────────────────────────────────────────────────────
function switchTab(idx) {
  if (!monaco || !editor) return
  const fromTab = openTabs.value[activeIdx.value]

  // 离开前保存当前 tab 的滚动位置与光标
  if (fromTab) {
    tabViewStates[fromTab.uri] = {
      scrollTop: editor.getScrollTop(),
      position:  editor.getPosition(),
    }
  }

  activeIdx.value = idx
  scrollActiveTabIntoView()   // 点击时也确保 Tab 可见（标签太多时会被遮住）
  const tab = openTabs.value[idx]
  if (!tab) return

  const model = monaco.editor.getModel(monaco.Uri.parse(tab.uri))
  if (!model) return
  editor.setModel(model)

  // 恢复目标 tab 之前的滚动位置与光标（若有）
  const saved = tabViewStates[tab.uri]
  if (saved) {
    requestAnimationFrame(() => {
      editor.setScrollTop(saved.scrollTop ?? 0)
      if (saved.position) editor.setPosition(saved.position)
    })
  }
}

function closeTab(idx) {
  if (idx <= 0) return
  // 清除关闭 tab 的保存状态
  const closingUri = openTabs.value[idx]?.uri
  if (closingUri) delete tabViewStates[closingUri]
  openTabs.value.splice(idx, 1)
  if (activeIdx.value >= idx) activeIdx.value = Math.max(0, activeIdx.value - 1)
  switchTab(activeIdx.value)
}

// ── 标签栏左右滚动 ──────────────────────────────────────────────────────────────
function updateScrollState() {
  const bar = tabBarRef.value
  if (!bar) return
  canScrollLeft.value  = bar.scrollLeft > 2
  canScrollRight.value = bar.scrollLeft + bar.clientWidth < bar.scrollWidth - 2
}

function resetSnippetExplainState(options = {}) {
  const keepVisible = !!options.keepVisible
  if (snippetExplainAbortController) {
    snippetExplainAbortController.abort()
    snippetExplainAbortController = null
  }
  clearSnippetExplainRevealTimer()
  if (!keepVisible) {
    isDrawerFullscreen.value = false
  }
  snippetExplain.value = {
    visible: keepVisible,
    loading: false,
    streaming: false,
    error: '',
    content: '',
    model: '',
    requestKey: '',
  }
  if (!keepVisible) {
    lastNonFullscreenDrawerLayout = null
    emitDrawerLayoutChange({ visible: false })
  }
}

function resolveCurrentMethodName() {
  const explicitMethod = activeIdx.value === 0
    ? (props.methodName || (entryMethod.value === '__xml_locate__' ? '' : entryMethod.value))
    : ''
  if (explicitMethod) {
    return explicitMethod
  }
  const tab = activeTab.value
  if (!tab?.symbols?.length || !editor) {
    return ''
  }
  const position = editor.getPosition()
  if (!position?.lineNumber) {
    return ''
  }
  const candidates = tab.symbols
    .filter(item => Number.isFinite(item?.line) && item.line <= position.lineNumber)
    .sort((a, b) => a.line - b.line)
  return candidates.length ? (candidates[candidates.length - 1].name || '') : ''
}

function buildExplainPayload() {
  const tab = activeTab.value
  const model = editor?.getModel()
  return {
    txId: props.transaction?.id || '',
    txName: props.transaction?.name || '',
    domain: props.transaction?.domain || '',
    nodeCode: props.node?.code || '',
    nodeName: props.node?.name || '',
    nodePrefix: props.node?.prefix || '',
    filePath: tab?.filePath || props.filePath || '',
    fileName: tab?.filename || '',
    methodName: resolveCurrentMethodName(),
    packageName: tab?.pkg || '',
    language: tab?.language || inferLanguage(tab?.filePath || props.filePath || ''),
    focus: [props.node?.code, props.node?.name].filter(Boolean).join(' · ') || (tab?.filename || ''),
    codeContent: model?.getValue?.() || '',
  }
}

async function scrollExplainToBottom() {
  await nextTick()
  const el = explainScrollRef.value
  if (!el) return
  el.scrollTop = el.scrollHeight
}

function splitExplainIntoChunks(content) {
  const normalized = sanitizeExplainMarkdown(content)
  if (!normalized) {
    return []
  }
  const lines = normalized.split('\n')
  const chunks = []
  let paragraphBuffer = []

  const flushParagraph = () => {
    if (!paragraphBuffer.length) {
      return
    }
    chunks.push(paragraphBuffer.join('\n'))
    paragraphBuffer = []
  }

  for (const rawLine of lines) {
    const line = rawLine ?? ''
    const trimmed = line.trim()
    const isHeading = /^#{1,6}\s/.test(trimmed)
    const isTableLine = /^\|.*\|$/.test(trimmed)
    const isListLine = /^([-*+]|\d+\.)\s+/.test(trimmed)

    if (!trimmed) {
      flushParagraph()
      chunks.push('')
      continue
    }

    if (isHeading || isTableLine || isListLine) {
      flushParagraph()
      chunks.push(line)
      continue
    }

    paragraphBuffer.push(line)
    if (paragraphBuffer.join('\n').length >= 140) {
      flushParagraph()
    }
  }

  flushParagraph()
  return chunks
}

async function revealExplainContent(content, options = {}) {
  const append = !!options.append
  const baseContent = append ? sanitizeExplainMarkdown(snippetExplain.value.content || '') : ''
  const chunks = splitExplainIntoChunks(content)

  if (!chunks.length) {
    if (!append) {
      snippetExplain.value.content = sanitizeExplainMarkdown(content)
    }
    return
  }

  clearSnippetExplainRevealTimer()
  if (!append) {
    snippetExplain.value.content = ''
  }

  let cursor = 0
  let assembled = baseContent

  await new Promise((resolve) => {
    const step = async () => {
      if (cursor >= chunks.length) {
        clearSnippetExplainRevealTimer()
        resolve()
        return
      }

      const chunk = chunks[cursor]
      if (!assembled) {
        assembled = chunk
      } else if (!chunk) {
        assembled += '\n\n'
      } else if (assembled.endsWith('\n') || assembled.endsWith('\n\n')) {
        assembled += chunk
      } else {
        assembled += '\n' + chunk
      }
      snippetExplain.value.content = assembled.trim()
      cursor += 1
      await scrollExplainToBottom()
      snippetExplainRevealTimer = setTimeout(step, cursor <= 2 ? 80 : 42)
    }
    step()
  })
}

async function runSnippetExplain(forceRefresh = false) {
  if (explainButtonDisabled.value) {
    return
  }
  if (snippetExplainAbortController) {
    snippetExplainAbortController.abort()
  }
  clearSnippetExplainRevealTimer()
  const controller = new AbortController()
  snippetExplainAbortController = controller
  snippetExplain.value = {
    visible: true,
    loading: true,
    streaming: true,
    error: '',
    content: '',
    model: '',
    requestKey: explainRequestKey.value,
  }
  let hasVisibleDelta = false

  try {
    await streamCodeExplanation(buildExplainPayload(), {
      signal: controller.signal,
      onStart: (event) => {
        snippetExplain.value.model = event.model || ''
      },
      onDelta: async (event) => {
        if (!event?.content) return
        hasVisibleDelta = true
        clearSnippetExplainRevealTimer()
        snippetExplain.value.content += event.content
        await scrollExplainToBottom()
      },
      onDone: async (event) => {
        const finalContent = sanitizeExplainMarkdown(event?.content || '')
        if (finalContent) {
          const currentContent = sanitizeExplainMarkdown(snippetExplain.value.content || '')
          if (!hasVisibleDelta) {
            await revealExplainContent(finalContent, { append: false })
          } else if (!currentContent) {
            snippetExplain.value.content = finalContent
          } else if (finalContent !== currentContent) {
            if (finalContent.startsWith(currentContent)) {
              const remainingContent = finalContent.slice(currentContent.length).replace(/^\n+/, '')
              if (remainingContent) {
                await revealExplainContent(remainingContent, { append: true })
              }
            } else {
              await revealExplainContent(finalContent, { append: false })
            }
          }
        }
        snippetExplain.value.model = event?.model || snippetExplain.value.model
        await scrollExplainToBottom()
      },
      onError: (event) => {
        throw new Error(event?.message || '智能解读失败')
      },
    })
  } catch (error) {
    if (!controller.signal.aborted) {
      snippetExplain.value.error = error?.message || '智能解读失败'
    }
  } finally {
    if (snippetExplainAbortController === controller) {
      snippetExplainAbortController = null
    }
    snippetExplain.value.loading = false
    snippetExplain.value.streaming = false
    await nextTick()
    editor?.layout()
  }
}

async function toggleSnippetExplain() {
  if (snippetExplain.value.visible) {
    const keepVisible = false
    resetSnippetExplainState({ keepVisible })
    await nextTick()
    editor?.layout()
    return
  }
  snippetExplain.value.visible = true
  await nextTick()
  scheduleDrawerLayout()
  if (!snippetExplain.value.content || isExplainStale.value) {
    await runSnippetExplain(false)
  }
}

function scrollTabBar(dir) {
  const bar = tabBarRef.value
  if (!bar) return
  bar.scrollBy({ left: dir * 160, behavior: 'smooth' })
  setTimeout(updateScrollState, 320)
}

/** 滚动标签栏，使当前激活的 tab 完整可见 */
async function scrollActiveTabIntoView() {
  await nextTick()
  const bar = tabBarRef.value
  if (!bar) return
  const tabs = bar.querySelectorAll('.mcv-tab')
  const activeTab = tabs[activeIdx.value]
  if (!activeTab) return
  const barLeft  = bar.scrollLeft
  const barRight = barLeft + bar.clientWidth
  const tabLeft  = activeTab.offsetLeft
  const tabRight = tabLeft + activeTab.offsetWidth
  if (tabLeft < barLeft) {
    bar.scrollTo({ left: tabLeft - 4, behavior: 'smooth' })
  } else if (tabRight > barRight) {
    bar.scrollTo({ left: tabRight - bar.clientWidth + 4, behavior: 'smooth' })
  }
  setTimeout(updateScrollState, 320)
}

// 标签页增减时更新滚动状态
watch(openTabs, () => nextTick(updateScrollState), { deep: true })

// ── 工具 ──────────────────────────────────────────────────────────────────────
const makeLine = (line) => ({ startLineNumber: line, startColumn: 1, endLineNumber: line, endColumn: 1 })

let statusTimer = null
/**
 * 定位 + 清除：关闭所有附加标签（保留入口文件），切回第一个标签，
 * 然后滚动定位到入口方法。
 * 适合"打开了太多关联文件想快速回到起点"的场景。
 */
function locateAndClean() {
  if (!editor || !monaco) return

  // 1. 清除第一个标签以外的所有附加标签（同时清理保存的视图状态）
  const extras = openTabs.value.slice(1)
  extras.forEach(t => { delete tabViewStates[t.uri] })
  openTabs.value = openTabs.value.slice(0, 1)

  // 2. 切换到第一个标签
  activeIdx.value = 0
  const firstTab = openTabs.value[0]
  if (firstTab) {
    const model = monaco.editor.getModel(monaco.Uri.parse(firstTab.uri))
    if (model) editor.setModel(model)
  }

  // 3. 定位到入口方法
  if (entryLocateText.value) {
    revealText(entryLocateText.value)
  } else {
    revealMethod(entryMethod.value)
  }
}

function setStatus(msg) {
  statusMsg.value = msg
  clearTimeout(statusTimer)
  if (msg) statusTimer = setTimeout(() => { statusMsg.value = '' }, 4000)
}

// ── 生命周期 ──────────────────────────────────────────────────────────────────
onMounted(async () => {
  window.addEventListener('resize', scheduleDrawerLayout)
  window.addEventListener('keydown', handleWindowKeydown)
  if ('ResizeObserver' in window && bodyRef.value) {
    drawerResizeObserver = new ResizeObserver(() => scheduleDrawerLayout())
    drawerResizeObserver.observe(bodyRef.value)
  }
  if (!props.filePath) return   // 无源码配置，不需要初始化编辑器
  await initMonaco()
  await loadFile(props.filePath, props.methodName, props.locateText)
})

onBeforeUnmount(() => {
  if (drawerLayoutFrame) {
    cancelAnimationFrame(drawerLayoutFrame)
    drawerLayoutFrame = 0
  }
  if (snippetExplainAbortController) {
    snippetExplainAbortController.abort()
    snippetExplainAbortController = null
  }
  clearSnippetExplainRevealTimer()
  window.removeEventListener('resize', scheduleDrawerLayout)
  window.removeEventListener('keydown', handleWindowKeydown)
  drawerResizeObserver?.disconnect()
  providerDisp?.dispose()
  editor?.dispose()
})

// ── 响应 filePath 变化（父组件切换节点） ─────────────────────────────────────
watch(() => [props.filePath, props.methodName, props.locateText], async ([fp, mn, lt]) => {
  if (!fp) return
  resetSnippetExplainState({ keepVisible: snippetExplain.value.visible })
  if (!monaco) { await initMonaco() }
  await loadFile(fp, mn, lt)
}, { immediate: false })

// ── 主题切换 ──────────────────────────────────────────────────────────────────
watch(() => props.isDark, dark => {
  if (monaco && editor) monaco.editor.setTheme(dark ? DARK_THEME : LIGHT_THEME)
})

watch(
  () => [snippetExplain.value.loading, snippetExplainHtml.value],
  async ([loading, html]) => {
    if (!loading && html) {
      await renderExplainMermaid()
    }
  }
)

watch(() => props.modalShift, async () => {
  await nextTick()
  if (snippetExplain.value.visible && props.visible) {
    scheduleDrawerLayout()
  }
})

// ── v-show 显示后强制重新测量布局（Monaco 在 display:none 时无法计算尺寸） ──
watch(() => props.visible, async (v) => {
  if (v && editor) {
    await nextTick()
    scheduleDrawerLayout()
  }
  // 关闭弹窗时退出全屏
  if (!v && isFullscreen.value) {
    isFullscreen.value = false
    emit('fullscreen-change', false)
  }
  if (!v) {
    isDrawerFullscreen.value = false
    resetSnippetExplainState({ keepVisible: false })
    emitDrawerLayoutChange({ visible: false })
  }
})

watch(activeIdx, async () => {
  resetSnippetExplainState({ keepVisible: snippetExplain.value.visible })
  await nextTick()
  scheduleDrawerLayout()
})

watch(() => snippetExplain.value.visible, async () => {
  await nextTick()
  scheduleDrawerLayout()
})

defineExpose({ loadFile, revealMethod })

function inferLanguage(filePath) {
  return (filePath || '').toLowerCase().endsWith('.xml') ? 'xml' : 'java'
}
</script>

<style scoped>
.mcv-root {
  display: flex; flex-direction: column;
  width: 100%; height: 100%;
  background: var(--code-modal-bg, #1e1e1e);
  border-radius: 10px; overflow: hidden;
}

.mcv-body {
  position: relative;
  display: flex;
  flex: 1;
  min-height: 0;
  background: var(--code-body-bg, #1e1e1e);
  transition: padding 0.24s ease;
}
.mcv-body-drawer-split.mcv-body-drawer-right {
  padding-right: calc(var(--mcv-drawer-width, 0px) + var(--mcv-drawer-gap, 0px));
}
.mcv-body-drawer-split.mcv-body-drawer-left {
  padding-left: calc(var(--mcv-drawer-width, 0px) + var(--mcv-drawer-gap, 0px));
}
.mcv-main-pane {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

/* ── Header ── */
.mcv-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 8px 14px; flex-shrink: 0;
  background: var(--code-header-bg, #2d2d2d);
  border-bottom: 1px solid var(--code-header-bd, #404040);
}
.mcv-title { display: flex; align-items: center; gap: 6px; font-size: 13px; overflow: hidden; }
.mcv-node-name { font-weight: 600; color: var(--text-primary, #ccc); white-space: nowrap; }
.mcv-sep, .mcv-desc { color: var(--text-faint, #888); font-size: 12px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mcv-actions { display: flex; gap: 6px; flex-shrink: 0; }
.mcv-btn {
  display: flex; align-items: center; gap: 4px;
  padding: 4px 8px; border: none; border-radius: 5px;
  background: transparent; color: var(--text-faint, #888);
  cursor: pointer; font-size: 11px;
  transition: background 0.15s, color 0.15s;
}
.mcv-btn:hover { background: var(--node-hover, rgba(255,255,255,0.06)); color: var(--text-primary, #ccc); }
.mcv-close:hover { background: rgba(220,50,50,0.15); color: #e06060; }

/* ── Tab nav 容器（箭头 + 标签栏） ── */
.mcv-tab-nav {
  display: flex; align-items: stretch; flex-shrink: 0;
  background: var(--code-header-bg, #2d2d2d);
  border-bottom: 1px solid var(--code-header-bd, #404040);
  min-width: 0;
}

/* 左右滚动箭头 */
.mcv-tab-scroll-btn {
  flex-shrink: 0; width: 26px;
  display: flex; align-items: center; justify-content: center;
  background: var(--code-header-bg, #2d2d2d);
  border: none; border-right: 1px solid var(--code-header-bd, #404040);
  cursor: pointer; color: var(--text-faint, #888);
  transition: background 0.15s, color 0.15s;
}
.mcv-tab-scroll-right { border-right: none; border-left: 1px solid var(--code-header-bd, #404040); }
.mcv-tab-scroll-btn:hover { background: rgba(255,255,255,0.07); color: var(--text-primary, #ccc); }

/* ── Tab bar ── */
.mcv-tab-bar {
  flex: 1; min-width: 0;
  display: flex; overflow-x: auto; overflow-y: hidden;
  scrollbar-width: none;
}
.mcv-tab-bar::-webkit-scrollbar { display: none; }
.mcv-tab {
  display: flex; align-items: center; gap: 5px;
  padding: 5px 12px; flex-shrink: 0;
  border-right: 1px solid var(--code-header-bd, #404040);
  cursor: pointer; font-size: 12px; color: var(--text-faint, #888);
  transition: background 0.15s, color 0.15s;
}
.mcv-tab:hover { background: rgba(255,255,255,0.04); color: var(--text-primary, #ccc); }
.mcv-tab.active {
  color: var(--text-primary, #ccc);
  background: var(--code-body-bg, #1e1e1e);
  border-bottom: 2px solid var(--accent, #4EC9B0);
}
.mcv-tab-label { max-width: 130px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mcv-tab-close {
  background: none; border: none; cursor: pointer;
  color: var(--text-faint, #888); font-size: 14px; line-height: 1;
  padding: 0 2px; border-radius: 3px;
}
.mcv-tab-close:hover { color: #e06060; background: rgba(220,50,50,0.12); }

/* IDEA 风格类型徽章 */
.kind-badge {
  display: inline-flex; align-items: center; justify-content: center;
  width: 15px; height: 15px; border-radius: 50%;
  font-size: 9px; font-weight: 800; color: #fff; flex-shrink: 0;
}
.kind-class     { background: #3573F0; }
.kind-interface { background: #3D8C5A; }
.kind-enum      { background: #C07B3E; }
.tab-icon { color: var(--text-faint, #888); flex-shrink: 0; }

/* ── 状态消息 ── */
.mcv-status {
  display: flex; align-items: center; gap: 6px;
  padding: 5px 14px; font-size: 12px; flex-shrink: 0;
  background: var(--code-header-bg, #2d2d2d);
  border-bottom: 1px solid rgba(78,201,176,0.25);
  color: #4EC9B0;
}
/* 错误/暂无源码状态 —— 红色突出 */
.mcv-status.mcv-status-error {
  color: #FF4D4F;
  font-size: 13px;
  font-weight: 600;
  background: rgba(255, 77, 79, 0.12);
  border-left: 3px solid #FF4D4F;
  border-bottom: 1px solid rgba(255, 77, 79, 0.3);
  padding-left: 11px;   /* 补偿左边框 3px */
  letter-spacing: 0.01em;
}

/* ── 编辑器 ── */
.mcv-editor { flex: 1; overflow: hidden; min-height: 0; }
.mcv-editor-hidden { visibility: hidden; pointer-events: none; }

/* ══════════════════════════════════════════════════════════════
   智能解读 — 深色科技风（Figma 还原）
   ══════════════════════════════════════════════════════════════ */

/* ── FAB 按钮 ── */
.mcv-ai-fab {
  position: absolute;
  right: 18px;
  top: 50%;
  transform: translateY(-50%);
  width: 48px;
  height: 48px;
  border: none;
  border-radius: 14px;
  background: linear-gradient(135deg, #0f172a 0%, #1a2744 100%);
  color: #94a3b8;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  box-shadow: 0 8px 24px rgba(0,0,0,0.35), inset 0 1px 0 rgba(255,255,255,0.06);
  transition: transform 0.2s, box-shadow 0.2s, right 0.25s, left 0.25s;
  z-index: 5;
}
.mcv-ai-fab:hover {
  transform: translateY(-50%) scale(1.06);
  box-shadow: 0 12px 32px rgba(79,124,255,0.25), inset 0 1px 0 rgba(255,255,255,0.08);
}
.mcv-ai-fab.active { box-shadow: 0 0 0 2px rgba(79,195,247,0.5), 0 8px 24px rgba(0,0,0,0.35); }
.mcv-ai-fab.drawer-left { left: 18px; right: auto; }
.mcv-body-drawer-split.mcv-body-drawer-right .mcv-ai-fab.drawer-split {
  right: calc(var(--mcv-drawer-width, 0px) + var(--mcv-drawer-gap, 0px) + 18px);
}
.mcv-body-drawer-split.mcv-body-drawer-left .mcv-ai-fab.drawer-split {
  left: calc(var(--mcv-drawer-width, 0px) + var(--mcv-drawer-gap, 0px) + 18px);
  right: auto;
}
.mcv-ai-fab.disabled { opacity: 0.35; cursor: not-allowed; box-shadow: none; }
.mcv-ai-fab-icon { display: block; }
.mcv-ai-tooltip {
  position: absolute; right: 58px; top: 50%; transform: translateY(-50%);
  padding: 6px 12px; border-radius: 10px; background: #0f172a; color: #e2e8f0;
  font-size: 12px; white-space: nowrap; opacity: 0; pointer-events: none; transition: opacity 0.15s;
  border: 1px solid rgba(79,195,247,0.2);
}
.mcv-ai-fab.drawer-left .mcv-ai-tooltip { left: 58px; right: auto; }
.mcv-ai-fab:hover .mcv-ai-tooltip { opacity: 1; }

/* ── 侧栏面板 ── */
.mcv-ai-drawer {
  position: fixed;
  display: flex; flex-direction: column; min-height: 0;
  background: linear-gradient(180deg, #0c1629 0%, #111d33 50%, #0a1222 100%);
  border: 1px solid rgba(79,195,247,0.16);
  border-radius: 16px;
  box-shadow: 0 28px 72px rgba(2,6,23,0.58), inset 0 1px 0 rgba(255,255,255,0.04);
  overflow: hidden;
  z-index: 2101;
  backdrop-filter: blur(18px);
}
.mcv-ai-drawer.is-split {
  border-radius: 12px;
  box-shadow: 0 20px 48px rgba(2,6,23,0.42), inset 0 1px 0 rgba(255,255,255,0.04);
}
.mcv-ai-drawer.is-fullscreen {
  border-radius: 20px;
  border-color: rgba(79,195,247,0.22);
  box-shadow: 0 32px 100px rgba(2,6,23,0.72), inset 0 1px 0 rgba(255,255,255,0.05);
  z-index: 2200;
}

/* ── Header ── */
.mcv-ai-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 16px 18px 14px; border-bottom: 1px solid rgba(79,195,247,0.1);
}
.mcv-ai-header-left { display: flex; align-items: center; gap: 12px; min-width: 0; }
.mcv-ai-header-actions { display: flex; align-items: center; gap: 10px; flex-shrink: 0; }
.mcv-ai-icon {
  width: 40px; height: 40px; border-radius: 12px;
  background: linear-gradient(135deg, #1a3a6e 0%, #0f2552 100%);
  display: flex; align-items: center; justify-content: center; flex-shrink: 0;
  box-shadow: 0 4px 12px rgba(0,0,0,0.3);
}
.mcv-ai-title-group { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.mcv-ai-title { font-size: 16px; font-weight: 700; color: #f1f5f9; letter-spacing: 0.5px; }
.mcv-ai-subtitle {
  font-size: 12px; color: #64748b; display: flex; align-items: center; gap: 6px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.mcv-ai-status-dot {
  width: 7px; height: 7px; border-radius: 50%; background: #475569; flex-shrink: 0;
  transition: background 0.3s;
}
.mcv-ai-status-dot.active { background: #22c55e; box-shadow: 0 0 8px rgba(34,197,94,0.6); animation: mcvDotPulse 1.5s ease-in-out infinite; }
.mcv-ai-action-btn,
.mcv-ai-close-btn {
  width: 32px; height: 32px; border-radius: 10px; border: 1px solid rgba(148,163,184,0.15);
  background: rgba(255,255,255,0.04); color: #94a3b8; display: flex; align-items: center; justify-content: center;
  cursor: pointer; transition: all 0.15s; flex-shrink: 0;
}
.mcv-ai-action-btn:hover { background: rgba(79,195,247,0.12); color: #4fc3f7; border-color: rgba(79,195,247,0.28); }
.mcv-ai-close-btn:hover { background: rgba(239,68,68,0.12); color: #ef4444; border-color: rgba(239,68,68,0.3); }

/* ── 滚动内容区 ── */
.mcv-ai-scroll {
  flex: 1; min-height: 0; overflow-y: auto; overflow-x: hidden; padding: 16px;
  display: flex; flex-direction: column; gap: 14px;
  scrollbar-width: thin; scrollbar-color: #1e3a5f transparent;
}
.mcv-ai-scroll::-webkit-scrollbar { width: 5px; }
.mcv-ai-scroll::-webkit-scrollbar-track { background: transparent; }
.mcv-ai-scroll::-webkit-scrollbar-thumb { background: #1e3a5f; border-radius: 3px; }
.mcv-ai-drawer.is-fullscreen .mcv-ai-header {
  padding: 18px 22px 16px;
}
.mcv-ai-drawer.is-fullscreen .mcv-ai-scroll {
  padding: 20px 22px 24px;
  gap: 18px;
}
.mcv-ai-drawer.is-fullscreen .mcv-ai-footer {
  padding: 12px 22px 18px;
}

/* ── 解析状态卡片 ── */
.mcv-ai-status-card {
  position: relative; border-radius: 12px; padding: 32px 20px 14px; min-height: 120px;
  background: linear-gradient(135deg, #0d1b33 0%, #132844 100%);
  border: 1px solid rgba(79,195,247,0.08);
  display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 12px;
  overflow: hidden;
}
.mcv-ai-status-corners .corner {
  position: absolute; width: 18px; height: 18px; border-color: rgba(79,195,247,0.5); border-style: solid;
}
.corner.tl { top: 8px; left: 8px; border-width: 2px 0 0 2px; border-radius: 3px 0 0 0; }
.corner.tr { top: 8px; right: 8px; border-width: 2px 2px 0 0; border-radius: 0 3px 0 0; }
.corner.bl { bottom: 8px; left: 8px; border-width: 0 0 2px 2px; border-radius: 0 0 0 3px; }
.corner.br { bottom: 8px; right: 8px; border-width: 0 2px 2px 0; border-radius: 0 0 3px 0; }
.mcv-ai-status-grid {
  position: absolute; inset: 0;
  background-image:
    linear-gradient(rgba(79,195,247,0.04) 1px, transparent 1px),
    linear-gradient(90deg, rgba(79,195,247,0.04) 1px, transparent 1px);
  background-size: 20px 20px;
  pointer-events: none;
}
.mcv-ai-status-center { position: relative; z-index: 1; }
.mcv-ai-spin-icon { animation: mcvSpin 6s linear infinite; }
.mcv-ai-status-done { position: relative; z-index: 1; }
.mcv-ai-status-label {
  position: relative; z-index: 1; display: flex; align-items: center; gap: 8px;
  font-size: 13px; font-weight: 600; color: #22c55e;
  background: rgba(34,197,94,0.08); padding: 5px 14px; border-radius: 6px;
}

/* ── 区块卡片 ── */
.mcv-ai-section-card {
  border-radius: 10px; padding: 14px 16px;
  background: linear-gradient(135deg, rgba(13,27,51,0.8) 0%, rgba(19,40,68,0.6) 100%);
  border: 1px solid rgba(79,195,247,0.08);
}
.mcv-ai-section-title {
  font-size: 13px; font-weight: 600; color: #94a3b8; margin-bottom: 12px;
  display: flex; align-items: center; gap: 6px;
}

/* ── 链路拓扑分析 ── */
.mcv-ai-topo {
  display: flex; align-items: center; justify-content: center; gap: 0; padding: 10px 0;
}
.mcv-ai-topo-layer {
  display: flex; flex-direction: column; align-items: center; gap: 6px;
}
.mcv-ai-topo-layer.multi { gap: 4px; }
.mcv-ai-topo-layer span { font-size: 11px; color: #64748b; margin-top: 4px; }
.mcv-ai-topo-node {
  width: 12px; height: 12px; border-radius: 50%;
  box-shadow: 0 0 8px rgba(255,255,255,0.15);
}
.mcv-ai-topo-node.sm { width: 8px; height: 8px; }
.mcv-ai-topo-lines { flex-shrink: 0; opacity: 0.5; }

/* ── 代码扫描追踪 ── */
.mcv-ai-code-trace {
  background: rgba(0,0,0,0.2); border-radius: 8px; padding: 12px;
  font-family: inherit;
  font-size: 13px; line-height: 1.8;
}
.mcv-ai-output {
  margin: 0;
  color: #e2e8f0;
  word-break: break-word;
}
.mcv-ai-drawer.is-fullscreen .mcv-ai-code-trace {
  min-height: 320px;
  padding: 18px 20px;
}
.mcv-ai-drawer.is-fullscreen .mcv-ai-output {
  font-size: 14px;
  line-height: 1.9;
}
.mcv-ai-markdown {
  font-size: 13px;
  line-height: 1.85;
}
.mcv-ai-markdown :deep(h1),
.mcv-ai-markdown :deep(h2),
.mcv-ai-markdown :deep(h3),
.mcv-ai-markdown :deep(h4) {
  margin: 0 0 12px;
  color: #f8fafc;
  line-height: 1.45;
  letter-spacing: 0;
}
.mcv-ai-markdown :deep(h1) { font-size: 22px; }
.mcv-ai-markdown :deep(h2) {
  font-size: 18px;
  margin-top: 18px;
  padding-bottom: 8px;
  border-bottom: 1px solid rgba(148, 163, 184, 0.16);
}
.mcv-ai-markdown :deep(h3) {
  font-size: 15px;
  margin-top: 16px;
  color: #cbd5e1;
}
.mcv-ai-markdown :deep(p) {
  margin: 0 0 10px;
  color: #dbe7f5;
}
.mcv-ai-markdown :deep(ul),
.mcv-ai-markdown :deep(ol) {
  margin: 0 0 12px 18px;
  padding: 0;
  color: #dbe7f5;
}
.mcv-ai-markdown :deep(li) {
  margin-bottom: 6px;
}
.mcv-ai-markdown :deep(strong) {
  color: #f8fafc;
  font-weight: 700;
}
.mcv-ai-markdown :deep(blockquote) {
  margin: 12px 0;
  padding: 10px 14px;
  border-left: 3px solid rgba(79, 195, 247, 0.7);
  background: rgba(15, 23, 42, 0.42);
  color: #cbd5e1;
  border-radius: 0 8px 8px 0;
}
.mcv-ai-markdown :deep(pre) {
  margin: 12px 0;
  padding: 12px 14px;
  overflow-x: auto;
  border-radius: 8px;
  background: rgba(2, 6, 23, 0.8);
  border: 1px solid rgba(79, 195, 247, 0.08);
}
.mcv-ai-markdown :deep(code) {
  font-family: 'Consolas','Cascadia Code','JetBrains Mono','Fira Code',monospace;
  font-size: 12px;
}
.mcv-ai-markdown :deep(:not(pre) > code) {
  padding: 2px 6px;
  border-radius: 6px;
  background: rgba(79, 195, 247, 0.12);
  color: #93c5fd;
}
.mcv-ai-markdown :deep(table) {
  width: 100%;
  border-collapse: collapse;
  margin: 12px 0;
  font-size: 12px;
}
.mcv-ai-markdown :deep(th),
.mcv-ai-markdown :deep(td) {
  padding: 8px 10px;
  border: 1px solid rgba(148, 163, 184, 0.12);
  text-align: left;
}
.mcv-ai-markdown :deep(th) {
  color: #e2e8f0;
  background: rgba(30, 41, 59, 0.65);
}
.mcv-ai-markdown :deep(td) {
  color: #cbd5e1;
}
.mcv-ai-markdown :deep(a) {
  color: #7dd3fc;
}
.mcv-ai-mermaid-wrapper {
  margin: 14px 0;
  padding: 18px 20px;
  border-radius: 10px;
  background: linear-gradient(180deg, rgba(3, 20, 32, 0.72) 0%, rgba(8, 45, 66, 0.66) 100%);
  border: 1px solid rgba(79, 227, 255, 0.34);
  box-shadow: inset 0 1px 0 rgba(186, 230, 253, 0.08);
  overflow-x: auto;
  overflow-y: hidden;
}
.mcv-ai-mermaid-wrapper :deep(svg) {
  display: block;
  width: 100%;
  min-width: 0;
  max-width: 100%;
  height: auto;
  margin: 0;
  overflow: visible;
  filter: drop-shadow(0 10px 22px rgba(34, 211, 238, 0.12));
  text-rendering: geometricPrecision;
  -webkit-font-smoothing: antialiased;
}
.mcv-ai-mermaid-wrapper[data-mermaid-kind="sequence"] {
  padding: 22px 24px;
}
.mcv-ai-mermaid-wrapper[data-mermaid-kind="sequence"] :deep(svg) {
  min-width: max-content;
}
.mcv-ai-mermaid-wrapper[data-mermaid-kind="sequence"] :deep(.messageText),
.mcv-ai-mermaid-wrapper[data-mermaid-kind="sequence"] :deep(.messageText > tspan),
.mcv-ai-mermaid-wrapper[data-mermaid-kind="sequence"] :deep(.labelText),
.mcv-ai-mermaid-wrapper[data-mermaid-kind="sequence"] :deep(.labelText > tspan),
.mcv-ai-mermaid-wrapper[data-mermaid-kind="sequence"] :deep(.loopText),
.mcv-ai-mermaid-wrapper[data-mermaid-kind="sequence"] :deep(.loopText > tspan) {
  letter-spacing: 0.02em;
}
.mcv-ai-mermaid-wrapper[data-mermaid-kind="sequence"] :deep(text.actor),
.mcv-ai-mermaid-wrapper[data-mermaid-kind="sequence"] :deep(text.actor > tspan) {
  letter-spacing: 0;
}
.mcv-ai-drawer.is-fullscreen .mcv-ai-mermaid-wrapper[data-mermaid-kind="sequence"] {
  padding: 26px 28px;
}
.mcv-ai-drawer.is-fullscreen .mcv-ai-mermaid-wrapper[data-mermaid-kind="sequence"] :deep(svg) {
  min-width: 1400px;
}
.mcv-ai-mermaid-fallback {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.mcv-ai-mermaid-fallback-title {
  color: #fbbf24;
  font-size: 12px;
  line-height: 1.6;
}
.mcv-ai-mermaid-fallback-pre {
  margin: 0;
  padding: 12px 14px;
  overflow-x: auto;
  border-radius: 8px;
  background: rgba(2, 6, 23, 0.8);
  border: 1px solid rgba(148, 163, 184, 0.12);
}
.mcv-ai-mermaid-fallback-pre code {
  color: #cbd5e1;
}
.mcv-ai-code-loading {
  display: flex; align-items: center; gap: 10px; color: #64748b; font-size: 12px;
  font-family: inherit; padding: 8px 0;
}
.mcv-ai-code-error {
  display: flex; align-items: center; gap: 8px; color: #ef4444; font-size: 12px;
  background: rgba(239,68,68,0.08); border: 1px solid rgba(239,68,68,0.15);
  border-radius: 8px; padding: 10px 12px;
}
.mcv-ai-code-empty {
  color: #475569; font-size: 12px; text-align: center; padding: 16px 8px;
  font-family: inherit;
}
.mcv-ai-loading-dot {
  width: 8px; height: 8px; border-radius: 50%; background: #4fc3f7; flex-shrink: 0;
  animation: mcvPulse 1s ease-in-out infinite;
}

/* ── 统计卡片 ── */
.mcv-ai-stats-row { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.mcv-ai-drawer.is-fullscreen .mcv-ai-stats-row {
  gap: 14px;
}
.mcv-ai-stat-card {
  border-radius: 10px; padding: 14px;
  background: linear-gradient(135deg, rgba(13,27,51,0.8) 0%, rgba(19,40,68,0.6) 100%);
  border: 1px solid rgba(79,195,247,0.08);
  display: flex; flex-direction: column; gap: 4px;
}
.mcv-ai-stat-num { font-size: 22px; font-weight: 800; color: #ef4444; letter-spacing: -0.5px; }
.mcv-ai-stat-card:last-child .mcv-ai-stat-num { color: #3b82f6; }
.mcv-ai-stat-label { font-size: 11px; color: #64748b; }

/* ── 底部 ── */
.mcv-ai-footer {
  padding: 10px 16px 14px; border-top: 1px solid rgba(79,195,247,0.08);
  flex-shrink: 0;
}
.mcv-ai-progress-bar {
  height: 3px; border-radius: 2px; background: rgba(255,255,255,0.06); margin-bottom: 10px;
  overflow: hidden;
}
.mcv-ai-progress-fill {
  height: 100%; border-radius: 2px; transition: width 0.6s ease;
  background: linear-gradient(90deg, #ef4444 0%, #f59e0b 30%, #22c55e 55%, #3b82f6 80%, #8b5cf6 100%);
}
.mcv-ai-footer-row { display: flex; align-items: center; justify-content: space-between; }
.mcv-ai-retry-btn {
  display: flex; align-items: center; gap: 5px; border: none; background: none;
  color: #64748b; font-size: 11px; cursor: pointer; padding: 4px 0; transition: color 0.15s;
}
.mcv-ai-retry-btn:hover { color: #4fc3f7; }
.mcv-ai-retry-btn:disabled { opacity: 0.35; cursor: not-allowed; }
.mcv-ai-version { font-size: 11px; color: #334155; font-family: 'Consolas',monospace; letter-spacing: 0.5px; }

/* ── 动画 ── */
@keyframes mcvPulse {
  0%, 100% { opacity: 0.35; transform: scale(0.9); }
  50% { opacity: 1; transform: scale(1.15); }
}
@keyframes mcvDotPulse {
  0%, 100% { box-shadow: 0 0 4px rgba(34,197,94,0.4); }
  50% { box-shadow: 0 0 12px rgba(34,197,94,0.8); }
}
@keyframes mcvSpin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

/* ── 暂无源码 ── */
.mcv-no-source {
  flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 10px; color: var(--text-faint, #888);
}
.no-source-title { font-size: 15px; font-weight: 600; color: var(--text-secondary, #aaa); }
.no-source-sub   { font-size: 12px; opacity: 0.7; }
.spin-icon { animation: spin 1s linear infinite; }
@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
</style>

<style>
/* Monaco 整行高亮装饰（全局，非 scoped） */
.mcv-line-highlight { background: rgba(78, 201, 176, 0.15) !important; }
</style>
