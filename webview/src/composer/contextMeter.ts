/** The context window meter, the prompt cache timer and the breakdown panel behind both. */
import type { Usage } from '../bridge/agent';
import { sendThread } from '../bridge/protocol';
import type { PromptCache, Thread } from '../state/model';
import { isActive, state } from '../state/store';
import { $, el, icon, iconButton, textButton } from '../ui/dom';
import { countdown, tokens } from '../ui/format';
import { t } from '../ui/i18n';
import { placeFloating } from '../ui/menu';
import { sendCommand } from './composer';
import { agentName, fitControls } from './controls';

const CACHE_TTL_5M = 5 * 60 * 1000;
const CACHE_TTL_1H = 60 * 60 * 1000;
/** Without a reported window size, assume the common one. */
const DEFAULT_MAX_TOKENS = 200000;
/** Chart series per context category, so each keeps its color. */
const CATEGORY_SLOTS: Record<string, number> = {
  'System prompt': 1,
  'System tools': 2,
  'MCP tools': 3,
  'Custom agents': 4,
  'Memory files': 5,
  Skills: 6,
  Messages: 7
};
const DEFERRED_SUFFIX = / \(deferred\)$/;

interface ContextNumbers {
  used: number;
  max: number;
  threshold: number | null;
  percent: number;
}

type CacheState = { kind: 'busy' | 'warm' | 'cold'; remaining: number };

const panel = () => $('context-panel');

/** Notes a request's token usage: the context it filled and the prompt cache it read or wrote. */
export function noteUsage(t: Thread, usage: Usage, at: number): void {
  if (usage.contextWindow) t.contextWindow = usage.contextWindow;
  const read = usage.cacheRead;
  const written = usage.cacheWrite;
  const prompt = usage.input + read + written;
  if (!prompt) return;
  let ttl = t.cache ? t.cache.ttl : CACHE_TTL_5M;
  if (usage.cacheWrite1h > 0) ttl = CACHE_TTL_1H;
  else if (usage.cacheWrite5m > 0) ttl = CACHE_TTL_5M;
  t.liveContext = { used: prompt + usage.output, at };
  if (read + written > 0) t.cache = { at, ttl, tokens: read + written };
  if (isActive(t)) renderContextControls();
}

/** The last breakdown's numbers, or the live request's total while the agent works. */
function contextNumbers(t: Thread): ContextNumbers | null {
  const usage = t.contextUsage;
  const max = (usage && usage.maxTokens) || t.contextWindow || DEFAULT_MAX_TOKENS;
  let used = usage && typeof usage.totalTokens === 'number' ? usage.totalTokens : null;
  if (t.liveContext && (!usage || t.busy || used === null)) used = t.liveContext.used;
  if (used === null) return null;
  const threshold = usage && usage.isAutoCompactEnabled !== false ? usage.autoCompactThreshold || null : null;
  return { used, max, threshold, percent: Math.min(100, Math.round((used / max) * 100)) };
}

function contextLevel(numbers: ContextNumbers): string {
  const share = numbers.used / (numbers.threshold || numbers.max);
  return share >= 0.95 ? 'critical' : share >= 0.8 ? 'warning' : 'normal';
}

function cacheState(t: Thread, cache: PromptCache): CacheState {
  if (t.busy) return { kind: 'busy', remaining: cache.ttl };
  const remaining = cache.at + cache.ttl - Date.now();
  return { kind: remaining > 0 ? 'warm' : 'cold', remaining };
}

export function renderContextControls(): void {
  const meter = $('context-meter');
  const timer = $('cache-timer');
  const thread = state.active;
  const numbers = thread ? contextNumbers(thread) : null;
  meter.hidden = !numbers;
  if (numbers) {
    const radius = 7;
    const circumference = 2 * Math.PI * radius;
    const filled = (circumference * numbers.percent) / 100;
    meter.className = 'context-meter ' + contextLevel(numbers);
    meter.innerHTML =
      '<svg viewBox="0 0 18 18" class="ring"><circle cx="9" cy="9" r="' +
      radius +
      '" class="track"/>' +
      '<circle cx="9" cy="9" r="' +
      radius +
      '" class="fill" stroke-dasharray="' +
      filled.toFixed(2) +
      ' ' +
      circumference.toFixed(2) +
      '" transform="rotate(-90 9 9)"/></svg>';
    meter.appendChild(el('span', 'meter-label', numbers.percent + '%'));
    const used = tokens(numbers.used);
    const max = tokens(numbers.max);
    meter.title = numbers.threshold
      ? t('context.meter.threshold', used, max, numbers.percent, tokens(numbers.threshold))
      : t('context.meter', used, max, numbers.percent);
  }
  const cache = thread && thread.cache ? cacheState(thread, thread.cache) : null;
  timer.hidden = !cache;
  if (thread && thread.cache && cache) {
    timer.className =
      'cache-timer ' + cache.kind + (cache.kind === 'warm' && cache.remaining < 60000 ? ' expiring' : '');
    timer.textContent = '';
    timer.appendChild(icon(cache.kind === 'cold' ? 'snowflake' : 'timer', 'tiny'));
    timer.appendChild(
      el(
        'span',
        null,
        cache.kind === 'busy'
          ? t('context.timer.busy')
          : cache.kind === 'warm'
            ? countdown(cache.remaining)
            : t('context.timer.cold')
      )
    );
    const ttl = thread.cache.ttl === CACHE_TTL_1H ? t('context.timer.hour') : t('context.timer.minutes');
    timer.title =
      cache.kind === 'cold'
        ? t('context.timer.expired', ttl, tokens(thread.cache.tokens))
        : t('context.timer.warm', tokens(thread.cache.tokens), ttl);
  }
  fitControls();
}

/** Counts the cache timer down; called every second. */
export function tickCache(): void {
  if (state.active && state.active.cache) renderContextControls();
}

function contextRow(color: string | null, name: string, value: number, max: number, muted?: boolean): HTMLElement {
  const row = el('div', 'context-row' + (muted ? ' muted' : ''));
  const swatch = el('span', 'swatch');
  if (color) swatch.style.background = color;
  else swatch.classList.add('empty');
  row.appendChild(swatch);
  row.appendChild(el('span', 'name', name));
  row.appendChild(el('span', 'value', tokens(value)));
  row.appendChild(el('span', 'share', max ? ((value / max) * 100).toFixed(1) + '%' : ''));
  return row;
}

function slotColor(name: string): string {
  return 'var(--series-' + (CATEGORY_SLOTS[name.replace(DEFERRED_SUFFIX, '')] || 8) + ')';
}

function renderContextPanel(): void {
  const root = panel();
  const thread = state.active;
  root.textContent = '';
  if (!thread) return;
  const numbers = contextNumbers(thread);
  const usage = thread.contextUsage;
  const head = el('div', 'context-head');
  head.appendChild(el('strong', null, t('context.title')));
  head.appendChild(iconButton('refresh-cw', t('context.refresh'), () => sendThread(thread, { op: 'contextUsage' })));
  head.appendChild(
    iconButton('x', t('common.close'), () => {
      root.hidden = true;
    })
  );
  root.appendChild(head);
  if (!numbers) {
    root.appendChild(el('div', 'context-note', t('context.no.data', agentName(thread))));
    return;
  }
  root.appendChild(
    el('div', 'context-total', t('context.total', tokens(numbers.used), tokens(numbers.max), numbers.percent))
  );
  if (numbers.threshold) {
    const thresholdPercent = Math.round((numbers.threshold / numbers.max) * 100);
    const left = Math.max(0, numbers.threshold - numbers.used);
    root.appendChild(
      el('div', 'context-note', t('context.threshold', tokens(numbers.threshold), thresholdPercent, tokens(left)))
    );
  }
  const bar = el('div', 'context-bar');
  const categories = usage ? usage.categories || [] : [];
  const used = categories.filter(category => category.kind === 'used' && category.tokens > 0);
  for (const category of used) {
    const segment = el('span', 'segment');
    segment.style.width = (category.tokens / numbers.max) * 100 + '%';
    segment.style.background = slotColor(category.name);
    segment.title = category.name + ': ' + tokens(category.tokens);
    bar.appendChild(segment);
  }
  if (!used.length) {
    const segment = el('span', 'segment');
    segment.style.width = numbers.percent + '%';
    segment.style.background = 'var(--series-1)';
    bar.appendChild(segment);
  }
  if (numbers.threshold) {
    const marker = el('span', 'threshold');
    marker.style.left = (numbers.threshold / numbers.max) * 100 + '%';
    marker.title = t('context.threshold.marker');
    bar.appendChild(marker);
  }
  root.appendChild(bar);
  if (usage) {
    if (thread.busy) root.appendChild(el('div', 'context-note', t('context.stale')));
    const legend = el('div', 'context-legend');
    for (const category of used) {
      legend.appendChild(contextRow(slotColor(category.name), category.name, category.tokens, numbers.max));
    }
    const free = categories.find(category => category.kind === 'free');
    if (free) legend.appendChild(contextRow(null, t('context.free'), free.tokens, numbers.max, true));
    root.appendChild(legend);
    const deferred = categories.filter(category => category.kind === 'deferred' && category.tokens > 0);
    if (deferred.length) {
      const names = deferred
        .map(category => category.name.replace(DEFERRED_SUFFIX, '') + ' ' + tokens(category.tokens))
        .join(', ');
      root.appendChild(el('div', 'context-note', t('context.deferred', names)));
    }
    const messages = usage.messages;
    const parts: [string, number][] = messages
      ? [
          [t('context.messages.tool.results'), messages.toolResultTokens],
          [t('context.messages.tool.calls'), messages.toolCallTokens],
          [t('context.messages.user'), messages.userMessageTokens],
          [t('context.messages.assistant'), messages.assistantMessageTokens],
          [t('context.messages.attachments'), messages.attachmentTokens],
          [t('context.messages.other'), (messages.unattributedTokens || 0) + (messages.redirectedContextTokens || 0)]
        ]
      : [];
    const shown = parts.filter(part => part[1] > 0);
    if (shown.length) {
      root.appendChild(el('div', 'context-section', t('context.messages')));
      for (const [name, value] of shown) root.appendChild(contextRow(null, name, value, numbers.max, true));
    }
    if ((usage.memoryFiles || []).length) {
      root.appendChild(el('div', 'context-section', t('context.memory.files')));
      for (const file of usage.memoryFiles) {
        const row = contextRow(
          null,
          file.path.split(/[\\/]/).pop() + ' (' + (file.type || '') + ')',
          file.tokens,
          numbers.max,
          true
        );
        row.title = file.path;
        root.appendChild(row);
      }
    }
    if ((usage.mcpServers || []).length) {
      root.appendChild(el('div', 'context-section', t('context.mcp.servers')));
      for (const server of usage.mcpServers) {
        const row = contextRow(
          null,
          t('context.mcp.server', server.name, server.tools),
          server.loadedTokens,
          numbers.max,
          true
        );
        row.title = t('context.mcp.server.title', tokens(server.loadedTokens), tokens(server.deferredTokens));
        root.appendChild(row);
      }
    }
  }
  if (thread.cache) {
    const cache = cacheState(thread, thread.cache);
    const ttl = thread.cache.ttl === CACHE_TTL_1H ? t('context.cache.hour') : t('context.cache.minutes');
    const size = tokens(thread.cache.tokens);
    root.appendChild(el('div', 'context-section', t('context.cache')));
    root.appendChild(
      el(
        'div',
        'context-note',
        cache.kind === 'busy'
          ? t('context.cache.busy', ttl, size)
          : cache.kind === 'warm'
            ? t('context.cache.warm', countdown(cache.remaining), ttl, size)
            : t('context.cache.expired', size)
      )
    );
  }
  if (thread.info && !thread.info.capabilities.compact) return;
  const actions = el('div', 'context-actions');
  actions.appendChild(
    textButton(t('context.compact'), 'outline', () => {
      root.hidden = true;
      sendCommand('/compact');
    })
  );
  root.appendChild(actions);
}

function placeContextPanel(): void {
  if (!panel().hidden) placeFloating(panel(), $('composer').getBoundingClientRect(), 'above', true);
}

export function toggleContextPanel(): void {
  if (!panel().hidden) {
    panel().hidden = true;
    return;
  }
  renderContextPanel();
  panel().hidden = false;
  placeContextPanel();
  if (state.active) sendThread(state.active, { op: 'contextUsage' });
}

export function closeContextPanel(): void {
  panel().hidden = true;
}

/** A fresh breakdown arrived; re-renders the open panel. */
export function refreshContextPanel(): void {
  renderContextControls();
  if (!panel().hidden) {
    renderContextPanel();
    placeContextPanel();
  }
}

/** Closes the panel when a click lands outside it and its buttons. */
export function closeContextPanelOutside(target: Element): void {
  const root = panel();
  if (!root.hidden && !root.contains(target) && !target.closest('#context-meter, #cache-timer')) root.hidden = true;
}
