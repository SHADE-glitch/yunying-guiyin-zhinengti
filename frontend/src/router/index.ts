import { createRouter, createWebHistory } from 'vue-router'

export default createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'workbench', component: () => import('../views/WorkbenchView.vue') },
    { path: '/runs', name: 'runs', component: () => import('../views/RunsView.vue') },
    { path: '/runs/:id', name: 'run-detail', component: () => import('../views/RunDetailView.vue') },
    { path: '/knowledge', name: 'knowledge', component: () => import('../views/KnowledgeView.vue') },
    { path: '/eval', name: 'eval', component: () => import('../views/EvalView.vue') },
  ],
})
