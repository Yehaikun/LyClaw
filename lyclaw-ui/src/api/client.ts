/**
 * HTTP客户端底层封装，提供统一的请求/响应拦截、超时控制、错误格式化及SSE流式处理能力。
 *
 * 本模块是整个前端API通信的基石，所有上层API函数（chat、action、memory、plan、protocol）
 * 均通过此处的get/post/del/postSSE函数与服务端交互。它封装了以下关键职责：
 * 1. 请求超时机制：普通请求使用AbortController在DEFAULT_TIMEOUT_MS后自动中止
 * 2. 错误标准化：将网络异常、超时、HTTP错误状态码统一包装为ApiError类型
 * 3. SSE流式解析：遵循SSE协议规范，处理event/data字段，支持done事件和[DONE]标记
 * 4. TraceId追踪：从响应头X-Trace-Id中提取追踪标识，贯穿整个错误报告链路
 * 5. 流超时保护：每60秒若无新数据块则判定流已停滞，整体超过300秒则强制终止
 *
 * 设计考虑：
 * - 不使用axios等第三方库，完全基于原生fetch API以保持零依赖
 * - SSE解析器以逐字符流的方式处理分块传输，兼容不完整的UTF-8字节序列
 * - 错误处理采用分层策略：先检查ApiError（已格式化），再检查AbortError（超时），最后兜底
 * - ApiError类携带traceId字段，前端可在错误提示中展示追踪ID便于后端日志关联
 */
const BASE_URL = ''
const DEFAULT_TIMEOUT_MS = 30_000

/**
 * 统一的API错误类型，将HTTP错误响应和网络异常标准化为单一错误对象。
 *
 * 携带以下关键信息用于前端展示与调试：
 * - status: HTTP状态码，超时或网络异常时为0
 * - body: 服务端返回的原始错误体（JSON对象或纯文本），可用于展示详细错误信息
 * - traceId: 从响应头X-Trace-Id提取的分布式追踪标识，用于关联后端日志定位问题根因
 *
 * 使用方式：在catch块中通过 instanceof ApiError 判断是否为已格式化的API错误，
 * 若是则可直接展示message字段，同时展示traceId供用户反馈问题时提供。
 */
export class ApiError extends Error {
  public status: number
  public body: unknown
  public traceId?: string

  constructor(status: number, message: string, body: unknown, traceId?: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.body = body
    this.traceId = traceId
  }
}

/**
 * 核心请求函数，封装fetch API提供统一的请求构造、超时控制、错误处理和响应解析。
 *
 * 处理流程：
 * 1. 创建AbortController并设置超时定时器，超时后自动中止请求
 * 2. 构造RequestInit对象，设置Content-Type为application/json
 * 3. 发起fetch请求，检查响应状态码
 * 4. 若非2xx响应，尝试解析响应体为JSON或文本，抛出ApiError
 * 5. 对于204 No Content响应，直接返回undefined（无响应体）
 * 6. 正常响应解析为JSON并返回
 * 7. 异常处理分三层：ApiError直接抛出、AbortError转换为超时错误、其他异常包装为ApiError
 * 8. finally块中清除超时定时器，避免内存泄漏
 *
 * @param method HTTP方法（GET/POST/DELETE等）
 * @param path 请求路径，会自动拼接BASE_URL前缀
 * @param body 可选的请求体，会被JSON序列化
 * @returns 解析后的响应数据，类型由泛型T指定
 */
async function request<T>(
  method: string,
  path: string,
  body?: unknown,
): Promise<T> {
  const controller = new AbortController()
  const timeoutId = setTimeout(() => controller.abort(), DEFAULT_TIMEOUT_MS)

  const url = `${BASE_URL}${path}`

  const init: RequestInit = {
    method,
    headers: {
      'Content-Type': 'application/json',
    },
    signal: controller.signal,
  }

  if (body !== undefined) {
    init.body = JSON.stringify(body)
  }

  try {
    const response = await fetch(url, init)

    const traceId = response.headers.get('X-Trace-Id') || undefined

    if (!response.ok) {
      let errorBody: unknown
      try {
        errorBody = await response.json()
      } catch {
        errorBody = await response.text()
      }
      throw new ApiError(
        response.status,
        `Request failed: ${response.status} ${response.statusText}`,
        errorBody,
        traceId,
      )
    }

    // 处理204 No Content响应：无响应体，直接返回undefined。
    // 这是RESTful API中DELETE操作或某些POST操作的常见响应模式，
    // 此时调用response.json()会失败，因此需要特殊处理。
    if (response.status === 204) {
      return undefined as unknown as T
    }

    const data: T = await response.json()
    return data
  } catch (err) {
    if (err instanceof ApiError) {
      throw err
    }
    if ((err as Error).name === 'AbortError') {
      throw new ApiError(0, `Request timed out after ${DEFAULT_TIMEOUT_MS}ms`, null)
    }
    throw new ApiError(0, (err as Error).message, null)
  } finally {
    clearTimeout(timeoutId)
  }
}

/**
 * 发起GET请求，从指定路径获取数据。
 *
 * 适用于获取资源列表、查询统计数据、获取健康检查结果等只读操作。
 * 内部调用request函数，将泛型T推断为响应JSON的类型。
 *
 * @param path 请求路径（不含BASE_URL前缀）
 * @returns Promise<T> 解析后的响应数据
 */
export async function get<T>(path: string): Promise<T> {
  return request<T>('GET', path)
}

/**
 * 发起POST请求，向指定路径提交数据。
 *
 * 适用于创建资源、提交查询条件、触发操作等需要携带请求体的写操作。
 * body参数会被JSON.stringify序列化后发送。
 *
 * @param path 请求路径（不含BASE_URL前缀）
 * @param body 可选的请求体对象
 * @returns Promise<T> 解析后的响应数据
 */
export async function post<T>(path: string, body?: unknown): Promise<T> {
  return request<T>('POST', path, body)
}

/**
 * 发起DELETE请求，删除指定路径对应的资源。
 *
 * 适用于删除会话、清理缓存、移除配置等资源删除操作。
 * 通常服务端返回204 No Content或包含删除确认信息的JSON对象。
 *
 * @param path 请求路径（不含BASE_URL前缀）
 * @returns Promise<T> 解析后的响应数据
 */
export async function del<T>(path: string): Promise<T> {
  return request<T>('DELETE', path)
}

/**
 * 发起SSE（Server-Sent Events）流式POST请求，实时接收服务端推送的事件流。
 *
 * 这是聊天功能的核心通信方式，支持大语言模型的逐token流式输出。内部实现了完整的
 * SSE协议解析器，处理以下关键场景：
 *
 * 1. 双超时保护机制：
 *    - 读取超时（READ_TIMEOUT_MS=60秒）：每次成功读取数据块后重置，确保只有真正停滞才判定超时
 *    - 总超时（MAX_TOTAL_MS=300秒/5分钟）：整个流的硬性上限，防止无限挂起
 *
 * 2. SSE事件解析：
 *    - event: 行 → 切换当前事件类型（message/error/done）
 *    - data: 行 → 累积数据到缓冲区
 *    - 空行 → 触发事件回调，清空缓冲区
 *    - event: done 或 data: [DONE] → 流正常结束
 *
 * 3. 分块传输处理：
 *    - 使用TextDecoder以stream模式解码，正确处理跨chunk的UTF-8多字节字符
 *    - buffer保留未完成的行片段，等待下一个chunk拼接完整后再处理
 *
 * 4. 错误传播：
 *    - HTTP非2xx响应 → 解析错误体并通过onError回调
 *    - AbortError（超时）→ 包装为ApiError后回调
 *    - 其他异常 → 保留原始堆栈信息后回调
 *
 * @param path 请求路径（不含BASE_URL前缀）
 * @param body 请求体对象，会被JSON序列化
 * @param onChunk 接收到文本数据块时的回调，参数为解码后的字符串
 * @param onDone 流正常结束时的回调
 * @param onError 发生错误时的回调，参数为Error对象
 */
export async function postSSE(
  path: string,
  body: unknown,
  onChunk: (text: string) => void,
  onDone: () => void,
  onError: (err: Error) => void,
  onStatus?: (text: string) => void,
  onToolCall?: (data: string) => void,
  onApprovalRequired?: (data: string) => void,
  onEvent?: (event: string, data: string) => void,
): Promise<void> {
  const controller = new AbortController()
  // 每次成功收到数据块后重置的读取超时，确保只有真正停滞超过60秒才判定为超时。
  // 这与固定超时不同，避免了大模型长时间"思考"后突然输出大量内容被误判超时。
  const READ_TIMEOUT_MS = 60_000
  // 整个SSE流的硬性总超时（5分钟），防止因服务端或网络问题导致的无限挂起。
  // 正常情况下流式响应不会超过此时间，此为安全兜底机制。
  const MAX_TOTAL_MS = 300_000

  let readTimer: ReturnType<typeof setTimeout> | null = null
  let maxTimer: ReturnType<typeof setTimeout> | null = null
  let capturedTraceId: string | undefined = undefined

  function clearTimers() {
    if (readTimer !== null) { clearTimeout(readTimer); readTimer = null }
    if (maxTimer !== null) { clearTimeout(maxTimer); maxTimer = null }
  }

  /**
   * 重置读取超时定时器。每次从网络成功读取到数据块后调用，
   * 取消之前的超时定时器并重新开始计时，确保只在数据真正停滞时才触发超时。
   */
  function resetReadTimeout() {
    if (readTimer !== null) clearTimeout(readTimer)
    readTimer = setTimeout(() => {
      controller.abort()
      onError(new ApiError(0, `Stream stalled: no data for ${READ_TIMEOUT_MS / 1000}s`, null, capturedTraceId))
    }, READ_TIMEOUT_MS)
  }

  // 启动总超时定时器，作为整个流的硬性时间上限
  maxTimer = setTimeout(() => {
    controller.abort()
    onError(new ApiError(0, `Stream timed out after ${MAX_TOTAL_MS / 1000}s`, null, capturedTraceId))
  }, MAX_TOTAL_MS)

  // 启动首次读取超时定时器
  resetReadTimeout()

  const url = `${BASE_URL}${path}`

  try {
    const response = await fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
      },
      body: JSON.stringify(body),
      signal: controller.signal,
    })

    capturedTraceId = response.headers.get('X-Trace-Id') || undefined

    if (!response.ok) {
      let errorBody: unknown
      try {
        errorBody = await response.json()
      } catch {
        errorBody = await response.text()
      }
      const err = new ApiError(
        response.status,
        `SSE request failed: ${response.status} ${response.statusText}`,
        errorBody,
        capturedTraceId,
      )
      onError(err)
      return
    }

    const reader = response.body?.getReader()
    if (!reader) {
      onError(new ApiError(0, 'Response body is not readable', null, capturedTraceId))
      return
    }

    const decoder = new TextDecoder()
    let buffer = ''
    let currentEvent = ''

    let dataBuffer: string[] = []

    /**
     * 将数据缓冲区中的内容触发对应的事件回调。
     * 根据currentEvent（事件类型）决定调用onChunk还是onError：
     * - 'message'事件（默认）：将累积的data行拼接后通过onChunk传递
     * - 'error'事件：尝试解析JSON提取message字段，通过onError传递
     * 处理完成后清空dataBuffer以接收下一个事件的数据。
     */
    function flushDataBuffer() {
      if (dataBuffer.length > 0) {
        const text = dataBuffer.join('\n')
        if (currentEvent === 'message') {
          if (text) onChunk(text)
        } else if (currentEvent === 'error') {
          let message = text
          try {
            const parsed = JSON.parse(text)
            message = parsed.message || text
          } catch { /* 解析失败时使用原始文本作为错误消息 */ }
          onError(new ApiError(0, message, text, capturedTraceId))
        } else if (currentEvent === 'status') {
          if (text && onStatus) onStatus(text)
        } else if (currentEvent === 'tool_call') {
          if (text && onToolCall) onToolCall(text)
        } else if (currentEvent === 'tool_approval') {
          if (text && onApprovalRequired) onApprovalRequired(text)
        } else if (onEvent) {
          onEvent(currentEvent, text)
        }
      }
      dataBuffer = []
    }

    /**
     * 处理SSE协议的行流，逐行解析event和数据字段。
     *
     * 支持以下SSE行类型：
     * - "event: <type>" → 设置当前事件类型，遇到"done"事件则返回true表示流结束
     * - "data: <content>" → 将数据内容追加到dataBuffer
     * - 空行 → 触发flushDataBuffer，完成一个事件的接收
     *
     * @param lines 从缓冲区中分割出的行数组
     * @returns 如果遇到流结束标记（event:done或data:[DONE]）则返回true
     */
    function processLines(lines: string[]): boolean {
      for (const line of lines) {
        if (line.startsWith('event:')) {
          flushDataBuffer()
          currentEvent = line.slice(6).trim()
          if (currentEvent === 'done') {
            return true
          }
        } else if (line.startsWith('data:')) {
          const data = line.slice(5)
          if (data.trim() === '[DONE]') {
            flushDataBuffer()
            return true
          }
          dataBuffer.push(data)
        } else if (line === '' || line === '\r') {
          flushDataBuffer()
        }
      }
      return false
    }

    // 主读取循环：持续从流中读取数据块直到done或出错
    while (true) {
      const { done, value } = await reader.read()

      // 每次成功从网络层读取到数据后重置读取超时，
      // 这是流控的关键：只要数据在持续到来就不会因超时而中断
      resetReadTimeout()

      if (done) {
        // 流底层的ReadableStream已完成，处理缓冲区中残留的最后一行
        if (buffer.trim()) {
          const lines = buffer.split('\n')
          processLines(lines)
        }
        flushDataBuffer()
        onDone()
        return
      }

      // 以stream模式解码新数据块并追加到缓冲区，
      // stream:true确保多字节UTF-8字符跨chunk时不会乱码
      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      // 最后一行可能不完整（换行符在下一个chunk中），保留在buffer中等待下次拼接
      buffer = lines.pop() || ''

      if (processLines(lines)) {
        onDone()
        return
      }
    }
  } catch (err) {
    // 分层错误处理：
    // 1. AbortError → 流被中止（超时或主动取消），包装为ApiError
    // 2. ApiError → 已格式化的API错误，直接传递
    // 3. 其他异常 → 包装为ApiError并保留原始堆栈信息
    if ((err as Error).name === 'AbortError') {
      onError(new ApiError(0, `SSE stream aborted`, null, capturedTraceId))
    } else if (err instanceof ApiError) {
      onError(err)
    } else {
      const genericErr = err as Error
      const apiError = new ApiError(0, genericErr.message, null, capturedTraceId)
      apiError.stack = genericErr.stack
      onError(apiError)
    }
  } finally {
    clearTimers()
  }
}
