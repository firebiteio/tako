/** A thread's conversation: turns of a prompt, Claude's text and the tool calls it made. */
import type { ToolFinished, ToolKind, ToolStarted, TurnEnded } from '../bridge/agent';
import { send, sendThread, type ContextItem, type ImageAttachment } from '../bridge/protocol';
import type { AssistantRow, Entry, TaskCard, Thread, ToolEntry, Turn, TurnItemKind, WorkGroup } from '../state/model';
import { relative } from '../state/projects';
import { isActive, state } from '../state/store';
import { $, chevron, el, icon, iconButton, on, rowIcon } from '../ui/dom';
import { clock, duration, elapsed, firstLine, truncate } from '../ui/format';
import { t } from '../ui/i18n';
import { markdown } from '../ui/markdown';
import { imageThumb, inlineAlert, mentionChip } from '../ui/widgets';
import { makeTaskCard, onTaskResult, refreshTaskPanel, renderTask } from './tasks';
import { CATEGORY_ICONS, entryDetails, entryLabel, OUTPUT_LIMIT, renderLabel } from './tools';

/** A prompt longer than this starts collapsed. */
const COLLAPSE_CHARS = 600;
const COLLAPSE_LINES = 8;
/** A plan longer than this starts collapsed. */
const PLAN_COLLAPSE_LINES = 24;

export const scroller = $('scroll');

/** Keeps the thread scrolled to its end while it is shown and the person has not scrolled away. */
export function follow(t: Thread): void {
  if (isActive(t) && t.stick) scroller.scrollTop = scroller.scrollHeight;
}

/** The empty-thread hero shows until the first turn. */
export function setDraft(isDraft: boolean): void {
  document.body.classList.toggle('draft', isDraft);
  if (isDraft) $('scroll-end').hidden = true;
  if (state.active) $('timelines').insertBefore($('hero'), $('timelines').firstChild);
}

export function startTurn(
  t: Thread,
  text: string | null,
  contexts: ContextItem[] | null,
  replayed: boolean,
  images?: ImageAttachment[]
): Turn {
  if (t.turn && !t.turn.done) finishTurn(t, null);
  t.turnCount++;
  if (isActive(t)) setDraft(false);
  const turn: Turn = {
    no: t.turnNo,
    el: el('div', 'turn'),
    items: [],
    group: null,
    lastText: null,
    changes: new Map(),
    startedAt: Date.now(),
    replayed,
    done: false,
    stopped: false,
    working: null
  };
  t.el.appendChild(turn.el);
  if (text !== null) turn.el.appendChild(userRow(text, contexts, images));
  if (!replayed) {
    turn.working = el('div', 'working');
    turn.el.appendChild(turn.working);
  }
  t.turn = turn;
  return turn;
}

/** The running turn, or one without a prompt for output that arrives after the last turn ended. */
export function currentTurn(t: Thread): Turn {
  return t.turn && !t.turn.done ? t.turn : startTurn(t, null, null, t.replaying);
}

export function addItem<T extends HTMLElement>(turn: Turn, node: T, kind: TurnItemKind): T {
  if (turn.working) turn.el.insertBefore(node, turn.working);
  else turn.el.appendChild(node);
  turn.items.push({ node, kind });
  return node;
}

function userRow(text: string, contexts: ContextItem[] | null, images?: ImageAttachment[]): HTMLElement {
  const row = el('div', 'user-row');
  const bubble = el('div', 'user-bubble');
  if (images && images.length) {
    const gallery = el('div', 'bubble-images');
    for (const image of images) gallery.appendChild(imageThumb(image, null));
    bubble.appendChild(gallery);
  }
  if (text) bubble.appendChild(document.createTextNode(text));
  if (contexts && contexts.length) {
    const mentions = el('div', 'mention-row');
    for (const item of contexts) mentions.appendChild(mentionChip(item, null));
    bubble.appendChild(mentions);
  }
  row.appendChild(bubble);
  const meta = el('div', 'hover-meta');
  if (text.length > COLLAPSE_CHARS || text.split('\n').length > COLLAPSE_LINES) {
    bubble.classList.add('collapsed');
    const toggle = el('button', 'show-more', t('timeline.show.more'));
    on(toggle, () => {
      const collapsed = bubble.classList.toggle('collapsed');
      toggle.textContent = collapsed ? t('timeline.show.more') : t('timeline.show.less');
    });
    meta.appendChild(toggle);
  }
  meta.appendChild(el('span', null, clock(new Date())));
  meta.appendChild(iconButton('copy', t('common.copy'), () => send({ op: 'copy', text })));
  row.appendChild(meta);
  return row;
}

export function assistantRow(text: string): AssistantRow {
  const row: AssistantRow = { el: el('div', 'assistant-row'), body: el('div', 'markdown'), rawText: text || '' };
  row.el.appendChild(row.body);
  const meta = el('div', 'hover-meta');
  meta.appendChild(iconButton('copy', t('common.copy'), () => send({ op: 'copy', text: row.rawText })));
  row.el.appendChild(meta);
  return row;
}

/** Adds a finished text block of the agent's to the running turn. */
export function addAssistantText(t: Thread, text: string): void {
  const turn = currentTurn(t);
  clearThinking(t);
  const row = assistantRow(text);
  markdown(row.body, text, true);
  addItem(turn, row.el, 'text');
  turn.lastText = row;
  turn.group = null;
}

/** Thinking rows only show while Claude thinks; output replaces them. */
export function clearThinking(t: Thread): void {
  for (const row of t.thinkingByIndex.values()) row.remove();
  t.thinkingByIndex.clear();
  if (t.turn) t.turn.items = t.turn.items.filter(item => item.node.isConnected);
}

export function addThinking(thread: Thread, blockId: string): void {
  clearThinking(thread);
  const row = el('div', 'row');
  row.appendChild(rowIcon('brain'));
  row.appendChild(el('span', 'row-label shine', t('timeline.thinking')));
  addItem(currentTurn(thread), row, 'live');
  thread.thinkingByIndex.set(blockId, row);
}

export function addNote(t: Thread, text: string): void {
  addItem(currentTurn(t), el('div', 'note', text), 'note');
}

function makeEntry(t: Thread, tool: ToolStarted): ToolEntry {
  const input = tool.input || {};
  const { details, subList } = entryDetails(tool.name, tool.kind, input);
  const entry: ToolEntry = {
    thread: t,
    id: tool.id,
    name: tool.name,
    input,
    category: tool.kind,
    title: tool.title,
    status: 'running',
    isTask: false,
    isNew: false,
    added: 0,
    removed: 0,
    group: null,
    el: el('div', 'entry'),
    row: el('div', 'row interactive'),
    label: el('span', 'row-label'),
    stat: el('span', 'row-stat'),
    details,
    subList
  };
  const x = el('span', 'row-x');
  x.appendChild(icon('x', 'tiny'));
  entry.row.append(rowIcon(CATEGORY_ICONS[entry.category]), entry.label, entry.stat, x, chevron());
  details.hidden = true;
  entry.el.append(entry.row, details);
  on(entry.row, () => {
    details.hidden = !details.hidden;
    entry.row.classList.toggle('open', !details.hidden);
  });
  if (entry.category === 'todo') {
    details.hidden = false;
    entry.row.classList.add('open');
  }
  updateEntry(entry);
  return entry;
}

export function updateEntry(entry: Entry): void {
  if (entry.isTask) {
    renderTask(entry);
    return;
  }
  renderLabel(entry.label, entryLabel(entry));
  entry.label.classList.toggle('shine', entry.status === 'running' && entry.thread.busy);
  entry.el.classList.toggle('error', entry.status === 'error');
  entry.el.classList.toggle('declined', entry.status === 'declined');
  entry.stat.textContent = '';
  if (entry.added) entry.stat.appendChild(el('span', 'stat-add', '+' + entry.added + ' '));
  if (entry.removed) entry.stat.appendChild(el('span', 'stat-del', '−' + entry.removed));
  if (entry.group) updateGroup(entry.group);
}

function makeGroup(t: Thread): WorkGroup {
  const group: WorkGroup = {
    thread: t,
    entries: [],
    el: el('div', 'work-group collapsed'),
    head: el('div', 'row interactive group-head'),
    icon: el('span', 'row-icon'),
    label: el('span', 'row-label'),
    list: el('div', 'group-list')
  };
  group.head.append(group.icon, group.label, chevron());
  group.el.append(group.head, group.list);
  on(group.head, () => {
    const collapsed = group.el.classList.toggle('collapsed');
    group.head.classList.toggle('open', !collapsed);
  });
  return group;
}

/** E.g. "Read 2 files, ran a command, and used 1 tool". */
function groupSummary(group: WorkGroup): string {
  const counts: Partial<Record<ToolKind, number>> = {};
  for (const entry of group.entries) counts[entry.category] = (counts[entry.category] || 0) + 1;
  const parts: string[] = [];
  if (counts.read) parts.push(t('timeline.group.read', counts.read));
  if (counts.edit) parts.push(t('timeline.group.edit', counts.edit));
  if (counts.command) parts.push(t('timeline.group.command', counts.command));
  if (counts.search) parts.push(t('timeline.group.search', counts.search));
  if (counts.web) parts.push(t('timeline.group.web', counts.web));
  const others =
    (counts.agent || 0) + (counts.todo || 0) + (counts.question || 0) + (counts.plan || 0) + (counts.other || 0);
  if (others) parts.push(t('timeline.group.other', others));
  const summary = new Intl.ListFormat(document.documentElement.lang || undefined, { type: 'conjunction' }).format(
    parts
  );
  return summary.charAt(0).toUpperCase() + summary.slice(1);
}

function updateGroup(group: WorkGroup): void {
  const isMulti = group.entries.length > 1;
  group.el.classList.toggle('multi', isMulti);
  if (!isMulti) return;
  const categories = new Set(group.entries.map(entry => entry.category));
  group.icon.textContent = '';
  group.icon.appendChild(icon(categories.size === 1 ? CATEGORY_ICONS[[...categories][0]] : 'hammer'));
  const running = group.entries.find(entry => entry.status === 'running');
  if (running && group.thread.busy) {
    renderLabel(group.label, entryLabel(running));
    group.label.classList.add('shine');
  } else {
    group.label.textContent = groupSummary(group);
    group.label.classList.remove('shine');
  }
}

/** Adds a task card to the running turn, outside any work group. */
export function addTaskToTurn(t: Thread, card: TaskCard): void {
  const turn = currentTurn(t);
  turn.group = null;
  card.turn = turn;
  addItem(turn, card.el, 'task');
}

/**
 * Shows a tool call: inside the subagent that made it, as a task card, on its own (todos, plans) or in the turn's
 * work group.
 */
export function addToolUse(t: Thread, tool: ToolStarted): void {
  const parentId = tool.parentId;
  const parent = parentId ? t.entries.get(parentId) : undefined;
  if (tool.kind === 'agent') {
    const card = makeTaskCard(t, tool);
    t.entries.set(tool.id, card);
    for (const task of t.tasks.values()) {
      if (task.toolUseId === tool.id) card.task = task;
    }
    if (parentId) {
      parent?.subList.appendChild(card.el);
      return;
    }
    currentTurn(t);
    clearThinking(t);
    addTaskToTurn(t, card);
    return;
  }
  const entry = makeEntry(t, tool);
  t.entries.set(tool.id, entry);
  if (parentId) {
    if (parent) {
      parent.subList.appendChild(entry.el);
      if (parent.isTask) refreshTaskPanel(parent);
    }
    return;
  }
  const turn = currentTurn(t);
  clearThinking(t);
  if (entry.category === 'todo' || entry.category === 'plan') {
    turn.group = null;
    addItem(turn, entry.el, 'entry');
    return;
  }
  if (!turn.group) {
    turn.group = makeGroup(t);
    addItem(turn, turn.group.el, 'group');
  }
  entry.group = turn.group;
  turn.group.entries.push(entry);
  turn.group.list.appendChild(entry.el);
  updateGroup(turn.group);
}

/** A sub-step's text inside the task that wrote it. */
export function addSubText(t: Thread, parentId: string, text: string): void {
  t.entries.get(parentId)?.subList.appendChild(el('div', 'sub-text', firstLine(text)));
}

export function attachResult(t: Thread, result: ToolFinished): void {
  const change = result.change;
  const entry = t.entries.get(result.id);
  if (!entry) return;
  if (entry.isTask) {
    entry.status = result.isError ? 'error' : 'done';
    entry.resultText = result.output;
    onTaskResult(entry);
    return;
  }
  entry.status = t.declined.has(result.id) ? 'declined' : result.isError ? 'error' : 'done';
  if (change) {
    entry.isNew = change.isNew;
    entry.added += change.added;
    entry.removed += change.removed;
    const turn = currentTurn(t);
    const previous = turn.changes.get(change.path);
    turn.changes.set(change.path, {
      path: change.path,
      added: (previous ? previous.added : 0) + change.added,
      removed: (previous ? previous.removed : 0) + change.removed,
      isNew: previous ? previous.isNew : change.isNew
    });
  }
  const text = result.output;
  const showsOutput = entry.category !== 'read' && entry.category !== 'edit' && entry.category !== 'todo';
  if (text && (showsOutput || result.isError)) {
    entry.details.insertBefore(
      el('pre', 'panel' + (result.isError ? ' error' : ''), truncate(text, OUTPUT_LIMIT)),
      entry.subList
    );
  }
  updateEntry(entry);
}

/**
 * Ends the running turn: settles what still streams or runs, folds the work above the final answer and adds the
 * changed files. @param result how the turn ended, null when the process ended it
 */
export function finishTurn(t: Thread, result: TurnEnded | null): void {
  const turn = t.turn;
  if (!turn || turn.done) return;
  turn.done = true;
  clearThinking(t);
  for (const entries of t.live.values()) {
    for (const live of entries) {
      if (!live.done) {
        live.done = true;
        markdown(live.row.body, live.text, true);
      }
    }
  }
  t.live.clear();
  for (const entry of t.entries.values()) {
    if (entry.status === 'running') entry.status = turn.stopped ? 'declined' : 'done';
    updateEntry(entry);
  }
  if (turn.working) {
    turn.working.remove();
    turn.working = null;
  }
  const took = result && result.durationMs ? result.durationMs : Date.now() - turn.startedAt;
  foldWork(turn, turn.replayed && !result ? null : took);
  turn.lastText?.el.classList.add('final');
  if (result && result.error && !turn.stopped) {
    turn.el.appendChild(inlineAlert(result.error));
    t.failed = true;
  }
  if (turn.changes.size) turn.el.appendChild(changesCard(t, turn));
}

/** Folds a finished turn's work under "Worked for …", leaving the final text visible. */
function foldWork(turn: Turn, took: number | null): void {
  const items = turn.items.filter(
    item =>
      item.node.isConnected &&
      item.kind !== 'completion' &&
      !(item.kind === 'task' && item.node.classList.contains('running'))
  );
  const last = items[items.length - 1];
  const foldable = last && last.kind === 'text' ? items.slice(0, -1) : items;
  if (!foldable.some(item => item.kind !== 'text')) return;
  const fold = el('div', 'fold');
  const head = el('button', 'fold-head');
  const label = turn.stopped
    ? t('timeline.stopped', duration(took || 0))
    : took
      ? t('timeline.worked.for', duration(took))
      : t('timeline.worked');
  head.append(el('span', null, label), chevron('small'));
  const body = el('div', 'fold-body');
  fold.append(head, body);
  on(head, () => fold.classList.toggle('open'));
  turn.el.insertBefore(fold, foldable[0].node);
  for (const item of foldable) body.appendChild(item.node);
}

function changesCard(thread: Thread, turn: Turn): HTMLElement {
  const card = el('div', 'changes');
  const head = el('div', 'changes-head');
  let added = 0;
  let removed = 0;
  for (const change of turn.changes.values()) {
    added += change.added;
    removed += change.removed;
  }
  head.appendChild(el('span', 'title', t('timeline.changes', turn.changes.size)));
  head.appendChild(el('span', 'stat-add', '+' + added));
  head.appendChild(el('span', 'stat-del', '−' + removed));
  if (!turn.replayed) {
    const open = el('button', 'ghost-button');
    open.append(icon('file-diff', 'small'), el('span', null, t('timeline.open.diff')));
    head.appendChild(on(open, () => sendThread(thread, { op: 'turnDiff', turn: turn.no })));
  }
  card.appendChild(head);
  const list = el('div', 'changes-list');
  for (const change of turn.changes.values()) {
    const row = el('div', 'change-row');
    row.appendChild(icon('file-text', 'small'));
    const path = el('span', 'path');
    path.appendChild(document.createTextNode('‎' + relative(change.path)));
    row.appendChild(path);
    if (change.isNew) row.appendChild(el('span', 'badge-new', t('timeline.new')));
    row.appendChild(el('span', 'stat-add', '+' + change.added));
    row.appendChild(el('span', 'stat-del', '−' + change.removed));
    row.title = turn.replayed ? t('timeline.open.file') : t('timeline.show.diff');
    on(row, () =>
      turn.replayed
        ? sendThread(thread, { op: 'open', path: change.path, line: 1 })
        : sendThread(thread, { op: 'turnDiff', turn: turn.no, path: change.path })
    );
    list.appendChild(row);
  }
  card.appendChild(list);
  return card;
}

/** Updates the "Working for …" line under the running turn. */
export function renderWorking(thread: Thread): void {
  const turn = thread.turn;
  if (!turn || !turn.working) return;
  turn.working.hidden = !thread.busy;
  if (thread.busy) {
    turn.working.textContent = thread.approvals.length
      ? t('timeline.working.waiting', elapsed(thread.startedAt))
      : t('timeline.working', elapsed(thread.startedAt));
  }
}

/** A plan Claude proposes (ExitPlanMode), long ones collapsed. */
export function planCard(plan: string): HTMLElement {
  const card = el('div', 'plan-card');
  const head = el('div', 'plan-head');
  head.appendChild(el('span', 'badge', t('timeline.plan')));
  head.appendChild(el('span', null, t('timeline.plan.title')));
  const spacer = el('span');
  spacer.style.flex = '1';
  head.appendChild(spacer);
  head.appendChild(iconButton('copy', t('timeline.plan.copy'), () => send({ op: 'copy', text: plan })));
  card.appendChild(head);
  const body = el('div', 'plan-body markdown');
  markdown(body, plan, true);
  card.appendChild(body);
  if (plan.split('\n').length > PLAN_COLLAPSE_LINES) {
    card.classList.add('collapsed');
    const expand = el('button', 'button outline plan-expand', t('timeline.plan.expand'));
    on(expand, () => {
      const collapsed = card.classList.toggle('collapsed');
      expand.textContent = collapsed ? t('timeline.plan.expand') : t('timeline.plan.collapse');
    });
    card.appendChild(expand);
  }
  return card;
}
