<script setup lang="ts">
import type { NodeRow } from '../types'

defineProps<{ node: NodeRow; children: Record<number, NodeRow[]> }>()

const fmt = (n: number | null) => (n == null ? '—' : Number(n).toLocaleString('zh-CN', { maximumFractionDigits: 2 }))
const pct = (n: number | null) => (n == null ? '—' : `${(Number(n) * 100).toFixed(1)}%`)

const verdictTag = (v: string) =>
  v === 'DOMINANT' ? 'success' : v === 'FAILED' ? 'danger' : v === 'ROOT' ? 'info' : 'primary'
</script>

<template>
  <div class="panel" style="padding: 10px 12px">
    <div style="display: flex; align-items: center; gap: 8px; flex-wrap: wrap">
      <el-tag size="small" :type="verdictTag(node.verdict)">{{ node.verdict }}</el-tag>
      <el-tag v-if="node.depth === 0" size="small" type="info">Root metric {{ node.metricCode }}</el-tag>
      <el-tag v-else size="small">{{ node.dimension }} = {{ node.targetValue }}</el-tag>
      <span class="num">Current {{ fmt(node.currentValue) }}</span>
      <span class="num muted">Baseline {{ fmt(node.baselineValue) }}</span>
      <span class="num" :style="{ color: (node.delta ?? 0) < 0 ? '#c0392b' : '#2f9e6f' }">Δ {{ fmt(node.delta) }}</span>
      <span v-if="node.depth > 0" class="num muted">Contribution {{ pct(node.contribution) }}</span>
      <span class="num muted">{{ node.rowCount ?? '—' }} rows</span>
      <a v-if="node.evidenceStepId" :href="`#step-${node.evidenceStepId}`" class="mono" style="font-size: 12px">
        Evidence step #{{ node.evidenceStepId }}
      </a>
      <span v-if="node.queryHash" class="mono muted" style="font-size: 11px">
        sql {{ node.queryHash.slice(0, 12) }}…
      </span>
    </div>
    <div v-if="node.depth > 0" class="contrib-bar" style="margin-top: 6px; max-width: 280px">
      <div :style="{ width: `${Math.min(Math.abs(Number(node.contribution ?? 0)) * 100, 100)}%` }"></div>
    </div>

    <div v-for="child in (children[node.id] ?? [])" :key="child.id" style="margin-left: 22px; margin-top: 8px">
      <NodeTree :node="child" :children="children" />
    </div>
  </div>
</template>
