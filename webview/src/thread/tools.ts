/** How tool calls read: their one-line label and expanded details, decided by what the call does. */
import type { PermissionPrompt, TextEdit, Todo, ToolInput, ToolKind } from '../bridge/agent';
import type { EntryStatus } from '../state/model';
import { relative } from '../state/projects';
import { el, icon } from '../ui/dom';
import { firstLine, truncate } from '../ui/format';
import { t } from '../ui/i18n';
import type { IconName } from '../ui/icons';

/** How much tool output a panel shows. */
export const OUTPUT_LIMIT = 20000;

/** Tool names some labels and task cards single out; any agent may use them. */
export const Tool = {
  Write: 'Write',
  PowerShell: 'PowerShell',
  Glob: 'Glob',
  LS: 'LS',
  WebSearch: 'WebSearch',
  Workflow: 'Workflow'
} as const;

export const CATEGORY_ICONS: Record<ToolKind, IconName> = {
  read: 'eye',
  edit: 'square-pen',
  command: 'terminal',
  search: 'search',
  web: 'globe',
  agent: 'bot',
  todo: 'list-todo',
  question: 'message-circle',
  plan: 'book-open',
  other: 'wrench'
};

/** A label is text with monospace runs, e.g. {@code Ran `npm test`}. Empty parts are skipped. */
export type LabelPart = string | { mono: string };

export interface LabelSubject {
  name: string;
  category: ToolKind;
  input: ToolInput;
  status: EntryStatus;
  title?: string;
  isNew?: boolean;
}

/** Stands in for the monospace run while a label's text is translated. */
const MONO = '\u0001';

/** A translated label whose argument {0} shows in monospace. */
function withMono(text: string, mono: string): LabelPart[] {
  const [before, after] = text.split(MONO);
  return [before, { mono }, after || ''];
}

export function entryLabel(entry: LabelSubject): LabelPart[] {
  const input = entry.input;
  const status = entry.status;
  const running = status === 'running';
  switch (entry.category) {
    case 'command': {
      const command = firstLine(input.command || input.description || entry.name);
      const text =
        status === 'declined'
          ? t('tools.command.declined', MONO)
          : running
            ? t('tools.command.running', MONO)
            : status === 'error'
              ? t('tools.command.failed', MONO)
              : t('tools.command.ran', MONO);
      return withMono(text, command);
    }
    case 'read': {
      const path = relative(input.file_path || input.notebook_path);
      return [running ? t('tools.read.running', path) : t('tools.read', path)];
    }
    case 'edit': {
      const path = relative(input.file_path || input.notebook_path);
      if (status === 'declined') return [t('tools.edit.declined', path)];
      if (entry.name === Tool.Write && entry.isNew) {
        return [running ? t('tools.edit.creating', path) : t('tools.edit.created', path)];
      }
      return [running ? t('tools.edit.editing', path) : t('tools.edit.edited', path)];
    }
    case 'search':
      if (entry.title) return [entry.title];
      if (entry.name === Tool.Glob) return withMono(t('tools.search.files', MONO), input.pattern || '');
      if (entry.name === Tool.LS) return [t('tools.search.listed', relative(input.path))];
      return withMono(
        input.path ? t('tools.search.in', MONO, relative(input.path)) : t('tools.search', MONO),
        input.pattern || ''
      );
    case 'web':
      if (entry.title) return [entry.title];
      return entry.name === Tool.WebSearch
        ? [t('tools.web.search', input.query || '')]
        : [t('tools.web.fetch', input.url || '')];
    case 'agent':
      return [t('tools.agent', input.description || input.subagent_type || t('tools.agent.task'))];
    case 'todo': {
      const todos = input.todos || [];
      const done = todos.filter(todo => todo.status === 'completed').length;
      return [t('tools.todo', done, todos.length)];
    }
    case 'question':
      return [t('tools.question', (input.questions as { question?: string }[] | undefined)?.[0]?.question || '')];
    case 'plan':
      return [t('tools.plan')];
    case 'other': {
      if (entry.title) return [entry.title];
      if (entry.name.startsWith('mcp__')) return [entry.name.split('__').slice(1).join(' · ')];
      const first = Object.values(input).find(value => typeof value === 'string');
      return [entry.name + (first ? ' · ' + firstLine(first) : '')];
    }
  }
}

export function renderLabel(target: HTMLElement, parts: LabelPart[]): void {
  target.textContent = '';
  let title = '';
  for (const part of parts) {
    if (!part) continue;
    if (typeof part === 'string') {
      target.appendChild(document.createTextNode(part));
      title += part;
    } else {
      target.appendChild(el('span', 'mono', part.mono));
      title += part.mono;
    }
  }
  target.title = title;
}

function inlineDiff(oldText: string | undefined, newText: string | undefined): HTMLElement {
  const diff = el('div', 'diff');
  for (const line of String(oldText || '').split('\n')) diff.appendChild(el('div', 'del', '- ' + line));
  for (const line of String(newText || '').split('\n')) diff.appendChild(el('div', 'add', '+ ' + line));
  return diff;
}

function todoList(todos: Todo[] | undefined): HTMLElement {
  const list = el('ul', 'todos');
  for (const todo of todos || []) {
    const item = el('li', todo.status);
    item.appendChild(
      icon(
        todo.status === 'completed' ? 'square-check-big' : todo.status === 'in_progress' ? 'circle-dot' : 'circle',
        'small'
      )
    );
    item.appendChild(
      el('span', null, todo.status === 'in_progress' && todo.activeForm ? todo.activeForm : todo.content)
    );
    list.appendChild(item);
  }
  return list;
}

/** An edit's change as the call describes it: text replacements, a whole new content, or patches. */
function editDetails(details: HTMLElement, input: ToolInput): void {
  if (input.edits) {
    for (const edit of input.edits as TextEdit[]) details.appendChild(inlineDiff(edit.old_string, edit.new_string));
  } else if (input.old_string !== undefined || input.new_string !== undefined) {
    details.appendChild(inlineDiff(input.old_string, input.new_string));
  } else if (input.content !== undefined) {
    details.appendChild(el('pre', 'panel', truncate(String(input.content || ''), OUTPUT_LIMIT)));
  } else if (input.changes) {
    for (const change of input.changes) {
      details.appendChild(el('pre', 'panel', truncate((change.path || '') + '\n' + (change.diff || ''), OUTPUT_LIMIT)));
    }
  } else {
    jsonDetails(details, input);
  }
}

function jsonDetails(details: HTMLElement, input: ToolInput): void {
  if (Object.keys(input).length) details.appendChild(el('pre', 'panel', JSON.stringify(input, null, 2)));
}

/** What an expanded tool row shows, ending in the list a subagent's own calls go to. */
export function entryDetails(
  name: string,
  entryCategory: ToolKind,
  input: ToolInput
): { details: HTMLElement; subList: HTMLElement } {
  const details = el('div', 'entry-details');
  switch (entryCategory) {
    case 'command':
      if (input.command === undefined) jsonDetails(details, input);
      else details.appendChild(el('pre', 'panel', (name === Tool.PowerShell ? 'PS> ' : '$ ') + input.command));
      break;
    case 'edit':
      editDetails(details, input);
      break;
    case 'todo':
      details.appendChild(todoList(input.todos));
      break;
    case 'read':
    case 'agent':
      break;
    default:
      jsonDetails(details, input);
  }
  const subList = el('div', 'sub-list');
  details.appendChild(subList);
  return { details, subList };
}

export function approvalLabel(prompt: PermissionPrompt): string {
  switch (prompt.category) {
    case 'command':
      return t('tools.approval.command');
    case 'edit':
      return t('tools.approval.edit');
    case 'read':
      return t('tools.approval.read');
    case 'web':
      return t('tools.approval.web');
    default:
      return t('tools.approval', prompt.label || prompt.toolName);
  }
}

export function approvalDetail(prompt: PermissionPrompt): string {
  const input = prompt.input || {};
  if (input.command) return input.command;
  if (input.file_path) return relative(input.file_path);
  if (input.url) return input.url;
  if (input.pattern) return input.pattern;
  return JSON.stringify(input, null, 1);
}
