/** The page's state: threads, their turns and the tool calls in them. */
import type { AgentInfo, Change, PermissionPrompt, ToolInput, ToolKind, WorkflowAgent } from '../bridge/agent';
import type {
  AgentOption,
  ContextItem,
  ContextUsage,
  ImageAttachment,
  ProjectItem,
  PullRequest
} from '../bridge/protocol';

export type EntryStatus = 'running' | 'done' | 'error' | 'declined';

export interface AssistantRow {
  el: HTMLElement;
  body: HTMLElement;
  /** The markdown source, for copying. */
  rawText: string;
}

/** An assistant text block arriving as stream deltas. */
export interface LiveText {
  row: AssistantRow;
  text: string;
  done: boolean;
  scheduled: boolean;
}

/** Consecutive tool calls of a turn, shown as one collapsible row. */
export interface WorkGroup {
  thread: Thread;
  entries: ToolEntry[];
  el: HTMLElement;
  head: HTMLElement;
  icon: HTMLElement;
  label: HTMLElement;
  list: HTMLElement;
}

interface EntryBase {
  thread: Thread;
  id: string;
  name: string;
  input: ToolInput;
  category: ToolKind;
  /** The agent's own label for the call, when it gives one. */
  title: string | undefined;
  status: EntryStatus;
  el: HTMLElement;
  row: HTMLElement;
  label: HTMLElement;
  stat: HTMLElement;
  /** Where a subagent's calls go. */
  subList: HTMLElement;
  details: HTMLElement;
}

/** One tool call. */
export interface ToolEntry extends EntryBase {
  isTask: false;
  isNew: boolean;
  added: number;
  removed: number;
  group: WorkGroup | null;
}

/** What runs as a task, as {@code TaskUpdated.taskType} names it. */
export type TaskType = 'agent' | 'shell' | 'workflow';

/** A tool call that runs as a task: a subagent, a workflow or a background shell. */
export interface TaskCard extends EntryBase {
  isTask: true;
  taskType: TaskType;
  task: Task | null;
  resultText: string;
  state: HTMLElement;
  completionRow: HTMLElement | null;
  turn: Turn | null;
}

export type Entry = ToolEntry | TaskCard;

/** A task as its {@code system/task_*} events describe it. */
export interface Task {
  id: string;
  agents: Map<number, WorkflowAgent>;
  logs: string[];
  seenLogs: Set<string>;
  status: string;
  startedAt: number;
  endedAt?: number;
  toolUseId?: string;
  type?: string;
  description?: string;
  subagentType?: string;
  prompt?: string;
  backgrounded?: boolean;
  summary?: string;
  toolUses?: number;
  totalTokens?: number;
  lastTool?: string;
}

export type TurnItemKind = 'text' | 'live' | 'entry' | 'group' | 'task' | 'completion' | 'note' | 'plan';

export interface Turn {
  /** The page's number for the prompt that started the turn; the IDE keeps the turn's changes under it. */
  no: number;
  el: HTMLElement;
  items: { node: HTMLElement; kind: TurnItemKind }[];
  group: WorkGroup | null;
  lastText: AssistantRow | null;
  changes: Map<string, Change>;
  startedAt: number;
  replayed: boolean;
  done: boolean;
  stopped: boolean;
  /** The "Working for…" line while the turn runs. */
  working: HTMLElement | null;
}

export interface Approval {
  requestId: string;
  prompt: PermissionPrompt;
}

/** Progress through an {@code AskUserQuestion} prompt. */
export interface QuestionState {
  requestId: string;
  index: number;
  answers: Record<string, string>;
  selected: Set<string>;
}

export interface PromptCache {
  at: number;
  ttl: number;
  tokens: number;
}

export interface PendingImage extends ImageAttachment {
  id: string;
}

/** A thread's row in the sidebar, with the parts updated in place. */
export interface ThreadRow {
  el: HTMLElement;
  status: HTMLElement;
  title: HTMLElement;
  time: HTMLElement;
  sub: HTMLElement;
  git: HTMLElement;
  actions: HTMLElement | null;
  statusKey: string | null;
  gitKey: string | null;
  archived: boolean | null;
}

export interface Thread {
  id: string;
  title: string;
  archived: boolean;
  updatedAt: number;
  el: HTMLElement;
  alertsEl: HTMLElement;
  row: ThreadRow | null;
  turnNo: number;
  turn: Turn | null;
  turnCount: number;
  entries: Map<string, Entry>;
  declined: Set<string>;
  live: Map<string | undefined, LiveText[]>;
  liveByIndex: Map<string, LiveText>;
  thinkingByIndex: Map<string, HTMLElement>;
  currentMessageId: string | undefined;
  contexts: ContextItem[];
  images: PendingImage[];
  attachQueue: Promise<void>;
  approvals: Approval[];
  question: QuestionState | null;
  busy: boolean;
  startedAt: number;
  contextUsage: ContextUsage | null;
  liveContext: { used: number; at: number } | null;
  cache: PromptCache | null;
  tasks: Map<string, Task>;
  finished: boolean;
  unread: boolean;
  failed: boolean;
  model: string | null;
  mode: string;
  effort: string | null;
  costUsd: number;
  tokens: number;
  branch: string | null;
  worktree: string | null;
  pullRequest: PullRequest | null;
  projectPath: string | null;
  projectRoot: string | null;
  /** The id of the agent the thread runs. */
  agent: string;
  hasSession: boolean;
  /** What the agent offers, once it started. */
  info: AgentInfo | null;
  /** The model's context size, when the agent reports it. */
  contextWindow: number | null;
  draft: string;
  stick: boolean;
  scrollTop: number;
  replaying: boolean;
}

/** What the page knows beyond single threads. */
export interface Workspace {
  projectRoot: string;
  projectName: string;
  projects: ProjectItem[];
  /** The root paths are shown relative to while a thread's message is handled. */
  renderRoot: string;
  agents: AgentOption[];
  includeFile: boolean;
  limits: string;
}
