/** Parallel runs: one prompt sent to several threads at once, each working in its own git worktree. */
import type { Model } from '../bridge/agent';
import { send, type ContextItem, type ImageAttachment, type RunSpec } from '../bridge/protocol';
import type { PendingImage, RunPlan, Thread } from '../state/model';
import { withRootOf } from '../state/projects';
import { isActive, state, threads, workspace } from '../state/store';
import { setBusy } from '../thread/threads';
import { $ } from '../ui/dom';
import { t } from '../ui/i18n';
import { openMenu, type MenuItem } from '../ui/menu';
import { showAlert } from '../ui/widgets';
import { autosize, input, renderAttachments, renderChips, sendPrompt } from './composer';
import { agentName, canChangeAgent, fill, renderControls } from './controls';

const RUN_COUNTS = [2, 3, 4, 5, 6, 8];
const TITLE_LENGTH = 40;

/** A prompt waiting for its bench's threads. */
interface PendingBench {
  source: Thread;
  text: string;
  contexts: ContextItem[];
  images: PendingImage[];
  includeFile: boolean;
}

const pending = new Map<number, PendingBench>();
let lastRequest = 0;

/** Runs can be set up on a thread that has not started, while the settings allow it. */
export function canPlanRuns(thread: Thread): boolean {
  return workspace.parallelRuns && canChangeAgent(thread);
}

export function isBench(thread: Thread): boolean {
  return canPlanRuns(thread) && thread.runs.length > 1;
}

function modelsOf(agent: string, thread: Thread): Model[] {
  if (agent === thread.agent && thread.info) return thread.info.models;
  return workspace.agentModels.get(agent) || [];
}

function agentLabel(agent: string): string {
  return workspace.agents.find(option => option.id === agent)?.name || agent;
}

function modelLabel(agent: string, model: string, thread: Thread): string {
  if (!model) return t('runs.default.model');
  const match = modelsOf(agent, thread).find(candidate => candidate.value === model);
  return match ? match.displayName || match.value : model;
}

/** What a run starts with: its own choices, else the thread's when it runs the thread's agent. */
function specOf(plan: RunPlan, thread: Thread): RunSpec {
  const agent = plan.agent || thread.agent;
  const isSameAgent = agent === thread.agent;
  return {
    agent,
    model: plan.model ?? (isSameAgent ? thread.model || '' : ''),
    mode: isSameAgent && thread.info ? thread.mode : '',
    effort: isSameAgent ? thread.effort || '' : ''
  };
}

function runLabel(plan: RunPlan, thread: Thread): string {
  const spec = specOf(plan, thread);
  return agentLabel(spec.agent) + ' · ' + modelLabel(spec.agent, spec.model, thread);
}

export function renderRunsPicker(thread: Thread): void {
  const picker = $('runs-picker');
  const isShown = canPlanRuns(thread);
  picker.hidden = !isShown;
  $('runs-sep').hidden = !isShown;
  if (!isShown) return;
  const count = Math.max(1, thread.runs.length);
  picker.classList.toggle('on', count > 1);
  fill(picker, 'workflow', count > 1 ? t('runs.count', count) : t('runs.single'), true);
  picker.title = count > 1 ? t('runs.title.on', count) : t('runs.title');
}

function setCount(thread: Thread, count: number): void {
  if (count <= 1) {
    thread.runs = [];
  } else {
    const runs = thread.runs.slice(0, count);
    while (runs.length < count) runs.push({});
    thread.runs = runs;
  }
  renderControls();
}

export function openRunsMenu(): void {
  const thread = state.active;
  if (!thread || !canPlanRuns(thread)) return;
  const count = thread.runs.length;
  const items: MenuItem[] = [
    {
      label: t('runs.single'),
      description: t('runs.single.description'),
      checked: count <= 1,
      run: () => setCount(thread, 1)
    },
    ...RUN_COUNTS.map(option => ({
      label: t('runs.count', option),
      checked: option === count,
      run: () => setCount(thread, option)
    }))
  ];
  thread.runs.forEach((plan, index) => {
    items.push({
      label: t('runs.run', index + 1, runLabel(plan, thread)),
      description: t('runs.run.change'),
      icon: 'bot',
      run: () => openRunMenu(thread, index)
    });
  });
  openMenu($('runs-picker'), items, 'above', t('runs.menu'));
}

/** Picks one run's agent and model: the models known for each agent, or its default. */
function openRunMenu(thread: Thread, index: number): void {
  const current = specOf(thread.runs[index], thread);
  const items: MenuItem[] = [];
  for (const agent of workspace.agents) {
    const models = modelsOf(agent.id, thread);
    const choices = models.length ? models.map(model => model.value) : [''];
    for (const model of choices) {
      items.push({
        label: agent.name + ' · ' + modelLabel(agent.id, model, thread),
        checked: current.agent === agent.id && current.model === model,
        run: () => {
          thread.runs[index] = { agent: agent.id, model };
          renderControls();
        }
      });
    }
  }
  openMenu($('runs-picker'), items, 'above', t('runs.run.menu', index + 1));
}

function titleOf(text: string): string {
  const line = text.replace(/\n/g, ' ').trim();
  return line.length <= TITLE_LENGTH ? line : line.slice(0, TITLE_LENGTH - 1) + '…';
}

/**
 * Asks the IDE for a worktree and thread per run; the prompt goes out once they exist.
 *
 * @return false while the thread still waits for an earlier bench
 */
export function startBench(thread: Thread, text: string): boolean {
  for (const waiting of pending.values()) {
    if (waiting.source === thread) return false;
  }
  const request = ++lastRequest;
  pending.set(request, {
    source: thread,
    text,
    contexts: thread.contexts,
    images: thread.images,
    includeFile: workspace.includeFile
  });
  send({
    op: 'createBench',
    request,
    title: titleOf(text) || t('runs.untitled'),
    projectPath: thread.projectPath,
    source: thread.id,
    runs: thread.runs.map(plan => specOf(plan, thread))
  });
  setBusy(thread, true);
  return true;
}

/** Sends the waiting prompt to each run's thread. */
export function onBenchCreated(request: number, threadIds: string[]): void {
  const prompt = pending.get(request);
  pending.delete(request);
  if (!prompt) return;
  if (threads.has(prompt.source.id)) setBusy(prompt.source, false);
  const images: ImageAttachment[] = prompt.images.map(image => ({
    mediaType: image.mediaType,
    data: image.data,
    name: image.name
  }));
  for (const id of threadIds) {
    const run = threads.get(id);
    if (run) withRootOf(run, () => sendPrompt(run, prompt.text, prompt.contexts, images, prompt.includeFile));
  }
}

/** Gives the prompt back to the thread it was typed in. An empty text means the person cancelled. */
export function onBenchFailed(request: number, text: string): void {
  const prompt = pending.get(request);
  pending.delete(request);
  if (!prompt || !threads.has(prompt.source.id)) return;
  const source = prompt.source;
  setBusy(source, false);
  source.contexts = prompt.contexts;
  source.images = prompt.images;
  if (isActive(source)) {
    if (!input.value.trim()) input.value = prompt.text;
    autosize();
    renderChips();
    renderAttachments();
  } else if (!source.draft.trim()) {
    source.draft = prompt.text;
  }
  if (text) showAlert(source, text);
}

/** A run's name in the list: its number, agent and model. */
export function runName(thread: Thread): string {
  const model = thread.model ? modelLabel(thread.agent, thread.model, thread) : '';
  return '#' + thread.runIndex + ' · ' + agentName(thread) + (model ? ' · ' + model : '');
}
