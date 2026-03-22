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
        <button v-if="entryMethod" class="mcv-btn" :title="`定位到 ${entryMethod}()`" @click="revealMethod(entryMethod)">
          <svg width="13" height="13" viewBox="0 0 13 13" fill="none">
            <circle cx="6.5" cy="6.5" r="5.5" stroke="currentColor" stroke-width="1.2"/>
            <circle cx="6.5" cy="6.5" r="2"   stroke="currentColor" stroke-width="1.2"/>
            <path d="M6.5 1v1.5M6.5 10.5V12M1 6.5h1.5M10.5 6.5H12" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"/>
          </svg>
        </button>
        <!-- 重新索引按钮：编译后点击，无需重启服务 -->
        <button class="mcv-btn" :class="{ 'mcv-refreshing': indexRefreshing }"
                :title="indexRefreshing ? '正在重建索引…' : '重新索引（编译后刷新）'"
                @click="refreshIndex">
          <svg width="13" height="13" viewBox="0 0 13 13" fill="none"
               :style="indexRefreshing ? 'animation:spin 1s linear infinite' : ''">
            <path d="M11 6.5A4.5 4.5 0 1 1 6.5 2a4.5 4.5 0 0 1 3.18 1.32" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
            <path d="M9.5 1v2.5H7"   stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </button>
        <button class="mcv-btn mcv-close" @click="$emit('close')">
          <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
            <path d="M2 2l10 10M12 2L2 12" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/>
          </svg>
        </button>
      </div>
    </div>

    <!-- 文件标签栏 -->
    <div class="mcv-tab-bar" ref="tabBarRef">
      <div
        v-for="(tab, idx) in openTabs"
        :key="tab.uri"
        class="mcv-tab"
        :class="{ active: idx === activeIdx }"
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

    <!-- 状态消息 -->
    <div v-if="statusMsg" class="mcv-status">{{ statusMsg }}</div>

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
</template>

<script setup>
import { ref, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import loader from '@monaco-editor/loader'

// ── Props / Emits ────────────────────────────────────────────────────────────
const props = defineProps({
  node:       { type: Object,  default: null },
  filePath:   { type: String,  default: '' },
  methodName: { type: String,  default: '' },
  isDark:     { type: Boolean, default: true },
  parentLoading: { type: Boolean, default: false },
})
const emit = defineEmits(['close'])

// ── 状态 ─────────────────────────────────────────────────────────────────────
const editorContainer = ref(null)
const tabBarRef       = ref(null)
const statusMsg       = ref('')
const openTabs        = ref([])
const activeIdx       = ref(0)
const entryMethod     = ref('')
const isLoading       = ref(false)
const indexRefreshing = ref(false)

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

// ── 初始化 Monaco ────────────────────────────────────────────────────────────
async function initMonaco() {
  if (initialized) return
  initialized = true

  monaco = await loader.init()

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
        // 跨文件方法调用：占位（onMouseDown 完成真正跳转）
        const cls = (tab.typeMap || {})[objName] || (/^[A-Z]/.test(objName) ? objName : '')
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

      const className = (tab.typeMap || {})[objName] || (/^[A-Z]/.test(objName) ? objName : '')
      if (!className) { setStatus('暂无源码，如需查看请至 IDE 中查看'); return }
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
async function loadFile(filePath, methodName = '') {
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

    entryMethod.value = methodName || ''

    const fileUri = window.__monacoFileUri(data.filePath)
    let   model   = monaco.editor.getModel(fileUri)
    if (!model)   model = monaco.editor.createModel(data.source, 'java', fileUri)

    openTabs.value = [{
      uri:      fileUri.toString(),
      filename: data.filename,
      kind:     data.kind || null,
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

    if (methodName) await nextTick(() => revealMethod(methodName))
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
    if (!targetModel) targetModel = monaco.editor.createModel(data.source, 'java', fileUri)

    const existIdx = openTabs.value.findIndex(t => t.uri === fileUriStr)
    if (existIdx < 0) {
      openTabs.value.push({
        uri:      fileUriStr,
        filename: data.filename,
        kind:     data.kind || 'class',
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

// ── 工具 ──────────────────────────────────────────────────────────────────────
const makeLine = (line) => ({ startLineNumber: line, startColumn: 1, endLineNumber: line, endColumn: 1 })

let statusTimer = null
// ── 热重载索引（编译后无需重启服务）──────────────────────────────────────────
async function refreshIndex() {
  if (indexRefreshing.value) return
  indexRefreshing.value = true
  setStatus('正在重建索引…')
  try {
    const res  = await fetch('/api/source/index/refresh', { method: 'POST' })
    const data = await res.json()
    setStatus(`索引完成：收录 ${data.classCount} 个类，耗时 ${data.elapsedMs} ms`)
  } catch {
    setStatus('索引重建失败，请检查服务日志')
  } finally {
    indexRefreshing.value = false
  }
}

function setStatus(msg) {
  statusMsg.value = msg
  clearTimeout(statusTimer)
  if (msg) statusTimer = setTimeout(() => { statusMsg.value = '' }, 4000)
}

// ── 生命周期 ──────────────────────────────────────────────────────────────────
onMounted(async () => {
  if (!props.filePath) return   // 无源码配置，不需要初始化编辑器
  await initMonaco()
  await loadFile(props.filePath, props.methodName)
})

onBeforeUnmount(() => {
  providerDisp?.dispose()
  editor?.dispose()
})

// ── 响应 filePath 变化（父组件切换节点） ─────────────────────────────────────
watch(() => [props.filePath, props.methodName], async ([fp, mn]) => {
  if (!fp) return
  if (!monaco) { await initMonaco() }
  await loadFile(fp, mn)
}, { immediate: false })

// ── 主题切换 ──────────────────────────────────────────────────────────────────
watch(() => props.isDark, dark => {
  if (monaco && editor) monaco.editor.setTheme(dark ? DARK_THEME : LIGHT_THEME)
})

defineExpose({ loadFile, revealMethod })
</script>

<style scoped>
.mcv-root {
  display: flex; flex-direction: column;
  width: 100%; height: 100%;
  background: var(--code-modal-bg, #1e1e1e);
  border-radius: 10px; overflow: hidden;
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

/* ── Tab bar ── */
.mcv-tab-bar {
  display: flex; flex-shrink: 0; overflow-x: auto; overflow-y: hidden;
  background: var(--code-header-bg, #2d2d2d);
  border-bottom: 1px solid var(--code-header-bd, #404040);
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
  padding: 5px 14px; font-size: 12px; flex-shrink: 0;
  background: var(--code-header-bg, #2d2d2d);
  border-bottom: 1px solid rgba(78,201,176,0.25);
  color: #4EC9B0;
}

/* ── 编辑器 ── */
.mcv-editor { flex: 1; overflow: hidden; min-height: 0; }
.mcv-editor-hidden { visibility: hidden; pointer-events: none; }

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
