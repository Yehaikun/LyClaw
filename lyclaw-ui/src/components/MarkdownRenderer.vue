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
import '@/assets/styles/markdown.css'

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

marked.use({
  gfm: true,
  breaks: true,
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
}>()

const rootRef = ref<HTMLElement | null>(null)
const renderedHtml = ref('')

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
    const code = pre.querySelector('code')
    if (!code) return

    const lang = code instanceof HTMLElement && code.className
      ? extractLang(code)
      : null

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

function enhanceAll() {
  enhanceCodeBlocks()
  wrapTables()
}

function renderMarkdown(text: string): string {
  if (!text) return ''
  const raw = marked.parse(text) as string
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
</script>

<style scoped>
.markdown-content {
  color: var(--color-body);
  line-height: var(--body-md-line-height);
  word-wrap: break-word;
  overflow-wrap: break-word;
}
</style>
