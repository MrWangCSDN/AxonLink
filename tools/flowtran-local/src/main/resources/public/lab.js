import { createExportView } from './chain-export.js';

const byId = id => document.getElementById(id);
const form = byId('scan-form');
const chainPanel = byId('chain');
const nodePanel = byId('node');
const scanButton = byId('scan-button');
const sampleButton = byId('sample-button');
const search = byId('search');
const tabs = Array.from(document.querySelectorAll('[data-view]'));

const logicalKinds = { transaction: '交易', service: '服务', component: '构件', table: '表', dao: 'DAO' };
const rawKinds = {
  Transaction: '交易', FlowBlock: '流程', FlowCase: '分支', FlowWhen: '条件',
  FlowMethodStep: '方法步骤', FlowServiceStep: '服务步骤', ServiceType: '服务类型',
  ServiceOperation: '服务操作', Class: '类', Interface: '接口', Method: 'Java 方法',
  Table: '表', DaoMethod: 'DAO 方法'
};
const relationNames = {
  ENTRY: '编排入口', serviceToService: '服务调用服务', serviceToComponent: '服务调用构件',
  componentToComponent: '构件调用构件', nodeToTable: '访问表', tableToDao: '表对应的 DAO'
};
const rawProperties = {
  id: '定义 ID', serviceName: '服务引用', serviceTypeId: '服务类型 ID', serviceId: '操作 ID',
  methodName: 'Java 方法名', txClassFqn: '交易类', classFqn: '声明类', interfaceFqn: '服务接口',
  packagePath: '包', test: '条件表达式', desc: '说明', order: '容器内序号',
  nodeKind: '分类', fileNodeKind: '文件分类', domainKey: '领域',
  tableId: '表 ID', daoClassName: 'DAO 类', schemaId: '表定义 ID'
};

let state = null;
let chain = null;
let exportRequest = null;
let txCode = null;
let view = 'logical';
let chainVersion = 0;
let nodeVersion = 0;
let methodVersion = 0;
let history = [];

function element(tag, className, text) {
  const item = document.createElement(tag);
  if (className) item.className = className;
  if (text != null) item.textContent = text;
  return item;
}

function button(className, text, onClick) {
  const item = element('button', className, text);
  item.type = 'button';
  item.addEventListener('click', onClick);
  return item;
}

async function api(path, options = {}) {
  const response = await fetch(path, { cache: 'no-store', ...options });
  const body = await response.json().catch(() => null);
  if (!response.ok) throw new Error(body?.message || body?.error || `请求失败（${response.status}）`);
  return body;
}

function withParam(path, key, value) {
  return `${path}?${new URLSearchParams({ [key]: value })}`;
}

function showMessage(area, text, kind = 'empty') {
  area.replaceChildren(element('p', kind, text));
}

function setForm(settings) {
  byId('workspace').value = (settings.workspaceRoots || '').replaceAll(',', '\n');
  byId('source-roots').value = (settings.sourceRoots || '').replaceAll(',', '\n');
  byId('packages').value = settings.includePackages;
  byId('max-kb').value = settings.maxFileKb;
}

function formSettings() {
  const limit = byId('max-kb').value;
  return {
    workspaceRoots: byId('workspace').value,
    sourceRoots: byId('source-roots').value,
    includePackages: byId('packages').value,
    maxFileKb: limit === '' ? null : Number(limit)
  };
}

function renderScanSummary() {
  const summary = state.summary;
  const status = byId('scan-status');
  if (summary) {
    const updated = new Date(summary.createdAt).toLocaleString('zh-CN', { hour12: false });
    status.textContent = `${updated} · ${summary.javaFiles} 个 Java 文件 · ${summary.edges} 条图关系`;
  } else {
    status.textContent = '还没有保存的扫描快照。';
  }

  const db = byId('database');
  db.textContent = state.dbFile;
  db.title = 'SQLite 文件';
  db.hidden = false;
  const notes = byId('scan-notes');
  notes.hidden = !summary?.issueCount;
  if (summary?.issueCount) {
    const issueCount = summary.issueCount;
    const shown = state.issues.length;
    byId('scan-notes-title').textContent = `${issueCount} 条扫描诊断${issueCount > shown ? `（前 ${shown} 条）` : ''}`;
    byId('scan-notes-list').replaceChildren(...state.issues.map(issue => {
      const row = element('li', '', issueText(issue));
      row.title = issue.file || '';
      return row;
    }));
  }
}

function renderTransactions() {
  const list = byId('transactions');
  const query = search.value.trim().toLowerCase();
  const transactions = state?.transactions ?? [];
  const matched = transactions.filter(tx => `${tx.code} ${tx.name}`.toLowerCase().includes(query));
  byId('tx-count').textContent = query ? `${matched.length} / ${transactions.length}` : String(transactions.length);
  const rows = matched.map(tx => {
    const row = button('', null, () => loadChain(tx.code));
    row.setAttribute('aria-current', String(tx.code === txCode));
    row.append(element('strong', 'mono', tx.code));
    if (tx.name !== tx.code) row.append(element('small', '', tx.name));
    return row;
  });
  if (!rows.length) rows.push(element('p', 'empty', query ? '没有匹配的交易。' : '当前快照没有交易。'));
  list.replaceChildren(...rows);
}

function applyState(result) {
  state = result;
  ++methodVersion;
  byId('method-results').replaceChildren();
  renderScanSummary();
  const selection = state.transactions.find(tx => tx.code === txCode) ?? state.transactions[0];
  loadChain(selection?.code ?? null);
}

function renderChainHeading() {
  const title = byId('chain-title');
  const file = byId('chain-file');
  const domain = byId('chain-domain');
  const counts = byId('chain-counts');
  file.hidden = domain.hidden = counts.hidden = !chain;
  if (!chain) {
    title.textContent = txCode || '查看交易链路';
    return;
  }
  const transaction = chain.transaction.properties;
  title.textContent = transaction.name === txCode ? txCode : `${txCode} · ${transaction.name}`;
  file.textContent = transaction.filePath || '';
  file.hidden = !transaction.filePath;
  domain.textContent = transaction.domainKey || '';
  domain.hidden = !transaction.domainKey;
  counts.replaceChildren(...Object.entries(logicalKinds).slice(1).map(([kind, label]) => {
    const total = chain.nodes.filter(node => node.kind === kind).length;
    return element('span', 'stat', `${label} ${total}`);
  }));
}

async function loadChain(code) {
  const request = ++chainVersion;
  txCode = code;
  chain = null;
  exportRequest = null;
  history = [];
  ++nodeVersion;
  byId('node-back').hidden = true;
  showMessage(nodePanel, '选择链路节点查看调用方向和源码行。');
  renderTransactions();
  renderChainHeading();
  if (!code) {
    showMessage(chainPanel, state?.summary
      ? '当前快照没有交易。可在右侧按签名查找已扫描的 Java 方法。'
      : '扫描业务源码或内置示例后，选择一笔交易。');
    return;
  }
  showMessage(chainPanel, '正在读取链路…');
  try {
    const result = await api(withParam('/api/chain', 'code', code));
    if (request !== chainVersion) return;
    chain = result;
    renderChainHeading();
    renderChain();
  } catch (error) {
    if (request === chainVersion) showMessage(chainPanel, error.message, 'error');
  }
}

function renderChain() {
  if (!chain) return;
  if (view === 'export') {
    renderExport();
    return;
  }
  if (view === 'components' || view === 'tables') {
    renderRelations();
    return;
  }
  if (view === 'xml') {
    if (!chain.flow.length) {
      showMessage(chainPanel, '交易 XML 中没有 flow 容器。');
      return;
    }
    const tree = element('div', 'tree');
    for (const item of chain.flow) tree.append(xmlItem(item));
    chainPanel.replaceChildren(tree);
    return;
  }

  const root = chain.nodes.find(node => node.kind === 'transaction');
  const nodes = new Map(chain.nodes.map(node => [node.id, node]));
  const children = new Map();
  for (const edge of chain.edges) {
    if (!children.has(edge.from)) children.set(edge.from, []);
    children.get(edge.from).push(edge);
  }
  const tree = element('div', 'tree');
  tree.append(logicalItem(root, null, nodes, children, new Set()));
  if (!children.has(root.id)) tree.append(element('p', 'empty', '没有可聚合的 XML 入口。'));
  chainPanel.replaceChildren(tree);
}

async function renderExport() {
  const selection = chain;
  if (!exportRequest) {
    const query = new URLSearchParams({ code: selection.transaction.properties.id });
    if (selection.snapshotAt) query.set('snapshotAt', selection.snapshotAt);
    exportRequest = api('/api/chain/export?' + query).then(data =>
      chain === selection ? createExportView(data, { ...rawKinds, ...logicalKinds }, inspect) : null);
  }
  const request = exportRequest;
  showMessage(chainPanel, '正在按调用顺序生成 JSON…');
  try {
    const content = await request;
    if (chain === selection && view === 'export' && content) chainPanel.replaceChildren(content);
  } catch (error) {
    if (request === exportRequest) exportRequest = null;
    if (chain === selection && view === 'export') {
      showMessage(chainPanel, error.message, 'error');
      chainPanel.append(button('edge', '重新载入交易', () => loadChain(txCode)));
    }
  }
}

function logicalItem(node, via, nodes, children, ancestors) {
  const section = element('div');
  const repeated = ancestors.has(node.id);
  const note = repeated ? '循环引用' : relationNames[via];
  section.append(logicalNodeButton(node, note));
  if (repeated) return section;
  const edges = children.get(node.id) ?? [];
  if (edges.length) {
    const path = new Set([...ancestors, node.id]);
    section.append(branches(edges.length, ancestors.size < 4 && edges.length <= 8,
      () => edges.map(edge => logicalItem(nodes.get(edge.to), edge.type, nodes, children, path))));
  }
  return section;
}

function logicalNodeButton(node, note) {
  const label = node.prefix === 'method' ? '方法' : logicalKinds[node.kind];
  return nodeButton(label, node.kind, node.name, node.code, note, () => inspect(node.rawId, node.entries));
}

function renderRelations() {
  const components = view === 'components';
  const records = components ? chain.componentCalls : chain.tableAccesses;
  if (!Array.isArray(records)) {
    showMessage(chainPanel, '请使用新版后端查看调用明细。当前版本可在「聚合链路」展开下游关系。');
    return;
  }
  if (!records.length) {
    const entryIds = new Set(chain.edges.filter(edge => edge.type === 'ENTRY').map(edge => edge.to));
    const entries = chain.nodes.filter(node => entryIds.has(node.id));
    let message;
    if (!entries.length) {
      message = '当前交易没有可展开的服务或方法入口。可在「XML 步骤」查看交易编排。';
    } else if (entries.every(node => !node.entries.length)) {
      message = '当前交易的编排入口均缺少 Java 方法绑定。请在「聚合链路」选中入口，在「原始节点」查看绑定和扫描诊断。';
    } else {
      message = components
        ? '当前交易未识别到构件调用。可从服务的 Java 入口查看原始调用边，并核对工作区中的 pbc 构件定义。'
        : '当前交易的 Java 入口在聚合范围内没有可达的 DAO 调用。可从实现方法查看下游调用边，并核对扫描包前缀。';
    }
    showMessage(chainPanel, message);
    return;
  }

  const nodes = new Map(chain.nodes.map(node => [node.id, node]));
  const rawNodes = new Map(chain.nodes.map(node => [node.rawId, node]));
  const headings = components
    ? ['构件调用构件', '服务或方法调用构件', '构件调用服务', '编排引用构件']
    : ['构件访问', '服务或方法访问'];
  const groups = new Map(headings.map(name => [name, []]));
  for (const record of records) {
    const source = nodes.get(record.sourceId);
    let group = source.kind === 'component' ? '构件访问' : '服务或方法访问';
    if (components) {
      if (source.kind === 'transaction') group = '编排引用构件';
      else if (source.kind !== 'component') group = '服务或方法调用构件';
      else group = record.target.kind === 'component' ? '构件调用构件' : '构件调用服务';
    }
    groups.get(group).push(record);
  }

  const content = document.createDocumentFragment();
  if (components && !groups.get('构件调用构件').length) {
    content.append(element('p', 'status', '当前交易没有识别到构件间调用。'));
  }
  if (!components && records.some(record => !record.tableId)) {
    content.append(element('p', 'status', '表定义待匹配的行已识别出 DAO 调用。请核对工作区资源目录中 tables 下的 *.tables.xml，或在日志中搜索 DAO_TABLE。'));
  }
  for (const [name, rows] of groups) {
    if (!rows.length) continue;
    const section = element('section', 'relation-group');
    const table = element('table', 'relation-table');
    table.append(element('caption', '', `${name}（${rows.length}）`));
    const header = table.createTHead().insertRow();
    for (const label of [components ? '调用入口 / 实际调用处' : '访问入口 / 实际调用处', components ? '目标操作' : '表 / DAO 方法']) {
      const th = element('th', '', label);
      th.scope = 'col';
      header.append(th);
    }
    const body = table.createTBody();
    for (const record of rows) {
      const row = body.insertRow();
      const source = row.insertCell();
      source.append(logicalNodeButton(nodes.get(record.sourceId)));
      appendCallSites(source, record.calls);
      const target = row.insertCell();
      if (components) {
        target.append(logicalNodeButton(record.target));
      } else {
        const tableNode = rawNodes.get(record.tableId);
        target.append(tableNode ? logicalNodeButton(tableNode) : element('span', 'unbound-table', '表定义待匹配'));
        const dao = record.daoId.replace(/^DaoMethod:/, '');
        target.append(nodeButton('DAO', 'dao', dao, dao, null, () => inspect(record.daoId)));
      }
    }
    section.append(table);
    content.append(section);
  }
  chainPanel.replaceChildren(content);
}

function appendCallSites(cell, calls) {
  if (!calls.length) return;
  const callButton = call => {
    const signature = call.from.replace(/^Method:/, '');
    const shortName = signature.slice(signature.lastIndexOf('.', signature.indexOf('#')) + 1);
    const line = call.line > 0 ? ` · 第 ${call.line} 行` : '';
    const link = button('call-site', shortName + line, () => inspect(call.from, [], call));
    link.title = `${signature}${line} · ${call.type} → ${call.to}`;
    return link;
  };
  if (calls.length === 1) {
    cell.append(callButton(calls[0]));
    return;
  }
  const group = element('details', 'call-sites');
  group.append(element('summary', '', `${calls.length} 条原始调用边`));
  const list = element('div');
  group.append(list);
  group.addEventListener('toggle', () => {
    if (group.open && !list.childElementCount) {
      for (const call of calls) list.append(callButton(call));
    }
  });
  cell.append(group);
}

function xmlItem(item, depth = 0) {
  const { node, children, repeated } = item;
  const data = node.properties;
  const section = element('div');
  const label = rawKinds[node.kind] || node.kind;
  const code = data.serviceName || data.methodName || data.id;
  const note = repeated ? '共享节点' : data.test;
  section.append(nodeButton(label, '', data.name || code || label, code, note, () => inspect(node.id)));
  if (children.length) {
    section.append(branches(children.length, depth < 3 && children.length <= 8,
      () => children.map(child => xmlItem(child, depth + 1))));
  }
  return section;
}

function branches(count, expand, buildChildren) {
  const details = element('details', 'branch');
  details.append(element('summary', '', `${count} 条下游关系`));
  const list = element('div', 'tree');
  let built = false;
  const build = () => {
    if (built) return;
    built = true;
    for (const child of buildChildren()) list.append(child);
    details.append(list);
  };
  details.open = expand;
  if (expand) build();
  details.addEventListener('toggle', () => { if (details.open) build(); });
  return details;
}

function nodeButton(label, kind, name, code, note, onClick) {
  const row = button('tree-row', null, onClick);
  const caption = element('span', 'caption');
  caption.append(element('strong', '', name));
  if (code && code !== name) caption.append(element('small', 'mono', code));
  if (note) caption.append(element('small', '', note));
  row.append(element('span', `tag ${kind}`, label), caption);
  return row;
}

function inspect(id, entries = [], call = null) {
  history = [{ id, entries, call }];
  loadNode();
  if (matchMedia('(max-width: 1080px)').matches) {
    byId('node-panel').scrollIntoView({ block: 'nearest' });
  }
}

function openRelated(id) {
  if (history.at(-1)?.id === id) return;
  history.push({ id, entries: [] });
  loadNode();
}

async function loadNode() {
  const selection = history.at(-1);
  const request = ++nodeVersion;
  byId('node-back').hidden = history.length < 2;
  showMessage(nodePanel, '正在读取节点…');
  try {
    const result = await api(withParam('/api/node', 'id', selection.id));
    if (request === nodeVersion) renderNode(result, selection.entries, selection.call);
  } catch (error) {
    if (request === nodeVersion) showMessage(nodePanel, error.message, 'error');
  }
}

function renderNode(detail, entries, call) {
  const { node, outgoing, incoming, issues } = detail;
  const data = node.properties;
  const selected = call && outgoing.find(({ edge }) =>
    edge.from === call.from && edge.to === call.to && edge.type === call.type &&
    edge.line === call.line && edge.methodName === call.methodName)?.edge;
  const header = element('div', 'node-title');
  header.append(element('span', 'tag', rawKinds[node.kind] || node.kind));
  header.append(element('strong', '', data.name || data.id || node.key));
  nodePanel.replaceChildren(header, element('p', 'mono muted', node.key));
  if (data.filePath) {
    const callLine = selected?.line;
    const line = callLine > 0 ? callLine : data.line;
    const location = data.filePath + (line > 0 ? `:${line}` : '');
    nodePanel.append(element('p', 'file-path', callLine > 0 ? `调用位置 ${location}` : location));
  }
  if (call && !selected) nodePanel.append(element('p', 'status', '当前快照中的调用边已变更，请重新选择交易。'));

  const properties = element('dl');
  for (const [key, label] of Object.entries(rawProperties)) {
    const value = data[key];
    if (value != null && value !== '' && value !== -1) {
      properties.append(element('dt', '', label), element('dd', '', value));
    }
  }
  if (properties.children.length) nodePanel.append(properties);

  if (entries.length) {
    const section = element('section', 'entry-method');
    section.append(element('h3', '', '聚合使用的 Java 入口'));
    const list = element('div', 'edge-list');
    for (const entry of entries) list.append(button('edge mono', entry.replace(/^Method:/, ''), () => openRelated(entry)));
    section.append(list);
    nodePanel.append(section);
  }
  if (issues.length) {
    const section = element('section');
    section.append(element('h3', '', '扫描诊断'));
    const list = element('ul', 'node-issues');
    for (const issue of issues) list.append(element('li', '', issueText(issue)));
    section.append(list);
    nodePanel.append(section);
  }
  if (node.kind === 'ServiceOperation' && !outgoing.some(({ edge }) => edge.type === 'IMPLEMENTS_BY')) {
    nodePanel.append(element('p', 'status', '当前快照缺少这个操作的 Java 实现绑定。'));
  }
  showEdges('指向下游', outgoing, false, data.filePath, selected);
  showEdges('来自上游', incoming, true, data.filePath);
  if (!outgoing.length && !incoming.length) nodePanel.append(element('p', 'empty', '这个节点没有已保存的关系。'));
}

function issueText(issue) {
  const line = issue.line > 0 ? `第 ${issue.line} 行 · ` : '';
  return `${line}${issue.message || issue.rule}`;
}

function showEdges(title, relations, incoming, file, selected = null) {
  if (!relations.length) return;
  const section = element('section');
  section.append(element('h3', '', `${title}（${relations.length}）`));
  const list = element('div', 'edge-list');
  const ordered = [...relations].sort((a, b) =>
    Number(b.edge === selected) - Number(a.edge === selected) || a.edge.line - b.edge.line);
  for (const { edge, node } of ordered) {
    const line = edge.line > 0 ? ` · 调用行 ${edge.line}` : '';
    const methodName = edge.methodName ? ` · ${edge.methodName}` : '';
    const row = button('edge', null, () => openRelated(node.id));
    if (edge === selected) row.setAttribute('aria-current', 'location');
    const marker = edge === selected ? '所选调用 · ' : '';
    row.append(element('span', 'relation-label', marker + edge.type + line + methodName));
    row.append(element('span', 'mono', node.key));
    if (edge.line > 0) row.title = (incoming ? node.properties.filePath : file) || '';
    list.append(row);
  }
  section.append(list);
  nodePanel.append(section);
}

async function runScan() {
  scanButton.disabled = sampleButton.disabled = true;
  const status = byId('scan-status');
  const errorBox = byId('scan-error');
  errorBox.hidden = true;
  status.textContent = '正在扫描 Java 和 XML 并保存快照…';
  try {
    const result = await api('/api/scan', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(formSettings())
    });
    search.value = '';
    applyState(result);
  } catch (error) {
    errorBox.textContent = error.message;
    errorBox.hidden = false;
    if (state) renderScanSummary();
    else status.textContent = '扫描未完成。';
  } finally {
    scanButton.disabled = false;
    sampleButton.disabled = !state;
  }
}

async function findMethods() {
  const request = ++methodVersion;
  const results = byId('method-results');
  const query = byId('method-query').value.trim();
  if (!query) {
    results.replaceChildren();
    return;
  }
  showMessage(results, '正在查找方法…');
  try {
    const matches = await api(withParam('/api/methods', 'query', query));
    if (request !== methodVersion) return;
    if (matches.length) {
      results.replaceChildren(...matches.map(node => button('edge mono', node.key, () => inspect(node.id))));
    } else {
      showMessage(results, '当前快照没有匹配的 Java 方法。');
    }
  } catch (error) {
    if (request === methodVersion) showMessage(results, error.message, 'error');
  }
}

form.addEventListener('submit', event => {
  event.preventDefault();
  runScan();
});
sampleButton.addEventListener('click', () => {
  if (!state) return;
  setForm({ ...state.defaults, workspaceRoots: state.exampleWorkspace });
  runScan();
});
search.addEventListener('input', renderTransactions);
byId('method-form').addEventListener('submit', event => {
  event.preventDefault();
  findMethods();
});
for (const tab of tabs) {
  tab.addEventListener('click', () => {
    view = tab.dataset.view;
    for (const item of tabs) item.setAttribute('aria-pressed', String(item === tab));
    renderChain();
  });
}
byId('node-back').addEventListener('click', () => {
  history.pop();
  loadNode();
});

scanButton.disabled = sampleButton.disabled = true;
api('/api/state').then(result => {
  setForm(result.summary?.settings || result.defaults);
  applyState(result);
}).catch(error => {
  byId('scan-status').textContent = 'SQLite 快照读取失败。';
  byId('scan-error').textContent = error.message;
  byId('scan-error').hidden = false;
}).finally(() => {
  scanButton.disabled = false;
  sampleButton.disabled = !state;
});
