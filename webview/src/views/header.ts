/** The header: the project and thread names, the thread's cost, the rate-limit windows, and its worktree, branch and pull request. */
import { send, sendThread } from '../bridge/protocol';
import type { Thread } from '../state/model';
import { projectOf } from '../state/projects';
import { state, workspace } from '../state/store';
import { $, el, icon, on } from '../ui/dom';
import { money, tokens } from '../ui/format';
import { t } from '../ui/i18n';
import { openMenu, type MenuItem } from '../ui/menu';
import { projectIcon, projectIconUrl } from '../ui/widgets';

/** How much of the agent's rate-limit windows is used, e.g. "5h 42% · 7d 10%". */
export function onRateLimits(limits: { label: string; usedPercent: number }[]): void {
  workspace.limits = limits.map(limit => limit.label + ' ' + Math.round(limit.usedPercent) + '%').join(' · ');
  renderMeta();
}

export function renderMeta(): void {
  const thread = state.active;
  const meta = $('meta');
  meta.textContent = [thread && thread.costUsd ? money(thread.costUsd) : '', workspace.limits]
    .filter(Boolean)
    .join(' · ');
  meta.title = thread && thread.tokens ? t('header.meta.tokens', tokens(thread.tokens)) : t('header.meta');
}

function chip(iconName: 'folder-git-2' | 'git-branch', text: string, title: string): HTMLElement {
  const node = el('span', 'git-chip');
  node.title = title;
  node.appendChild(icon(iconName, 'tiny'));
  node.appendChild(el('span', null, text));
  return node;
}

export function renderGit(): void {
  const chips = $('git-chips');
  chips.textContent = '';
  const thread = state.active;
  if (!thread) return;
  if (thread.worktree) {
    chips.appendChild(chip('folder-git-2', thread.worktree, t('header.worktree', thread.worktree)));
  }
  if (thread.branch) chips.appendChild(chip('git-branch', thread.branch, t('header.branch', thread.branch)));
  const pr = thread.pullRequest;
  if (pr) {
    const button = el('button', 'git-chip pr ' + pr.state);
    button.title = t('header.pull.request', pr.title, pr.state);
    button.appendChild(icon(pr.state === 'merged' ? 'git-merge' : 'git-pull-request', 'tiny'));
    button.appendChild(el('span', null, pr.number));
    chips.appendChild(on(button, () => send({ op: 'openUrl', url: pr.url })));
  }
}

/** The scratchpad button, shown while the thread's agent has a scratchpad. */
export function renderScratchpad(): void {
  $('scratchpad').hidden = !state.active || !state.active.scratchpad;
}

export function requestScratchpad(): void {
  if (state.active) sendThread(state.active, { op: 'scratchpad' });
}

/** Lists the scratchpad's files, newest first, each opening in the editor. */
export function showScratchpad(thread: Thread, path: string, files: string[]): void {
  const items: MenuItem[] = files.map(file => {
    const slash = file.lastIndexOf('/');
    return {
      label: file.slice(slash + 1),
      description: slash < 0 ? undefined : file.slice(0, slash),
      icon: 'file-text',
      run: () => sendThread(thread, { op: 'open', path: path + '/' + file, line: 1 })
    };
  });
  items.push({
    label: t('header.scratchpad.reveal'),
    icon: 'folder-open',
    run: () => sendThread(thread, { op: 'revealScratchpad' })
  });
  openMenu($('scratchpad'), items, 'below', t('header.scratchpad'));
}

/** The project crumb, and the empty thread's hero that names the project. */
export function renderProjectName(): void {
  const project = state.active ? projectOf(state.active) : null;
  const name = project ? project.name : workspace.projectName;
  const crumb = $('project-name');
  crumb.textContent = '';
  if (projectIconUrl(project)) crumb.appendChild(projectIcon(project, 'small'));
  crumb.appendChild(document.createTextNode(name));
  crumb.title = project ? project.path : '';
  $('hero-title').textContent = heroTitle(state.active ? state.active.id : '', name);
}

/** Headlines for an empty thread, each with and without the project's name. */
const HEROES: [() => string, (name: string) => string][] = [
  [() => t('header.hero.build'), name => t('header.hero.build.project', name)],
  [() => t('header.hero.working'), name => t('header.hero.working.project', name)],
  [() => t('header.hero.start'), name => t('header.hero.start.project', name)],
  [() => t('header.hero.fix'), name => t('header.hero.fix.project', name)],
  [() => t('header.hero.next'), name => t('header.hero.next.project', name)],
  [() => t('header.hero.tackle'), name => t('header.hero.tackle.project', name)]
];

/** A headline picked by the thread's id, so a thread keeps its headline while the header re-renders. */
function heroTitle(threadId: string, name: string): string {
  let hash = 0;
  for (const c of threadId) hash = (hash * 31 + c.charCodeAt(0)) | 0;
  const [plain, named] = HEROES[Math.abs(hash) % HEROES.length];
  return name ? named(name) : plain();
}
