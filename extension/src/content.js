(() => {
  'use strict';
  const HOST_ID = 'gemini-anchor-extension';
  if (document.getElementById(HOST_ID)) return;
  // Gemini uses custom elements. Keep fallbacks scoped to user messages so that
  // sidebar/history titles and answer text can never become invented questions.
  const QUERY_SELECTOR = 'user-query, [data-message-author-role="user"], .user-query-container';
  const TEXT_SELECTOR = '.query-text, .query-text-line, [data-test-id="user-query-text"], .user-query-text';
  const host = document.createElement('div');
  host.id = HOST_ID;
  host.style.cssText = 'position:fixed;inset:0;z-index:2147483646;pointer-events:none;';
  const shadow = host.attachShadow({ mode: 'open' });
  shadow.innerHTML = `
    <style>
      :host{all:initial;color-scheme:light dark;font-family:Inter,"Segoe UI","Microsoft YaHei",sans-serif;font-size:14px;color:#24352e}
      *{box-sizing:border-box}button,input{font:inherit}button{cursor:pointer}button:focus-visible,input:focus-visible{outline:3px solid #6ba68d;outline-offset:3px}
      button{color:inherit}button:disabled{opacity:.45;cursor:default}[hidden]{display:none!important}
      .launcher{pointer-events:auto;position:fixed;right:16px;top:42%;display:flex;align-items:center;gap:9px;padding:12px 15px;border:1px solid #d2dfd5;border-radius:16px;background:#f7faf5;color:#234b3d;box-shadow:0 5px 24px #132c2420;font-weight:650}
      .launcher svg{width:19px;height:19px}.badge{font-size:11px;background:#dce9df;padding:2px 6px;border-radius:6px}
      .panel{pointer-events:auto;position:fixed;right:16px;top:16px;bottom:16px;width:336px;max-width:calc(100vw - 24px);display:flex;flex-direction:column;background:#f8faf6;border:1px solid #d8e1d7;border-radius:22px;box-shadow:0 12px 64px #122a2326;overflow:hidden}
      header{padding:24px 22px 16px}.eyebrow{font-size:10px;letter-spacing:2px;color:#6d8074;font-weight:700}.heading{display:flex;align-items:center;justify-content:space-between;margin:9px 0 7px}h2{font-size:23px;letter-spacing:-.8px;font-weight:650;margin:0}.icon{border:0;background:transparent;font-size:23px;width:34px;height:34px;border-radius:9px}.icon:hover{background:#e9eee6}
      .subtitle{font-size:12px;color:#738075;line-height:1.7;margin:0}.search{margin:0 18px;position:relative}.search span{position:absolute;left:12px;top:12px;color:#708478}.search input{width:100%;height:43px;padding:0 12px 0 34px;border:1px solid #dce3d9;border-radius:11px;background:#fff;color:inherit;font-size:13px}
      .tools{display:flex;gap:6px;padding:12px 18px}.tools button,.load{border:1px solid #d9e2d7;border-radius:8px;background:transparent;font-size:11px;padding:8px 10px}.tools button:hover,.load:hover{background:#e5eee2}.tools .load{margin-left:auto;color:#35634e}
      .meta{padding:5px 22px 12px;font-size:10px;letter-spacing:1px;color:#7c887c;display:flex;justify-content:space-between}.list{margin:0 10px;padding:0 0 12px;overflow:auto;overscroll-behavior:contain;flex:1;list-style:none;scrollbar-width:thin;scrollbar-color:#bdcdbd transparent}
      .entry{position:relative;display:grid;grid-template-columns:28px 1fr;gap:8px;text-align:left;width:100%;padding:13px 12px;margin:2px 0;border:1px solid transparent;border-radius:12px;background:transparent;line-height:1.6}.entry:hover{background:#edf2e9}.entry[aria-current="true"]{background:#e7efe3;border-color:#d8e5d1}.entry[aria-current="true"]:before{content:'';position:absolute;left:0;top:17px;bottom:17px;width:3px;background:#5a8169;border-radius:3px}.number{font-family:monospace;font-size:11px;color:#8c9b8a;padding-top:2px}.title{font-size:13px;display:-webkit-box;-webkit-line-clamp:3;-webkit-box-orient:vertical;overflow:hidden;overflow-wrap:anywhere}.entry[aria-current="true"] .number{color:#497a57}
      mark{background:#e1ca85;color:#263c2d;border-radius:2px}.empty{padding:30px 20px;text-align:center;color:#7b887b;font-size:13px;line-height:1.9}.empty strong{display:block;color:#3f5b47;font-size:15px;font-weight:600;margin-bottom:8px}
      footer{padding:13px 20px 16px;border-top:1px solid #e2e8de;display:flex;gap:10px;justify-content:space-between;font-size:10px;color:#8a9686}.status{padding:0 20px 12px;font-size:11px;color:#55715b;line-height:1.6}.dot{display:inline-block;background:#7b9c70;width:5px;height:5px;border-radius:50%;margin-right:5px}
      @media(prefers-color-scheme:dark){:host{color:#e0e9dc}.panel,.launcher{background:#202a24;border-color:#3d4b3d}.launcher{color:#d0e3cb}.badge,.entry[aria-current="true"]{background:#354634}.search input{background:#28342b;border-color:#465642}.icon:hover,.entry:hover,.tools button:hover,.load:hover{background:#303d2f}.entry[aria-current="true"]{border-color:#455740}.tools button,.load{border-color:#495744}.tools .load,.status,.empty strong{color:#acc5a0}.subtitle,.eyebrow,.meta,.empty{color:#a0b19b}footer{border-color:#3d4b3d;color:#a0b19b}}
      @media(max-width:600px){.panel{right:8px;top:8px;bottom:8px}.launcher{right:8px;padding:11px}}
      @media(prefers-reduced-motion:no-preference){.panel{animation:arrive .18s ease-out}@keyframes arrive{from{opacity:0;transform:translateX(6px)}to{opacity:1;transform:none}}}
    </style>
    <button class="launcher" aria-label="展开对话目录" aria-expanded="false" aria-controls="ga-panel">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true"><path d="M5 5v14M10 5h9M10 12h9M10 19h6"/></svg>目录 <span class="badge">0</span>
    </button>
    <aside class="panel" id="ga-panel" role="complementary" aria-label="Gemini 对话目录" hidden>
      <header><div class="eyebrow">GEMINI ANCHOR</div><div class="heading"><h2>对话目录</h2><button class="icon close" aria-label="收起目录" title="收起 · Alt+Shift+G">×</button></div><p class="subtitle">把长对话，变成随手可翻的章节。</p></header>
      <label class="search"><span aria-hidden="true">⌕</span><input type="search" placeholder="搜索这段对话…" aria-label="搜索提问" autocomplete="off"></label>
      <nav class="tools" aria-label="对话导航"><button class="first">最早提问 ↑</button><button class="last">最新 ↓</button><button class="load">加载前文</button></nav>
      <div class="meta"><span class="count">0 条提问</span><span>按对话顺序</span></div>
      <ol class="list" aria-label="提问列表"></ol>
      <div class="status" role="status" aria-live="polite"></div>
      <footer><span><i class="dot"></i>仅在本机 · 不保存对话</span><span>Alt + Shift + G</span></footer>
    </aside>`;
  document.documentElement.append(host);
  const $ = (selector) => shadow.querySelector(selector);
  const panel = $('.panel'), launcher = $('.launcher'), list = $('.list'), search = $('input');
  let entries = [], active = null, opened = false, generation = 0, scanTimer = 0;
  let route = location.pathname + location.search, loading = false, loadToken = 0;
  let previousFocus = null, scrollFrame = 0, disposed = false;
  const normalize = (text) => text.replace(/\s+/gu, ' ').trim();

  function setOpen(value, focus = false) {
    opened = value;
    panel.hidden = !value; launcher.hidden = value;
    launcher.setAttribute('aria-expanded', String(value));
    if (value) {
      previousFocus = document.activeElement;
      scan();
      if (focus) search.focus();
    } else {
      cancelLoad();
      if (focus) (previousFocus?.isConnected && previousFocus !== host ? previousFocus : launcher).focus();
    }
    try { chrome.storage.local.set({ anchorOpen: value }).catch(() => {}); } catch { /* Test/extension reload. */ }
  }
  function status(message) { $('.status').textContent = message; }
  function queryText(node) {
    const copy = node.cloneNode(true);
    // Live Gemini repeats the entire prompt in a hidden "You said" heading.
    // Strip accessibility-only copies from our clone, never from Gemini itself.
    copy.querySelectorAll('button,mat-icon,[role="button"],svg,script,style,[hidden],[aria-hidden="true"],.cdk-visually-hidden,.screen-reader-user-query-label').forEach((el) => el.remove());
    copy.querySelectorAll('p,div,li,pre,br,.query-text-line').forEach((el) => el.after(document.createTextNode('\n')));
    return copy.textContent;
  }
  function readQueries() {
    const candidates = [...document.querySelectorAll(QUERY_SELECTOR)];
    return candidates.filter((node) => !candidates.some((other) => other !== node && other.contains(node)))
      .filter((node) => node.getClientRects().length && getComputedStyle(node).visibility !== 'hidden')
      .map((node) => {
        const parts = [...node.querySelectorAll(TEXT_SELECTOR)];
        const outer = parts.filter((part) => !parts.some((other) => other !== part && other.contains(part)));
        let text = normalize(outer.map(queryText).join('\n'));
        if (!text) {
          text = normalize(queryText(node));
        }
        return { node, text: text || '图片或附件提问' };
      });
  }
  function scan() {
    if (disposed) return;
    const nextRoute = location.pathname + location.search;
    const routeChanged = nextRoute !== route;
    if (routeChanged) {
      route = nextRoute; generation++; cancelLoad(); entries = []; active = null; search.value = ''; status('');
    }
    const next = readQueries();
    const changed = next.length !== entries.length || next.some((item, i) => item.node !== entries[i]?.node || item.text !== entries[i]?.text);
    entries = next;
    $('.badge').textContent = String(entries.length);
    if (changed || routeChanged) render();
    updateActive();
  }
  function highlight(container, text, query) {
    // Never put conversation content into innerHTML.
    if (!query) { container.textContent = text; return; }
    const folded = text.toLocaleLowerCase(); const needle = query.toLocaleLowerCase();
    let from = 0, match;
    while ((match = folded.indexOf(needle, from)) !== -1) {
      container.append(document.createTextNode(text.slice(from, match)));
      const mark = document.createElement('mark'); mark.textContent = text.slice(match, match + query.length); container.append(mark);
      from = match + query.length;
    }
    container.append(document.createTextNode(text.slice(from)));
  }
  function render() {
    const query = normalize(search.value);
    const filtered = entries.map((entry, index) => ({ ...entry, index })).filter((entry) => entry.text.toLocaleLowerCase().includes(query.toLocaleLowerCase()));
    $('.count').textContent = query ? `${filtered.length} / ${entries.length} 条提问` : `${entries.length} 条提问`;
    $('.first').disabled = $('.last').disabled = !entries.length;
    const fragment = document.createDocumentFragment();
    for (const entry of filtered) {
      const li = document.createElement('li'), button = document.createElement('button');
      button.className = 'entry'; button.dataset.index = String(entry.index);
      button.title = entry.text; button.setAttribute('aria-current', String(entry.node === active));
      const number = document.createElement('span'); number.className = 'number'; number.textContent = String(entry.index + 1).padStart(2, '0');
      const title = document.createElement('span'); title.className = 'title'; highlight(title, entry.text, query);
      button.append(number, title); button.addEventListener('click', () => jump(entry)); li.append(button); fragment.append(li);
    }
    if (!filtered.length) {
      const li = document.createElement('li'); li.className = 'empty';
      const title = document.createElement('strong'); title.textContent = query ? '没有找到这条提问' : '从一段对话开始';
      li.append(title, document.createTextNode(query ? '试试更短的关键词。' : '打开 Gemini 对话后，提问会自动出现在这里。未显示的历史内容可点「加载前文」。'));
      fragment.append(li);
    }
    list.replaceChildren(fragment);
  }
  function jump(entry) {
    if (!entry.node.isConnected) { scan(); status('页面已更新，请从新目录中选择。'); return; }
    entry.node.scrollIntoView({ behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth', block: 'center' });
    active = entry.node; updateCurrent();
    if (entry.node.animate && !matchMedia('(prefers-reduced-motion: reduce)').matches) {
      entry.node.animate([{ outline: '3px solid #91b786', outlineOffset: '8px' }, { outline: '3px solid transparent', outlineOffset: '8px' }], { duration: 1600 });
    }
    status(`已定位第 ${entry.index + 1} 条提问`);
    if (innerWidth < 700) setOpen(false);
  }
  function updateCurrent() {
    list.querySelectorAll('.entry').forEach((button) => button.setAttribute('aria-current', String(entries[Number(button.dataset.index)]?.node === active)));
  }
  function updateActive() {
    const targetY = innerHeight * .3;
    let closest = null, distance = Infinity;
    for (const entry of entries) {
      const rect = entry.node.getBoundingClientRect();
      const d = Math.abs(rect.top - targetY);
      if (d < distance) { distance = d; closest = entry.node; }
    }
    if (closest !== active) { active = closest; updateCurrent(); }
  }
  function scrollContainer(node) {
    for (let parent = node?.parentElement; parent; parent = parent.parentElement) {
      if (/(auto|scroll)/.test(getComputedStyle(parent).overflowY) && parent.scrollHeight > parent.clientHeight + 8) return parent;
    }
    return document.scrollingElement;
  }
  const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
  function cancelLoad() { loading = false; loadToken++; $('.load').textContent = '加载前文'; }
  async function loadEarlier() {
    if (loading) { cancelLoad(); status('已停止加载，已读到的提问保留在目录中。'); return; }
    scan();
    if (!entries.length) { status('尚未识别到提问。请先打开一段对话，或手动向上滚动。'); return; }
    loading = true; const token = ++loadToken, currentGeneration = generation;
    const container = scrollContainer(entries[0].node);
    const originalAnchoring = container.style.overflowAnchor;
    container.style.overflowAnchor = 'none';
    const origin = entries.find((entry) => entry.node === active)?.node;
    const originTop = origin?.getBoundingClientRect().top;
    const initialScrollTop = container.scrollTop;
    let stable = 0, previousSignature = '';
    $('.load').textContent = '停止加载';
    const interrupted = () => { if (loading && loadToken === token) { cancelLoad(); status('已停止自动加载，跟随你的滚动。'); } };
    document.addEventListener('wheel', interrupted, { once: true, passive: true });
    document.addEventListener('touchstart', interrupted, { once: true, passive: true });
    try {
      for (let step = 0; step < 60 && loading && loadToken === token && generation === currentGeneration; step++) {
        status(`正在加载前文 · 已识别 ${entries.length} 条提问（可随时停止）`);
        container.scrollTo({ top: 0, behavior: 'instant' });
        await wait(900); scan();
        if (!loading || loadToken !== token || generation !== currentGeneration) break;
        const signature = `${entries.length}:${entries[0]?.text}:${container.scrollHeight}`;
        stable = signature === previousSignature ? stable + 1 : 0; previousSignature = signature;
        if (stable >= 4) break;
      }
      if (loading && loadToken === token && generation === currentGeneration) {
        if (origin?.isConnected) container.scrollBy({ top: origin.getBoundingClientRect().top - originTop, behavior: 'instant' });
        else container.scrollTo({ top: initialScrollTop, behavior: 'instant' });
        cancelLoad(); status(`本轮加载结束 · ${entries.length} 条提问。若仍有更早内容，可再次加载或手动上翻。`);
      }
    } finally {
      document.removeEventListener('wheel', interrupted); document.removeEventListener('touchstart', interrupted);
      // Let the explicit restoration settle before re-enabling native anchoring.
      await new Promise(requestAnimationFrame);
      container.style.overflowAnchor = originalAnchoring;
    }
  }
  launcher.addEventListener('click', () => setOpen(true, true));
  $('.close').addEventListener('click', () => setOpen(false, true));
  $('.first').addEventListener('click', () => entries[0] && jump({ ...entries[0], index: 0 }));
  $('.last').addEventListener('click', () => entries.length && jump({ ...entries.at(-1), index: entries.length - 1 }));
  $('.load').addEventListener('click', loadEarlier);
  search.addEventListener('input', render);
  shadow.addEventListener('keydown', (event) => { if (event.key === 'Escape') { event.preventDefault(); setOpen(false, true); } });
  document.addEventListener('scroll', () => {
    if (!scrollFrame) scrollFrame = requestAnimationFrame(() => { scrollFrame = 0; updateActive(); });
  }, { capture: true, passive: true });
  const observer = new MutationObserver(() => {
    clearTimeout(scanTimer); scanTimer = setTimeout(scan, 160);
  });
  observer.observe(document.body, { subtree: true, childList: true, characterData: true });
  // URL changes can happen without mutations during client-side navigation.
  const routeTimer = setInterval(() => { if (route !== location.pathname + location.search) scan(); }, 700);
  window.addEventListener('pagehide', (event) => {
    cancelLoad();
    if (!event.persisted) { disposed = true; observer.disconnect(); clearInterval(routeTimer); clearTimeout(scanTimer); }
  });
  try {
    chrome.runtime.onMessage.addListener((message) => { if (message.type === 'gemini-anchor:toggle') setOpen(!opened, true); });
    chrome.storage.local.get('anchorOpen').then((value) => { if (value.anchorOpen === true) setOpen(true); }).catch(() => {});
  } catch { /* Allows the exact shipped script to run in the local demo. */ }
  scan(); render();
})();
