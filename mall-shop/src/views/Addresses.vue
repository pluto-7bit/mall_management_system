<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  deleteAddress,
  listAddresses,
  setDefaultAddress,
} from '@/api/address'
import AddressFormDialog from '@/components/AddressFormDialog.vue'

/**
 * 收货地址管理页。
 *
 * <h3>★ 为什么地址簿要是一个独立的页面/资源？</h3>
 *
 * <p>因为地址<b>不是订单的一部分</b>。用户在完全没打算买东西的时候
 * 也会想维护它：「我搬家了，把地址改一下」。
 *
 * <p>如果地址只能在下单流程里填，就会有三件麻烦事：
 * <ul>
 *   <li>每下一单都要重填一遍（或者从上次的订单里"复制"，
 *       但订单里存的是<b>快照</b>，改订单不会改地址簿 —— 越用越乱）</li>
 *   <li>下单页会变得很长（一边挑商品一边填地址）</li>
 *   <li>「我有两个收货地址换着用」这种需求没法表达</li>
 * </ul>
 *
 * <p><b>★ 这就是「把地址抽成独立资源」的价值：一旦它独立了，
 * 订单只需要引用它的 id（{@code addressId}），
 * 而地址怎么增删改就和下单流程完全脱钩了。</b>
 * 代价是多了一个页面、一张表、一套接口 —— 值得。
 *
 * <h3>本地状态从哪来</h3>
 *
 * <p>连改动之后的刷新都很简单：<b>每次操作完重新拉一次完整列表</b>。
 * 因为「谁是默认」这件事只有服务端算得准 ——
 * 设某条为默认会同时把原来那条取消掉（两条记录一起变），
 * 删掉默认地址还会自动提升另一条。这些都不是"本地改一个字段"能表达的。
 *
 * <p>这和 {@code Cart.vue} 的做法是同一个思路，也是同一个理由。
 */

const router = useRouter()

const addresses = ref([])
const loading = ref(false)
const loadFailed = ref(false)

/** 正在操作的那条地址 id —— 给「设为默认」按钮加 loading */
const busyId = ref(null)

const dialogVisible = ref(false)
/** null = 新增；有值 = 编辑那一条 */
const editing = ref(null)

async function load() {
  loading.value = true
  loadFailed.value = false
  try {
    // ★ 后端已经把默认地址排在最前面了，前端【不再排一遍】。
    //   排序规则属于业务，只该有一处定义 ——
    //   前端再排一次，就是两个地方各自维护"什么算更靠前"，
    //   迟早有一天两边不一致，用户看到页面顺序和服务端不一致
    addresses.value = await listAddresses()
  } catch {
    loadFailed.value = true
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  dialogVisible.value = true
}

function openEdit(addr) {
  // ⚠️ 传的是【整条地址对象】，不是 id。
  //   因为修改接口是全量替换，表单必须拿到全部字段才能提交。
  //   如果这里只传 id，表单就得自己去查一遍 ——
  //   多一次请求，而且数据可能已经变了
  editing.value = addr
  dialogVisible.value = true
}

async function handleSetDefault(addr) {
  busyId.value = addr.id
  try {
    await setDefaultAddress(addr.id)
    ElMessage.success('已设为默认地址')
    await load()
  } catch {
    // 提示已由 request.js 弹出
  } finally {
    busyId.value = null
  }
}

/**
 * 删除地址。
 *
 * <p>⚠️ 这里必须弹二次确认，而且提示语要说清楚后果 ——
 * 因为删除的代价不只是"少了一条"：
 * <b>如果删的是默认地址，后端会自动把另一条提升为默认。</b>
 * 用户可能期望"删掉默认之后就没有默认了"，实际不是。
 * 把这句话写在确认框里，比等他删完发现默认地址换人了要友好。
 */
async function handleDelete(addr) {
  const isDefault = addr.isDefault === 1
  const tip = isDefault
    ? '这是你的默认地址。删除后，系统会自动把另一条设为默认（如果还有的话）。确定删除吗？'
    : `确定要删除「${addr.receiver} ${addr.phone}」这条地址吗？`

  try {
    await ElMessageBox.confirm(tip, '删除收货地址', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }

  try {
    await deleteAddress(addr.id)
    ElMessage.success('已删除')
    await load()
  } catch {
    // 提示已由 request.js 弹出
  }
}

/** 表单弹窗里保存成功后 —— 重新拉列表 */
function onSaved() {
  load()
}

function goBack() {
  router.back()
}

// 路由上标了 requiresAuth，走到这里一定是登录状态
onMounted(load)
</script>

<template>
  <div class="page-container">
    <div class="head">
      <h2 class="page-title">收货地址</h2>
      <el-button type="primary" @click="openCreate">
        <el-icon><Plus /></el-icon>
        <span>新增地址</span>
      </el-button>
    </div>

    <div v-loading="loading" class="list-wrap">
      <el-result
        v-if="loadFailed"
        icon="error"
        title="加载失败"
        sub-title="网络或服务端出了点问题"
      >
        <template #extra>
          <el-button type="primary" @click="load">重新加载</el-button>
        </template>
      </el-result>

      <!--
        空状态。★ 这里的文案是有讲究的：
        用户可能是【从结算页被"赶"过来的】——发现一个地址都没有，
        不知道接下来该干嘛。所以除了"还没有地址"，还要给一个出口按钮
      -->
      <el-empty
        v-else-if="!addresses.length && !loading"
        description="还没有收货地址"
      >
        <el-button type="primary" @click="openCreate">添加第一个地址</el-button>
      </el-empty>

      <template v-else>
        <el-card
          v-for="addr in addresses"
          :key="addr.id"
          shadow="never"
          class="addr-card"
        >
          <div class="addr-main">
            <div class="addr-line-1">
              <span class="receiver">{{ addr.receiver }}</span>
              <span class="phone">{{ addr.phone }}</span>
              <!--
                ★ 默认标记。用 tag 而不是给整张卡片换色 ——
                默认只是个属性，不是"这条更重要"
              -->
              <el-tag v-if="addr.isDefault === 1" type="danger" size="small" effect="plain">
                默认
              </el-tag>
            </div>
            <div class="addr-line-2">
              {{ addr.region }} {{ addr.detail }}
            </div>
          </div>

          <div class="addr-actions">
            <el-button
              v-if="addr.isDefault !== 1"
              text
              type="primary"
              :loading="busyId === addr.id"
              @click="handleSetDefault(addr)"
            >
              设为默认
            </el-button>
            <el-button text @click="openEdit(addr)">修改</el-button>
            <el-button text type="danger" @click="handleDelete(addr)">删除</el-button>
          </div>
        </el-card>

        <div class="foot">
          <el-button text @click="goBack">← 返回上一页</el-button>
        </div>
      </template>
    </div>

    <AddressFormDialog
      v-model="dialogVisible"
      :address="editing"
      @saved="onSaved"
    />
  </div>
</template>

<style scoped>
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 18px;
}

.page-title {
  margin: 0;
  font-size: 20px;
  color: #303133;
}

.list-wrap {
  min-height: 260px;
}

.addr-card {
  /* 圆角交给 --el-card-border-radius，见 theme.css。
     ★ 这个 el-card 里【不能】再加包裹层 —— 下面那条 :deep()
       是把 .el-card__body 当直接布局容器用的（左右分栏），
       中间插一个 div 进去，分栏立刻就散架了 */
  margin-bottom: 12px;
}

/* 卡片内部：左边信息、右边按钮 */
.addr-card :deep(.el-card__body) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.addr-main {
  min-width: 0;
}

.addr-line-1 {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 6px;
}

.receiver {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}

.phone {
  font-size: 14px;
  color: #606266;
}

.addr-line-2 {
  font-size: 13px;
  color: #909399;
  line-height: 1.6;
  word-break: break-all;
}

.addr-actions {
  flex-shrink: 0;
}

.foot {
  margin-top: 20px;
}
</style>
