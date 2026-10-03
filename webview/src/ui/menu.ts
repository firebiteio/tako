/** Popup menus and other popovers placed next to what opened them. */
import { el, icon, on } from './dom';
import { t } from './i18n';
import type { IconName } from './icons';

export interface MenuItem {
  label: string;
  description?: string;
  icon?: IconName;
  checked?: boolean;
  run(): void;
}

const MENU_ID = 'popup-menu';

/** Puts a fixed popover next to an anchor rectangle on the side with room, scrolling when neither has enough. */
export function placeFloating(
  node: HTMLElement,
  rect: DOMRect,
  preferred: 'above' | 'below',
  alignRight: boolean
): void {
  const margin = 8;
  const gap = 6;
  node.style.top = 'auto';
  node.style.bottom = 'auto';
  node.style.maxHeight = 'none';
  const natural = node.scrollHeight;
  const above = rect.top - gap - margin;
  const below = window.innerHeight - rect.bottom - gap - margin;
  const useAbove = preferred === 'above' ? natural <= above || above >= below : !(natural <= below || below >= above);
  if (useAbove) {
    node.style.bottom = window.innerHeight - rect.top + gap + 'px';
    node.style.maxHeight = Math.max(80, above) + 'px';
  } else {
    node.style.top = rect.bottom + gap + 'px';
    node.style.maxHeight = Math.max(80, below) + 'px';
  }
  const width = node.offsetWidth;
  const left = alignRight ? rect.right - width : rect.left;
  node.style.left = Math.max(margin, Math.min(left, window.innerWidth - width - margin)) + 'px';
  node.style.right = 'auto';
}

export function openMenu(anchor: HTMLElement, items: MenuItem[], placement: 'above' | 'below', title?: string): void {
  closeMenu();
  const menu = el('div', 'menu');
  menu.id = MENU_ID;
  if (title) menu.appendChild(el('div', 'menu-title', title));
  if (!items.length) menu.appendChild(el('div', 'menu-empty', t('menu.empty')));
  for (const item of items) {
    const row = el('button', 'menu-item');
    if (item.icon) row.appendChild(icon(item.icon, 'small'));
    const text = el('span', 'text');
    text.appendChild(el('span', 'label', item.label));
    if (item.description) text.appendChild(el('span', 'description', item.description));
    row.appendChild(text);
    if (item.checked) {
      const check = icon('check', 'small');
      check.classList.add('check');
      row.appendChild(check);
    }
    menu.appendChild(
      on(row, () => {
        closeMenu();
        item.run();
      })
    );
  }
  document.body.appendChild(menu);
  placeFloating(menu, anchor.getBoundingClientRect(), placement, false);
}

export function closeMenu(): void {
  document.getElementById(MENU_ID)?.remove();
}

/** Closes the open menu when a click lands outside it. */
export function closeMenuOutside(target: Node): void {
  const menu = document.getElementById(MENU_ID);
  if (menu && !menu.contains(target)) closeMenu();
}
