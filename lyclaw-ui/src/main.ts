/**
 * LyClaw前端应用入口文件，负责创建Vue应用实例、安装核心插件并挂载到DOM。
 *
 * 启动流程分为以下步骤：
 * 1. 导入根组件App.vue：整个应用的组件树从App开始
 * 2. 创建Pinia实例（createPinia）：全局状态管理，所有Store通过Pinia注册
 * 3. 安装Pinia插件（app.use）：使得所有组件可以通过useXxxStore()访问状态
 * 4. 安装Vue Router插件（app.use）：启用路由导航和页面切换
 * 5. 挂载到#app元素（app.mount）：将Vue应用渲染到index.html中的根DOM节点
 *
 * Pinia作为Vue 3官方推荐的状态管理库，相比Vuex具有以下优势：
 * - 完整的TypeScript类型推断支持
 * - 无需mutation，直接修改state
 * - 支持多个Store实例，天然的代码分割
 * - DevTools集成，支持时间旅行调试
 *
 * 注意：全局CSS样式（base.css）由App.vue通过import导入，
 * 而非在此处导入，确保样式在组件树建立后正确应用CSS变量。
 */
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.mount('#app')
