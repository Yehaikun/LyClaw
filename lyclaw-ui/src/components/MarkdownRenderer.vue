<template>
  <div ref="rootRef" class="markdown-content" v-html="renderedHtml"></div>
</template>

<script setup lang="ts">
import { ref, watch, nextTick } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import hljs from 'highlight.js/lib/core'
import javascript from 'highlight.js/lib/languages/javascript'
import typescript from 'highlight.js/lib/languages/typescript'
import python from 'highlight.js/lib/languages/python'
import java from 'highlight.js/lib/languages/java'
import bash from 'highlight.js/lib/languages/bash'
import json from 'highlight.js/lib/languages/json'
import xml from 'highlight.js/lib/languages/xml'
import yaml from 'highlight.js/lib/languages/yaml'
import go from 'highlight.js/lib/languages/go'
import rust from 'highlight.js/lib/languages/rust'
import cpp from 'highlight.js/lib/languages/cpp'
import c from 'highlight.js/lib/languages/c'
import sql from 'highlight.js/lib/languages/sql'
import css from 'highlight.js/lib/languages/css'
import markdown from 'highlight.js/lib/languages/markdown'
import shell from 'highlight.js/lib/languages/shell'
import diff from 'highlight.js/lib/languages/diff'
import nginx from 'highlight.js/lib/languages/nginx'
import dockerfile from 'highlight.js/lib/languages/dockerfile'
import plaintext from 'highlight.js/lib/languages/plaintext'
import properties from 'highlight.js/lib/languages/properties'
import makefile from 'highlight.js/lib/languages/makefile'
import mermaid from 'mermaid'
import katex from 'katex'
import '@/assets/styles/markdown.css'
import 'highlight.js/styles/github.css'
import 'katex/dist/katex.min.css'

hljs.registerLanguage('javascript', javascript)
hljs.registerLanguage('js', javascript)
hljs.registerLanguage('typescript', typescript)
hljs.registerLanguage('ts', typescript)
hljs.registerLanguage('python', python)
hljs.registerLanguage('py', python)
hljs.registerLanguage('java', java)
hljs.registerLanguage('bash', bash)
hljs.registerLanguage('sh', shell)
hljs.registerLanguage('shell', shell)
hljs.registerLanguage('json', json)
hljs.registerLanguage('xml', xml)
hljs.registerLanguage('html', xml)
hljs.registerLanguage('yaml', yaml)
hljs.registerLanguage('yml', yaml)
hljs.registerLanguage('go', go)
hljs.registerLanguage('golang', go)
hljs.registerLanguage('rust', rust)
hljs.registerLanguage('rs', rust)
hljs.registerLanguage('c', c)
hljs.registerLanguage('cpp', cpp)
hljs.registerLanguage('c++', cpp)
hljs.registerLanguage('sql', sql)
hljs.registerLanguage('css', css)
hljs.registerLanguage('markdown', markdown)
hljs.registerLanguage('md', markdown)
hljs.registerLanguage('diff', diff)
hljs.registerLanguage('nginx', nginx)
hljs.registerLanguage('dockerfile', dockerfile)
hljs.registerLanguage('plaintext', plaintext)
hljs.registerLanguage('text', plaintext)
hljs.registerLanguage('properties', properties)
hljs.registerLanguage('ini', properties)
hljs.registerLanguage('makefile', makefile)

mermaid.initialize({
  startOnLoad: false,
  theme: 'default',
  securityLevel: 'loose',
  suppressErrorRendering: true,
})

marked.use({
  gfm: true,
  breaks: false,
})

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const purifyConfig: any = {
  ADD_ATTR: ['class', 'target', 'rel'],
  ADD_TAGS: ['del', 's', 'input'],
  ALLOW_ARIA_ATTR: true,
  ALLOW_DATA_ATTR: true,
}

const props = defineProps<{
  content: string
  isStreaming?: boolean
}>()

const rootRef = ref<HTMLElement | null>(null)
const renderedHtml = ref('')

// ── LaTeX 渲染 ──────────────────────────────────────────────

let katexPlaceholderId = 0

function renderLatex(text: string): string {
  katexPlaceholderId = 0
  const placeholders: Map<string, string> = new Map()

  // 保护 display math：$$...$$ 和 \[...\]
  let result = text.replace(/\$\$([\s\S]*?)\$\$/g, (_match, formula: string) => {
    const id = `KATEX_BLOCK_${katexPlaceholderId++}`
    try {
      const html = katex.renderToString(formula.trim(), {
        displayMode: true,
        throwOnError: false,
        trust: true,
      })
      placeholders.set(id, html)
    } catch {
      placeholders.set(id, `<code class="katex-error">${escapeHtml(formula)}</code>`)
    }
    return id
  })

  result = result.replace(/\\\[([\s\S]*?)\\\]/g, (_match, formula: string) => {
    const id = `KATEX_BLOCK_${katexPlaceholderId++}`
    try {
      const html = katex.renderToString(formula.trim(), {
        displayMode: true,
        throwOnError: false,
        trust: true,
      })
      placeholders.set(id, html)
    } catch {
      placeholders.set(id, `<code class="katex-error">${escapeHtml(formula)}</code>`)
    }
    return id
  })

  // 保护 inline math：$...$ 和 \(...\)
  result = result.replace(/\$([^\s$][^$]*?)\$/g, (_match, formula: string) => {
    const id = `KATEX_INLINE_${katexPlaceholderId++}`
    try {
      const html = katex.renderToString(formula.trim(), {
        displayMode: false,
        throwOnError: false,
        trust: true,
      })
      placeholders.set(id, html)
    } catch {
      placeholders.set(id, `<code class="katex-error">${escapeHtml(formula)}</code>`)
    }
    return id
  })

  result = result.replace(/\\\(([\s\S]*?)\\\)/g, (_match, formula: string) => {
    const id = `KATEX_INLINE_${katexPlaceholderId++}`
    try {
      const html = katex.renderToString(formula.trim(), {
        displayMode: false,
        throwOnError: false,
        trust: true,
      })
      placeholders.set(id, html)
    } catch {
      placeholders.set(id, `<code class="katex-error">${escapeHtml(formula)}</code>`)
    }
    return id
  })

  // 还原占位符
  for (const [id, html] of placeholders) {
    result = result.replace(id, html)
  }

  return result
}
function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

// ── 代码块增强 ──────────────────────────────────────────────

function extractLang(block: Element): string | null {
  const cls = block.className
  const m = cls.match(/language-(\S+)/)
  return m ? m[1] : null
}

function enhanceCodeBlocks() {
  if (!rootRef.value) return
  const pres = rootRef.value.querySelectorAll('pre')
  pres.forEach((pre) => {
    if (pre.parentElement?.classList.contains('code-block-wrapper')) return
    if (pre.parentElement?.classList.contains('mermaid-wrapper')) return
    if (pre.parentElement?.classList.contains('mermaid-error')) return
    const code = pre.querySelector('code')
    if (!code) return

    const lang = code instanceof HTMLElement && code.className
      ? extractLang(code)
      : null

    if (lang === 'mermaid') {
      const wrapper = document.createElement('div')
      wrapper.className = 'code-block-wrapper'

      const header = document.createElement('div')
      header.className = 'code-block-header'

      const langLabel = document.createElement('span')
      langLabel.className = 'code-block-lang'
      langLabel.textContent = 'mermaid'

      header.appendChild(langLabel)
      wrapper.appendChild(header)
      pre.parentNode!.insertBefore(wrapper, pre)
      wrapper.appendChild(pre)
      return
    }

    hljs.highlightElement(code as HTMLElement)

    const wrapper = document.createElement('div')
    wrapper.className = 'code-block-wrapper'

    const header = document.createElement('div')
    header.className = 'code-block-header'

    const langLabel = document.createElement('span')
    langLabel.className = 'code-block-lang'
    langLabel.textContent = lang || 'text'

    const copyBtn = document.createElement('button')
    copyBtn.className = 'code-block-copy'
    copyBtn.textContent = 'Copy'
    copyBtn.onclick = () => {
      const text = code.textContent || ''
      navigator.clipboard.writeText(text).then(() => {
        copyBtn.textContent = 'Copied!'
        setTimeout(() => { copyBtn.textContent = 'Copy' }, 2000)
      }).catch(() => {
        copyBtn.textContent = 'Failed'
        setTimeout(() => { copyBtn.textContent = 'Copy' }, 2000)
      })
    }

    header.appendChild(langLabel)
    header.appendChild(copyBtn)
    wrapper.appendChild(header)
    pre.parentNode!.insertBefore(wrapper, pre)
    wrapper.appendChild(pre)
  })
}

function wrapTables() {
  if (!rootRef.value) return
  const tables = rootRef.value.querySelectorAll('table')
  tables.forEach((table) => {
    if (table.parentElement?.classList.contains('table-wrapper')) return
    const wrapper = document.createElement('div')
    wrapper.className = 'table-wrapper'
    table.parentNode!.insertBefore(wrapper, table)
    wrapper.appendChild(table)
  })
}

// ── Mermaid 图表 ────────────────────────────────────────────

// 缓存已渲染的 SVG，避免每次 DOM 更新都重新调用 mermaid.render()
const mermaidSvgCache = new Map<string, string>()

async function enhanceMermaidBlocks() {
  if (!rootRef.value) return
  const pres = rootRef.value.querySelectorAll('pre')
  const tasks: Promise<void>[] = []

  pres.forEach((pre) => {
    if (pre.parentElement?.classList.contains('mermaid-wrapper')) return
    if (pre.parentElement?.classList.contains('mermaid-error')) return
    const code = pre.querySelector('code')
    if (!code) return

    const lang = code instanceof HTMLElement && code.className
      ? extractLang(code)
      : null

    if (lang !== 'mermaid') return

    const mermaidCode = code.textContent || ''
    const cacheKey = mermaidCode.trim()

    const cachedSvg = mermaidSvgCache.get(cacheKey)
    if (cachedSvg) {
      replacePreWithSvg(pre, cachedSvg)
      return
    }

    const id = 'mermaid-' + Math.random().toString(36).slice(2, 10)

    const task = mermaid.render(id, mermaidCode).then(({ svg }) => {
      mermaidSvgCache.set(cacheKey, svg)
      replacePreWithSvg(pre, svg)
    }).catch((err: Error) => {
      console.warn('Mermaid render failed:', err.message)
      // 降级：保留 code-block-wrapper，标记为错误状态
      let outerWrapper: HTMLElement | null = null
      let parent: Node | null = pre.parentNode
      while (parent && parent instanceof HTMLElement) {
        if (parent.classList.contains('code-block-wrapper')) {
          outerWrapper = parent
          break
        }
        parent = parent.parentNode
      }
      if (outerWrapper) {
        outerWrapper.classList.add('mermaid-error')
        const langLabel = outerWrapper.querySelector('.code-block-lang')
        if (langLabel) {
          langLabel.textContent = 'mermaid (parse error)'
        }
      }
    })

    tasks.push(task)
  })

  await Promise.all(tasks)
}

function replacePreWithSvg(pre: HTMLPreElement, svg: string) {
  let outerWrapper: HTMLElement | null = null
  let parent: Node | null = pre.parentNode
  while (parent && parent instanceof HTMLElement) {
    if (parent.classList.contains('code-block-wrapper')) {
      outerWrapper = parent
      break
    }
    parent = parent.parentNode
  }

  const wrapper = document.createElement('div')
  wrapper.className = 'mermaid-wrapper'

  const toolbar = document.createElement('div')
  toolbar.className = 'mermaid-toolbar'

  const label = document.createElement('span')
  label.className = 'mermaid-label'
  label.textContent = 'mermaid'

  const downloadSvgBtn = document.createElement('button')
  downloadSvgBtn.className = 'mermaid-download-btn'
  downloadSvgBtn.textContent = 'SVG'
  downloadSvgBtn.title = '下载 SVG'
  downloadSvgBtn.onclick = () => downloadSvg(svg)

  const downloadPngBtn = document.createElement('button')
  downloadPngBtn.className = 'mermaid-download-btn'
  downloadPngBtn.textContent = 'PNG'
  downloadPngBtn.title = '下载 PNG'
  downloadPngBtn.onclick = () => downloadPng(svg)

  toolbar.appendChild(label)
  toolbar.appendChild(downloadSvgBtn)
  toolbar.appendChild(downloadPngBtn)

  const svgContainer = document.createElement('div')
  svgContainer.className = 'mermaid-svg'
  svgContainer.innerHTML = svg

  wrapper.appendChild(toolbar)
  wrapper.appendChild(svgContainer)

  if (outerWrapper) {
    outerWrapper.parentNode!.insertBefore(wrapper, outerWrapper)
    outerWrapper.remove()
  } else {
    pre.parentNode!.insertBefore(wrapper, pre)
    pre.remove()
  }
}

function downloadSvg(svg: string) {
  const blob = new Blob([svg], { type: 'image/svg+xml' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = 'diagram.svg'
  a.click()
  URL.revokeObjectURL(url)
}

function downloadPng(svg: string) {
  const canvas = document.createElement('canvas')
  const ctx = canvas.getContext('2d')
  if (!ctx) return

  const img = new Image()
  const blob = new Blob([svg], { type: 'image/svg+xml' })
  const url = URL.createObjectURL(blob)

  img.onload = () => {
    canvas.width = img.width * 2
    canvas.height = img.height * 2
    ctx.scale(2, 2)
    ctx.drawImage(img, 0, 0)
    URL.revokeObjectURL(url)

    canvas.toBlob((pngBlob) => {
      if (!pngBlob) return
      const pngUrl = URL.createObjectURL(pngBlob)
      const a = document.createElement('a')
      a.href = pngUrl
      a.download = 'diagram.png'
      a.click()
      URL.revokeObjectURL(pngUrl)
    }, 'image/png')
  }

  img.src = url
}

// ── 编排 ────────────────────────────────────────────────────

function enhanceAll() {
  enhanceCodeBlocks()
  enhanceMermaidBlocks()
  wrapTables()
}

function renderMarkdown(text: string): string {
  if (!text) return ''
  const withLatex = renderLatex(text)
  const raw = marked.parse(withLatex) as string
  return DOMPurify.sanitize(raw, purifyConfig) as unknown as string
}

watch(
  () => props.content,
  (newContent) => {
    renderedHtml.value = renderMarkdown(newContent)
    nextTick(() => enhanceAll())
  },
  { immediate: true },
)

watch(
  () => props.isStreaming,
  (streaming) => {
    if (!streaming) {
      nextTick(() => enhanceAll())
    }
  },
)
</script>

<style scoped>
.markdown-content {
  color: var(--color-body);
  line-height: var(--body-md-line-height);
  word-wrap: break-word;
  overflow-wrap: break-word;
}
</style>
