const BASE_URL = ''
const DEFAULT_TIMEOUT_MS = 30_000

export class ApiError extends Error {
  public status: number
  public body: unknown

  constructor(status: number, message: string, body: unknown) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.body = body
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

  function clearTimers() {
    if (readTimer !== null) { clearTimeout(readTimer); readTimer = null }
    if (maxTimer !== null) { clearTimeout(maxTimer); maxTimer = null }
  }

  function resetReadTimeout() {
    if (readTimer !== null) clearTimeout(readTimer)
    readTimer = setTimeout(() => {
      controller.abort()
      onError(new ApiError(0, `Stream stalled: no data for ${READ_TIMEOUT_MS / 1000}s`, null))
    }, READ_TIMEOUT_MS)
  }

  // Hard cap for the entire stream
  maxTimer = setTimeout(() => {
    controller.abort()
    onError(new ApiError(0, `Stream timed out after ${MAX_TOTAL_MS / 1000}s`, null))
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
      )
      onError(err)
      return
    }

    const reader = response.body?.getReader()
    if (!reader) {
      onError(new Error('Response body is not readable'))
      return
    }

    const decoder = new TextDecoder()
    let buffer = ''
    let currentEvent = ''

    function processLines(lines: string[]): boolean {
      for (const line of lines) {
        if (line.startsWith('event:')) {
          currentEvent = line.slice(6).trim()
          if (currentEvent === 'done') {
            return true // signal completion
          }
        } else if (line.startsWith('data:')) {
          const data = line.slice(5).trim()
          if (data === '[DONE]') {
            return true
          }
          if (data && currentEvent === 'message') {
            onChunk(data)
          }
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
      onError(new ApiError(0, `SSE stream aborted`, null))
    } else if (err instanceof ApiError) {
      onError(err)
    } else {
      onError(err as Error)
    }
  } finally {
    clearTimers()
  }
}
