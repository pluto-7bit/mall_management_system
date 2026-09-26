<script setup>
/**
 * 退款/售后 —— 我的售后单列表。
 *
 * <p>和「我的订单」是两条链路：这里只读售后单自己的状态，
 * 不看订单状态。列表项已经带齐了全部字段（后端没有详情接口）。
 *
 * <p>★ 这里【不】显示「预计退款金额」—— 退款金额由服务端在退款那一刻算
 * （运费退不退取决于之后整单是否退完）。申请阶段能确定的只有货款 =
 * 明细小计，所以文案写「货款 ¥x」，不写「将退 ¥x」。
 */
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { cancelAfterSale, listMyAfterSales, submitReturnInfo } from '@/api/afterSale'
import {
  AFTER_SALE_STATUS,
  AFTER_SALE_STATUS_OPTIONS,
  afterSaleReasonLabel,
  afterSaleStatusLabel,
  afterSaleStatusTagType,
  afterSaleTypeLabel,
} from '@/utils/afterSaleStatus'
import { payMethodLabel } from '@/utils/orderStatus'
import { formatAmount } from '@/utils/format'

const PAGE_SIZE = 5

const loading = ref(false)
const list = ref([])
const total = ref(0)
const pageNum = ref(1)

/** null = 全部。和后端约定：不传 status 表示不筛（传 0 表示只看待审核） */
const statusFilter = ref(null)

/** 以 afterSaleNo 为键，避免一个动作把整页按钮都置灰 */
const acting = ref({})

const returnVisible = ref(false)
const returnSubmitting = ref(false)
const returnForm = reactive({
  afterSaleNo: '',
  productName: '',
  returnCompany: '',
  returnTracking: '',
})

/** 只有「待审核」能撤销 —— 后端 T7 的 WHERE status = 0 才是真正的闸门 */
function canCancel(row) {
  return row.status === AFTER_SALE_STATUS.APPLIED
}

async function load() {
  loading.value = true
  try {
    const params = { pageNum: pageNum.value, pageSize: PAGE_SIZE }
    if (statusFilter.value !== null) {
      params.status = statusFilter.value
    }
    const data = await listMyAfterSales(params)
    list.value = data.list || []
    total.value = data.total || 0
  } catch {
    // 失败时清空：留着上次的数据会让人以为"搜到了这些"
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function changePage(page) {
  pageNum.value = page
  load()
}

function changeStatus() {
  pageNum.value = 1
  load()
}

/**
 * 撤销之后本页可能空了 —— 页码回退，和 Orders.vue 是同一个补丁。
 */
function backOffIfLastRow() {
  if (list.value.length === 1 && pageNum.value > 1) {
    pageNum.value -= 1
  }
}

async function handleCancel(row) {
  try {
    await ElMessageBox.confirm(
      `确定要撤销售后单 ${row.afterSaleNo} 吗？撤销后可以重新申请。`,
      '撤销申请',
      { type: 'warning', confirmButtonText: '撤销', cancelButtonText: '再想想' },
    )
  } catch {
    return
  }

  acting.value[row.afterSaleNo] = true
  try {
    await cancelAfterSale(row.afterSaleNo)
    ElMessage.success('已撤销')
    backOffIfLastRow()
    await load()
  } finally {
    acting.value[row.afterSaleNo] = false
  }
}

function openReturn(row) {
  returnForm.afterSaleNo = row.afterSaleNo
  returnForm.productName = row.productName
  returnForm.returnCompany = ''
  returnForm.returnTracking = ''
  returnVisible.value = true
}

async function submitReturn() {
  if (!returnForm.returnCompany.trim() || !returnForm.returnTracking.trim()) {
    ElMessage.warning('快递公司和单号都要填')
    return
  }

  returnSubmitting.value = true
  try {
    await submitReturnInfo(
      returnForm.afterSaleNo,
      returnForm.returnCompany.trim(),
      returnForm.returnTracking.trim(),
    )
    ElMessage.success('已提交，等待商家收货')
    returnVisible.value = false
    await load()
  } finally {
    returnSubmitting.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="after-sale-page">
    <div class="page-head">
      <h2>退款/售后</h2>
      <el-select
        v-model="statusFilter"
        class="status-filter"
        placeholder="全部状态"
        @change="changeStatus"
      >
        <el-option
          v-for="opt in AFTER_SALE_STATUS_OPTIONS"
          :key="String(opt.value)"
          :label="opt.label"
          :value="opt.value"
        />
      </el-select>
    </div>

    <div v-loading="loading" class="list-area">
      <el-empty v-if="!loading && list.length === 0" description="暂无售后单" />

      <div v-for="row in list" :key="row.afterSaleNo" class="as-card">
        <div class="card-head">
          <span class="as-no">售后单号：{{ row.afterSaleNo }}</span>
          <el-tag :type="afterSaleStatusTagType(row.status)" size="small" effect="light">
            {{ afterSaleStatusLabel(row.status) }}
          </el-tag>
        </div>

        <div class="card-meta">
          <span>订单号：{{ row.orderNo }}</span>
          <span>申请时间：{{ row.createTime }}</span>
          <span>类型：{{ afterSaleTypeLabel(row.type) }}</span>
        </div>

        <div class="goods">
          <div class="goods-name">
            {{ row.productName }}
            <!-- skuSpec 是空字符串（非 null），所以用真假值判断就够 -->
            <span v-if="row.skuSpec" class="goods-spec">{{ row.skuSpec }}</span>
          </div>
          <div class="goods-qty">× {{ row.quantity }}</div>
          <div class="goods-money">货款 ¥{{ formatAmount(row.subtotal) }}</div>
        </div>

        <div class="card-meta">
          <span>申请原因：{{ afterSaleReasonLabel(row.reason) }}</span>
          <span v-if="row.description">说明：{{ row.description }}</span>
        </div>

        <!--
          ★ 只有退款完成的行才显示退款信息。refundAmount 是 DEFAULT NULL（申请时不写），
            所以判断必须用真假值，不能依赖 key 存在。
        -->
        <div v-if="row.status === AFTER_SALE_STATUS.REFUNDED" class="result-block">
          <div>退款金额：¥{{ formatAmount(row.refundAmount) }}</div>
          <!-- refundFreight 是 NOT NULL DEFAULT 0.00，所以用 > 0 判断而不是真假值 -->
          <div v-if="row.refundFreight > 0">
            其中运费：¥{{ formatAmount(row.refundFreight) }}
          </div>
          <div>退款去向：{{ payMethodLabel(row.refundMethod) }}（原路退回）</div>
          <div>退款时间：{{ row.refundTime }}</div>
        </div>

        <div v-else-if="row.status === AFTER_SALE_STATUS.REJECTED" class="result-block">
          <div>拒绝理由：{{ row.rejectReason }}</div>
        </div>

        <div v-else-if="row.status === AFTER_SALE_STATUS.WAITING_RETURN" class="result-block">
          <div>商家已同意退货，请把商品寄回并填写快递单号。</div>
        </div>

        <div v-else-if="row.status === AFTER_SALE_STATUS.WAITING_RECEIVE" class="result-block">
          <div>寄回物流：{{ row.returnCompany }} {{ row.returnTracking }}</div>
          <div>填写时间：{{ row.returnTime }}</div>
          <div>等待商家确认收货后退款。</div>
        </div>

        <div v-if="canCancel(row)" class="card-actions">
          <el-button
            size="small"
            :loading="acting[row.afterSaleNo]"
            @click="handleCancel(row)"
          >
            撤销申请
          </el-button>
          <!--
            ★ 只有「待买家寄回」时买家能动。状态 2 之后货已经在路上，
              撤销（T7）只允许 status = 0 —— 前端不显示入口，后端 WHERE 才是闸门。
          -->
        </div>

        <div v-if="row.status === AFTER_SALE_STATUS.WAITING_RETURN" class="card-actions">
          <el-button type="primary" size="small" @click="openReturn(row)">
            填写寄回单号
          </el-button>
        </div>
      </div>
    </div>

    <div v-if="total > PAGE_SIZE" class="pager">
      <el-pagination
        layout="prev, pager, next"
        :current-page="pageNum"
        :page-size="PAGE_SIZE"
        :total="total"
        background
        @current-change="changePage"
      />
    </div>

    <el-dialog v-model="returnVisible" title="填写寄回物流" width="420px">
      <p class="dialog-tip">商品：{{ returnForm.productName }}</p>
      <el-form label-width="80px">
        <el-form-item label="快递公司">
          <el-input v-model="returnForm.returnCompany" maxlength="50" placeholder="如：顺丰" />
        </el-form-item>
        <el-form-item label="快递单号">
          <el-input v-model="returnForm.returnTracking" maxlength="64" placeholder="如：SF1234567890" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="returnVisible = false">取消</el-button>
        <el-button type="primary" :loading="returnSubmitting" @click="submitReturn">
          提交
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.after-sale-page {
  max-width: 900px;
  margin: 0 auto;
  padding: 16px;
}

.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.page-head h2 {
  margin: 0;
  font-size: 20px;
}

.status-filter {
  width: 150px;
}

.list-area {
  min-height: 200px;
}

.as-card {
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  padding: 12px 16px;
  margin-bottom: 12px;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 8px;
  border-bottom: 1px solid #f2f6fc;
}

.as-no {
  font-weight: 600;
  font-size: 14px;
}

.card-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  color: #909399;
  font-size: 12px;
  margin-top: 8px;
}

.goods {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 10px;
  font-size: 14px;
}

.goods-name {
  flex: 1;
  min-width: 0;
}

.goods-spec {
  color: #909399;
  font-size: 12px;
  margin-left: 6px;
}

.goods-money {
  color: #f56c6c;
}

.result-block {
  margin-top: 10px;
  padding: 8px 10px;
  background: #fafafa;
  border-radius: 4px;
  font-size: 13px;
  color: #606266;
  line-height: 1.9;
}

.card-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 10px;
}

.pager {
  display: flex;
  justify-content: center;
  margin-top: 16px;
}

.dialog-tip {
  margin: 0 0 12px;
  color: #909399;
  font-size: 13px;
}
</style>
