package com.example.mall.mapper;

import com.example.mall.entity.Member;
import org.apache.ibatis.annotations.Param;

/**
 * 会员数据访问接口。
 *
 * <p>只实现注册和登录需要的四个方法。会员的增删改查（管理端会员列表、
 * 修改资料）属于里程碑 10 之后的事，<b>现在不写</b>。
 */
public interface MemberMapper {

    /**
     * 根据登录账号查会员，登录时用。
     *
     * <p>和 {@code AdminUserMapper.selectByUsername} 一样，
     * 这是会员这边<b>唯一</b>会查出 password 的方法。
     *
     * @return 查不到返回 null
     */
    Member selectByUsername(@Param("username") String username);

    /**
     * 根据 id 查会员，用于前端刷新页面后恢复登录状态。
     *
     * <p>不查 password —— 用不上的敏感字段就不该从数据库读出来。
     */
    Member selectById(@Param("id") Long id);

    /**
     * 统计某个账号的会员数量，注册时用来查重。
     *
     * <p><b>为什么返回 long 而不是 boolean？</b>
     * 因为 SQL 只能数出个数，转成 boolean 是在 Java 里做的。
     * 保持 Mapper 层「数据库返回什么就映射成什么」，
     * 判断语义（{@code > 0}）留给 Service —— 那里才知道
     * 「大于 1」意味着数据出了问题，而 Mapper 不需要知道这些。
     */
    long countByUsername(@Param("username") String username);

    /**
     * 新增会员，注册时用。
     *
     * <p>XML 里配了 {@code useGeneratedKeys}，
     * 插入成功后自增主键会被写回参数的 {@code id} 字段。
     * 所以调用方在 insert 之后就能直接 {@code member.getId()} 拿到新 id ——
     * 注册接口要靠它签 token。
     */
    int insert(Member member);
}
