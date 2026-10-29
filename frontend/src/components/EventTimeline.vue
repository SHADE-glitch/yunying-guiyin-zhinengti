<script setup lang="ts">
import { RouterLink } from 'vue-router'

export interface TimelineItem {
  type: string
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  data: any
}

defineProps<{ items: TimelineItem[]; mode: 'ATTRIBUTION' | 'LOOKUP' }>()

const toolStatusTag = (status: string) =>
  status === 'OK' ? 'success'
    : status === 'EMPTY' ? 'info'
      : status === 'REJECTED' ? 'warning' : 'danger'

const probeStatusTag = (status: string) =>
  status === 'OK' ? 'success' : status === 'INCONSISTENT' ? 'warning' : 'danger'
</script>

<template>
  <div class="panel">
    <h3 class="panel-title">
      {{ mode === 'ATTRIBUTION' ? 'Execution timeline · baseline → probe* → verdict → answer → done' : 'Execution timeline · Tool loop' }}
    </h3>
    <el-timeline v-if="items.length" style="padding-left: 2px">
      <el-timeline-item
        v-for="(item, i) in items"
        :key="i"
        :type="item.type === 'error' ? 'danger' : item.type === 'done' ? 'success' : 'primary'"
      >
        <template v-if="item.type === 'baseline'">
          <b>Root metric</b>
          <el-tag v-for="(v, k) in item.data.scope" :key="k" size="small" style="margin-left: 6px">
            {{ k }} = {{ v }}
          </el-tag>
          <div class="num" style="margin-top: 4px">
            Current {{ Number(item.data.current).toLocaleString() }} ·
            Baseline {{ Number(item.data.baseline).toLocaleString() }} ·
            Δ {{ Number(item.data.delta).toLocaleString() }}
          </div>
        </template>

        <template v-else-if="item.type === 'probe'">
          <b>Split probe</b> depth {{ item.data.depth }}
          <el-tag size="small" style="margin: 0 6px">{{ item.data.dimension }}</el-tag>
          <el-tag size="small" :type="probeStatusTag(item.data.status)">{{ item.data.status }}</el-tag>
          <span class="muted num" style="margin-left: 6px">{{ item.data.groups }} groups</span>
          <div v-if="item.data.error" class="muted" style="font-size: 12px">{{ item.data.error }}</div>
        </template>

        <template v-else-if="item.type === 'tool_call'">
          <b>Tool call</b> step {{ item.data.step }}
          <code class="mono" style="margin-left: 6px">{{ item.data.tool }}</code>
          <pre v-if="item.data.args" class="block" style="margin-top: 6px">{{ item.data.args }}</pre>
        </template>

        <template v-else-if="item.type === 'tool_result'">
          <b>Tool result</b> step {{ item.data.step }}
          <code class="mono" style="margin: 0 6px">{{ item.data.tool }}</code>
          <el-tag size="small" :type="toolStatusTag(item.data.status)">{{ item.data.status }}</el-tag>
          <pre class="block" style="margin-top: 6px">{{ item.data.result }}</pre>
        </template>

        <template v-else-if="item.type === 'reflect'">
          <b>Reflection</b> round {{ item.data.round }}
          <pre class="block" style="margin-top: 6px">{{ item.data.message }}</pre>
        </template>

        <template v-else-if="item.type === 'answer' && mode === 'LOOKUP'">
          <b>Final answer</b>
          <pre class="block" style="margin-top: 6px">{{ item.data.text }}</pre>
        </template>

        <template v-else-if="item.type === 'done'">
          <b>Done</b>
          <span v-if="item.data.outcome" class="muted" style="margin-left: 6px">{{ item.data.outcome }}</span>
          <span v-if="item.data.tokens != null" class="muted num" style="margin-left: 6px">
            {{ item.data.tokens }} tokens
          </span>
          <RouterLink v-if="item.data.runId" :to="`/runs/${item.data.runId}`" style="margin-left: 8px">
            View trace for this run
          </RouterLink>
        </template>

        <template v-else-if="item.type === 'error'">
          <b>Error</b>
          <span v-if="item.data.code" class="mono" style="margin-left: 6px">{{ item.data.code }}</span>
          <div style="margin-top: 4px">{{ item.data.message }}</div>
        </template>
      </el-timeline-item>
    </el-timeline>
    <div v-else class="muted" style="font-size: 13px">After sending a question, each step is pushed here as soon as it finishes computing.</div>
  </div>
</template>
