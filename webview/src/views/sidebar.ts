/** The thread list: threads grouped by project folder, archived ones apart, each with its live status. */
import { send, type ProjectItem, type SessionItem } from '../bridge/protocol';
import { agentName } from '../composer/controls';
import type { Thread, ThreadRow } from '../state/model';
import { projectList, projectOf } from '../state/projects';
import { isActive, state, threads, workspace } from '../state/store';
import { runningTasks } from '../thread/tasks';
import { activate } from '../thread/threads';
import { $, chevron, el, icon, iconButton, spinner } from '../ui/dom';
import { ago, elapsed, money } from '../ui/format';
import { t } from '../ui/i18n';
import { openMenu } from '../ui/menu';
import { projectIcon, projectIconUrl } from '../ui/widgets';

/** Below this width the sidebar overlays the chat and closes after picking a thread. */
export const NARROW = 640;
const SIDEBAR_KEY = 'tako.sidebar';

type StatusKind = 'input' | 'working' | 'error' | 'done' | 'idle';

interface ProjectGroup {
  el: HTMLElement;
  head: HTMLElement;
  list: HTMLElement;
  iconSlot: HTMLElement;
  name: HTMLElement;
  count: HTMLElement;
  primary: boolean;
  iconKey: string | null;
}

const groups = new Map<string, ProjectGroup>();
let showArchived = false;
let isScheduled = false;

/** Re-renders the list on the next frame, once however often it is asked for. */
export function scheduleSidebar(): void {
  if (isScheduled) return;
  isScheduled = true;
  requestAnimationFrame(() => {
    isScheduled = false;
    renderSidebar();
  });
}

export function setSidebarOpen(open: boolean): void {
  document.body.classList.toggle('sidebar-open', open);
  try {
    localStorage.setItem(SIDEBAR_KEY, open ? '1' : '0');
  } catch (ignored) {}
}

/** Open on wide windows unless the person closed it last time. */
export function restoreSidebar(): void {
  let open = window.innerWidth > NARROW;
  try {
    const stored = localStorage.getItem(SIDEBAR_KEY);
    if (stored !== null && window.innerWidth > NARROW) open = stored === '1';
  } catch (ignored) {}
  document.body.classList.toggle('sidebar-open', open);
}

export function toggleArchived(): void {
  showArchived = !showArchived;
  renderSidebar();
}

/** Drops the groups of folders that are gone or changed role. */
export function pruneProjectGroups(projects: ProjectItem[]): void {
  for (const [path, group] of [...groups]) {
    if (!projects.some(project => project.path === path && project.primary === group.primary)) {
      group.el.remove();
      groups.delete(path);
    }
  }
}

export function showSessions(items: SessionItem[]): void {
  const several = workspace.projects.length > 1;
  const agents = workspace.agents.length > 1;
  const entries = items.map(item => ({
    label: item.title,
    description:
      (agents ? item.agentName + ' · ' : '') +
      (several && item.projectName ? item.projectName + ' · ' : '') +
      new Date(item.lastModified).toLocaleString(),
    icon: 'message-circle' as const,
    run: () =>
      send({ op: 'resume', sessionId: item.id, title: item.title, projectPath: item.projectPath, agent: item.agent })
  }));
  const anchor = document.body.classList.contains('sidebar-open') ? $('sidebar-history') : $('sidebar-toggle');
  openMenu(anchor, entries, 'below', t('sidebar.history'));
}

function threadStatus(thread: Thread): { kind: StatusKind; label: string } {
  const approval = thread.approvals[0];
  if (approval) {
    if (approval.prompt.kind === 'question') return { kind: 'input', label: t('sidebar.status.question') };
    if (approval.prompt.kind === 'plan') return { kind: 'input', label: t('sidebar.status.plan') };
    return { kind: 'input', label: t('sidebar.status.approval') };
  }
  if (thread.busy) return { kind: 'working', label: t('sidebar.status.working', elapsed(thread.startedAt)) };
  const background = runningTasks(thread).length;
  if (background) return { kind: 'working', label: t('sidebar.status.background', background) };
  if (thread.failed) return { kind: 'error', label: t('sidebar.status.failed') };
  if (thread.finished)
    return { kind: 'done', label: thread.unread ? t('sidebar.status.done.new') : t('sidebar.status.done') };
  return { kind: 'idle', label: thread.turnCount ? t('sidebar.status.idle') : t('sidebar.status.not.started') };
}

function statusIcon(kind: StatusKind, t: Thread): Element {
  switch (kind) {
    case 'working':
      return spinner();
    case 'input':
      return icon(t.approvals[0]?.prompt.kind === 'question' ? 'message-circle' : 'circle-alert', 'small');
    case 'error':
      return icon('circle-alert', 'small');
    case 'done':
      return t.unread ? el('span', 'unread-dot') : icon('circle-check', 'small');
    case 'idle':
      return el('span', 'idle-dot');
  }
}

function threadActions(thread: Thread): HTMLElement {
  const actions = el('div', 'thread-actions');
  if (thread.archived) {
    actions.appendChild(
      iconButton('archive-restore', t('sidebar.restore'), () =>
        send({ op: 'archive', thread: thread.id, archived: false })
      )
    );
  } else {
    actions.appendChild(
      iconButton('archive', t('sidebar.archive', agentName(thread)), () =>
        send({ op: 'archive', thread: thread.id, archived: true })
      )
    );
  }
  actions.appendChild(
    iconButton('x', t('sidebar.close'), () => {
      if (thread.busy && !window.confirm(t('sidebar.close.confirm', agentName(thread)))) return;
      send({ op: 'close', thread: thread.id });
    })
  );
  return actions;
}

function threadRow(t: Thread): ThreadRow {
  if (t.row) return t.row;
  const row: ThreadRow = {
    el: el('div', 'thread'),
    status: el('span', 'thread-status'),
    title: el('span', 'thread-title'),
    time: el('span', 'thread-time'),
    sub: el('span', 'thread-sub'),
    git: el('span', 'thread-git'),
    actions: null,
    statusKey: null,
    gitKey: null,
    archived: null
  };
  row.el.append(row.status, row.title, row.time, row.sub, row.git);
  row.el.addEventListener('click', () => {
    if (t.archived) {
      send({ op: 'archive', thread: t.id, archived: false });
      return;
    }
    activate(t);
    send({ op: 'select', thread: t.id });
  });
  t.row = row;
  return row;
}

function gitPart(iconName: 'folder-git-2' | 'git-branch', text: string, className: string): HTMLElement {
  const part = el('span', className);
  part.appendChild(icon(iconName, 'tiny'));
  part.appendChild(el('span', null, text));
  return part;
}

function updateRow(thread: Thread): ThreadRow {
  const row = threadRow(thread);
  const status = threadStatus(thread);
  const className =
    'thread status-' +
    status.kind +
    (isActive(thread) ? ' active' : '') +
    (thread.unread ? ' unread' : '') +
    (thread.archived ? ' archived' : '');
  if (row.el.className !== className) row.el.className = className;
  const statusKey = status.kind + '|' + thread.unread + '|' + (thread.approvals[0]?.prompt.kind || '');
  if (row.statusKey !== statusKey) {
    row.statusKey = statusKey;
    row.status.textContent = '';
    row.status.appendChild(statusIcon(status.kind, thread));
  }
  if (row.title.textContent !== thread.title) {
    row.title.textContent = thread.title;
    row.title.title = thread.title;
  }
  const time = ago(thread.updatedAt);
  if (row.time.textContent !== time) row.time.textContent = time;
  const otherAgent =
    workspace.agents.length && thread.agent !== workspace.agents[0].id ? ' · ' + agentName(thread) : '';
  const sub =
    (thread.archived ? t('sidebar.archived') : status.label) +
    otherAgent +
    (thread.costUsd ? ' · ' + money(thread.costUsd) : '');
  if (row.sub.textContent !== sub) row.sub.textContent = sub;
  const pr = thread.pullRequest;
  const gitKey = (thread.branch || '') + '|' + (thread.worktree || '') + '|' + (pr ? pr.number + pr.state : '');
  if (row.gitKey !== gitKey) {
    row.gitKey = gitKey;
    row.git.textContent = '';
    row.git.hidden = !thread.branch && !thread.worktree;
    if (thread.worktree) row.git.appendChild(gitPart('folder-git-2', thread.worktree, 'thread-git-part worktree'));
    if (thread.branch) row.git.appendChild(gitPart('git-branch', thread.branch, 'thread-git-part'));
    if (pr) row.git.appendChild(el('span', 'thread-git-part pr ' + pr.state, pr.number));
    row.git.title = [
      thread.worktree ? t('header.worktree', thread.worktree) : '',
      thread.branch ? t('header.branch', thread.branch) : '',
      pr ? pr.number + ' ' + pr.title : ''
    ]
      .filter(Boolean)
      .join('\n');
  }
  if (row.archived !== thread.archived) {
    row.archived = thread.archived;
    row.actions?.remove();
    row.actions = threadActions(thread);
    row.el.appendChild(row.actions);
  }
  return row;
}

function projectGroup(project: ProjectItem): ProjectGroup {
  const existing = groups.get(project.path);
  if (existing) return existing;
  const group: ProjectGroup = {
    el: el('div', 'project-group'),
    head: el('div', 'project-head'),
    list: el('div', 'project-threads'),
    iconSlot: el('span', 'project-icon-slot'),
    name: el('span', 'project-name'),
    count: el('span', 'project-count'),
    primary: !!project.primary,
    iconKey: null
  };
  group.head.append(chevron(), group.iconSlot, group.name, group.count);
  const actions = el('div', 'project-actions');
  actions.appendChild(
    iconButton('plus', t('sidebar.project.new.thread'), () => send({ op: 'newThread', projectPath: project.path }))
  );
  if (!project.primary) {
    actions.appendChild(
      iconButton('folder-minus', t('sidebar.project.remove'), () => send({ op: 'removeProject', path: project.path }))
    );
  }
  group.head.appendChild(actions);
  group.head.addEventListener('click', () => {
    const current = projectList().find(candidate => candidate.path === project.path) || project;
    current.collapsed = !current.collapsed;
    send({ op: 'collapseProject', path: current.path, collapsed: current.collapsed });
    renderSidebar();
  });
  group.el.append(group.head, group.list);
  groups.set(project.path, group);
  return group;
}

/** Puts exactly these rows into the list, in order, moving only what moved. */
function placeRows(list: HTMLElement, rows: HTMLElement[]): void {
  rows.forEach((row, index) => {
    if (list.children[index] !== row) list.insertBefore(row, list.children[index] || null);
  });
  while (list.children.length > rows.length) list.lastChild?.remove();
}

export function renderSidebar(): void {
  const sorted = [...threads.values()].sort((a, b) => b.updatedAt - a.updatedAt);
  const projects = projectList();
  const rowsByProject = new Map<string, HTMLElement[]>(projects.map(project => [project.path, []]));
  const archivedRows: HTMLElement[] = [];
  for (const thread of sorted) {
    const row = updateRow(thread);
    if (thread.archived) archivedRows.push(row.el);
    else rowsByProject.get(projectOf(thread).path)?.push(row.el);
  }
  const groupEls: HTMLElement[] = [];
  for (const project of projects) {
    const group = projectGroup(project);
    const rows = rowsByProject.get(project.path) || [];
    group.name.textContent = project.name;
    group.head.title = project.path;
    const iconKey = projectIconUrl(project) || 'folder';
    if (group.iconKey !== iconKey) {
      group.iconKey = iconKey;
      group.iconSlot.textContent = '';
      group.iconSlot.appendChild(projectIcon(project, 'small'));
    }
    group.count.textContent = rows.length ? String(rows.length) : '';
    group.el.classList.toggle('collapsed', !!project.collapsed);
    group.el.classList.toggle('has-active', !!state.active?.row && rows.includes(state.active.row.el));
    placeRows(group.list, project.collapsed ? [] : rows);
    groupEls.push(group.el);
  }
  placeRows($('thread-list'), groupEls);
  placeRows($('archived-list'), archivedRows);
  $('archived-section').hidden = archivedRows.length === 0;
  const toggle = $('archived-toggle');
  const label = t('sidebar.archived.count', archivedRows.length);
  if (toggle.dataset.label !== label) {
    toggle.dataset.label = label;
    toggle.textContent = '';
    toggle.append(chevron(), icon('archive', 'small'), el('span', null, label));
  }
  toggle.classList.toggle('open', showArchived);
  $('archived-list').hidden = !showArchived;
  const waiting = sorted.filter(
    thread => !thread.archived && thread !== state.active && (thread.approvals.length || thread.unread)
  ).length;
  const toggleButton = $('sidebar-toggle');
  toggleButton.title = waiting ? t('sidebar.threads.attention', waiting) : t('sidebar.threads');
  toggleButton.classList.toggle('attention', waiting > 0);
}
