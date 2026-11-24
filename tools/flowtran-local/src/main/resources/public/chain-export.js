const PAGE_SIZE = 50;

export function createExportView(data, kinds, inspect) {
  if (!data?.transaction || !Array.isArray(data.relations)) {
    throw new Error('服务未返回有序链路，请确认运行的是支持 JSON 导出的后端。');
  }
  const root = document.getElementById('chain-export-template').content.firstElementChild.cloneNode(true);
  const preview = root.querySelector('.json-preview');
  const textarea = root.querySelector('textarea');
  const feedback = root.querySelector('[data-feedback]');
  const list = root.querySelector('ol');
  const previous = root.querySelector('[data-prev]');
  const next = root.querySelector('[data-next]');
  const relations = data.relations;
  const text = JSON.stringify(data, null, 2);
  let offset = 0;
  textarea.value = text;

  function notify(message) {
    feedback.hidden = false;
    feedback.textContent = message;
  }

  root.querySelector('[data-copy]').addEventListener('click', async () => {
    if (navigator.clipboard?.writeText) {
      try {
        await navigator.clipboard.writeText(text);
        notify('完整 JSON 已复制。');
        return;
      } catch {
        // 受限的内网浏览器可直接复制已选中的只读文本。
      }
    }
    preview.open = true;
    textarea.focus();
    textarea.select();
    notify('已选中完整 JSON，请使用系统复制快捷键。');
  });

  root.querySelector('[data-download]').addEventListener('click', () => {
    const code = data.transaction.code.replace(/[^\p{L}\p{N}._-]+/gu, '_').slice(0, 96);
    const link = document.createElement('a');
    link.download = `chain-${code || 'transaction'}.json`;
    const url = URL.createObjectURL(new Blob([text], { type: 'application/json;charset=utf-8' }));
    link.href = url;
    document.body.append(link);
    try {
      link.click();
    } finally {
      link.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    }
  });

  function nodeButton(node, call = null) {
    const item = document.createElement('button');
    item.type = 'button';
    item.className = 'sequence-link';
    item.textContent = `${kinds[node.kind] || node.kind} ${node.code}`;
    item.title = node.name || node.code;
    item.addEventListener('click', () => inspect(node.id, [], call));
    return item;
  }

  function referenceButton(sequence) {
    const item = document.createElement('button');
    item.type = 'button';
    item.className = 'sequence-link';
    item.textContent = `见第 ${sequence} 条`;
    item.addEventListener('click', () => {
      renderPage(Math.floor((sequence - 1) / PAGE_SIZE) * PAGE_SIZE);
      list.children[(sequence - 1) % PAGE_SIZE]?.focus();
    });
    return item;
  }

  function renderPage(start) {
    offset = start;
    const end = Math.min(start + PAGE_SIZE, relations.length);
    root.querySelector('[data-range]').textContent = relations.length
      ? `第 ${start + 1}–${end} 条，共 ${relations.length} 条。XML 按编排顺序展开，同一 Java 方法内按源码行排列。`
      : '当前快照中这笔交易没有调用关系。';
    list.start = start + 1;
    const rows = document.createDocumentFragment();
    for (const row of relations.slice(start, end)) {
      const li = document.createElement('li');
      li.tabIndex = -1;
      li.value = row.sequence;
      const site = row.callSite;
      const call = site ? {
        from: row.from.id, to: site.rawTarget, type: row.relation,
        line: site.line ?? -1, methodName: site.methodName ?? ''
      } : null;
      li.append(nodeButton(row.from, call), ' → ', nodeButton(row.to));
      const detail = document.createElement('div');
      detail.className = 'sequence-detail';
      const parts = [row.relation, row.parentSequence ? `上游 #${row.parentSequence}` : '交易入口'];
      if (row.condition) parts.push(`条件 ${row.condition}`);
      if (row.callSite?.line) parts.push(`调用行 ${row.callSite.line}`);
      if (row.missingBinding) parts.push(`缺少 ${row.missingBinding} 绑定`);
      detail.textContent = parts.join(' · ');
      if (row.callSite?.file) detail.title = row.callSite.file;
      if (row.referenceSequence) detail.append(' · ', referenceButton(row.referenceSequence));
      li.append(detail);
      rows.append(li);
    }
    list.replaceChildren(rows);
    root.querySelector('[data-pages]').hidden = relations.length <= PAGE_SIZE;
    previous.disabled = start === 0;
    next.disabled = end === relations.length;
  }

  previous.addEventListener('click', () => renderPage(offset - PAGE_SIZE));
  next.addEventListener('click', () => renderPage(offset + PAGE_SIZE));
  renderPage(0);
  return root;
}
