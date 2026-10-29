<script setup lang="ts">
import type { VerdictPathNode } from '../types'

defineProps<{ path: VerdictPathNode[] }>()

const fmt = (n: number) => n.toLocaleString('zh-CN', { maximumFractionDigits: 2 })
const pct = (n: number) => `${(n * 100).toFixed(1)}%`
</script>

<template>
  <div class="panel">
    <h3 class="panel-title">Attribution path · Root metric → per-layer splits</h3>
    <div v-if="!path.length" class="muted" style="font-size: 13px">
      No usable split was produced in this run (no significant change at the root metric, or budget exhausted).
    </div>
    <div v-for="node in path" :key="node.depth" :style="{ marginLeft: `${node.depth * 18}px` }" style="margin-bottom: 10px">
      <div style="display: flex; align-items: center; gap: 8px; flex-wrap: wrap">
        <el-tag v-if="node.depth === 0" size="small" type="info">Root metric</el-tag>
        <el-tag v-else size="small">{{ node.dimension }} = {{ node.value }}</el-tag>
        <span class="num">Current {{ fmt(node.current) }}</span>
        <span class="muted num">Baseline {{ fmt(node.baseline) }}</span>
        <span class="num" :style="{ color: node.delta < 0 ? '#c0392b' : '#2f9e6f' }">
          Δ {{ fmt(node.delta) }}
        </span>
        <span v-if="node.depth > 0" class="num muted">Contribution {{ pct(node.contribution) }}</span>
      </div>
      <div v-if="node.depth > 0" class="contrib-bar" style="margin-top: 4px; max-width: 320px">
        <div :style="{ width: `${Math.min(Math.abs(node.contribution) * 100, 100)}%` }"></div>
      </div>
    </div>
  </div>
</template>
