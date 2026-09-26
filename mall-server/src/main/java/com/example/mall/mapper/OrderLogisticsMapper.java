package com.example.mall.mapper;

import com.example.mall.entity.OrderLogistics;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 物流轨迹节点的读写。★ 里程碑 18 新增。
 *
 * <p>只有三个方法，而且<b>刻意没有 update</b> —— 轨迹不提供编辑，
 * 理由见 {@code OrderLogistics} 的类注释（一条被改过的轨迹不是轨迹）。
 * 改错的做法是删掉重录。
 *
 * <h3>★ 这个 Mapper 里【没有一行是带 member_id 的】</h3>
 *
 * <p>它是全项目唯一一个<b>完全不涉及会员隔离</b>的 Mapper，
 * 原因和 {@code OrderAdminMapper} 不同，比那个更硬：
 *
 * <p><b>权限判断不在这里，也不该在这里。</b>
 * 「这一单是不是你的」是<b>订单级</b>的事实，由调用方
 * （{@code LogisticsServiceImpl}）先拿订单查出来、
 * 查不到就抛 1003；到了这个 Mapper 手上时，
 * 手里已经是一个<b>被授权过的 {@code orderId}</b> 了。
 *
 * <p>把这个判断塞进每条 SQL 的 WHERE 里，意味着
 * 「查轨迹」「加轨迹」「删轨迹」三处各要 join 一次 {@code orders}
 * 才能碰到 {@code member_id} —— 三份可能分岔的实现，
 * 而分岔的症状是「漏了一处 → 别人能读你的物流」。
 * <b>一个判断只该有一个定义者。</b>
 */
public interface OrderLogisticsMapper {

    /**
     * 新增一个轨迹节点。
     *
     * <p>★ 不用 {@code useGeneratedKeys} 回填 id：调用方写完就重新查一次
     * （要拿最新的完整列表返回给前端），回填的那个 id 没人读。
     *
     * <p>★ {@code create_time} 不在这里出现 —— 交给数据库的
     * {@code DEFAULT CURRENT_TIMESTAMP}。理由同订单：
     * 「这一条什么时候录进来的」应该由数据库的时钟说了算。
     */
    int insert(OrderLogistics node);

    /**
     * 查一张订单的全部轨迹，<b>最新在上</b>。
     *
     * <p>★★ {@code ORDER BY trace_time DESC, id DESC} —— <b>两个键都不能少</b>。
     * 完整理由见 {@code OrderLogistics} 的类注释，一句话版本：
     * <pre>
     *   trace_time 可以被填成过去的时刻（补录是这个功能的默认用法）
     *     → 按 id 排会把【录入顺序】当成【发生顺序】，补录后时间线倒过来
     *   trace_time 相同的两条，单独按它排不出先后
     *     → MySQL 返回不确定的顺序，每次查询还可能不一样
     * </pre>
     *
     * @return 没有节点时返回空列表，不是 null
     */
    List<OrderLogistics> selectByOrderId(@Param("orderId") Long orderId);

    /**
     * 删除一个轨迹节点。
     *
     * <p>★★ {@code AND order_id = #{orderId}} 是<b>安全边界，不是装饰</b>：
     * 路径参数 {@code traceId} 来自前端，只按 {@code id} 删的话，
     * 它就能删掉<b>任意一张订单</b>的节点。
     *
     * <p>全库零外键（既定约定）意味着数据库不会帮你挡这个 ——
     * 外键能挡「孤儿行」，挡不了「张冠李戴」。所以只能由 WHERE 挡。
     *
     * <p>⚠️ <b>删除不回退订单状态</b> —— 删掉一条「已签收」不会把订单
     * 退回「已发货」。这是决定，不是漏了，理由见
     * {@code LogisticsServiceImpl.deleteTrace}。
     *
     * @return 影响行数。0 = 这个 id 不存在，<b>或者它不属于这张订单</b>
     *         （两种情况刻意合并成同一个 {@code 1003}，同 {@code requireOwnOrder}）
     */
    int deleteByIdAndOrderId(@Param("id") Long id, @Param("orderId") Long orderId);
}
