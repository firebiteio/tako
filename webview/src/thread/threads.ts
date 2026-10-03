/** Threads: creating them from the IDE's records, switching between them and their lifecycle. */
import type { AgentEvent, AgentInfo } from '../bridge/agent';
import { sendThread, type ThreadItem } from '../bridge/protocol';
import { cancelAllApprovals, renderDrawer } from '../composer/approvals';
import { autosize, hidePopup, input, renderAttachments, renderChips, renderSend } from '../composer/composer';
import { renderContextControls } from '../composer/contextMeter';
import { agentName, renderControls } from '../composer/controls';
import type { Thread } from '../state/model';
import { rootOf } from '../state/projects';
import { isActive, state, threads, workspace } from '../state/store';
import { $, el } from '../ui/dom';
import { t } from '../ui/i18n';
import { closeMenu } from '../ui/menu';
import { showAlert } from '../ui/widgets';
import { renderGit, renderMeta, renderProjectName } from '../views/header';
import { NARROW, scheduleSidebar, setSidebarOpen } from '../views/sidebar';
import { handleEvent } from './stream';
import { closeTaskPanel, renderTasksTray, stopAllTasks } from './tasks';
import { finishTurn, follow, renderWorking, scroller, setDraft } from './timeline';

export function makeThread(item: ThreadItem): Thread {
  const thread: Thread = {
    id: item.id,
    title: item.title || t('common.new.chat'),
    archived: !!item.archived,
    updatedAt: item.updatedAt || Date.now(),
    el: el('div', 'timeline'),
    alertsEl: el('div', 'alert-stack'),
    row: null,
    turnNo: 0,
    turn: null,
    turnCount: 0,
    entries: new Map(),
    declined: new Set(),
    live: new Map(),
    liveByIndex: new Map(),
    thinkingByIndex: new Map(),
    currentMessageId: undefined,
    contexts: [],
    images: [],
    attachQueue: Promise.resolve(),
    approvals: [],
    question: null,
    busy: false,
    startedAt: 0,
    contextUsage: null,
    liveContext: null,
    cache: null,
    tasks: new Map(),
    finished: false,
    unread: false,
    failed: false,
    model: null,
    mode: 'default',
    effort: null,
    costUsd: item.costUsd || 0,
    tokens: item.tokens || 0,
    branch: item.branch || null,
    worktree: item.worktree || null,
    pullRequest: item.pullRequest || null,
    projectPath: item.projectPath || null,
    projectRoot: null,
    agent: item.agent,
    hasSession: !!item.hasSession,
    info: null,
    contextWindow: null,
    draft: '',
    stick: true,
    scrollTop: 0,
    replaying: false
  };
  thread.el.hidden = true;
  $('timelines').appendChild(thread.el);
  threads.set(thread.id, thread);
  return thread;
}

export function updateThread(t: Thread, item: ThreadItem): void {
  t.title = item.title || t.title;
  t.archived = !!item.archived;
  t.updatedAt = item.updatedAt || t.updatedAt;
  t.costUsd = item.costUsd || 0;
  t.tokens = item.tokens || 0;
  t.branch = item.branch || null;
  t.worktree = item.worktree || null;
  t.projectPath = item.projectPath || null;
  t.pullRequest = item.pullRequest || null;
  t.hasSession = !!item.hasSession;
  if (item.agent && item.agent !== t.agent) {
    t.agent = item.agent;
    t.info = null;
    t.model = null;
    t.effort = null;
    t.contextWindow = null;
    if (isActive(t)) renderControls();
  }
  if (isActive(t)) {
    $('thread-title').textContent = t.title;
    renderMeta();
    renderGit();
  }
  scheduleSidebar();
}

export function removeThread(id: string): void {
  const t = threads.get(id);
  if (!t) return;
  t.el.remove();
  t.alertsEl.remove();
  t.row?.el.remove();
  threads.delete(id);
  if (state.active === t) state.active = null;
  scheduleSidebar();
}

/** Shows the thread, keeping the one it replaces as it was: its draft, alerts and scroll position. */
export function activate(t: Thread | undefined): void {
  const previous = state.active;
  if (!t || previous === t) return;
  if (previous) {
    previous.draft = input.value;
    previous.el.hidden = true;
    previous.alertsEl.remove();
  }
  closeTaskPanel();
  state.active = t;
  workspace.renderRoot = rootOf(t);
  renderProjectName();
  t.el.hidden = false;
  t.unread = false;
  $('alerts').appendChild(t.alertsEl);
  input.value = t.draft;
  $('thread-title').textContent = t.title;
  hidePopup();
  closeMenu();
  autosize();
  renderChips();
  renderAttachments();
  renderDrawer();
  renderControls();
  renderMeta();
  renderGit();
  renderContextControls();
  renderTasksTray();
  setDraft(t.turnCount === 0);
  scroller.scrollTop = t.stick ? scroller.scrollHeight : t.scrollTop;
  if (window.innerWidth <= NARROW) setSidebarOpen(false);
  scheduleSidebar();
  input.focus();
}

export function setBusy(t: Thread, busy: boolean): void {
  t.busy = busy;
  if (busy && !t.startedAt) t.startedAt = Date.now();
  if (!busy) t.startedAt = 0;
  renderWorking(t);
  if (isActive(t)) {
    renderSend();
    renderContextControls();
  }
  scheduleSidebar();
}

/** Advances the busy threads' clocks; called every second. */
export function tickBusy(): void {
  let anyBusy = false;
  for (const t of threads.values()) {
    if (t.busy) {
      anyBusy = true;
      renderWorking(t);
    }
  }
  if (anyBusy) scheduleSidebar();
}

/** Remembers where the person scrolled the shown thread, and whether to keep following its end. */
export function onScroll(): void {
  const t = state.active;
  if (!t) return;
  t.stick = scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight < 80;
  t.scrollTop = scroller.scrollTop;
  $('scroll-end').hidden = t.stick || document.body.classList.contains('draft');
}

export function scrollToEnd(): void {
  const t = state.active;
  if (!t) return;
  t.stick = true;
  follow(t);
}

function resetThread(t: Thread): void {
  t.el.textContent = '';
  t.turn = null;
  t.turnNo = 0;
  t.turnCount = 0;
  t.entries.clear();
  t.declined.clear();
  t.live.clear();
  t.liveByIndex.clear();
  t.thinkingByIndex.clear();
  t.finished = false;
  t.failed = false;
  cancelAllApprovals(t);
  setBusy(t, false);
}

/** Rebuilds the timeline from the thread's transcript. */
export function replay(t: Thread, events: AgentEvent[]): void {
  resetThread(t);
  t.replaying = true;
  for (const event of events) handleEvent(t, event);
  finishTurn(t, null);
  t.replaying = false;
  t.finished = false;
  t.stick = true;
  if (isActive(t)) setDraft(t.turnCount === 0);
  follow(t);
}

/** The thread's agent is ready: what it can do, and which model and mode it runs with. */
export function onInit(t: Thread, info: AgentInfo, projectRoot: string | undefined): void {
  if (projectRoot) {
    t.projectRoot = projectRoot;
    workspace.renderRoot = projectRoot;
  }
  t.info = info;
  const wanted = info.model || '';
  const models = info.models;
  const match = models.find(model => model.value === wanted) || models.find(model => model.resolvedModel === wanted);
  if (!t.model || !models.some(model => model.value === t.model)) {
    t.model = match ? match.value : models[0]?.value || null;
  }
  if (info.mode) t.mode = info.mode;
  if (info.effort) t.effort = info.effort;
  if (isActive(t)) renderControls();
}

/** The process ended; a crash offers a restart. */
export function onExit(thread: Thread, code: number, stderr: string): void {
  if (thread.turn && !thread.turn.done) finishTurn(thread, null);
  setBusy(thread, false);
  cancelAllApprovals(thread);
  stopAllTasks(thread);
  if (code !== 0 && stderr) {
    thread.failed = true;
    showAlert(thread, t('thread.exited', agentName(thread), code, stderr.trim()), {
      label: t('thread.restart'),
      run: () => sendThread(thread, { op: 'restart' })
    });
  }
}

export function onThreadError(t: Thread, text: string): void {
  setBusy(t, false);
  t.failed = true;
  showAlert(t, text);
}
