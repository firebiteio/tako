/** The project folders threads work in, and paths shown relative to them. */
import type { ProjectItem } from '../bridge/protocol';
import { samePath } from '../ui/format';
import { t } from '../ui/i18n';
import type { Thread } from './model';
import { state, workspace } from './store';

/** The path relative to the root of the thread being rendered, else as given. */
export function relative(path: string | undefined | null): string {
  if (!path) return '';
  const normalized = String(path).replace(/\\/g, '/');
  const root = (workspace.renderRoot || workspace.projectRoot).replace(/\\/g, '/');
  if (root && normalized.toLowerCase().startsWith(root.toLowerCase() + '/')) return normalized.slice(root.length + 1);
  return normalized;
}

export function rootOf(t: Thread): string {
  return t.projectRoot || t.projectPath || workspace.projectRoot;
}

/** Runs a render for a thread with its paths relative to its own folder. */
export function withRootOf(t: Thread, render: () => void): void {
  workspace.renderRoot = rootOf(t);
  try {
    render();
  } finally {
    workspace.renderRoot = state.active ? rootOf(state.active) : workspace.projectRoot;
  }
}

export function projectList(): ProjectItem[] {
  if (workspace.projects.length) return workspace.projects;
  return [
    {
      path: workspace.projectRoot || '',
      name: workspace.projectName || t('common.project'),
      primary: true,
      collapsed: false
    }
  ];
}

export function projectOf(t: Thread): ProjectItem {
  const projects = projectList();
  const path = t.projectPath || workspace.projectRoot;
  return projects.find(project => samePath(project.path, path)) || projects[0];
}
