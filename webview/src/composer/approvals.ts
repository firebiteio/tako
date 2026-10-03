/** The drawer over the composer where the agent waits for the person: tool approvals, plans and questions. */
import type { PermissionPrompt } from '../bridge/agent';
import { sendThread } from '../bridge/protocol';
import type { Approval, Thread } from '../state/model';
import { isActive, state } from '../state/store';
import { addItem, currentTurn, follow, planCard, renderWorking } from '../thread/timeline';
import { approvalDetail, approvalLabel } from '../thread/tools';
import { $, el, icon, iconButton, on, textButton } from '../ui/dom';
import { t } from '../ui/i18n';
import type { IconName } from '../ui/icons';
import { openMenu, type MenuItem } from '../ui/menu';
import { scheduleSidebar } from '../views/sidebar';
import { DEFAULT_PLACEHOLDER, input, renderSend, takeFeedback } from './composer';
import { renderControls } from './controls';

/** How long a picked single-choice answer shows before the next question. */
const ANSWER_DELAY_MILLIS = 200;

/** How the person answers a prompt. */
interface Answer {
  allow: boolean;
  message?: string;
  interrupt?: boolean;
  answers?: Record<string, string>;
  /** Index of the suggestion approved with. */
  suggestion?: number;
}

const drawer = $('drawer');

export function addPermission(t: Thread, requestId: string, prompt: PermissionPrompt): void {
  t.approvals.push({ requestId, prompt });
  if (prompt.kind === 'plan') addItem(currentTurn(t), planCard(prompt.plan || ''), 'plan');
  changed(t);
  follow(t);
}

export function removeApproval(t: Thread, requestId: string): void {
  t.approvals = t.approvals.filter(approval => approval.requestId !== requestId);
  if (t.question && t.question.requestId === requestId) t.question = null;
  changed(t);
}

export function cancelAllApprovals(t: Thread): void {
  t.approvals = [];
  t.question = null;
  if (isActive(t)) renderDrawer();
  scheduleSidebar();
}

function changed(t: Thread): void {
  if (isActive(t)) renderDrawer();
  renderWorking(t);
  scheduleSidebar();
}

function answer(t: Thread, approval: Approval, reply: Answer): void {
  sendThread(t, {
    op: 'permission',
    requestId: approval.requestId,
    allow: reply.allow,
    message: reply.message,
    interrupt: !!reply.interrupt,
    answers: reply.answers,
    suggestion: reply.suggestion
  });
  const prompt = approval.prompt;
  if (!reply.allow && prompt.toolUseId) t.declined.add(prompt.toolUseId);
  const mode = reply.suggestion !== undefined ? prompt.suggestions[reply.suggestion]?.mode : undefined;
  if (reply.allow && mode) {
    t.mode = mode;
    if (isActive(t)) renderControls();
  }
  removeApproval(t, approval.requestId);
}

const deny = (fallback: string, interrupt?: boolean): Answer => ({
  allow: false,
  message: takeFeedback() || fallback,
  interrupt
});

/** The typed text declines the waiting approval, or refines the plan. */
export function declineWithFeedback(t: Thread, approval: Approval): void {
  if (!input.value.trim()) return;
  answer(t, approval, deny(approval.prompt.kind === 'plan' ? 'Keep planning.' : 'Declined.'));
}

function moreMenu(anchor: HTMLElement, thread: Thread, approval: Approval): void {
  const items: MenuItem[] = approval.prompt.suggestions.map((suggestion, index) => ({
    label: suggestion.label,
    icon: 'check',
    run: () => answer(thread, approval, { allow: true, suggestion: index })
  }));
  items.push({
    label: t('approvals.decline.and.stop'),
    icon: 'x',
    run: () => answer(thread, approval, deny('The user declined and stopped.', true))
  });
  openMenu(anchor, items, 'above');
}

export function renderDrawer(): void {
  drawer.textContent = '';
  drawer.className = '';
  const t = state.active;
  const approval = t ? t.approvals[0] : null;
  if (!t || !approval) {
    drawer.hidden = true;
    input.placeholder = DEFAULT_PLACEHOLDER;
    renderSend();
    return;
  }
  drawer.hidden = false;
  if (approval.prompt.kind === 'question') renderQuestion(t, approval);
  else if (approval.prompt.kind === 'plan') renderPlanDrawer(t, approval);
  else renderApproval(t, approval);
  renderSend();
}

function drawerFrame(t: Thread, iconName: IconName, label: string): HTMLElement {
  const grid = el('div', 'drawer-grid');
  const side = el('span', 'drawer-icon');
  side.appendChild(icon(iconName));
  grid.appendChild(side);
  const content = el('div');
  const head = el('div', 'drawer-head');
  head.appendChild(el('span', 'label', label));
  if (t.approvals.length > 1) head.appendChild(el('span', 'counter', '1/' + t.approvals.length));
  content.appendChild(head);
  grid.appendChild(content);
  drawer.appendChild(grid);
  return content;
}

function moreButton(thread: Thread, approval: Approval): HTMLButtonElement {
  const more: HTMLButtonElement = iconButton('ellipsis', t('approvals.more'), () => moreMenu(more, thread, approval));
  return more;
}

function renderApproval(thread: Thread, approval: Approval): void {
  drawer.className = 'warning';
  const prompt = approval.prompt;
  const content = drawerFrame(thread, 'shield', approvalLabel(prompt));
  const detail = approvalDetail(prompt);
  content.appendChild(el('code', 'drawer-detail', detail));
  if (prompt.description && prompt.description !== detail) {
    content.appendChild(el('div', 'drawer-description', prompt.description));
  }
  const actions = el('div', 'drawer-actions');
  if (prompt.canDiff) {
    const diff = el('button', 'button outline');
    diff.append(icon('file-diff', 'small'), el('span', null, t('approvals.diff')));
    actions.appendChild(on(diff, () => sendThread(thread, { op: 'diff', requestId: approval.requestId })));
  }
  actions.appendChild(moreButton(thread, approval));
  actions.appendChild(
    textButton(t('approvals.decline'), 'outline', () =>
      answer(thread, approval, deny('The user declined this tool call.'))
    )
  );
  actions.appendChild(textButton(t('approvals.approve'), 'primary', () => answer(thread, approval, { allow: true })));
  content.appendChild(actions);
  input.placeholder = t('approvals.placeholder');
}

function renderPlanDrawer(thread: Thread, approval: Approval): void {
  const content = drawerFrame(thread, 'book-open', t('approvals.plan'));
  content.appendChild(el('div', 'drawer-description', t('approvals.plan.description')));
  const actions = el('div', 'drawer-actions');
  actions.appendChild(moreButton(thread, approval));
  actions.appendChild(
    textButton(t('approvals.refine'), 'outline', () => answer(thread, approval, deny('Keep planning.')))
  );
  actions.appendChild(textButton(t('approvals.implement'), 'primary', () => answer(thread, approval, { allow: true })));
  content.appendChild(actions);
  input.placeholder = t('approvals.plan.placeholder');
}

function renderQuestion(thread: Thread, approval: Approval): void {
  const questions = approval.prompt.questions;
  if (!thread.question || thread.question.requestId !== approval.requestId) {
    thread.question = { requestId: approval.requestId, index: 0, answers: {}, selected: new Set() };
  }
  const q = thread.question;
  const question = questions[q.index];
  if (!question) return;
  const content = drawerFrame(thread, 'message-circle', question.header || t('approvals.question'));
  const head = content.querySelector('.drawer-head') as HTMLElement;
  (head.querySelector('.label') as HTMLElement).style.color = 'var(--fg)';
  if (questions.length > 1) head.appendChild(el('span', 'counter', q.index + 1 + '/' + questions.length));
  head.appendChild(
    iconButton('x', t('approvals.skip'), () =>
      answer(thread, approval, { allow: false, message: 'The user declined to answer.' })
    )
  );
  content.appendChild(el('div', 'question-text', question.question));
  const options = el('div', 'options');
  question.options.forEach((option, index) => {
    const row = el('button', 'option' + (q.selected.has(option.label) ? ' selected' : ''));
    const text = el('span', 'text');
    text.appendChild(el('span', 'label', option.label));
    if (option.description) text.appendChild(el('span', 'description', option.description));
    row.appendChild(text);
    row.appendChild(el('kbd', null, String(index + 1)));
    options.appendChild(on(row, () => chooseOption(index)));
  });
  content.appendChild(options);
  const actions = el('div', 'drawer-actions');
  const isLast = q.index === questions.length - 1;
  actions.appendChild(
    textButton(isLast ? t('approvals.submit') : t('approvals.next'), 'primary', () => advanceQuestion())
  );
  content.appendChild(actions);
  input.placeholder = t('approvals.question.placeholder');
}

/** Picks an option of the shown question; a single choice also moves on. */
export function chooseOption(index: number): void {
  const t = state.active;
  const q = t?.question;
  const approval = t?.approvals[0];
  const question = approval?.prompt.questions[q ? q.index : 0];
  const option = question?.options[index];
  if (!q || !question || !option) return;
  if (question.multiSelect) {
    if (q.selected.has(option.label)) q.selected.delete(option.label);
    else q.selected.add(option.label);
    renderDrawer();
  } else {
    q.selected = new Set([option.label]);
    renderDrawer();
    setTimeout(advanceQuestion, ANSWER_DELAY_MILLIS);
  }
}

/** Takes the selected options and typed text as the answer, then shows the next question or answers the agent. */
export function advanceQuestion(): void {
  const t = state.active;
  const q = t?.question;
  const approval = t?.approvals[0];
  if (!t || !q || !approval || approval.requestId !== q.requestId) return;
  const questions = approval.prompt.questions;
  const typed = takeFeedback();
  const values = [...q.selected];
  if (typed) values.push(typed);
  if (!values.length) return;
  q.answers[questions[q.index].question] = values.join(', ');
  q.selected = new Set();
  if (q.index < questions.length - 1) {
    q.index++;
    renderDrawer();
    return;
  }
  answer(t, approval, { allow: true, answers: q.answers });
}
