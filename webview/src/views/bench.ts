/** A bench's runs side by side: what each changed, cost and took. One run's changes can be applied. */
import { send, type BenchItem, type RunChanges } from '../bridge/protocol';
import { runName } from '../composer/runs';
import type { Thread } from '../state/model';
import { threads, workspace } from '../state/store';
import { activate } from '../thread/threads';
import { $, el, icon, iconButton, textButton } from '../ui/dom';
import { duration, elapsed, money, tokens } from '../ui/format';
import { t } from '../ui/i18n';

let shownBench: string | null = null;

export function openBench(benchId: string): void {
  shownBench = benchId;
  render(null);
  send({ op: 'compareBench', bench: benchId });
}

export function closeBench(): void {
  shownBench = null;
  $('bench-view').hidden = true;
}

/** The IDE's comparison, unless the view moved on to another bench or closed. */
export function showBenchComparison(benchId: string, runs: RunChanges[]): void {
  if (benchId === shownBench) render(runs);
}

/** Closes the view when its bench is gone. */
export function pruneBenchView(): void {
  if (shownBench && !workspace.benches.some(bench => bench.id === shownBench)) closeBench();
}

export function confirmDiscard(bench: BenchItem): void {
  if (!window.confirm(t('bench.discard.confirm', bench.title))) return;
  send({ op: 'discardBench', bench: bench.id });
  closeBench();
}

function runStatus(thread: Thread | undefined): string {
  if (!thread) return t('bench.status.closed');
  if (thread.approvals.length) return t('bench.status.waiting');
  if (thread.busy) return t('bench.status.working');
  if (thread.failed) return t('bench.status.failed');
  return t('bench.status.done');
}

function runTime(thread: Thread | undefined): string {
  if (!thread) return '';
  if (thread.runMillis) return duration(thread.runMillis);
  return thread.busy ? elapsed(thread.startedAt) : '';
}

function runRow(bench: BenchItem, run: RunChanges): HTMLElement {
  const thread = threads.get(run.thread);
  const row = el('tr', thread ? 'clickable' : null);
  const cells = run.error
    ? [run.error, '', '', '', '', '']
    : [
        runStatus(thread),
        String(run.files),
        '+' + run.added + ' −' + run.removed,
        thread ? money(thread.costUsd) : '',
        thread ? tokens(thread.tokens) : '',
        runTime(thread)
      ];
  row.appendChild(el('td', null, thread ? runName(thread) : '#' + run.runIndex));
  for (const cell of cells) row.appendChild(el('td', run.error ? 'muted' : null, cell));
  const actions = el('td', 'bench-actions');
  if (!run.error && run.files) {
    actions.appendChild(
      textButton(t('bench.diff'), 'outline', event => {
        event.stopPropagation();
        send({ op: 'showRunDiff', bench: bench.id, runIndex: run.runIndex });
      })
    );
    actions.appendChild(
      textButton(t('bench.pick'), 'primary', event => {
        event.stopPropagation();
        if (!window.confirm(t('bench.pick.confirm', run.runIndex))) return;
        send({ op: 'pickWinner', bench: bench.id, runIndex: run.runIndex });
        closeBench();
      })
    );
  }
  row.appendChild(actions);
  if (thread) {
    row.addEventListener('click', () => {
      closeBench();
      activate(thread);
      send({ op: 'select', thread: thread.id });
    });
  }
  return row;
}

/** @param runs null while the IDE still reads the worktrees */
function render(runs: RunChanges[] | null): void {
  const bench = workspace.benches.find(candidate => candidate.id === shownBench);
  if (!bench) {
    closeBench();
    return;
  }
  const view = $('bench-view');
  view.textContent = '';
  const lane = el('div', 'usage-lane');
  const head = el('div', 'usage-head');
  head.append(icon('workflow'), el('h2', null, bench.title));
  head.appendChild(iconButton('refresh-cw', t('bench.refresh'), () => openBench(bench.id)));
  head.appendChild(iconButton('x', t('common.close'), closeBench));
  lane.appendChild(head);
  lane.appendChild(el('p', 'usage-note', t('bench.note', bench.baseCommit)));
  view.appendChild(lane);
  view.hidden = false;
  if (!runs) {
    lane.appendChild(el('p', 'usage-note', t('bench.reading')));
    return;
  }
  const table = el('table', 'usage-table');
  const header = el('tr');
  for (const name of [
    t('bench.run'),
    t('bench.status'),
    t('bench.files'),
    t('bench.lines'),
    t('usage.cost'),
    t('usage.tokens'),
    t('bench.time'),
    ''
  ]) {
    header.appendChild(el('th', null, name));
  }
  table.appendChild(header);
  for (const run of runs) table.appendChild(runRow(bench, run));
  const wrap = el('div', 'table-wrap');
  wrap.appendChild(table);
  lane.appendChild(wrap);
  const footer = el('div', 'bench-footer');
  footer.appendChild(textButton(t('bench.discard'), 'outline danger', () => confirmDiscard(bench)));
  lane.appendChild(footer);
}
