import { defineStore } from 'pinia'
import { ref } from 'vue'

/** 工作台的输入草稿与会话 id：切页再回来不丢，也保证演示时不用重打字。 */
export const useWorkbenchStore = defineStore('workbench', () => {
  const mode = ref<'ATTRIBUTION' | 'LOOKUP'>(
    localStorage.getItem('wb.mode') === 'LOOKUP' ? 'LOOKUP' : 'ATTRIBUTION',
  )
  const question = ref(localStorage.getItem('wb.question') ?? '为什么华东GMV跌了')
  const sessionId = ref(localStorage.getItem('wb.session') ?? crypto.randomUUID())
  localStorage.setItem('wb.session', sessionId.value)

  function setMode(value: 'ATTRIBUTION' | 'LOOKUP') {
    mode.value = value
    localStorage.setItem('wb.mode', value)
  }

  function setQuestion(value: string) {
    question.value = value
    localStorage.setItem('wb.question', value)
  }

  function newSession() {
    sessionId.value = crypto.randomUUID()
    localStorage.setItem('wb.session', sessionId.value)
  }

  return { mode, question, sessionId, setMode, setQuestion, newSession }
})
