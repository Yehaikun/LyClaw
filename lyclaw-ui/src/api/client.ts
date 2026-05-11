const BASE_URL = ''
const DEFAULT_TIMEOUT_MS = 30_000

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

    // Handle 204 No Content
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

export async function get<T>(path: string): Promise<T> {
  return request<T>('GET', path)
}

export async function post<T>(path: string, body?: unknown): Promise<T> {
  return request<T>('POST', path, body)
}

export async function del<T>(path: string): Promise<T> {
  return request<T>('DELETE', path)
}

export async function postSSE(
  path: string,
  body: unknown,
  onChunk: (text: string) => void,
  onDone: () => void,
  onError: (err: Error) => void,
): Promise<void> {
  const controller = new AbortController()
  const READ_TIMEOUT_MS = 60_000   // reset on each chunk — genuine stall only
  const MAX_TOTAL_MS = 300_000     // hard cap for entire stream (5 min)

  let readTimer: ReturnType<typeof setTimeout> | null = null
  let maxTimer: ReturnType<typeof setTimeout> | null = null
  let capturedTraceId: string | undefined = undefined

  function clearTimers() {
    if (readTimer !== null) { clearTimeout(readTimer); readTimer = null }
    if (maxTimer !== null) { clearTimeout(maxTimer); maxTimer = null }
  }

  function resetReadTimeout() {
    if (readTimer !== null) clearTimeout(readTimer)
    readTimer = setTimeout(() => {
      controller.abort()
      onError(new ApiError(0, `Stream stalled: no data for ${READ_TIMEOUT_MS / 1000}s`, null, capturedTraceId))
    }, READ_TIMEOUT_MS)
  }

  // Hard cap for the entire stream
  maxTimer = setTimeout(() => {
    controller.abort()
    onError(new ApiError(0, `Stream timed out after ${MAX_TOTAL_MS / 1000}s`, null, capturedTraceId))
  }, MAX_TOTAL_MS)

  // Start the per-chunk read timeout
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
          } catch { /* use raw text */ }
          onError(new ApiError(0, message, text, capturedTraceId))
        }
      }
      dataBuffer = []
    }

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

    while (true) {
      const { done, value } = await reader.read()

      // Reset the read timeout on every successful read from the network
      resetReadTimeout()

      if (done) {
        if (buffer.trim()) {
          const lines = buffer.split('\n')
          processLines(lines)
        }
        flushDataBuffer()
        onDone()
        return
      }

      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''

      if (processLines(lines)) {
        onDone()
        return
      }
    }
  } catch (err) {
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
