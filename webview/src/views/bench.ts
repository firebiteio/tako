/** A bench's runs side by side: what each changed, cost and took. One run's changes can be applied. */
import { send, type BenchItem, type RunChanges } from '../bridge/protocol';
import { runName } from '../composer/runs';
import type { Thread } from '../state/model';
import { threads, workspace } from '../state/store';
import { openThread } from '../thread/threads';
import { $, el, icon, iconButton, textButton } from '../ui/dom';
import { duration, elapsed, money, tokens } from '../ui/format';
import { t } from '../ui/i18n';
import { table } from './usage';

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
  if (thread.firstTurnMillis) return duration(thread.firstTurnMillis);
  return thread.busy ? elapsed(thread.startedAt) : '';
}

/** @param index the run's number, from 1 */
function runRow(bench: BenchItem, run: RunChanges, index: number): HTMLElement {
  const thread = threads.get(bench.threads[index - 1]);
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
  row.appendChild(el('td', null, thread ? runName(thread, index) : '#' + index));
  for (const cell of cells) row.appendChild(el('td', run.error ? 'muted' : null, cell));
  const actions = el('td', 'bench-actions');
  if (!run.error && run.files) {
    actions.appendChild(
      textButton(t('bench.diff'), 'outline', () => send({ op: 'showRunDiff', bench: bench.id, runIndex: index }))
    );
    actions.appendChild(
      textButton(t('bench.pick'), 'primary', () => {
        if (!window.confirm(t('bench.pick.confirm', index))) return;
        send({ op: 'pickWinner', bench: bench.id, runIndex: index });
        closeBench();
      })
    );
  }
  row.appendChild(actions);
  if (thread) {
    row.addEventListener('click', () => {
      closeBench();
      openThread(thread);
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
  lane.appendChild(
    table(
      [
        t('bench.run'),
        t('bench.status'),
        t('bench.files'),
        t('bench.lines'),
        t('usage.cost'),
        t('usage.tokens'),
        t('bench.time'),
        ''
      ],
      runs.map((run, index) => runRow(bench, run, index + 1))
    )
  );
  const footer = el('div', 'bench-footer');
  footer.appendChild(textButton(t('bench.discard'), 'outline danger', () => confirmDiscard(bench)));
  lane.appendChild(footer);
}
