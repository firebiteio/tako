/** Turns the agent's events into the thread's timeline, live or replayed from its past. */
import type { AgentEvent, TurnEnded } from '../bridge/agent';
import { removeApproval } from '../composer/approvals';
import { noteUsage } from '../composer/contextMeter';
import { renderControls, selectResolvedModel } from '../composer/controls';
import type { LiveText, Thread } from '../state/model';
import { isActive } from '../state/store';
import { t } from '../ui/i18n';
import { markdown } from '../ui/markdown';
import { showAlert } from '../ui/widgets';
import { onRateLimits, renderMeta, renderScratchpad } from '../views/header';
import { scheduleSidebar } from '../views/sidebar';
import { onTaskEvent } from './tasks';
import { setBusy } from './threads';
import {
  addAssistantText,
  addItem,
  addNote,
  addSubText,
  addThinking,
  addToolUse,
  assistantRow,
  attachResult,
  clearThinking,
  currentTurn,
  finishTurn,
  follow,
  startTurn
} from './timeline';

export function handleEvent(thread: Thread, event: AgentEvent): void {
  switch (event.type) {
    case 'sessionStarted':
      if (event.mode) {
        thread.mode = event.mode;
        if (isActive(thread)) renderControls();
      }
      if (event.model) selectResolvedModel(thread, event.model);
      thread.scratchpad = event.scratchpadPath || null;
      if (isActive(thread)) renderScratchpad();
      break;
    case 'modeChanged':
      thread.mode = event.mode;
      if (isActive(thread)) renderControls();
      break;
    case 'commandsChanged':
      if (thread.info) thread.info.commands = event.commands;
      break;
    case 'busy':
      if (!thread.replaying) setBusy(thread, true);
      break;
    case 'messageStarted':
      thread.currentMessageId = event.messageId;
      thread.liveByIndex.clear();
      break;
    case 'textStarted':
      startLiveText(thread, event.blockId);
      break;
    case 'textDelta': {
      const live = thread.liveByIndex.get(event.blockId);
      if (live) {
        live.text += event.text;
        live.row.rawText = live.text;
        scheduleRender(thread, live);
      }
      break;
    }
    case 'thinkingStarted':
      currentTurn(thread);
      addThinking(thread, event.blockId);
      break;
    case 'blockEnded': {
      const row = thread.thinkingByIndex.get(event.blockId);
      if (row) {
        row.remove();
        thread.thinkingByIndex.delete(event.blockId);
      }
      break;
    }
    case 'text':
      onText(thread, event.messageId, event.text, event.parentId);
      break;
    case 'toolStarted':
      addToolUse(thread, event);
      break;
    case 'toolFinished':
      attachResult(thread, event);
      break;
    case 'prompt':
      startTurn(
        thread,
        event.text,
        event.hasIdeContext ? [{ id: '', kind: 'file', label: t('stream.ide.context') }] : null,
        true,
        event.images
      );
      break;
    case 'interrupted':
      if (thread.turn) thread.turn.stopped = true;
      break;
    case 'usage': {
      const at = event.at ?? Date.now();
      if (event.at === undefined || thread.replaying || !thread.liveContext || at >= thread.liveContext.at)
        noteUsage(thread, event, at);
      break;
    }
    case 'turnEnded':
      onTurnEnded(thread, event);
      break;
    case 'rateLimits':
      onRateLimits(event.limits);
      break;
    case 'retrying':
      showAlert(thread, retrying(event.attempt, event.message));
      break;
    case 'compacted':
      addNote(thread, t('stream.compacted'));
      break;
    case 'permissionCancelled':
      removeApproval(thread, event.requestId);
      break;
    case 'taskUpdated':
      onTaskEvent(thread, event);
      break;
  }
}

function startLiveText(thread: Thread, blockId: string): void {
  const turn = currentTurn(thread);
  clearThinking(thread);
  const row = assistantRow('');
  row.body.classList.add('fade-in');
  addItem(turn, row.el, 'text');
  turn.lastText = row;
  turn.group = null;
  const live: LiveText = { row, text: '', done: false, scheduled: false };
  liveTexts(thread, thread.currentMessageId).push(live);
  thread.liveByIndex.set(blockId, live);
}

function liveTexts(thread: Thread, messageId: string | undefined): LiveText[] {
  let texts = thread.live.get(messageId);
  if (!texts) {
    texts = [];
    thread.live.set(messageId, texts);
  }
  return texts;
}

/** Renders streamed markdown at most once a frame. */
function scheduleRender(thread: Thread, live: LiveText): void {
  if (live.scheduled) return;
  live.scheduled = true;
  requestAnimationFrame(() => {
    live.scheduled = false;
    if (!live.done) {
      markdown(live.row.body, live.text, false);
      follow(thread);
    }
  });
}

/** A complete text block: it finishes its streamed block, goes into the task that wrote it, or stands alone. */
function onText(thread: Thread, messageId: string | undefined, text: string, parentId: string | undefined): void {
  if (parentId) {
    addSubText(thread, parentId, text);
    return;
  }
  const live = (thread.live.get(messageId) || []).find(candidate => !candidate.done);
  if (live) {
    live.done = true;
    live.row.rawText = text;
    markdown(live.row.body, text, true);
  } else {
    addAssistantText(thread, text);
  }
}

function onTurnEnded(thread: Thread, event: TurnEnded): void {
  finishTurn(thread, event);
  setBusy(thread, false);
  thread.finished = true;
  if (!isActive(thread) || document.hidden) thread.unread = true;
  if (event.cost) thread.costUsd = event.cost.costUsd;
  if (isActive(thread)) renderMeta();
  scheduleSidebar();
}

function retrying(attempt: number | undefined, message: string | undefined): string {
  if (attempt && message) return t('stream.retrying.attempt.message', attempt, message);
  if (attempt) return t('stream.retrying.attempt', attempt);
  if (message) return t('stream.retrying.message', message);
  return t('stream.retrying');
}
