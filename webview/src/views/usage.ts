/** The usage view: cost and tokens of every session of the workspace's folders. */
import { send, type Usage } from '../bridge/protocol';
import { threads, workspace } from '../state/store';
import { activate } from '../thread/threads';
import { $, el, icon, iconButton } from '../ui/dom';
import { money, shortDay, tokens } from '../ui/format';
import { t } from '../ui/i18n';

/** The chart spans the transcripts' retention, at least a month and at most a quarter. */
const MIN_CHART_DAYS = 30;
const MAX_CHART_DAYS = 90;
const DEFAULT_RETENTION_DAYS = 30;

function isoDay(date: Date): string {
  return (
    date.getFullYear() +
    '-' +
    String(date.getMonth() + 1).padStart(2, '0') +
    '-' +
    String(date.getDate()).padStart(2, '0')
  );
}

function dailyChart(days: Usage['days'], span: number): HTMLElement {
  const byDay = new Map(days.map(day => [day.day, day.cost]));
  const today = new Date();
  const series: { date: Date; cost: number }[] = [];
  for (let offset = span - 1; offset >= 0; offset--) {
    const date = new Date(today.getFullYear(), today.getMonth(), today.getDate() - offset);
    series.push({ date, cost: byDay.get(isoDay(date)) || 0 });
  }
  const max = Math.max(...series.map(point => point.cost), 0);
  const chart = el('div', 'bars-chart');
  const top = el('div', 'bars-top');
  top.appendChild(el('span', null, t('usage.chart.title', span)));
  top.appendChild(el('span', null, t('usage.chart.max', money(max))));
  chart.appendChild(top);
  const bars = el('div', 'bars');
  const tooltip = el('div', 'chart-tooltip');
  tooltip.hidden = true;
  for (const point of series) {
    const slot = el('div', 'bar-slot' + (point.cost ? '' : ' empty'));
    const bar = el('div', 'bar');
    bar.style.height = max ? Math.max(point.cost ? 2 : 0, (point.cost / max) * 100) + '%' : '0';
    slot.appendChild(bar);
    slot.addEventListener('mouseenter', () => {
      tooltip.textContent = shortDay(point.date) + ' · ' + money(point.cost);
      const chartRect = chart.getBoundingClientRect();
      const slotRect = slot.getBoundingClientRect();
      tooltip.style.left = slotRect.left - chartRect.left + slotRect.width / 2 + 'px';
      tooltip.style.top = bar.getBoundingClientRect().top - chartRect.top - 6 + 'px';
      tooltip.hidden = false;
    });
    slot.addEventListener('mouseleave', () => {
      tooltip.hidden = true;
    });
    bars.appendChild(slot);
  }
  chart.appendChild(bars);
  const axis = el('div', 'bars-axis');
  axis.appendChild(el('span', null, shortDay(series[0].date)));
  axis.appendChild(el('span', null, shortDay(series[Math.floor(span / 2)].date)));
  axis.appendChild(el('span', null, t('usage.chart.today')));
  chart.appendChild(axis);
  chart.appendChild(tooltip);
  return chart;
}

function tile(label: string, value: string, detail: string): HTMLElement {
  const node = el('div', 'tile');
  node.appendChild(el('div', 'label', label));
  node.appendChild(el('div', 'value', value));
  if (detail) node.appendChild(el('div', 'detail', detail));
  return node;
}

export function table(headers: string[], rows: HTMLElement[]): HTMLElement {
  const wrap = el('div', 'table-wrap');
  const node = el('table', 'usage-table');
  const head = el('tr');
  for (const header of headers) head.appendChild(el('th', null, header));
  node.appendChild(head);
  for (const row of rows) node.appendChild(row);
  wrap.appendChild(node);
  return wrap;
}

function tableRow(cells: string[], onClick?: () => void): HTMLElement {
  const row = el('tr', onClick ? 'clickable' : null);
  for (const cell of cells) row.appendChild(el('td', null, cell));
  if (onClick) row.addEventListener('click', onClick);
  return row;
}

function section(title: string | null, content: HTMLElement): HTMLElement {
  const node = el('div', 'usage-section');
  if (title) node.appendChild(el('h3', null, title));
  node.appendChild(content);
  return node;
}

export function closeUsage(): void {
  $('usage-view').hidden = true;
}

/** Opens a session from the table: its thread, restored if archived, or a new thread resuming it. */
function openSession(item: Usage['top'][number]): void {
  closeUsage();
  if (!item.thread) {
    send({ op: 'resume', sessionId: item.sessionId, title: item.title, projectPath: item.projectPath });
  } else if (item.archived) {
    send({ op: 'archive', thread: item.thread, archived: false });
  } else {
    const thread = threads.get(item.thread);
    if (thread) activate(thread);
    send({ op: 'select', thread: item.thread });
  }
}

/** @param usage null while the IDE still reads the transcripts */
export function showUsage(usage: Usage | null): void {
  const view = $('usage-view');
  view.textContent = '';
  const lane = el('div', 'usage-lane');
  const head = el('div', 'usage-head');
  head.appendChild(icon('chart-column'));
  head.appendChild(
    el('h2', null, workspace.projectName ? t('usage.title.project', workspace.projectName) : t('usage.title'))
  );
  head.appendChild(iconButton('x', t('common.close'), closeUsage));
  lane.appendChild(head);
  view.appendChild(lane);
  view.hidden = false;
  if (!usage) {
    lane.appendChild(el('p', 'usage-note', t('usage.reading')));
    return;
  }
  lane.appendChild(el('p', 'usage-note', t('usage.note.prices')));
  const retention = usage.retentionDays || DEFAULT_RETENTION_DAYS;
  const oldest = usage.oldestDay ? new Date(usage.oldestDay + 'T00:00:00') : null;
  lane.appendChild(
    el(
      'p',
      'usage-note',
      oldest ? t('usage.note.retention.oldest', retention, shortDay(oldest)) : t('usage.note.retention', retention)
    )
  );
  if (usage.estimatedSessions) {
    lane.appendChild(
      el('p', 'usage-note', t('usage.note.estimated', usage.estimatedSessions, money(usage.estimatedCost)))
    );
  }
  const tiles = el('div', 'tiles');
  const sessions = usage.sessions || 0;
  tiles.appendChild(tile(t('usage.tile.cost'), money(usage.totalCost), t('usage.tile.cost.detail')));
  tiles.appendChild(tile(t('usage.tokens'), tokens(usage.totalTokens), t('usage.tile.tokens.detail')));
  tiles.appendChild(
    tile(
      t('usage.sessions'),
      String(sessions),
      sessions ? t('usage.tile.sessions.average', money(usage.totalCost / sessions)) : ''
    )
  );
  lane.appendChild(tiles);
  lane.appendChild(
    section(null, dailyChart(usage.days || [], Math.min(Math.max(MIN_CHART_DAYS, retention), MAX_CHART_DAYS)))
  );
  lane.appendChild(
    section(
      t('usage.by.model'),
      table(
        [
          t('usage.model'),
          t('usage.input'),
          t('usage.output'),
          t('usage.cache.read'),
          t('usage.cache.write'),
          t('usage.cost')
        ],
        (usage.models || [])
          .sort((a, b) => b.cost - a.cost)
          .map(model =>
            tableRow([
              model.model,
              tokens(model.input),
              tokens(model.output),
              tokens(model.cacheRead),
              tokens(model.cacheWrite),
              money(model.cost)
            ])
          )
      )
    )
  );
  const several = (usage.projects || []).length > 1;
  if (several) {
    lane.appendChild(
      section(
        t('usage.by.project'),
        table(
          [t('usage.project'), t('usage.sessions'), t('usage.tokens'), t('usage.cost')],
          usage.projects
            .slice()
            .sort((a, b) => b.cost - a.cost)
            .map(project =>
              tableRow([project.name, String(project.sessions), tokens(project.tokens), money(project.cost)])
            )
        )
      )
    );
  }
  lane.appendChild(
    section(
      t('usage.top'),
      table(
        [t('usage.session'), t('usage.tokens'), t('usage.cost')],
        (usage.top || []).map(item =>
          tableRow(
            [
              (item.archived ? t('usage.session.archived', item.title) : item.title) +
                (several && item.projectName ? ' · ' + item.projectName : ''),
              tokens(item.tokens),
              (item.estimated ? '~' : '') + money(item.cost)
            ],
            () => openSession(item)
          )
        )
      )
    )
  );
  $('usage-button-value').textContent = money(usage.totalCost);
}
