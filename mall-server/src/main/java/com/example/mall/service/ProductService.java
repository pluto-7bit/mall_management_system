package com.example.mall.service;

import com.example.mall.common.PageResult;
import com.example.mall.dto.ProductQueryDTO;
import com.example.mall.dto.ProductSaveDTO;
import com.example.mall.vo.AdminProductDetailVO;
import com.example.mall.vo.ProductVO;

/**
 * 商品业务接口。
 *
 * <p><b>关于「接口 + 实现类」这种写法</b>，说句实话：
 * 在只有一个实现类的情况下，接口其实<b>不是必需的</b>，
 * 现代 Spring 项目（尤其是 Kotlin 或较新的 Java 项目）经常直接写一个
 * {@code @Service} 类，不拆接口。
 *
 * <p>之所以本项目还是这么写，有两个原因：
 * <ol>
 *   <li>国内企业项目（包括若依这类主流脚手架）基本都是这个风格，你工作中大概率会遇到</li>
 *   <li>Spring AOP 生成代理时，基于接口的 JDK 动态代理是经典方式，
 *       理解这层有助于搞懂 {@code @Transactional} 是怎么生效的</li>
 * </ol>
 *
 * <p>你自己写新项目时，如果确定不会有第二个实现，直接写类也完全可以。
 * 知道这个取舍比死守规矩更重要。
 *
 * <p><b>这一层是整个项目的核心</b>，Controller 和 Mapper 都是它的配角。
 * 业务规则、事务边界、校验逻辑，全部集中在这里。
 */
public interface ProductService {

    /**
     * 分页查询商品列表。
     *
     * <p>★ 返回的是 {@link ProductVO}，<b>不含图集</b> —— 列表页不显示图集，
     * 见 {@code ProductVO} 的类注释。
     */
    PageResult<ProductVO> page(ProductQueryDTO query);

    /**
     * 查询商品详情，查不到时抛业务异常。
     *
     * <p>★ 返回类型在里程碑 11 从 {@code ProductVO} 换成了它的子类
     * {@link AdminProductDetailVO}，多带一个 {@code images}。
     * 这对老调用方是<b>透明的</b>：子类拥有父类的全部字段，
     * 前端不改也不会坏（只是看不到图集）。
     *
     * <p>⚠️ <b>为什么必须改接口，而不是只在实现类里返回子类？</b>
     * 因为 Java 的<b>协变返回类型</b>（covariant return）允许
     * 「实现类返回比接口声明的更具体的类型」——
     * 所以「接口写 {@code ProductVO}、实现写 {@code AdminProductDetailVO}」
     * 是<b>能编译通过</b>的。
     *
     * <p>但那样做没用：<b>调用方拿到的是接口类型</b>。
     * Controller 注入的是 {@code ProductService}，编译器按接口签名
     * 认定它返回 {@code ProductVO}，于是 {@code product.getImages()} 编译不过。
     * 想让调用方看见子类的字段，就必须把子类写进接口签名。
     *
     * <p>（顺带：正因为这个规则，接口和实现的返回类型<b>不强制一致</b>，
     * 所以「只改了实现忘了改接口」是能编译的 ——
     * 症状是 Controller 里那个方法突然找不到，而错误信息指向 Controller。
     * 这算是一个值得知道的排查方向。）
     */
    AdminProductDetailVO getById(Long id);

    /** 新增商品，返回新生成的 id */
    Long create(ProductSaveDTO dto);

    /** 修改商品 */
    void update(Long id, ProductSaveDTO dto);

    /** 删除商品 */
    void delete(Long id);
}
