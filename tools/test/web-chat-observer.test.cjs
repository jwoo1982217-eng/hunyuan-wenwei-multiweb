// Run with Node.js and jsdom; see docs/web-chat-relay.md.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const { JSDOM } = require('jsdom');
const source = readFileSync(resolve(__dirname, '../../app-proxy/src/main/assets/multiweb-chat-observer.js'), 'utf8');

function fixture(t, gemini = false) {
  const dom = new JSDOM(`<body><button data-testid="model-switcher-dropdown-button">GPT-6 Pro</button>
    <div id="messages"></div><div id="composer">${gemini
      ? '<rich-textarea><div class="ql-editor" contenteditable="true">ping</div></rich-textarea>'
      : '<div id="prompt-textarea" contenteditable="true">ping</div>'}
    <button data-testid="send-button" type="button">Send</button></div></body>`, {
      runScripts: 'outside-only', url: gemini ? 'https://gemini.google.com/app' : 'https://chatgpt.com/',
    });
  const { window } = dom;
  Object.defineProperty(window.HTMLElement.prototype, 'getClientRects', {
    value() { return this.style.display === 'none' ? [] : [{ width: 100, height: 20 }]; },
  });
  const events = [];
  window.MultiWebChat = { onState: (id, payload) => events.push([id, JSON.parse(payload)]) };
  const messages = window.document.querySelector('#messages');
  let turn = 0;
  const addUser = () => {
    messages.insertAdjacentHTML('beforeend', gemini ? '<user-query>ping</user-query>'
      : `<article data-testid="conversation-turn-${turn++}"><div data-message-author-role="user">ping</div></article>`);
  };
  const addReply = (text = 'pong', terminal = true) => {
    const footer = terminal ? (gemini ? '<button data-test-id="copy-button">Copy</button>'
      : '<button data-testid="copy-turn-action-button">Copy</button>') : '';
    messages.insertAdjacentHTML('beforeend', gemini
      ? `<model-response><message-content>${text}</message-content>${footer}</model-response>`
      : `<article data-testid="conversation-turn-${turn++}"><div data-message-author-role="assistant"><div class="markdown">${text}</div></div>${footer}</article>`);
  };
  const install = (id = 'current') => {
    window.eval(`(${source})`)({ gemini, requestId: id, prompt: 'ping' });
    return window.__multiwebChat;
  };
  const sendButton = window.document.querySelector('[data-testid="send-button"]');
  const busy = () => {
    const button = window.document.createElement('button');
    button.setAttribute('data-testid', 'stop-button');
    window.document.body.append(button);
    return button;
  };
  t.after(() => { window.__multiwebChat?.dispose(); window.close(); });
  return { window, messages, addUser, addReply, install, sendButton, busy, events };
}

for (const gemini of [false, true]) {
  test(`${gemini ? 'Gemini' : 'ChatGPT'}: a repeated answer belongs to the new turn`, t => {
    const f = fixture(t, gemini);
    f.addUser(); f.addReply();
    const call = f.install();
    f.sendButton.addEventListener('click', () => { f.addUser(); f.addReply(); });
    assert.equal(call.snapshot().text, '');
    assert.equal(call.send(), 'dispatched');
    const state = call.snapshot();
    assert.equal(state.text, 'pong');
    assert.equal(state.acknowledged, true);
    assert.equal(state.finished, true);
  });
}

test('clicking send does not by itself confirm receipt or trigger a second send', t => {
  const f = fixture(t);
  let clicks = 0;
  f.sendButton.addEventListener('click', () => clicks++);
  const call = f.install();
  assert.equal(call.send(), 'dispatched');
  assert.equal(call.snapshot().acknowledged, false);
  assert.equal(call.send(), 'already_dispatched');
  assert.equal(call.nativeEnter(), false);
  assert.equal(clicks, 1);
});

test('partial output and a thinking pause are not a completed reply', t => {
  const f = fixture(t);
  const call = f.install();
  f.sendButton.addEventListener('click', f.addUser);
  call.send(); f.addReply('half', false);
  assert.equal(call.snapshot().finished, false);
  const stop = f.busy();
  assert.equal(call.snapshot().busy, true);
  assert.equal(call.snapshot().finished, false);
  assert.equal(call.snapshot().text, 'half');
  stop.remove();
  assert.equal(call.snapshot().finished, true);
});

test('disabled and hidden controls cannot submit the prompt', t => {
  const f = fixture(t);
  const call = f.install();
  f.sendButton.disabled = true;
  assert.equal(call.send(), 'send_not_ready');
  f.sendButton.disabled = false;
  f.sendButton.style.display = 'none';
  assert.equal(call.send(), 'send_not_ready');
  f.sendButton.style.display = '';
  f.window.document.querySelector('#prompt-textarea').textContent = 'wrong input';
  assert.equal(call.send(), 'input_not_ready');
});

for (const gemini of [false, true]) test(`${gemini ? 'Gemini' : 'ChatGPT'}: rehydrating history cannot return the previous answer`, t => {
  const f = fixture(t, gemini);
  f.addUser(); f.addReply('old answer');
  const call = f.install();
  call.send();
  f.messages.innerHTML = f.messages.innerHTML;
  const state = call.snapshot();
  assert.equal(state.acknowledged, false);
  assert.equal(state.text, '');
  assert.equal(state.finished, false);
});

test('a new user anchor handles removed history without using old output', t => {
  const f = fixture(t);
  f.addUser(); f.addReply('old');
  const call = f.install();
  f.sendButton.addEventListener('click', () => {
    f.messages.replaceChildren(); f.addUser(); f.addReply('fresh');
  });
  call.send();
  assert.equal(call.snapshot().text, 'fresh');
  assert.equal(call.snapshot().finished, true);
});

test('DOM changes push progress without waiting for a JavaScript interval', async t => {
  const f = fixture(t);
  const call = f.install();
  f.sendButton.addEventListener('click', f.addUser);
  call.send(); f.addReply('first output');
  await Promise.resolve(); await Promise.resolve(); await Promise.resolve();
  assert.ok(f.events.some(([id, event]) => id === 'current' && event.text === 'first output'));
  const count = f.events.length;
  call.dispose(); f.addReply('late output');
  await Promise.resolve(); await Promise.resolve();
  assert.equal(f.events.length, count);
});

test('native Enter may be dispatched only once and still needs a website receipt', t => {
  const f = fixture(t, true);
  const call = f.install();
  assert.equal(call.nativeEnter(), true);
  assert.equal(call.nativeEnter(), false);
  assert.equal(call.snapshot().acknowledged, false);
  f.addUser(); f.addReply('accepted');
  assert.equal(call.snapshot().acknowledged, true);
});

test('Gemini generated images are associated with the current response', t => {
  const f = fixture(t, true);
  const call = f.install();
  f.sendButton.addEventListener('click', () => {
    f.addUser(); f.addReply('<img src="blob:generated">');
  });
  call.send();
  assert.deepEqual(Array.from(call.snapshot().images), ['blob:generated']);
  assert.equal(call.snapshot().finished, true);
});

test('the recorded model follows the actual website picker', t => {
  const f = fixture(t);
  const call = f.install();
  assert.equal(call.snapshot().pageModel, 'GPT-6 Pro');
  f.window.document.querySelector('[data-testid="model-switcher-dropdown-button"]').textContent = 'GPT-6 Thinking';
  assert.equal(call.snapshot().pageModel, 'GPT-6 Thinking');
});

test('assigning the first conversation URL is allowed, switching an existing conversation fails', t => {
  const f = fixture(t);
  const call = f.install();
  f.sendButton.addEventListener('click', f.addUser);
  call.send();
  f.window.history.pushState({}, '', '/c/current');
  assert.equal(call.snapshot().error, '');
  f.window.history.pushState({}, '', '/');
  assert.equal(call.snapshot().error, 'web_page_changed');
  f.window.history.pushState({}, '', '/c/another');
  assert.equal(call.snapshot().error, 'web_page_changed');
});

test('an existing conversation cannot consume output from a different SPA route', t => {
  const f = fixture(t, true);
  f.window.history.replaceState({}, '', '/app/current');
  const call = f.install();
  call.send();
  f.window.history.pushState({}, '', '/app/another');
  f.addUser(); f.addReply('unrelated');
  assert.equal(call.snapshot().error, 'web_page_changed');
});

test('Gemini can assign a conversation URL under a numbered account', t => {
  const f = fixture(t, true);
  f.window.history.replaceState({}, '', '/u/1/app');
  const call = f.install();
  f.sendButton.addEventListener('click', f.addUser);
  call.send();
  f.window.history.pushState({}, '', '/u/1/app/current');
  f.addReply('accepted');
  assert.equal(call.snapshot().error, '');
  assert.equal(call.snapshot().finished, true);
});
