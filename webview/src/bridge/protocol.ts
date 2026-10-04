/**
 * The page ↔ IDE protocol. Mirrors io.firebite.tako.ui.bridge.PageOp (page → IDE, {@code op}) and PageMessage
 * (IDE → page, {@code kind}): each name is the Java record's name in lower camel case. The IDE leaves null fields
 * out, so nullable fields are optional here.
 */
import type { AgentEvent, AgentInfo, PermissionPrompt } from './agent';

export interface ContextItem {
  id: string;
  kind: 'selection' | 'file' | 'problems';
  label: string;
  text?: string;
}

export interface ImageAttachment {
  mediaType: string;
  data: string;
  name: string;
}

export interface PullRequest {
  number: string;
  title: string;
  url: string;
  state: string;
}

export interface ThreadItem {
  id: string;
  title: string;
  archived: boolean;
  updatedAt: number;
  hasSession: boolean;
  costUsd: number;
  tokens: number;
  branch?: string;
  worktree?: string;
  projectPath?: string;
  pullRequest?: PullRequest;
  agent: string;
  /** The bench the thread is a run of. */
  bench?: string;
  runIndex: number;
  /** How long the run's first turn took, 0 until it ended. */
  runMillis: number;
}

export interface BenchItem {
  id: string;
  title: string;
  projectPath?: string;
  createdAt: number;
  baseCommit: string;
}

export interface RunChanges {
  thread: string;
  runIndex: number;
  files: number;
  added: number;
  removed: number;
  error?: string;
}

/** Blank values keep the agent's defaults. */
export interface RunSpec {
  agent: string;
  model: string;
  mode: string;
  effort: string;
}

export interface ProjectItem {
  path: string;
  name: string;
  collapsed: boolean;
  primary: boolean;
  icon?: string;
  iconDark?: string;
}

export interface SessionItem {
  id: string;
  title: string;
  lastModified: number;
  projectPath: string;
  projectName: string;
  agent: string;
  agentName: string;
}

export interface AgentOption {
  id: string;
  name: string;
}

export interface Usage {
  totalCost: number;
  totalTokens: number;
  sessions: number;
  estimatedSessions: number;
  estimatedCost: number;
  retentionDays: number;
  oldestDay?: string;
  models: { model: string; input: number; output: number; cacheRead: number; cacheWrite: number; cost: number }[];
  days: { day: string; cost: number }[];
  projects: { name: string; path: string; cost: number; tokens: number; sessions: number }[];
  top: {
    sessionId: string;
    projectPath: string;
    projectName: string;
    title: string;
    cost: number;
    tokens: number;
    thread?: string;
    archived: boolean;
    estimated: boolean;
  }[];
}

export interface ContextUsage {
  totalTokens?: number;
  maxTokens?: number;
  autoCompactThreshold?: number;
  isAutoCompactEnabled?: boolean;
  categories: { name: string; tokens: number; kind: 'used' | 'free' | 'deferred' }[];
  memoryFiles: { path: string; type?: string; tokens: number }[];
  mcpServers: { name: string; loadedTokens: number; deferredTokens: number; tools: number }[];
  messages?: {
    toolCallTokens: number;
    toolResultTokens: number;
    attachmentTokens: number;
    assistantMessageTokens: number;
    userMessageTokens: number;
    redirectedContextTokens: number;
    unattributedTokens: number;
  };
}

export interface FileMatch {
  path: string;
  isDirectory: boolean;
}

export type WorkspaceMessage =
  | { kind: 'project'; projectRoot?: string; projectName: string }
  | { kind: 'projects'; items: ProjectItem[] }
  | { kind: 'agents'; items: AgentOption[] }
  | { kind: 'threads'; items: ThreadItem[] }
  | { kind: 'threadAdded'; item: ThreadItem }
  | { kind: 'threadUpdated'; item: ThreadItem }
  | { kind: 'threadRemoved'; thread: string }
  | { kind: 'select'; thread: string }
  | { kind: 'sessions'; items: SessionItem[] }
  | ({ kind: 'usage' } & Usage)
  | { kind: 'theme'; css: string }
  | { kind: 'settings'; parallelRuns: boolean }
  | { kind: 'benches'; items: BenchItem[] }
  | { kind: 'benchCreated'; request: number; bench: string; threads: string[] }
  | { kind: 'benchFailed'; request: number; text: string }
  | { kind: 'benchComparison'; bench: string; runs: RunChanges[] }
  | { kind: 'error'; text: string };

export type ThreadMessage = { thread: string } & (
  | { kind: 'init'; info: AgentInfo; projectRoot?: string }
  | { kind: 'event'; event: AgentEvent }
  | { kind: 'replay'; events: AgentEvent[] }
  | { kind: 'permission'; requestId: string; prompt: PermissionPrompt }
  | { kind: 'context'; item: ContextItem; prefill?: string }
  | { kind: 'image'; image: ImageAttachment }
  | { kind: 'files'; requestId: number; items: FileMatch[] }
  | { kind: 'scratchpad'; path: string; files: string[] }
  | { kind: 'contextUsage'; data: ContextUsage }
  | { kind: 'exit'; code: number; stderr: string }
  | { kind: 'error'; text: string }
);

export type PageMessage = WorkspaceMessage | ThreadMessage;

const THREAD_KINDS: ReadonlySet<string> = new Set<ThreadMessage['kind']>([
  'init',
  'event',
  'replay',
  'permission',
  'context',
  'image',
  'files',
  'scratchpad',
  'contextUsage',
  'exit',
  'error'
]);

export function isThreadMessage(message: PageMessage): message is ThreadMessage {
  return THREAD_KINDS.has(message.kind) && 'thread' in message && !!message.thread;
}

export type WorkspaceOp =
  | { op: 'ready' }
  | { op: 'newThread'; projectPath?: string | null; agent?: string }
  | { op: 'addProject' }
  | { op: 'removeProject'; path: string }
  | { op: 'collapseProject'; path: string; collapsed: boolean }
  | { op: 'select'; thread: string }
  | { op: 'close'; thread: string }
  | { op: 'archive'; thread: string; archived: boolean }
  | { op: 'history' }
  | { op: 'usage' }
  | { op: 'openUrl'; url: string }
  | { op: 'resume'; sessionId: string; title?: string; projectPath?: string; agent?: string }
  | { op: 'setAgent'; thread: string; agent: string }
  | { op: 'copy'; text: string }
  | {
      op: 'createBench';
      request: number;
      title: string;
      projectPath?: string | null;
      source?: string;
      runs: RunSpec[];
    }
  | { op: 'compareBench'; bench: string }
  | { op: 'showRunDiff'; bench: string; runIndex: number }
  | { op: 'pickWinner'; bench: string; runIndex: number }
  | { op: 'discardBench'; bench: string };

export type ThreadOp =
  | {
      op: 'send';
      text: string;
      context: ContextItem[];
      images: ImageAttachment[];
      includeActiveFile: boolean;
      turn: number;
    }
  | { op: 'interrupt' }
  | {
      op: 'permission';
      requestId: string;
      allow: boolean;
      message?: string;
      interrupt: boolean;
      answers?: Record<string, string>;
      suggestion?: number;
    }
  | { op: 'diff'; requestId: string }
  | { op: 'open'; path: string; line: number }
  | { op: 'insert'; text: string }
  | { op: 'setModel'; model: string }
  | { op: 'setMode'; mode: string }
  | { op: 'setEffort'; effort: string }
  | { op: 'pasteImage' }
  | { op: 'pickImage' }
  | { op: 'contextUsage' }
  | { op: 'files'; query: string; requestId: number }
  | { op: 'turnDiff'; turn: number; path?: string }
  | { op: 'restart' }
  | { op: 'scratchpad' }
  | { op: 'revealScratchpad' };

declare global {
  interface Window {
    /** Injected by ChatPanel: hands a JSON op to the IDE. */
    sendToIde(payload: string): void;
    tako: { receive(message: PageMessage): void };
  }
}

export function send(op: WorkspaceOp): void {
  window.sendToIde(JSON.stringify(op));
}

export function sendThread(thread: { id: string }, op: ThreadOp): void {
  window.sendToIde(JSON.stringify({ ...op, thread: thread.id }));
}
