import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import '@logicflow/core/dist/index.css'
import '@logicflow/extension/lib/style/index.css'
import { GraphDslManager } from '../src/index.js'

createApp(GraphDslManager).use(createPinia()).use(ElementPlus).mount('#app')
