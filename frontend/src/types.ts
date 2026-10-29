export interface RunStats {
  runs: number
  succeeded: number
  failed: number
  attributionRuns: number
  guardedEnds: number
  totalTokens: number
  avgTokens: number
  p50Seconds: number
  p90Seconds: number
}

export interface RunSummary {
  id: string
  question: string
  mode: string
  status: string
  attributionOutcome: string | null
  stepCount: number
  promptTokens: number
  completionTokens: number
  createdAt: string
  finishedAt: string | null
}

export interface StepRow {
  id: number
  stepNo: number
  type: string
  toolName: string | null
  input: unknown
  output: unknown
  latencyMs: number | null
  promptTokens: number
  completionTokens: number
  createdAt: string
}

export interface NodeRow {
  id: number
  parentId: number | null
  depth: number
  dimension: string | null
  targetValue: string | null
  metricCode: string
  currentValue: number | null
  baselineValue: number | null
  delta: number | null
  contribution: number | null
  verdict: string
  evidenceStepId: number | null
  queryHash: string | null
  rowCount: number | null
}

export interface RunDetail {
  run: RunSummary
  answer: string | null
  error: string | null
  steps: StepRow[]
  nodes: NodeRow[]
}

export interface KnowledgeDoc {
  id: number
  docType: string
  code: string | null
  title: string
  content: string
  synonyms: string[]
  sqlTemplate: string | null
  relatedTables: string[]
  score: number | null
}

export interface KnowledgePage {
  mode: string
  query?: string
  fallback?: string
  total: number
  embedded: number
  docs: KnowledgeDoc[]
}

export interface EvalBatch {
  id: string
  kind: 'AGENT' | 'RETRIEVAL'
  label: string | null
  note: string | null
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  baselineId: string | null
  gitSha: string | null
  model: string | null
  embeddingModel: string | null
  asOf: string | null
  config: Record<string, unknown>
  promptHashes: Record<string, string>
  datasetVersion: string | null
  datasetSize: number
  progressDone: number
  progressTotal: number
  summary: Record<string, unknown> | null
  error: string | null
  startedAt: string
  finishedAt: string | null
}

export interface AgentEvalCase {
  id: number
  question: string
  kind: string
  expectedOutcome: string | null
  expectedLeaf: string | null
  expectedPath: string[]
  expectedAnswerContains: string | null
  expectedAnswerAny: string[]
  expectedError: string | null
  setupQuestion: string | null
  expectedResolvedContains: string[]
}

export interface RetrievalEvalCase {
  id: number
  query: string
  expectedDocIds: number[]
}

export interface EvalDatasets {
  agent: { version: string; size: number; cases: AgentEvalCase[] }
  retrieval: { version: string; size: number; cases: RetrievalEvalCase[] }
}

export interface PromptInfo {
  kind: string
  name: string
  sha256: string
}

export interface EvalCaseResult {
  caseId: number
  question: string
  kind: string
  expected: string | null
  actual: string | null
  passed: boolean
  outcomeMatch: boolean
  pathMatch: boolean
  leafMatch: boolean
  answerMatch: boolean
  steps: number
  probes: number
  tokens: number
  degraded: boolean
  failureKind: string | null
  runId: string | null
  durationMs: number
  detail: Record<string, unknown>
}

export interface EvalRetrievalResult {
  mode: string
  query: string
  expectedIds: number[]
  retrievedIds: number[]
  recall: number
  reciprocalRank: number
}

export interface EvalBatchDetail {
  batch: EvalBatch
  cases: EvalCaseResult[]
  retrievalCases: EvalRetrievalResult[]
}

export interface AgentEvalSummary {
  attribution: { cases: number; passed: number; passRate: number; outcomeAccuracy: number; pathAccuracy: number; leafAccuracy: number }
  lookup: { cases: number; passed: number; passRate: number }
  guard: { cases: number; passed: number; passRate: number }
  session: { cases: number; passed: number; passRate: number }
  overall: { cases: number; passed: number; passRate: number }
  efficiency: {
    avgSteps: number; avgProbes: number; avgTokens: number
    avgAttributionTokens: number; avgLookupTokens: number; totalTokens: number
  }
  degradedRuns: number
  failedCases: number
  failureKinds: Record<string, number>
}

export interface RetrievalEvalSummary {
  cases: number
  topK: number
  modes: { mode: string; cases: number; recallAtK: number; mrr: number }[]
}

export interface EvalGatePolicy {
  maxPassRateDrop: number
  maxOutcomeDrop: number
  maxPathDrop: number
  maxLeafDrop: number
  maxSafetyDrop: number
  maxTokenIncreaseRatio: number
  maxDegradedIncrease: number
  maxRecallDrop: number
  maxMrrDrop: number
}

export interface EvalMetricDelta {
  name: string
  baseline: number
  candidate: number
  delta: number
}

export interface EvalCaseDiff {
  caseId: number
  question: string
  kind: string
  status: string
  expected: string | null
  baselineActual: string | null
  candidateActual: string | null
  failureKind: string | null
  baselineRunId: string | null
  candidateRunId: string | null
}

export interface EvalRetrievalDiff {
  mode: string
  query: string
  status: string
  baselineRecall: number
  candidateRecall: number
  baselineRr: number
  candidateRr: number
}

export interface EvalComparison {
  kind: 'AGENT' | 'RETRIEVAL'
  baseline: EvalBatch
  candidate: EvalBatch
  metrics: EvalMetricDelta[]
  cases: EvalCaseDiff[]
  retrievalCases: EvalRetrievalDiff[]
  gate: { passed: boolean; violations: string[]; policy: EvalGatePolicy }
}

export interface BaselineEvent {
  current: number
  baseline: number
  delta: number
  scope: Record<string, string>
}

export interface ProbeEvent {
  depth: number
  dimension: string
  status: string
  groups: number
  error?: string
}

export interface VerdictPathNode {
  depth: number
  dimension: string
  value: string
  current: number
  baseline: number
  delta: number
  contribution: number
}

export interface VerdictEvent {
  outcome: string
  message: string
  probes: number
  budgetUsed: number
  budgetLimit: number
  dominantIsAggregate: boolean
  proposerDegraded: boolean
  path: VerdictPathNode[]
}

export interface Findings {
  facts: string[]
  hypothesis: string[]
  uncertainty: string[]
}

export interface DoneEvent {
  runId: string
  outcome?: string
  steps?: number
  tokens?: number
  succeeded?: boolean
  sessionId?: string
  resolved?: string
  note?: string
}

export interface ToolCallEvent {
  step: number
  tool: string
  args: string
}

export interface ToolResultEvent {
  step: number
  tool: string
  status: string
  result: string
}

export interface ReflectEvent {
  step: number
  round: number
  message: string
}

export interface SseErrorEvent {
  code?: string
  message: string
}
