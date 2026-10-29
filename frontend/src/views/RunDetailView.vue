<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../api/client'
import NodeTree from '../components/NodeTree.vue'
import FindingsCard from '../components/FindingsCard.vue'
import type { Findings, NodeRow, RunDetail } from '../types'

const route = useRoute()
const detail = ref<RunDetail | null>(null)
const error = ref('')

const roots = computed<NodeRow[]>(() => detail.value?.nodes.filter((n) => n.depth === 0) ?? [])
const children = computed<Record<number, NodeRow[]>>(() => {
  const map: Record<number, NodeRow[]> = {}
  for (const node of detail.value?.nodes ?? []) {
    if (node.parentId == null) continue
    ;(map[node.parentId] ??= []).push(node)
  }
  return map
})

const findings = computed<Findings | null>(() => {
  const answer = detail.value?.answer
  if (!answer) return null
  try {
    const parsed = JSON.parse(answer)
    return Array.isArray(parsed.facts) ? (parsed as Findings) : null
  } catch {
    return null
  }
})

onMounted(async () => {
  try {
    detail.value = await api.run(String(route.params.id))
  } catch (e) {
    error.value = String(e)
  }
})

const fmtTime = (iso: string | null) => (iso ? new Date(iso).toLocaleString('zh-CN', { hour12: false }) : '—')
const stepTag = (type: string) =>
  type.startsWith('TOOL_') && type !== 'TOOL_RESULT' ? 'danger'
    : type === 'REFLECT' ? 'warning'
      : type === 'ANSWER' ? 'success' : 'primary'
</script>

<template>
  <div v-if="detail">
    <div class="panel">
      <div style="display: flex; align-items: center; gap: 8px; flex-wrap: wrap">
        <b style="font-size: 15px">{{ detail.run.question }}</b>
        <el-tag size="small" :type="detail.run.mode === 'ATTRIBUTION' ? 'primary' : 'info'">
          {{ detail.run.mode === 'ATTRIBUTION' ? 'Attribution' : 'Lookup' }}
        </el-tag>
        <el-tag size="small" :type="detail.run.status === 'SUCCEEDED' ? 'success' : 'danger'">
          {{ detail.run.status }}
        </el-tag>
        <el-tag v-if="detail.run.attributionOutcome" size="small" type="info">
          {{ detail.run.attributionOutcome }}
        </el-tag>
      </div>
      <div class="muted num" style="margin-top: 6px; font-size: 12px">
        run {{ detail.run.id }} · {{ fmtTime(detail.run.createdAt) }} → {{ fmtTime(detail.run.finishedAt) }} ·
        steps {{ detail.run.stepCount }} · tokens {{ detail.run.promptTokens + detail.run.completionTokens }}
        (prompt {{ detail.run.promptTokens }} / completion {{ detail.run.completionTokens }})
      </div>
      <el-alert v-if="detail.error" type="error" :closable="false" :title="detail.error" style="margin-top: 8px" />
    </div>

    <FindingsCard v-if="findings" style="margin-top: 12px" :findings="findings" />
    <div v-else-if="detail.answer" class="panel" style="margin-top: 12px">
      <h3 class="panel-title">Answer</h3>
      <pre class="block">{{ detail.answer }}</pre>
    </div>

    <div v-if="roots.length" class="panel" style="margin-top: 12px">
      <h3 class="panel-title">Attribution tree · Node-bound evidence step and SQL fingerprint</h3>
      <NodeTree v-for="root in roots" :key="root.id" :node="root" :children="children" style="margin-bottom: 8px" />
    </div>

    <div class="panel" style="margin-top: 12px">
      <h3 class="panel-title">Step trace</h3>
      <div v-for="step in detail.steps" :key="step.id" :id="`step-${step.id}`" style="margin-bottom: 10px; scroll-margin-top: 12px">
        <div style="display: flex; align-items: center; gap: 8px; flex-wrap: wrap">
          <span class="num muted">#{{ step.stepNo }}</span>
          <el-tag size="small" :type="stepTag(step.type)">{{ step.type }}</el-tag>
          <code v-if="step.toolName" class="mono">{{ step.toolName }}</code>
          <span v-if="step.latencyMs != null" class="num muted">{{ step.latencyMs }} ms</span>
          <span v-if="step.promptTokens + step.completionTokens > 0" class="num muted">
            {{ step.promptTokens + step.completionTokens }} tokens
          </span>
        </div>
        <el-collapse style="margin-top: 4px">
          <el-collapse-item title="input / output">
            <pre v-if="step.input" class="block">{{ step.input }}</pre>
            <pre v-if="step.output" class="block" style="margin-top: 6px">{{ step.output }}</pre>
          </el-collapse-item>
        </el-collapse>
      </div>
      <div v-if="!detail.steps.length" class="muted" style="font-size: 13px">No steps (failed before entering the loop).</div>
    </div>
  </div>
  <el-alert v-else-if="error" type="error" :closable="false" :title="error" />
</template>
