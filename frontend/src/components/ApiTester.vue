<template>
  <Teleport to="body">
    <div v-if="visible" class="at-backdrop" :class="{ 'at-fullscreen-mode': isFullscreen }" @click.self="!isFullscreen && close()">
      <div class="at-panel" :class="{ 'at-dark': isDark, 'at-panel-fullscreen': isFullscreen }">

        <!-- ── 标题栏 ── -->
        <div class="at-header">
          <div class="at-header-left">
            <svg class="at-logo-icon" width="16" height="16" viewBox="0 0 16 16" fill="none">
              <path d="M2 4h12M2 8h8M2 12h5" stroke="#4F7CFF" stroke-width="1.6" stroke-linecap="round"/>
              <circle cx="13" cy="12" r="2.5" stroke="#4F7CFF" stroke-width="1.5"/>
              <path d="M13 10V9M13 15v-1" stroke="#4F7CFF" stroke-width="1.4" stroke-linecap="round"/>
            </svg>
            <span class="at-title">API 测试</span>
            <span class="at-tx-code">{{ txCode }}</span>
            <span class="at-tx-name">{{ txName }}</span>
          </div>
          <div class="at-header-btns">
            <button class="at-icon-btn" :title="isFullscreen ? '退出全屏' : '全屏展示'" @click="isFullscreen = !isFullscreen">
              <svg v-if="!isFullscreen" width="15" height="15" viewBox="0 0 15 15" fill="none">
                <path d="M1.5 5.5V2H5M10 2h3.5V5.5M13.5 9.5V13H10M5 13H1.5V9.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              <svg v-else width="15" height="15" viewBox="0 0 15 15" fill="none">
                <path d="M5 1.5V5H1.5M13.5 5H10V1.5M10 13.5V10h3.5M1.5 10H5v3.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </button>
            <button class="at-icon-btn at-close" @click="close">
              <svg width="15" height="15" viewBox="0 0 15 15" fill="none">
                <path d="M2.5 2.5l10 10M12.5 2.5l-10 10" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/>
              </svg>
            </button>
          </div>
        </div>

        <!-- ── 请求栏 ── -->
        <div class="at-request-bar">
          <select v-model="method" class="at-method-select" :class="`method-${method.toLowerCase()}`">
            <option value="GET">GET</option>
            <option value="POST">POST</option>
          </select>
          <input
            v-model="url"
            class="at-url-input"
            type="text"
            placeholder="请输入请求 URL，如 http://localhost:8080/api/loan/apply"
            @keyup.enter="sendRequest"
          />
          <button class="at-send-btn" :class="{ loading: isSending }" :disabled="isSending" @click="sendRequest">
            <svg v-if="!isSending" width="14" height="14" viewBox="0 0 14 14" fill="none">
              <path d="M2 7h10M8 3l4 4-4 4" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
            <svg v-else class="spin-icon" width="14" height="14" viewBox="0 0 14 14" fill="none">
              <circle cx="7" cy="7" r="5" stroke="currentColor" stroke-opacity="0.3" stroke-width="2"/>
              <path d="M7 2a5 5 0 0 1 5 5" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
            </svg>
            {{ isSending ? '发送中' : '发送' }}
          </button>
        </div>

        <!-- ── 可拖拽上下分区 ── -->
        <div ref="splitRef" class="at-split">

          <!-- 请求区 -->
          <div class="at-req-section" :style="{ height: reqPanelHeight + 'px' }">

            <!-- 选项卡 -->
            <div class="at-tabs">
              <button class="at-tab" :class="{ active: activeTab === 'headers' }" @click="activeTab = 'headers'">
                请求头
                <span v-if="activeHeaders.length" class="at-tab-badge">{{ activeHeaders.length }}</span>
              </button>
              <button class="at-tab" :class="{ active: activeTab === 'body' }" @click="activeTab = 'body'">
                Body
                <span v-if="method === 'POST' && bodyText.trim()" class="at-tab-dot" />
              </button>
            </div>

            <!-- Headers 面板 -->
            <div v-show="activeTab === 'headers'" class="at-tab-panel">
              <div class="at-kv-toolbar">
                <span class="at-kv-toolbar-label">
                  Headers
                  <span v-if="activeHeaders.length" class="at-kv-count">{{ activeHeaders.length }} 条已启用</span>
                </span>
                <button class="at-bulk-toggle" :class="{ active: bulkEdit }" @click="toggleBulkEdit">
                  <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
                    <path d="M1 2.5h10M1 6h7M1 9.5h5" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
                  </svg>
                  {{ bulkEdit ? '表格编辑' : 'Bulk Edit' }}
                </button>
              </div>

              <template v-if="!bulkEdit">
                <div class="at-kv-table">
                  <div class="at-kv-head">
                    <span class="kv-col-check" />
                    <span class="kv-col-key">Key</span>
                    <span class="kv-col-val">Value</span>
                    <span class="kv-col-del" />
                  </div>
                  <div v-for="(row, idx) in headers" :key="idx" class="at-kv-row">
                    <label class="kv-check-wrap">
                      <input type="checkbox" v-model="row.enabled" class="kv-check" />
                    </label>
                    <input v-model="row.key" class="kv-input" placeholder="Header 名" @input="ensureLastRow(headers)" />
                    <input v-model="row.value" class="kv-input" placeholder="值" @input="ensureLastRow(headers)" />
                    <button class="kv-del" :disabled="headers.length === 1" @click="removeRow(headers, idx)">
                      <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
                        <path d="M2 2l8 8M10 2l-8 8" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/>
                      </svg>
                    </button>
                  </div>
                </div>
                <button class="at-add-row" @click="addRow(headers)">
                  <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
                    <path d="M6 2v8M2 6h8" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
                  </svg>
                  添加请求头
                </button>
              </template>

              <template v-else>
                <div class="at-bulk-hint">每行一条，格式：<code>Key: Value</code>，以 <code>#</code> 开头的行为注释（禁用）</div>
                <textarea
                  v-model="bulkText"
                  class="at-bulk-editor"
                  placeholder="Content-Type: application/json&#10;Authorization: Bearer token&#10;# X-Custom-Header: value"
                  spellcheck="false"
                  @keydown.tab.prevent="insertBulkTab"
                />
                <div class="at-bulk-footer">
                  <span class="at-bulk-preview">解析到 <em>{{ bulkParsedCount }}</em> 条有效请求头</span>
                  <button class="at-body-action-btn" @click="applyBulk">
                    <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
                      <path d="M2 6l3 3 5-5" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>
                    </svg>
                    应用
                  </button>
                </div>
              </template>
            </div>

            <!-- Body 面板 -->
            <div v-show="activeTab === 'body'" class="at-tab-panel">
              <div class="at-body-toolbar">
                <span class="at-body-type">JSON</span>
                <div class="at-body-actions">
                  <button class="at-body-action-btn" @click="loadMockBody" title="填入贷款申请 Mock 数据（100字段）">
                    <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
                      <path d="M6 1.5v5M3 4.5L6 7.5l3-3" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/>
                      <path d="M1.5 9h9" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
                    </svg>
                    Mock 数据
                  </button>
                  <button class="at-body-action-btn" @click="formatJson" title="格式化 JSON">
                    <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
                      <path d="M1.5 3h9M1.5 6h6M1.5 9h7.5" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
                    </svg>
                    格式化
                  </button>
                  <button class="at-body-action-btn" @click="clearBody" title="清空">
                    <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
                      <path d="M2 2l8 8M10 2l-8 8" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
                    </svg>
                    清空
                  </button>
                </div>
              </div>
              <textarea
                v-model="bodyText"
                class="at-body-editor"
                placeholder='{\n  "key": "value"\n}'
                spellcheck="false"
                @keydown.tab.prevent="insertTab"
              />
              <p v-if="jsonError" class="at-json-error">{{ jsonError }}</p>
            </div>

          </div><!-- /at-req-section -->

          <!-- ── 拖拽分割条 ── -->
          <div class="at-divider" @mousedown.prevent="startDividerDrag">
            <div class="at-divider-bar" />
            <div class="at-divider-handle">
              <svg width="20" height="6" viewBox="0 0 20 6" fill="none">
                <circle cx="4" cy="3" r="1.2" fill="currentColor"/>
                <circle cx="10" cy="3" r="1.2" fill="currentColor"/>
                <circle cx="16" cy="3" r="1.2" fill="currentColor"/>
              </svg>
            </div>
          </div>

          <!-- ── 响应区域 ── -->
          <div class="at-response">
            <div class="at-response-header">
              <span class="at-response-title">响应</span>
              <template v-if="response">
                <span class="at-status-badge" :class="statusClass">{{ response.status }} {{ response.statusText }}</span>
                <span class="at-response-time">{{ response.time }}ms</span>
                <span class="at-response-size">{{ response.size }}</span>
                <button class="at-copy-btn" @click="copyResponse">
                  <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
                    <rect x="3.5" y="3.5" width="7" height="7" rx="1" stroke="currentColor" stroke-width="1.2"/>
                    <path d="M1.5 8.5V2a.5.5 0 0 1 .5-.5h6.5" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"/>
                  </svg>
                  {{ copied ? '已复制' : '复制' }}
                </button>
              </template>
              <button v-if="!response" class="at-mock-resp-btn" @click="loadMockResponse">
                <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
                  <path d="M6 1.5v5M3 4.5L6 7.5l3-3" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/>
                  <path d="M1.5 9h9" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
                </svg>
                加载 Mock 响应
              </button>
              <button v-else class="at-mock-resp-btn" @click="loadMockResponse">
                <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
                  <path d="M2 6a4 4 0 1 1 4 4" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
                  <path d="M2 4v2h2" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
                重置 Mock
              </button>
            </div>

            <div v-if="!response && !requestError" class="at-response-empty">
              <svg width="40" height="40" viewBox="0 0 40 40" fill="none">
                <circle cx="20" cy="20" r="18" stroke="var(--at-border)" stroke-width="1.5"/>
                <path d="M13 20h14M20 13v14" stroke="var(--at-border)" stroke-width="1.5" stroke-linecap="round"/>
              </svg>
              <p>点击发送后在此查看响应</p>
              <span>或点击「加载 Mock 响应」预览效果</span>
            </div>

            <div v-else-if="requestError" class="at-response-error">
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
                <circle cx="8" cy="8" r="6.5" stroke="#FF4D4F" stroke-width="1.3"/>
                <path d="M8 5v4M8 10.5v.5" stroke="#FF4D4F" stroke-width="1.5" stroke-linecap="round"/>
              </svg>
              {{ requestError }}
            </div>

            <div v-else-if="response" class="at-response-body-wrap">
              <div class="at-response-tabs">
                <button class="at-resp-tab" :class="{ active: respTab === 'pretty' }" @click="respTab = 'pretty'">Body</button>
                <button class="at-resp-tab" :class="{ active: respTab === 'headers' }" @click="respTab = 'headers'">
                  响应头
                  <span v-if="response.headers && response.headers.length" class="at-tab-badge" style="margin-left:4px;">{{ response.headers.length }}</span>
                </button>
              </div>

              <pre v-if="respTab === 'pretty'" class="at-response-body" v-html="prettyResponse" />

              <div v-else-if="respTab === 'headers'" class="at-resp-headers">
                <div class="at-resp-hd-head"><span>Key</span><span>Value</span></div>
                <div v-for="(h, idx) in response.headers" :key="idx" class="at-resp-hd-row">
                  <span class="at-resp-hd-key">{{ h.key }}</span>
                  <span class="at-resp-hd-val">{{ h.value }}</span>
                </div>
                <div v-if="!response.headers || !response.headers.length" class="at-resp-hd-empty">暂无响应头信息</div>
              </div>
            </div>
          </div>

        </div><!-- /at-split -->

      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { ref, computed, watch, onBeforeUnmount } from 'vue'

const props = defineProps({
  visible:  { type: Boolean, default: false },
  txCode:   { type: String,  default: '' },
  txName:   { type: String,  default: '' },
  isDark:   { type: Boolean, default: false },
})

const emit = defineEmits(['close'])

const method    = ref('POST')
const url       = ref('')
const activeTab = ref('body')
const bodyText  = ref('{\n  \n}')
const jsonError = ref('')
const isFullscreen = ref(false)
const isSending = ref(false)
const response  = ref(null)
const requestError = ref('')
const respTab   = ref('pretty')
const copied    = ref(false)

// ── 拖拽分割 ──
const splitRef       = ref(null)
const reqPanelHeight = ref(260)
const MIN_REQ  = 88
const MIN_RESP = 120
let dragStartY     = 0
let dragStartHeight = 0

const startDividerDrag = (e) => {
  dragStartY      = e.clientY
  dragStartHeight = reqPanelHeight.value
  document.addEventListener('mousemove', onDividerMove)
  document.addEventListener('mouseup',   endDividerDrag)
  document.body.style.cursor    = 'row-resize'
  document.body.style.userSelect = 'none'
}
const onDividerMove = (e) => {
  if (!splitRef.value) return
  const splitH   = splitRef.value.getBoundingClientRect().height
  const maxReq   = splitH - 10 - MIN_RESP
  const newH     = dragStartHeight + (e.clientY - dragStartY)
  reqPanelHeight.value = Math.max(MIN_REQ, Math.min(maxReq, newH))
}
const endDividerDrag = () => {
  document.removeEventListener('mousemove', onDividerMove)
  document.removeEventListener('mouseup',   endDividerDrag)
  document.body.style.cursor    = ''
  document.body.style.userSelect = ''
}
onBeforeUnmount(() => endDividerDrag())

// ── Headers 行 ──
const makeRow = () => ({ enabled: true, key: '', value: '' })
const headers = ref([makeRow()])
const activeHeaders = computed(() => headers.value.filter(r => r.enabled && r.key.trim()))
const addRow  = (list) => list.push(makeRow())
const removeRow = (list, idx) => { if (list.length > 1) list.splice(idx, 1) }
const ensureLastRow = (list) => {
  const last = list[list.length - 1]
  if (last.key.trim() || last.value.trim()) list.push(makeRow())
}

// ── Bulk Edit ──
const bulkEdit = ref(false)
const bulkText = ref('')
const headersToBulk = () =>
  headers.value.filter(r => r.key.trim())
    .map(r => (r.enabled ? '' : '# ') + r.key.trim() + ': ' + r.value)
    .join('\n')
const parseBulk = (text) =>
  text.split('\n').map(l => l.trimEnd()).filter(l => l !== '')
    .map(line => {
      const disabled = line.startsWith('#')
      const clean = disabled ? line.slice(1).trim() : line
      const colon = clean.indexOf(':')
      return colon === -1
        ? { enabled: !disabled, key: clean.trim(), value: '' }
        : { enabled: !disabled, key: clean.slice(0, colon).trim(), value: clean.slice(colon + 1).trim() }
    }).filter(r => r.key)
const bulkParsedCount = computed(() => parseBulk(bulkText.value).filter(r => r.enabled).length)
const toggleBulkEdit = () => {
  if (!bulkEdit.value) bulkText.value = headersToBulk()
  else applyBulk()
  bulkEdit.value = !bulkEdit.value
}
const applyBulk = () => {
  const rows = parseBulk(bulkText.value)
  headers.value = rows.length ? [...rows, makeRow()] : [makeRow()]
}
const insertBulkTab = (e) => {
  const ta = e.target, s = ta.selectionStart, end = ta.selectionEnd
  bulkText.value = bulkText.value.slice(0, s) + '  ' + bulkText.value.slice(end)
  ta.setSelectionRange(s + 2, s + 2)
}

// ── Body 工具 ──
const formatJson = () => {
  try { bodyText.value = JSON.stringify(JSON.parse(bodyText.value), null, 2); jsonError.value = '' }
  catch { jsonError.value = 'JSON 格式有误，无法格式化' }
}
const clearBody = () => { bodyText.value = '' }
const insertTab = (e) => {
  const ta = e.target, s = ta.selectionStart, end = ta.selectionEnd
  bodyText.value = bodyText.value.slice(0, s) + '  ' + bodyText.value.slice(end)
  ta.setSelectionRange(s + 2, s + 2)
}
watch(bodyText, () => { jsonError.value = '' })

// ── Mock 数据 ──
const MOCK_REQUEST = {
  txCode: "TD0101", txName: "贷款申请提交", channelCode: "APP", channelName: "手机银行",
  requestTime: "2026-03-22T10:00:00", requestNo: "REQ202603221000001", version: "1.0.0", clientIp: "192.168.1.100",
  customerId: "CUS202603220001", customerName: "张三", customerType: "INDIVIDUAL",
  idType: "01", idNo: "110101199001011234", idExpiryDate: "2030-01-01",
  mobile: "13800138000", email: "zhangsan@example.com", gender: "M", birthday: "1990-01-01", nationality: "CN",
  loanProduct: "CONSUMER_LOAN", loanProductName: "个人消费贷款",
  loanPurpose: "PERSONAL_CONSUMPTION", loanPurposeDesc: "个人消费",
  applyAmount: 50000.00, currency: "CNY", loanTerm: 12, termUnit: "MONTH",
  repayMethod: "EQUAL_INSTALLMENT", expectedRate: 0.0436,
  gracePeriod: 0, firstRepayDate: "2026-05-22",
  applyBranch: "110100", applyBranchName: "北京市分行", applyStaff: "STAFF001",
  annualIncome: 120000.00, incomeSource: "SALARY", incomeProofType: "PAYSLIP",
  employerName: "北京某科技有限公司", employerType: "PRIVATE", workYears: 5,
  monthlyNetIncome: 8500.00, otherIncome: 2000.00,
  totalAssets: 800000.00, realEstate: 600000.00, vehicle: 200000.00,
  financialAssets: 50000.00, liabilityTotal: 100000.00, debtRatio: 0.12,
  residenceType: "OWNER", residenceAddress: "北京市朝阳区某某路1号",
  residenceCity: "110100", residenceCityName: "北京市",
  residenceDistrict: "110105", residenceDistrictName: "朝阳区",
  residenceZip: "100020", residenceSince: "2015-06-01",
  permanentAddress: "北京市朝阳区某某路1号", isResidenceSameAsPermanent: true,
  emergencyContactName: "李四", emergencyContactRelation: "SPOUSE", emergencyContactMobile: "13900139000",
  guarantorName: "王五", guarantorIdNo: "110101198501011234",
  guarantorMobile: "13700137000", guarantorRelation: "PARENT", guarantorAnnualIncome: 100000.00,
  settlementAccountNo: "6222020200000001", settlementBankCode: "ICBC", settlementBankName: "中国工商银行",
  settlementAccountName: "张三", repayAccountNo: "6222020200000001",
  repayBankCode: "ICBC", repayBankName: "中国工商银行", autoRepay: true,
  creditScore: 720, creditGrade: "A", blacklistCheck: false, antifraudScore: 95,
  debtOverdueCount: 0, maxOverdueDays: 0, creditCardCount: 2,
  creditCardLimit: 50000.00, creditCardBalance: 8000.00,
  existingLoansCount: 0, existingLoansBalance: 0.00, loanApplyCount30d: 1,
  attachmentList: ["ID_FRONT.jpg", "ID_BACK.jpg", "INCOME_PROOF.pdf"],
  agreementCode: "AGR_CONSUMER_V1", agreementVersion: "1.0",
  agreementSignTime: "2026-03-22T09:58:00", agreementSignIp: "192.168.1.100",
  isAgreePrivacyPolicy: true, isAgreeMarketingInfo: false, dataEncryption: "AES256",
  extField1: "EXT001", extField2: "2026", extField3: "ONLINE",
  extField4: "NORMAL", extField5: "CAMPAIGN_2026Q1", remark: "客户申请消费贷款，资质良好"
}

const MOCK_RESPONSE_DATA = {
  respCode: "000000", respMsg: "贷款申请受理成功", txCode: "TD0101", txName: "贷款申请提交",
  requestNo: "REQ202603221000001", responseNo: "RSP202603221000001",
  responseTime: "2026-03-22T10:00:03", processTime: 2847,
  approvalStatus: "APPROVED", approvalStatusName: "已审批通过",
  approvalTime: "2026-03-22T10:00:02", approvalStaff: "SYS_AUTO",
  approvalBranch: "110100", approvalBranchName: "北京市分行",
  rejectionReason: null, approvalRemark: "系统自动审批通过，信用评分达标",
  reviewLevel: "AUTO", reviewResult: "PASS",
  loanNo: "LOAN202603220001", loanContractNo: "CONTRACT202603220001",
  loanContractDate: "2026-03-22", actualAmount: 50000.00, actualRate: 0.0436,
  actualTerm: 12, actualTermUnit: "MONTH", actualRepayMethod: "EQUAL_INSTALLMENT",
  firstRepayDate: "2026-05-22", lastRepayDate: "2027-04-22",
  totalRepayAmount: 51191.40, totalInterest: 1191.40,
  monthlyPayment: 4265.95, principalPerMonth: 4166.67, interestPerMonth: 99.28,
  feePerMonth: 0.00, totalFee: 0.00, prepaymentFee: 0.01,
  lateFeeRate: 0.00050, graceDays: 3,
  disbursementStatus: "PENDING", expectedDisbDate: "2026-03-24",
  disbAccountNo: "6222020200000001", disbBankCode: "ICBC",
  disbBankName: "中国工商银行", disbAccountName: "张三",
  disbChannelCode: "INTERBANK", disbRemark: "预计 T+2 放款",
  creditLimit: 100000.00, availableLimit: 50000.00,
  riskLevel: "LOW", nextStep: "等待放款，如需咨询请联系客服 400-800-0000"
}

const MOCK_HEADERS = [
  { key: 'content-type',                value: 'application/json;charset=UTF-8' },
  { key: 'transfer-encoding',           value: 'chunked' },
  { key: 'connection',                  value: 'keep-alive' },
  { key: 'date',                        value: new Date().toUTCString() },
  { key: 'server',                      value: 'AxonLink/1.0 (Spring Boot 3.1)' },
  { key: 'x-request-id',               value: 'REQ202603221000001' },
  { key: 'x-response-id',              value: 'RSP202603221000001' },
  { key: 'x-process-time',             value: '2847' },
  { key: 'x-rate-limit-limit',         value: '100' },
  { key: 'x-rate-limit-remaining',     value: '99' },
  { key: 'x-rate-limit-reset',         value: '1711101600' },
  { key: 'cache-control',              value: 'no-cache, no-store, must-revalidate' },
  { key: 'pragma',                     value: 'no-cache' },
  { key: 'expires',                    value: '0' },
  { key: 'vary',                       value: 'Accept-Encoding' },
  { key: 'access-control-allow-origin', value: '*' },
]

const loadMockBody = () => {
  bodyText.value = JSON.stringify(MOCK_REQUEST, null, 2)
  jsonError.value = ''
  activeTab.value = 'body'
}

const loadMockResponse = () => {
  const body = JSON.stringify(MOCK_RESPONSE_DATA, null, 2)
  response.value = {
    status: 200, statusText: 'OK', time: 2847,
    size: body.length < 1024 ? body.length + ' B' : (body.length / 1024).toFixed(1) + ' KB',
    body, raw: body, headers: MOCK_HEADERS, isMock: true
  }
  requestError.value = ''
}

// ── 发送请求 ──
const sendRequest = async () => {
  if (!url.value.trim()) { requestError.value = '请输入请求 URL'; return }
  if (method.value === 'POST' && bodyText.value.trim()) {
    try { JSON.parse(bodyText.value) } catch {
      jsonError.value = 'Body 中 JSON 格式有误，请修正后再发送'
      activeTab.value = 'body'; return
    }
  }
  isSending.value = true; response.value = null; requestError.value = ''
  const t0 = Date.now()
  try {
    const reqHeaders = { 'Content-Type': 'application/json' }
    activeHeaders.value.forEach(r => { if (r.key.trim()) reqHeaders[r.key.trim()] = r.value })
    const opts = { method: method.value, headers: reqHeaders }
    if (method.value === 'POST' && bodyText.value.trim()) opts.body = bodyText.value
    const res = await fetch(url.value, opts)
    const elapsed = Date.now() - t0
    const rawBody = await res.text()
    let bodyDisplay = rawBody
    try { bodyDisplay = JSON.stringify(JSON.parse(rawBody), null, 2) } catch { /* keep raw */ }
    const sizeStr = rawBody.length < 1024 ? rawBody.length + ' B' : (rawBody.length / 1024).toFixed(1) + ' KB'
    response.value = {
      status: res.status, statusText: res.statusText || statusTextFor(res.status),
      time: elapsed, size: sizeStr, body: bodyDisplay, raw: rawBody,
      headers: headersToList(res.headers)
    }
  } catch (err) {
    requestError.value = err.name === 'TypeError' && err.message.includes('fetch')
      ? `无法连接到服务器：${err.message}（请确认 URL 可访问，并注意跨域限制）`
      : err.message
  } finally { isSending.value = false }
}

const statusTextFor = (c) => ({ 200:'OK',201:'Created',204:'No Content',400:'Bad Request',401:'Unauthorized',403:'Forbidden',404:'Not Found',500:'Internal Server Error',502:'Bad Gateway',503:'Service Unavailable' }[c] || '')

const statusClass = computed(() => {
  if (!response.value) return ''
  const s = response.value.status
  return s >= 200 && s < 300 ? 'status-2xx' : s >= 300 && s < 400 ? 'status-3xx' : s >= 400 && s < 500 ? 'status-4xx' : 'status-5xx'
})

const headersToList = (hdrs) => {
  const list = []
  if (!hdrs) return list
  if (typeof hdrs.entries === 'function') {
    for (const [k, v] of hdrs.entries()) list.push({ key: k, value: v })
  } else if (typeof hdrs === 'object') {
    Object.entries(hdrs).forEach(([k, v]) => list.push({ key: k, value: v }))
  }
  return list
}

const escHtml = s => s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')
const syntaxHighlight = (json) => escHtml(json).replace(
  /("(\\u[\dA-Fa-f]{4}|\\[^u]|[^"\\])*"(\s*:)?|\b(true|false|null)\b|-?\d+(?:\.\d*)?(?:[eE][+-]?\d+)?)/g,
  m => /^"/.test(m)
    ? (/:$/.test(m) ? `<span class="json-key">${m}</span>` : `<span class="json-str">${m}</span>`)
    : /true|false/.test(m) ? `<span class="json-bool">${m}</span>`
    : /null/.test(m) ? `<span class="json-null">${m}</span>`
    : `<span class="json-num">${m}</span>`
)

const prettyResponse = computed(() => {
  if (!response.value) return ''
  try { return syntaxHighlight(JSON.stringify(JSON.parse(response.value.body), null, 2)) }
  catch { return escHtml(response.value.body) }
})

const copyResponse = async () => {
  if (!response.value) return
  await navigator.clipboard.writeText(response.value.raw)
  copied.value = true
  setTimeout(() => { copied.value = false }, 1500)
}

const close = () => { emit('close') }

watch(() => props.visible, (v) => {
  if (v) {
    response.value     = null
    requestError.value = ''
    jsonError.value    = ''
    copied.value       = false
    isFullscreen.value = false
    bulkEdit.value     = false
    bulkText.value     = ''
    reqPanelHeight.value = 260
    respTab.value      = 'pretty'
  }
})
</script>

<style scoped>
.at-panel {
  --at-bg:          #FFFFFF;
  --at-bg-sub:      #F8FAFF;
  --at-bg-tab:      #F3F6FB;
  --at-bg-input:    #FFFFFF;
  --at-bg-code:     #F4F7FF;
  --at-border:      #E4E9F2;
  --at-border-sub:  #DDE4EF;
  --at-text:        #1F2937;
  --at-text-sub:    #4B5563;
  --at-text-faint:  #9CA3AF;
  --at-shadow:      0 24px 64px rgba(0,0,0,0.14);
  --at-kv-head-bg:  #F3F6FB;
  --at-divider-bg:  #EDF1F8;
  --at-divider-hover: #C5D5FF;
}
.at-dark {
  --at-bg:          #1E2130;
  --at-bg-sub:      #252840;
  --at-bg-tab:      #1A1D2D;
  --at-bg-input:    #252840;
  --at-bg-code:     #161826;
  --at-border:      #2E3348;
  --at-border-sub:  #363B55;
  --at-text:        #E5E9F0;
  --at-text-sub:    #9BA8BE;
  --at-text-faint:  #5A6280;
  --at-shadow:      0 24px 64px rgba(0,0,0,0.5);
  --at-kv-head-bg:  #1A1D2D;
  --at-divider-bg:  #1A1D2D;
  --at-divider-hover: #3A4A7A;
}

.at-backdrop {
  position: fixed; inset: 0; background: rgba(0,0,0,0.45);
  display: flex; align-items: center; justify-content: center;
  z-index: 3000; padding: 20px; backdrop-filter: blur(2px); transition: padding 0.2s;
}
.at-fullscreen-mode { padding: 0; background: rgba(0,0,0,0.6); }

.at-panel {
  background: var(--at-bg); border-radius: 14px; box-shadow: var(--at-shadow);
  width: 960px; max-width: 100%; height: 88vh; max-height: 88vh;
  display: flex; flex-direction: column; overflow: hidden;
  color: var(--at-text); font-family: inherit; border: 1px solid var(--at-border);
  transition: width 0.2s, border-radius 0.2s;
}
.at-panel-fullscreen { width: 100vw !important; max-width: 100vw !important; height: 100vh !important; max-height: 100vh !important; border-radius: 0 !important; border: none !important; }

.at-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 12px 16px; border-bottom: 1px solid var(--at-border); flex-shrink: 0;
}
.at-header-left { display: flex; align-items: center; gap: 8px; min-width: 0; flex: 1; overflow: hidden; }
.at-logo-icon   { flex-shrink: 0; }
.at-title       { font-size: 14px; font-weight: 700; color: var(--at-text); flex-shrink: 0; }
.at-tx-code     { font-size: 12px; font-weight: 600; color: #4F7CFF; background: rgba(79,124,255,0.1); padding: 2px 8px; border-radius: 6px; font-family: monospace; flex-shrink: 0; }
.at-tx-name     { font-size: 13px; color: var(--at-text-sub); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.at-header-btns { display: flex; align-items: center; gap: 4px; flex-shrink: 0; }
.at-icon-btn {
  width: 28px; height: 28px; border: none; background: transparent; cursor: pointer;
  color: var(--at-text-faint); border-radius: 6px;
  display: flex; align-items: center; justify-content: center; transition: background 0.15s, color 0.15s;
}
.at-icon-btn:hover       { background: rgba(79,124,255,0.1); color: #4F7CFF; }
.at-close.at-icon-btn:hover { background: rgba(255,77,79,0.1); color: #FF4D4F; }

.at-request-bar {
  display: flex; align-items: center; gap: 8px;
  padding: 10px 16px; border-bottom: 1px solid var(--at-border); flex-shrink: 0;
}
.at-method-select {
  height: 34px; padding: 0 10px; border: 1px solid var(--at-border); border-radius: 8px;
  font-size: 12px; font-weight: 700; background: var(--at-bg-input); color: var(--at-text);
  cursor: pointer; outline: none; width: 76px; flex-shrink: 0;
}
.at-method-select.method-get  { color: #12B886; border-color: #12B886; background: rgba(18,184,134,0.07); }
.at-method-select.method-post { color: #FF6B35; border-color: #FF6B35; background: rgba(255,107,53,0.07); }
.at-method-select option { color: var(--at-text); background: var(--at-bg); font-weight: 600; }
.at-url-input {
  flex: 1; height: 34px; padding: 0 12px; border: 1px solid var(--at-border); border-radius: 8px;
  font-size: 13px; color: var(--at-text); background: var(--at-bg-input); outline: none;
  font-family: 'Consolas', 'SF Mono', monospace; min-width: 0; transition: border-color 0.15s, box-shadow 0.15s;
}
.at-url-input:focus { border-color: #4F7CFF; box-shadow: 0 0 0 3px rgba(79,124,255,0.12); }
.at-url-input::placeholder { color: var(--at-text-faint); font-family: inherit; }
.at-send-btn {
  height: 34px; padding: 0 16px; background: #4F7CFF; color: #fff; border: none; border-radius: 8px;
  font-size: 13px; font-weight: 600; cursor: pointer; display: flex; align-items: center; gap: 6px;
  flex-shrink: 0; transition: background 0.15s, opacity 0.15s;
}
.at-send-btn:hover:not(:disabled) { background: #3A6BF0; }
.at-send-btn:disabled { opacity: 0.6; cursor: not-allowed; }
.at-send-btn.loading  { background: #8BA8FF; }
@keyframes spin { to { transform: rotate(360deg); } }
.spin-icon { animation: spin 0.7s linear infinite; }

.at-split { flex: 1; display: flex; flex-direction: column; overflow: hidden; min-height: 0; }

.at-req-section { flex-shrink: 0; display: flex; flex-direction: column; overflow: hidden; min-height: 0; }

.at-tabs {
  display: flex; padding: 0 16px; border-bottom: 1px solid var(--at-border);
  background: var(--at-bg-tab); flex-shrink: 0;
}
.at-tab {
  position: relative; padding: 8px 12px; font-size: 13px; color: var(--at-text-faint);
  background: transparent; border: none; border-bottom: 2px solid transparent;
  cursor: pointer; display: flex; align-items: center; gap: 5px; transition: color 0.15s, border-color 0.15s; margin-bottom: -1px;
}
.at-tab.active { color: #4F7CFF; border-bottom-color: #4F7CFF; }
.at-tab:hover:not(.active) { color: var(--at-text-sub); }
.at-tab-badge { font-size: 10px; background: #4F7CFF; color: #fff; padding: 1px 5px; border-radius: 8px; }
.at-tab-dot   { width: 6px; height: 6px; background: #FF6B35; border-radius: 50%; }

.at-tab-panel {
  flex: 1; overflow-y: auto; min-height: 0;
  scrollbar-width: thin; scrollbar-color: var(--at-border) transparent;
}
.at-tab-panel::-webkit-scrollbar { width: 4px; }
.at-tab-panel::-webkit-scrollbar-thumb { background: var(--at-border); border-radius: 2px; }

.at-divider {
  position: relative; flex-shrink: 0; height: 10px; cursor: row-resize;
  display: flex; align-items: center; justify-content: center;
  background: var(--at-divider-bg); border-top: 1px solid var(--at-border); border-bottom: 1px solid var(--at-border);
  transition: background 0.15s; user-select: none;
}
.at-divider:hover, .at-divider:active { background: var(--at-divider-hover); }
.at-divider-bar { position: absolute; inset: 0; opacity: 0; }
.at-divider-handle { color: var(--at-text-faint); line-height: 0; pointer-events: none; transition: color 0.15s; }
.at-divider:hover .at-divider-handle { color: #4F7CFF; }

.at-response { flex: 1; display: flex; flex-direction: column; overflow: hidden; min-height: 0; }
.at-response-header {
  display: flex; align-items: center; gap: 8px; padding: 8px 16px;
  border-bottom: 1px solid var(--at-border); flex-shrink: 0; flex-wrap: wrap;
}
.at-response-title { font-size: 13px; font-weight: 600; color: var(--at-text-sub); }
.at-status-badge   { font-size: 12px; font-weight: 700; padding: 2px 10px; border-radius: 6px; }
.status-2xx { background: rgba(18,184,134,0.12); color: #12B886; }
.status-3xx { background: rgba(79,124,255,0.12); color: #4F7CFF; }
.status-4xx { background: rgba(255,107,53,0.12); color: #FF6B35; }
.status-5xx { background: rgba(255,77,79,0.12);  color: #FF4D4F; }
.at-response-time { font-size: 12px; color: var(--at-text-faint); }
.at-response-size { font-size: 12px; color: var(--at-text-faint); }
.at-copy-btn {
  display: flex; align-items: center; gap: 4px; padding: 3px 10px;
  background: transparent; border: 1px solid var(--at-border); border-radius: 5px;
  font-size: 11px; color: var(--at-text-faint); cursor: pointer; transition: color 0.15s, border-color 0.15s;
}
.at-copy-btn:hover { color: #4F7CFF; border-color: #4F7CFF; }
.at-mock-resp-btn {
  margin-left: auto; display: flex; align-items: center; gap: 4px; padding: 3px 10px;
  background: rgba(18,184,134,0.07); border: 1px solid rgba(18,184,134,0.3); border-radius: 5px;
  font-size: 11px; color: #12B886; cursor: pointer; font-weight: 500; transition: background 0.15s, border-color 0.15s;
}
.at-mock-resp-btn:hover { background: rgba(18,184,134,0.14); border-color: #12B886; }

.at-response-empty {
  flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 8px; color: var(--at-text-faint); font-size: 13px;
}
.at-response-empty span { font-size: 12px; }
.at-response-error { display: flex; align-items: flex-start; gap: 8px; padding: 14px 16px; font-size: 13px; color: #FF4D4F; line-height: 1.5; }
.at-response-body-wrap { flex: 1; display: flex; flex-direction: column; overflow: hidden; }
.at-response-tabs { display: flex; padding: 0 16px; border-bottom: 1px solid var(--at-border); flex-shrink: 0; }
.at-resp-tab {
  padding: 6px 12px; font-size: 12px; color: var(--at-text-faint); background: transparent;
  border: none; border-bottom: 2px solid transparent; cursor: pointer; margin-bottom: -1px; transition: color 0.15s, border-color 0.15s;
}
.at-resp-tab.active { color: #4F7CFF; border-bottom-color: #4F7CFF; }

.at-resp-headers { flex: 1; overflow-y: auto; scrollbar-width: thin; scrollbar-color: var(--at-border) transparent; }
.at-resp-hd-head {
  display: grid; grid-template-columns: 2fr 3fr; padding: 5px 16px;
  font-size: 11px; font-weight: 700; color: var(--at-text-faint); letter-spacing: 0.05em; text-transform: uppercase;
  background: var(--at-kv-head-bg); border-bottom: 1px solid var(--at-border); position: sticky; top: 0;
}
.at-resp-hd-row {
  display: grid; grid-template-columns: 2fr 3fr; padding: 7px 16px;
  border-bottom: 1px solid var(--at-border-sub); align-items: baseline; transition: background 0.1s;
}
.at-resp-hd-row:hover { background: var(--at-bg-sub); }
.at-resp-hd-row:last-child { border-bottom: none; }
.at-resp-hd-key { font-family: 'Consolas', monospace; font-size: 12px; font-weight: 600; color: #569CD6; word-break: break-all; padding-right: 12px; }
.at-resp-hd-val { font-family: 'Consolas', monospace; font-size: 12px; color: var(--at-text-sub); word-break: break-all; }
.at-resp-hd-empty { padding: 20px 16px; font-size: 13px; color: var(--at-text-faint); text-align: center; }

.at-response-body {
  flex: 1; overflow: auto; padding: 10px 16px; margin: 0;
  font-family: 'Consolas', 'SF Mono', 'Fira Code', monospace; font-size: 13px; line-height: 1.65;
  color: var(--at-text); background: var(--at-bg-code); white-space: pre-wrap; word-break: break-all;
  scrollbar-width: thin; scrollbar-color: var(--at-border) transparent;
}

.at-kv-toolbar {
  display: flex; align-items: center; justify-content: space-between;
  padding: 5px 12px; border-bottom: 1px solid var(--at-border); background: var(--at-kv-head-bg); flex-shrink: 0;
}
.at-kv-toolbar-label { display: flex; align-items: center; gap: 6px; font-size: 11px; font-weight: 600; color: var(--at-text-faint); letter-spacing: 0.05em; text-transform: uppercase; }
.at-kv-count { font-size: 10px; background: rgba(79,124,255,0.12); color: #4F7CFF; padding: 1px 6px; border-radius: 8px; text-transform: none; font-weight: 500; letter-spacing: 0; }
.at-bulk-toggle {
  display: flex; align-items: center; gap: 4px; padding: 3px 10px;
  background: transparent; border: 1px solid var(--at-border); border-radius: 5px;
  font-size: 11px; font-weight: 600; color: var(--at-text-faint); cursor: pointer; transition: color 0.15s, border-color 0.15s, background 0.15s;
}
.at-bulk-toggle:hover { color: #4F7CFF; border-color: #4F7CFF; }
.at-bulk-toggle.active { color: #4F7CFF; border-color: #4F7CFF; background: rgba(79,124,255,0.07); }

.at-kv-table { width: 100%; }
.at-kv-head {
  display: grid; grid-template-columns: 32px 1fr 1fr 32px;
  background: var(--at-kv-head-bg); padding: 5px 12px;
  font-size: 11px; font-weight: 600; color: var(--at-text-faint); letter-spacing: 0.05em; text-transform: uppercase;
  border-bottom: 1px solid var(--at-border);
}
.at-kv-row {
  display: grid; grid-template-columns: 32px 1fr 1fr 32px;
  border-bottom: 1px solid var(--at-border-sub); align-items: center; padding: 0 12px; transition: background 0.1s;
}
.at-kv-row:hover { background: var(--at-bg-sub); }
.at-kv-row:last-child { border-bottom: none; }
.kv-check-wrap { display: flex; align-items: center; justify-content: center; }
.kv-check { accent-color: #4F7CFF; cursor: pointer; }
.kv-input { border: none; outline: none; background: transparent; font-size: 13px; color: var(--at-text); padding: 7px 6px; width: 100%; font-family: 'Consolas', monospace; }
.kv-input::placeholder { color: var(--at-text-faint); }
.kv-del { width: 24px; height: 24px; display: flex; align-items: center; justify-content: center; background: transparent; border: none; cursor: pointer; color: var(--at-text-faint); border-radius: 4px; transition: color 0.15s, background 0.15s; }
.kv-del:hover:not(:disabled) { color: #FF4D4F; background: rgba(255,77,79,0.08); }
.kv-del:disabled { opacity: 0.3; cursor: not-allowed; }
.at-add-row { display: flex; align-items: center; gap: 5px; margin: 6px 12px; padding: 4px 10px; background: transparent; border: 1px dashed var(--at-border); border-radius: 6px; font-size: 12px; color: var(--at-text-faint); cursor: pointer; transition: color 0.15s, border-color 0.15s; }
.at-add-row:hover { color: #4F7CFF; border-color: #4F7CFF; }

.at-bulk-hint { padding: 5px 14px; font-size: 11px; color: var(--at-text-faint); border-bottom: 1px solid var(--at-border); line-height: 1.6; flex-shrink: 0; }
.at-bulk-hint code { font-family: 'Consolas', monospace; background: rgba(79,124,255,0.1); color: #4F7CFF; padding: 0 4px; border-radius: 3px; font-size: 11px; }
.at-bulk-editor { display: block; width: 100%; min-height: 80px; padding: 8px 14px; background: var(--at-bg-code); border: none; outline: none; font-family: 'Consolas', monospace; font-size: 13px; line-height: 1.6; color: var(--at-text); resize: none; tab-size: 2; box-sizing: border-box; }
.at-bulk-footer { display: flex; align-items: center; justify-content: space-between; padding: 5px 12px; border-top: 1px solid var(--at-border); flex-shrink: 0; }
.at-bulk-preview { font-size: 12px; color: var(--at-text-faint); }
.at-bulk-preview em { font-style: normal; color: #4F7CFF; font-weight: 600; }

.at-body-toolbar { display: flex; align-items: center; justify-content: space-between; padding: 5px 12px; border-bottom: 1px solid var(--at-border); flex-shrink: 0; }
.at-body-type    { font-size: 11px; font-weight: 700; color: #FF6B35; background: rgba(255,107,53,0.1); padding: 2px 8px; border-radius: 4px; }
.at-body-actions { display: flex; gap: 4px; }
.at-body-action-btn { display: flex; align-items: center; gap: 4px; padding: 3px 8px; background: transparent; border: 1px solid var(--at-border); border-radius: 5px; font-size: 11px; color: var(--at-text-faint); cursor: pointer; transition: color 0.15s, border-color 0.15s; }
.at-body-action-btn:hover { color: #4F7CFF; border-color: #4F7CFF; }
.at-body-editor { display: block; width: 100%; height: 100%; min-height: 60px; padding: 8px 14px; background: var(--at-bg-code); border: none; outline: none; font-family: 'Consolas', monospace; font-size: 13px; line-height: 1.6; color: var(--at-text); resize: none; tab-size: 2; box-sizing: border-box; }
.at-json-error { margin: 3px 12px; font-size: 12px; color: #FF4D4F; }

:deep(.json-key)  { color: #569CD6; }
:deep(.json-str)  { color: #CE9178; }
:deep(.json-num)  { color: #B5CEA8; }
:deep(.json-bool) { color: #569CD6; }
:deep(.json-null) { color: #9CDCFE; }
</style>
