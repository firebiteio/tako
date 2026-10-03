import { ICONS, type IconName } from './icons';

export type IconSize = '' | 'tiny' | 'small' | 'tiny spin' | 'small spin' | 'small chevron';

/** An element of the static page; throws when index.html lacks it. */
export function $<T extends HTMLElement = HTMLElement>(id: string): T {
  const node = document.getElementById(id);
  if (!node) throw new Error('Missing #' + id);
  return node as T;
}

export function el<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  className?: string | null,
  text?: string | null
): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined && text !== null) node.textContent = text;
  return node;
}

function svg(markup: string): SVGSVGElement {
  const span = document.createElement('span');
  span.innerHTML = markup;
  return span.firstChild as SVGSVGElement;
}

export function icon(name: IconName, size: IconSize = ''): SVGSVGElement {
  return svg(
    '<svg class="icon' +
      (size ? ' ' + size : '') +
      '" viewBox="0 0 24 24" fill="none" stroke="currentColor" ' +
      'stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
      ICONS[name] +
      '</svg>'
  );
}

export function sendArrow(): SVGSVGElement {
  return svg(
    '<svg class="icon small" viewBox="0 0 14 14" fill="none" stroke="currentColor" stroke-width="1.8" ' +
      'stroke-linecap="round" stroke-linejoin="round"><path d="M7 11.5V2.5M7 2.5L3 6.5M7 2.5L11 6.5"/></svg>'
  );
}

export function stopSquare(): SVGSVGElement {
  return svg(
    '<svg class="icon tiny" viewBox="0 0 12 12"><rect x="2" y="2" width="8" height="8" rx="1.5" fill="currentColor"/></svg>'
  );
}

/** A spinner in phase with every other one on the page, however late it was created. */
export function spinner(): SVGSVGElement {
  const node = icon('loader-circle', 'small spin');
  node.style.animationDelay = '-' + (Date.now() % 1000) + 'ms';
  return node;
}

export function chevron(size: IconSize = 'tiny'): HTMLSpanElement {
  const node = el('span', 'row-chevron');
  node.appendChild(icon('chevron-right', size));
  return node;
}

/** A row's leading icon slot. */
export function rowIcon(name: IconName): HTMLSpanElement {
  const node = el('span', 'row-icon');
  node.appendChild(icon(name));
  return node;
}

/** Click handler that keeps the click from reaching rows and menus underneath. */
export function on<T extends HTMLElement>(node: T, handler: (event: MouseEvent) => void): T {
  node.addEventListener('click', event => {
    event.stopPropagation();
    event.preventDefault();
    handler(event);
  });
  return node;
}

export function iconButton(
  name: IconName,
  title: string,
  handler: (event: MouseEvent) => void,
  extraClass?: string
): HTMLButtonElement {
  const node = el('button', 'icon-button xs' + (extraClass ? ' ' + extraClass : ''));
  node.title = title;
  node.appendChild(icon(name, 'small'));
  return on(node, handler);
}

export function textButton(label: string, className: string, handler: (event: MouseEvent) => void): HTMLButtonElement {
  return on(el('button', 'button ' + className, label), handler);
}

/** Sets text only when it changed, so a selection inside the node survives re-renders. */
export function setText(node: Node, text: string): void {
  if (node.textContent !== text) node.textContent = text;
}
