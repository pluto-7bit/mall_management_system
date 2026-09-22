<script setup>
import { ref, onMounted } from 'vue'
import request from '@/api/request'

/**
 * 首页 —— 里程碑 1 的验收页。
 *
 * 它调用后端的 /api/health/ping，把返回结果展示出来。
 * 这个页面能正常显示数据，就说明下面这条完整链路全通了：
 *
 *   浏览器(5173) → Vite 代理 → 后端(8080) → Controller → MySQL
 *
 * 任何一环出问题（代理没配、后端没启动、数据库连不上），这个页面都会报错，
 * 所以它是排查环境问题最快的手段。
 */

const loading = ref(false)
const connected = ref(false)
const healthInfo = ref(null)

async function checkHealth() {
  loading.value = true
  try {
    // 注意这里没有写 /api 前缀 —— 因为 axios 实例的 baseURL 已经配了，
    // 而且响应拦截器已经把外层包装剥掉了，所以拿到的直接就是 data 部分
    healthInfo.value = await request.get('/health/ping')
    connected.value = true
  } catch {
    // 具体错误信息拦截器已经弹过提示了，这里只需要把状态标记为失败
    connected.value = false
    healthInfo.value = null
  } finally {
    loading.value = false
  }
}

// onMounted：组件挂载完成后执行，相当于「页面打开就自动跑一次」
onMounted(checkHealth)
</script>

<template>
  <div class="home">
    <el-alert
      v-if="connected"
      title="环境检查通过"
      type="success"
      description="前端 → 代理 → 后端 → 数据库，整条链路已打通。"
      show-icon
      :closable="false"
    />

    <el-alert
      v-else-if="!loading"
      title="连接失败"
      type="error"
      description="请确认后端已启动（mall-server，端口 8080），以及 MySQL 服务正在运行。"
      show-icon
      :closable="false"
    />

    <el-card v-loading="loading" class="health-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span>后端健康检查</span>
          <el-button type="primary" size="small" :loading="loading" @click="checkHealth">
            重新检测
          </el-button>
        </div>
      </template>

      <el-descriptions v-if="healthInfo" :column="1" border>
        <el-descriptions-item label="服务状态">
          <el-tag type="success">{{ healthInfo.status }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="应用名称">
          {{ healthInfo.application }}
        </el-descriptions-item>
        <el-descriptions-item label="商品数量">
          {{ healthInfo.productCount }} 条
        </el-descriptions-item>
      </el-descriptions>

      <el-empty v-else-if="!loading" description="暂无数据" />
    </el-card>
  </div>
</template>

<style scoped>
.home {
  max-width: 720px;
  margin: 0 auto;
}

.health-card {
  margin-top: 16px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
</style>
