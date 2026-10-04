/** The composer's pickers: agent, model, reasoning effort, permission mode, and whether to name the open file. */
import type { Mode, Model } from '../bridge/agent';
import { send, sendThread } from '../bridge/protocol';
import type { Thread } from '../state/model';
import { isActive, state, workspace } from '../state/store';
import { $, el, icon } from '../ui/dom';
import { t } from '../ui/i18n';
import type { IconName } from '../ui/icons';
import { openMenu } from '../ui/menu';
import { renderRunsPicker } from './runs';

/** Icons of the modes the agents offer; other modes show a shield. */
const MODE_ICONS: Record<string, IconName> = {
  default: 'lock',
  acceptEdits: 'pen-line',
  plan: 'pencil-ruler',
  auto: 'sparkles',
  dontAsk: 'shield',
  bypassPermissions: 'lock-open',
  'read-only': 'lock',
  'full-access': 'lock-open'
};

/** Reasoning effort levels by their usual names; other levels show as named. */
const EFFORTS: Record<string, { label: string; description: string }> = {
  low: { label: t('composer.effort.low'), description: t('composer.effort.low.description') },
  medium: { label: t('composer.effort.medium'), description: t('composer.effort.medium.description') },
  high: { label: t('composer.effort.high'), description: t('composer.effort.high.description') },
  xhigh: { label: t('composer.effort.xhigh'), description: t('composer.effort.xhigh.description') },
  max: { label: t('composer.effort.max'), description: t('composer.effort.max.description') }
};

/** Controls whose label gives way to just the icon when the composer is too narrow, least needed first. */
const SHRINKING = [
  'file-toggle',
  'cache-timer',
  'effort-picker',
  'mode-picker',
  'model-picker',
  'agent-picker',
  'runs-picker'
];

function models(t: Thread): Model[] {
  return t.info ? t.info.models : [];
}

function modes(t: Thread): Mode[] {
  return t.info ? t.info.modes : [];
}

function currentModel(t: Thread): Model | undefined {
  return models(t).find(candidate => candidate.value === t.model);
}

/** Selects the model the agent reports it resolved the thread's choice to, unless that is the choice already. */
export function selectResolvedModel(t: Thread, resolved: string): void {
  const current = currentModel(t);
  if (current && (current.resolvedModel === resolved || current.value === resolved)) return;
  const match = models(t).find(model => model.resolvedModel === resolved || model.value === resolved);
  if (match) {
    t.model = match.value;
    if (isActive(t)) renderControls();
  }
}

/** The agent's name. */
export function agentName(t: Thread): string {
  return workspace.agents.find(agent => agent.id === t.agent)?.name || t.info?.agentName || t.agent;
}

/** The agent can only change before the thread has a session; the picker shows until then. */
export function canChangeAgent(t: Thread): boolean {
  return t.turnCount === 0 && !t.hasSession;
}

export function fill(button: HTMLElement, iconName: IconName, label: string, withChevron: boolean): void {
  button.textContent = '';
  button.appendChild(icon(iconName, 'small'));
  button.appendChild(el('span', 'control-label', label));
  if (withChevron) button.appendChild(icon('chevron-down', 'small chevron'));
}

export function renderControls(): void {
  const thread = state.active;
  if (!thread) return;
  renderRunsPicker(thread);
  const agentPicker = $('agent-picker');
  const picksAgent = workspace.agents.length > 1 && canChangeAgent(thread);
  agentPicker.hidden = !picksAgent;
  $('agent-sep').hidden = !picksAgent;
  fill(agentPicker, 'bot', agentName(thread), true);
  agentPicker.title = t('composer.agent');

  const model = currentModel(thread);
  const modelPicker = $('model-picker');
  fill(modelPicker, 'sparkles', model ? model.displayName || model.value : t('composer.model'), true);
  modelPicker.title = model
    ? [model.displayName || model.value, model.description].filter(Boolean).join(': ')
    : t('composer.model');

  const effortPicker = $('effort-picker');
  const supportsEffort = !!(model && model.efforts.length);
  effortPicker.hidden = !supportsEffort;
  $('effort-sep').hidden = !supportsEffort;
  const effort = thread.effort && (EFFORTS[thread.effort]?.label || thread.effort);
  fill(effortPicker, 'brain', effort || t('composer.effort'), true);
  effortPicker.title = effort ? t('composer.effort.title.level', effort) : t('composer.effort.title');

  const mode = modes(thread).find(candidate => candidate.value === thread.mode);
  const modePicker = $('mode-picker');
  const hasModes = modes(thread).length > 0;
  modePicker.hidden = !hasModes;
  $('mode-sep').hidden = !hasModes;
  fill(modePicker, MODE_ICONS[thread.mode] || 'shield', mode ? mode.label : thread.mode, true);
  modePicker.title = mode ? mode.label + ': ' + mode.description : t('composer.mode');

  const fileToggle = $('file-toggle');
  fill(fileToggle, 'file-text', t('composer.current.file'), false);
  fileToggle.setAttribute('aria-pressed', String(workspace.includeFile));

  $('attach').hidden = !!thread.info && !thread.info.capabilities.images;
  $('hero-hint').textContent = thread.info ? t('composer.hint', thread.info.authDescription) : '';
  fitControls();
}

/** Shows each label in full or not at all: drops labels to icons, least needed first, until the row fits. */
export function fitControls(): void {
  const row = document.querySelector<HTMLElement>('.composer-controls');
  if (!row) return;
  for (const id of SHRINKING) $(id).classList.remove('icon-only');
  for (const id of SHRINKING) {
    if (row.scrollWidth <= row.clientWidth) return;
    $(id).classList.add('icon-only');
  }
}

export function openAgentMenu(): void {
  const thread = state.active;
  if (!thread || !canChangeAgent(thread)) return;
  openMenu(
    $('agent-picker'),
    workspace.agents.map(agent => ({
      label: agent.name,
      checked: agent.id === thread.agent,
      run: () => {
        if (agent.id !== thread.agent) send({ op: 'setAgent', thread: thread.id, agent: agent.id });
      }
    })),
    'above',
    t('composer.agent')
  );
}

export function openModelMenu(): void {
  const thread = state.active;
  if (!thread) return;
  openMenu(
    $('model-picker'),
    models(thread).map(model => ({
      label: model.displayName || model.value,
      description: model.description,
      checked: model.value === thread.model,
      run: () => {
        thread.model = model.value;
        renderControls();
        sendThread(thread, { op: 'setModel', model: model.value });
      }
    })),
    'above',
    t('composer.model')
  );
}

export function openEffortMenu(): void {
  const thread = state.active;
  if (!thread) return;
  const model = currentModel(thread);
  const levels = model && model.efforts.length ? model.efforts : Object.keys(EFFORTS);
  openMenu(
    $('effort-picker'),
    levels.map(level => ({
      label: EFFORTS[level]?.label || level,
      description: EFFORTS[level]?.description,
      checked: level === thread.effort,
      run: () => {
        thread.effort = level;
        renderControls();
        sendThread(thread, { op: 'setEffort', effort: level });
      }
    })),
    'above',
    t('composer.effort')
  );
}

export function openModeMenu(): void {
  const thread = state.active;
  if (!thread) return;
  openMenu(
    $('mode-picker'),
    modes(thread).map(mode => ({
      label: mode.label,
      description: mode.description,
      icon: MODE_ICONS[mode.value] || 'shield',
      checked: mode.value === thread.mode,
      run: () => {
        thread.mode = mode.value;
        renderControls();
        sendThread(thread, { op: 'setMode', mode: mode.value });
      }
    })),
    'above',
    t('composer.permissions')
  );
}

export function toggleIncludeFile(): void {
  workspace.includeFile = !workspace.includeFile;
  renderControls();
}
