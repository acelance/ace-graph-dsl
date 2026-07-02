import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// `vite` / `vite --mode demo` 启动 demo 开发服务器；
// `vite build` 产出可发布的库 dist（ESM + 抽取的样式）。
export default defineConfig(({ command }) => {
  const isBuild = command === 'build'

  return {
    plugins: [vue()],
    server: {
      host: '127.0.0.1',
      port: 5173,
      proxy: {
        '/api': {
          target: 'http://127.0.0.1:8087',
          changeOrigin: true
        }
      }
    },
    build: isBuild
      ? {
          // 库构建：从 src/index.js 打包，外置 peer 依赖，避免宿主重复打包/解析冲突
          lib: {
            entry: fileURLToPath(new URL('./src/index.js', import.meta.url)),
            name: 'AceGraphDslUI',
            formats: ['es'],
            fileName: () => 'index.js'
          },
          cssCodeSplit: false,
          rollupOptions: {
            external: [
              'vue',
              'pinia',
              'axios',
              'element-plus',
              /^element-plus\/.*/,
              '@logicflow/core',
              /^@logicflow\/core\/.*/,
              '@logicflow/extension',
              /^@logicflow\/extension\/.*/
            ],
            output: {
              // 抽取的样式统一输出为 style.css，对应 package.json 的 "./style" 导出
              assetFileNames: (asset) => (asset.name && asset.name.endsWith('.css') ? 'style.css' : '[name][extname]')
            }
          }
        }
      : undefined
  }
})
