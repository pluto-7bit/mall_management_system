import request from './request'

/**
 * 商品评价（管理端）。
 *
 * <p>baseURL 是 {@code /api}，所以下面两个路径最终打到
 * {@code /api/admin/reviews}。
 *
 * <h3>★★ 这个文件里最显眼的特征是【只有两个函数，而且没有 create】</h3>
 *
 * <p>评价是<b>会员在用户端产生的</b>：他要先下单、付款、收货，
 * 然后才能给那条订单明细写一条评价。管理员在这一头
 * <b>既不能替谁写一条评价，也不能改任何一条已有的</b> ——
 * 用户确认过的设计是「一次定终身」。
 *
 * <p>所以管理端对评价只有两个动作：<b>看</b> 和 <b>删</b>。
 * 「接口的数量反映业务上真实存在的动作，而不是一个资源标配 CRUD」
 * —— 这句话在后端 {@code ReviewAdminController} 的类注释里写过一遍，
 * 这里是从调用方看过去的同一件事。
 *
 * <p>⚠️ 也<b>没有 update</b>，理由同上。虽然用户端那两条评论里
 * 反复讲的「不可修改」是针对会员的，但那条规矩对管理员同样成立：
 * 「删掉再让他重写」和「直接改成我想写的」是两件不同的事，
 * 而只有前者是合理的 —— <b>后者等于让管理员伪造了一条用户评价</b>。
 *
 * <h3>★ 管理端能看到 {@code username}，用户端看不到</h3>
 *
 * <p>列表返回的每一项里有 {@code memberUsername} 和 {@code memberNickname}，
 * 而用户端的评价列表<b>只给昵称</b>（连 username 都没有）。
 *
 * <p>这不是不一致，判据是「<b>看这个接口的人是谁</b>」：
 * 管理员本来就在订单列表里看得到 username（他得靠它找人），
 * 而商品详情页是<b>任何人都能打开</b>的 ——
 * 在那里暴露登录账号等于把用户名给所有访客。
 *
 * <p>⚠️ 注意也<b>没有 phone</b>。用户端的 {@code MemberInfoVO.phone}
 * 那句「只返回昵称」的预言在里程碑 12 兑现时，管理端也没给它 ——
 * 「管理员看得到的」和「管理员需要看到的」是两件事。
 */

/**
 * 分页查询评价。
 *
 * <p>{@code GET /api/admin/reviews}
 *
 * <p>★ 两个筛选条件都是<b>模糊匹配</b>，这一点和订单列表刻意不同
 * （那边 {@code orderNo} 是精确匹配）。区别在「管理员手里有什么」：
 * <pre>
 *   订单号是一个【唯一标识】—— 用户报单号来问，要的就是那一单，
 *                              模糊会返回一堆，反而要人工挑
 *   商品 / 会员 —— 管理员记得的是「那个 iPhone」和「张三」，
 *                 手里【没有】id、也没有完整的名字，只能模糊
 * </pre>
 * 所以这里是 {@code LIKE '%关键词%'}，两个框都是。
 *
 * <p>★ 两个条件<b>可以同时用</b>，是 AND 关系
 * （「张三买的那个 iPhone 他说了什么」）。服务端那边写成子查询而不是
 * join，正是为了让列表和 count 共用同一段条件 —— 否则会出现
 * 「表格里 5 条、分页器说总共 3 条」。
 *
 * @param {Object} params { pageNum, pageSize, productKeyword, memberKeyword }
 * @returns {Promise<{list: object[], total: number, pageNum: number,
 *                    pageSize: number, pages: number}>}
 */
export function getReviewList(params) {
  return request.get('/admin/reviews', { params })
}

/**
 * 删除一条评价。
 *
 * <p>{@code DELETE /api/admin/reviews/{id}}
 *
 * <p>⚠️⚠️ <b>这是物理删除，而且它有一个不显眼但真实的后果：
 * 那条订单明细的「唯一索引槽位」被释放了，会员可以重新评价。</b>
 *
 * <p>后端 {@code product_review.order_item_id} 上有唯一索引
 * （「一个明细只能评一次」这条不变量的唯一真正保证）。删掉那一行，
 * 索引里对应的槽位就空了 —— 于是那个会员回到「我的订单」，
 * 会看到那条明细的按钮从「已评价」变回「评价」。
 *
 * <p>★ 这个语义是<b>合理的</b>（被删掉的违规评价不该永久剥夺
 * 他重写的权利），但它必须被知道 —— 所以后端把它写进了注释，
 * 测试里有一条用例专门锁它。这里再写一遍是因为
 * <b>点这个按钮的人是他唯一一个会看到这个后果的人</b>。
 *
 * <p>⚠️ 另一个后果：<b>磁盘上的晒图文件不会被删</b>，
 * 会成为 {@code uploads/} 里的孤儿文件（本项目已知的、写出来的取舍，
 * 商品删除也一样）。
 *
 * @param {number} id 评价 id
 */
export function deleteReview(id) {
  return request.delete(`/admin/reviews/${id}`)
}
