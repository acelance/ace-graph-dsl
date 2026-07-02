import { defineStore } from 'pinia'
import { ref } from 'vue'
import { listNodes, listDispatchers } from '../api/graph'

export const useNodeRegistryStore = defineStore('aceNodeRegistry', () => {
  const nodes = ref([])
  const dispatchers = ref([])
  const loading = ref(false)

  async function fetchNodes() {
    loading.value = true
    try {
      nodes.value = await listNodes()
    } finally {
      loading.value = false
    }
  }

  async function fetchDispatchers() {
    dispatchers.value = await listDispatchers()
  }

  return { nodes, dispatchers, loading, fetchNodes, fetchDispatchers }
})
