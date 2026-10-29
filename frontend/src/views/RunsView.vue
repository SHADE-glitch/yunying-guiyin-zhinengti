<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api/client'
import type { RunSummary } from '../types'

const router = useRouter()
const runs = ref<RunSummary[]>([])
const loading = ref(false)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    runs.value = await api.runs(50)
  } catch (e) {
    error.value = String(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)

const statusTag = (s: string) => (s === 'SUCCEEDED' ? 'success' : s === 'RUNNING' ? 'warning' : 'danger')
const fmtTime = (iso: string) => new Date(iso).toLocaleString('zh-CN', { hour12: false })
</script>

<template>
  <div>
    <div class="panel" style="display: flex; align-items: center; justify-content: space-between">
      <div>
        <b>Run history</b>
        <span class="muted" style="margin-left: 10px">One row per question · Each step of the trace can be drilled into from the detail page</span>
      </div>
      <el-button :loading="loading" @click="load">Refresh</el-button>
    </div>

    <el-alert v-if="error" type="error" :closable="false" :title="error" style="margin-top: 12px" />

    <el-table
      v-loading="loading"
      :data="runs"
      style="margin-top: 12px; cursor: pointer"
      @row-click="(row: RunSummary) => router.push(`/runs/${row.id}`)"
    >
      <el-table-column label="Time" width="170">
        <template #default="{ row }">
          <span class="num muted">{{ fmtTime(row.createdAt) }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="question" label="Question" min-width="260" show-overflow-tooltip />
      <el-table-column label="Chain" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.mode === 'ATTRIBUTION' ? 'primary' : 'info'">
            {{ row.mode === 'ATTRIBUTION' ? 'Attribution' : 'Lookup' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="Status" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="statusTag(row.status)">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="attributionOutcome" label="Outcome" width="190">
        <template #default="{ row }">
          <span class="mono muted">{{ row.attributionOutcome ?? '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="stepCount" label="Steps" width="70" align="right" />
      <el-table-column label="Tokens" width="110" align="right">
        <template #default="{ row }">
          <span class="num">{{ row.promptTokens + row.completionTokens }}</span>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>
