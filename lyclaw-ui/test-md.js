const { marked } = require('marked');
const createDOMPurify = require('dompurify');
const { JSDOM } = require('jsdom');

marked.use({ gfm: true, breaks: true });

const window = new JSDOM('').window;
const purify = createDOMPurify(window);

const purifyConfig = {
  ADD_ATTR: ['class', 'target', 'rel'],
  ADD_TAGS: ['del', 's', 'input'],
  ALLOW_ARIA_ATTR: true,
  ALLOW_DATA_ATTR: true,
};

const text = `## 二级标题

### 三级标题

- 列表项1
- 列表项2

1. 有序1
2. 有序2

` + '```python\nprint("hello")\n```';

const raw = marked.parse(text);
console.log('===RAW HTML===');
console.log(raw);
console.log('');
const clean = purify.sanitize(raw, purifyConfig);
console.log('===CLEAN HTML===');
console.log(clean);
