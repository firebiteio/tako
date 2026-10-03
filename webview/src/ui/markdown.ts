/** Claude's markdown, sanitized, with code blocks and file paths wired to the IDE. */
import DOMPurify from 'dompurify';
import hljs from 'highlight.js/lib/common';
import { marked } from 'marked';

import { send, sendThread } from '../bridge/protocol';
import { state } from '../state/store';
import { el, iconButton } from './dom';
import { t } from './i18n';

marked.use({ gfm: true, breaks: true });

/** A path, optionally with a line: {@code src/a.ts}, {@code C:\x\b.java:12}. */
const PATH = /^((?:[A-Za-z]:)?[\w.\-\/\\]*[\w\-]\.[A-Za-z0-9]{1,10})(?::(\d+))?$/;
/** Has a folder, or looks like a bare file name, so version numbers like {@code 1.2} are no paths. */
const PATH_LIKE = /[\/\\]|^[\w\-]+\.[A-Za-z]{1,6}$/;

/** @param isFinal highlights code; skipped while text still streams in */
export function markdown(target: HTMLElement, text: string, isFinal: boolean): void {
  target.innerHTML = DOMPurify.sanitize(marked.parse(text || '', { async: false }));
  enhance(target, isFinal);
}

function enhance(root: HTMLElement, highlight: boolean): void {
  root.querySelectorAll<HTMLElement>('pre > code').forEach(code => {
    const pre = code.parentElement as HTMLElement;
    const block = el('div', 'code-block');
    pre.replaceWith(block);
    const head = el('div', 'code-head');
    const language = /language-([\w+#-]+)/.exec(code.className);
    head.appendChild(el('span', null, language ? language[1] : 'text'));
    const tools = el('div', 'tools');
    tools.appendChild(
      iconButton('square-pen', t('markdown.insert'), () => {
        if (state.active) sendThread(state.active, { op: 'insert', text: code.textContent || '' });
      })
    );
    tools.appendChild(iconButton('copy', t('common.copy'), () => send({ op: 'copy', text: code.textContent || '' })));
    head.appendChild(tools);
    block.appendChild(head);
    block.appendChild(pre);
    if (highlight && language) {
      try {
        hljs.highlightElement(code);
      } catch (ignored) {}
    }
  });
  root.querySelectorAll<HTMLElement>(':not(pre) > code').forEach(code => {
    const match = PATH.exec((code.textContent || '').trim());
    if (match && PATH_LIKE.test(match[1])) {
      code.classList.add('path');
      code.title = t('markdown.open');
      code.addEventListener('click', () => {
        if (state.active) sendThread(state.active, { op: 'open', path: match[1], line: Number(match[2] || 1) });
      });
    }
  });
  root.querySelectorAll<HTMLAnchorElement>('a[href]').forEach(link => {
    link.addEventListener('click', event => {
      event.preventDefault();
      send({ op: 'openUrl', url: link.getAttribute('href') || '' });
    });
  });
}
