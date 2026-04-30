<template>
  <div class="chat-wrap">
    <div class="chat-header">
      <span class="logo">&#9670; LyClaw</span>
      <span class="subtitle">AI 调度引擎</span>
    </div>

    <div class="chat-box" ref="chatBox">
      <div v-for="(msg, i) in messages" :key="i" class="msg-row" :class="msg.role">
        <div class="avatar">{{ msg.role === 'user' ? 'U' : 'A' }}</div>
        <div class="bubble">
          <div class="name">{{ msg.role === 'user' ? 'You' : 'Assistant' }}</div>
          <div class="content" v-html="renderMarkdown(msg.content)"></div>
        </div>
      </div>

      <div v-if="streaming" class="msg-row assistant">
        <div class="avatar">A</div>
        <div class="bubble">
          <div class="name">Assistant</div>
          <div class="content" v-html="renderMarkdown(currentOutput) + cursorHtml"></div>
        </div>
      </div>

      <div v-if="messages.length === 0 && !streaming" class="empty-hint">
        &#128161; 发送一条消息开始对话
      </div>
    </div>

    <div class="input-area">
      <textarea
        v-model="input"
        :disabled="streaming"
        placeholder="输入消息后按 Enter 发送..."
        @keydown.enter.prevent="send"
        rows="1"
      ></textarea>
      <button :disabled="streaming || !input.trim()" @click="send">
        <span v-if="!streaming">&#10148;</span>
        <span v-else class="spinner"></span>
      </button>
    </div>
  </div>
</template>

<script setup>
import { ref, nextTick, onMounted } from 'vue'
import { marked } from 'marked'

const API_BASE = 'http://192.168.3.90:8080'

const input = ref('')
const messages = ref([])
const currentOutput = ref('')
const streaming = ref(false)
const chatBox = ref(null)
let sessionId = null

function scrollToBottom() {
  nextTick(() => {
    if (chatBox.value) {
      chatBox.value.scrollTop = chatBox.value.scrollHeight
    }
  })
}

function decodeContent(raw) {
  if (!raw) return ''
  try {
    return raw.replace(/\\u([0-9a-fA-F]{4})/g, (_, hex) => String.fromCharCode(parseInt(hex, 16)))
  } catch {
    return raw
  }
}

// 渲染 markdown，将 \n 转成 <br>（marked 的 breaks 需要两个空格+换行才生效，不够直接）
function renderMarkdown(text) {
  if (!text) return ''
  // 先对文本做预处理：把单独的 \n（前后不是空格的）转成两个空格+\n，让 marked 的 breaks 生效
  // 更直接的办法：渲染完 marked 之后把 <p> 里的 \n 手动换成 <br>
  // 最佳方案：直接替换 \n 为 <br>，再走 marked

  // 方案：把文本里的 \n 替换为两个空格+\n，触发 marked 的 breaks 换行
  let processed = text
  // 确保每个 \n 都有两个空格前缀，这样 breaks:true 才会换行
  processed = processed.replace(/\n(?!\n)/g, '  \n')

  const html = marked.parse(processed, { breaks: true, gfm: true })
  return html
}

const cursorHtml = '<span class="cursor">|</span>'

async function send() {
  const text = input.value.trim()
  if (!text || streaming.value) return

  messages.value.push({ role: 'user', content: text })
  input.value = ''
  currentOutput.value = ''
  streaming.value = true
  scrollToBottom()

  try {
    const res = await fetch(`${API_BASE}/api/chat/stream`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        sessionId: sessionId,
        messages: [{ role: 'user', content: text }]
      })
    })

    if (!res.ok) {
      throw new Error(`HTTP ${res.status}`)
    }

    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })

      // 按 data: 前缀行解析，支持 \n 或 \n\n 作为行分隔
      // \n 可能出现在 markdown 内容中，所以找 data: 前缀更可靠
      const lines = buffer.split('\n')
      // 最后一行可能不完整，保留到下次
      buffer = lines.pop() || ''

      for (const line of lines) {
        const trimmed = line.trim()
        if (!trimmed) continue
        if (trimmed.startsWith('data:')) {
          const chunk = trimmed.slice(5).trim()
          if (chunk === '[DONE]') continue
          currentOutput.value += decodeContent(chunk)
          scrollToBottom()
        }
      }
    }

    // 处理 buffer 中剩余的未完成行
    if (buffer.trim().startsWith('data:')) {
      const chunk = buffer.trim().slice(5).trim()
      if (chunk !== '[DONE]' && chunk) {
        currentOutput.value += decodeContent(chunk)
        scrollToBottom()
      }
    }
  } catch (err) {
    currentOutput.value = `[错误] ${err.message}`
  }

  messages.value.push({ role: 'assistant', content: currentOutput.value })
  streaming.value = false
  scrollToBottom()
}

onMounted(() => {
  sessionId = crypto.randomUUID()
})
</script>

<style scoped>
* {
  box-sizing: border-box;
}

.chat-wrap {
  max-width: 740px;
  margin: 20px auto;
  display: flex;
  flex-direction: column;
  height: calc(100vh - 80px);
  background: #f0f2f5;
  border-radius: 16px;
  box-shadow: 0 4px 24px rgba(0,0,0,0.08);
  overflow: hidden;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
}

.chat-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px 20px;
  background: #1a1a2e;
  color: #fff;
}

.logo {
  font-size: 18px;
  font-weight: 700;
  letter-spacing: 0.5px;
}

.subtitle {
  font-size: 12px;
  color: #8892b0;
}

.chat-box {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  background: #f0f2f5;
}

.empty-hint {
  text-align: center;
  color: #999;
  margin-top: 40%;
  font-size: 15px;
}

.msg-row {
  display: flex;
  gap: 10px;
  margin-bottom: 16px;
  align-items: flex-start;
}

.msg-row.user {
  flex-direction: row-reverse;
}

.avatar {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 600;
  color: #fff;
  flex-shrink: 0;
}

.msg-row.assistant .avatar {
  background: #52c41a;
}

.msg-row.user .avatar {
  background: #1677ff;
}

.bubble {
  max-width: 75%;
}

.name {
  font-size: 11px;
  color: #999;
  margin-bottom: 3px;
  padding-left: 4px;
}

.content {
  padding: 10px 14px;
  border-radius: 12px;
  font-size: 14px;
  line-height: 1.7;
  word-break: break-word;
}

.msg-row.assistant .content {
  background: #fff;
  color: #333;
  border-bottom-left-radius: 4px;
  box-shadow: 0 1px 4px rgba(0,0,0,0.06);
}

.msg-row.user .content {
  background: #1677ff;
  color: #fff;
  border-bottom-right-radius: 4px;
  box-shadow: 0 1px 4px rgba(0,0,0,0.08);
}

.msg-row.user .name {
  text-align: right;
  padding-right: 4px;
}

/* markdown 样式 */
.content :deep(p) {
  margin: 4px 0;
}
.content :deep(p:first-child) {
  margin-top: 0;
}
.content :deep(p:last-child) {
  margin-bottom: 0;
}
.content :deep(ul), .content :deep(ol) {
  padding-left: 20px;
  margin: 4px 0;
}
.content :deep(li) {
  margin: 2px 0;
}
.content :deep(code) {
  background: #f0f0f0;
  padding: 1px 5px;
  border-radius: 4px;
  font-size: 13px;
}
.content :deep(pre) {
  background: #1e1e1e;
  color: #d4d4d4;
  padding: 12px;
  border-radius: 8px;
  overflow-x: auto;
  font-size: 13px;
  margin: 6px 0;
}
.content :deep(pre code) {
  background: none;
  padding: 0;
  color: inherit;
}
.content :deep(strong) {
  font-weight: 600;
}
.content :deep(a) {
  color: #1677ff;
  text-decoration: none;
}
.content :deep(a:hover) {
  text-decoration: underline;
}
.content :deep(blockquote) {
  border-left: 3px solid #52c41a;
  margin: 8px 0;
  padding: 4px 12px;
  color: #666;
  background: #f9f9f9;
  border-radius: 4px;
}
.content :deep(h1), .content :deep(h2), .content :deep(h3) {
  margin: 10px 0 4px;
  font-size: 15px;
  font-weight: 600;
}
.content :deep(h1) { font-size: 17px; }
.content :deep(h2) { font-size: 16px; }
.content :deep(h3) { font-size: 15px; }
.content :deep(br) {
  display: block;
  content: '';
  margin: 4px 0;
}
.content :deep(hr) {
  border: none;
  border-top: 1px solid #e0e0e0;
  margin: 10px 0;
}

.cursor {
  animation: blink 0.9s step-end infinite;
  color: #52c41a;
  font-weight: 300;
}

@keyframes blink {
  50% { opacity: 0; }
}

.input-area {
  display: flex;
  gap: 8px;
  padding: 12px 16px;
  background: #fff;
  border-top: 1px solid #e8e8e8;
}

textarea {
  flex: 1;
  border: 1px solid #ddd;
  border-radius: 10px;
  padding: 10px 14px;
  font-size: 14px;
  resize: none;
  outline: none;
  transition: border-color 0.2s;
  font-family: inherit;
  line-height: 1.5;
  min-height: 42px;
  max-height: 120px;
}

textarea:focus {
  border-color: #1677ff;
}

button {
  width: 42px;
  height: 42px;
  border: none;
  border-radius: 50%;
  background: #1677ff;
  color: #fff;
  font-size: 18px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.2s;
  flex-shrink: 0;
}

button:hover:not(:disabled) {
  background: #0958d9;
}

button:disabled {
  background: #ccc;
  cursor: not-allowed;
}

.spinner {
  width: 16px;
  height: 16px;
  border: 2px solid rgba(255,255,255,0.3);
  border-top-color: #fff;
  border-radius: 50%;
  display: block;
  animation: spin 0.6s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}
</style>
