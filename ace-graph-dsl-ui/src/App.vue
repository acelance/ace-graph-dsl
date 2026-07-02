<script setup>
import { onMounted } from 'vue'
import { useNodeRegistryStore } from './stores/nodeRegistry'
import Designer from './components/Designer/index.vue'

const nodeStore = useNodeRegistryStore()

onMounted(() => {
  nodeStore.fetchNodes()
  nodeStore.fetchDispatchers()
})
</script>

<template>
  <Designer />
</template>

<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
html, body, #app { height: 100%; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; }
</style>
