const EVENT_TYPES = [
  'baseline', 'probe', 'verdict', 'answer', 'done',
  'tool_call', 'tool_result', 'reflect', 'error',
]

/**
 * 消费后端 SSE。两个入口都是 GET，所以直接用 EventSource。
 * 服务端发完 done / error 会关闭流；EventSource 默认会重连，这里收到终态就 close。
 */
export function streamEvents(
  url: string,
  // 各事件 payload 结构不同，消费方按类型窄化；这里不做统一建模
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  onEvent: (type: string, data: any) => void,
): { close: () => void } {
  const source = new EventSource(url)
  let finished = false

  const finish = () => {
    finished = true
    source.close()
  }

  for (const type of EVENT_TYPES) {
    source.addEventListener(type, (e) => {
      onEvent(type, JSON.parse((e as MessageEvent).data))
      if (type === 'done' || type === 'error') finish()
    })
  }

  source.onerror = () => {
    if (finished) return
    onEvent('error', { message: '连接中断：服务未启动，或该链路当前不可用' })
    finish()
  }

  return { close: finish }
}
