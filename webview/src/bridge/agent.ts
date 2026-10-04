/**
 * What every agent reports, whichever CLI runs it. Mirrors io.firebite.tako.agent: AgentEvent (discriminated by
 * {@code type}, the record name in lower camel case), AgentInfo and PermissionPrompt. Null fields are left out.
 */
import type { ImageAttachment } from './protocol';

export type ToolKind =
  'read' | 'edit' | 'command' | 'search' | 'web' | 'agent' | 'todo' | 'question' | 'plan' | 'other';

export interface Todo {
  content: string;
  status: 'pending' | 'in_progress' | 'completed';
  activeForm?: string;
}

export interface TextEdit {
  old_string?: string;
  new_string?: string;
}

/** A tool call's arguments: any JSON, with the members the page shows typed. */
export interface ToolInput extends TextEdit {
  [key: string]: unknown;
  command?: string;
  description?: string;
  file_path?: string;
  notebook_path?: string;
  edits?: TextEdit[];
  changes?: { path?: string; diff?: string }[];
  content?: string;
  todos?: Todo[];
  pattern?: string;
  path?: string;
  query?: string;
  url?: string;
  plan?: string;
  subagent_type?: string;
  prompt?: string;
  title?: string;
  name?: string;
}

export interface Command {
  name: string;
  description: string;
  hint: string;
}

export interface Model {
  value: string;
  displayName: string;
  description?: string;
  resolvedModel?: string;
  /** The effort levels the model takes; empty when it takes none. */
  efforts: string[];
}

export interface Mode {
  value: string;
  label: string;
  description: string;
}

/** What a started agent offers the composer. */
export interface AgentInfo {
  agent: string;
  agentName: string;
  authDescription: string;
  capabilities: { images: boolean; compact: boolean };
  models: Model[];
  model?: string;
  modes: Mode[];
  mode?: string;
  effort?: string;
  commands: Command[];
}

export interface Change {
  path: string;
  added: number;
  removed: number;
  isNew: boolean;
}

export interface SessionCost {
  costUsd: number;
  inputTokens: number;
  outputTokens: number;
  cacheReadTokens: number;
  cacheWriteTokens: number;
}

export interface WorkflowAgent {
  index: number;
  label?: string;
  state?: string;
  model?: string;
  toolCalls?: number;
  tokens?: number;
  phaseIndex?: number;
  phaseTitle?: string;
  error?: string;
  resultPreview?: string;
  promptPreview?: string;
}

export interface ToolStarted {
  type: 'toolStarted';
  id: string;
  name: string;
  kind: ToolKind;
  input: ToolInput;
  title?: string;
  parentId?: string;
}

export interface ToolFinished {
  type: 'toolFinished';
  id: string;
  output: string;
  isError: boolean;
  change?: Change;
}

export interface Usage {
  type: 'usage';
  input: number;
  output: number;
  cacheRead: number;
  cacheWrite: number;
  cacheWrite1h: number;
  cacheWrite5m: number;
  /** When the request was made; absent for now. */
  at?: number;
  contextWindow?: number;
}

export interface TurnEnded {
  type: 'turnEnded';
  durationMs?: number;
  error?: string;
  cost?: SessionCost;
}

export interface TaskUpdated {
  type: 'taskUpdated';
  taskId: string;
  toolUseId?: string;
  /** {@code agent}, {@code shell} or {@code workflow}. */
  taskType?: string;
  description?: string;
  subagentType?: string;
  prompt?: string;
  backgrounded?: boolean;
  status?: string;
  summary?: string;
  toolUses?: number;
  totalTokens?: number;
  lastTool?: string;
  endedAt?: number;
  notified: boolean;
  agents: WorkflowAgent[];
  logs: string[];
}

export type AgentEvent =
  | {
      type: 'sessionStarted';
      sessionId?: string;
      workingDirectory?: string;
      model?: string;
      mode?: string;
      scratchpadPath?: string;
    }
  | { type: 'modeChanged'; mode: string }
  | { type: 'commandsChanged'; commands: Command[] }
  | { type: 'busy' }
  | { type: 'messageStarted'; messageId?: string }
  | { type: 'textStarted'; blockId: string }
  | { type: 'textDelta'; blockId: string; text: string }
  | { type: 'thinkingStarted'; blockId: string }
  | { type: 'blockEnded'; blockId: string }
  | { type: 'text'; messageId?: string; text: string; parentId?: string }
  | ToolStarted
  | ToolFinished
  | { type: 'prompt'; text: string; hasIdeContext: boolean; images: ImageAttachment[] }
  | { type: 'interrupted' }
  | Usage
  | TurnEnded
  | { type: 'rateLimits'; limits: { label: string; usedPercent: number }[] }
  | { type: 'retrying'; attempt?: number; message?: string }
  | { type: 'compacted' }
  | { type: 'permissionCancelled'; requestId: string }
  | TaskUpdated;

export interface Question {
  question: string;
  header?: string;
  multiSelect: boolean;
  options: { label: string; description?: string }[];
}

/** The agent waits for the person: to approve a tool call, to answer questions or to accept a plan. */
export interface PermissionPrompt {
  kind: 'tool' | 'question' | 'plan';
  toolName: string;
  category: ToolKind;
  input: ToolInput;
  toolUseId?: string;
  label?: string;
  description?: string;
  canDiff: boolean;
  /** Broader approvals besides approving once; approving with one may switch to its mode. */
  suggestions: { label: string; mode?: string }[];
  questions: Question[];
  plan?: string;
}
