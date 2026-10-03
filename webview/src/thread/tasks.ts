/** Tasks: subagents, workflows and background shells, as cards in the turn, a detail panel and a tray of pills. */
import type { TaskUpdated, ToolStarted, WorkflowAgent } from '../bridge/agent';
import type { Entry, Task, TaskCard, TaskType, Thread } from '../state/model';
import { isActive, state } from '../state/store';
import { $, chevron, el, icon, iconButton, on, rowIcon, setText } from '../ui/dom';
import { duration, tokens, truncate } from '../ui/format';
import { t } from '../ui/i18n';
import type { IconName } from '../ui/icons';
import { markdown } from '../ui/markdown';
import { scheduleSidebar } from '../views/sidebar';
import { addItem, addTaskToTurn, currentTurn, updateEntry } from './timeline';
import { OUTPUT_LIMIT, renderLabel, Tool } from './tools';

type TaskState = 'running' | 'done' | 'error';

const TASK_DONE: ReadonlySet<string> = new Set(['completed', 'done', 'success']);
const TASK_FAILED: ReadonlySet<string> = new Set(['failed', 'error', 'killed', 'stopped', 'cancelled']);
/** How each type of task is named and drawn. */
const TASK_TYPES: Record<TaskType, { kind: string; icon: IconName; title: string }> = {
  agent: { kind: t('tasks.agent'), icon: 'bot', title: t('tasks.agent.title') },
  shell: { kind: t('tasks.shell'), icon: 'square-terminal', title: t('tasks.shell.title') },
  workflow: { kind: t('tasks.workflow'), icon: 'workflow', title: t('tasks.workflow.title') }
};

function typeOf(task: Task): TaskType {
  return task.type === 'shell' || task.type === 'workflow' ? task.type : 'agent';
}

interface PanelSection {
  el: HTMLElement;
  title: HTMLElement;
  count: HTMLElement;
}

interface AgentRow {
  el: HTMLElement;
  stateIcon: HTMLElement;
  label: HTMLElement;
  meta: HTMLElement;
  detail: HTMLElement;
  state: string | null;
  detailKey: string | null;
  promptOpen: boolean;
}

interface Phase {
  head: PanelSection;
  list: HTMLElement;
  rows: Map<number, AgentRow>;
}

/** The open panel's parts; built once per card, then updated in place. */
interface Panel {
  card: TaskCard;
  phases: Map<string | number, Phase>;
  logCount: number;
  resultShown: boolean;
  promptShown: boolean;
  technicalShown: boolean;
  statusKey: string | null;
  icon: HTMLElement;
  title: HTMLElement;
  status: HTMLElement;
  statusIcon: HTMLElement;
  statusText: HTMLElement;
  facts: HTMLElement;
  phasesBox: HTMLElement;
  stepsHead: PanelSection;
  logsHead: PanelSection;
  logs: HTMLElement;
  resultHead: PanelSection;
  result: HTMLElement;
  details: HTMLElement;
}

let openCard: TaskCard | null = null;
let panel: Panel | null = null;

/**
 * A task's state from its events; a foreground task (not backgrounded) is also over once its tool result came,
 * since the CLI may send the final event late or never.
 */
function taskState(task: Task | null | undefined, card: Entry | null | undefined): TaskState {
  if (task && TASK_DONE.has(task.status)) return 'done';
  if (task && TASK_FAILED.has(task.status)) return 'error';
  const resultCame = card && card.status !== 'running';
  if (task && !(resultCame && !task.backgrounded)) return 'running';
  if (!card) return 'running';
  return card.status === 'running' ? 'running' : card.status === 'error' ? 'error' : 'done';
}

function cardState(card: TaskCard): TaskState {
  return taskState(card.task, card);
}

/** The entry id of a task's card: its tool call, or a made-up one for tasks the stream showed no call for. */
function cardId(task: Task): string {
  return task.toolUseId || 'task-' + task.id;
}

function cardOf(t: Thread, task: Task): TaskCard | null {
  const entry = t.entries.get(cardId(task));
  return entry && entry.isTask ? entry : null;
}

export function runningTasks(t: Thread): Task[] {
  return [...t.tasks.values()].filter(task => taskState(task, t.entries.get(cardId(task))) === 'running');
}

function changed(t: Thread): void {
  if (isActive(t)) renderTasksTray();
  scheduleSidebar();
}

/** The process ended: nothing it started runs anymore. */
export function stopAllTasks(t: Thread): void {
  for (const task of t.tasks.values()) {
    if (taskState(task, t.entries.get(cardId(task))) !== 'running') continue;
    task.status = 'stopped';
    task.endedAt = task.endedAt || Date.now();
    const card = cardOf(t, task);
    if (card) {
      settleSteps(card);
      renderTask(card);
    }
  }
  for (const entry of t.entries.values()) {
    if (entry.isTask && entry.status === 'running' && !entry.task) {
      entry.status = 'error';
      renderTask(entry);
    }
  }
  changed(t);
}

/** Steps of a finished task that never got their result are over too. */
function settleSteps(card: TaskCard): void {
  for (const node of card.subList.querySelectorAll('.entry')) {
    for (const entry of card.thread.entries.values()) {
      if (entry.el === node && entry.status === 'running') {
        entry.status = 'done';
        updateEntry(entry);
      }
    }
  }
}

/** The task's tool call returned. */
export function onTaskResult(card: TaskCard): void {
  if (cardState(card) !== 'running') settleSteps(card);
  renderTask(card);
  changed(card.thread);
}

function taskKind(card: TaskCard): string {
  return TASK_TYPES[card.taskType].kind;
}

function taskIcon(card: TaskCard): IconName {
  return TASK_TYPES[card.taskType].icon;
}

function taskTitle(card: TaskCard): string {
  return (
    card.task?.description ||
    card.input.description ||
    card.input.title ||
    card.input.name ||
    TASK_TYPES[card.taskType].title
  );
}

export function makeTaskCard(
  t: Thread,
  block: ToolStarted,
  taskType: TaskType = block.name === Tool.Workflow ? 'workflow' : 'agent'
): TaskCard {
  const subList = el('div', 'sub-list task-steps');
  const card: TaskCard = {
    thread: t,
    id: block.id,
    name: block.name,
    input: block.input || {},
    category: 'agent',
    title: block.title,
    status: 'running',
    isTask: true,
    taskType,
    task: null,
    resultText: '',
    completionRow: null,
    turn: null,
    el: el('div', 'entry task-row'),
    row: el('div', 'row interactive'),
    label: el('span', 'row-label'),
    stat: el('span', 'row-stat task-stat'),
    state: el('span', 'task-state'),
    subList,
    details: subList
  };
  card.row.append(rowIcon(taskIcon(card)), card.label, card.stat, card.state, chevron());
  card.el.appendChild(card.row);
  on(card.row, () => openTaskPanel(card));
  renderTask(card);
  return card;
}

function doneAgents(task: Task): number {
  return [...task.agents.values()].filter(agent => agent.state === 'done').length;
}

function taskStats(card: TaskCard): string {
  const task = card.task;
  const parts: string[] = [];
  if (task && task.agents.size) parts.push(t('tasks.agents', doneAgents(task), task.agents.size));
  if (task && task.toolUses && !task.agents.size) parts.push(t('tasks.tools', task.toolUses));
  if (task && task.startedAt) {
    const end = task.endedAt || (cardState(card) === 'running' ? Date.now() : null);
    if (end) parts.push(duration(end - task.startedAt));
  }
  return parts.join(' · ');
}

function taskActivity(card: TaskCard): string {
  const task = card.task;
  const status = cardState(card);
  if (status === 'running') {
    const running = task ? [...task.agents.values()].filter(agent => agent.state === 'running') : [];
    if (running.length) return t('tasks.running', running.map(agent => agent.label).join(', '));
    return task && task.lastTool ? t('tasks.running', task.lastTool) : t('tasks.working');
  }
  if (task && task.summary) return task.summary;
  return status === 'error' ? t('tasks.failed') : t('tasks.completed');
}

function stateIcon(status: string): SVGSVGElement {
  return status === 'running' ? icon('loader-circle', 'tiny spin') : icon(status === 'done' ? 'check' : 'x', 'tiny');
}

export function renderTask(card: TaskCard): void {
  const status = cardState(card);
  card.el.className = 'entry task-row ' + status;
  renderLabel(card.label, [taskKind(card) + ' · ' + taskTitle(card)]);
  card.label.classList.toggle('shine', status === 'running');
  card.stat.textContent = taskStats(card);
  card.state.textContent = '';
  card.state.appendChild(stateIcon(status));
  card.row.title = t('tasks.click.for.details', taskActivity(card));
  if (card.completionRow) renderCompletionRow(card, card.completionRow);
  if (openCard === card) renderTaskPanel();
}

/** A task that ends after its turn gets a row at the end of the conversation saying so. */
function addCompletionRow(card: TaskCard): void {
  const row = el('div', 'entry task-row completion');
  row.appendChild(el('div', 'row interactive'));
  card.completionRow = row;
  on(row.firstChild as HTMLElement, () => openTaskPanel(card));
  addItem(currentTurn(card.thread), row, 'completion');
  renderCompletionRow(card, row);
}

function renderCompletionRow(card: TaskCard, completion: HTMLElement): void {
  const status = cardState(card);
  const row = completion.firstChild as HTMLElement;
  row.textContent = '';
  row.appendChild(rowIcon(status === 'error' ? 'circle-alert' : 'circle-check'));
  row.appendChild(
    el(
      'span',
      'row-label',
      status === 'error'
        ? t('tasks.completion.failed', taskKind(card), taskTitle(card))
        : t('tasks.completion.finished', taskKind(card), taskTitle(card))
    )
  );
  row.appendChild(el('span', 'row-stat task-stat', taskStats(card)));
  row.appendChild(chevron());
  completion.className = 'entry task-row completion ' + status;
}

function openTaskPanel(card: TaskCard): void {
  openCard = card;
  panel = null;
  $('task-panel').hidden = false;
  renderTaskPanel();
  $('task-panel').scrollTop = 0;
}

export function closeTaskPanel(): void {
  openCard = null;
  panel = null;
  $('task-panel').hidden = true;
}

/** Re-renders the panel if it shows this card. */
export function refreshTaskPanel(card: TaskCard): void {
  if (openCard === card) renderTaskPanel();
}

function panelSection(title: string): PanelSection {
  const section: PanelSection = {
    el: el('div', 'panel-section'),
    title: el('span', null, title),
    count: el('span', 'panel-count', '')
  };
  section.el.append(section.title, section.count);
  return section;
}

function agentStatus(agent: WorkflowAgent): string {
  return agent.state === 'done'
    ? 'done'
    : agent.state === 'error'
      ? 'error'
      : agent.state === 'start'
        ? 'queued'
        : 'running';
}

function buildTaskPanel(card: TaskCard): Panel {
  const root = $('task-panel');
  root.textContent = '';
  const head = el('div', 'task-panel-head');
  const parts: Panel = {
    card,
    phases: new Map(),
    logCount: 0,
    resultShown: false,
    promptShown: false,
    technicalShown: false,
    statusKey: null,
    icon: el('span', 'task-panel-icon'),
    title: el('div', 'task-panel-title'),
    status: el('div', 'task-panel-status'),
    statusIcon: el('span', 'status-icon'),
    statusText: el('span'),
    facts: el('div', 'task-panel-facts'),
    phasesBox: el('div', 'panel-phases'),
    stepsHead: panelSection(t('tasks.panel.steps')),
    logsHead: panelSection(t('tasks.panel.log')),
    logs: el('div', 'panel-logs'),
    resultHead: panelSection(t('tasks.panel.result')),
    result: el('div', 'markdown panel-result'),
    details: el('div')
  };
  parts.icon.appendChild(icon(taskIcon(card)));
  head.appendChild(parts.icon);
  const titles = el('div', 'task-panel-titles');
  titles.appendChild(
    el('div', 'task-panel-kind', taskKind(card) + (card.input.subagent_type ? ' · ' + card.input.subagent_type : ''))
  );
  titles.appendChild(parts.title);
  head.appendChild(titles);
  head.appendChild(iconButton('x', t('tasks.panel.close'), closeTaskPanel));
  root.appendChild(head);
  parts.status.append(parts.statusIcon, parts.statusText);
  parts.stepsHead.el.hidden = true;
  parts.logsHead.el.hidden = true;
  parts.resultHead.el.hidden = true;
  root.append(
    parts.status,
    parts.facts,
    parts.phasesBox,
    parts.stepsHead.el,
    card.subList,
    parts.logsHead.el,
    parts.logs,
    parts.resultHead.el,
    parts.result,
    parts.details
  );
  return parts;
}

function phaseOf(parts: Panel, key: string | number, title: string): Phase {
  let phase = parts.phases.get(key);
  if (!phase) {
    phase = { head: panelSection(title), list: el('div', 'panel-agents'), rows: new Map() };
    parts.phasesBox.append(phase.head.el, phase.list);
    parts.phases.set(key, phase);
  }
  return phase;
}

function agentRow(phase: Phase, agent: WorkflowAgent): AgentRow {
  const existing = phase.rows.get(agent.index);
  if (existing) return existing;
  const row: AgentRow = {
    el: el('div', 'panel-agent'),
    stateIcon: el('span', 'status-icon'),
    label: el('span', 'agent-label'),
    meta: el('span', 'agent-meta'),
    detail: el('div', 'panel-agent-detail'),
    state: null,
    detailKey: null,
    promptOpen: false
  };
  const line = el('div', 'panel-agent-line');
  line.append(row.stateIcon, row.label, row.meta);
  row.detail.hidden = true;
  row.el.append(line, row.detail);
  line.addEventListener('click', () => {
    row.detail.hidden = !row.detail.hidden;
    row.el.classList.toggle('open', !row.detail.hidden);
  });
  phase.list.appendChild(row.el);
  phase.rows.set(agent.index, row);
  return row;
}

function updateAgentDetail(row: AgentRow, agent: WorkflowAgent): void {
  const key = [agent.error, agent.resultPreview, agent.promptPreview].join('\u0000');
  if (row.detailKey === key) return;
  row.detailKey = key;
  row.detail.textContent = '';
  if (agent.error) row.detail.appendChild(el('div', 'agent-error', agent.error));
  if (agent.resultPreview) row.detail.appendChild(el('div', 'agent-preview', agent.resultPreview));
  if (agent.promptPreview) {
    const prompt = el('details');
    prompt.open = row.promptOpen;
    prompt.addEventListener('toggle', () => {
      row.promptOpen = prompt.open;
    });
    prompt.appendChild(el('summary', null, t('tasks.panel.prompt')));
    prompt.appendChild(el('div', 'agent-preview', agent.promptPreview));
    row.detail.appendChild(prompt);
  }
  if (!row.detail.children.length) row.detail.appendChild(el('div', 'agent-preview', t('tasks.panel.no.details')));
}

function renderAgents(parts: Panel, task: Task): void {
  const byPhase = new Map<string | number, { title: string; agents: WorkflowAgent[] }>();
  for (const agent of [...task.agents.values()].sort((a, b) => a.index - b.index)) {
    const key = agent.phaseIndex !== undefined ? agent.phaseIndex : agent.phaseTitle || '';
    if (!byPhase.has(key)) byPhase.set(key, { title: agent.phaseTitle || t('tasks.panel.agents'), agents: [] });
    byPhase.get(key)?.agents.push(agent);
  }
  for (const [key, group] of byPhase) {
    const phase = phaseOf(parts, key, group.title);
    const models = new Set(group.agents.map(agent => agent.model).filter(Boolean));
    setText(phase.head.title, group.title + (models.size === 1 ? ' · ' + [...models][0] : ''));
    setText(phase.head.count, group.agents.filter(agent => agent.state === 'done').length + '/' + group.agents.length);
    for (const agent of group.agents) {
      const row = agentRow(phase, agent);
      const status = agentStatus(agent);
      row.el.className = 'panel-agent ' + status + (row.detail.hidden ? '' : ' open');
      if (row.state !== status) {
        row.state = status;
        row.stateIcon.textContent = '';
        row.stateIcon.appendChild(status === 'queued' ? icon('circle-dashed', 'tiny') : stateIcon(status));
      }
      setText(row.label, agent.label || t('tasks.panel.agent', agent.index + 1));
      setText(
        row.meta,
        [
          models.size > 1 ? agent.model : '',
          agent.toolCalls ? t('tasks.tools', agent.toolCalls) : '',
          agent.tokens ? tokens(agent.tokens) : ''
        ]
          .filter(Boolean)
          .join(' · ')
      );
      updateAgentDetail(row, agent);
    }
  }
}

function detailsBlock(summary: string, text: string): HTMLElement {
  const details = el('details', 'panel-details');
  details.appendChild(el('summary', null, summary));
  details.appendChild(el('pre', 'panel', truncate(text, OUTPUT_LIMIT)));
  return details;
}

function renderTaskPanel(): void {
  const card = openCard;
  if (!card) return;
  const parts = panel && panel.card === card ? panel : (panel = buildTaskPanel(card));
  const status = cardState(card);
  const task = card.task;
  parts.icon.className = 'task-panel-icon ' + status;
  setText(parts.title, taskTitle(card));
  parts.status.className = 'task-panel-status ' + status;
  if (parts.statusKey !== status) {
    parts.statusKey = status;
    parts.statusIcon.textContent = '';
    parts.statusIcon.appendChild(stateIcon(status));
  }
  setText(parts.statusText, taskActivity(card));
  const agentCount = task && task.agents.size ? t('tasks.agents', doneAgents(task), task.agents.size) : '';
  const end = task && (task.endedAt || (status === 'running' ? Date.now() : null));
  setText(
    parts.facts,
    [
      agentCount,
      task && task.toolUses ? t('tasks.tool.calls', task.toolUses) : '',
      task && task.totalTokens ? t('tasks.tokens', tokens(task.totalTokens)) : '',
      task && task.startedAt && end ? duration(end - task.startedAt) : ''
    ]
      .filter(Boolean)
      .join(' · ')
  );
  if (task && task.agents.size) renderAgents(parts, task);

  const steps = card.subList.querySelectorAll(':scope > .entry, :scope > .sub-text').length;
  parts.stepsHead.el.hidden = steps === 0;
  setText(parts.stepsHead.count, String(steps));
  const logs = task ? task.logs : [];
  for (let i = parts.logCount; i < logs.length; i++) parts.logs.appendChild(el('div', 'task-log', logs[i]));
  parts.logCount = logs.length;
  parts.logsHead.el.hidden = logs.length === 0;
  setText(parts.logsHead.count, String(logs.length));
  if (card.resultText && card.taskType === 'agent' && !parts.resultShown) {
    parts.resultShown = true;
    markdown(parts.result, card.resultText, true);
  }
  parts.resultHead.el.hidden = !parts.resultShown;
  const prompt = card.input.prompt || task?.prompt;
  if (prompt && !parts.promptShown) {
    parts.promptShown = true;
    parts.details.appendChild(detailsBlock(t('tasks.panel.prompt'), String(prompt)));
  }
  if (card.resultText && card.taskType !== 'agent' && !parts.technicalShown) {
    parts.technicalShown = true;
    parts.details.appendChild(detailsBlock(t('tasks.panel.technical'), card.resultText));
  }
}

/** The task's card; tasks the stream never showed a tool call for get one of their own. */
function taskCardFor(t: Thread, task: Task): TaskCard {
  const existing = cardOf(t, task);
  if (existing) return existing;
  const block: ToolStarted = {
    type: 'toolStarted',
    id: cardId(task),
    kind: 'agent',
    name: TASK_TYPES[typeOf(task)].kind,
    input: { description: task.description, subagent_type: task.subagentType, prompt: task.prompt }
  };
  const card = makeTaskCard(t, block, typeOf(task));
  t.entries.set(block.id, card);
  addTaskToTurn(t, card);
  return card;
}

export function onTaskEvent(t: Thread, event: TaskUpdated): void {
  const id = event.taskId;
  if (!id) return;
  let task = t.tasks.get(id);
  if (!task) {
    task = { id, agents: new Map(), logs: [], seenLogs: new Set(), status: 'running', startedAt: Date.now() };
    t.tasks.set(id, task);
  }
  if (event.toolUseId) task.toolUseId = event.toolUseId;
  if (event.taskType) task.type = event.taskType;
  if (event.description) task.description = event.description;
  if (event.subagentType) task.subagentType = event.subagentType;
  if (event.prompt) task.prompt = event.prompt;
  if (event.backgrounded !== undefined) task.backgrounded = event.backgrounded;
  if (event.status) task.status = event.status;
  if (event.summary) task.summary = event.summary;
  if (event.toolUses !== undefined) task.toolUses = event.toolUses;
  if (event.totalTokens !== undefined) task.totalTokens = event.totalTokens;
  if (event.lastTool) task.lastTool = event.lastTool;
  if (event.endedAt) task.endedAt = event.endedAt;
  if (event.notified && !task.endedAt) task.endedAt = Date.now();
  for (const agent of event.agents || []) {
    task.agents.set(agent.index, { ...(task.agents.get(agent.index) || {}), ...agent });
  }
  for (const log of event.logs || []) {
    if (!task.seenLogs.has(log)) {
      task.seenLogs.add(log);
      task.logs.push(log);
    }
  }
  if (typeOf(task) === 'agent' && !task.toolUseId) return;
  const card = taskCardFor(t, task);
  card.task = task;
  const finished = cardState(card) !== 'running';
  if (finished) settleSteps(card);
  if (finished && !card.completionRow && card.turn && card.turn.done) addCompletionRow(card);
  renderTask(card);
  changed(t);
}

export function renderTasksTray(): void {
  const tray = $('tasks-tray');
  tray.textContent = '';
  const thread = state.active;
  const running = thread ? runningTasks(thread) : [];
  tray.hidden = !running.length;
  if (!thread) return;
  for (const task of running) {
    const card = cardOf(thread, task);
    const pill = el('button', 'task-pill');
    pill.appendChild(icon('loader-circle', 'tiny spin'));
    pill.appendChild(el('span', 'pill-text', task.description || t('tasks.background')));
    const stats = card ? taskStats(card) : '';
    if (stats) pill.appendChild(el('span', 'pill-stats', stats));
    pill.title = t('tasks.pill', TASK_TYPES[typeOf(task)].title, task.description || '');
    tray.appendChild(
      on(pill, () => {
        if (card) openTaskPanel(card);
      })
    );
  }
}

/** Advances the running tasks' clocks; called every second. */
export function tickTasks(): void {
  const t = state.active;
  if (!t) return;
  let any = false;
  for (const task of runningTasks(t)) {
    const card = cardOf(t, task);
    if (card) {
      renderTask(card);
      any = true;
    }
  }
  if (any) renderTasksTray();
}
