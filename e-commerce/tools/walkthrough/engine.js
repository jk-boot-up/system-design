/*
 * Walkthrough engine: animates a system diagram step by step.
 * Data (window.WALKTHROUGH) = { title, subtitle, nodes[], edges[], scenarios[] }
 *   node:     { id, label, kind, tech, x, y }          kind: client | edge | service | db | cache | queue | search | external | job
 *   edge:     { from, to, dashed }
 *   scenario: { id, title, summary, steps[] }
 *   step:     { say, active[], ok[], bad[], dim[], packets[{from, to, label, kind}], state{title, rows{}}, log, badges[{node, text, tone}] }
 *             packet kind: req | res | event | error
 * Narration uses the Web Speech API (a female voice, preferring "Amy"); it is optional and remembered per browser.
 */
(function () {
  'use strict';
  const W = window.WALKTHROUGH;
  const $ = s => document.querySelector(s);
  const NS = 'http://www.w3.org/2000/svg';
  const ICONS = { client: '👤', edge: '🛡', service: '⚙', db: '🛢', cache: '⚡', queue: '📨', search: '🔎', external: '🌐', job: '⏱', analytics: '📊', storage: '🗂' };
  const NODE_W = 228, NODE_H = 78;
  const store = (k, v) => { try { if (v === undefined) return localStorage.getItem(k); localStorage.setItem(k, v); } catch (e) { return null; } };

  const state = { scenario: 0, step: -1, playing: false, speed: +(store('wt-speed') || 1), narrate: store('wt-narrate') === 'on', timer: null, anim: [], prevRows: {} };
  const nodesById = Object.fromEntries(W.nodes.map(n => [n.id, n]));

  // ---------- diagram ----------
  const svg = document.createElementNS(NS, 'svg');
  const maxX = Math.max(...W.nodes.map(n => n.x)) + NODE_W / 2 + 20, maxY = Math.max(...W.nodes.map(n => n.y)) + NODE_H / 2 + 30;
  svg.setAttribute('viewBox', `0 0 ${maxX} ${maxY}`);
  svg.setAttribute('role', 'img');
  svg.setAttribute('aria-label', W.title + ' architecture diagram');
  const gEdges = el('g'), gNodes = el('g'), gPkts = el('g');
  svg.append(gEdges, gNodes, gPkts);
  $('#stage').append(svg);

  function el(tag, attrs = {}, text) {
    const e = document.createElementNS(NS, tag);
    for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v);
    if (text !== undefined) e.textContent = text;
    return e;
  }

  const edgeEls = {};
  for (const e of W.edges || []) {
    const a = nodesById[e.from], b = nodesById[e.to];
    if (!a || !b) { console.error('edge with unknown node', e); continue; }
    const line = el('line', { x1: a.x, y1: a.y, x2: b.x, y2: b.y, class: 'edge' + (e.dashed ? ' dashed' : '') });
    gEdges.append(line);
    edgeEls[e.from + '>' + e.to] = edgeEls[e.to + '>' + e.from] = line;
  }
  const nodeEls = {};
  for (const n of W.nodes) {
    const g = el('g', { class: 'node', transform: `translate(${n.x - NODE_W / 2},${n.y - NODE_H / 2})` });
    g.append(el('rect', { width: NODE_W, height: NODE_H, rx: n.kind === 'db' || n.kind === 'cache' ? 18 : 12 }));
    g.append(el("text", { x: 16, y: 48, class: "icon" }, ICONS[n.kind] || "■"));
    g.append(el("text", { x: 56, y: 34 }, n.label));
    if (n.tech) g.append(el("text", { x: 56, y: 57, class: "tech" }, n.tech));
    const title = el('title', {}, `${n.label}${n.tech ? ' — ' + n.tech : ''}`);
    g.append(title);
    gNodes.append(g);
    nodeEls[n.id] = g;
  }

  // ---------- scenario list ----------
  W.scenarios.forEach((s, i) => {
    const b = document.createElement('button');
    b.className = 'scn';
    b.innerHTML = `${i + 1}. ${esc(s.title)}<small>${esc(s.summary || '')}</small>`;
    b.onclick = () => { pause(); select(i); };
    $('#scenarios').append(b);
  });

  function esc(s) { return String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c])); }

  // ---------- rendering a step ----------
  function scenario() { return W.scenarios[state.scenario]; }

  function select(i) {
    state.scenario = i;
    state.step = -1;
    state.prevRows = {};
    document.querySelectorAll('.scn').forEach((b, j) => b.classList.toggle('on', j === i));
    $('#log').innerHTML = '';
    show(0);
  }

  function show(i) {
    const steps = scenario().steps;
    i = Math.max(0, Math.min(steps.length - 1, i));
    if (i < state.step) {           // going back: rebuild the log up to this step
      $('#log').innerHTML = '';
      state.prevRows = {};
      for (let k = 0; k < i; k++) appendLog(steps[k]);
    }
    state.step = i;
    const st = steps[i];
    clearAnimations();
    for (const [id, g] of Object.entries(nodeEls)) {
      g.classList.toggle('active', (st.active || []).includes(id));
      g.classList.toggle('ok', (st.ok || []).includes(id));
      g.classList.toggle('bad', (st.bad || []).includes(id));
      g.classList.toggle('dim', (st.dim || []).includes(id));
      g.querySelectorAll('.badge').forEach(b => b.remove());
    }
    Object.values(edgeEls).forEach(l => l.classList.remove('hot'));
    for (const b of st.badges || []) badge(b);
    $('#say').textContent = st.say;
    renderState(currentState(steps, i));
    appendLog(st);
    $('#count').textContent = `Step ${i + 1} / ${steps.length}`;
    $('#bar').style.width = `${((i + 1) / steps.length) * 100}%`;
    animatePackets(st.packets || []);
    speak(st.say);
    if (state.playing) schedule();
  }

  function badge({ node, text, tone }) {
    const g = nodeEls[node];
    if (!g) return;
    const color = { ok: 'var(--res)', bad: 'var(--err)', warn: 'var(--evt)' }[tone] || 'var(--accent)';
    const w = Math.max(34, text.length * 8.5 + 18);
    const b = el('g', { class: 'badge', transform: `translate(${NODE_W - w + 8},-10)` });
    b.append(el('rect', { width: w, height: 24, rx: 12, style: `fill:${color}` }), el('text', { x: w / 2, y: 17, 'text-anchor': 'middle' }, text));
    g.append(b);
  }

  // the most recent state at or before step i, so the panel keeps showing the latest known values
  function currentState(steps, i) {
    for (let k = i; k >= 0; k--) if (steps[k].state) return steps[k].state;
    return null;
  }

  function renderState(s) {
    const box = $('#state'), card = box.closest('.card');
    if (!s) { card.style.display = 'none'; return; }
    card.style.display = '';
    $('#stateTitle').textContent = s.title || 'State';
    box.innerHTML = Object.entries(s.rows || {}).map(([k, v]) => {
      const changed = state.prevRows[k] !== undefined && state.prevRows[k] !== String(v);
      return `<tr class="${changed ? 'changed' : ''}"><td>${esc(k)}</td><td>${esc(v)}</td></tr>`;
    }).join('');
    state.prevRows = Object.fromEntries(Object.entries(s.rows || {}).map(([k, v]) => [k, String(v)]));
  }

  function appendLog(st) {
    const lines = [];
    for (const p of st.packets || []) lines.push([p.kind || 'req', `${nodesById[p.from]?.label} → ${nodesById[p.to]?.label}: ${p.label}`]);
    if (st.log) lines.push(['', st.log]);
    for (const [cls, text] of lines) {
      const li = document.createElement('li');
      li.className = cls;
      li.textContent = text;
      $('#log').append(li);
    }
    $('#log').scrollTop = $('#log').scrollHeight;
  }

  // ---------- packets ----------
  function clearAnimations() { state.anim.forEach(cancelAnimationFrame); state.anim = []; gPkts.innerHTML = ''; }

  function animatePackets(packets) {
    const dur = 900 / state.speed;
    packets.forEach((p, k) => {
      const a = nodesById[p.from], b = nodesById[p.to];
      if (!a || !b) { console.error('packet with unknown node', p); return; }
      const line = edgeEls[p.from + '>' + p.to];
      if (line) line.classList.add('hot');
      const color = { req: 'var(--req)', res: 'var(--res)', event: 'var(--evt)', error: 'var(--err)' }[p.kind || 'req'];
      const g = el('g', { class: 'pkt', opacity: 0 });
      g.append(el('circle', { r: 10, style: `fill:${color}` }), el('text', { x: 14, y: -12 }, p.label));
      gPkts.append(g);
      const start = performance.now() + k * dur * 0.6;
      const frame = now => {
        const t = Math.min(1, Math.max(0, (now - start) / dur));
        const e = t < .5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
        g.setAttribute('transform', `translate(${a.x + (b.x - a.x) * e},${a.y + (b.y - a.y) * e})`);
        g.setAttribute('opacity', now < start ? 0 : 1);
        if (t < 1) state.anim.push(requestAnimationFrame(frame));
      };
      state.anim.push(requestAnimationFrame(frame));
    });
  }

  // ---------- narration ----------
  let voice = null;
  function pickVoice() {
    const vs = speechSynthesis.getVoices().filter(v => /^en/i.test(v.lang));
    const female = /(amy|samantha|karen|serena|victoria|moira|tessa|fiona|zira|aria|jenny|libby|sonia|natasha|hazel|susan|female)/i;
    voice = vs.find(v => /amy/i.test(v.name)) || vs.find(v => female.test(v.name)) || vs[0] || null;
  }
  if ('speechSynthesis' in window) { pickVoice(); speechSynthesis.onvoiceschanged = pickVoice; }

  let speaking = false;
  function speak(text) {
    if (!('speechSynthesis' in window)) return;
    speechSynthesis.cancel();
    speaking = false;
    if (!state.narrate) return;
    const u = new SpeechSynthesisUtterance(text);
    if (voice) u.voice = voice;
    u.rate = Math.min(1.6, 0.95 * state.speed);
    u.onend = () => { speaking = false; if (state.playing) schedule(true); };
    speaking = true;
    speechSynthesis.speak(u);
  }

  function setNarration(on) {
    state.narrate = on;
    store('wt-narrate', on ? 'on' : 'off');
    const b = $('#narrate');
    b.classList.toggle('on', on);
    b.textContent = on ? '🔊 Narration on' : '🔇 Narration off';
    b.setAttribute('aria-pressed', on);
    if (on) speak($('#say').textContent); else if ('speechSynthesis' in window) speechSynthesis.cancel();
  }

  // ---------- playback ----------
  function schedule(afterSpeech) {
    clearTimeout(state.timer);
    if (state.narrate && speaking && !afterSpeech) return;     // wait for the sentence to finish
    const delay = state.narrate ? 600 : Math.max(1800, 2600 / state.speed);
    state.timer = setTimeout(next, delay);
  }

  function next() {
    const steps = scenario().steps;
    if (state.step < steps.length - 1) show(state.step + 1);
    else if (state.scenario < W.scenarios.length - 1 && $('#autoNext').checked) select(state.scenario + 1);
    else pause();
  }

  function play() { state.playing = true; $('#play').textContent = '⏸ Pause'; schedule(); }
  function pause() { state.playing = false; clearTimeout(state.timer); $('#play').textContent = '▶ Play'; }

  $('#play').onclick = () => (state.playing ? pause() : play());
  $('#prev').onclick = () => { pause(); show(state.step - 1); };
  $('#next').onclick = () => { pause(); show(state.step + 1); };
  $('#restart').onclick = () => { pause(); select(state.scenario); };
  $('#narrate').onclick = () => setNarration(!state.narrate);
  $('#speed').value = String(state.speed);
  $('#speed').onchange = e => { state.speed = +e.target.value; store('wt-speed', state.speed); };
  $('#progress').onclick = e => {
    const r = e.currentTarget.getBoundingClientRect();
    pause();
    show(Math.floor(((e.clientX - r.left) / r.width) * scenario().steps.length));
  };

  // ---------- themes ----------
  function setTheme(t) {
    document.documentElement.dataset.theme = t;
    store('sd-theme', t);
    document.querySelectorAll('[data-set-theme]').forEach(b => b.classList.toggle('on', b.dataset.setTheme === t));
  }
  document.querySelectorAll('[data-set-theme]').forEach(b => (b.onclick = () => setTheme(b.dataset.setTheme)));

  document.addEventListener('keydown', e => {
    if (e.target.tagName === 'SELECT' || e.target.tagName === 'INPUT') return;
    if (e.key === ' ') { e.preventDefault(); state.playing ? pause() : play(); }
    else if (e.key === 'ArrowRight') { pause(); show(state.step + 1); }
    else if (e.key === 'ArrowLeft') { pause(); show(state.step - 1); }
    else if (e.key.toLowerCase() === 'n') setNarration(!state.narrate);
    else if (e.key.toLowerCase() === 't') {
      const order = ['light', 'medium', 'dark'];
      setTheme(order[(order.indexOf(document.documentElement.dataset.theme) + 1) % 3]);
    }
  });

  // ---------- start ----------
  $('#title').textContent = W.title;
  $('#subtitle').textContent = W.subtitle || '';
  document.title = W.title + ' — walkthrough';
  setTheme(store('sd-theme') || 'dark');
  setNarration(state.narrate);
  const hash = location.hash.replace('#', '');
  const start = Math.max(0, W.scenarios.findIndex(s => s.id === hash));
  select(start);

  // test hook: lets automated checks walk every step
  window.__walkthrough = { scenarios: W.scenarios.length, steps: i => W.scenarios[i].steps.length, select, show };
})();
