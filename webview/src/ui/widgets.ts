/** Small pieces shared by the timeline and the composer. */
import type { ContextItem, ImageAttachment, ProjectItem } from '../bridge/protocol';
import type { Thread } from '../state/model';
import { $, el, icon, iconButton, on, textButton, type IconSize } from './dom';
import { t } from './i18n';

export function imageThumb(image: ImageAttachment, onRemove: (() => void) | null): HTMLElement {
  const thumb = el('div', 'thumb');
  const img = el('img');
  img.src = 'data:' + image.mediaType + ';base64,' + image.data;
  img.alt = image.name || t('common.image');
  thumb.title = image.name || t('common.image');
  thumb.appendChild(img);
  thumb.addEventListener('click', () => openLightbox(img.src));
  if (onRemove) {
    const remove = el('button', 'remove');
    remove.title = t('common.remove');
    remove.appendChild(icon('x', 'tiny'));
    thumb.appendChild(on(remove, onRemove));
  }
  return thumb;
}

function openLightbox(source: string): void {
  const box = $('lightbox');
  (box.querySelector('img') as HTMLImageElement).src = source;
  box.hidden = false;
}

export function closeLightbox(): void {
  $('lightbox').hidden = true;
}

/** A chip for attached IDE context. */
export function mentionChip(item: ContextItem, onRemove: (() => void) | null): HTMLElement {
  const chip = el('span', 'mention ' + (item.kind || 'file'));
  chip.title = item.text || item.label;
  chip.appendChild(
    icon(item.kind === 'problems' ? 'circle-alert' : item.kind === 'selection' ? 'file-code' : 'file-text', 'tiny')
  );
  chip.appendChild(el('span', null, item.label));
  if (onRemove) chip.appendChild(on(el('button'), onRemove)).appendChild(icon('x', 'tiny'));
  return chip;
}

export function inlineAlert(text: string): HTMLElement {
  const alert = el('div', 'inline-alert');
  alert.appendChild(icon('circle-alert', 'small'));
  alert.appendChild(el('span', null, text));
  return alert;
}

/** A dismissible alert above the thread, or above the whole page without one. */
export function showAlert(thread: Thread | null, text: string, action?: { label: string; run(): void }): void {
  const alert = el('div', 'alert');
  alert.appendChild(icon('circle-alert', 'small'));
  alert.appendChild(el('span', 'text', text));
  if (action) {
    alert.appendChild(
      textButton(action.label, 'outline', () => {
        alert.remove();
        action.run();
      })
    );
  }
  alert.appendChild(iconButton('x', t('common.dismiss'), () => alert.remove()));
  (thread ? thread.alertsEl : $('alerts')).appendChild(alert);
}

function isDarkTheme(): boolean {
  return ($('theme').textContent || '').includes('color-scheme:dark');
}

export function projectIconUrl(project: ProjectItem | null): string | null {
  if (!project) return null;
  return (isDarkTheme() && project.iconDark) || project.icon || null;
}

export function projectIcon(project: ProjectItem | null, size: IconSize): Element {
  const url = projectIconUrl(project);
  if (!url) return icon('folder', size);
  const image = el('img', 'project-icon' + (size ? ' ' + size : ''));
  image.src = url;
  image.alt = '';
  return image;
}
