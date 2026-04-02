<template>
  <div class="transaction-card" :class="{ expanded: isExpanded }">
    <!-- 卡片头部 -->
    <div class="card-header" @click="toggleExpand">
      <div class="card-expand-icon">
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none"
          :style="{ transform: isExpanded ? 'rotate(90deg)' : 'rotate(0deg)', transition: 'transform 0.2s' }">
          <path d="M6 4L10 8L6 12" stroke="#8C94A6" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
      </div>
      <div class="card-main">
        <div class="card-title-row">
          <span class="tx-code">{{ transaction.id }}</span>
          <span class="tx-name">{{ transaction.name }}</span>
        </div>
        <div class="card-meta">
          <span class="meta-badge service">
            <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
              <rect x="1" y="1" width="10" height="10" rx="2" stroke="currentColor" stroke-width="1.2"/>
              <path d="M3.5 6h5M3.5 4h5M3.5 8h3" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"/>
            </svg>
            {{ transaction.serviceCount }} 服务
          </span>
          <span class="meta-badge component">
            <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
              <path d="M6 1.5L10.5 4V8L6 10.5L1.5 8V4L6 1.5z" stroke="currentColor" stroke-width="1.2"/>
            </svg>
            {{ transaction.componentCount }} 构件
          </span>
          <span class="meta-badge table">
            <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
              <rect x="1" y="1" width="10" height="10" rx="1.5" stroke="currentColor" stroke-width="1.2"/>
              <path d="M1 4.5h10M1 7.5h10M4.5 1v10" stroke="currentColor" stroke-width="1.2"/>
            </svg>
            {{ transaction.tableCount }} 表
          </span>
        </div>
      </div>
      <div class="card-right">
        <span class="domain-tag">{{ transaction.domain }}</span>
        <span class="layer-badge">
          <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
            <path d="M6 1.5L10.5 3.5L6 5.5L1.5 3.5L6 1.5z" stroke="currentColor" stroke-width="1.2"/>
            <path d="M1.5 6L6 8L10.5 6" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"/>
            <path d="M1.5 8.5L6 10.5L10.5 8.5" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"/>
          </svg>
          {{ transaction.layers }}层
        </span>
        <button v-if="isExpanded" class="fullscreen-btn" title="全屏展示" @click.stop="openFullscreen">
          <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
            <path d="M1.5 5V2H4.5M9.5 2H12.5V5M12.5 9V12H9.5M4.5 12H1.5V9" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </button>
      </div>
    </div>

    <!-- 全屏遮罩 -->
    <Teleport to="body">
      <div v-if="isFullscreen" class="fs-backdrop" @click.self="closeFullscreen"/>
    </Teleport>

    <!-- API 测试弹窗 -->
    <ApiTester
      :visible="apiTester.visible"
      :tx-code="apiTester.txCode"
      :tx-name="apiTester.txName"
      :is-dark="isDark"
      @close="apiTester.visible = false"
    />

    <!-- 代码查看弹窗（Monaco Editor）
         使用 v-show 而非 v-if：组件常驻 DOM，关闭时只隐藏不销毁，
         保留 Monaco 的 openTabs 状态，再次打开时标签页仍在。 -->
    <Teleport to="body">
      <div v-show="codeViewer.visible"
           class="code-modal-backdrop"
           :class="{
             'code-modal-backdrop-fs': codeViewer.fullscreen,
             'code-modal-backdrop-drawer-right': codeViewer.drawerVisible && !codeViewer.fullscreen && codeViewer.drawerPlacement === 'external-right',
             'code-modal-backdrop-ai-fullscreen': codeViewer.drawerVisible && codeViewer.drawerPlacement === 'fullscreen',
           }"
           :style="{ '--code-modal-reserved-width': `${codeViewer.drawerReservedWidth || 0}px` }"
           @click.self="!codeViewer.fullscreen && codeViewer.drawerPlacement !== 'fullscreen' && closeCodeViewer()">
        <div
          class="code-modal monaco-modal"
          :class="{
            'monaco-modal-fs': codeViewer.fullscreen,
            'monaco-modal-external-right': codeViewer.drawerVisible && !codeViewer.fullscreen && codeViewer.drawerPlacement === 'external-right',
            'monaco-modal-ai-background': codeViewer.drawerVisible && codeViewer.drawerPlacement === 'fullscreen',
            'monaco-modal-ai-hidden': codeViewer.drawerVisible && codeViewer.drawerPlacement === 'fullscreen',
          }"
          :style="{ '--code-modal-reserved-width': `${codeViewer.drawerReservedWidth || 0}px` }"
        >
          <!-- Monaco 代码查看器 -->
          <MonacoCodeViewer
            :node="codeViewer.node"
            :transaction="transaction"
            :file-path="codeViewer.filePath"
            :method-name="codeViewer.methodName"
            :locate-text="codeViewer.locateText"
            :is-dark="isDark"
            :parent-loading="codeViewer.loading"
            :modal-shift="codeViewer.drawerReservedWidth"
            :visible="codeViewer.visible"
            @close="closeCodeViewer"
            @fullscreen-change="handleCodeViewerFullscreenChange"
            @drawer-layout-change="updateCodeViewerDrawer"
          />


        </div>
      </div>
    </Teleport>

    <!-- 链路图：v-if 确保收起时 DOM 彻底销毁，不占内存 -->
    <div v-if="isExpanded" class="chain-view" :class="{ 'chain-view-fullscreen': isFullscreen }">
      <!-- 选中信息栏 / 提示栏 -->
      <div v-if="hasSelection" class="selection-bar">
        <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
          <circle cx="7" cy="7" r="5.5" stroke="#4F7CFF" stroke-width="1.3"/>
          <path d="M4.5 7l2 2 3-3" stroke="#4F7CFF" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
        <span class="selection-text">{{ selectionSummary }}</span>
        <button class="reset-btn" @click.stop="resetSelection">重置</button>
      </div>
      <div v-else class="chain-hint">
        <svg width="13" height="13" viewBox="0 0 13 13" fill="none">
          <circle cx="6.5" cy="6.5" r="5.5" stroke="#8C94A6" stroke-width="1.2"/>
          <path d="M6.5 4v3l2 1.5" stroke="#8C94A6" stroke-width="1.2" stroke-linecap="round"/>
        </svg>
        点击服务层或构件层节点进行关联筛选
      </div>

      <!-- 工具栏 -->
      <div class="chain-toolbar">
        <div v-if="isFullscreen" class="fs-title-bar">
          <span class="tx-code" style="font-family:monospace;">{{ transaction.id }}</span>
          <span style="font-size:14px;font-weight:600;color:#1F2937;">{{ transaction.name }}</span>
          <span class="domain-tag" style="margin-left:4px;">{{ transaction.domain }}</span>
        </div>
        <div v-else class="zoom-hint">
          <svg width="13" height="13" viewBox="0 0 13 13" fill="none">
            <path d="M2 6.5h9M6.5 2v9" stroke="#8C94A6" stroke-width="1.3" stroke-linecap="round"/>
          </svg>
          Ctrl + 滚轮缩放
        </div>
        <div class="toolbar-right">
          <div class="ai-toolbar-actions">
            <button
              class="ai-toolbar-btn"
              :class="{ active: aiAnalysis.visible }"
              :disabled="aiAnalysis.loading"
              @click.stop="toggleAiPanel"
            >
              <svg width="13" height="13" viewBox="0 0 13 13" fill="none">
                <path d="M6.5 1.5L7.6 4.4L10.5 5.5L7.6 6.6L6.5 9.5L5.4 6.6L2.5 5.5L5.4 4.4L6.5 1.5z" stroke="currentColor" stroke-width="1.2" stroke-linejoin="round"/>
                <path d="M10.5 1.8L10.9 2.8L11.9 3.2L10.9 3.6L10.5 4.6L10.1 3.6L9.1 3.2L10.1 2.8L10.5 1.8z" fill="currentColor"/>
              </svg>
              {{ analysisButtonLabel }}
            </button>
            <button
              v-if="aiAnalysis.visible"
              class="ai-toolbar-btn secondary"
              :disabled="aiAnalysis.loading"
              @click.stop="runAiAnalysis(true)"
            >
              <svg width="13" height="13" viewBox="0 0 13 13" fill="none">
                <path d="M10.8 5.2A4.8 4.8 0 1 0 11 7" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"/>
                <path d="M8.9 2.5H11.2V4.8" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              刷新
            </button>
          </div>
          <div class="zoom-controls">
            <button class="zoom-btn" @click.stop="zoomOut">
              <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
                <path d="M3 7h8" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
              </svg>
            </button>
            <span class="zoom-value">{{ Math.round(scale * 100) }}%</span>
            <button class="zoom-btn" @click.stop="zoomIn">
              <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
                <path d="M7 3v8M3 7h8" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
              </svg>
            </button>
          </div>
          <!-- 适应宽度 -->
          <button class="zoom-btn" title="适应宽度" @click.stop="fitToWidth().then(recalcAll)">
            <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
              <path d="M1 7h12M1 7l3-3M1 7l3 3M13 7l-3-3M13 7l-3 3" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </button>
          <button v-if="isFullscreen" class="zoom-btn fs-close-btn" @click.stop="closeFullscreen">
            <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
              <path d="M1.5 5.5H4.5V2.5M9.5 2.5V5.5H12.5M12.5 8.5H9.5V11.5M4.5 11.5V8.5H1.5" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </button>
        </div>
      </div>

      <div v-if="aiAnalysis.visible" class="analysis-panel">
        <div class="analysis-panel-header">
          <div class="analysis-panel-title-group">
            <div class="analysis-panel-title-row">
              <span class="analysis-panel-title">AI 解读</span>
              <span v-if="aiAnalysis.result?.cached" class="analysis-status-tag cached">缓存结果</span>
              <span v-if="isAnalysisStale" class="analysis-status-tag stale">路径已变化</span>
            </div>
            <span class="analysis-panel-scope">{{ analysisScopeLabel }}</span>
          </div>
          <button class="analysis-close-btn" @click.stop="aiAnalysis.visible = false">收起</button>
        </div>

        <div v-if="aiAnalysis.loading" class="analysis-loading">
          <div class="analysis-loading-spinner"/>
          <div class="analysis-loading-text">
            <span class="analysis-loading-title">正在生成解读</span>
            <span class="analysis-loading-sub">会结合当前交易链路、选中路径和关键代码片段进行分析</span>
          </div>
        </div>

        <div v-else-if="aiAnalysis.error" class="analysis-error-card">
          <div>
            <div class="analysis-error-title">AI 解读失败</div>
            <div class="analysis-error-detail">{{ aiAnalysis.error }}</div>
          </div>
        </div>

        <div v-else-if="aiAnalysis.result" class="analysis-content">
          <div class="analysis-stats">
            <div v-for="stat in analysisStats" :key="stat.label" class="analysis-stat-chip">
              <span class="analysis-stat-label">{{ stat.label }}</span>
              <span class="analysis-stat-value">{{ stat.value }}</span>
            </div>
          </div>

          <div class="analysis-section-grid">
            <section class="analysis-section-card">
              <div class="analysis-section-title">总览</div>
              <div class="analysis-section-text">{{ analysisSummaryText || '暂无总览内容' }}</div>
            </section>

            <section class="analysis-section-card">
              <div class="analysis-section-title">业务解读</div>
              <div class="analysis-section-text">{{ analysisBusinessText || '暂无业务解读内容' }}</div>
            </section>

            <section class="analysis-section-card">
              <div class="analysis-section-title">技术检查</div>
              <div class="analysis-section-text">{{ analysisTechnicalText || '暂无技术检查内容' }}</div>
            </section>
          </div>

          <section v-if="analysisFindings.length" class="analysis-section-card">
            <div class="analysis-section-title">规则发现</div>
            <div class="analysis-finding-list">
              <article v-for="item in analysisFindings" :key="`${item.key}-${item.title}`" class="analysis-finding-item">
                <div class="analysis-finding-head">
                  <span class="analysis-severity-chip" :class="findingSeverityClass(item.severity)">
                    {{ item.severity || 'INFO' }}
                  </span>
                  <span class="analysis-finding-title">{{ item.title || item.key }}</span>
                </div>
                <div v-if="item.detail" class="analysis-finding-detail">{{ item.detail }}</div>
                <div v-if="item.evidence" class="analysis-finding-evidence">{{ item.evidence }}</div>
              </article>
            </div>
          </section>

          <section v-if="analysisSnippets.length" class="analysis-section-card">
            <div class="analysis-section-title">关联代码片段</div>
            <div class="analysis-snippet-list">
              <article
                v-for="snippet in analysisSnippets.slice(0, 3)"
                :key="`${snippet.nodeCode}-${snippet.methodName}-${snippet.startLine}`"
                class="analysis-snippet-item"
              >
                <div class="analysis-snippet-head">
                  <span class="analysis-snippet-node">{{ snippet.nodeCode || '未命名节点' }}</span>
                  <span class="analysis-snippet-method">{{ snippet.methodName || '代码片段' }}</span>
                </div>
                <div class="analysis-snippet-path">{{ snippet.filePath }}</div>
                <div v-if="snippet.startLine > 0 && snippet.endLine > 0" class="analysis-snippet-range">
                  第 {{ snippet.startLine }} - {{ snippet.endLine }} 行
                </div>
                <pre class="analysis-snippet-preview">{{ snippetPreview(snippet.content) }}</pre>
              </article>
            </div>
          </section>
        </div>
      </div>

      <!-- 链路画布 -->
      <div ref="chainCanvasEl" class="chain-canvas"
        :style="canvasHeight ? { height: canvasHeight + 'px' } : {}"
        @wheel.prevent="handleWheel" @mousedown="startDrag" @mousemove="onDrag" @mouseup="endDrag" @mouseleave="endDrag">
        <div ref="chainContentEl" class="chain-content"
          :style="{ transform: `scale(${scale}) translate(${translateX}px, ${translateY}px)`, transformOrigin: 'top left' }">

          <!-- SVG 贝塞尔曲线 -->
          <svg class="chain-curves-svg" :width="svgSize.w" :height="svgSize.h">
            <defs>
              <marker id="arrow-marker" markerWidth="8" markerHeight="8" refX="6" refY="3" orient="auto">
                <path d="M0,0 L0,6 L8,3 z" fill="#C5CBD7"/>
              </marker>
            </defs>
            <path v-for="(c,i) in svgCurves" :key="i" :d="c.d" stroke="#C5CBD7" stroke-width="1.5" fill="none" marker-end="url(#arrow-marker)"/>
          </svg>

          <!-- 交易层 -->
          <div class="chain-layer" data-layer="orchestration">
            <div class="layer-header orchestration" @click.stop="toggleLayer('orchestration')">
              <span class="layer-title">交易层</span>
              <svg width="14" height="14" viewBox="0 0 14 14" fill="none"
                :style="{ transform: collapsedLayers.orchestration ? 'rotate(-90deg)' : 'rotate(0)', transition: 'transform 0.2s' }">
                <path d="M3.5 5.5L7 9L10.5 5.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
              </svg>
            </div>
            <div v-show="!collapsedLayers.orchestration" class="layer-nodes">
              <div v-for="node in transaction.chain.orchestration" :key="node.code" class="chain-node orchestration-node">
                <div class="node-first-row">
                  <span class="node-code">{{ node.code }}</span>
                  <button class="code-view-btn test-btn" @click.stop="openApiTester(node)" title="报文测试">
                    <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
                      <path d="M2 3h8M2 6h5.5M2 9h4" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
                      <circle cx="9.5" cy="9" r="2" stroke="#4F7CFF" stroke-width="1.2"/>
                      <path d="M9.5 8v1.2M9.5 10.4v.1" stroke="#4F7CFF" stroke-width="1.2" stroke-linecap="round"/>
                    </svg>
                  </button>
                  <button class="code-view-btn" @click.stop="openCodeViewer(node, 'orchestration')" title="查看代码">
                    <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
                      <path d="M4 2.5L1.5 6 4 9.5" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>
                      <path d="M8 2.5L10.5 6 8 9.5" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>
                      <path d="M6.5 1.5l-1 9" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
                    </svg>
                  </button>
                </div>
                <span class="node-name">{{ node.name }}</span>
              </div>
            </div>
          </div>

          <!-- 服务层 -->
          <div class="chain-layer" data-layer="service">
            <div class="layer-header service" @click.stop="toggleLayer('service')">
              <span class="layer-title">服务层</span>
              <div class="layer-header-right">
                <span v-if="activePath.service && hasSvcToSvc && activeCalledServices.length" class="layer-badge-count">
                  {{ activeCalledServices.length }}/{{ calledServices.length }}
                </span>
                <span class="layer-click-hint">点击筛选</span>
                <svg width="14" height="14" viewBox="0 0 14 14" fill="none"
                  :style="{ transform: collapsedLayers.service ? 'rotate(-90deg)' : 'rotate(0)', transition: 'transform 0.2s' }">
                  <path d="M3.5 5.5L7 9L10.5 5.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
                </svg>
              </div>
            </div>

            <div v-show="!collapsedLayers.service" ref="serviceLayerRef" class="svc-inner-canvas">
              <!-- pcs→pbs 调用箭头 SVG -->
              <svg v-if="serviceCallArrows.length" class="service-call-svg" :width="serviceCallSvgSize.w" :height="serviceCallSvgSize.h">
                <defs>
                  <marker id="svc-arrow" markerWidth="8" markerHeight="8" refX="6" refY="3" orient="auto">
                    <path d="M0,0 L0,6 L8,3 z" fill="#C5CBD7"/>
                  </marker>
                </defs>
                <path v-for="(a,i) in serviceCallArrows" :key="i" :d="a.d" stroke="#C5CBD7" stroke-width="1.5" fill="none" marker-end="url(#svc-arrow)"/>
              </svg>

              <!-- 流程编排子层 -->
              <div class="svc-inner-layer pbs-inner-layer">
                <div class="svc-inner-header">
                  <span class="svc-inner-title">流程编排</span>
                </div>
                <div class="svc-inner-nodes">
                  <template v-for="node in orchestrationServices" :key="node.code">
                    <!-- 服务节点 -->
                    <div :data-scode="node.code" class="chain-node service-node"
                      :class="{ 'node-selected': selectedServices.has(node.code) }"
                      @click.stop="selectOrchService(node.code)">
                      <div class="node-header">
                        <span class="node-prefix" :class="`prefix-${node.prefix}`">{{ node.prefix }}</span>
                        <span class="node-code-service">{{ node.code }}</span>
                        <div class="node-header-actions">
                          <svg v-if="selectedServices.has(node.code)" class="node-check" width="14" height="14" viewBox="0 0 14 14" fill="none">
                            <circle cx="7" cy="7" r="6" fill="#12B886"/>
                            <path d="M4 7l2.5 2.5 4-4" stroke="white" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
                          </svg>
                          <button class="code-view-btn" @click.stop="openCodeViewer(node, 'service')" title="查看代码">
                            <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
                              <path d="M4 2.5L1.5 6 4 9.5" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>
                              <path d="M8 2.5L10.5 6 8 9.5" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>
                              <path d="M6.5 1.5l-1 9" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
                            </svg>
                          </button>
                        </div>
                      </div>
                      <span class="node-name">{{ node.name }}</span>
                      <span v-if="isCrossDomain(node)" class="cross-domain-tag"
                        :style="{ background: domainStyle(node.domain).bg, color: domainStyle(node.domain).color, border: `1px solid ${domainStyle(node.domain).border}` }">
                        <svg width="10" height="10" viewBox="0 0 10 10" fill="none">
                          <path d="M2 8L8 2M8 2H4M8 2v4" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
                        </svg>
                        {{ node.domain }}
                      </span>
                    </div>
                  </template>
                </div>
              </div>

              <!-- 编码调用子层 -->
              <div v-if="hasSvcToSvc && activeCalledServices.length" class="svc-inner-layer pcs-inner-layer">
                <div class="svc-inner-header">
                  <span class="svc-inner-title">编码调用</span>
                  <span v-if="selectedServices.size && activeCalledServices.length < calledServices.length" class="layer-badge-count" style="font-size:10px;padding:1px 6px;">
                    {{ activeCalledServices.length }}/{{ calledServices.length }}
                  </span>
                </div>
                <div class="svc-inner-nodes">
                  <div v-for="node in activeCalledServices" :key="node.code" :data-scode="node.code"
                    class="chain-node service-node pcs-service-node"
                    :class="{ 'node-selected': selectedServices.has(node.code) }"
                    @click.stop="selectCalledSvc(node.code)">
                    <div class="node-header">
                      <span class="node-prefix" :class="`prefix-${node.prefix}`">{{ node.prefix }}</span>
                      <span class="node-code-service">{{ node.code }}</span>
                      <div class="node-header-actions">
                        <svg v-if="selectedServices.has(node.code)" class="node-check" width="14" height="14" viewBox="0 0 14 14" fill="none">
                          <circle cx="7" cy="7" r="6" fill="#12B886"/>
                          <path d="M4 7l2.5 2.5 4-4" stroke="white" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
                        </svg>
                        <button class="code-view-btn" @click.stop="openCodeViewer(node, 'service')" title="查看代码">
                          <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
                            <path d="M4 2.5L1.5 6 4 9.5" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>
                            <path d="M8 2.5L10.5 6 8 9.5" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>
                            <path d="M6.5 1.5l-1 9" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
                          </svg>
                        </button>
                      </div>
                    </div>
                    <span class="node-name">{{ node.name }}</span>
                    <span v-if="isCrossDomain(node)" class="cross-domain-tag"
                      :style="{ background: domainStyle(node.domain).bg, color: domainStyle(node.domain).color, border: `1px solid ${domainStyle(node.domain).border}` }">
                      <svg width="10" height="10" viewBox="0 0 10 10" fill="none">
                        <path d="M2 8L8 2M8 2H4M8 2v4" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
                      </svg>
                      {{ node.domain }}
                    </span>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- 构件层（双子框：业务/产品构件 + 公共/技术构件） -->
          <div class="chain-layer" data-layer="component">
            <div class="layer-header component" @click.stop="toggleLayer('component')">
              <span class="layer-title">构件层</span>
              <div class="layer-header-right">
                <span v-if="activePath.service || activePath.calledSvc" class="layer-badge-count">
                  {{ visibleBizCompCount + visibleTechCompCount }}/{{ transaction.chain.component.length }}
                </span>
                <span class="layer-click-hint">点击筛选</span>
                <svg width="14" height="14" viewBox="0 0 14 14" fill="none"
                  :style="{ transform: collapsedLayers.component ? 'rotate(-90deg)' : 'rotate(0)', transition: 'transform 0.2s' }">
                  <path d="M3.5 5.5L7 9L10.5 5.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
                </svg>
              </div>
            </div>

            <!-- 构件层内嵌双子框 -->
            <div v-show="!collapsedLayers.component" ref="compLayerRef" class="svc-inner-canvas">
              <!-- 构件间调用箭头 SVG -->
              <svg v-if="compCallArrows.length" class="service-call-svg"
                :width="compCallSvgSize.w" :height="compCallSvgSize.h">
                <defs>
                  <marker id="comp-arrow" markerWidth="8" markerHeight="8" refX="6" refY="3" orient="auto">
                    <path d="M0,0 L0,6 L8,3 z" fill="#C5CBD7"/>
                  </marker>
                </defs>
                <path v-for="(a,i) in compCallArrows" :key="i" :d="a.d"
                  stroke="#C5CBD7" stroke-width="1.5" fill="none" marker-end="url(#comp-arrow)"/>
              </svg>

              <!-- 左子框：业务/产品构件（pbcb/pbcp），无可见构件时隐藏 -->
              <div v-if="visibleBizComps.length" class="svc-inner-layer comp-biz-layer">
                <div class="svc-inner-header" style="border-top-color:#F76707;background:linear-gradient(to bottom,rgba(247,103,7,0.05),transparent)">
                  <span class="svc-inner-title">业务/产品构件</span>
                  <span v-if="activePath.bizComp" class="layer-badge-count" style="background:#F76707;">
                    1/{{ bizComps.length }}
                  </span>
                </div>
                <div class="svc-inner-nodes">
                  <div v-for="node in visibleBizComps" :key="node.code" :data-ccode="node.code"
                    class="chain-node component-node"
                    :class="{ 'node-selected': activePath.bizComp === node.code }"
                    @click.stop="selectBizComp(node.code)">
                    <div class="node-header">
                      <span class="node-prefix" :class="`prefix-${node.prefix}`">{{ node.prefix }}</span>
                      <span class="node-code-service">{{ node.code }}</span>
                      <div class="node-header-actions">
                        <svg v-if="activePath.bizComp === node.code" class="node-check" width="14" height="14" viewBox="0 0 14 14" fill="none">
                          <circle cx="7" cy="7" r="6" fill="#F76707"/>
                          <path d="M4 7l2.5 2.5 4-4" stroke="white" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
                        </svg>
                        <button class="code-view-btn" @click.stop="openCodeViewer(node, 'component')" title="查看代码">
                          <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
                            <path d="M4 2.5L1.5 6 4 9.5" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>
                            <path d="M8 2.5L10.5 6 8 9.5" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>
                            <path d="M6.5 1.5l-1 9" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
                          </svg>
                        </button>
                      </div>
                    </div>
                    <span class="node-name">{{ node.name }}</span>
                    <span v-if="isCrossDomain(node)" class="cross-domain-tag"
                      :style="{ background: domainStyle(node.domain).bg, color: domainStyle(node.domain).color, border: `1px solid ${domainStyle(node.domain).border}` }">
                      <svg width="10" height="10" viewBox="0 0 10 10" fill="none">
                        <path d="M2 8L8 2M8 2H4M8 2v4" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
                      </svg>
                      {{ node.domain }}
                    </span>
                  </div>
                </div>
              </div>

              <!-- 右子框：公共/技术构件（pbcc/pbct），无可见构件时隐藏 -->
              <div v-if="visibleTechComps.length" class="svc-inner-layer comp-tech-layer">
                <div class="svc-inner-header" style="border-top-color:#7950F2;background:linear-gradient(to bottom,rgba(121,80,242,0.05),transparent)">
                  <span class="svc-inner-title">公共/技术构件</span>
                  <span v-if="activePath.techComp" class="layer-badge-count" style="background:#7950F2;">
                    1/{{ techComps.length }}
                  </span>
                </div>
                <div class="svc-inner-nodes">
                  <div v-for="node in visibleTechComps" :key="node.code" :data-ccode="node.code"
                    class="chain-node component-node"
                    :class="{ 'node-selected': activePath.techComp === node.code }"
                    @click.stop="selectTechComp(node.code)">
                    <div class="node-header">
                      <span class="node-prefix" :class="`prefix-${node.prefix}`">{{ node.prefix }}</span>
                      <span class="node-code-service">{{ node.code }}</span>
                      <div class="node-header-actions">
                        <svg v-if="activePath.techComp === node.code" class="node-check" width="14" height="14" viewBox="0 0 14 14" fill="none">
                          <circle cx="7" cy="7" r="6" fill="#7950F2"/>
                          <path d="M4 7l2.5 2.5 4-4" stroke="white" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
                        </svg>
                        <button class="code-view-btn" @click.stop="openCodeViewer(node, 'component')" title="查看代码">
                          <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
                            <path d="M4 2.5L1.5 6 4 9.5" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>
                            <path d="M8 2.5L10.5 6 8 9.5" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>
                            <path d="M6.5 1.5l-1 9" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
                          </svg>
                        </button>
                      </div>
                    </div>
                    <span class="node-name">{{ node.name }}</span>
                    <span v-if="isCrossDomain(node)" class="cross-domain-tag"
                      :style="{ background: domainStyle(node.domain).bg, color: domainStyle(node.domain).color, border: `1px solid ${domainStyle(node.domain).border}` }">
                      <svg width="10" height="10" viewBox="0 0 10 10" fill="none">
                        <path d="M2 8L8 2M8 2H4M8 2v4" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
                      </svg>
                      {{ node.domain }}
                    </span>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- 数据层 -->
          <div class="chain-layer" data-layer="data">
            <div class="layer-header data" @click.stop="toggleLayer('data')">
              <span class="layer-title">数据层</span>
              <div class="layer-header-right">
                <span v-if="activePath.techComp || activePath.bizComp || activePath.calledSvc || activePath.service" class="layer-badge-count data-badge">
                  {{ visibleDataCount }}/{{ transaction.chain.data.length }}
                </span>
                <svg width="14" height="14" viewBox="0 0 14 14" fill="none"
                  :style="{ transform: collapsedLayers.data ? 'rotate(-90deg)' : 'rotate(0)', transition: 'transform 0.2s' }">
                  <path d="M3.5 5.5L7 9L10.5 5.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
                </svg>
              </div>
            </div>
            <div v-show="!collapsedLayers.data" class="layer-nodes">
              <div v-for="node in transaction.chain.data" v-show="showData(node.code)" :key="node.code" class="chain-node data-node">
                <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
                  <rect x="1.5" y="1.5" width="11" height="11" rx="1.5" stroke="#7950F2" stroke-width="1.3"/>
                  <path d="M1.5 5h11M1.5 9h11M5 1.5v11" stroke="#7950F2" stroke-width="1.3"/>
                </svg>
                <span class="node-table-code">{{ node.code }}</span>
              </div>
            </div>
          </div>

        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, nextTick, onMounted } from 'vue'
import MonacoCodeViewer from './MonacoCodeViewer.vue'
import ApiTester from './ApiTester.vue'
import { analyzeFlowtranTransaction } from '../api/index.js'

const props = defineProps({
  transaction:     { type: Object,  required: true },
  defaultExpanded: { type: Boolean, default: false },
  isDark:          { type: Boolean, default: false }
})

const isExpanded = ref(props.defaultExpanded)
const isFullscreen = ref(false)
const scale = ref(1)
const translateX = ref(0)
const translateY = ref(0)

const collapsedLayers = reactive({ orchestration: false, service: false, component: false, data: false })

const chainContentEl = ref(null)
const chainCanvasEl  = ref(null)
const svgCurves = ref([])
const svgSize = reactive({ w: 0, h: 0 })
const canvasHeight = ref(null)  // 动态高度，消除缩放后的空白

// 根据 scale 重新计算 canvas 高度（transform 不影响 DOM 流，需手动补正）
const updateCanvasHeight = async () => {
  await nextTick()
  const content = chainContentEl.value
  if (!content) { canvasHeight.value = null; return }
  const naturalH = content.scrollHeight
  canvasHeight.value = Math.max(160, Math.ceil(naturalH * scale.value) + 36)
}

const serviceLayerRef = ref(null)
const serviceCallArrows = ref([])
const serviceCallSvgSize = reactive({ w: 0, h: 0 })

const LAYER_ORDER = ['orchestration', 'service', 'component', 'data']

// ---- 跨领域标签 ----
const DOMAIN_COLORS = {
  '公共领域': { bg: '#EEF3FF', color: '#3B5ADB', border: '#C5D5FF' },
  '贷款领域': { bg: '#FFF4E6', color: '#E67700', border: '#FFD8A8' },
  '存款领域': { bg: '#E6FCF5', color: '#087F5B', border: '#96F2D7' },
  '结算领域': { bg: '#F3F0FF', color: '#5F3DC4', border: '#B197FC' },
}
const domainStyle = (d) => DOMAIN_COLORS[d] || { bg: '#F3F4F6', color: '#374151', border: '#D1D5DB' }
const isCrossDomain = (node) => node.domain && node.domain !== props.transaction.domain

const relations = computed(() => props.transaction.chain.relations || {
  rootServices: [],
  serviceToService: {},
  serviceToComponent: {},
  componentToComponent: {},
  componentToData: {},
  nodeToData: {}
})

const hasSvcToSvc = computed(() => Object.keys(relations.value.serviceToService || {}).length > 0)

// ---- 构件层分类 ----
const BIZ_PREFIXES  = new Set(['pbcb', 'pbcp'])   // 业务/产品构件
const TECH_PREFIXES = new Set(['pbcc', 'pbct'])   // 公共/技术构件

const bizComps  = computed(() => props.transaction.chain.component.filter(c => BIZ_PREFIXES.has(c.prefix)))
const techComps = computed(() => props.transaction.chain.component.filter(c => TECH_PREFIXES.has(c.prefix)))

// 构件间调用关系（pbcb/pbcp → pbcc/pbct）
const compCallSvgRef = ref(null)
const compLayerRef   = ref(null)
const compCallArrows = ref([])
const compCallSvgSize = reactive({ w: 0, h: 0 })

const serviceNodeMap = computed(() =>
  new Map((props.transaction.chain.service || []).map(node => [node.code, node]))
)
const componentNodeMap = computed(() =>
  new Map((props.transaction.chain.component || []).map(node => [node.code, node]))
)

const serviceToServiceMap = computed(() => relations.value.serviceToService || {})
const serviceToComponentMap = computed(() => relations.value.serviceToComponent || {})
const componentToComponentMap = computed(() => relations.value.componentToComponent || {})
const componentParentMap = computed(() => {
  const reverse = {}
  Object.entries(componentToComponentMap.value || {}).forEach(([parentCode, children]) => {
    ;(children || []).forEach(childCode => {
      if (!reverse[childCode]) reverse[childCode] = []
      if (!reverse[childCode].includes(parentCode)) reverse[childCode].push(parentCode)
    })
  })
  return reverse
})
const nodeToDataMap = computed(() => relations.value.nodeToData || relations.value.componentToData || {})

// ======== 服务路径下钻（serviceTrail）+ 构件层级下钻 ========
const activePath = reactive({ serviceTrail: [], bizComp: null, techComp: null })
Object.defineProperty(activePath, 'service', {
  get: () => activePath.serviceTrail[0] || null,
  enumerable: false
})
Object.defineProperty(activePath, 'calledSvc', {
  get: () => activePath.serviceTrail.length ? activePath.serviceTrail[activePath.serviceTrail.length - 1] : null,
  enumerable: false
})
Object.defineProperty(activePath, 'component', {
  get: () => activePath.bizComp || activePath.techComp,
  enumerable: false
})

const calledByCodes = computed(() => {
  const set = new Set()
  Object.values(serviceToServiceMap.value).forEach(list => (list || []).forEach(code => set.add(code)))
  return set
})

const rootServiceCodes = computed(() => {
  const configured = new Set(relations.value.rootServices || [])
  if (configured.size) {
    return configured
  }
  const inferred = new Set()
  ;(props.transaction.chain.service || []).forEach(node => {
    if (!calledByCodes.value.has(node.code)) inferred.add(node.code)
  })
  return inferred
})

const orchestrationServices = computed(() =>
  (props.transaction.chain.service || []).filter(node => rootServiceCodes.value.has(node.code))
)
const calledServices = computed(() =>
  (props.transaction.chain.service || []).filter(node => !rootServiceCodes.value.has(node.code))
)

const selectedServiceCode = computed(() =>
  activePath.serviceTrail.length ? activePath.serviceTrail[activePath.serviceTrail.length - 1] : null
)

const hasSelection = computed(() =>
  activePath.serviceTrail.length > 0 || !!(activePath.bizComp || activePath.techComp)
)

const selectionSummary = computed(() => {
  const parts = activePath.serviceTrail
    .map(code => serviceNodeMap.value.get(code)?.name || code)
  if (activePath.bizComp) {
    parts.push(componentNodeMap.value.get(activePath.bizComp)?.name || activePath.bizComp)
  }
  if (activePath.techComp) {
    parts.push(componentNodeMap.value.get(activePath.techComp)?.name || activePath.techComp)
  }
  return parts.join(' → ')
})

const walkClosure = (startCodes, adjacency) => {
  const visited = new Set()
  const stack = [...startCodes]
  while (stack.length) {
    const code = stack.pop()
    if (!code || visited.has(code)) continue
    visited.add(code)
    ;(adjacency[code] || []).forEach(next => {
      if (!visited.has(next)) stack.push(next)
    })
  }
  return visited
}

const directServiceChildren = (code) => serviceToServiceMap.value[code] || []
const directComponentChildren = (code) => componentToComponentMap.value[code] || []
const directNodeTables = (code) => nodeToDataMap.value[code] || []

const collectServiceClosure = (startCode, { includeStart = true } = {}) => {
  if (!startCode) return new Set()
  const closure = walkClosure([startCode], serviceToServiceMap.value)
  if (!includeStart) closure.delete(startCode)
  return closure
}

const collectComponentClosure = (startCodes, { includeStart = true } = {}) => {
  const closure = walkClosure(startCodes, componentToComponentMap.value)
  if (!includeStart) {
    startCodes.forEach(code => closure.delete(code))
  }
  return closure
}

const collectComponentAncestors = (startCodes, { includeStart = true } = {}) => {
  const closure = walkClosure(startCodes, componentParentMap.value)
  if (!includeStart) {
    startCodes.forEach(code => closure.delete(code))
  }
  return closure
}

const collectComponentContext = (componentCode) => {
  if (!componentCode) return new Set()
  const context = collectComponentClosure([componentCode])
  collectComponentAncestors([componentCode], { includeStart: false }).forEach(code => context.add(code))
  return context
}

const collectComponentsForService = (serviceCode) => {
  const services = collectServiceClosure(serviceCode)
  const directComponents = new Set()
  services.forEach(code => {
    ;(serviceToComponentMap.value[code] || []).forEach(compCode => directComponents.add(compCode))
  })
  const allComponents = new Set(directComponents)
  collectComponentClosure([...directComponents], { includeStart: false }).forEach(code => allComponents.add(code))
  return allComponents
}

const collectTablesForComponent = (componentCode) => {
  const components = collectComponentClosure([componentCode])
  const tables = new Set()
  components.forEach(code => {
    directNodeTables(code).forEach(table => tables.add(table))
  })
  return tables
}

const collectTablesForService = (serviceCode) => {
  const services = collectServiceClosure(serviceCode)
  const tables = new Set()
  services.forEach(code => {
    directNodeTables(code).forEach(table => tables.add(table))
  })
  collectComponentsForService(serviceCode).forEach(code => {
    directNodeTables(code).forEach(table => tables.add(table))
  })
  return tables
}

// 服务层点击后保留完整服务视图，只联动下游构件/数据层筛选。
const activeCalledServices = computed(() => calledServices.value)

const visibleComponentCodes = computed(() => {
  if (activePath.techComp) return collectComponentContext(activePath.techComp)
  if (activePath.bizComp) return collectComponentContext(activePath.bizComp)
  if (selectedServiceCode.value) return collectComponentsForService(selectedServiceCode.value)
  return new Set((props.transaction.chain.component || []).map(node => node.code))
})

const showComponent = (code) => visibleComponentCodes.value.has(code)

const visibleBizComps = computed(() =>
  bizComps.value.filter(node => showComponent(node.code))
)

const visibleTechComps = computed(() =>
  techComps.value.filter(node => showComponent(node.code))
)

const visibleBizCompCount  = computed(() => visibleBizComps.value.length)
const visibleTechCompCount = computed(() => visibleTechComps.value.length)

const visibleDataCodes = computed(() => {
  if (activePath.techComp) return collectTablesForComponent(activePath.techComp)
  if (activePath.bizComp) return collectTablesForComponent(activePath.bizComp)
  if (selectedServiceCode.value) return collectTablesForService(selectedServiceCode.value)
  return new Set((props.transaction.chain.data || []).map(node => node.code))
})

const showData = (code) => visibleDataCodes.value.has(code)

// badge 计数
const visibleComponentCount = computed(() =>
  (props.transaction.chain.component || []).filter(n => showComponent(n.code)).length
)
const visibleDataCount = computed(() =>
  (props.transaction.chain.data || []).filter(n => showData(n.code)).length
)

// ---- 点击处理 ----
const selectOrchService = (code) => {
  if (activePath.serviceTrail.length === 1 && activePath.serviceTrail[0] === code) {
    activePath.serviceTrail = []
  } else {
    activePath.serviceTrail = [code]
  }
  activePath.bizComp = null
  activePath.techComp = null
  nextTick(() => { recalcCurves(); recalcServiceCallArrows(); recalcCompCallArrows() })
}

const selectCalledSvc = (code) => {
  const trail = [...activePath.serviceTrail]
  const last = trail[trail.length - 1]

  if (!trail.length) {
    activePath.serviceTrail = [code]
  } else if (last === code) {
    activePath.serviceTrail = trail.slice(0, -1)
  } else if (trail.includes(code)) {
    activePath.serviceTrail = trail.slice(0, trail.indexOf(code) + 1)
  } else if (directServiceChildren(last).includes(code)) {
    activePath.serviceTrail = [...trail, code]
  } else {
    activePath.serviceTrail = [code]
  }

  activePath.bizComp = null
  activePath.techComp = null
  nextTick(() => { recalcCurves(); recalcCompCallArrows() })
}

// 点击业务/产品构件（pbcb/pbcp）→ 设置 bizComp，清除 techComp
const selectBizComp = (code) => {
  activePath.bizComp  = activePath.bizComp === code ? null : code
  activePath.techComp = null
  nextTick(() => { recalcCurves(); recalcCompCallArrows() })
}

// 点击公共/技术构件（pbcc/pbct）→ 设置 techComp
const selectTechComp = (code) => {
  activePath.techComp = activePath.techComp === code ? null : code
  nextTick(() => { recalcCurves(); recalcCompCallArrows() })
}

// 兼容旧 toggleComponent（现在区分 biz/tech）
const toggleComponent = (code) => {
  const node = props.transaction.chain.component.find(c => c.code === code)
  if (!node) return
  BIZ_PREFIXES.has(node.prefix) ? selectBizComp(code) : selectTechComp(code)
}

const resetSelection = () => {
  activePath.serviceTrail = []
  activePath.bizComp = null
  activePath.techComp  = null
  nextTick(() => { recalcCurves(); recalcServiceCallArrows(); recalcCompCallArrows() })
}

const selectedServices   = computed(() => new Set(activePath.serviceTrail))
const selectedComponents = computed(() => new Set([activePath.bizComp, activePath.techComp].filter(Boolean)))

const createAnalysisSessionId = () =>
  `tx-${props.transaction.id}-${Math.random().toString(36).slice(2, 10)}`

const aiSessionId = ref(createAnalysisSessionId())
const aiAnalysis = reactive({
  visible: false,
  loading: false,
  error: '',
  result: null,
  requestKey: '',
})

const analysisSelectedPath = computed(() => [
  ...activePath.serviceTrail,
  ...(activePath.bizComp ? [activePath.bizComp] : []),
  ...(activePath.techComp ? [activePath.techComp] : []),
])

const analysisFocus = computed(() =>
  selectionSummary.value || `${props.transaction.id} ${props.transaction.name}`
)

const analysisScopeLabel = computed(() =>
  hasSelection.value ? `当前路径：${selectionSummary.value}` : '当前范围：整笔交易'
)

const currentAnalysisKey = computed(() =>
  JSON.stringify({
    txId: props.transaction.id,
    path: analysisSelectedPath.value,
    focus: analysisFocus.value,
  })
)

const analysisButtonLabel = computed(() => {
  if (aiAnalysis.loading) return '解读中'
  if (aiAnalysis.visible) return '收起解读'
  if (!aiAnalysis.result) return 'AI解读'
  return currentAnalysisKey.value === aiAnalysis.requestKey ? '查看解读' : '更新解读'
})

const isAnalysisStale = computed(() =>
  !!aiAnalysis.result && !!aiAnalysis.requestKey && aiAnalysis.requestKey !== currentAnalysisKey.value
)

const analysisFindings = computed(() => aiAnalysis.result?.findings || [])
const analysisSnippets = computed(() => aiAnalysis.result?.codeSnippets || [])
const analysisStats = computed(() => {
  const stats = aiAnalysis.result?.contextStats || {}
  return [
    { label: '模式', value: stats.mode || aiAnalysis.result?.mode || '--' },
    { label: '模型', value: aiAnalysis.result?.model || stats.model || '--' },
    { label: '片段', value: stats.snippetCount ?? analysisSnippets.value.length },
    { label: '规则', value: stats.findingCount ?? analysisFindings.value.length },
  ]
})

const stripSectionTitle = (text, label) => {
  if (!text) return ''
  const pattern = new RegExp(`^##\\s*${label}\\s*\\n?`, 'i')
  return text.replace(pattern, '').trim()
}

const analysisSummaryText = computed(() =>
  stripSectionTitle(aiAnalysis.result?.summary, '总览')
)
const analysisBusinessText = computed(() =>
  stripSectionTitle(aiAnalysis.result?.businessSummary, '业务解读')
)
const analysisTechnicalText = computed(() =>
  stripSectionTitle(aiAnalysis.result?.technicalSummary, '技术检查')
)

const snippetPreview = (content) => {
  if (!content) return ''
  return content.length <= 320 ? content : `${content.slice(0, 320)}...`
}

const findingSeverityClass = (severity) => {
  const value = (severity || '').toUpperCase()
  if (value === 'HIGH' || value === 'ERROR') return 'severity-high'
  if (value === 'MEDIUM' || value === 'WARN' || value === 'WARNING') return 'severity-medium'
  if (value === 'LOW' || value === 'INFO') return 'severity-low'
  return 'severity-default'
}

const runAiAnalysis = async (forceRefresh = false) => {
  aiAnalysis.visible = true
  aiAnalysis.loading = true
  aiAnalysis.error = ''
  try {
    const result = await analyzeFlowtranTransaction(props.transaction.id, {
      sessionId: aiSessionId.value,
      focus: analysisFocus.value,
      mode: 'FULL',
      includeCode: true,
      forceRefresh,
      maxCodeSnippets: 6,
      selectedPath: analysisSelectedPath.value,
    })
    aiAnalysis.result = result
    aiAnalysis.requestKey = currentAnalysisKey.value
  } catch (error) {
    aiAnalysis.error = error?.message || 'AI 解读失败'
  } finally {
    aiAnalysis.loading = false
  }
}

const toggleAiPanel = async () => {
  if (aiAnalysis.visible) {
    aiAnalysis.visible = false
    return
  }
  if (!aiAnalysis.result || isAnalysisStale.value) {
    await runAiAnalysis(false)
    return
  }
  aiAnalysis.visible = true
}

// 构件层内部调用箭头（bizComp → techComp）
const recalcCompCallArrows = async () => {
  await nextTick()
  await nextTick() // 等待构件子框高度变化完全稳定
  const el = compLayerRef.value
  if (!el) return
  const c2c = relations.value.componentToComponent || {}
  if (!Object.keys(c2c).length) { compCallArrows.value = []; return }
  const s = scale.value
  const containerRect = el.getBoundingClientRect()
  compCallSvgSize.w = el.scrollWidth
  compCallSvgSize.h = el.scrollHeight
  const leftBox  = el.querySelector('.comp-biz-layer')
  const rightBox = el.querySelector('.comp-tech-layer')
  if (!leftBox || !rightBox) { compCallArrows.value = []; return }
  const lr = leftBox.getBoundingClientRect()
  const rr = rightBox.getBoundingClientRect()
  const x1 = (lr.right - containerRect.left) / s
  const y1 = (lr.top + lr.height / 2 - containerRect.top) / s
  const x2 = (rr.left  - containerRect.left) / s
  const y2 = (rr.top + rr.height / 2 - containerRect.top) / s
  const cx = (x2 - x1) * 0.5
  compCallArrows.value = [{ d: `M ${x1} ${y1} C ${x1+cx} ${y1} ${x2-cx} ${y2} ${x2} ${y2}` }]
}

// ---- SVG 曲线计算 ----
const recalcCurves = async () => {
  await nextTick()
  const el = chainContentEl.value
  if (!el) return
  const s = scale.value
  const canvasRect = el.getBoundingClientRect()
  svgSize.w = el.scrollWidth
  svgSize.h = el.scrollHeight
  const curves = []
  for (let i = 0; i < LAYER_ORDER.length - 1; i++) {
    const leftLayer  = el.querySelector(`[data-layer="${LAYER_ORDER[i]}"]`)
    const rightLayer = el.querySelector(`[data-layer="${LAYER_ORDER[i+1]}"]`)
    if (!leftLayer || !rightLayer) continue
    const lr = leftLayer.getBoundingClientRect()
    const rr = rightLayer.getBoundingClientRect()
    const x1 = (lr.right - canvasRect.left) / s
    const y1 = (lr.top + lr.height / 2 - canvasRect.top) / s
    const x2 = (rr.left  - canvasRect.left) / s
    const y2 = (rr.top + rr.height / 2 - canvasRect.top) / s
    const cx = (x2 - x1) * 0.5
    curves.push({ d: `M ${x1} ${y1} C ${x1+cx} ${y1} ${x2-cx} ${y2} ${x2} ${y2}` })
  }
  svgCurves.value = curves
}

const recalcServiceCallArrows = async () => {
  await nextTick()
  const el = serviceLayerRef.value
  if (!el || !hasSvcToSvc.value) { serviceCallArrows.value = []; return }
  const s = scale.value
  const containerRect = el.getBoundingClientRect()
  serviceCallSvgSize.w = el.scrollWidth
  serviceCallSvgSize.h = el.scrollHeight
  const leftBox  = el.querySelector('.pbs-inner-layer')
  const rightBox = el.querySelector('.pcs-inner-layer')
  if (!leftBox || !rightBox) { serviceCallArrows.value = []; return }
  const lr = leftBox.getBoundingClientRect()
  const rr = rightBox.getBoundingClientRect()
  const x1 = (lr.right - containerRect.left) / s
  const y1 = (lr.top + lr.height / 2 - containerRect.top) / s
  const x2 = (rr.left  - containerRect.left) / s
  const y2 = (rr.top + rr.height / 2 - containerRect.top) / s
  const cx = (x2 - x1) * 0.5
  serviceCallArrows.value = [{ d: `M ${x1} ${y1} C ${x1+cx} ${y1} ${x2-cx} ${y2} ${x2} ${y2}` }]
}

const recalcAll = () => {
  recalcCurves()
  recalcServiceCallArrows()
  recalcCompCallArrows()
  updateCanvasHeight()
}

// 自动适应宽度（内容超出才缩小，否则保持 100%）
const fitToWidth = async () => {
  await nextTick()
  await nextTick()
  const canvas  = chainCanvasEl.value
  const content = chainContentEl.value
  if (!canvas || !content) return
  const canvasW  = canvas.clientWidth - 40
  const contentW = content.scrollWidth
  if (contentW <= 0) return
  const fit = Math.min(1, Math.max(0.3, canvasW / contentW))
  scale.value      = parseFloat(fit.toFixed(2))
  translateX.value = 0
  translateY.value = 0
  await updateCanvasHeight()
}

// 初始化展开：适应宽度 + 绘制连线
const initExpanded = async () => {
  await fitToWidth()
  recalcAll()
}

// defaultExpanded=true 时 isExpanded 初始就是 true，watch 不会触发，需 onMounted 处理
onMounted(() => { if (isExpanded.value) initExpanded() })

// 后续手动展开时触发
watch(isExpanded, (v) => { if (v) initExpanded() })
watch(() => props.transaction.id, () => {
  aiSessionId.value = createAnalysisSessionId()
  Object.assign(aiAnalysis, {
    visible: false,
    loading: false,
    error: '',
    result: null,
    requestKey: '',
  })
})
watch(collapsedLayers, recalcAll, { deep: true })
watch(scale, recalcAll)
watch([translateX, translateY], recalcAll)

const toggleExpand = () => { isExpanded.value = !isExpanded.value }
const toggleLayer  = (layer) => { collapsedLayers[layer] = !collapsedLayers[layer] }
const zoomIn  = () => { scale.value = Math.min(scale.value + 0.1, 2);  updateCanvasHeight() }
const zoomOut = () => { scale.value = Math.max(scale.value - 0.1, 0.3); updateCanvasHeight() }

const handleWheel = (e) => {
  if (e.ctrlKey || e.metaKey) {
    scale.value = Math.max(0.3, Math.min(2, scale.value + (e.deltaY > 0 ? -0.1 : 0.1)))
    updateCanvasHeight()
  }
}

let isDragging = false, dragStartX = 0, dragStartY = 0, dragStartTX = 0, dragStartTY = 0
const startDrag = (e) => {
  if (e.button !== 0) return
  isDragging = true
  dragStartX = e.clientX; dragStartY = e.clientY
  dragStartTX = translateX.value; dragStartTY = translateY.value
}
const onDrag = (e) => {
  if (!isDragging) return
  translateX.value = dragStartTX + (e.clientX - dragStartX) / scale.value
  translateY.value = dragStartTY + (e.clientY - dragStartY) / scale.value
}
const endDrag = () => { isDragging = false }

const openFullscreen = () => {
  isFullscreen.value = true
  document.body.style.overflow = 'hidden'
  nextTick(() => { recalcCurves(); recalcServiceCallArrows() })
}
const closeFullscreen = () => {
  isFullscreen.value = false
  document.body.style.overflow = ''
  nextTick(() => { recalcCurves(); recalcServiceCallArrows() })
}

// ======== 代码查看（增强版：全类展示 + 内部方法跳转 + 多标签导航） ========
const codeBodyRef       = ref(null)
const tabsContainerRef  = ref(null)
const codeViewer = reactive({
  visible: false, node: null, nodeType: '', copied: false,
  noSource: false, loading: false,
  toast: '', filePath: '', methodName: '', locateText: '',
  fullscreen: false,
  drawerVisible: false, drawerPlacement: 'hidden', drawerShift: 0, drawerWidth: 0, drawerReservedWidth: 0,
})

// ── API 测试弹窗状态 ──
const apiTester = reactive({ visible: false, txCode: '', txName: '' })

const openApiTester = (node) => {
  apiTester.txCode  = node.code || props.transaction.id
  apiTester.txName  = node.name || props.transaction.name
  apiTester.visible = true
}

// ── Tab 标签页状态 ──
// 每个 tab：{ id, filename, kind, pkg, source, internalMethods, entryMethod, className }
const tabs = reactive({ list: [], activeIdx: 0 })

const currentTab = computed(() => tabs.list[tabs.activeIdx] ?? null)

// 当前 Tab 高亮后的代码
const currentTabCode = computed(() => {
  const tab = currentTab.value
  if (!tab?.source) return ''
  // internalMethods 为 null 时（外部文件加载、外部类等）自动从源码提取，确保方法可导航
  const methods = tab.internalMethods ?? extractMethodNames(tab.source)
  // 写回 tab，避免后续 buildCallSitesMap 再次得到 null
  if (tab.internalMethods == null) tab.internalMethods = methods
  return highlightJavaWithLinks(tab.source, methods, tab.entryMethod || null)
})

// ── 反向跳转弹窗状态 ──
const callSitesPopup = reactive({
  visible: false,
  methodName: '',
  sites: [],   // [{ line: Number, preview: String }]
  x: 0,
  y: 0
})

// 构建调用位置索引：source 每一行中哪些行调用了 internalMethods 中的方法
const buildCallSitesMap = (source, internalMethods) => {
  if (!internalMethods || internalMethods.length === 0) return {}
  const map = {}
  for (const m of internalMethods) map[m] = []
  source.split('\n').forEach((lineText, lineIdx) => {
    const lineNum = lineIdx + 1
    for (const method of internalMethods) {
      // 定义行特征：类型关键字/词 + 空格 + 方法名 + (
      const defRe = new RegExp(
        `(?:void|boolean|int|long|double|float|char|byte|short|String|[A-Z]\\w*)\\s+${method}\\s*\\(`
      )
      if (defRe.test(lineText)) continue // 定义行，不算调用
      // 调用行：非单词字符边界 + 方法名 + (
      const callRe = new RegExp(`(?<![\\w$])${method}\\s*\\(`)
      if (callRe.test(lineText)) {
        const preview = lineText.trim().slice(0, 80) + (lineText.trim().length > 80 ? '…' : '')
        map[method].push({ line: lineNum, preview })
      }
    }
  })
  return map
}

// ── 精确 mock 数据注册表（按节点码索引） ──
// ──────────────────────────────────────────────────────────────
// 外部类导航注册表（跨文件跳转 mock 数据，共 5 层）
// 当代码中出现这些 PascalCase 类名时，允许点击在右侧面板展示
// ──────────────────────────────────────────────────────────────
const EXTERNAL_REGISTRY = {
  BasePbcbComponent: {
    filename: 'BasePbcbComponent.java',
    kind: 'abstract class',
    pkg: 'com.spdb.pbcb.core',
    source:
`package com.spdb.pbcb.core;

import com.spdb.pbcb.param.BizParam;
import com.spdb.pbcb.result.BizResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 构件基类 —— 所有业务构件的父类
 * 提供生命周期管理、日志、异常处理等公共能力
 * 继承自 AbstractBizComponent，实现 BizComponent 接口
 */
public abstract class BasePbcbComponent extends AbstractBizComponent
        implements BizComponent {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    /**
     * 核心执行方法（模板方法模式）
     * 子类必须实现具体业务逻辑
     */
    @Override
    public abstract BizResult execute(BizParam param);

    /**
     * 参数预处理钩子（子类可覆盖）
     */
    protected BizParam preProcess(BizParam param) {
        log.debug("[BasePbcb] preProcess - {}", param.getClass().getSimpleName());
        validateParam(param);
        return param;
    }

    /**
     * 后置处理钩子（子类可覆盖）
     */
    protected void postProcess(BizParam param, Object result) {
        log.debug("[BasePbcb] postProcess - result={}", result);
        recordMetrics(param, result);
    }

    /**
     * 参数合法性校验
     */
    private void validateParam(BizParam param) {
        if (param == null) {
            throw new IllegalArgumentException("BizParam cannot be null");
        }
        if (param.getTxCode() == null || param.getTxCode().isEmpty()) {
            throw new IllegalArgumentException("txCode cannot be empty");
        }
    }

    /**
     * 埋点/指标记录（接入 Micrometer 指标体系）
     */
    private void recordMetrics(BizParam param, Object result) {
        MetricsHolder.record(param.getTxCode(), result);
    }
}`
  },

  BizResult: {
    filename: 'BizResult.java',
    kind: 'class',
    pkg: 'com.spdb.pbcb.result',
    source:
`package com.spdb.pbcb.result;

/**
 * 业务统一返回结果封装
 * 遵循 PBCB 平台返回规范 v2.3
 *
 * 泛型参数 T 表示业务数据类型
 */
public class BizResult<T> {

    /** 是否成功 */
    private boolean success;

    /** 业务错误码（成功时为 "000000"） */
    private String code;

    /** 提示信息 */
    private String message;

    /** 业务数据载体 */
    private T data;

    private BizResult() {}

    /**
     * 构造成功结果
     * @param data 业务数据
     */
    public static <T> BizResult<T> success(T data) {
        BizResult<T> r = new BizResult<>();
        r.success = true;
        r.code    = "000000";
        r.message = "success";
        r.data    = data;
        return r;
    }

    /**
     * 构造失败结果
     * @param code    错误码
     * @param message 错误描述
     */
    public static <T> BizResult<T> fail(String code, String message) {
        BizResult<T> r = new BizResult<>();
        r.success = false;
        r.code    = code;
        r.message = message;
        return r;
    }

    public boolean isSuccess()  { return success; }
    public String  getCode()    { return code; }
    public String  getMessage() { return message; }
    public T       getData()    { return data; }

    @Override
    public String toString() {
        return "BizResult{success=" + success
            + ", code=" + code + ", msg=" + message + "}";
    }
}`
  },

  BizParam: {
    filename: 'BizParam.java',
    kind: 'abstract class',
    pkg: 'com.spdb.pbcb.param',
    source:
`package com.spdb.pbcb.param;

import java.io.Serializable;

/**
 * 业务参数基类
 * 所有构件入参均需继承此类
 * 提供交易上下文公共字段
 */
public abstract class BizParam implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 交易码 */
    private String txCode;

    /** 渠道号 */
    private String channelId;

    /** 操作员号 */
    private String operatorId;

    /** 请求流水号（全局唯一，由网关注入） */
    private String traceId;

    /** 操作机构号 */
    private String orgCode;

    /** 请求时间戳 */
    private long   timestamp;

    public String getTxCode()     { return txCode; }
    public String getChannelId()  { return channelId; }
    public String getOperatorId() { return operatorId; }
    public String getTraceId()    { return traceId; }
    public String getOrgCode()    { return orgCode; }
    public long   getTimestamp()  { return timestamp; }

    public void setTxCode(String txCode)          { this.txCode = txCode; }
    public void setChannelId(String channelId)     { this.channelId = channelId; }
    public void setOperatorId(String operatorId)   { this.operatorId = operatorId; }
    public void setTraceId(String traceId)         { this.traceId = traceId; }
    public void setOrgCode(String orgCode)         { this.orgCode = orgCode; }
    public void setTimestamp(long timestamp)       { this.timestamp = timestamp; }

    @Override
    public String toString() {
        return "BizParam{txCode=" + txCode
            + ", channelId=" + channelId
            + ", traceId=" + traceId + "}";
    }
}`
  },

  SysUtil: {
    filename: 'SysUtil.java',
    kind: 'class',
    pkg: 'com.spdb.pbcb.util',
    source:
`package com.spdb.pbcb.util;

import com.spdb.pbcb.core.BizComponent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * 系统工具类 —— 构件实例获取入口
 * 通过 Spring ApplicationContext 获取业务构件单例
 *
 * 标准用法：
 *   XxxComponent comp = SysUtil.getInstance(XxxComponent.class);
 *   BizResult result  = comp.execute(param);
 */
@Component
public class SysUtil implements ApplicationContextAware {

    private static ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext context) {
        SysUtil.ctx = context;
    }

    /**
     * 获取构件实例（核心方法）
     * @param clazz 构件 Class 对象
     * @return Spring 管理的构件单例
     */
    public static <T extends BizComponent> T getInstance(Class<T> clazz) {
        if (ctx == null) {
            throw new IllegalStateException("Spring context not initialized");
        }
        return ctx.getBean(clazz);
    }

    /**
     * 按 Bean 名称获取实例
     */
    public static Object getBean(String beanName) {
        return ctx.getBean(beanName);
    }

    /**
     * 判断 Bean 是否存在于容器中
     */
    public static boolean containsBean(String beanName) {
        return ctx != null && ctx.containsBean(beanName);
    }

    /**
     * 发布应用事件
     */
    public static void publishEvent(Object event) {
        if (ctx != null) ctx.publishEvent(event);
    }
}`
  },

  AuthCheckResult: {
    filename: 'AuthCheckResult.java',
    kind: 'class',
    pkg: 'com.spdb.pbcb.auth',
    source:
`package com.spdb.pbcb.auth;

/**
 * 认证校验结果
 * 封装认证流程各阶段的检查结论
 * 支持链式调用模式
 */
public class AuthCheckResult {

    private boolean passed;
    private String  errorCode;
    private String  errorMsg;
    private Object  data;

    private AuthCheckResult() {}

    /**
     * 构造通过结果
     * @param data 认证数据（如用户信息、token 等）
     */
    public static AuthCheckResult pass(Object data) {
        AuthCheckResult r = new AuthCheckResult();
        r.passed    = true;
        r.errorCode = "000000";
        r.data      = data;
        return r;
    }

    /**
     * 构造失败结果
     * @param code 错误码（AUTH_001 ~ AUTH_099）
     * @param msg  错误描述
     */
    public static AuthCheckResult fail(String code, String msg) {
        AuthCheckResult r = new AuthCheckResult();
        r.passed    = false;
        r.errorCode = code;
        r.errorMsg  = msg;
        return r;
    }

    public boolean isPassed()   { return passed; }
    public String  getCode()    { return errorCode; }
    public String  getMessage() { return errorMsg; }
    public Object  getData()    { return data; }

    /**
     * 转换为 BizResult（用于向上层返回）
     */
    public BizResult toBizResult() {
        return passed
            ? BizResult.success(data)
            : BizResult.fail(errorCode, errorMsg);
    }

    @Override
    public String toString() {
        return "AuthCheckResult{passed=" + passed
            + ", code=" + errorCode + ", msg=" + errorMsg + "}";
    }
}`
  },

  BizComponent: {
    filename: 'BizComponent.java',
    kind: 'interface',
    pkg: 'com.spdb.pbcb.core',
    source:
`package com.spdb.pbcb.core;

import com.spdb.pbcb.param.BizParam;
import com.spdb.pbcb.result.BizResult;

/**
 * 构件顶层接口 —— PBCB 平台所有业务构件的公共契约
 * 规范构件生命周期与核心执行语义
 */
public interface BizComponent {

    /**
     * 执行业务逻辑（核心方法）
     * @param param 入参，继承自 BizParam
     * @return 统一结果封装 BizResult
     */
    BizResult execute(BizParam param);

    /**
     * 获取构件唯一标识码
     * 默认从 @PbcbComponent(code = "xxx") 注解中读取
     */
    default String getComponentCode() {
        PbcbComponent ann = getClass().getAnnotation(PbcbComponent.class);
        return ann != null ? ann.code() : getClass().getSimpleName();
    }

    /**
     * 构件是否支持异步执行（默认不支持）
     */
    default boolean isAsyncSupported() {
        return false;
    }

    /**
     * 构件健康检查（供平台监控使用）
     */
    default HealthStatus healthCheck() {
        return HealthStatus.UP;
    }
}`
  },

  TxStatus: {
    filename: 'TxStatus.java',
    kind: 'enum',
    pkg: 'com.spdb.pbcb.enums',
    source:
`package com.spdb.pbcb.enums;

/**
 * 交易状态枚举
 * 描述联机交易在各阶段的处理状态
 */
public enum TxStatus {

    /** 待处理（刚进入队列，未分配处理节点） */
    PENDING("00", "待处理"),

    /** 处理中（已分配，正在执行业务逻辑） */
    PROCESSING("01", "处理中"),

    /** 成功（业务处理完成，结果正常） */
    SUCCESS("02", "成功"),

    /** 失败（业务处理完成，结果异常） */
    FAILED("03", "失败"),

    /** 超时（处理超出最大等待时间） */
    TIMEOUT("04", "超时"),

    /** 已回滚（触发补偿机制，事务已撤销） */
    ROLLED_BACK("05", "已回滚"),

    /** 部分成功（批量交易中部分记录成功） */
    PARTIAL_SUCCESS("06", "部分成功");

    private final String code;
    private final String desc;

    TxStatus(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() { return code; }
    public String getDesc() { return desc; }

    /**
     * 根据状态码查找枚举值
     * @param code 状态码（如 "02"）
     */
    public static TxStatus ofCode(String code) {
        for (TxStatus s : values()) {
            if (s.code.equals(code)) return s;
        }
        throw new IllegalArgumentException("Unknown TxStatus code: " + code);
    }

    /**
     * 是否为终态（成功、失败、超时、回滚均为终态）
     */
    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED
            || this == TIMEOUT || this == ROLLED_BACK;
    }

    @Override
    public String toString() {
        return code + ":" + desc;
    }
}`
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 节点 → 真实文件映射（key = 节点 code）
// filePath : 服务器上的绝对路径
// methodName : 要定位的入口方法名（可选）
// ─────────────────────────────────────────────────────────────────────────────
/**
 * 节点 → 源码映射表
 * 格式：节点编码 → { className: 类简单名, methodName: 入口方法名 }
 * 后端全局索引会自动找到类所在的文件，无需写死绝对路径。
 */
const NODE_FILE_REGISTRY = {

  // ═══════════════════════════════════════════════════════════════════════════
  // 贷款领域 - TD0101 贷款申请提交
  // ═══════════════════════════════════════════════════════════════════════════

  // 服务层
  'SVC_LOAN_001': {
    fqn:        'cn.sunline.ltts.busi.dept.aps.serviceimpl.apsimpl.managedeptcptl.IoDpPrcAcctDeptSvcApsImpl',
    className:  'IoDpPrcAcctDeptSvcApsImpl',
    methodName: 'prcAcctDept',
  },
  'SVC_LIMIT_001': {
    fqn:        'cn.sunline.ltts.busi.dept.aps.serviceimpl.apsimpl.acclimit.IoDpAccLimitSetApsImpl',
    className:  'IoDpAccLimitSetApsImpl',
    methodName: 'prcAccLimitRgst',
  },
  'SVC_RISK_001': {
    fqn:        'cn.sunline.ltts.busi.dept.aps.serviceimpl.apsimpl.acclimit.IoDpQryAccLimitApsImpl',
    className:  'IoDpQryAccLimitApsImpl',
    methodName: 'qryAccLimit',
  },

  // 构件层（业务/产品构件）
  'COMP_AUTH_BIZ_001': {
    fqn:        'cn.sunline.ltts.busi.dept.aps.serviceimpl.apsimpl.dpbtchbusi.IoDpAcctFrzCtrlDetlApsImpl',
    className:  'IoDpAcctFrzCtrlDetlApsImpl',
    methodName: 'prcAcctFrzCtrlDetl',
  },
  'COMP_QUOTA_PROD_001': {
    fqn:        'cn.sunline.ltts.busi.dept.aps.serviceimpl.apsimpl.managedeptctrct.IoDpOpenDeptAcctApsImpl',
    className:  'IoDpOpenDeptAcctApsImpl',
    methodName: 'openDeptAcct',
  },
  'COMP_RISK_BASE_001': {
    fqn:        'cn.sunline.ltts.busi.dept.bcs.serviceimpl.dpbtchbusi.DpPrcCalIntLayerBcsImpl',
    className:  'DpPrcCalIntLayerBcsImpl',
    methodName: 'prcCalIntLayer',
  },

  // 构件层（公共/技术构件）
  'COMP_CALC_002': {
    fqn:        'cn.sunline.ltts.busi.dept.bcs.serviceimpl.dpacclimit.DpQryAccLimitBcsImpl',
    className:  'DpQryAccLimitBcsImpl',
    methodName: 'qryAccLimit',
  },
  'COMP_RULE_002': {
    fqn:        'cn.sunline.ltts.busi.dept.bcs.serviceimpl.dpacclimit.DpSetAccLimitBcsImpl',
    className:  'DpSetAccLimitBcsImpl',
    methodName: 'prcAccLimitMntn',
  },
  'COMP_SCORE_001': {
    fqn:        'cn.sunline.ltts.busi.dept.bcs.publicBusiness.interest.base.DpInterestBaseImpl',
    className:  'DpInterestBaseImpl',
    methodName: 'calculateInterest',
  },

  // ═══════════════════════════════════════════════════════════════════════════
  // 贷款领域 - TD0102 贷款额度查询
  // ═══════════════════════════════════════════════════════════════════════════

  // 服务层
  'SVC_QUOTA_001': {
    fqn:        'cn.sunline.ltts.busi.dept.aps.serviceimpl.apsimpl.acclimit.IoDpQryAccLimitApsImpl',
    className:  'IoDpQryAccLimitApsImpl',
    methodName: 'qryAccLimit',
  },
  'SVC_LEVEL_001': {
    fqn:        'cn.sunline.ltts.busi.dept.aps.serviceimpl.apsimpl.acclimit.IoDpAccLimitSetApsImpl',
    className:  'IoDpAccLimitSetApsImpl',
    methodName: 'prcAccLimitMntn',
  },

  // 构件层
  'COMP_CALC_001': {
    fqn:        'cn.sunline.ltts.busi.dept.bcs.serviceimpl.dpmaintaindeptinfo.DpCalIntByProdBcsImpl',
    className:  'DpCalIntByProdBcsImpl',
    methodName: 'prcCalIntByProd',
  },

  // ═══════════════════════════════════════════════════════════════════════════
  // 贷款领域 - TD0103 还款计划生成
  // ═══════════════════════════════════════════════════════════════════════════

  // 服务层
  'SVC_REPAY_001': {
    fqn:        'cn.sunline.ltts.busi.dept.aps.serviceimpl.apsimpl.maintaindeptinfo.IoDpCalIntByAccApsImpl',
    className:  'IoDpCalIntByAccApsImpl',
    methodName: 'prcCalIntByAcc',
  },
  'SVC_CALC_001': {
    fqn:        'cn.sunline.ltts.busi.dept.aps.serviceimpl.apsimpl.dpbtchbusi.IoDpPrcAcrlPstgApsImpl',
    className:  'IoDpPrcAcrlPstgApsImpl',
    methodName: 'prcAcrlPstg',
  },
  'SVC_RPT_001': {
    fqn:        'cn.sunline.ltts.busi.dept.aps.serviceimpl.apsimpl.dpbtchbusi.IoDpPrcCalIntLayerApsImpl',
    className:  'IoDpPrcCalIntLayerApsImpl',
    methodName: 'prcCalIntLayer',
  },

  // 构件层
  'COMP_DATE_001': {
    fqn:        'cn.sunline.ltts.busi.dept.bcs.serviceimpl.dpmaintaindeptinfo.DpCalIntByAccBcsImpl',
    className:  'DpCalIntByAccBcsImpl',
    methodName: 'prcCalIntByAcc',
  },
  'COMP_FMT_001': {
    fqn:        'cn.sunline.ltts.busi.dept.bcs.serviceimpl.dpbtchbusi.DpPrcAcrlPstgBcsImpl',
    className:  'DpPrcAcrlPstgBcsImpl',
    methodName: 'prcAcrlPstg',
  },
}

// 从后端 /api/source 加载真实 Java 文件
const fetchSourceFromServer = async (filePath, methodName) => {
  const params = new URLSearchParams({ filePath })
  if (methodName) params.set('methodName', methodName)
  const res = await fetch(`/api/source?${params}`)
  if (!res.ok) return null
  return res.json()
}

const MOCK_REGISTRY = {
  'COMP_AUTH_BIZ_001': {
    filename: 'AuthBizPbcb.java',
    entryMethod: 'execute',
    internalMethods: null,  // 由 getMockCodeData 自动从 source 提取
    source:
`package com.yourbank.auth.component;

import com.yourbank.common.util.SysUtil;
import com.yourbank.common.util.HashUtil;
import com.yourbank.common.base.BasePbcbComponent;
import java.time.LocalDateTime;

/**
 * 认证业务组件
 * 组件码  : COMP_AUTH_BIZ_001
 * 调用方式 : SysUtil.getInstance(AuthBizPbcb.class)
 */
@PbcbComponent(code = "COMP_AUTH_BIZ_001")
public class AuthBizPbcb extends BasePbcbComponent {

    // ─────────────── [入口方法] ───────────────
    // 由上游服务通过 SysUtil.getInstance(AuthBizPbcb.class) 触发调用

    @Override
    public BizResult execute(BizParam param) {
        // 1. 参数标准化
        AuthBizParam authParam = normalizeParam(param);

        // 2. 执行核心认证校验
        AuthCheckResult checkResult = performAuthCheck(authParam);
        if (!checkResult.isPassed()) {
            return BizResult.fail(checkResult.getCode(), checkResult.getMessage());
        }

        // 3. 认证后置处理（记录日志）
        postProcess(authParam, checkResult);

        return BizResult.success(checkResult.getData());
    }

    // ─────────────── 私有方法 ───────────────

    /**
     * 参数标准化处理
     */
    private AuthBizParam normalizeParam(BizParam param) {
        AuthBizParam authParam = new AuthBizParam();
        authParam.setCustNo(param.getCustNo());
        authParam.setChannel(param.getChannel() != null ? param.getChannel() : "WEB");
        authParam.setIpAddress(param.getExtInfo("ipAddress"));
        authParam.setTimestamp(LocalDateTime.now());
        return authParam;
    }

    /**
     * 执行核心认证校验流程
     */
    private AuthCheckResult performAuthCheck(AuthBizParam param) {
        // 校验用户是否存在
        boolean exists = checkUserExists(param.getCustNo());
        if (!exists) {
            return AuthCheckResult.fail("AUTH_001", "用户不存在");
        }

        // 校验账户状态
        String status = getAccountStatus(param.getCustNo());
        if (!"ACTIVE".equals(status)) {
            return AuthCheckResult.fail("AUTH_002", "账户状态异常: " + status);
        }

        // 渠道风控校验
        boolean riskOk = checkChannelRisk(param.getChannel(), param.getIpAddress());
        if (!riskOk) {
            return AuthCheckResult.fail("AUTH_003", "渠道风控拦截");
        }

        return AuthCheckResult.success(buildAuthData(param));
    }

    /**
     * 校验用户是否存在（调用公共构件）
     */
    private boolean checkUserExists(String custNo) {
        AuthQueryPbcc comp = SysUtil.getInstance(AuthQueryPbcc.class);
        return comp.existsByCustNo(custNo);
    }

    /**
     * 获取账户当前状态（调用公共构件）
     */
    private String getAccountStatus(String custNo) {
        AuthQueryPbcc comp = SysUtil.getInstance(AuthQueryPbcc.class);
        return comp.getAccountStatus(custNo);
    }

    /**
     * 渠道风控校验（调用风控构件）
     */
    private boolean checkChannelRisk(String channel, String ipAddress) {
        RiskCheckPbcc comp = SysUtil.getInstance(RiskCheckPbcc.class);
        return comp.checkRisk(new RiskCheckParam(channel, ipAddress)).isPassed();
    }

    /**
     * 构建认证成功数据对象
     */
    private AuthData buildAuthData(AuthBizParam param) {
        return AuthData.builder()
            .custNo(param.getCustNo())
            .channel(param.getChannel())
            .authTime(param.getTimestamp())
            .token(generateToken(param.getCustNo()))
            .build();
    }

    /**
     * 生成认证令牌（SHA-256 哈希）
     */
    private String generateToken(String custNo) {
        return HashUtil.sha256(custNo + ":" + System.currentTimeMillis());
    }

    /**
     * 认证后置处理：异步写入认证流水日志
     */
    private void postProcess(AuthBizParam param, AuthCheckResult result) {
        AuthLogPbcc comp = SysUtil.getInstance(AuthLogPbcc.class);
        comp.saveLog(AuthLogRecord.builder()
            .custNo(param.getCustNo())
            .channel(param.getChannel())
            .success(result.isPassed())
            .timestamp(param.getTimestamp())
            .build());
    }
}`
  }
}
// COMP_AUTH_001 与 COMP_AUTH_BIZ_001 共用同一套完整 mock 类（数据库中实际码）
MOCK_REGISTRY['COMP_AUTH_001'] = MOCK_REGISTRY['COMP_AUTH_BIZ_001']

// ── 通用 mock 代码生成（注册表未命中时降级使用） ──
const generateGenericCode = (node, nodeType) => {
  const code = node.code || ''
  const name = node.name || ''
  const prefix = node.prefix || ''
  const cls = code.replace(/[^a-zA-Z0-9]/g, '') || 'Node'

  if (nodeType === 'orchestration') {
    return `/**
 * 交易编排层入口
 * 交易码: ${code}  名称: ${name}
 */
@TxEntry(txCode = "${code}", name = "${name}")
public class ${cls}Tx extends BaseTxOrchestration {

    /**
     * 交易主入口，由框架统一调度
     */
    @Override
    public TxResult execute(TxContext ctx) {
        TxRequest request = ctx.getRequest(TxRequest.class);

        // 参数非空校验
        ValidationUtil.checkNotNull(request.getCustNo(), "客户号不能为空");
        ValidationUtil.checkNotNull(request.getAmount(),  "交易金额不能为空");

        // 获取流程编排服务实例
        ${cls}Pbs service = SysUtil.getInstance(${cls}Pbs.class);

        // 执行主业务流程
        ServiceResult result = service.process(request);

        // 记录交易流水
        TxJournalUtil.record(ctx, result);

        return TxResult.ok(result.getData());
    }
}`
  }

  if (prefix === 'pbs') {
    return `/**
 * 流程编排服务
 * 服务码: ${code}  名称: ${name}
 */
@PbsService(code = "${code}")
public class ${cls}Pbs implements I${cls}Pbs {

    @Override
    public ServiceResult process(TxRequest request) {
        // Step1: 业务规则前置校验
        validateBusinessRules(request);

        // Step2: 通过 SysUtil 获取业务构件实例
        ${cls}Pbcb bizComp = SysUtil.getInstance(${cls}Pbcb.class);
        BizResult bizResult = bizComp.execute(request.toBizParam());

        // Step3: 获取公共技术构件进行后处理
        CommonValidPbcc validComp = SysUtil.getInstance(CommonValidPbcc.class);
        validComp.postCheck(bizResult);

        return ServiceResult.success(bizResult);
    }

    private void validateBusinessRules(TxRequest request) {
        if (request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("E001", "交易金额必须大于零");
        }
        if (StringUtil.isBlank(request.getCustNo())) {
            throw new BizException("E002", "客户号不能为空");
        }
    }
}`
  }

  if (prefix === 'pcs') {
    return `/**
 * 编码调用服务（由 SysUtil.getInstance 调用）
 * 服务码: ${code}  名称: ${name}
 */
@PcsService(code = "${code}")
public class ${cls}Pcs implements I${cls}Pcs {

    @Override
    public PcsResult invoke(PcsParam param) {
        log.info("[${code}] invoke start, custNo={}", param.getCustNo());

        // 获取公共构件实例处理核心逻辑
        ${cls}Pbcc techComp = SysUtil.getInstance(${cls}Pbcc.class);
        DataResult data = techComp.queryAndProcess(param.toQueryParam());

        PcsResult result = PcsResult.builder()
            .code("0000")
            .message("success")
            .data(data)
            .build();

        log.info("[${code}] invoke end, resultCode={}", result.getCode());
        return result;
    }
}`
  }

  if (prefix === 'pbcb' || prefix === 'pbcp') {
    const typeName = prefix === 'pbcb' ? '业务构件' : '产品构件'
    return `/**
 * ${typeName}
 * 构件码: ${code}  名称: ${name}
 */
@${prefix.toUpperCase()}Component(code = "${code}")
public class ${cls}${prefix === 'pbcb' ? 'Pbcb' : 'Pbcp'} extends BaseComponent {

    /**
     * 执行业务逻辑，内部通过 SysUtil 调用技术构件
     */
    public BizResult execute(BizParam param) {
        // 参数标准化处理
        param.setCurrency(param.getCurrency() != null ? param.getCurrency() : "CNY");
        param.setDate(param.getDate() != null ? param.getDate() : LocalDate.now());

        // 获取公共技术构件
        ${cls}Pbcc techComp = SysUtil.getInstance(${cls}Pbcc.class);
        DataResult data = techComp.queryAndProcess(param.toQueryParam());

        // 核心业务规则计算
        String status = data.getTotal() > 0 ? "APPROVED" : "REJECTED";

        return BizResult.builder()
            .status(status)
            .amount(param.getAmount())
            .records(data.getRecords())
            .build();
    }
}`
  }

  if (prefix === 'pbcc' || prefix === 'pbct') {
    const typeName = prefix === 'pbcc' ? '公共构件' : '技术构件'
    return `/**
 * ${typeName}
 * 构件码: ${code}  名称: ${name}
 */
@${prefix.toUpperCase()}Component(code = "${code}")
public class ${cls}${prefix === 'pbcc' ? 'Pbcc' : 'Pbct'} extends BaseComponent {

    @Autowired
    private ${cls}Mapper mapper;

    @Autowired
    private CacheManager cacheManager;

    /**
     * 查询并处理数据（带缓存）
     */
    public DataResult queryAndProcess(QueryParam param) {
        // 优先读缓存
        String cacheKey = String.format("${code}:%s:%s",
            param.getKey(), param.getStartDate());
        DataResult cached = cacheManager.get(cacheKey, DataResult.class);
        if (cached != null) {
            return cached;
        }

        // 查询数据库
        List<${cls}Entity> records = mapper.selectByCondition(
            param.getKey(), param.getStartDate(), param.getEndDate()
        );

        DataResult result = DataResult.builder()
            .total(records.size())
            .records(records)
            .build();

        // 写入缓存（TTL 5分钟）
        cacheManager.set(cacheKey, result, 300);
        return result;
    }
}`
  }

  return `// ${code} - ${name}\npublic class ${cls} {\n    // 暂无代码片段\n}`
}

// ── 从源码自动提取所有方法定义名（无需手动维护列表） ──
const extractMethodNames = (source) => {
  const methods = []
  // 匹配模式：(private|public|protected) [static|final|...] returnType methodName(
  const methodDefRe = /^\s+(?:(?:private|public|protected|static|final|synchronized|abstract)\s+)+(\w[\w<>[\],\s]*\s+)?(\w+)\s*\(/gm
  let m
  while ((m = methodDefRe.exec(source)) !== null) {
    const name = m[2]
    // 排除语言关键字和大写构造函数
    if (name && /^[a-z]/.test(name) &&
        !['if','for','while','switch','catch','try','new'].includes(name)) {
      if (!methods.includes(name)) methods.push(name)
    }
  }
  return methods
}

// ── 获取节点代码数据（注册表优先，降级通用生成） ──
const getMockCodeData = (node, nodeType) => {
  const key = node.code || ''
  if (MOCK_REGISTRY[key]) {
    const reg = MOCK_REGISTRY[key]
    // internalMethods 为 null 时从 source 自动提取
    const internalMethods = reg.internalMethods ?? extractMethodNames(reg.source)
    return { ...reg, internalMethods }
  }
  const p = node.prefix || ''
  const cls = key.replace(/[^a-zA-Z0-9]/g, '') || 'Node'
  const sfx = nodeType === 'orchestration' ? 'Tx'
    : p === 'pbs' ? 'Pbs' : p === 'pcs' ? 'Pcs'
    : p === 'pbcb' ? 'Pbcb' : p === 'pbcp' ? 'Pbcp'
    : p === 'pbcc' ? 'Pbcc' : p === 'pbct' ? 'Pbct' : ''
  const entryMethod = (nodeType === 'orchestration' || p === 'pbcb' || p === 'pbcp' || p === 'pbcc' || p === 'pbct')
    ? 'execute' : 'process'
  const source = generateGenericCode(node, nodeType)
  // 自动从源码中提取所有方法名，不遗漏任何私有辅助方法
  const internalMethods = extractMethodNames(source)
  return { source, entryMethod, internalMethods, filename: `${cls}${sfx}.java` }
}

// ── VSCode Dark+ 风格 Java 高亮分词器（含行号 + 内部方法跳转） ──
const highlightJavaWithLinks = (source, internalMethodsArr, entryMethod) => {
  const iMethods = new Set(internalMethodsArr)

  // VSCode Dark+ 关键字（蓝色 #569CD6）
  const KEYWORDS = new Set([
    'public','private','protected','class','interface','abstract','enum',
    'extends','implements','return','new','void','static','final','native',
    'if','else','for','while','do','switch','case','break','continue','default',
    'try','catch','finally','throw','throws','this','super',
    'import','package','synchronized','transient','volatile','strictfp',
    'instanceof','assert','const','goto',
    // primitives（同样蓝色）
    'boolean','int','long','double','float','char','byte','short',
    // literals
    'null','true','false'
  ])

  const esc = s => s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')

  // ── 分词（换行单独为 nl token，块注释为 block-comment） ──
  const toks = []
  let i = 0
  while (i < source.length) {
    if (source[i] === '/' && source[i+1] === '*') {
      let e = source.indexOf('*/', i); e = e === -1 ? source.length : e + 2
      toks.push({ t:'block-comment', v: source.slice(i, e) }); i = e
    } else if (source[i] === '/' && source[i+1] === '/') {
      let e = source.indexOf('\n', i); if (e === -1) e = source.length
      toks.push({ t:'comment', v: source.slice(i, e) }); i = e
    } else if (source[i] === '"') {
      let j = i + 1
      while (j < source.length && source[j] !== '"') { if (source[j] === '\\') j++; j++ }
      toks.push({ t:'string', v: source.slice(i, j+1) }); i = j + 1
    } else if (source[i] === "'") {
      let j = i + 1
      while (j < source.length && source[j] !== "'") { if (source[j] === '\\') j++; j++ }
      toks.push({ t:'string', v: source.slice(i, j+1) }); i = j + 1
    } else if (source[i] === '@') {
      let j = i + 1; while (j < source.length && /\w/.test(source[j])) j++
      toks.push({ t:'annotation', v: source.slice(i, j) }); i = j
    } else if (/[a-zA-Z_$]/.test(source[i])) {
      let j = i + 1; while (j < source.length && /[\w$]/.test(source[j])) j++
      toks.push({ t:'word', v: source.slice(i, j) }); i = j
    } else if (/\d/.test(source[i])) {
      let j = i + 1; while (j < source.length && /[\da-fA-FxXlLdDfF_.]/.test(source[j])) j++
      toks.push({ t:'number', v: source.slice(i, j) }); i = j
    } else if (source[i] === '\n') {
      toks.push({ t:'nl', v: '\n' }); i++
    } else {
      toks.push({ t:'other', v: source[i] }); i++
    }
  }

  // ── 辅助：跳过空白/换行向前/后找 ──
  const skipWS = (idx, dir) => {
    let k = idx + dir
    while (k >= 0 && k < toks.length &&
      (toks[k].t === 'nl' || (toks[k].t === 'other' && /\s/.test(toks[k].v)))) k += dir
    return k
  }

  // ── 渲染（按行生成 HTML，含行号） ──
  const lines = []
  let curLine = []
  let lineNum = 1

  const flushLine = () => {
    lines.push(
      `<span class="code-line" data-ln="${lineNum}"><span class="ln">${lineNum}</span>` +
      `<span class="lc">${curLine.join('')}</span></span>`
    )
    lineNum++
    curLine = []
  }

  const pushToken = (html) => curLine.push(html)

  for (let idx = 0; idx < toks.length; idx++) {
    const tok = toks[idx]

    // 换行 → 结束当前行
    if (tok.t === 'nl') { flushLine(); continue }

    const ev = esc(tok.v)

    // 块注释（可能跨多行）
    if (tok.t === 'block-comment') {
      const parts = tok.v.split('\n')
      parts.forEach((part, pi) => {
        pushToken(`<span class="hl-comment">${esc(part)}</span>`)
        if (pi < parts.length - 1) flushLine()
      })
      continue
    }

    if (tok.t === 'comment')    { pushToken(`<span class="hl-comment">${ev}</span>`);    continue }
    if (tok.t === 'string')     { pushToken(`<span class="hl-string">${ev}</span>`);     continue }
    if (tok.t === 'annotation') { pushToken(`<span class="hl-annotation">${ev}</span>`); continue }
    if (tok.t === 'number')     { pushToken(`<span class="hl-number">${ev}</span>`);     continue }

    if (tok.t === 'word') {
      // 关键字
      if (KEYWORDS.has(tok.v)) { pushToken(`<span class="hl-keyword">${ev}</span>`); continue }

      // 内部方法（可双向跳转）
      if (iMethods.has(tok.v)) {
        const jj = skipWS(idx, 1)
        if (jj < toks.length && toks[jj].v === '(') {
          // 判断是定义（前一个有效 token 是 word 类型名）还是调用
          const kk = skipWS(idx, -1)
          const isDef = kk >= 0 && toks[kk].t === 'word'
          if (isDef) {
            const isEntry = tok.v === entryMethod
            // data-method-def 用于反向：点击定义 → 查看所有调用位置
            pushToken(`<span id="mdef-${tok.v}" class="hl-fn${isEntry ? ' hl-fn-entry' : ''} hl-fn-def" data-method-def="${tok.v}" title="查看 ${tok.v}() 的调用位置 (${ev})">${ev}</span>`)
          } else {
            pushToken(`<span class="hl-fn hl-internal-call" data-method="${tok.v}" title="跳转到 ${tok.v}() 定义处">${ev}</span>`)
          }
          continue
        }
      }

      // 大写开头 → 类/接口/枚举名
      if (/^[A-Z]/.test(tok.v)) {
        if (EXTERNAL_REGISTRY[tok.v]) {
          // 外部可导航类：点击在右侧面板展示
          pushToken(`<span class="hl-type hl-ext-ref" data-ext-class="${tok.v}" title="查看 ${tok.v}.java (${EXTERNAL_REGISTRY[tok.v].kind})">${ev}</span>`)
        } else {
          pushToken(`<span class="hl-type">${ev}</span>`)
        }
        continue
      }

      // 小写且紧跟 '(' → 方法调用（#DCDCAA）
      const jj = skipWS(idx, 1)
      if (jj < toks.length && toks[jj].v === '(') {
        const kk = skipWS(idx, -1)
        const isDef = kk >= 0 && toks[kk].t === 'word'
        if (isDef) {
          pushToken(`<span class="hl-fn">${ev}</span>`)
        } else {
          // 检测 obj.method( 模式 → 跨文件可导航
          const prevTok = kk >= 0 ? toks[kk] : null
          if (prevTok && prevTok.v === '.') {
            // 取 '.' 前面的对象名（可能是变量/this/super）
            const objIdx = skipWS(kk, -1)
            const objName = (objIdx >= 0 && toks[objIdx].t === 'word') ? toks[objIdx].v : ''
            pushToken(`<span class="hl-fn-call hl-cross-call" data-cross-method="${tok.v}" data-cross-obj="${objName}" title="跳转到 ${tok.v}() 定义文件">${ev}</span>`)
          } else {
            pushToken(`<span class="hl-fn-call">${ev}</span>`)
          }
        }
        continue
      }

      // 其余小写标识符 → 变量/参数（#9CDCFE）
      pushToken(`<span class="hl-var">${ev}</span>`)
      continue
    }

    pushToken(ev)
  }

  // 最后一行（无结尾换行时）
  if (curLine.length > 0) flushLine()

  return lines.join('')
}

const highlightedCode = computed(() => {
  if (!codeViewer.node) return ''
  const d = getMockCodeData(codeViewer.node, codeViewer.nodeType)
  return highlightJavaWithLinks(d.source, d.internalMethods, d.entryMethod)
})

// 公共：让某个 .code-line 元素闪烁高亮
const flashCodeLine = (el) => {
  if (!el) return
  el.classList.remove('line-flash')
  void el.offsetWidth
  el.classList.add('line-flash')
  setTimeout(() => el.classList.remove('line-flash'), 2000)
}

// 按行号精确跳转（AST symbolsMap 或反向调用都走这里）
const scrollToLine = (lineNum) => {
  if (!codeBodyRef.value || !lineNum) return
  const el = codeBodyRef.value.querySelector(`.code-line[data-ln="${lineNum}"]`)
  if (!el) return
  el.scrollIntoView({ behavior: 'smooth', block: 'center' })
  flashCodeLine(el)
}

// 滚动到指定方法定义处并高亮整行
// ① 优先用 AST symbolsMap 行号（精确）
// ② 降级到 DOM anchor #mdef-xxx（正则模式 / mock 数据）
const scrollToMethod = (methodName) => {
  if (!methodName || !codeBodyRef.value) return
  const tab = currentTab.value
  // 优先：AST 精确行号
  if (tab?.symbolsMap && tab.symbolsMap[methodName] != null) {
    scrollToLine(tab.symbolsMap[methodName])
    return
  }
  // 降级：DOM anchor（mock / regex 模式）
  const anchor = codeBodyRef.value.querySelector(`#mdef-${methodName}`)
  if (!anchor) return
  anchor.scrollIntoView({ behavior: 'smooth', block: 'center' })
  flashCodeLine(anchor.closest('.code-line') || anchor)
}

// 显示一条短暂的 toast 消息
const showToast = (msg, durationMs = 3000) => {
  codeViewer.toast = msg
  setTimeout(() => { if (codeViewer.toast === msg) codeViewer.toast = '' }, durationMs)
}

// 跨文件方法导航：obj.method() → 找到 obj 的类型 → 查找文件 → 开新 Tab
const openCrossFileMethod = async (objName, methodName) => {
  const tab = currentTab.value
  if (!tab) return

  console.log('[CrossNav] click:', objName, '.', methodName)
  console.log('[CrossNav] tab.typeMap:', tab.typeMap)
  console.log('[CrossNav] tab.filePath:', tab.filePath)

  // 从 typeMap 推断对象类型
  const typeMap  = tab.typeMap  || {}
  const filePath = tab.filePath || ''
  let className  = typeMap[objName] || ''

  // 若对象名以大写开头，本身就可能是类名（如静态方法 MyClass.method()）
  if (!className && /^[A-Z]/.test(objName)) className = objName

  if (!className) {
    showToast(`⚠ 无法推断 "${objName}" 的类型（该变量未找到字段声明）`)
    return
  }

  console.log('[CrossNav] resolved class:', className)

  // 检查是否已有该类的 Tab
  const existIdx = tabs.list.findIndex(t => t.id === `cross:${className}`)
  if (existIdx >= 0) {
    tabs.activeIdx = existIdx
    nextTick(() => scrollToMethod(methodName))
    return
  }

  // 若 EXTERNAL_REGISTRY 中有 mock 数据，优先用 mock
  if (EXTERNAL_REGISTRY[className]) {
    openExternalClass(className)
    nextTick(() => nextTick(() => scrollToMethod(methodName)))
    return
  }

  // 调用后端 /api/source/find 在项目源码树中查找真实文件
  if (!filePath) {
    showToast(`⚠ 暂无源码路径，无法定位 ${className}.java`)
    return
  }

  showToast(`🔍 正在查找 ${className}.java …`)

  try {
    const params = new URLSearchParams({ currentFilePath: filePath, className, methodName })
    console.log('[CrossNav] fetch:', `/api/source/find?${params}`)
    const res = await fetch(`/api/source/find?${params}`)
    console.log('[CrossNav] response status:', res.status)
    if (!res.ok) {
      showToast(`⚠ 项目中未找到 ${className}.java（可能不在同一模块）`)
      return
    }
    const data = await res.json()
    const astSymbols = Array.isArray(data.symbols) ? data.symbols : []
    console.log('[CrossNav] found file:', data.filename, 'symbols:', astSymbols.length)
    tabs.list.push({
      id:              `cross:${className}`,
      className,
      filename:        data.filename,
      source:          data.source,
      filePath:        data.filePath,
      internalMethods: astSymbols.length > 0 ? astSymbols.map(s => s.name) : extractMethodNames(data.source),
      symbolsMap:      Object.fromEntries(astSymbols.map(s => [s.name, s.line])),
      typeMap:         data.typeMap || {},
      entryMethod:     methodName || null,
      kind:            data.kind  || 'class',
      pkg:             data.pkg   || ''
    })
    tabs.activeIdx = tabs.list.length - 1
    codeViewer.toast = ''
    nextTick(() => {
      scrollActiveTabIntoView()
      nextTick(() => scrollToMethod(methodName))
    })
  } catch (err) {
    console.error('[CrossNav] error:', err)
    showToast(`⚠ 跨文件导航失败：${err.message}`)
  }
}

// 代码区点击：正向（call→def）+ 反向（def→call sites）+ 外部类导航 + 跨文件方法导航
const handleCodeAreaClick = (e) => {
  // 点击非弹窗区域 → 关闭调用位置弹窗
  if (callSitesPopup.visible && !e.target.closest('.csp-popup')) {
    callSitesPopup.visible = false
  }

  // 外部类跳转：点击 PascalCase 外部类名 → 新 Tab
  const extSpan = e.target.closest('[data-ext-class]')
  if (extSpan) {
    openExternalClass(extSpan.dataset.extClass)
    return
  }

  // 跨文件方法跳转：点击 obj.method( 形式的调用 → 查找文件并跳转
  const crossSpan = e.target.closest('[data-cross-method]')
  if (crossSpan) {
    callSitesPopup.visible = false
    openCrossFileMethod(crossSpan.dataset.crossObj, crossSpan.dataset.crossMethod)
    return
  }

  // 正向跳转：点击调用处 → 跳到定义（同文件内部方法）
  const callSpan = e.target.closest('.hl-internal-call')
  if (callSpan) {
    callSitesPopup.visible = false
    scrollToMethod(callSpan.dataset.method)
    return
  }

  // 反向跳转：点击方法定义 → 弹出调用位置列表
  const defSpan = e.target.closest('[data-method-def]')
  if (defSpan) {
    const methodName = defSpan.dataset.methodDef
    const tab = currentTab.value
    const map = buildCallSitesMap(tab?.source || '', tab?.internalMethods || [])
    const sites = map[methodName] || []

    // 弹窗定位：在点击元素正下方
    const rect = defSpan.getBoundingClientRect()
    callSitesPopup.methodName = methodName
    callSitesPopup.sites = sites
    callSitesPopup.x = rect.left
    callSitesPopup.y = rect.bottom + 6
    callSitesPopup.visible = true

    // 确保弹窗不超出屏幕右侧/底部
    nextTick(() => {
      const popup = document.querySelector('.csp-popup')
      if (!popup) return
      const pr = popup.getBoundingClientRect()
      if (pr.right > window.innerWidth - 8)
        callSitesPopup.x = window.innerWidth - pr.width - 8
      if (pr.bottom > window.innerHeight - 8)
        callSitesPopup.y = rect.top - pr.height - 6
    })
    return
  }
}

// 跳转到调用位置（反向跳转）—— 统一走 scrollToLine
const jumpToCallSite = (lineNum) => {
  if (!codeBodyRef.value) return
  scrollToLine(lineNum)
  callSitesPopup.visible = false
  // 保留旧逻辑兼容 index-based 访问（scrollToLine 已处理，此处仅做 fallback）
  const lines = codeBodyRef.value.querySelectorAll('.code-line')
  const target = lines[lineNum - 1]
  if (!target) return
  target.classList.remove('line-flash')
  void target.offsetWidth
  target.classList.add('line-flash')
  setTimeout(() => target.classList.remove('line-flash'), 2000)
  callSitesPopup.visible = false
}

const closeCallSitesPopup = () => { callSitesPopup.visible = false }

// 已加载节点路径缓存：node.code → filePath
// 跨关闭/打开保留，避免重复发起网络请求；切换到其他交易卡片时自然随组件销毁
const nodePathCache = new Map()
const shouldResolveFlowSource = (node, nodeType) =>
  nodeType === 'orchestration' || node?.prefix === 'method' || !!node?.code?.includes('.')

// 打开代码查看器：通过全局索引把 className 解析成 filePath，再传给 MonacoCodeViewer
const openCodeViewer = async (node, nodeType) => {
  codeViewer.drawerVisible = false
  codeViewer.drawerPlacement = 'hidden'
  codeViewer.drawerShift = 0
  codeViewer.drawerWidth = 0
  codeViewer.drawerReservedWidth = 0
  const cacheKey = `${props.transaction.id}|${nodeType}|${node?.prefix || ''}|${node.code}`
  const reg = NODE_FILE_REGISTRY[node.code]

  if (shouldResolveFlowSource(node, nodeType)) {
    const cached = nodePathCache.get(cacheKey)
    if (cached) {
      Object.assign(codeViewer, {
        visible: true, node, nodeType, noSource: false,
        loading: false, toast: '',
        filePath: cached.filePath,
        methodName: cached.methodName || '',
        locateText: cached.locateText || '',
      })
      return
    }

    Object.assign(codeViewer, {
      visible: true, node, nodeType, noSource: false,
      loading: true, toast: '', filePath: '', methodName: '', locateText: '',
    })

    try {
      const params = new URLSearchParams({
        txId: props.transaction.id,
        nodeCode: node.code,
        nodeType,
        nodePrefix: node?.prefix || '',
      })
      const res = await fetch(`/api/source/resolve/flow?${params}`)
      if (!res.ok) {
        Object.assign(codeViewer, { noSource: true, loading: false })
        return
      }
      const data = await res.json()
      const resolved = {
        filePath: data.filePath,
        methodName: data.methodName || '',
        locateText: data.locateText || '',
      }
      nodePathCache.set(cacheKey, resolved)
      Object.assign(codeViewer, {
        loading: false,
        filePath: resolved.filePath,
        methodName: resolved.methodName,
        locateText: resolved.locateText,
      })
      return
    } catch {
      Object.assign(codeViewer, { noSource: true, loading: false })
      return
    }
  }

  if (!reg) {
    Object.assign(codeViewer, { visible: true, node, nodeType, noSource: true,
      loading: false, toast: '', filePath: '', methodName: '', locateText: '' })
    return
  }

  // 同一节点且已有缓存路径 → 直接显示，无需重新请求（保留 Monaco 现有标签页）
  const cached = nodePathCache.get(cacheKey)
  if (cached) {
    Object.assign(codeViewer, {
      visible: true, node, nodeType, noSource: false,
      loading: false, toast: '',
      filePath:   cached.filePath,
      methodName: cached.methodName,
      locateText: cached.locateText || '',
    })
    return
  }

  // 首次加载：先打开弹窗（loading 状态）
  Object.assign(codeViewer, { visible: true, node, nodeType, noSource: false,
    loading: true, toast: '', methodName: '', locateText: '' })
  // 不重置 filePath，避免 MonacoCodeViewer 触发空路径处理

  try {
    const params = new URLSearchParams({ methodName: reg.methodName })
    if (reg.fqn)  params.set('fqn',       reg.fqn)
    else          params.set('className',  reg.className)

    const res = await fetch(`/api/source/find?${params}`)
    if (!res.ok) {
      Object.assign(codeViewer, { noSource: true, loading: false })
      return
    }
    const data = await res.json()
    const filePath   = data.filePath
    const methodName = reg.methodName
    // 写入缓存
    nodePathCache.set(cacheKey, { filePath, methodName, locateText: '' })
    Object.assign(codeViewer, { loading: false, filePath, methodName, locateText: '' })
  } catch {
    Object.assign(codeViewer, { noSource: true, loading: false })
  }
}

const updateCodeViewerDrawer = (layout = {}) => {
  codeViewer.drawerVisible = !!layout.visible
  codeViewer.drawerPlacement = layout.placement || 'hidden'
  codeViewer.drawerWidth = layout.visible ? Math.max(0, Number(layout.width) || 0) : 0
  codeViewer.drawerReservedWidth = layout.visible && !codeViewer.fullscreen && layout.placement === 'external-right'
    ? codeViewer.drawerWidth + 20
    : 0
  codeViewer.drawerShift = layout.visible && !codeViewer.fullscreen
    ? Math.max(0, Number(layout.modalShift) || 0)
    : 0
}

const handleCodeViewerFullscreenChange = (value) => {
  codeViewer.fullscreen = !!value
  if (codeViewer.fullscreen) {
    codeViewer.drawerShift = 0
    codeViewer.drawerReservedWidth = 0
  } else if (codeViewer.drawerVisible && codeViewer.drawerPlacement === 'external-right') {
    codeViewer.drawerReservedWidth = codeViewer.drawerWidth + 20
  }
}

const closeCodeViewer = () => {
  codeViewer.visible    = false
  codeViewer.fullscreen = false
  codeViewer.drawerVisible = false
  codeViewer.drawerPlacement = 'hidden'
  codeViewer.drawerShift = 0
  codeViewer.drawerWidth = 0
  codeViewer.drawerReservedWidth = 0
  callSitesPopup.visible = false
}

// 打开外部类 Tab（已有则切换，没有则新增）
const openExternalClass = (className) => {
  const reg = EXTERNAL_REGISTRY[className]
  if (!reg) return
  const existingIdx = tabs.list.findIndex(t => t.id === className)
  if (existingIdx >= 0) {
    tabs.activeIdx = existingIdx
    nextTick(() => { if (codeBodyRef.value) codeBodyRef.value.scrollTop = 0 })
    return
  }
  tabs.list.push({
    id: className, className,
    filename: reg.filename, source: reg.source,
    filePath: null, typeMap: {}, symbolsMap: {},
    internalMethods: null, entryMethod: null,
    kind: reg.kind, pkg: reg.pkg
  })
  tabs.activeIdx = tabs.list.length - 1
  nextTick(() => {
    if (codeBodyRef.value) codeBodyRef.value.scrollTop = 0
    // 自动将新 Tab 滚入视图
    scrollActiveTabIntoView()
  })
}

// 关闭指定 Tab（第一个 Tab 不可关闭）
const closeTab = (idx) => {
  if (idx <= 0) return
  tabs.list.splice(idx, 1)
  if (tabs.activeIdx >= idx) tabs.activeIdx = Math.max(0, tabs.activeIdx - 1)
}

// Tab 栏左右滚动
const scrollTabs = (dir) => {
  tabsContainerRef.value?.scrollBy({ left: dir * 200, behavior: 'smooth' })
}

// 让当前激活的 Tab 滚入可见区域
const scrollActiveTabIntoView = () => {
  nextTick(() => {
    const container = tabsContainerRef.value
    if (!container) return
    const active = container.querySelector('.code-tab.active')
    if (active) active.scrollIntoView({ behavior: 'smooth', inline: 'nearest', block: 'nearest' })
  })
}

// 切换 Tab 时自动滚到代码顶部
watch(() => tabs.activeIdx, () => {
  callSitesPopup.visible = false
  nextTick(() => { if (codeBodyRef.value) codeBodyRef.value.scrollTop = 0 })
})

const copyCode = () => {
  const tab = currentTab.value
  if (!tab) return
  navigator.clipboard?.writeText(tab.source)
  codeViewer.copied = true
  setTimeout(() => { codeViewer.copied = false }, 2000)
}

defineExpose({
  isExpanded,
  expand:   () => { isExpanded.value = true },
  collapse: () => { if (isFullscreen.value) closeFullscreen(); isExpanded.value = false }
})
</script>

<style scoped>
.transaction-card {
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: 10px;
  overflow: clip;
  transition: box-shadow 0.2s, background 0.3s, border-color 0.3s;
}
.transaction-card:hover { box-shadow: 0 2px 12px rgba(0,0,0,0.12); }
.transaction-card.expanded { box-shadow: 0 4px 20px rgba(0,0,0,0.15); }

/* 卡片头部 */
.card-header {
  display: flex; align-items: center; gap: 12px;
  padding: 14px 16px; cursor: pointer; transition: background 0.15s;
}
.card-header:hover { background: var(--card-hover-bg); }
.card-expand-icon { width: 24px; height: 24px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.card-main { flex: 1; min-width: 0; }
.card-title-row { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; }
.tx-code { font-size: 12px; font-weight: 600; color: #4F7CFF; background: rgba(79,124,255,0.1); padding: 2px 8px; border-radius: 4px; font-family: 'SF Mono','Fira Code',monospace; flex-shrink: 0; }
.tx-name { font-size: 14px; font-weight: 600; color: var(--text-primary); }
.card-meta { display: flex; align-items: center; gap: 8px; }
.meta-badge { display: inline-flex; align-items: center; gap: 4px; font-size: 11px; font-weight: 500; padding: 3px 8px; border-radius: 12px; }
.meta-badge.service   { background: rgba(79,124,255,0.1);  color: #4F7CFF; }
.meta-badge.component { background: rgba(247,103,7,0.1);   color: #F76707; }
.meta-badge.table     { background: rgba(18,184,134,0.1);  color: #12B886; }
.card-right { display: flex; flex-direction: column; align-items: flex-end; gap: 6px; flex-shrink: 0; }
.domain-tag { font-size: 11px; color: var(--text-muted); background: var(--bg-badge); padding: 2px 8px; border-radius: 4px; }
.layer-badge { display: flex; align-items: center; gap: 4px; font-size: 11px; color: #7950F2; background: rgba(121,80,242,0.1); padding: 2px 8px; border-radius: 4px; font-weight: 500; }
.fullscreen-btn { width: 24px; height: 24px; display: flex; align-items: center; justify-content: center; background: transparent; border: 1px solid var(--card-border); border-radius: 5px; cursor: pointer; color: var(--text-faint); flex-shrink: 0; transition: all 0.15s; }
.fullscreen-btn:hover { background: rgba(79,124,255,0.1); color: #4F7CFF; border-color: rgba(79,124,255,0.3); }

/* 全屏 */
.fs-backdrop { position: fixed; inset: 0; background: rgba(0,0,0,0.7); z-index: 998; backdrop-filter: blur(2px); }
.chain-view-fullscreen { position: fixed !important; inset: 40px !important; z-index: 999; border-radius: 14px !important; box-shadow: 0 24px 80px rgba(0,0,0,0.4) !important; display: flex; flex-direction: column; border: 1px solid var(--card-border); overflow: hidden; }
.chain-view-fullscreen .chain-canvas { flex: 1; overflow: auto; min-height: 0 !important; }
.fs-title-bar { display: flex; align-items: center; gap: 8px; flex: 1; min-width: 0; }
.toolbar-right { display: flex; align-items: center; gap: 4px; }
.fs-close-btn { background: #FEF2F2 !important; color: #EF4444 !important; border-color: #FECACA !important; }
.fs-close-btn:hover { background: #FEE2E2 !important; border-color: #FCA5A5 !important; }
.ai-toolbar-actions { display: flex; align-items: center; gap: 6px; margin-right: 4px; }
.ai-toolbar-btn {
  height: 28px; display: inline-flex; align-items: center; justify-content: center; gap: 6px;
  padding: 0 10px; background: rgba(79,124,255,0.08); border: 1px solid rgba(79,124,255,0.18);
  border-radius: 6px; cursor: pointer; color: #315fd7; font-size: 12px; font-weight: 600;
  transition: all 0.15s; font-family: inherit;
}
.ai-toolbar-btn:hover:not(:disabled) { background: rgba(79,124,255,0.14); border-color: rgba(79,124,255,0.28); }
.ai-toolbar-btn.active { background: rgba(79,124,255,0.18); border-color: rgba(79,124,255,0.32); }
.ai-toolbar-btn.secondary { background: var(--bg-action-btn); color: var(--text-muted); border-color: var(--card-border); }
.ai-toolbar-btn.secondary:hover:not(:disabled) { background: rgba(79,124,255,0.08); color: #4F7CFF; border-color: rgba(79,124,255,0.22); }
.ai-toolbar-btn:disabled { opacity: 0.65; cursor: not-allowed; }

/* 选中信息栏 */
.selection-bar { display: flex; align-items: center; gap: 8px; padding: 8px 16px; background: rgba(79,124,255,0.1); border-bottom: 1px solid rgba(79,124,255,0.2); font-size: 12px; }
.selection-text { flex: 1; color: #4F7CFF; font-weight: 500; }
.reset-btn { padding: 3px 10px; background: var(--card-bg); border: 1px solid rgba(79,124,255,0.3); border-radius: 5px; color: #4F7CFF; font-size: 12px; cursor: pointer; font-family: inherit; transition: all 0.15s; }
.reset-btn:hover { background: #4F7CFF; color: #fff; }

.chain-hint { padding: 8px 16px 0; font-size: 12px; color: var(--text-faint); display: flex; align-items: center; gap: 5px; }

.chain-toolbar { display: flex; align-items: center; justify-content: space-between; padding: 8px 16px; background: var(--chain-toolbar); border-bottom: 1px solid var(--layer-nodes-bd); gap: 12px; flex-shrink: 0; transition: background 0.3s; }
.zoom-hint { display: flex; align-items: center; gap: 5px; font-size: 11px; color: var(--text-faint); }
.zoom-controls { display: flex; align-items: center; gap: 4px; }
.zoom-btn { width: 26px; height: 26px; display: flex; align-items: center; justify-content: center; background: var(--bg-action-btn); border: 1px solid var(--card-border); border-radius: 6px; cursor: pointer; color: var(--text-muted); transition: all 0.15s; }
.zoom-btn:hover { background: rgba(79,124,255,0.1); color: #4F7CFF; border-color: rgba(79,124,255,0.3); }
.zoom-value { font-size: 12px; color: var(--text-muted); min-width: 40px; text-align: center; font-family: monospace; }

.analysis-panel {
  border-bottom: 1px solid var(--layer-nodes-bd);
  background: linear-gradient(180deg, rgba(79,124,255,0.04), transparent 36%), var(--chain-bg);
  padding: 14px 16px 16px;
}
.analysis-panel-header {
  display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 12px;
}
.analysis-panel-title-group { display: flex; flex-direction: column; gap: 5px; min-width: 0; }
.analysis-panel-title-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.analysis-panel-title { font-size: 14px; font-weight: 700; color: var(--text-primary); }
.analysis-panel-scope { font-size: 12px; color: var(--text-faint); }
.analysis-status-tag {
  display: inline-flex; align-items: center; padding: 2px 8px; border-radius: 999px;
  font-size: 11px; font-weight: 600;
}
.analysis-status-tag.cached { background: rgba(18,184,134,0.12); color: #0f9f74; }
.analysis-status-tag.stale { background: rgba(247,103,7,0.12); color: #d96a02; }
.analysis-close-btn {
  height: 28px; padding: 0 10px; border-radius: 6px; border: 1px solid var(--card-border);
  background: var(--card-bg); color: var(--text-muted); cursor: pointer; font-size: 12px; transition: all 0.15s;
}
.analysis-close-btn:hover { color: #4F7CFF; border-color: rgba(79,124,255,0.26); background: rgba(79,124,255,0.06); }
.analysis-loading, .analysis-error-card {
  display: flex; align-items: center; gap: 12px; padding: 14px; border-radius: 10px;
  background: var(--layer-bg); border: 1px solid var(--layer-bd);
}
.analysis-loading-spinner {
  width: 18px; height: 18px; border-radius: 50%;
  border: 2px solid rgba(79,124,255,0.18); border-top-color: #4F7CFF;
  animation: spin 0.9s linear infinite; flex-shrink: 0;
}
.analysis-loading-text { display: flex; flex-direction: column; gap: 4px; }
.analysis-loading-title, .analysis-error-title { font-size: 13px; font-weight: 600; color: var(--text-primary); }
.analysis-loading-sub, .analysis-error-detail { font-size: 12px; color: var(--text-faint); line-height: 1.6; }
.analysis-content { display: flex; flex-direction: column; gap: 12px; }
.analysis-stats { display: flex; flex-wrap: wrap; gap: 8px; }
.analysis-stat-chip {
  display: inline-flex; align-items: center; gap: 6px; padding: 5px 9px;
  border-radius: 999px; background: var(--layer-bg); border: 1px solid var(--layer-bd);
}
.analysis-stat-label { font-size: 11px; color: var(--text-faint); }
.analysis-stat-value { font-size: 11px; color: var(--text-primary); font-weight: 600; }
.analysis-section-grid {
  display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 12px;
}
.analysis-section-card {
  display: flex; flex-direction: column; gap: 10px; padding: 14px;
  border-radius: 12px; background: var(--layer-bg); border: 1px solid var(--layer-bd);
}
.analysis-section-title { font-size: 13px; font-weight: 700; color: var(--text-primary); }
.analysis-section-text {
  font-size: 12px; line-height: 1.75; color: var(--text-secondary);
  white-space: pre-wrap; word-break: break-word;
}
.analysis-finding-list, .analysis-snippet-list { display: flex; flex-direction: column; gap: 10px; }
.analysis-finding-item, .analysis-snippet-item {
  display: flex; flex-direction: column; gap: 7px; padding: 12px;
  border-radius: 10px; background: var(--card-bg); border: 1px solid var(--card-border);
}
.analysis-finding-head, .analysis-snippet-head {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
}
.analysis-severity-chip {
  display: inline-flex; align-items: center; padding: 2px 8px; border-radius: 999px;
  font-size: 10px; font-weight: 700; letter-spacing: 0.02em;
}
.analysis-severity-chip.severity-high { background: rgba(239,68,68,0.12); color: #dc2626; }
.analysis-severity-chip.severity-medium { background: rgba(245,158,11,0.14); color: #d97706; }
.analysis-severity-chip.severity-low { background: rgba(18,184,134,0.12); color: #0f9f74; }
.analysis-severity-chip.severity-default { background: rgba(140,148,166,0.14); color: var(--text-muted); }
.analysis-finding-title, .analysis-snippet-node { font-size: 12px; font-weight: 600; color: var(--text-primary); }
.analysis-finding-detail, .analysis-snippet-path, .analysis-snippet-range {
  font-size: 12px; line-height: 1.6; color: var(--text-faint); word-break: break-word;
}
.analysis-finding-evidence {
  font-family: 'Consolas','Cascadia Code','JetBrains Mono','Fira Code',monospace;
  font-size: 11px; color: var(--text-secondary);
  background: rgba(79,124,255,0.06); border: 1px solid rgba(79,124,255,0.12);
  padding: 8px 10px; border-radius: 8px; white-space: pre-wrap; word-break: break-word;
}
.analysis-snippet-method {
  font-size: 11px; color: #4F7CFF; font-family: 'SF Mono','Fira Code',monospace;
}
.analysis-snippet-preview {
  margin: 0; padding: 10px 12px; border-radius: 8px; max-height: 180px; overflow: auto;
  background: rgba(17,24,39,0.92); color: #E5EDF7; font-size: 11px; line-height: 1.6;
  font-family: 'Consolas','Cascadia Code','JetBrains Mono','Fira Code',monospace;
  white-space: pre-wrap; word-break: break-word;
}

/* 链路图 */
.chain-view { border-top: 1px solid var(--layer-nodes-bd); background: var(--chain-bg); transition: background 0.3s; }
.chain-canvas { overflow-x: auto; overflow-y: hidden; min-height: 160px; cursor: grab; user-select: none; padding: 16px 20px 20px; scrollbar-width: thin; scrollbar-color: var(--scrollbar-thumb) transparent; }
.chain-canvas:active { cursor: grabbing; }
.chain-canvas::-webkit-scrollbar { height: 6px; }
.chain-canvas::-webkit-scrollbar-track { background: transparent; border-radius: 3px; }
.chain-canvas::-webkit-scrollbar-thumb { background: var(--scrollbar-thumb); border-radius: 3px; }

.chain-content { display: flex; flex-direction: row; align-items: flex-start; gap: 48px; width: fit-content; min-width: 100%; position: relative; }
.chain-curves-svg { position: absolute; top: 0; left: 0; pointer-events: none; overflow: visible; z-index: 0; }

/* 层 */
.chain-layer { background: var(--layer-bg); border: 1px solid var(--layer-bd); border-radius: 10px; overflow: visible; min-width: 190px; flex-shrink: 0; display: flex; flex-direction: column; position: relative; z-index: 1; transition: background 0.3s, border-color 0.3s; }
.layer-header { display: flex; align-items: center; justify-content: space-between; padding: 9px 12px; cursor: pointer; transition: background 0.15s; border-top: 3px solid transparent; white-space: nowrap; }
.layer-header:hover { background: var(--card-hover-bg); }
.orchestration { border-top-color: #4F7CFF; background: linear-gradient(to bottom, rgba(79,124,255,0.06), transparent); }
.service       { border-top-color: #12B886; background: linear-gradient(to bottom, rgba(18,184,134,0.06), transparent); }
.component     { border-top-color: #F76707; background: linear-gradient(to bottom, rgba(247,103,7,0.06), transparent); }
.data          { border-top-color: #7950F2; background: linear-gradient(to bottom, rgba(121,80,242,0.06), transparent); }
.layer-title { font-size: 12px; font-weight: 600; color: var(--layer-title); letter-spacing: 0.5px; }
.layer-header-right { display: flex; align-items: center; gap: 6px; }
.layer-click-hint { font-size: 10px; color: var(--text-faint); }
.layer-badge-count { font-size: 11px; font-weight: 700; background: #4F7CFF; color: #fff; padding: 1px 7px; border-radius: 10px; }
.data-badge { background: #7950F2; }

.layer-nodes { padding: 10px 12px; display: flex; flex-direction: column; gap: 8px; border-top: 1px solid var(--layer-nodes-bd); flex: 1; }

/* 节点 */
.chain-node { display: flex; flex-direction: column; gap: 3px; padding: 9px 12px; border-radius: 8px; border: 1px solid; cursor: pointer; transition: all 0.15s; width: 100%; }
.orchestration-node { background: var(--node-orch-bg); border-color: var(--node-orch-bd); }
.orchestration-node:hover { filter: brightness(0.95); box-shadow: 0 2px 8px rgba(79,124,255,0.2); }
.service-node { background: var(--node-svc-bg); border-color: var(--node-svc-bd); }
.service-node:hover { filter: brightness(0.95); box-shadow: 0 2px 8px rgba(18,184,134,0.2); }
.component-node { background: var(--node-comp-bg); border-color: var(--node-comp-bd); }
.component-node:hover { filter: brightness(0.95); box-shadow: 0 2px 8px rgba(247,103,7,0.2); }
.data-node { flex-direction: row; align-items: center; gap: 8px; background: var(--node-data-bg); border-color: var(--node-data-bd); padding: 8px 12px; }
.data-node:hover { filter: brightness(0.95); box-shadow: 0 2px 8px rgba(121,80,242,0.2); }
.pcs-service-node { background: var(--node-pcs-bg) !important; border-color: var(--node-pcs-bd) !important; }

.node-header { display: flex; align-items: center; gap: 6px; }
.node-prefix { display: inline-flex; align-items: center; justify-content: center; font-size: 11px; font-weight: 700; padding: 3px 8px; border-radius: 6px; font-family: 'SF Mono','Fira Code',monospace; letter-spacing: 0.5px; white-space: nowrap; flex-shrink: 0; box-shadow: 0 1px 3px rgba(0,0,0,0.08); border-width: 1.5px; border-style: solid; }
.prefix-pbs    { background: #DCFCE7; color: #15803D; border-color: #86EFAC; }
.prefix-pcs    { background: #DBEAFE; color: #1D4ED8; border-color: #93C5FD; }
.prefix-pbcc   { background: #FFEDD5; color: #C2410C; border-color: #FDBA74; }
.prefix-pbct   { background: #FEF9C3; color: #A16207; border-color: #FDE047; }
.prefix-pbcb   { background: #F3E8FF; color: #7E22CE; border-color: #D8B4FE; }
.prefix-pbcp   { background: #FCE7F3; color: #BE185D; border-color: #F9A8D4; }
.prefix-method { background: #F0FDFA; color: #0F766E; border-color: #5EEAD4; }

.node-code { font-size: 12px; font-weight: 700; color: var(--node-code); font-family: monospace; }
.node-code-service { font-size: 11px; font-weight: 600; color: var(--node-svc-code); font-family: monospace; }
.node-name { font-size: 12px; color: var(--node-name); }
.node-table-code { font-size: 12px; font-weight: 600; color: #7950F2; font-family: monospace; }
.node-check { flex-shrink: 0; }

/* 节点状态 */
.service-node.node-selected   { border-color: #12B886 !important; filter: brightness(0.92); }
.component-node.node-selected { border-color: #F76707 !important; filter: brightness(0.92); }

@keyframes callingPulse {
  0%   { box-shadow: 0 0 0 0   rgba(79,124,255,0.55); }
  50%  { box-shadow: 0 0 0 6px rgba(79,124,255,0); }
  100% { box-shadow: 0 0 0 0   rgba(79,124,255,0.55); }
}
@keyframes sharedPulse {
  0%   { box-shadow: 0 0 0 0   rgba(79,124,255,0.55); }
  50%  { box-shadow: 0 0 0 6px rgba(79,124,255,0); }
  100% { box-shadow: 0 0 0 0   rgba(79,124,255,0.55); }
}
.node-calling { border-color: #4F7CFF !important; background: linear-gradient(135deg, rgba(79,124,255,0.18), rgba(79,124,255,0.1)) !important; animation: callingPulse 1.4s ease-in-out infinite; }
.node-shared  { border-color: #4F7CFF !important; background: linear-gradient(135deg, rgba(79,124,255,0.18), rgba(79,124,255,0.1)) !important; animation: sharedPulse 1.4s ease-in-out infinite; }

.calling-badge { margin-left: auto; font-size: 10px; font-weight: 600; border-radius: 4px; padding: 1px 5px; white-space: nowrap; flex-shrink: 0; color: #4F7CFF; background: rgba(79,124,255,0.1); border: 1px solid rgba(79,124,255,0.3); }

/* 服务层内嵌双子层 */
.svc-inner-canvas { position: relative; display: flex; flex-direction: row; align-items: flex-start; gap: 48px; padding: 12px 14px; overflow: visible; }
.service-call-svg { position: absolute; top: 0; left: 0; pointer-events: none; overflow: visible; z-index: 2; }
.svc-inner-layer { background: var(--layer-inner-bg); border: 1px solid var(--layer-bd); border-radius: 10px; overflow: hidden; min-width: 180px; flex-shrink: 0; display: flex; flex-direction: column; position: relative; z-index: 1; transition: background 0.3s; }
.svc-inner-header { display: flex; align-items: center; justify-content: space-between; padding: 8px 12px; border-top: 3px solid transparent; }
.pbs-inner-layer .svc-inner-header { border-top-color: #12B886; background: linear-gradient(to bottom, rgba(18,184,134,0.06), transparent); }
.pcs-inner-layer .svc-inner-header { border-top-color: #228BE6; background: linear-gradient(to bottom, rgba(34,139,230,0.06), transparent); }
.svc-inner-title { font-size: 12px; font-weight: 600; color: var(--layer-title); letter-spacing: 0.5px; }
.svc-inner-nodes { padding: 10px 12px; display: flex; flex-direction: column; gap: 8px; border-top: 1px solid var(--inner-nodes-bd); flex: 1; }

/* 跨领域标签 */
.cross-domain-tag { display: inline-flex; align-items: center; gap: 4px; font-size: 11px; font-weight: 500; padding: 2px 7px; border-radius: 4px; margin-top: 4px; width: fit-content; white-space: nowrap; }

/* 节点头部右侧操作区 */
.node-header-actions { margin-left: auto; display: flex; align-items: center; gap: 3px; }
.node-check { flex-shrink: 0; }
/* 交易层首行布局 */
.node-first-row { display: flex; align-items: center; justify-content: space-between; }

/* 代码查看按钮（hover 显示） */
.code-view-btn {
  width: 20px; height: 20px; display: flex; align-items: center; justify-content: center;
  background: transparent; border: 1px solid transparent; border-radius: 4px;
  cursor: pointer; color: var(--text-faint); opacity: 0; transition: all 0.15s;
  flex-shrink: 0; padding: 0;
}
.chain-node:hover .code-view-btn { opacity: 1; }
.code-view-btn:hover { background: rgba(79,124,255,0.12); color: #4F7CFF; border-color: rgba(79,124,255,0.3); }
.code-view-btn.test-btn:hover { background: rgba(18,184,134,0.12); color: #12B886; border-color: rgba(18,184,134,0.3); }

/* ── 代码查看弹窗（跟随日/夜主题） ── */
.code-modal-backdrop {
  position: fixed; inset: 0; z-index: 2000;
  background: rgba(0,0,0,0.60);
  backdrop-filter: blur(4px);
  display: flex; align-items: center; justify-content: center;
  padding: 28px;
  transition: background 0.24s ease, backdrop-filter 0.24s ease;
}
.code-modal-backdrop.code-modal-backdrop-drawer-right {
  justify-content: flex-start;
  padding-right: calc(28px + var(--code-modal-reserved-width, 0px));
}
.code-modal-backdrop.code-modal-backdrop-ai-fullscreen {
  background: rgba(2, 6, 23, 0.82);
  backdrop-filter: blur(12px);
}
.code-modal {
  background: var(--code-modal-bg);
  border: 1px solid var(--code-modal-bd);
  border-radius: 6px;
  width: min(960px, 100%); max-height: 86vh;
  display: flex; flex-direction: column;
  box-shadow: 0 20px 80px rgba(0,0,0,0.50);
  overflow: hidden;
  transition: transform 0.24s ease, opacity 0.24s ease, filter 0.24s ease, box-shadow 0.24s ease;
}
/* Monaco 版弹窗需要明确高度，让编辑器能计算尺寸 */
.code-modal.monaco-modal {
  height: 80vh;
}
.code-modal.monaco-modal.monaco-modal-external-right {
  width: min(1400px, calc(100vw - 56px - var(--code-modal-reserved-width, 0px)));
  max-width: calc(100vw - 56px - var(--code-modal-reserved-width, 0px));
}
.code-modal.monaco-modal.monaco-modal-ai-background {
  opacity: 0.1;
  transform: scale(0.96);
  filter: blur(6px) saturate(0.7);
  box-shadow: none;
  pointer-events: none;
}
.code-modal.monaco-modal.monaco-modal-ai-hidden {
  opacity: 0;
  visibility: hidden;
  width: 0 !important;
  max-width: 0 !important;
  height: 0 !important;
  max-height: 0 !important;
  min-height: 0 !important;
  min-width: 0 !important;
  border: none !important;
  box-shadow: none !important;
  overflow: hidden;
}
/* 全屏模式 */
.code-modal-backdrop-fs {
  padding: 0 !important;
}
.code-modal.monaco-modal-fs {
  width: 100vw !important;
  max-width: 100vw !important;
  height: 100vh !important;
  max-height: 100vh !important;
  border-radius: 0 !important;
  border: none !important;
}

/* ── 标题栏 ── */
.code-modal-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 10px 14px 10px 16px; border-bottom: 1px solid var(--code-header-bd); gap: 10px;
  background: var(--code-header-bg); flex-shrink: 0; user-select: none;
}
.code-modal-title { display: flex; align-items: center; gap: 8px; min-width: 0; flex: 1; overflow: hidden; }
.code-modal-name { font-size: 12.5px; font-weight: 600; color: var(--code-text); font-family: 'Consolas','SF Mono',monospace; white-space: nowrap; }
.code-modal-sep  { color: var(--code-ln); font-size: 13px; flex-shrink: 0; }
.code-modal-desc { font-size: 12px; color: var(--code-ln); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.code-modal-header-actions { display: flex; align-items: center; gap: 6px; flex-shrink: 0; }
.code-modal-close {
  width: 26px; height: 26px; display: flex; align-items: center; justify-content: center;
  background: transparent; border: none;
  border-radius: 4px; cursor: pointer; color: var(--code-ln); transition: all 0.12s; flex-shrink: 0;
}
.code-modal-close:hover { background: var(--code-copy-hover); color: var(--code-text); }

/* 复制 & 入口跳转 */
.code-copy-btn {
  display: flex; align-items: center; gap: 5px;
  font-size: 11px; color: var(--code-annotation);
  background: transparent; border: 1px solid rgba(128,128,128,0.2);
  border-radius: 3px; padding: 3px 9px; cursor: pointer;
  transition: all 0.12s; font-family: inherit;
}
.code-copy-btn:hover { background: var(--code-copy-hover); }
.code-copy-btn.copied { color: var(--code-type); }
.code-jump-btn {
  width: 26px; height: 26px; display: flex; align-items: center; justify-content: center;
  background: transparent; border: 1px solid rgba(128,128,128,0.2);
  border-radius: 3px; cursor: pointer; color: var(--code-jump-color); transition: all 0.12s;
}
.code-jump-btn:hover { background: var(--code-jump-hover); }

/* ── IDE 风格 Tab 标签栏 ── */
.code-tab-bar {
  display: flex; align-items: stretch;
  background: var(--code-header-bg);
  border-bottom: 1px solid var(--code-header-bd);
  flex-shrink: 0; height: 36px; overflow: hidden;
}

/* 左右滚动箭头 */
.tab-bar-arrow {
  flex-shrink: 0; width: 24px;
  display: flex; align-items: center; justify-content: center;
  background: var(--code-header-bg);
  border: none; border-right: 1px solid var(--code-header-bd);
  cursor: pointer; color: var(--code-ln);
  transition: background 0.12s, color 0.12s;
  z-index: 1;
}
.tab-bar-arrow.right { border-right: none; border-left: 1px solid var(--code-header-bd); }
.tab-bar-arrow:hover { background: var(--code-copy-hover); color: var(--code-text); }

/* Tab 滚动区域（隐藏系统滚动条） */
.tabs-scroll-area {
  flex: 1; display: flex; align-items: stretch;
  overflow-x: auto; overflow-y: hidden;
  scrollbar-width: none;  /* Firefox */
}
.tabs-scroll-area::-webkit-scrollbar { display: none; }  /* Chrome/Safari */

/* 单个 Tab */
.code-tab {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 0 14px 0 12px;
  flex-shrink: 0;
  cursor: pointer;
  border-right: 1px solid var(--code-header-bd);
  color: var(--code-ln);
  font-size: 12px;
  font-family: 'Consolas','SF Mono', system-ui, monospace;
  transition: background 0.12s, color 0.12s;
  position: relative;
  user-select: none;
  max-width: 220px;
}
.code-tab:hover { background: var(--code-copy-hover); color: var(--code-text); }
/* 激活 Tab：底部蓝线 + 高亮背景 */
.code-tab.active {
  background: var(--code-body-bg);
  color: var(--code-text);
  border-bottom: 2px solid var(--code-file-tab-bd);
  padding-bottom: 2px;
}
.tab-icon { flex-shrink: 0; color: var(--code-annotation); }
.code-tab.active .tab-icon { color: var(--code-type); }
.tab-label {
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
  max-width: 140px;
}
/* ── IDEA 风格 C / I / E 类型徽章 ── */
.kind-badge {
  display: inline-flex; align-items: center; justify-content: center;
  width: 15px; height: 15px; border-radius: 50%;
  font-size: 9px; font-weight: 800; line-height: 1;
  color: #fff; flex-shrink: 0;
  font-family: 'SF Pro Text', system-ui, -apple-system, sans-serif;
  letter-spacing: 0;
  user-select: none;
}
.kind-badge.kind-sm { width: 13px; height: 13px; font-size: 8px; }
/* class / abstract class → 蓝色 (#3573F0) */
.kind-class     { background: #3573F0; }
/* interface → 绿色 (#3D8C5A) */
.kind-interface { background: #3D8C5A; }
/* enum → 橙色 (#C07B3E) */
.kind-enum      { background: #C07B3E; }
.tab-close {
  flex-shrink: 0; width: 16px; height: 16px;
  display: flex; align-items: center; justify-content: center;
  background: transparent; border: none; border-radius: 3px;
  cursor: pointer; color: var(--code-ln);
  opacity: 0; transition: all 0.12s; padding: 0;
}
.code-tab:hover .tab-close { opacity: 0.6; }
.tab-close:hover { background: rgba(255,80,80,0.15); color: #FF5555; opacity: 1 !important; }

/* 包路径行（外部类 Tab 时展示） */
.code-pkg-bar {
  display: flex; align-items: center; gap: 6px;
  padding: 3px 16px;
  background: var(--code-header-bg);
  border-bottom: 1px solid var(--code-header-bd);
  flex-shrink: 0;
}
.pkg-label {
  font-size: 10px; color: var(--code-ln);
  font-family: 'Consolas','SF Mono',monospace;
}

/* 代码区 */
.cross-nav-toast {
  padding: 5px 16px; font-size: 12px;
  background: var(--code-header-bg);
  border-bottom: 1px solid rgba(78,201,176,0.3);
  color: #4EC9B0; flex-shrink: 0;
  animation: fadeIn 0.2s ease;
}
@keyframes fadeIn { from { opacity:0; transform:translateY(-4px) } to { opacity:1; transform:none } }

.code-no-source {
  flex: 1; display: flex; flex-direction: column;
  align-items: center; justify-content: center; gap: 10px;
  background: var(--code-body-bg); color: var(--text-faint);
  padding: 40px 24px; text-align: center;
}
.code-no-source .no-source-title { font-size: 15px; font-weight: 600; color: var(--text-primary); }
.code-no-source .no-source-sub   { font-size: 12px; max-width: 300px; line-height: 1.6; }
.code-no-source .spin-icon { animation: spin 1.2s linear infinite; opacity: 0.6; }
@keyframes spin { to { transform: rotate(360deg); } }
.code-no-source .no-source-icon { opacity: 0.45; }

.code-modal-body {
  flex: 1; overflow: auto; padding: 8px 0; margin: 0;
  font-family: 'Consolas','Cascadia Code','JetBrains Mono','Fira Code',monospace;
  font-size: 13px; line-height: 1.6;
  color: var(--code-text); background: var(--code-body-bg);
  white-space: normal;
  scrollbar-width: thin; scrollbar-color: var(--code-ln) transparent;
  tab-size: 4;
  transition: background 0.3s, color 0.3s;
}
.code-modal-body::-webkit-scrollbar { width: 10px; height: 10px; }
.code-modal-body::-webkit-scrollbar-track { background: var(--code-body-bg); }
.code-modal-body::-webkit-scrollbar-thumb { background: var(--code-ln); }
.code-modal-body::-webkit-scrollbar-thumb:hover { background: var(--text-faint); }
.code-modal-body code { display: block; min-width: max-content; }

/* 行容器 */
.code-line {
  display: flex; align-items: baseline;
  padding: 0; min-height: 1.6em; transition: background 0.06s;
}

/* 行号 */
.ln {
  display: flex; align-items: center; justify-content: flex-end;
  min-width: 48px; padding-right: 18px;
  color: var(--code-ln); font-size: 12px;
  user-select: none; flex-shrink: 0;
  line-height: 1.6em;
}
/* 行内容 */
.lc { flex: 1; padding: 0 20px 0 0; white-space: pre; }

/* ── 语法高亮（:deep 穿透 v-html 内容，颜色从 CSS 变量读取） ── */
:deep(.code-line) {
  display: flex; align-items: baseline;
  min-height: 1.6em; padding: 0; transition: background 0.06s;
}
:deep(.code-line:hover) { background: var(--code-internal-bg); }
:deep(.ln) {
  display: flex; align-items: center; justify-content: flex-end;
  min-width: 48px; padding-right: 18px;
  color: var(--code-ln); font-size: 12px; line-height: 1.6em;
  user-select: none; flex-shrink: 0;
}
:deep(.lc) { flex: 1; padding: 0 20px 0 0; white-space: pre; }

:deep(.hl-comment)    { color: var(--code-comment); }
:deep(.hl-annotation) { color: var(--code-annotation); }
:deep(.hl-keyword)    { color: var(--code-keyword); }
:deep(.hl-type)       { color: var(--code-type); }
:deep(.hl-string)     { color: var(--code-string); }
:deep(.hl-number)     { color: var(--code-number); }
:deep(.hl-fn)         { color: var(--code-fn); }
:deep(.hl-fn-entry)   { color: var(--code-fn-entry); font-weight: 700; }
:deep(.hl-fn-call)    { color: var(--code-fn); }
:deep(.hl-var)        { color: var(--code-var); }

/* 同文件内部方法调用（橙黄色）*/
:deep(.hl-internal-call) {
  color: var(--code-internal);
  cursor: pointer;
  border-bottom: 1px solid var(--code-internal);
  transition: background 0.12s;
  opacity: 0.85;
}
:deep(.hl-internal-call:hover) {
  background: var(--code-internal-bg);
  border-radius: 2px;
  opacity: 1;
}
/* 跨文件方法调用（青绿色，与内部方法区分）*/
:deep(.hl-cross-call) {
  color: #4EC9B0;
  cursor: pointer;
  border-bottom: 1px dashed #4EC9B0;
  transition: background 0.12s;
  opacity: 0.9;
}
:deep(.hl-cross-call:hover) {
  background: rgba(78, 201, 176, 0.12);
  border-radius: 2px;
  opacity: 1;
}

/* 整行跳转高亮 */
@keyframes lineFlash {
  0%   { background: var(--code-line-flash); }
  60%  { background: var(--code-line-flash); }
  100% { background: transparent; }
}
:deep(.line-flash) { animation: lineFlash 2s ease-out forwards; }

/* ── 外部可导航类（高亮 + 可点击） ── */
:deep(.hl-ext-ref) {
  cursor: pointer;
  border-bottom: 1px dashed var(--code-type);
  transition: background 0.12s, color 0.12s;
}
:deep(.hl-ext-ref:hover) {
  background: rgba(78,201,176,0.1);
  color: var(--code-type) !important;
  border-bottom-style: solid;
  border-radius: 2px;
}

/* 方法定义处可点击（反向跳转） */
:deep(.hl-fn-def) {
  cursor: pointer;
  text-decoration: underline dotted currentColor;
  text-underline-offset: 3px;
}
:deep(.hl-fn-def:hover) {
  background: var(--code-internal-bg);
  border-radius: 2px;
  text-decoration: underline solid currentColor;
}

/* ── 调用位置弹窗（反向跳转 IDE 风格） ── */
.csp-popup {
  position: fixed;
  z-index: 3000;
  min-width: 320px;
  max-width: 520px;
  background: var(--code-modal-bg);
  border: 1px solid var(--code-header-bd);
  border-radius: 6px;
  box-shadow: 0 8px 32px rgba(0,0,0,0.35), 0 2px 8px rgba(0,0,0,0.2);
  font-family: 'Consolas','Cascadia Code','JetBrains Mono','Fira Code', monospace;
  font-size: 12px;
  overflow: hidden;
  animation: cspFadeIn 0.12s ease-out;
}

@keyframes cspFadeIn {
  from { opacity: 0; transform: translateY(-4px); }
  to   { opacity: 1; transform: translateY(0); }
}

.csp-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 10px 7px;
  background: var(--code-header-bg);
  border-bottom: 1px solid var(--code-header-bd);
  user-select: none;
}

.csp-title {
  display: flex;
  align-items: center;
  gap: 5px;
  color: var(--code-fn);
  font-weight: 600;
  flex: 1;
  min-width: 0;
}

.csp-method-name { color: var(--code-fn); }
.csp-paren { color: var(--code-text); opacity: 0.5; }

.csp-count {
  font-size: 11px;
  color: var(--code-ln);
  background: var(--code-internal-bg);
  padding: 2px 7px;
  border-radius: 10px;
  white-space: nowrap;
  flex-shrink: 0;
}

.csp-close {
  width: 20px; height: 20px;
  display: flex; align-items: center; justify-content: center;
  background: transparent; border: none;
  border-radius: 3px; cursor: pointer;
  color: var(--code-ln); transition: all 0.12s;
  flex-shrink: 0; padding: 0;
}
.csp-close:hover { background: var(--code-copy-hover); color: var(--code-text); }

.csp-file-row {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px;
  color: var(--code-ln);
  background: var(--code-header-bg);
  border-bottom: 1px solid var(--code-header-bd);
  font-size: 11px;
}

.csp-filename { color: var(--code-annotation); font-size: 11px; }

.csp-item {
  display: flex;
  align-items: baseline;
  gap: 0;
  padding: 0;
  cursor: pointer;
  transition: background 0.1s;
  border-bottom: 1px solid var(--code-header-bd);
}
.csp-item:last-child { border-bottom: none; }
.csp-item:hover { background: var(--code-internal-bg); }

.csp-line-badge {
  flex-shrink: 0;
  min-width: 40px;
  padding: 6px 8px 6px 10px;
  text-align: right;
  color: var(--code-ln);
  font-size: 11px;
  background: var(--code-header-bg);
  border-right: 1px solid var(--code-header-bd);
  user-select: none;
  line-height: 1.5;
}

.csp-preview {
  flex: 1;
  padding: 6px 12px;
  color: var(--code-text);
  white-space: pre;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 12px;
  line-height: 1.5;
}

.csp-item:hover .csp-preview { color: var(--code-internal); }

.csp-empty {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 12px 14px;
  color: var(--code-ln);
  font-size: 11px;
  font-family: inherit;
}
</style>
