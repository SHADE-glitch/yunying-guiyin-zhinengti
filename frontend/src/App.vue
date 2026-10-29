<script setup lang="ts">
import { useRoute } from 'vue-router'

const route = useRoute()
const nav = [
  { to: '/', label: 'Workbench' },
  { to: '/runs', label: 'Run History' },
  { to: '/knowledge', label: 'Knowledge Base' },
  { to: '/eval', label: 'Evaluation' },
]
</script>

<template>
  <div>
    <header class="app-header">
      <h1>insight-agent</h1>
      <span class="muted">Operational Attribution Agent · The backend is the main body of this repository; the frontend is the presentation layer</span>
    </header>
    <el-container>
      <el-aside width="168px" style="border-right: 1px solid var(--line); background: var(--panel)">
        <el-menu :default-active="route.path" router style="border-right: none">
          <el-menu-item v-for="item in nav" :key="item.to" :index="item.to">
            {{ item.label }}
          </el-menu-item>
        </el-menu>
      </el-aside>
      <el-main class="app-main" style="padding: 16px 20px">
        <router-view />
      </el-main>
    </el-container>
  </div>
</template>
