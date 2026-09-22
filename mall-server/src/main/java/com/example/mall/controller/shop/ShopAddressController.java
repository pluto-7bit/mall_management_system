package com.example.mall.controller.shop;

import com.example.mall.common.Result;
import com.example.mall.dto.AddressSaveDTO;
import com.example.mall.entity.MemberAddress;
import com.example.mall.service.AddressService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 收货地址接口（<b>用户端</b>）。
 *
 * <h3>★ 这里直接返回 Entity，没有建 VO</h3>
 *
 * <p>判断标准和 {@code ShopCategoryController} 那条一样：
 * <b>不是「规范要求建 VO」，而是「有没有不该给前端看的东西」。</b>
 * 看一遍 {@link MemberAddress} 的字段：
 * <pre>
 *   id / receiver / phone / region / detail / isDefault   → 都该给前端
 *   memberId                                             → 是他自己的 id，给了也无害
 *   createTime / updateTime                              → 无害
 * </pre>
 * 没有一个是需要藏起来的，所以不必多写一个字段完全相同的 VO 类。
 * 对比 {@code ShopProductVO}：那边必须藏掉 {@code status}，
 * 所以那边必须建 VO。
 *
 * <p><b>「什么时候该建 VO」这个问题没有统一答案，但有一个统一的判断方法：
 * 把 Entity 的字段挨个看一遍，问「这个给前端看有没有问题」。
 * 全部没问题就不用建；有一个有问题就必须建。</b>
 *
 * <h3>路径 {@code /api/shop/addresses} 没有被排除出拦截器</h3>
 *
 * <p>所以这个类里的每个接口都<b>只有登录后能调</b>，
 * 而且 Service 里的 memberId 一定来自 JWT。
 * 这正是我们要的 —— <b>地址是纯私人的数据，没有任何一个接口该对游客开放。</b>
 *
 * <p>对比 {@code /api/shop/products/**}（已排除，游客可匿名浏览）：
 * 同样是 {@code /api/shop/} 开头，安全等级完全不同。
 * 所以看一个接口要不要登录，<b>不能看路径的「大前缀」，
 * 必须看 {@code WebMvcConfig} 里的排除列表</b> ——
 * 那才是唯一的真相来源。
 */
@RestController
@RequestMapping("/api/shop/addresses")
@RequiredArgsConstructor
public class ShopAddressController {

    private final AddressService addressService;

    /**
     * 查询我的地址列表。
     *
     * <p>{@code GET /api/shop/addresses}
     *
     * <p>默认地址排在最前。一个地址都没有时返回空数组（HTTP 200），
     * <b>不是 404</b> —— 「还没存过地址」是正常状态，见
     * {@code ShopCartController.getCart} 里对「空」的讨论。
     */
    @GetMapping
    public Result<List<MemberAddress>> list() {
        return Result.success(addressService.list());
    }

    /**
     * 查询我的默认地址。
     *
     * <p>{@code GET /api/shop/addresses/default}
     *
     * <p>★ 注意这个方法的返回值可能是 {@code data: null}，
     * 这是一种<b>正常的成功响应</b>，不是错误。
     *
     * <p>为什么单独给一个接口，而不是让前端从列表里自己挑
     * {@code isDefault === 1} 的那条？
     * <ul>
     *   <li>下单页只关心「默认地址是哪个」，不需要拉整个列表。
     *       如果用户存了 20 个地址，为了拿一个默认地址传 20 条数据是浪费</li>
     *   <li>更重要的是<b>语义</b>：前端调这个接口表达的是
     *       「给我默认地址」，而不是「给我所有地址然后我自己找」。
     *       「谁来算」这件事放在服务端，将来默认地址的规则变了
     *       （比如「上次用过的地址优先」），前端一行都不用改</li>
     * </ul>
     * <b>「让前端自己从数据里算」和「服务端直接给结果」，
     * 在后端不麻烦的时候要选后者 —— 规则只有一处，才不会分岔。</b>
     */
    @GetMapping("/default")
    public Result<MemberAddress> getDefault() {
        return Result.success(addressService.getDefault());
    }

    /**
     * 新增地址。
     *
     * <p>{@code POST /api/shop/addresses}
     *
     * <p>返回新地址的 id，方便前端插入后立刻切到「编辑」状态，
     * 或者把它设为选中项。
     */
    @PostMapping
    public Result<Long> create(@Valid @RequestBody AddressSaveDTO dto) {
        return Result.success(addressService.create(dto));
    }

    /**
     * 修改地址。
     *
     * <p>{@code PUT /api/shop/addresses/{id}}
     *
     * <p>id 放在 URL 里而不是 body 里 —— 资源的身份由 URL 表达。
     * 而且 Service 会用 JWT 里的 memberId 去校验这个 id 是不是自己的，
     * 所以传别人的 id 只会得到「地址不存在」。
     *
     * <p>★ 注意 {@code @Valid}：这是<b>全量替换</b>，
     * 四个文本字段必须全传，少一个就是 400。
     * 想做「只改电话」这种局部更新是不行的 —— 详见
     * {@link AddressSaveDTO} 和 {@code AddressService.update} 的注释，
     * 那里说明了为什么不需要局部更新（列表页的「设为默认」走下面那个接口）。
     *
     * <p>返回 {@code Result<Void>}（data 为 null）而不是返回更新后的对象：
     * 因为前端手上已经有完整数据了（它刚提交的），
     * 再回传一遍没有意义。**只有当服务端会「算出前端不知道的东西」时，
     * 才需要在响应里带数据。** 新增接口返回 id 就是这种情况 ——
     * id 是数据库生成的，前端事先确实不知道。
     */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id,
                               @Valid @RequestBody AddressSaveDTO dto) {
        addressService.update(id, dto);
        return Result.success();
    }

    /**
     * 把某个地址设为默认。
     *
     * <p>{@code PUT /api/shop/addresses/{id}/default}
     *
     * <p>★ 为什么用 {@code PUT} 而不是 {@code POST}？
     * 因为这个操作是<b>幂等</b>的 —— 连点两次「设为默认」，
     * 结果和点一次完全一样。PUT 表达「把它变成某个状态」，
     * 正好对应这里（对比 POST 表达「新增一件东西」）。
     * 用对方法，接口的含义就不用文档解释了。
     *
     * <p>★ 为什么路径结尾是 {@code /default} 而不是把
     * {@code isDefault} 塞进 body 走上面那个 update？
     * 因为地址列表页上的「设为默认」按钮手上<b>只有 id</b>，
     * 没有完整的地址数据。让它先查一次再回传，
     * 既多了一次请求，又有「拿旧数据覆盖别人刚改的内容」的风险。
     * 详见 {@code AddressService.setDefault} 的注释。
     *
     * <p>⚠️ 注意这个路径是 {@code /api/shop/addresses/{id}/default} ——
     * 它<b>不会</b>和上面的 {@code GET /default} 冲突：
     * 一个匹配的是「/addresses/default」（两段），
     * 一个匹配的是「/addresses/数字/default」（三段）。
     * Spring 按路径段数匹配，不会混淆。
     * （但如果你把 GET 的路径写成 {@code /{id}} 就真的会冲突 ——
     *   "default" 会被当成 id，然后类型转换失败报 400。
     *   这也是为什么这个「取默认地址」的接口要放在 {@code /default}
     *   而不是靠 {@code /{id}} 去猜。）
     */
    @PutMapping("/{id}/default")
    public Result<Void> setDefault(@PathVariable Long id) {
        addressService.setDefault(id);
        return Result.success();
    }

    /**
     * 删除地址。
     *
     * <p>{@code DELETE /api/shop/addresses/{id}}
     *
     * <p>幂等：删一个不存在的地址也返回成功。理由和购物车的删除一样 ——
     * 用户的目标状态（「这条地址不在了」）已经达成，就不算失败。
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        addressService.delete(id);
        return Result.success();
    }
}
