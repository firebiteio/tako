import type { Thread, Workspace } from './model';

export const threads = new Map<string, Thread>();

export const state: { active: Thread | null } = { active: null };

export const workspace: Workspace = {
  projectRoot: '',
  projectName: '',
  projects: [],
  renderRoot: '',
  agents: [],
  includeFile: true,
  limits: ''
};

export function isActive(t: Thread): boolean {
  return t === state.active;
}
