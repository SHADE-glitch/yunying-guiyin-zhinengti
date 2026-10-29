<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../api/client'
import type { KnowledgePage } from '../types'

const page = ref<KnowledgePage | null>(null)
const type = ref('')
const query = ref('')
const loading = ref(false)
const error = ref('')

async function load(q?: string) {
  loading.value = true
  error.value = ''
  try {
    page.value = await api.knowledge({ type: type.value || undefined, q, limit: 30 })
  } catch (e) {
    error.value = String(e)
  } finally {
    loading.value = false
  }
}

onMounted(() => load())

const typeTag = (t: string) => (t === 'METRIC' ? 'primary' : t === 'RULE' ? 'success' : 'info')
</script>

<template>
  <div>
    <div class="panel" style="display: flex; gap: 10px; align-items: center; flex-wrap: wrap">
      <b>Knowledge base</b>
      <span v-if="page" class="muted num" style="font-size: 12px">
        Corpus {{ page.total }} docs · Vectors embedded {{ page.embedded }}
        <el-tag v-if="page.embedded < page.total" size="small" type="warning" style="margin-left: 6px">
          Incomplete embedding: vector path is empty
        </el-tag>
        <el-tag v-if="page.mode !== 'LIST'" size="small" type="info" style="margin-left: 6px">
          This retrieval path: {{ page.mode }}
        </el-tag>
      </span>
      <span v-if="page?.fallback" class="muted" style="font-size: 12px; margin-left: 8px">{{ page.fallback }}</span>
      <div style="flex: 1"></div>
      <el-select v-model="type" style="width: 130px" @change="load()">
        <el-option label="All types" value="" />
        <el-option label="Metric caliber METRIC" value="METRIC" />
        <el-option label="Platform rules RULE" value="RULE" />
        <el-option label="FAQ" value="FAQ" />
      </el-select>
      <el-input
        v-model="query"
        style="width: 260px"
        placeholder="Search demo: how is refund rate calculated"
        clearable
        @keyup.enter="load(query)"
        @clear="load()"
      />
      <el-button type="primary" :loading="loading" @click="load(query)">Search</el-button>
    </div>
    <div class="muted" style="font-size: 12px; margin: 8px 2px">
      Search goes through the same path as the in-Agent tool: vector (pgvector HNSW) + keyword (pg_trgm trigram) + RRF fusion;
      when embeddings are incomplete, only the keyword path hits.
    </div>

    <el-alert v-if="error" type="error" :closable="false" :title="error" style="margin-top: 8px" />

    <div v-loading="loading" style="margin-top: 12px">
      <div v-for="doc in (page?.docs ?? [])" :key="doc.id" class="panel" style="margin-bottom: 10px">
        <div style="display: flex; align-items: center; gap: 8px; flex-wrap: wrap">
          <el-tag size="small" :type="typeTag(doc.docType)">{{ doc.docType }}</el-tag>
          <b>{{ doc.title }}</b>
          <code v-if="doc.code" class="mono muted">{{ doc.code }}</code>
          <span v-if="doc.score != null" class="num muted" style="margin-left: auto">
            {{ page?.mode === 'HYBRID' ? 'RRF score' : 'score' }} {{ doc.score.toFixed(4) }}
          </span>
        </div>
        <pre class="block" style="margin-top: 8px">{{ doc.content }}</pre>
        <div v-if="doc.synonyms?.length" style="margin-top: 6px">
          <el-tag v-for="s in doc.synonyms" :key="s" size="small" type="info" style="margin-right: 6px">{{ s }}</el-tag>
        </div>
        <pre v-if="doc.sqlTemplate" class="block" style="margin-top: 8px">{{ doc.sqlTemplate }}</pre>
      </div>
      <div v-if="page && !page.docs.length" class="muted" style="font-size: 13px">No hits.</div>
    </div>
  </div>
</template>
