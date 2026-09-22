package com.example.mall.mapper;

import com.example.mall.entity.MemberAddress;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 收货地址数据访问接口。
 *
 * <h3>★ 这个接口里最重要的一条规矩：每个方法都必须带 memberId</h3>
 *
 * <p>你可能会觉得 {@code selectById(Long id)} 就够了 ——
 * 主键唯一，查出来当然是自己的。
 * <b>但那是错的，而且错得很危险。</b>
 *
 * <p>假设只有 {@code selectById(id)}，Service 里这么写：
 * <pre>
 *   MemberAddress addr = mapper.selectById(dto.getId());
 *   // 然后就用它去下单
 * </pre>
 * 那么会员 A 只要把请求里的 id 改成 B 的地址 id，
 * 就能用 <b>别人的收货地址</b>下自己的单。
 * 接口一切正常、日志一切正常，只有业务被薅了。
 * 这叫<b>水平越权</b>（同一个角色，看到别人的数据）。
 *
 * <p>正确的做法是让「归属」成为查询条件的一部分：
 * <pre>
 *   MemberAddress addr = mapper.selectByIdAndMember(id, currentMemberId());
 * </pre>
 * 这样即使 id 是别人的，SQL 也查不出东西，Service 会正常地报「地址不存在」。
 *
 * <p><b>为什么要在 Mapper 层就强制？</b>
 * 因为 Service 有十几个方法、未来还会加更多，
 * 靠「记得每次都校验一下归属」来保证安全，迟早会漏。
 * 把 memberId 做成<b>必填参数</b>，漏掉就是编译不过 ——
 * <b>让错误的写法写不出来，比让正确的写法更容易记住更可靠。</b>
 *
 * <p>这和 {@link com.example.mall.mapper.ProductMapper} 的
 * {@code selectShopById}「内部就带 status = 1」是同一个思路：
 * 把安全条件焊死在查询里，而不是指望调用方记得加。
 */
public interface MemberAddressMapper {

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /**
     * 查某个会员的全部地址。
     *
     * <p>排序：默认地址永远排第一，其余按 id 倒序（新加的在前）。
     * 让默认地址排在最前面，是为了让前端不用再自己排一遍 ——
     * 而且「默认地址在第一个」是用户的直觉预期。
     *
     * <p>{@code is_default DESC} 能直接排，因为 1 > 0。
     * 注意这里<b>不能</b>写成 {@code ORDER BY is_default = 1 DESC}，
     * 那样虽然也对，但多此一举。
     *
     * <p>地址数量很少（一般人不会存几十个），所以不分页。
     */
    List<MemberAddress> selectByMemberId(@Param("memberId") Long memberId);

    /**
     * 按 id 查地址，<b>并且必须属于这个会员</b>。
     *
     * <p>见类注释：这是防水平越权的关键 —— id 是别人的就查不出来。
     *
     * @return 查不到、或者不属于该会员，都返回 null
     */
    MemberAddress selectByIdAndMember(@Param("id") Long id,
                                      @Param("memberId") Long memberId);

    /**
     * 查某个会员的默认地址。
     *
     * <p>下单页要默认选中它。查不到返回 null（用户还没设过默认地址），
     * 由 Service 决定怎么处理 —— 注意这<b>不是</b>错误情况，
     * 一个刚注册的用户就是没有默认地址的。
     */
    MemberAddress selectDefaultByMemberId(@Param("memberId") Long memberId);

    /**
     * 数某个会员有几个地址。
     *
     * <p>用途：判断「这是不是他的第一个地址」——
     * 第一个地址应该自动成为默认地址，否则用户存了地址却发现下单时
     * 一个都没选中，还得手动去设一次默认，很别扭。
     */
    int countByMemberId(@Param("memberId") Long memberId);

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    /**
     * 新增地址，自增主键回填到入参对象。
     */
    int insert(MemberAddress address);

    /**
     * 更新地址。
     *
     * <p>★ 这个方法<b>不改 member_id</b>（XML 里刻意不写这一列）。
     * 地址的归属一旦确定就不该变 —— 允许改归属意味着
     * 「把地址转送给别人」，这是个没必要存在的功能，
     * 而且会给越权留一扇门。
     * <b>能通过「不提供这个能力」来消除的风险，就别用校验去防。</b>
     */
    int updateById(MemberAddress address);

    /**
     * 删除地址。<b>必须带 memberId</b>，理由同 {@link #selectByIdAndMember}。
     *
     * <p>如果只按 id 删，会员 A 就能删掉 B 的地址 ——
     * 而且删除是<b>不可逆</b>的，比查到别人的数据更严重。
     * 查别人的地址只是信息泄露，删别人的地址是直接破坏数据。
     *
     * @return 影响行数。0 表示 id 不存在或不属于该会员
     */
    int deleteByIdAndMember(@Param("id") Long id,
                            @Param("memberId") Long memberId);

    /**
     * 把某个会员的所有地址都设为「非默认」。
     *
     * <p>设为默认地址时要先调这个，再把自己置 1。
     * 两步配合起来保证「一个会员最多一个默认地址」。
     *
     * <p><b>为什么不用一条 SQL 搞定（CASE WHEN）？</b>
     * 因为一条 SQL 也解决不了「并发」—— 两个请求同时设不同的默认地址，
     * 无论写成一条还是两条，最终结果都取决于执行顺序。
     * 真正要保证唯一，得靠「先清空再设置」这个顺序本身，
     * 而它在同一事务里已经能挡住绝大多数问题。
     * 具体讨论见 {@code AddressServiceImpl.setDefault}。
     */
    int clearDefaultByMemberId(@Param("memberId") Long memberId);
}
