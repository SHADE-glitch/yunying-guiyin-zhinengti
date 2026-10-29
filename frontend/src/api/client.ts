import type {
  EvalBatch, EvalBatchDetail, EvalComparison, EvalDatasets, EvalGatePolicy,
  KnowledgePage, PromptInfo, RunDetail, RunStats, RunSummary,
} from '../types'

async function json<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, init)
  if (!res.ok) {
    let detail = ''
    try {
      const body = (await res.json()) as { error?: string; message?: string }
      detail = body.error ?? body.message ?? ''
    } catch {
      detail = ''
    }
    throw new Error(`${res.status} ${res.statusText}${detail ? ` · ${detail}` : ''} · ${url}`)
  }
  return res.json() as Promise<T>
}

function post<T>(url: string, body?: unknown): Promise<T> {
  return json<T>(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body ?? {}),
  })
}

export const api = {
  runs: (limit = 50) => json<RunSummary[]>(`/api/runs?limit=${limit}`),
  runStats: () => json<RunStats>('/api/runs/stats'),
  run: (id: string) => json<RunDetail>(`/api/runs/${id}`),
  knowledge: (params: { type?: string; q?: string; limit?: number } = {}) => {
    const qs = new URLSearchParams()
    if (params.type) qs.set('type', params.type)
    if (params.q) qs.set('q', params.q)
    qs.set('limit', String(params.limit ?? 20))
    return json<KnowledgePage>(`/api/knowledge?${qs}`)
  },
  evalDatasets: () => json<EvalDatasets>('/api/eval/datasets'),
  evalPrompts: () => json<PromptInfo[]>('/api/eval/prompts'),
  evalGate: () => json<EvalGatePolicy>('/api/eval/gate'),
  startAgentEval: (body: Record<string, unknown>) => post<EvalBatch>('/api/eval/agent/runs', body),
  startRetrievalEval: (body: Record<string, unknown>) => post<EvalBatch>('/api/eval/retrieval/runs', body),
  evalBatches: (kind?: string, limit = 30) => {
    const qs = new URLSearchParams()
    if (kind) qs.set('kind', kind)
    qs.set('limit', String(limit))
    return json<EvalBatch[]>(`/api/eval/batches?${qs}`)
  },
  evalBatch: (id: string) => json<EvalBatchDetail>(`/api/eval/batches/${id}`),
  compareEval: (baseline: string, candidate: string) =>
    post<EvalComparison>(`/api/eval/compare?baseline=${baseline}&candidate=${candidate}`),
}
