<script setup lang="ts">
import type { Findings } from '../types'

defineProps<{ findings: Findings }>()

const sections = [
  { key: 'facts', label: 'Facts · Only numbers present in the evidence', cls: 'findings-facts' },
  { key: 'hypothesis', label: 'Hypothesis · Speculation', cls: 'findings-hypothesis' },
  { key: 'uncertainty', label: 'Uncertainty · I don\'t know', cls: 'findings-uncertainty' },
] as const
</script>

<template>
  <div class="panel">
    <h3 class="panel-title">Three-part conclusion</h3>
    <div v-for="s in sections" :key="s.key" :class="s.cls" style="padding-left: 10px; margin-bottom: 10px">
      <div class="muted" style="font-size: 12px; margin-bottom: 4px">{{ s.label }}</div>
      <ul v-if="findings[s.key].length" class="findings-list">
        <li v-for="(line, i) in findings[s.key]" :key="i">{{ line }}</li>
      </ul>
      <span v-else class="muted" style="font-size: 12px">—</span>
    </div>
  </div>
</template>
