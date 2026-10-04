/** The prompt box: typing, sending, slash commands, {@code @} file mentions, attached context and images. */
import type { Command } from '../bridge/agent';
import { send, sendThread, type ContextItem, type FileMatch, type ImageAttachment } from '../bridge/protocol';
import type { Thread } from '../state/model';
import { isActive, state, workspace } from '../state/store';
import { setBusy } from '../thread/threads';
import { follow, startTurn } from '../thread/timeline';
import { $, el, icon, sendArrow, stopSquare } from '../ui/dom';
import { t } from '../ui/i18n';
import { imageThumb, mentionChip, showAlert } from '../ui/widgets';
import { advanceQuestion, chooseOption, declineWithFeedback } from './approvals';

export const DEFAULT_PLACEHOLDER = t('composer.placeholder');
/** The API's limits, which larger pasted images are scaled down to (io.firebite.tako.ide.context.Images). */
const MAX_IMAGE_EDGE = 1568;
const MAX_IMAGE_BYTES = 3500000;
const MAX_INPUT_HEIGHT = 208;
const MAX_SLASH_ITEMS = 50;
/** Waits for typing to pause before asking the IDE for files. */
const MENTION_DEBOUNCE_MILLIS = 80;
const SUPPORTED_IMAGE = /^image\/(png|jpeg|gif|webp)$/;

export const input = $<HTMLTextAreaElement>('input');
const popup = $('slash');

/** The open popup: slash commands or files for an {@code @} mention. */
type Popup = { mode: 'slash'; items: Command[] } | { mode: 'mention'; items: FileMatch[] };

let popupState: Popup = { mode: 'slash', items: [] };
let popupIndex = 0;
let mention: { start: number; end: number; query: string } | null = null;
let mentionRequest = 0;
let mentionTimer = 0;

export function autosize(): void {
  input.style.height = 'auto';
  input.style.height = Math.min(input.scrollHeight, MAX_INPUT_HEIGHT) + 'px';
  renderSend();
}

/** Takes the typed text, e.g. as feedback for a declined approval, and clears the box. */
export function takeFeedback(): string {
  const text = input.value.trim();
  input.value = '';
  autosize();
  return text;
}

/** The send button stops Claude when there is nothing to send. */
export function renderSend(): void {
  const button = $<HTMLButtonElement>('send');
  const thread = state.active;
  const hasText = input.value.trim().length > 0 || !!(thread && thread.images.length);
  const busy = !!(thread && thread.busy);
  const isStop = busy && !hasText && !(thread && thread.approvals.length);
  button.textContent = '';
  button.classList.toggle('stop', isStop);
  button.appendChild(isStop ? stopSquare() : sendArrow());
  button.title = isStop ? t('composer.stop') : busy ? t('composer.queue.title') : t('composer.send.title');
  button.disabled = !isStop && !hasText && !(thread && thread.question && thread.question.selected.size);
}

/** Sends what is typed; with Claude waiting, it answers the question or declines the approval instead. */
export function submit(): void {
  const t = state.active;
  if (!t) return;
  if (t.question) {
    advanceQuestion();
    return;
  }
  const approval = t.approvals[0];
  if (approval) {
    declineWithFeedback(t, approval);
    return;
  }
  const text = input.value.trim();
  const images: ImageAttachment[] = t.images.map(image => ({
    mediaType: image.mediaType,
    data: image.data,
    name: image.name
  }));
  if (!text && !images.length) {
    if (t.busy) sendThread(t, { op: 'interrupt' });
    return;
  }
  hidePopup();
  t.turnNo++;
  t.finished = false;
  t.failed = false;
  startTurn(t, text, t.contexts, false, images);
  sendThread(t, {
    op: 'send',
    text,
    context: t.contexts,
    images,
    includeActiveFile: workspace.includeFile,
    turn: t.turnNo
  });
  t.contexts = [];
  t.images = [];
  renderAttachments();
  t.updatedAt = Date.now();
  renderChips();
  input.value = '';
  autosize();
  if (!t.busy) {
    t.startedAt = Date.now();
    setBusy(t, true);
  }
  t.stick = true;
  follow(t);
}

/** Sends a command as if typed, e.g. {@code /compact}. */
export function sendCommand(command: string): void {
  input.value = command;
  submit();
}

/** Sends, then opens a new thread in the same folder. */
function submitAndBranch(): void {
  const t = state.active;
  if (!t || t.question || t.approvals.length || (!input.value.trim() && !t.images.length)) return;
  hidePopup();
  submit();
  send({ op: 'newThread' });
}

export function renderChips(): void {
  const chips = $('chips');
  chips.textContent = '';
  const t = state.active;
  if (!t) return;
  for (const item of t.contexts) {
    chips.appendChild(
      mentionChip(item, () => {
        t.contexts = t.contexts.filter(candidate => candidate.id !== item.id);
        renderChips();
      })
    );
  }
}

/** IDE context attached from an editor action; a proposed prompt fills an empty box. */
export function addContext(t: Thread, item: ContextItem, prefill: string | undefined): void {
  t.contexts.push(item);
  if (!isActive(t)) return;
  renderChips();
  if (prefill && !input.value.trim()) {
    input.value = prefill;
    autosize();
  }
  input.focus();
}

export function addImage(t: Thread, image: ImageAttachment): void {
  t.images.push({ ...image, id: Math.random().toString(36).slice(2) });
  if (isActive(t)) {
    renderAttachments();
    renderSend();
    input.focus();
  }
}

export function renderAttachments(): void {
  const box = $('attachments');
  box.textContent = '';
  const t = state.active;
  if (!t) return;
  for (const image of t.images) {
    box.appendChild(
      imageThumb(image, () => {
        t.images = t.images.filter(candidate => candidate.id !== image.id);
        renderAttachments();
        renderSend();
      })
    );
  }
}

function readAsDataUrl(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result as string);
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(blob);
  });
}

function base64Of(dataUrl: string): string {
  return dataUrl.slice(dataUrl.indexOf(',') + 1);
}

/** The image as the API takes it: as is when it fits, else scaled down, as JPEG when PNG is too large. */
async function imageFromFile(file: File): Promise<ImageAttachment> {
  const name = file.name || 'pasted-image';
  const dataUrl = await readAsDataUrl(file);
  const bitmap = await createImageBitmap(file);
  const longEdge = Math.max(bitmap.width, bitmap.height);
  if (SUPPORTED_IMAGE.test(file.type) && longEdge <= MAX_IMAGE_EDGE && file.size <= MAX_IMAGE_BYTES) {
    return { mediaType: file.type, data: base64Of(dataUrl), name };
  }
  const scale = Math.min(1, MAX_IMAGE_EDGE / longEdge);
  const canvas = el('canvas');
  canvas.width = Math.max(1, Math.round(bitmap.width * scale));
  canvas.height = Math.max(1, Math.round(bitmap.height * scale));
  canvas.getContext('2d')?.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
  let encoded = canvas.toDataURL('image/png');
  let mediaType = 'image/png';
  if (encoded.length * 0.75 > MAX_IMAGE_BYTES) {
    const opaque = el('canvas');
    opaque.width = canvas.width;
    opaque.height = canvas.height;
    const context = opaque.getContext('2d');
    if (context) {
      context.fillStyle = '#fff';
      context.fillRect(0, 0, opaque.width, opaque.height);
      context.drawImage(canvas, 0, 0);
    }
    encoded = opaque.toDataURL('image/jpeg', 0.9);
    mediaType = 'image/jpeg';
  }
  return { mediaType, data: base64Of(encoded), name };
}

/** Attaches dropped or pasted image files in order. */
function attachFiles(files: File[]): Promise<void> {
  const thread = state.active;
  if (!thread) return Promise.resolve();
  thread.attachQueue = thread.attachQueue.then(async () => {
    for (const file of files) {
      if (!file.type.startsWith('image/')) continue;
      try {
        addImage(thread, await imageFromFile(file));
      } catch (error) {
        showAlert(
          thread,
          file.name
            ? t('composer.image.error', file.name, String(error))
            : t('composer.image.error.unnamed', String(error))
        );
      }
    }
  });
  return thread.attachQueue;
}

function updateSlash(): void {
  const match = /^\/(\S*)$/.exec(input.value);
  const commands = state.active?.info?.commands || [];
  if (!match || !commands.length) {
    if (popupState.mode === 'slash') hidePopup();
    return;
  }
  const query = match[1].toLowerCase();
  const items = commands
    .filter(command => command.name.toLowerCase().includes(query))
    .sort((a, b) => Number(!a.name.toLowerCase().startsWith(query)) - Number(!b.name.toLowerCase().startsWith(query)))
    .slice(0, MAX_SLASH_ITEMS);
  if (!items.length) {
    hidePopup();
    return;
  }
  showPopup({ mode: 'slash', items });
}

/** An {@code @} before the caret asks the IDE for matching files. */
function updateMention(): void {
  const before = input.value.slice(0, input.selectionStart);
  const match = /(^|[\s(])@("[^"]*|[^\s@"]*)$/.exec(before);
  const t = state.active;
  if (!match || !t) {
    if (popupState.mode === 'mention') hidePopup();
    mention = null;
    return;
  }
  const query = match[2].replace(/^"/, '');
  mention = { start: before.length - match[2].length - 1, end: input.selectionStart, query };
  clearTimeout(mentionTimer);
  mentionTimer = window.setTimeout(() => {
    mentionRequest++;
    sendThread(t, { op: 'files', query, requestId: mentionRequest });
  }, MENTION_DEBOUNCE_MILLIS);
}

/** The IDE's files for the latest mention; answers to earlier ones are dropped. */
export function showMentions(t: Thread, requestId: number, items: FileMatch[]): void {
  if (!isActive(t) || requestId !== mentionRequest || !mention) return;
  if (!items.length) {
    hidePopup();
    return;
  }
  showPopup({ mode: 'mention', items });
}

function chooseMention(item: FileMatch): void {
  if (!mention) return;
  const path = item.path + (item.isDirectory ? '/' : '');
  const token = /\s/.test(path) ? '@"' + path + '"' : '@' + path;
  const insert = token + (item.isDirectory ? '' : ' ');
  input.value = input.value.slice(0, mention.start) + insert + input.value.slice(mention.end);
  const caret = mention.start + insert.length;
  input.setSelectionRange(caret, caret);
  hidePopup();
  autosize();
  input.focus();
  if (item.isDirectory) updateMention();
}

function showPopup(next: Popup): void {
  popupState = next;
  popupIndex = Math.min(popupIndex, next.items.length - 1);
  renderPopup();
}

function popupRow(index: number, onChoose: () => void): HTMLElement {
  const row = el('div', 'menu-item' + (index === popupIndex ? ' active' : ''));
  row.addEventListener('mousedown', event => {
    event.preventDefault();
    onChoose();
  });
  return row;
}

function renderPopup(): void {
  popup.textContent = '';
  if (popupState.mode === 'mention') {
    popupState.items.forEach((item, index) => {
      const row = popupRow(index, () => choosePopupItem(index));
      row.classList.add('mention-item');
      row.appendChild(icon(item.isDirectory ? 'folder' : 'file-text', 'small'));
      const text = el('span', 'text');
      const slash = item.path.lastIndexOf('/');
      text.appendChild(el('span', 'label', item.path.slice(slash + 1) + (item.isDirectory ? '/' : '')));
      if (slash > 0) text.appendChild(el('span', 'description', item.path.slice(0, slash)));
      row.appendChild(text);
      popup.appendChild(row);
    });
  } else {
    popupState.items.forEach((command, index) => {
      const row = popupRow(index, () => choosePopupItem(index));
      const text = el('span', 'text');
      text.appendChild(el('span', 'label', '/' + command.name + (command.hint ? '  ' + command.hint : '')));
      if (command.description) text.appendChild(el('span', 'description', command.description));
      row.appendChild(text);
      popup.appendChild(row);
    });
  }
  popup.hidden = false;
  // Sits 6px above the composer; keeps 8px to the window top and scrolls beyond that.
  popup.style.maxHeight = Math.max(80, $('composer').getBoundingClientRect().top - 6 - 8) + 'px';
  popup.children[popupIndex]?.scrollIntoView({ block: 'nearest' });
}

export function hidePopup(): void {
  popup.hidden = true;
  popupState = popupState.mode === 'slash' ? { mode: 'slash', items: [] } : { mode: 'mention', items: [] };
  popupIndex = 0;
}

function choosePopupItem(index: number): void {
  if (popupState.mode === 'mention') {
    const item = popupState.items[index];
    if (item) chooseMention(item);
    return;
  }
  const command = popupState.items[index];
  if (!command) return;
  input.value = '/' + command.name + ' ';
  hidePopup();
  autosize();
  input.focus();
}

function onKeyDown(event: KeyboardEvent): void {
  const popupOpen = !popup.hidden;
  const count = popupState.items.length;
  if (popupOpen && (event.key === 'ArrowDown' || event.key === 'ArrowUp')) {
    event.preventDefault();
    popupIndex = (popupIndex + (event.key === 'ArrowDown' ? 1 : -1) + count) % count;
    renderPopup();
    return;
  }
  if (popupOpen && (event.key === 'Tab' || (event.key === 'Enter' && !event.shiftKey))) {
    event.preventDefault();
    choosePopupItem(popupIndex);
    return;
  }
  const t = state.active;
  if (t?.question && !input.value && /^[1-9]$/.test(event.key)) {
    event.preventDefault();
    chooseOption(Number(event.key) - 1);
    return;
  }
  if (event.key === 'Enter' && event.shiftKey && (event.ctrlKey || event.metaKey) && !event.isComposing) {
    event.preventDefault();
    submitAndBranch();
    return;
  }
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault();
    submit();
    return;
  }
  if (event.key === 'Escape') {
    if (popupOpen) hidePopup();
    else if (t?.busy) sendThread(t, { op: 'interrupt' });
  }
}

function onPaste(event: ClipboardEvent): void {
  const t = state.active;
  if (!t) return;
  const files = [...(event.clipboardData?.items || [])]
    .filter(item => item.kind === 'file' && item.type.startsWith('image/'))
    .map(item => item.getAsFile())
    .filter((file): file is File => !!file);
  if (files.length) {
    event.preventDefault();
    attachFiles(files);
    return;
  }
  if (!event.clipboardData || !event.clipboardData.getData('text/plain')) sendThread(t, { op: 'pasteImage' });
}

export function initComposer(): void {
  input.addEventListener('input', () => {
    autosize();
    updateSlash();
    updateMention();
  });
  input.addEventListener('click', updateMention);
  input.addEventListener('paste', onPaste);
  input.addEventListener('keydown', onKeyDown);
  const composer = $('composer');
  composer.addEventListener('dragover', event => {
    if ([...(event.dataTransfer?.items || [])].some(item => item.kind === 'file')) {
      event.preventDefault();
      composer.classList.add('dragging');
    }
  });
  composer.addEventListener('dragleave', () => composer.classList.remove('dragging'));
  composer.addEventListener('drop', event => {
    composer.classList.remove('dragging');
    const files = [...(event.dataTransfer?.files || [])].filter(file => file.type.startsWith('image/'));
    if (files.length && state.active) {
      event.preventDefault();
      attachFiles(files);
    }
  });
  input.placeholder = DEFAULT_PLACEHOLDER;
}
