/** Dispatches what the IDE posts to the page. */
import { addPermission } from '../composer/approvals';
import { addContext, addImage, showMentions } from '../composer/composer';
import { refreshContextPanel } from '../composer/contextMeter';
import { renderControls } from '../composer/controls';
import type { Thread } from '../state/model';
import { withRootOf } from '../state/projects';
import { isActive, threads, workspace } from '../state/store';
import { handleEvent } from '../thread/stream';
import {
  activate,
  makeThread,
  onExit,
  onInit,
  onThreadError,
  removeThread,
  replay,
  updateThread
} from '../thread/threads';
import { follow } from '../thread/timeline';
import { $ } from '../ui/dom';
import { showAlert } from '../ui/widgets';
import { renderProjectName } from '../views/header';
import { pruneProjectGroups, scheduleSidebar, showSessions } from '../views/sidebar';
import { showUsage } from '../views/usage';
import { isThreadMessage, type PageMessage, type ThreadMessage, type WorkspaceMessage } from './protocol';

export function receive(message: PageMessage): void {
  if (isThreadMessage(message)) {
    const t = threads.get(message.thread);
    if (t) {
      withRootOf(t, () => receiveForThread(t, message));
      scheduleSidebar();
      follow(t);
    }
    return;
  }
  receiveForWorkspace(message as WorkspaceMessage);
}

function receiveForThread(t: Thread, message: ThreadMessage): void {
  switch (message.kind) {
    case 'init':
      onInit(t, message.info, message.projectRoot);
      break;
    case 'event':
      handleEvent(t, message.event);
      break;
    case 'replay':
      replay(t, message.events || []);
      break;
    case 'permission':
      addPermission(t, message.requestId, message.prompt);
      break;
    case 'context':
      addContext(t, message.item, message.prefill);
      break;
    case 'image':
      addImage(t, message.image);
      break;
    case 'files':
      showMentions(t, message.requestId, message.items || []);
      break;
    case 'contextUsage':
      t.contextUsage = message.data;
      if (isActive(t)) refreshContextPanel();
      break;
    case 'exit':
      onExit(t, message.code, message.stderr);
      break;
    case 'error':
      onThreadError(t, message.text);
      break;
  }
}

function receiveForWorkspace(message: WorkspaceMessage): void {
  switch (message.kind) {
    case 'project':
      workspace.projectRoot = message.projectRoot || '';
      workspace.projectName = message.projectName || '';
      renderProjectName();
      break;
    case 'agents':
      workspace.agents = message.items || [];
      renderControls();
      scheduleSidebar();
      break;
    case 'projects': {
      const previous = new Map(workspace.projects.map(project => [project.path, project]));
      workspace.projects = (message.items || []).map(item => ({
        ...item,
        collapsed: previous.get(item.path)?.collapsed ?? item.collapsed
      }));
      pruneProjectGroups(workspace.projects);
      renderProjectName();
      scheduleSidebar();
      break;
    }
    case 'threads':
      for (const item of message.items || []) {
        if (!threads.has(item.id)) makeThread(item);
      }
      scheduleSidebar();
      break;
    case 'threadAdded':
      if (!threads.has(message.item.id)) makeThread(message.item);
      scheduleSidebar();
      break;
    case 'threadUpdated':
      updateThread(threads.get(message.item.id) || makeThread(message.item), message.item);
      break;
    case 'threadRemoved':
      removeThread(message.thread);
      break;
    case 'select':
      activate(threads.get(message.thread));
      break;
    case 'sessions':
      showSessions(message.items || []);
      break;
    case 'usage':
      showUsage(message);
      break;
    case 'error':
      showAlert(null, message.text);
      break;
    case 'theme':
      $('theme').textContent = message.css;
      renderProjectName();
      scheduleSidebar();
      break;
  }
}
