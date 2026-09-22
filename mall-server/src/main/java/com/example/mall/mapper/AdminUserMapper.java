package com.example.mall.mapper;

import com.example.mall.entity.AdminUser;
import org.apache.ibatis.annotations.Param;

/**
 * 管理员数据访问接口。
 *
 * <p>本次只实现登录需要的两个查询。管理员的增删改（后台加账号、改密码）
 * 属于运营功能，不在当前里程碑范围内 —— 需要时再加，
 * <b>不要为了「完整性」提前写一堆用不上的方法</b>。
 * 没人调用的代码不会有 bug 暴露，但会一直需要维护，
 * 而且会让后来的人以为它很重要。
 */
public interface AdminUserMapper {

    /**
     * 根据登录账号查管理员，登录时用。
     *
     * <p>这是整个项目里<b>唯一一个会查出 password 字段</b>的方法。
     * 其他任何查询管理员的地方都不该带上密码 ——
     * 用不上的敏感字段就不该从数据库里读出来，
     * 少读一次就少一次泄露的机会。
     *
     * @return 查不到返回 null
     */
    AdminUser selectByUsername(@Param("username") String username);

    /**
     * 根据 id 查管理员，用于前端刷新页面后恢复登录状态。
     *
     * <p>注意这里<b>不查 password</b>。登录校验以外的场景都不需要它。
     *
     * @return 查不到返回 null
     */
    AdminUser selectById(@Param("id") Long id);
}
