import { computed } from 'vue'
import { marked } from 'marked'

export function useMarkdown(text: () => string) {
  const renderedHtml = computed(() => {
    const raw = text()
    if (!raw) return ''

    // Convert literal "\n" strings to actual newlines
    let processed = raw.replace(/\\n/g, '\n')
    // Use double-space before newline for marked's breaks option
    processed = processed.replace(/\n/g, '  \n')

    const html = marked.parse(processed, {
      breaks: true,
      gfm: true,
    })

    return html as string
  })

  return {
    renderedHtml,
  }
}

export function renderMarkdownSync(text: string): string {
  if (!text) return ''
  let processed = text.replace(/\\n/g, '\n')
  processed = processed.replace(/\n/g, '  \n')
  return marked.parse(processed, { breaks: true, gfm: true }) as string
}
