<script setup lang="ts">
import { onUnmounted, ref } from 'vue'
import { useWorkbenchStore } from '../store/workbench'
import { streamEvents } from '../api/sse'
import type { TimelineItem } from '../components/EventTimeline.vue'
import EventTimeline from '../components/EventTimeline.vue'
import AttributionPath from '../components/AttributionPath.vue'
import FindingsCard from '../components/FindingsCard.vue'
import type { BaselineEvent, DoneEvent, Findings, SseErrorEvent, VerdictEvent } from '../types'

const store = useWorkbenchStore()

const items = ref<TimelineItem[]>([])
const running = ref(false)
const baseline = ref<BaselineEvent | null>(null)
const verdict = ref<VerdictEvent | null>(null)
const findings = ref<Findings | null>(null)
const done = ref<DoneEvent | null>(null)
const failure = ref<SseErrorEvent | null>(null)

let stream: { close: () => void } | null = null

const OUTCOME_TAG: Record<string, string> = {
  DOMINANT_FOUND: 'success',
  MULTI_FACTOR: 'success',
  NO_SIGNIFICANT_CHANGE: 'info',
  INSUFFICIENT_EVIDENCE: 'warning',
  DATA_INCOMPLETE: 'danger',
  INVALID_PROPOSAL_LIMIT: 'danger',
  BUDGET_EXHAUSTED: 'danger',
  FAILED: 'danger',
}

function send() {
  const question = store.question.trim()
  if (!question || running.value) return

  items.value = []
  baseline.value = null
  verdict.value = null
  findings.value = null
  done.value = null
  failure.value = null
  running.value = true

  const path = store.mode === 'ATTRIBUTION' ? 'attribute' : 'ask'
  const session = store.mode === 'ATTRIBUTION' ? `&sessionId=${encodeURIComponent(store.sessionId)}` : ''
  stream = streamEvents(`/api/${path}?q=${encodeURIComponent(question)}${session}`, (type, data) => {
    // verdict / done 右侧有专门卡片；归因的 answer 是三段式结论卡，
    // 查数的 answer 是自由文本，仍走时间线渲染
    const cardOnly = type === 'verdict' || type === 'done'
      || (type === 'answer' && store.mode === 'ATTRIBUTION')
    if (!cardOnly) items.value.push({ type, data })
    if (type === 'baseline') baseline.value = data as BaselineEvent
    if (type === 'verdict') verdict.value = data as VerdictEvent
    if (type === 'answer' && store.mode === 'ATTRIBUTION') findings.value = data as Findings
    if (type === 'done') {
      done.value = data as DoneEvent
      running.value = false
    }
    if (type === 'error') {
      failure.value = data as SseErrorEvent
      running.value = false
    }
  })
}

function stop() {
  stream?.close()
  running.value = false
}

onUnmounted(() => stream?.close())
</script>

<template>
  <div>
    <div class="panel" style="display: flex; gap: 10px; align-items: center; flex-wrap: wrap">
      <el-segmented
        :model-value="store.mode"
        :options="[
          { label: 'Attribution drill-down', value: 'ATTRIBUTION' },
          { label: 'Data lookup / metric explanation', value: 'LOOKUP' },
        ]"
        @update:model-value="store.setMode($event as 'ATTRIBUTION' | 'LOOKUP')"
      />
      <el-input
        :model-value="store.question"
        style="flex: 1; min-width: 260px"
        placeholder="Attribution: why did East China GMV drop? / Lookup: how is refund rate calculated?"
        @update:model-value="store.setQuestion($event)"
        @keyup.enter="send"
      />
      <el-button type="primary" :loading="running" @click="send">Send</el-button>
      <el-button v-if="running" @click="stop">Stop</el-button>
      <el-tooltip content="Start a fresh session: follow-up completion will not inherit context" placement="bottom">
        <el-button @click="store.newSession()">New session</el-button>
      </el-tooltip>
    </div>
    <div class="muted" style="font-size: 12px; margin: 8px 2px">
      The two chains have different contracts (free text vs structured path), so routing is a product decision and stays on the frontend; each send is a brand-new stateless request, the backend does not preserve session context.
    </div>

    <el-row :gutter="12" style="margin-top: 4px">
      <el-col :span="13">
        <EventTimeline :items="items" :mode="store.mode" />
      </el-col>
      <el-col :span="11">
        <el-alert
          v-if="failure"
          type="error"
          :closable="false"
          :title="failure.code ?? 'FAILED'"
          :description="failure.message"
          style="margin-bottom: 12px"
        />

        <div v-if="baseline" class="panel">
          <h3 class="panel-title">Root metric</h3>
          <div style="display: flex; gap: 6px; flex-wrap: wrap; margin-bottom: 6px">
            <el-tag v-for="(v, k) in baseline.scope" :key="k" size="small">{{ k }} = {{ v }}</el-tag>
          </div>
          <div class="num">
            Current {{ Number(baseline.current).toLocaleString() }} ·
            Baseline {{ Number(baseline.baseline).toLocaleString() }} ·
            Δ {{ Number(baseline.delta).toLocaleString() }}
          </div>
        </div>

        <div v-if="verdict" class="panel">
          <h3 class="panel-title">Verdict</h3>
          <div style="display: flex; align-items: center; gap: 8px; flex-wrap: wrap">
            <el-tag :type="(OUTCOME_TAG[verdict.outcome] as any) ?? 'info'">{{ verdict.outcome }}</el-tag>
            <el-tag v-if="verdict.proposerDegraded" type="warning" size="small">Proposer degraded to rule version</el-tag>
            <el-tag v-if="verdict.dominantIsAggregate" type="info" size="small">Main cause is an aggregate value</el-tag>
          </div>
          <div class="muted" style="margin: 6px 0">{{ verdict.message }}</div>
          <div class="num muted">
            Probes {{ verdict.probes }} · Query budget {{ verdict.budgetUsed }}/{{ verdict.budgetLimit }}
          </div>
          <div class="contrib-bar" style="margin-top: 6px">
            <div :style="{ width: `${(verdict.budgetUsed / verdict.budgetLimit) * 100}%` }"></div>
          </div>
        </div>

        <AttributionPath v-if="verdict && store.mode === 'ATTRIBUTION'" :path="verdict.path" style="margin-top: 12px" />
        <FindingsCard v-if="findings" style="margin-top: 12px" :findings="findings" />

        <div v-if="done" class="panel" style="margin-top: 12px">
          <h3 class="panel-title">Done</h3>
          <div class="mono muted">run {{ done.runId }}</div>
          <div v-if="done.sessionId" class="mono muted" style="font-size: 12px">
            session {{ done.sessionId.slice(0, 8) }}…
          </div>
          <div v-if="done.resolved && done.resolved !== store.question" style="font-size: 12px; margin-top: 4px">
            Resolved question: <span class="mono">{{ done.resolved }}</span>
            <el-tag v-if="done.note" size="small" type="info" style="margin-left: 6px">{{ done.note }}</el-tag>
          </div>
          <RouterLink :to="`/runs/${done.runId}`" style="font-size: 13px">View trace for this run</RouterLink>
        </div>
      </el-col>
    </el-row>
  </div>
</template>
