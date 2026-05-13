<!--
  MarkdownRenderer：Markdown渲染引擎组件，负责将AI模型输出的Markdown文本转换为富HTML并进行DOM增强。

  渲染管线（5个阶段顺序执行）：
  1. LaTeX数学公式预渲染（renderLatex）：
     - 在Markdown解析前，用正则匹配数学公式（display: $$...$$ / \[...\]，inline: $...$ / \(...\)）
     - 调用KaTeX.renderToString生成HTML并用占位符替换原文本，防止Markdown解析器破坏LaTeX结构
     - 渲染失败时降级为katex-error样式包裹的HTML转义原文

  2. Markdown → HTML（Marked）：
     - 使用marked库将文本解析为HTML（GFM模式启用、硬换行禁用）
     - marked配置：gfm=true支持表格/任务列表，breaks=false保持标准换行语义

  3. XSS净化（DOMPurify）：
     - 对marked输出的HTML进行安全过滤，保留必要的class/target/rel属性
     - 允许del/s/input标签和ARIA/data属性，确保DOM增强不被清洗

  4. DOM增强（enhanceAll：在nextTick中执行）：
     - enhanceCodeBlocks：识别code块（含mermaid语言标记），包装code-block-wrapper容器，
       用highlight.js进行语法高亮，添加语言标签和复制按钮
     - enhanceMermaidBlocks：将mermaid代码块异步渲染为SVG图表并插入到DOM
     - wrapTables：将table元素包装在table-wrapper容器中实现水平滚动

  5. KaTeX占位符还原：
     - 将第1阶段插入的KATEX_BLOCK_*/KATEX_INLINE_*占位符替换回实际的KaTeX HTML

  Mermaid缓存机制（mermaidSvgCache）：
  - 使用Map<string, string>以代码内容为键缓存已渲染的SVG字符串
  - 流式输出中同一Mermaid图表在每次内容更新时不会重复调用mermaid.render()
  - 显著降低渲染开销，避免闪烁

  KaTeX占位符保护策略：
  - 数学公式先替换为唯一占位符ID，待marked+DOMPurify处理完后再还原
  - 防止marked误解析LaTeX语法（如下划线、花括号），防止DOMPurify移除数学公式HTML

  流式输出适配：
  - 流式输出中（isStreaming=true）：仅执行renderMarkdown生成HTML，不触发DOM增强
  - 流式停止时（isStreaming从true变false）：触发enhanceAll()进行完整DOM增强

  键盘交互：
  - 代码块Copy按钮：点击复制代码到剪贴板，2秒后恢复"Copy"文本
  - Mermaid图表工具栏：含mermaid标签 + SVG下载按钮 + PNG下载按钮

  注册的代码语言（highlight.js：22种语言 + 别名）：
  JavaScript/TypeScript、Python、Java、Bash/Shell、JSON、XML/HTML、YAML、Go/Golang、
  Rust、C/C++、SQL、CSS、Markdown、Diff、Nginx、Dockerfile、Plaintext、Properties/INI、Makefile
-->
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

/** 注册所有支持的编程语言及常用别名到highlight.js核心库 */
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

/**
 * 初始化Mermaid全局配置。
 * startOnLoad=false：禁止自动查找.mermaid类元素渲染，由enhanceMermaidBlocks手动控制
 * securityLevel='loose'：允许在SVG中使用HTML标签和样式
 * suppressErrorRendering=true：渲染失败时不显示Mermaid内置错误UI，使用自定义降级逻辑
 */
mermaid.initialize({
  startOnLoad: false,
  theme: 'default',
  securityLevel: 'loose',
  suppressErrorRendering: true,
})

/**
 * 配置marked解析器选项。
 * gfm=true：启用GitHub Flavored Markdown（表格、任务列表、删除线等扩展语法）
 * breaks=false：禁用单换行符转<br>，仅双换行视为段落分隔
 */
marked.use({
  gfm: true,
  breaks: false,
})

// eslint-disable-next-line @typescript-eslint/no-explicit-any
/**
 * DOMPurify净化配置：允许HTML属性和标签的白名单。
 * ADD_ATTR：允许class（代码高亮样式）、target/rel（链接安全属性）
 * ADD_TAGS：允许del/s（文本格式）、input（任务列表复选框）
 * ALLOW_ARIA_ATTR / ALLOW_DATA_ATTR：保留无障碍和数据属性
 */
const purifyConfig: any = {
  ADD_ATTR: ['class', 'target', 'rel'],
  ADD_TAGS: ['del', 's', 'input'],
  ALLOW_ARIA_ATTR: true,
  ALLOW_DATA_ATTR: true,
}

const props = defineProps<{
  /** 待渲染的原始Markdown文本 */
  content: string
  /** 是否正在流式输出：为true时跳过DOM增强以避免频繁DOM操作 */
  isStreaming?: boolean
}>()

/** 渲染容器的DOM引用，用于DOM增强时的querySelector操作 */
const rootRef = ref<HTMLElement | null>(null)
/** 经过渲染和净化后的HTML字符串，通过v-html绑定到模板 */
const renderedHtml = ref('')

// ── LaTeX 数学公式渲染 ──────────────────────────────────────────────

/** KaTeX占位符的自增ID计数器，每次renderLatex调用时重置 */
let katexPlaceholderId = 0

/**
 * LaTeX数学公式预渲染：在Markdown解析前将数学公式转换为KaTeX HTML并用占位符保护。
 *
 * 处理4种公式格式：
 * - 块级公式：$$...$$ 和 \[...\]（displayMode=true，居中显示）
 * - 行内公式：$...$ 和 \(...\)（displayMode=false，嵌入行内文本）
 *
 * 占位符策略：将每个公式替换为KATEX_BLOCK_N或KATEX_INLINE_N的唯一标识，
 * 待marked+DOMPurify处理完后再还原为真实HTML。防止Markdown解析器误解析
 * LaTeX语法（如下划线_可能被误认为斜体标记），防止DOMPurify移除数学HTML标签。
 *
 * 错误处理：KaTeX渲染失败时保留原文并用HTML转义包裹在katex-error代码块中，
 * 确保不因为单个公式错误影响整个文档渲染。
 *
 * @param text 包含LaTeX公式的原始Markdown文本
 * @returns 公式替换为占位符ID的文本
 */
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

  // 还原占位符：将KATEX_BLOCK_*/KATEX_INLINE_*替换回真实的KaTeX HTML或错误标记
  for (const [id, html] of placeholders) {
    result = result.replace(id, html)
  }

  return result
}

/**
 * HTML特殊字符转义：将&、<、>转换为对应HTML实体。
 * 用于KaTeX渲染失败时的降级显示，防止用户输入的HTML标签被执行。
 *
 * @param text 需要转义的原始文本
 * @returns HTML实体转义后的安全文本
 */
function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

// ── 代码块DOM增强 ──────────────────────────────────────────────

/**
 * 从HTML code/pre元素的class属性中提取编程语言标识。
 * HTML中由marked生成的格式为class="language-xxx"。
 *
 * @param block pre或code元素
 * @returns 提取的语言名称（小写），如"python"、"javascript"；无法识别时返回null
 */
function extractLang(block: Element): string | null {
  const cls = block.className
  const m = cls.match(/language-(\S+)/)
  return m ? m[1] : null
}

/**
 * 代码块DOM增强：为每个<pre><code>块包装code-block-wrapper容器，
 * 添加语言标签和复制按钮，调用highlight.js进行语法高亮。
 *
 * 结构转换（前→后）：
 *   <pre><code class="language-python">print("hello")</code></pre>
 * 变为：
 *   <div class="code-block-wrapper">
 *     <div class="code-block-header">
 *       <span class="code-block-lang">python</span>
 *       <button class="code-block-copy">Copy</button>
 *     </div>
 *     <pre><code class="language-python hljs">print("hello")</code></pre>
 *   </div>
 *
 * 特殊处理：
 * - mermaid语言块：包装但不语法高亮，后续由enhanceMermaidBlocks异步渲染SVG
 * - 已被增强过的块（父级有code-block-wrapper类）跳过，避免重复包装
 * - 语言标签无法识别时显示"text"
 *
 * 复制按钮交互：点击后复制代码纯文本到剪贴板，按钮文字切换为"Copied!"，
 * 2秒后恢复为"Copy"。复制失败时显示"Failed"。
 */
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

/**
 * 表格包装：为所有<table>元素包装table-wrapper容器以实现水平滚动。
 * 移动端或窄屏时表格可能超出内容宽度，wrapper提供overflow-x:auto滚动。
 * 已包装的表格跳过不重复包装。
 */
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

// ── Mermaid 图表渲染 ────────────────────────────────────────────

/**
 * 缓存已渲染Mermaid图表SVG的映射表。
 * 键为Mermaid代码的trim后文本，值为mermaid.render()生成的SVG字符串。
 * 流式输出中代码内容频繁变化，但相同图表只渲染一次，显著减少重复计算。
 */
const mermaidSvgCache = new Map<string, string>()

/**
 * Mermaid图表异步渲染：遍历所有pre元素，识别mermaid语言标记的代码块，
 * 调用mermaid.render()生成SVG图表并替换原始pre元素。
 *
 * 缓存策略：
 * - 先查找mermaidSvgCache，命中则直接使用缓存SVG
 * - 未命中则调用mermaid.render(id, code)异步渲染
 * - 渲染成功后将SVG存入缓存 + 调用replacePreWithSvg替换DOM
 *
 * 错误降级处理：
 * - mermaid.render()失败时，向上查找code-block-wrapper父节点
 * - 为wrapper添加mermaid-error类（CSS标记为错误状态）
 * - 将语言标签文本改为"mermaid (parse error)"提示用户语法错误
 * - 原始pre/code内容保留用于调试
 *
 * 使用Promise.all等待所有图表渲染完成（每个图表渲染独立互不影响）。
 */
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

/**
 * 用Mermaid SVG包装元素替换原始<pre>元素。
 *
 * 新DOM结构：
 *   <div class="mermaid-wrapper">
 *     <div class="mermaid-toolbar">
 *       <span class="mermaid-label">mermaid</span>
 *       <button class="mermaid-download-btn">SVG</button>
 *       <button class="mermaid-download-btn">PNG</button>
 *     </div>
 *     <div class="mermaid-svg">{svg内容}</div>
 *   </div>
 *
 * 父级处理：如果原始pre在code-block-wrapper中，则将wrapper也替换为mermaid-wrapper；
 * 如果pre直接在父节点中，则直接在pre之前插入mermaid-wrapper并移除pre。
 *
 * @param pre 原始<pre>元素，内部包含mermaid代码
 * @param svg mermaid.render()生成的SVG字符串
 */
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

/**
 * 下载Mermaid图表的SVG格式文件。
 * 创建Blob对象 → 生成Object URL → 触发<a>标签下载 → 清理URL。
 *
 * @param svg 完整的SVG字符串内容
 */
function downloadSvg(svg: string) {
  const blob = new Blob([svg], { type: 'image/svg+xml' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = 'diagram.svg'
  a.click()
  URL.revokeObjectURL(url)
}

/**
 * 下载Mermaid图表的PNG格式文件。
 * 流程：SVG字符串 → Blob → Image元素加载 → Canvas绘制（2倍分辨率）→ toBlob导出PNG → 触发下载。
 * 使用2倍缩放确保高清导出（视网膜屏幕友好）。
 *
 * @param svg 完整的SVG字符串内容
 */
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

// ── 渲染编排 ────────────────────────────────────────────────────

/**
 * 执行所有DOM增强操作（入口函数）。
 * 顺序执行：代码块增强 → Mermaid图表渲染（异步）→ 表格包装。
 * 在watch的nextTick回调中调用，确保v-html渲染的DOM已经就绪。
 */
function enhanceAll() {
  enhanceCodeBlocks()
  enhanceMermaidBlocks()
  wrapTables()
}

/**
 * Markdown渲染主函数：将原始Markdown文本转换为安全的HTML。
 *
 * 三阶段处理：
 * 1. renderLatex(text)：LaTeX公式预渲染为KaTeX HTML
 * 2. marked.parse(withLatex)：将Markdown解析为HTML
 * 3. DOMPurify.sanitize(raw, purifyConfig)：XSS安全净化
 *
 * @param text 原始Markdown文本（可能包含LaTeX公式）
 * @returns 安全的、包含数学公式HTML的富文本HTML字符串
 */
function renderMarkdown(text: string): string {
  if (!text) return ''
  const withLatex = renderLatex(text)
  const raw = marked.parse(withLatex) as string
  return DOMPurify.sanitize(raw, purifyConfig) as unknown as string
}

/**
 * 监听content属性变化：文本内容改变时重新渲染HTML并执行DOM增强。
 * immediate=true确保组件挂载时立即渲染初始内容（如历史消息回填）。
 * 每次内容变化都触发完整的渲染+增强流程。
 */
watch(
  () => props.content,
  (newContent) => {
    renderedHtml.value = renderMarkdown(newContent)
    nextTick(() => enhanceAll())
  },
  { immediate: true },
)

/**
 * 监听isStreaming属性变化：流式输出结束时（true→false）触发最终DOM增强。
 * 流式输出中不执行DOM增强是有意为之：每收到一个SSE分块就重构整个DOM开销过大，
 * 流式停止时一次性完成代码高亮、Mermaid渲染和表格包装是最高效的策略。
 */
watch(
  () => props.isStreaming,
  (streaming) => {
    if (!streaming) {
      nextTick(() => enhanceAll())
    }
  },
)
</script>

<template>
  <div ref="rootRef" class="markdown-content" v-html="renderedHtml"></div>
</template>

<style scoped>
.markdown-content {
  color: var(--color-body);
  line-height: var(--body-md-line-height);
  word-wrap: break-word;
  overflow-wrap: break-word;
}
</style>
