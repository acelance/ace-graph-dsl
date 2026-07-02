import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getMenuPermissions } from '../api/graph'

/** 标准菜单权限 key（与后端 GraphMenuPermissions 对齐） */
export const MENU = {
  GRAPH_VIEW: 'graph:view',
  GRAPH_CREATE: 'graph:create',
  GRAPH_SAVE: 'graph:save',
  GRAPH_VALIDATE: 'graph:validate',
  GRAPH_PREVIEW: 'graph:preview',
  GRAPH_PUBLISH: 'graph:publish',
  GRAPH_ROLLBACK: 'graph:rollback',
  SCRIPT_NODE_VIEW: 'script-node:view',
  SCRIPT_NODE_CREATE: 'script-node:create',
  SCRIPT_NODE_DELETE: 'script-node:delete',
  SCRIPT_NODE_TEST: 'script-node:test'
}

export const usePermissionStore = defineStore('aceMenuPermissions', () => {
  const grantedKeys = ref(new Set())
  const menus = ref([])
  const principal = ref(null)
  const loaded = ref(false)
  // 加载前/失败时默认放行，避免后端未接入权限时误隐藏功能
  const failOpen = ref(true)

  async function load() {
    try {
      const view = await getMenuPermissions()
      grantedKeys.value = new Set(view?.grantedKeys || [])
      menus.value = view?.menus || []
      principal.value = view?.principal || null
      failOpen.value = false
      loaded.value = true
    } catch {
      // 接口不可用（如旧后端）时回退为全部放行
      grantedKeys.value = new Set()
      menus.value = []
      principal.value = null
      failOpen.value = true
      loaded.value = true
    }
  }

  /** 是否拥有某菜单权限；未加载或加载失败时默认放行 */
  function can(key) {
    if (failOpen.value) return true
    return grantedKeys.value.has(key)
  }

  return { grantedKeys, menus, principal, loaded, failOpen, load, can, MENU }
})
