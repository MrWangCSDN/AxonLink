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
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import loader from '@monaco-editor/loader'

// ── Props / Emits ────────────────────────────────────────────────────────────
const props = defineProps({
  node:          { type: Object,  default: null },
  filePath:      { type: String,  default: '' },
  methodName:    { type: String,  default: '' },
  isDark:        { type: Boolean, default: true },
  parentLoading: { type: Boolean, default: false },
  // 父组件用 v-show 控制可见性时传入，visible 由 false→true 时触发 editor.layout()
  visible:       { type: Boolean, default: true },
})
const emit = defineEmits(['close', 'fullscreen-change'])

// ── 状态 ─────────────────────────────────────────────────────────────────────
const editorContainer = ref(null)
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
const isLoading    = ref(false)
const isFullscreen = ref(false)

function toggleFullscreen() {
  isFullscreen.value = !isFullscreen.value
  emit('fullscreen-change', isFullscreen.value)
  nextTick(() => editor?.layout())
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
  revealMethod(entryMethod.value)
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

// ── v-show 显示后强制重新测量布局（Monaco 在 display:none 时无法计算尺寸） ──
watch(() => props.visible, async (v) => {
  if (v && editor) {
    await nextTick()
    editor.layout()
  }
  // 关闭弹窗时退出全屏
  if (!v && isFullscreen.value) {
    isFullscreen.value = false
    emit('fullscreen-change', false)
  }
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
