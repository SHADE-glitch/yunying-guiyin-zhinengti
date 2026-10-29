<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import type {
  AgentEvalSummary, EvalBatch, EvalBatchDetail, EvalCaseResult, EvalComparison,
  EvalDatasets, EvalGatePolicy, EvalRetrievalResult, PromptInfo, RetrievalEvalSummary,
} from '../types'

const datasets = ref<EvalDatasets | null>(null)
const prompts = ref<PromptInfo[]>([])
const gatePolicy = ref<EvalGatePolicy | null>(null)
const batches = ref<EvalBatch[]>([])
const detail = ref<EvalBatchDetail | null>(null)
const comparison = ref<EvalComparison | null>(null)

const loadError = ref('')
const startError = ref('')
const detailError = ref('')
const compareError = ref('')
const starting = ref(false)
const startingRetrieval = ref(false)
const comparing = ref(false)
const loadingDetail = ref(false)
const onlyFailures = ref(false)
const diffFilter = ref('ALL')

const agentForm = ref({
  label: '',
  note: '',
  baselineId: '',
  threshold: '',
  maxDepth: '',
  maxProbes: '',
  maxSteps: '',
  tokenBudget: '',
  proposerMode: 'inherit' as 'inherit' | 'on' | 'off',
  proposerVariant: 'default',
  reporterVariant: 'default',
})

const retrievalForm = ref({
  label: '',
  topK: 5,
  baselineId: '',
})

const cmp = ref({ baselineId: '', candidateId: '' })

let pollTimer: number | undefined

onMounted(async () => {
  try {
    const [d, p, g, b] = await Promise.all([
      api.evalDatasets(),
      api.evalPrompts(),
      api.evalGate(),
      api.evalBatches(undefined, 40),
    ])
    datasets.value = d
    prompts.value = p
    gatePolicy.value = g
    batches.value = b
  } catch (e) {
    loadError.value = String(e)
  }
})

onUnmounted(stopPolling)

const proposerPrompts = computed(() => prompts.value.filter(p => p.kind === 'proposer'))
const reporterPrompts = computed(() => prompts.value.filter(p => p.kind === 'reporter'))
const succeededAgentBatches = computed(() =>
  batches.value.filter(b => b.kind === 'AGENT' && b.status === 'SUCCEEDED'))
const succeededRetrievalBatches = computed(() =>
  batches.value.filter(b => b.kind === 'RETRIEVAL' && b.status === 'SUCCEEDED'))
const succeededBatches = computed(() => batches.value.filter(b => b.status === 'SUCCEEDED'))
const compareCandidates = computed(() => {
  const baseline = batches.value.find(b => b.id === cmp.value.baselineId)
  if (!baseline) return succeededBatches.value
  return succeededBatches.value.filter(b => b.kind === baseline.kind && b.id !== baseline.id)
})

const detailAgentSummary = computed<AgentEvalSummary | null>(() => {
  if (detail.value?.batch.kind !== 'AGENT') return null
  const s = agentSummary(detail.value.batch)
  return s?.overall ? s : null
})
const detailRetrievalGroups = computed(() => {
  if (detail.value?.batch.kind !== 'RETRIEVAL') return []
  return retrievalGroups(detail.value.retrievalCases)
})
const visibleCases = computed(() => {
  const rows = detail.value?.cases ?? []
  return onlyFailures.value ? rows.filter(r => !r.passed) : rows
})
const visibleDiffs = computed(() => {
  const rows = comparison.value?.cases ?? []
  return diffFilter.value === 'ALL' ? rows : rows.filter(r => r.status === diffFilter.value)
})

async function refreshBatches() {
  batches.value = await api.evalBatches(undefined, 40).catch(() => batches.value)
}

function agentSummary(batch: EvalBatch | null | undefined): AgentEvalSummary | null {
  if (!batch || batch.kind !== 'AGENT' || !batch.summary) return null
  const summary = batch.summary as unknown as AgentEvalSummary
  return summary.overall ? summary : null
}

function retrievalSummary(batch: EvalBatch | null | undefined): RetrievalEvalSummary | null {
  if (!batch || batch.kind !== 'RETRIEVAL' || !batch.summary) return null
  const summary = batch.summary as unknown as RetrievalEvalSummary
  return summary.modes?.length ? summary : null
}

function retrievalGroups(results: EvalRetrievalResult[]) {
  const byMode = new Map<string, EvalRetrievalResult[]>()
  for (const result of results) {
    const list = byMode.get(result.mode) ?? []
    list.push(result)
    byMode.set(result.mode, list)
  }
  return [...byMode.entries()].map(([mode, items]) => ({
    mode,
    cases: items.length,
    recallAtK: round(items.reduce((sum, r) => sum + r.recall, 0) / Math.max(1, items.length)),
    mrr: round(items.reduce((sum, r) => sum + r.reciprocalRank, 0) / Math.max(1, items.length)),
    details: items,
  }))
}

async function startAgentBatch() {
  try {
    await ElMessageBox.confirm(
      `This batch really calls the model over ${datasets.value?.agent.size ?? 40} cases and usually takes 5–10 minutes. ` +
        'Metadata (model, as-of, config, prompt hashes, dataset version) is frozen in the batch; the run continues even if you leave this page. Continue?',
      'Start agent evaluation batch',
      { type: 'warning', confirmButtonText: 'Start', cancelButtonText: 'Cancel' },
    )
  } catch {
    return
  }
  starting.value = true
  startError.value = ''
  try {
    const body: Record<string, unknown> = {
      proposerVariant: agentForm.value.proposerVariant,
      reporterVariant: agentForm.value.reporterVariant,
    }
    if (agentForm.value.label.trim()) body.label = agentForm.value.label.trim()
    if (agentForm.value.note.trim()) body.note = agentForm.value.note.trim()
    if (agentForm.value.baselineId) body.baselineId = agentForm.value.baselineId
    putNumber(body, 'threshold', agentForm.value.threshold)
    putNumber(body, 'maxDepth', agentForm.value.maxDepth)
    putNumber(body, 'maxProbes', agentForm.value.maxProbes)
    putNumber(body, 'maxSteps', agentForm.value.maxSteps)
    putNumber(body, 'lookupTokenBudget', agentForm.value.tokenBudget)
    if (agentForm.value.proposerMode === 'on') body.modelProposerEnabled = true
    if (agentForm.value.proposerMode === 'off') body.modelProposerEnabled = false

    const batch = await api.startAgentEval(body)
    ElMessage.success(`Batch ${shortId(batch.id)} started`)
    await refreshBatches()
    await openBatch(batch.id)
  } catch (e) {
    startError.value = String(e)
  } finally {
    starting.value = false
  }
}

async function startRetrievalBatch() {
  startingRetrieval.value = true
  startError.value = ''
  try {
    const body: Record<string, unknown> = { topK: retrievalForm.value.topK }
    if (retrievalForm.value.label.trim()) body.label = retrievalForm.value.label.trim()
    if (retrievalForm.value.baselineId) body.baselineId = retrievalForm.value.baselineId
    const batch = await api.startRetrievalEval(body)
    ElMessage.success(`Retrieval batch ${shortId(batch.id)} started`)
    await refreshBatches()
    await openBatch(batch.id)
  } catch (e) {
    startError.value = String(e)
  } finally {
    startingRetrieval.value = false
  }
}

async function openBatch(id: string) {
  loadingDetail.value = true
  detailError.value = ''
  try {
    const result = await api.evalBatch(id)
    detail.value = result
    if (result.batch.status === 'RUNNING') {
      schedulePoll(id)
    } else {
      stopPolling()
      await refreshBatches()
    }
  } catch (e) {
    detailError.value = String(e)
  } finally {
    loadingDetail.value = false
  }
}

function schedulePoll(id: string) {
  stopPolling()
  pollTimer = window.setTimeout(async () => {
    try {
      const result = await api.evalBatch(id)
      if (detail.value?.batch.id === id) detail.value = result
      await refreshBatches()
      if (result.batch.status === 'RUNNING') {
        schedulePoll(id)
      } else if (comparison.value) {
        comparison.value = null
      }
    } catch {
      // 批次还在跑，轮询抖动不打扰用户
    }
  }, 1500)
}

function stopPolling() {
  if (pollTimer !== undefined) {
    window.clearTimeout(pollTimer)
    pollTimer = undefined
  }
}

async function compare() {
  if (!cmp.value.baselineId || !cmp.value.candidateId) return
  comparing.value = true
  compareError.value = ''
  try {
    comparison.value = await api.compareEval(cmp.value.baselineId, cmp.value.candidateId)
    diffFilter.value = 'ALL'
  } catch (e) {
    compareError.value = String(e)
    comparison.value = null
  } finally {
    comparing.value = false
  }
}

function useBaselineOfDetail() {
  const baselineId = detail.value?.batch.baselineId
  if (!baselineId || !detail.value) return
  cmp.value = { baselineId, candidateId: detail.value.batch.id }
  compare()
}

function onCompareBaselineChange() {
  const chosen = batches.value.find(b => b.id === cmp.value.baselineId)
  const candidate = batches.value.find(b => b.id === cmp.value.candidateId)
  if (chosen && candidate && chosen.kind !== candidate.kind) {
    cmp.value.candidateId = ''
  }
}

function putNumber(body: Record<string, unknown>, key: string, value: string) {
  const text = value.trim()
  if (text !== '') body[key] = Number(text)
}

function batchQuality(batch: EvalBatch): string {
  if (batch.kind === 'AGENT') {
    const summary = agentSummary(batch)
    if (!summary) return '—'
    return `${summary.overall.passed}/${summary.overall.cases} passed · ${pct(summary.overall.passRate)}`
  }
  const summary = retrievalSummary(batch)
  if (!summary) return '—'
  const hybrid = summary.modes.find(m => m.mode === 'HYBRID') ?? summary.modes[0]
  return `HYBRID recall ${pct(hybrid.recallAtK)} · MRR ${hybrid.mrr.toFixed(3)}`
}

function configLine(batch: EvalBatch): string {
  const c = batch.config ?? {}
  if (batch.kind === 'RETRIEVAL') {
    const modes = Array.isArray(c.modes) ? (c.modes as string[]).join('/') : '—'
    return `topK=${c.topK ?? '—'} · modes=${modes}`
  }
  return `threshold=${c.threshold ?? '—'} · depth=${c.maxDepth ?? '—'} · probes=${c.maxProbes ?? '—'}`
    + ` / ${c.queryBudget ?? '—'} queries · tokens=${c.tokenBudget ?? '—'}`
    + ` · modelProposer=${c.modelProposerEnabled ?? '—'}`
}

function promptLine(batch: EvalBatch): string {
  const hashes = batch.promptHashes ?? {}
  if (!hashes.proposerSha) return 'no prompt hash'
  return `proposer ${hashes.proposerVariant}@${shortSha(hashes.proposerSha)} · `
    + `reporter ${hashes.reporterVariant}@${shortSha(hashes.reporterSha)}`
}

function optionLabel(batch: EvalBatch): string {
  const label = batch.label || shortId(batch.id)
  return `${label} · ${batch.kind === 'AGENT' ? batchQuality(batch) : pct(hybridRecall(batch))}`
}

function hybridRecall(batch: EvalBatch): number {
  const summary = retrievalSummary(batch)
  if (!summary) return 0
  return (summary.modes.find(m => m.mode === 'HYBRID') ?? summary.modes[0]).recallAtK
}

function checks(row: EvalCaseResult): string {
  const mark = (ok: boolean) => (ok ? '✓' : '✗')
  if (row.kind === 'ATTRIBUTION') {
    return `${mark(row.outcomeMatch)}${mark(row.pathMatch)}${mark(row.leafMatch)}`
  }
  if (row.kind === 'LOOKUP') return mark(row.answerMatch)
  return mark(row.passed)
}

function checkTitle(row: EvalCaseResult): string {
  if (row.kind === 'ATTRIBUTION') return 'outcome / path / leaf'
  if (row.kind === 'LOOKUP') return 'answer keyword'
  return 'expected error code'
}

function kindTag(kind: string): 'primary' | 'success' | 'warning' | 'info' | 'danger' {
  if (kind === 'ATTRIBUTION') return 'primary'
  if (kind === 'LOOKUP') return 'success'
  if (kind === 'GUARD') return 'warning'
  return 'info'
}

function statusTag(status: string): 'success' | 'warning' | 'danger' {
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'RUNNING') return 'warning'
  return 'danger'
}

function diffTag(status: string): 'success' | 'danger' | 'warning' | 'info' {
  if (status === 'IMPROVED') return 'success'
  if (status === 'REGRESSED') return 'danger'
  if (status === 'SAME_PASS') return 'info'
  return 'warning'
}

function deltaClass(name: string, delta: number): string {
  if (Math.abs(delta) < 1e-9) return 'delta-flat'
  const cost = name.includes('Tokens') || name.includes('Probes') || name.includes('degraded')
  const good = cost ? delta < 0 : delta > 0
  return good ? 'delta-good' : 'delta-bad'
}

function pct(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—'
  return `${(value * 100).toFixed(1)}%`
}

function shortId(id: string): string {
  return id.slice(0, 8)
}

function shortSha(sha: string | null | undefined): string {
  return sha ? sha.slice(0, 10) : '—'
}

function fmtTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString('zh-CN', { hour12: false })
}

function pretty(value: unknown): string {
  return JSON.stringify(value, null, 2)
}

function round(value: number): number {
  return Math.round(value * 10000) / 10000
}
</script>

<template>
  <div>
    <div class="panel">
      <div style="display: flex; align-items: center; justify-content: space-between; gap: 12px">
        <div>
          <b>Evaluation lab</b>
          <span class="muted" style="margin-left: 10px">
            Every batch freezes git/model/as-of/config/prompt hashes/dataset version, then compares against a baseline through a regression gate
          </span>
        </div>
        <el-button size="small" @click="refreshBatches">Refresh</el-button>
      </div>
      <div v-if="datasets" class="num muted" style="display: flex; gap: 18px; flex-wrap: wrap; font-size: 12px; margin-top: 8px">
        <span>Agent dataset {{ datasets.agent.size }} cases · v{{ shortSha(datasets.agent.version) }}</span>
        <span>Retrieval dataset {{ datasets.retrieval.size }} queries · v{{ shortSha(datasets.retrieval.version) }}</span>
        <span v-if="gatePolicy">
          Gate: pass drop ≤{{ pct(gatePolicy.maxPassRateDrop) }} · safety drop ≤{{ pct(gatePolicy.maxSafetyDrop) }} ·
          tokens +{{ pct(gatePolicy.maxTokenIncreaseRatio) }} · recall drop ≤{{ pct(gatePolicy.maxRecallDrop) }}
        </span>
      </div>
    </div>

    <el-alert v-if="loadError" type="error" :closable="false" :title="loadError" style="margin-top: 12px" />
    <el-alert v-if="startError" type="error" :closable="false" :title="startError" style="margin-top: 12px" />

    <div class="panel" style="margin-top: 12px">
      <h3 class="panel-title">Agent experiment · attribution outcomes, lookup answers, guard and session behavior</h3>
      <el-row :gutter="16">
        <el-col :xs="24" :md="12">
          <el-form label-width="132px" size="small">
            <el-form-item label="Label">
              <el-input v-model="agentForm.label" placeholder="e.g. threshold-0.55" />
            </el-form-item>
            <el-form-item label="Note">
              <el-input v-model="agentForm.note" placeholder="why this run exists" />
            </el-form-item>
            <el-form-item label="Baseline">
              <el-select v-model="agentForm.baselineId" clearable placeholder="auto: latest succeeded on the same dataset">
                <el-option v-for="b in succeededAgentBatches" :key="b.id" :value="b.id" :label="optionLabel(b)" />
              </el-select>
            </el-form-item>
            <el-form-item label="threshold">
              <el-select v-model="agentForm.threshold" clearable placeholder="inherit 0.6">
                <el-option value="0.5" label="0.50" />
                <el-option value="0.55" label="0.55" />
                <el-option value="0.6" label="0.60" />
                <el-option value="0.65" label="0.65" />
                <el-option value="0.7" label="0.70" />
              </el-select>
            </el-form-item>
            <el-form-item label="maxDepth">
              <el-select v-model="agentForm.maxDepth" clearable placeholder="inherit 2">
                <el-option value="1" label="1 layer" />
                <el-option value="2" label="2 layers" />
                <el-option value="3" label="3 layers" />
              </el-select>
            </el-form-item>
            <el-form-item label="maxProbes">
              <el-select v-model="agentForm.maxProbes" clearable placeholder="inherit 8">
                <el-option value="4" label="4" />
                <el-option value="6" label="6" />
                <el-option value="8" label="8" />
                <el-option value="10" label="10" />
              </el-select>
            </el-form-item>
          </el-form>
        </el-col>
        <el-col :xs="24" :md="12">
          <el-form label-width="132px" size="small">
            <el-form-item label="lookup maxSteps">
              <el-select v-model="agentForm.maxSteps" clearable placeholder="inherit 6">
                <el-option value="4" label="4" />
                <el-option value="6" label="6" />
                <el-option value="8" label="8" />
              </el-select>
            </el-form-item>
            <el-form-item label="lookup tokens">
              <el-select v-model="agentForm.tokenBudget" clearable placeholder="inherit 30000">
                <el-option value="15000" label="15k" />
                <el-option value="30000" label="30k" />
                <el-option value="60000" label="60k" />
              </el-select>
            </el-form-item>
            <el-form-item label="model proposer">
              <el-select v-model="agentForm.proposerMode">
                <el-option value="inherit" label="inherit (on)" />
                <el-option value="on" label="on" />
                <el-option value="off" label="off · rule-based ablation" />
              </el-select>
            </el-form-item>
            <el-form-item label="proposer prompt">
              <el-select v-model="agentForm.proposerVariant">
                <el-option v-for="p in proposerPrompts" :key="p.name" :value="p.name"
                           :label="`${p.name} · ${shortSha(p.sha256)}`" />
              </el-select>
            </el-form-item>
            <el-form-item label="reporter prompt">
              <el-select v-model="agentForm.reporterVariant">
                <el-option v-for="p in reporterPrompts" :key="p.name" :value="p.name"
                           :label="`${p.name} · ${shortSha(p.sha256)}`" />
              </el-select>
            </el-form-item>
            <el-form-item label=" ">
              <el-button type="primary" :loading="starting" @click="startAgentBatch">
                Start agent batch
              </el-button>
            </el-form-item>
          </el-form>
        </el-col>
      </el-row>
    </div>

    <div class="panel" style="margin-top: 12px">
      <h3 class="panel-title">Retrieval regression · VECTOR / KEYWORD / HYBRID on the annotated set</h3>
      <div style="display: flex; gap: 12px; align-items: flex-end; flex-wrap: wrap">
        <el-form label-width="90px" size="small" style="display: flex; gap: 8px; align-items: flex-end">
          <el-form-item label="Label" style="margin-bottom: 0">
            <el-input v-model="retrievalForm.label" placeholder="e.g. topK-5" style="width: 180px" />
          </el-form-item>
          <el-form-item label="topK" style="margin-bottom: 0">
            <el-select v-model="retrievalForm.topK" style="width: 100px">
              <el-option :value="3" label="3" />
              <el-option :value="5" label="5" />
              <el-option :value="10" label="10" />
            </el-select>
          </el-form-item>
          <el-form-item label="Baseline" style="margin-bottom: 0">
            <el-select v-model="retrievalForm.baselineId" clearable placeholder="auto: latest succeeded"
                       style="width: 260px">
              <el-option v-for="b in succeededRetrievalBatches" :key="b.id" :value="b.id" :label="optionLabel(b)" />
            </el-select>
          </el-form-item>
        </el-form>
        <el-button type="primary" plain :loading="startingRetrieval" @click="startRetrievalBatch">
          Start retrieval batch
        </el-button>
      </div>
    </div>

    <div class="panel" style="margin-top: 12px">
      <h3 class="panel-title">Batch history · click a row to inspect cases and frozen metadata</h3>
      <el-table :data="batches" size="small" style="margin-top: 8px; cursor: pointer"
                @row-click="(row: EvalBatch) => openBatch(row.id)">
        <el-table-column label="Started" width="160">
          <template #default="{ row }"><span class="num muted">{{ fmtTime(row.startedAt) }}</span></template>
        </el-table-column>
        <el-table-column label="Kind" width="100">
          <template #default="{ row }"><el-tag size="small" :type="row.kind === 'AGENT' ? 'primary' : 'info'">{{ row.kind }}</el-tag></template>
        </el-table-column>
        <el-table-column label="Label" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ row.label || '—' }}</span>
            <span v-if="row.id === detail?.batch.id" class="muted" style="margin-left: 6px">(open)</span>
          </template>
        </el-table-column>
        <el-table-column label="Status" width="150">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(row.status)">{{ row.status }}</el-tag>
            <span v-if="row.status === 'RUNNING'" class="num muted" style="margin-left: 6px">
              {{ row.progressDone }}/{{ row.progressTotal }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="Quality" min-width="190">
          <template #default="{ row }"><span class="num">{{ batchQuality(row) }}</span></template>
        </el-table-column>
        <el-table-column label="Dataset" width="120">
          <template #default="{ row }"><span class="mono muted">{{ shortSha(row.datasetVersion) }}</span></template>
        </el-table-column>
        <el-table-column label="Model" width="150">
          <template #default="{ row }"><span class="mono muted">{{ row.model }}</span></template>
        </el-table-column>
      </el-table>
    </div>

    <el-alert v-if="detailError" type="error" :closable="false" :title="detailError" style="margin-top: 12px" />

    <div v-if="detail" v-loading="loadingDetail" class="panel" style="margin-top: 12px">
      <div style="display: flex; align-items: center; justify-content: space-between; gap: 12px">
        <div>
          <b>Batch {{ shortId(detail.batch.id) }}</b>
          <span class="muted" style="margin-left: 8px">{{ detail.batch.label }}</span>
          <el-tag size="small" :type="statusTag(detail.batch.status)" style="margin-left: 8px">
            {{ detail.batch.status }}
          </el-tag>
        </div>
        <div style="display: flex; gap: 8px">
          <el-button v-if="detail.batch.baselineId && detail.batch.status === 'SUCCEEDED'"
                     size="small" @click="useBaselineOfDetail">
            Compare with baseline
          </el-button>
          <el-button size="small" @click="openBatch(detail.batch.id)">Refresh</el-button>
        </div>
      </div>

      <div class="mono muted" style="font-size: 12px; line-height: 1.9; margin-top: 8px">
        git {{ shortSha(detail.batch.gitSha) }} · model {{ detail.batch.model }} · embedding {{ detail.batch.embeddingModel }}<br />
        as-of {{ fmtTime(detail.batch.asOf) }} · dataset {{ shortSha(detail.batch.datasetVersion) }}
        ({{ detail.batch.datasetSize }} cases) · {{ promptLine(detail.batch) }}<br />
        config {{ configLine(detail.batch) }}
      </div>

      <el-progress v-if="detail.batch.status === 'RUNNING'"
                   :percentage="detail.batch.progressTotal ? Math.round(detail.batch.progressDone / detail.batch.progressTotal * 100) : 0"
                   :stroke-width="10" style="margin-top: 12px" />
      <el-alert v-if="detail.batch.error" type="error" :closable="false" :title="detail.batch.error"
                style="margin-top: 12px" />

      <template v-if="detail.batch.kind === 'AGENT' && detailAgentSummary">
        <div style="display: flex; gap: 12px; flex-wrap: wrap; margin-top: 12px">
          <div class="stat-box">
            <div class="muted">Overall</div>
            <div class="num stat-value">{{ pct(detailAgentSummary.overall.passRate) }}</div>
            <div class="muted num">{{ detailAgentSummary.overall.passed }}/{{ detailAgentSummary.overall.cases }} passed</div>
          </div>
          <div class="stat-box">
            <div class="muted">Attribution</div>
            <div class="num stat-value">{{ pct(detailAgentSummary.attribution.passRate) }}</div>
            <div class="muted num">
              outcome {{ pct(detailAgentSummary.attribution.outcomeAccuracy) }} ·
              path {{ pct(detailAgentSummary.attribution.pathAccuracy) }} ·
              leaf {{ pct(detailAgentSummary.attribution.leafAccuracy) }}
            </div>
          </div>
          <div class="stat-box">
            <div class="muted">Lookup / Guard / Session</div>
            <div class="num stat-value">{{ pct(detailAgentSummary.lookup.passRate) }}</div>
            <div class="muted num">
              guard {{ pct(detailAgentSummary.guard.passRate) }} ·
              session {{ pct(detailAgentSummary.session.passRate) }}
            </div>
          </div>
          <div class="stat-box">
            <div class="muted">Cost</div>
            <div class="num stat-value">{{ Math.round(detailAgentSummary.efficiency.avgTokens) }}</div>
            <div class="muted num">
              avg tokens · steps {{ detailAgentSummary.efficiency.avgSteps.toFixed(1) }} ·
              probes {{ detailAgentSummary.efficiency.avgProbes.toFixed(1) }} ·
              degraded {{ detailAgentSummary.degradedRuns }}
            </div>
          </div>
        </div>

        <div v-if="Object.keys(detailAgentSummary.failureKinds).length" class="muted num" style="margin-top: 8px; font-size: 12px">
          Failure kinds:
          <el-tag v-for="(count, kind) in detailAgentSummary.failureKinds" :key="kind" size="small" type="warning"
                  style="margin-right: 6px">
            {{ kind }} × {{ count }}
          </el-tag>
        </div>

        <div style="display: flex; align-items: center; justify-content: space-between; margin-top: 12px">
          <h3 class="panel-title" style="margin: 0">Cases · expected vs actual</h3>
          <el-checkbox v-model="onlyFailures" size="small">Failures only</el-checkbox>
        </div>
        <el-table :data="visibleCases" size="small" style="margin-top: 8px" max-height="460">
          <el-table-column prop="caseId" label="#" width="50" align="right" />
          <el-table-column prop="question" label="Question" min-width="220" show-overflow-tooltip />
          <el-table-column label="Kind" width="110">
            <template #default="{ row }"><el-tag size="small" :type="kindTag(row.kind)">{{ row.kind }}</el-tag></template>
          </el-table-column>
          <el-table-column label="Expected" min-width="150" show-overflow-tooltip>
            <template #default="{ row }"><span class="mono muted">{{ row.expected ?? '—' }}</span></template>
          </el-table-column>
          <el-table-column label="Actual" min-width="150" show-overflow-tooltip>
            <template #default="{ row }"><span class="mono">{{ row.actual ?? '—' }}</span></template>
          </el-table-column>
          <el-table-column label="Checks" width="90" align="center">
            <template #default="{ row }">
              <el-tooltip :content="checkTitle(row)" placement="top">
                <span class="mono">{{ checks(row) }}</span>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column label="Failure" width="120">
            <template #default="{ row }">
              <el-tag v-if="row.failureKind" size="small" type="danger">{{ row.failureKind }}</el-tag>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="Steps/Probes" width="110" align="right">
            <template #default="{ row }"><span class="num muted">{{ row.steps }}/{{ row.probes }}</span></template>
          </el-table-column>
          <el-table-column label="Tokens" width="90" align="right">
            <template #default="{ row }"><span class="num muted">{{ row.tokens || '—' }}</span></template>
          </el-table-column>
          <el-table-column label="Trace" width="70">
            <template #default="{ row }">
              <router-link v-if="row.runId" :to="`/runs/${row.runId}`" class="mono">run</router-link>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column type="expand">
            <template #default="{ row }">
              <pre class="mono" style="font-size: 12px; white-space: pre-wrap; margin: 0">{{ pretty(row.detail) }}</pre>
            </template>
          </el-table-column>
        </el-table>
      </template>

      <template v-if="detail.batch.kind === 'RETRIEVAL'">
        <el-table :data="detailRetrievalGroups" size="small" style="margin-top: 12px">
          <el-table-column prop="mode" label="Mode" width="120" />
          <el-table-column prop="cases" label="Queries" width="90" align="right" />
          <el-table-column label="recall@K" width="120" align="right">
            <template #default="{ row }"><span class="num">{{ pct(row.recallAtK) }}</span></template>
          </el-table-column>
          <el-table-column label="MRR" width="120" align="right">
            <template #default="{ row }"><span class="num">{{ row.mrr.toFixed(4) }}</span></template>
          </el-table-column>
          <el-table-column type="expand">
            <template #default="{ row }">
              <el-table :data="row.details" size="small">
                <el-table-column prop="query" label="query" min-width="220" />
                <el-table-column label="expected" min-width="120">
                  <template #default="{ row: d }"><span class="num">{{ d.expectedIds.join(', ') }}</span></template>
                </el-table-column>
                <el-table-column label="retrieved" min-width="180">
                  <template #default="{ row: d }"><span class="num">{{ d.retrievedIds.join(', ') }}</span></template>
                </el-table-column>
                <el-table-column label="recall" width="90" align="right">
                  <template #default="{ row: d }"><span class="num">{{ d.recall.toFixed(2) }}</span></template>
                </el-table-column>
                <el-table-column label="RR" width="80" align="right">
                  <template #default="{ row: d }"><span class="num">{{ d.reciprocalRank.toFixed(2) }}</span></template>
                </el-table-column>
              </el-table>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </div>

    <div class="panel" style="margin-top: 12px">
      <h3 class="panel-title">Compare & regression gate · quality, safety and cost</h3>
      <div style="display: flex; gap: 12px; align-items: flex-end; flex-wrap: wrap">
        <el-form label-width="80px" size="small" style="display: flex; gap: 8px; align-items: flex-end">
          <el-form-item label="Baseline" style="margin-bottom: 0">
            <el-select v-model="cmp.baselineId" clearable placeholder="succeeded batch" style="width: 300px"
                       @change="onCompareBaselineChange">
              <el-option v-for="b in succeededBatches" :key="b.id" :value="b.id" :label="optionLabel(b)" />
            </el-select>
          </el-form-item>
          <el-form-item label="Candidate" style="margin-bottom: 0">
            <el-select v-model="cmp.candidateId" clearable placeholder="same kind as baseline" style="width: 300px">
              <el-option v-for="b in compareCandidates" :key="b.id" :value="b.id" :label="optionLabel(b)" />
            </el-select>
          </el-form-item>
        </el-form>
        <el-button type="primary" :loading="comparing" :disabled="!cmp.baselineId || !cmp.candidateId"
                   @click="compare">
          Compare
        </el-button>
      </div>

      <el-alert v-if="compareError" type="error" :closable="false" :title="compareError" style="margin-top: 12px" />

      <template v-if="comparison">
        <el-alert :type="comparison.gate.passed ? 'success' : 'error'" :closable="false" style="margin-top: 12px"
                  :title="comparison.gate.passed
                    ? 'Gate passed · no quality, safety or cost regression beyond the allowed margins'
                    : `Gate failed · ${comparison.gate.violations.length} violation(s)`">
          <div v-if="comparison.gate.violations.length" class="mono" style="font-size: 12px; line-height: 1.8">
            <div v-for="violation in comparison.gate.violations" :key="violation">{{ violation }}</div>
          </div>
        </el-alert>

        <div class="muted num" style="font-size: 12px; margin-top: 8px">
          baseline {{ shortId(comparison.baseline.id) }} → candidate {{ shortId(comparison.candidate.id) }} ·
          dataset {{ shortSha(comparison.baseline.datasetVersion) }} vs {{ shortSha(comparison.candidate.datasetVersion) }}
        </div>

        <el-table :data="comparison.metrics" size="small" style="margin-top: 8px">
          <el-table-column prop="name" label="Metric" min-width="220" />
          <el-table-column label="Baseline" width="140" align="right">
            <template #default="{ row }"><span class="num">{{ row.baseline }}</span></template>
          </el-table-column>
          <el-table-column label="Candidate" width="140" align="right">
            <template #default="{ row }"><span class="num">{{ row.candidate }}</span></template>
          </el-table-column>
          <el-table-column label="Delta" width="140" align="right">
            <template #default="{ row }">
              <span class="num" :class="deltaClass(row.name, row.delta)">{{ row.delta > 0 ? '+' : '' }}{{ row.delta }}</span>
            </template>
          </el-table-column>
        </el-table>

        <template v-if="comparison.kind === 'AGENT'">
          <div style="display: flex; align-items: center; justify-content: space-between; margin-top: 12px">
            <h3 class="panel-title" style="margin: 0">Case diffs</h3>
            <el-select v-model="diffFilter" size="small" style="width: 180px">
              <el-option value="ALL" label="All statuses" />
              <el-option value="REGRESSED" label="Regressed only" />
              <el-option value="IMPROVED" label="Improved only" />
              <el-option value="SAME_FAIL" label="Still failing" />
              <el-option value="ONLY_BASELINE" label="Only baseline" />
              <el-option value="ONLY_CANDIDATE" label="Only candidate" />
            </el-select>
          </div>
          <el-table :data="visibleDiffs" size="small" style="margin-top: 8px" max-height="420">
            <el-table-column prop="caseId" label="#" width="50" align="right" />
            <el-table-column prop="question" label="Question" min-width="220" show-overflow-tooltip />
            <el-table-column label="Kind" width="110">
              <template #default="{ row }"><el-tag size="small" :type="kindTag(row.kind)">{{ row.kind }}</el-tag></template>
            </el-table-column>
            <el-table-column label="Status" width="140">
              <template #default="{ row }"><el-tag size="small" :type="diffTag(row.status)">{{ row.status }}</el-tag></template>
            </el-table-column>
            <el-table-column label="Expected" min-width="130" show-overflow-tooltip>
              <template #default="{ row }"><span class="mono muted">{{ row.expected ?? '—' }}</span></template>
            </el-table-column>
            <el-table-column label="Baseline" min-width="130" show-overflow-tooltip>
              <template #default="{ row }"><span class="mono muted">{{ row.baselineActual ?? '—' }}</span></template>
            </el-table-column>
            <el-table-column label="Candidate" min-width="130" show-overflow-tooltip>
              <template #default="{ row }"><span class="mono">{{ row.candidateActual ?? '—' }}</span></template>
            </el-table-column>
            <el-table-column label="Failure" width="110">
              <template #default="{ row }">
                <span class="mono muted">{{ row.failureKind ?? '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="Trace" width="120">
              <template #default="{ row }">
                <router-link v-if="row.candidateRunId" :to="`/runs/${row.candidateRunId}`" class="mono">candidate</router-link>
                <span v-else-if="row.baselineRunId" class="muted mono">baseline only</span>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
          </el-table>
        </template>

        <template v-else>
          <h3 class="panel-title" style="margin-top: 12px">Retrieval diffs</h3>
          <el-table :data="comparison.retrievalCases" size="small" style="margin-top: 8px" max-height="420">
            <el-table-column prop="mode" label="Mode" width="110" />
            <el-table-column prop="query" label="Query" min-width="240" show-overflow-tooltip />
            <el-table-column label="Status" width="140">
              <template #default="{ row }"><el-tag size="small" :type="diffTag(row.status)">{{ row.status }}</el-tag></template>
            </el-table-column>
            <el-table-column label="Recall" width="150" align="right">
              <template #default="{ row }">
                <span class="num">{{ row.baselineRecall.toFixed(2) }} → {{ row.candidateRecall.toFixed(2) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="RR" width="130" align="right">
              <template #default="{ row }">
                <span class="num">{{ row.baselineRr.toFixed(2) }} → {{ row.candidateRr.toFixed(2) }}</span>
              </template>
            </el-table-column>
          </el-table>
        </template>
      </template>
    </div>
  </div>
</template>

<style scoped>
.stat-box {
  min-width: 180px;
  flex: 1 1 180px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 10px 12px;
}

.stat-value {
  font-size: 20px;
  margin: 2px 0;
}

.delta-good {
  color: var(--el-color-success);
}

.delta-bad {
  color: var(--el-color-danger);
}

.delta-flat {
  color: var(--el-text-color-secondary);
}

a.mono {
  color: var(--el-color-primary);
  text-decoration: none;
}
</style>
