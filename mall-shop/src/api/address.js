import request from './request'

/**
 * 收货地址接口。
 *
 * <p>路径都在 {@code /api/shop/addresses/**} 下面，<b>全部需要登录</b>。
 * 和 {@code cart.js} 一样，后端没有把这块排除出 {@code MemberAuthInterceptor}。
 *
 * <h3>★ 为什么「地址」值得单独一个 API 模块？</h3>
 *
 * <p>因为它是一个<b>完整的、独立的资源</b>：有列表、有新增、有修改、
 * 有删除、还有一个「默认」这样的状态。它不是「订单的一部分」——
 * 用户在没事的时候（还没打算买任何东西）也会想维护自己的地址簿。
 *
 * <p>这一点在设计上的体现是：<b>下单接口只收一个 {@code addressId}，
 * 不收收货人姓名电话地址。</b>收货信息是地址簿的数据，
 * 订单只是「引用」了它 —— 引用关系用一个 id 表达就够了。
 * 如果下单接口要求前端把收货人、电话、地址都传一遍，
 * 那前端就可以随便填（比如填别人的地址），而且用户每下单一笔
 * 就要重填一次，地址簿也就没有存在意义了。
 *
 * <p>详见 {@code OrderBaseDTO} 的注释：「身份」和「金额」这两类信息，
 * 永远不由客户端提供。收货地址属于「身份」那一类。
 */

/**
 * 查询我的所有地址。
 *
 * <p>返回一个数组，<b>默认地址排在最前面</b>（后端排好的，
 * 前端不要再排一遍 —— 排序规则属于业务，只该有一处定义）。
 *
 * <p>没有地址时返回<b>空数组，不是 null</b>，所以调用方可以直接
 * {@code forEach}，不用先判空。
 */
export function listAddresses() {
  return request.get('/shop/addresses')
}

/**
 * 查默认地址。
 *
 * <p>⚠️ {@code data} 可能是 {@code null} —— 一个地址都没有的会员
 * 调这个接口是<b>正常情况</b>，不是错误，所以后端返回
 * 200 + data 为 null，而不是 404。
 *
 * <p>调用方必须处理 null。下面封装了一个 {@link getDefaultAddressOrNull}，
 * 它把「没有默认地址」这个正常状态统一成一个可判断的值，
 * 免得每个调用点各写一套判断。
 */
export function getDefaultAddress() {
  return request.get('/shop/addresses/default')
}

/**
 * 查默认地址，没有就返回 {@code null}。
 *
 * <p>为什么要在 API 层包这一层？因为对调用方来说，
 * 「后端返回了 data: null」和「后端返回了一个对象」是两种形状，
 * 直接用在模板里就要写成 {@code addr ? addr.receiver : '请选择' }。
 * 包一层之后语义明确：<b>这个函数永远不会抛异常，只会返回对象或 null</b>。
 *
 * <p>⚠️ 注意它和 {@link getDefaultAddress} 的区别不是「有没有 try/catch」——
 * 网络故障该抛还是要抛（调用方要能区分「没有地址」和「请求失败」）。
 * 它只是把 null 这种"合法的空"整理出来。
 */
export async function getDefaultAddressOrNull() {
  const addr = await getDefaultAddress()
  return addr || null
}

/**
 * 新增地址。
 *
 * <p>返回新地址的 id（<b>数据库生成的，前端事先不知道</b>，
 * 所以这个接口必须返回数据，不像「修改」那样返回 null）。
 *
 * @param {object} body {receiver, phone, region, detail, isDefault}
 */
export function createAddress(body) {
  return request.post('/shop/addresses', body)
}

/**
 * 修改地址 —— <b>★ 全量替换，不是局部更新！</b>
 *
 * <p>四个文本字段 {@code receiver / phone / region / detail}
 * 必须<b>全部传</b>，少传一个后端就是 400。所以调用方必须先有一份
 * 完整的地址数据（比如从列表里拿到的那条），改掉要改的字段，
 * 整体提交。
 *
 * <p>这是 {@code PUT} 这个词本身的意思。为什么会设计成这样、
 * 以及「只改一个字段」的需求由谁满足，见
 * {@code AddressService.update} 的注释（结论：「设为默认」有独立接口，
 * 所以剩下的场景都是用户在编辑表单里改了完整的一遍）。
 *
 * <p>返回 {@code Result<Void>}，也就是 {@code data} 是 null ——
 * 前端刚提交上去的就是完整数据，服务端没有"新东西"要告诉它。
 *
 * @param {number} id
 * @param {object} body 必须包含全部四个文本字段
 */
export function updateAddress(id, body) {
  return request.put(`/shop/addresses/${id}`, body)
}

/**
 * 把某个地址设为默认。
 *
 * <p><b>★ 单独一个接口，而不是「改 isDefault 字段」。</b>
 * 因为这背后不只是「这条记录的 isDefault 变成 1」——
 * 还要<b>把原来那条默认的取消掉</b>。两步操作必须在一个事务里，
 * 而且要保证「默认地址有且只有一个」。
 *
 * <p>如果允许前端通过「修改接口」把 isDefault 设成 1，
 * 前端就得自己负责先去取消旧的默认 —— 那是把一条业务规则
 * 交给客户端执行，客户端可以不做（于是就出现两个默认地址）。
 * <b>凡是「有多条数据要一起改」的操作，都该是一个独立接口。</b>
 *
 * <p>返回 null。前端手上已经有全部数据了（哪条是默认它知道），
 * 但 ⚠️ 列表的<b>顺序</b>会变（默认排最前），所以调用方
 * 应该重新拉一次列表，而不是只改本地那条记录的状态。
 */
export function setDefaultAddress(id) {
  return request.put(`/shop/addresses/${id}/default`)
}

/**
 * 删除地址。
 *
 * <p><b>幂等</b>：删一个不存在的 id 也返回成功。
 *
 * <p>★ 但有件事值得知道：如果删掉的是默认地址，
 * <b>后端会自动把另一条提升为默认</b>（见 {@code AddressServiceImpl.delete}）。
 * 所以删完之后要重新拉列表 —— 不是只有"少了一条"这么简单，
 * 「谁是默认」这件事也变了。
 */
export function deleteAddress(id) {
  return request.delete(`/shop/addresses/${id}`)
}
