/** The chat page: wires the static controls, starts the clocks and tells the IDE it is ready. */
import { send, sendThread } from './bridge/protocol';
import { receive } from './bridge/receive';
import { initComposer, input, renderSend, submit } from './composer/composer';
import { closeContextPanel, closeContextPanelOutside, tickCache, toggleContextPanel } from './composer/contextMeter';
import {
  fitControls,
  openAgentMenu,
  openEffortMenu,
  openModelMenu,
  openModeMenu,
  toggleIncludeFile
} from './composer/controls';
import { state } from './state/store';
import { closeTaskPanel, tickTasks } from './thread/tasks';
import { onScroll, scrollToEnd, tickBusy } from './thread/threads';
import { scroller } from './thread/timeline';
import { $, el, icon, on } from './ui/dom';
import { t } from './ui/i18n';
import { closeMenu, closeMenuOutside } from './ui/menu';
import { closeLightbox } from './ui/widgets';
import { NARROW, restoreSidebar, scheduleSidebar, setSidebarOpen, toggleArchived } from './views/sidebar';
import { closeUsage, showUsage } from './views/usage';

const SECOND = 1000;
/** Refreshes the sidebar's "5m ago" times. */
const MINUTE = 60 * SECOND;

function newThreadHere(): void {
  send({ op: 'newThread', projectPath: state.active ? state.active.projectPath : null });
}

function wireControls(): void {
  on($('send'), submit);
  on($('context-meter'), toggleContextPanel);
  on($('cache-timer'), toggleContextPanel);
  on($('attach'), () => {
    if (state.active) sendThread(state.active, { op: 'pickImage' });
  });
  on($('new-chat'), newThreadHere);
  on($('sidebar-new'), newThreadHere);
  on($('sidebar-add-project'), () => send({ op: 'addProject' }));
  on($('sidebar-history'), () => send({ op: 'history' }));
  on($('sidebar-toggle'), () => setSidebarOpen(!document.body.classList.contains('sidebar-open')));
  on($('sidebar-backdrop'), () => setSidebarOpen(false));
  on($('usage-button'), () => {
    showUsage(null);
    if (window.innerWidth <= NARROW) setSidebarOpen(false);
    send({ op: 'usage' });
  });
  on($('archived-toggle'), toggleArchived);
  on($('scroll-end'), scrollToEnd);
  on($('agent-picker'), openAgentMenu);
  on($('model-picker'), openModelMenu);
  on($('effort-picker'), openEffortMenu);
  on($('mode-picker'), openModeMenu);
  on($('file-toggle'), toggleIncludeFile);
  on($('lightbox'), closeLightbox);
  scroller.addEventListener('scroll', onScroll);
}

function wireGlobalKeysAndClicks(): void {
  document.addEventListener('click', event => {
    closeMenuOutside(event.target as Node);
    closeContextPanelOutside(event.target as Element);
  });
  new ResizeObserver(fitControls).observe($('composer'));
  window.addEventListener('resize', () => {
    closeMenu();
    closeContextPanel();
  });
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape') {
      closeMenu();
      closeUsage();
      closeTaskPanel();
      closeContextPanel();
      closeLightbox();
    }
  });
}

function addStaticIcons(): void {
  $('sidebar-toggle').appendChild(icon('panel-left'));
  $('attach').appendChild(icon('paperclip'));
  const usageValue = el('span', 'value');
  usageValue.id = 'usage-button-value';
  $('usage-button').append(icon('chart-column', 'small'), el('span', null, t('sidebar.usage')), usageValue);
  $('sidebar-history').appendChild(icon('history'));
  $('sidebar-add-project').appendChild(icon('folder-plus'));
  $('sidebar-new').appendChild(icon('plus'));
  $('new-chat').appendChild(icon('plus'));
  $('scroll-end').append(icon('arrow-down', 'small'), el('span', null, t('timeline.scroll.to.end')));
}

initComposer();
wireControls();
wireGlobalKeysAndClicks();
restoreSidebar();
addStaticIcons();
setInterval(tickTasks, SECOND);
setInterval(tickBusy, SECOND);
setInterval(tickCache, SECOND);
setInterval(scheduleSidebar, MINUTE);
renderSend();
window.tako = { receive };
send({ op: 'ready' });
input.focus();
