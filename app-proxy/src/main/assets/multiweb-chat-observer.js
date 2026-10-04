function(config) {
  const previous = window.__multiwebChat;
  if (previous) previous.dispose();
  const editorSelector = config.gemini
    ? 'rich-textarea .ql-editor[contenteditable="true"],.ql-editor[contenteditable="true"],div[contenteditable="true"][role="textbox"],textarea'
    : '#prompt-textarea[contenteditable="true"]';
  const replySelector = config.gemini ? 'model-response' : '[data-message-author-role="assistant"]';
  const userSelector = config.gemini ? 'user-query' : '[data-message-author-role="user"]';
  const norm = text => String(text || '').replace(/\s+/g, ' ').trim();
  const visible = node => !!node && !!(node.offsetWidth || node.offsetHeight || node.getClientRects().length);
  const nodes = selector => [...document.querySelectorAll(selector)];
  const text = node => (node.innerText || node.textContent || '').trim();
  const input = () => document.querySelector(editorSelector);
  const inputText = () => norm(input()?.value || input()?.innerText || input()?.textContent);
  const oldReplies = nodes(replySelector);
  const oldUsers = new Set(nodes(userSelector));
  const userKey = node => node.getAttribute('data-message-id') ||
    node.getAttribute('data-turn-id') || node.id ||
    node.closest('[data-testid^="conversation-turn"]')?.getAttribute('data-testid') || '';
  const oldUserKeys = new Set([...oldUsers].map(userKey).filter(Boolean));
  const baselineCount = oldReplies.length;
  const baselinePromptCount = [...oldUsers].filter(n => norm(text(n)) === norm(config.prompt)).length;
  let dispatched = false, acknowledged = false, sawBusy = false, userAnchor = null;
  const initialPath = location.pathname;
  const startedAtHome = config.gemini ? /^(?:\/u\/\d+)?\/app\/?$/.test(initialPath) : initialPath === '/';
  let responsePath = null, pageChanged = false;
  let lastPayload = '', disposed = false, scheduled = false;
  const modelLabel = () => {
    const selector = config.gemini
      ? 'button[data-test-id="bard-mode-menu-button"],button[data-test-id="model-selector"],button.input-area-switch'
      : 'button[data-testid="model-switcher-dropdown-button"],button[data-testid="model-selector"]';
    const node = nodes(selector).find(visible);
    return node ? text(node).slice(0, 96) : '';
  };
  const isBusy = latest => nodes('button').some(node => visible(node) &&
    /stop.?generat|stop.?response|stop.?stream|stop-button|停止生成|停止回答|停止回复|停止流式/i.test(
      (node.getAttribute('data-testid') || '') + ' ' + (node.getAttribute('aria-label') || '') + ' ' + text(node))) ||
    !!latest && [...latest.querySelectorAll('mat-progress-spinner,.loading-indicator,[aria-busy="true"],[data-is-streaming="true"],.result-streaming')].some(visible);
  const hadBusy = isBusy(oldReplies[oldReplies.length - 1]);
  function snapshot() {
    // Starting a new chat may assign its first conversation URL. Further SPA navigation is unrelated.
    const currentPath = location.pathname;
    if (dispatched && currentPath !== initialPath && startedAtHome && responsePath === null)
      responsePath = currentPath;
    pageChanged = pageChanged || dispatched && currentPath !== (responsePath || initialPath);
    const matching = nodes(userSelector).filter(node => norm(text(node)) === norm(config.prompt));
    const addedUser = matching.find(node => !oldUsers.has(node) &&
      userKey(node) && !oldUserKeys.has(userKey(node)));
    if (dispatched && (addedUser || matching.length > baselinePromptCount)) {
      userAnchor = matching[matching.length - 1];
      acknowledged = true;
    }
    if (userAnchor && !userAnchor.isConnected) userAnchor = null;
    const replies = nodes(replySelector);
    // Message position/ownership, not text equality: two consecutive "pong" replies are valid.
    const owned = replies.filter((node, index) => userAnchor
      ? !!(userAnchor.compareDocumentPosition(node) & 4)
      : index >= baselineCount && !oldReplies.includes(node));
    const latest = dispatched ? owned[owned.length - 1] : null;
    const busy = isBusy(latest || replies[replies.length - 1]);
    if (dispatched && (latest || busy && !hadBusy)) acknowledged = true;
    if (dispatched && busy) sawBusy = true;
    let responseText = '', images = [];
    if (latest) {
      if (config.gemini) {
        responseText = [...latest.querySelectorAll('structured-content-container.model-response-text,message-content,.markdown.markdown-main-panel')]
          .map(text).filter(Boolean).sort((a, b) => b.length - a.length)[0] || '';
        images = [...new Set([...latest.querySelectorAll('img')]
          .filter(img => img.naturalWidth >= 256 && img.naturalHeight >= 256 || /googleusercontent|ggpht|blob:|generated/i.test(img.currentSrc || img.src || ''))
          .map(img => img.currentSrc || img.src).filter(Boolean))];
      } else {
        const content = [...latest.querySelectorAll('.markdown')];
        responseText = content.length ? content.map(text).join('\n\n') : text(latest);
      }
    }
    const turn = latest && (config.gemini ? latest : latest.closest('article,[data-testid^="conversation-turn"]') || latest.parentElement || latest);
    const terminal = !!turn && [...turn.querySelectorAll(config.gemini
      ? 'button[data-test-id="copy-button"],button[data-test-id="response-copy-button"]'
      : 'button[data-testid="copy-turn-action-button"],button[data-testid="good-response-turn-action-button"],button[data-testid="bad-response-turn-action-button"]')]
      .some(node => visible(node) && !node.disabled && node.getAttribute('aria-disabled') !== 'true');
    return { requestId: config.requestId, error: pageChanged ? 'web_page_changed' : '', acknowledged, busy,
      finished: !!latest && acknowledged && !busy && (sawBusy || terminal),
      text: responseText, images, pageModel: modelLabel() };
  }
  function publish() {
    if (disposed) return;
    const payload = JSON.stringify(snapshot());
    if (payload === lastPayload) return;
    lastPayload = payload;
    // The bridge is installed before page loading. No timers or animation frames are needed.
    try { MultiWebChat.onState(config.requestId, payload); } catch (_) {}
  }
  const observer = new MutationObserver(() => {
    if (scheduled || disposed) return;
    scheduled = true;
    Promise.resolve().then(() => { scheduled = false; publish(); });
  });
  observer.observe(document.documentElement, { subtree: true, childList: true, characterData: true,
    attributes: true, attributeFilter: ['disabled', 'aria-disabled', 'aria-busy', 'class', 'data-testid', 'aria-label'] });
  window.__multiwebChat = {
    requestId: config.requestId,
    snapshot,
    inputMatches: () => inputText() === norm(config.prompt),
    send: () => {
      if (dispatched) return 'already_dispatched';
      if (inputText() !== norm(config.prompt)) return 'input_not_ready';
      const selector = config.gemini
        ? 'button.send-button,button[data-test-id="send-button"],button[data-testid="send-button"]'
        : 'button[data-testid="send-button"],button[data-testid="fruitjuice-send-button"],form button[type="submit"]';
      const candidates = [...nodes(selector), ...nodes('button').filter(node =>
        /^(send|发送)$|send message|send prompt|发送消息|发送提示/i.test(node.getAttribute('aria-label') || node.title || text(node)) ||
        config.gemini && /^(send|arrow_upward)$/i.test(node.querySelector('mat-icon')?.textContent?.trim() || ''))];
      const button = candidates.find(node => visible(node) && !node.disabled && node.getAttribute('aria-disabled') !== 'true');
      if (!button) return 'send_not_ready';
      // Mark before dispatch; never retry a request after a click/Enter has been dispatched.
      dispatched = true;
      button.click();
      publish();
      return 'dispatched';
    },
    nativeEnter: () => {
      if (dispatched || inputText() !== norm(config.prompt)) return false;
      const node = input();
      if (!node) return false;
      node.focus(); dispatched = true; publish(); return true;
    },
    dispose: () => { disposed = true; observer.disconnect(); }
  };
  publish();
  return 'ready';
}
