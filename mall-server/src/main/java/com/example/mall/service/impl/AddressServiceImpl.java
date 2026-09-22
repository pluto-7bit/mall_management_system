package com.example.mall.service.impl;

import com.example.mall.common.BusinessException;
import com.example.mall.common.LoginUser;
import com.example.mall.common.ResultCode;
import com.example.mall.common.UserContext;
import com.example.mall.dto.AddressSaveDTO;
import com.example.mall.entity.MemberAddress;
import com.example.mall.mapper.MemberAddressMapper;
import com.example.mall.service.AddressService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 收货地址簿业务实现。
 *
 * <p>这个类的重点不是增删改查（那是 Mapper 的事），而是
 * <b>在每一个入口维护「恰好一个默认地址」这条不变量</b>。
 * 详见 {@link AddressService} 的类注释。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AddressServiceImpl implements AddressService {

    private final MemberAddressMapper addressMapper;

    // ==========================================================================
    // 读
    // ==========================================================================

    @Override
    public List<MemberAddress> list() {
        return addressMapper.selectByMemberId(currentMemberId());
    }

    @Override
    public MemberAddress getDefault() {
        // 查不到就是 null，直接返回。
        // 不在这里「兜底选第一条」—— 那是替用户做决定，
        // 而且会让「用户明明取消过默认」这件事变得没有效果。
        // 到底要不要兜底，是前端展示层该考虑的事。
        return addressMapper.selectDefaultByMemberId(currentMemberId());
    }

    // ==========================================================================
    // 写
    // ==========================================================================

    /**
     * 新增地址。
     *
     * <p>{@code @Transactional} 在这里是必须的：下面可能执行「清空默认 + 插入」
     * 两条写操作，如果清空成功而插入失败，用户的默认地址就凭空没了。
     */
    @Override
    @Transactional
    public Long create(AddressSaveDTO dto) {
        Long memberId = currentMemberId();

        // ★ 判断「这是不是第一个地址」，必须在插入【之前】查。
        //   插进去之后再查，永远是 1，这个判断就失效了。
        boolean isFirst = addressMapper.countByMemberId(memberId) == 0;

        // 要不要设为默认？两种情况之一：
        //   1. 这是第一个地址 —— 自动设默认，省得用户再点一次
        //   2. 用户明确勾了「设为默认」
        boolean wantDefault = isFirst || Boolean.TRUE.equals(dto.getIsDefault());

        MemberAddress address = new MemberAddress();
        address.setMemberId(memberId);
        address.setReceiver(dto.getReceiver().trim());
        address.setPhone(dto.getPhone().trim());
        address.setRegion(dto.getRegion().trim());
        address.setDetail(dto.getDetail().trim());
        address.setIsDefault(wantDefault ? 1 : 0);

        // ★ 先清空旧的默认，再插入新的。
        //   顺序不能反：先插入的话，会出现「同时存在两个默认地址」的瞬间。
        //   虽然在同一事务里外部读不到这个中间状态（InnoDB 的隔离性），
        //   但代码按「任何时刻都最多一个默认」来写，更容易推理。
        if (wantDefault) {
            addressMapper.clearDefaultByMemberId(memberId);
        }

        addressMapper.insert(address);
        log.info("新增收货地址: memberId={}, addressId={}, 设为默认={}, 是否首个={}",
                memberId, address.getId(), wantDefault, isFirst);
        return address.getId();
    }

    @Override
    @Transactional
    public void update(Long id, AddressSaveDTO dto) {
        Long memberId = currentMemberId();

        // ★★ 这一行是防「水平越权」的关键。
        //
        //    必须用 selectByIdAndMember 而不是 selectById(id)。
        //    如果只按 id 查，会员 A 传一个 B 的地址 id，
        //    就能改掉 B 的收货地址 —— 而且改完之后，
        //    B 下次下单会把货寄到 A 指定的地方。
        //    这是这个模块里最严重的风险。
        //
        //    查不到统一报「地址不存在」，不区分
        //    「不存在」和「不是你的」—— 区分了就等于告诉攻击者
        //    「这个 id 是存在的」，和商品详情接口的处理一致。
        MemberAddress exist = addressMapper.selectByIdAndMember(id, memberId);
        if (exist == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "地址不存在");
        }

        // ★★ 这里是【全量替换】，四个文本字段直接赋值，没有 null 判断。
        //
        //   为什么可以不判断 null？因为 AddressSaveDTO 上这四个字段都是
        //   @NotBlank，而 Controller 上有 @Valid —— 校验没过的请求
        //   根本进不到这个方法。所以走到这里时，它们必定是非 null 的。
        //
        //   （⚠️ 这段代码最早写成的是
        //     dto.getReceiver() == null ? null : dto.getReceiver().trim()
        //     也就是「null 就不更新」的局部更新语义。但那是自相矛盾的：
        //     @Valid 在外层已经把局部更新的请求全部 400 掉了，
        //     于是那几个 null 分支永远执行不到，成了骗人的死代码 ——
        //     读代码的人会以为「只传 phone 就能只改电话」，
        //     照着写一个局部更新的调用，拿到的是一个 400。
        //     ★ 一段永远不会执行的防御代码，比没有这段代码更糟，
        //       因为它描述了一个不成立的事实。
        //
        //     局部更新的需求并没有丢：列表页「设为默认」只有 id、
        //     没有完整数据，那条路走的是下面的 setDefault。
        //     这也正是那个接口存在的理由，见 AddressService 的注释。）
        //
        //   trim 仍然要做：@NotBlank 只拒绝「全是空格」，
        //   「  张三  」是能通过的。存进库里看不出差别，
        //   但做「这两个地址是不是同一个」的判断时就会不一致。
        //   （注：@Size 校验的是 trim 之前的长度，所以「前后带空格刚好卡到上限」
        //    的值会先被拒。这是可接受的 —— 宁可让它重填，
        //    也不要悄悄截断用户的地址。）
        MemberAddress update = new MemberAddress();
        update.setId(id);
        update.setMemberId(memberId);
        update.setReceiver(dto.getReceiver().trim());
        update.setPhone(dto.getPhone().trim());
        update.setRegion(dto.getRegion().trim());
        update.setDetail(dto.getDetail().trim());

        // ★ isDefault 的处理要分三种情况，不能简单写成
        //   update.setIsDefault(dto.getIsDefault() ? 1 : 0) ——
        //   那样「没传」（null）会被当成 false，也就是「取消默认」。
        //   用户只想改个电话号码，结果默认地址被取消了。
        //
        //   这就是 DTO 里 isDefault 必须用 Boolean 而不是 boolean 的原因
        //   （见 AddressSaveDTO.isDefault 的注释）：
        //   **要区分「没传」和「传了 false」，就必须能表达 null。**
        if (dto.getIsDefault() != null) {
            if (Boolean.TRUE.equals(dto.getIsDefault())) {
                // 设为默认：先清掉别人的
                addressMapper.clearDefaultByMemberId(memberId);
                update.setIsDefault(1);
            } else {
                // 取消默认：直接置 0。
                // 取消之后这个会员可能就没有默认地址了 —— 这是允许的，
                // 因为不变量说的是「最多一个」，不是「必须有一个」。
                // 强制「必须有」的话，用户想取消默认就没法取消了。
                update.setIsDefault(0);
            }
        }

        addressMapper.updateById(update);
        log.info("修改收货地址: memberId={}, addressId={}", memberId, id);
    }

    @Override
    @Transactional
    public void setDefault(Long id) {
        Long memberId = currentMemberId();

        // 归属校验，理由同 update 方法
        MemberAddress exist = addressMapper.selectByIdAndMember(id, memberId);
        if (exist == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "地址不存在");
        }

        // 已经是默认了就直接返回，省一次写库。
        // ★ 这不只是优化 —— 它让这个接口<b>幂等</b>：
        //   连点两次「设为默认」的结果和点一次完全一样，
        //   而且第二次不会去动数据库。
        //
        //   幂等的好处在这里很实在：用户网络卡了多点了一下，
        //   不会产生任何多余的影响。
        if (exist.getIsDefault() != null && exist.getIsDefault() == 1) {
            return;
        }

        addressMapper.clearDefaultByMemberId(memberId);

        MemberAddress update = new MemberAddress();
        update.setId(id);
        update.setMemberId(memberId);
        update.setIsDefault(1);
        addressMapper.updateById(update);

        log.info("设置默认地址: memberId={}, addressId={}", memberId, id);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        Long memberId = currentMemberId();

        // 先查出来，一是做归属校验，二是要知道「删的是不是默认地址」
        MemberAddress exist = addressMapper.selectByIdAndMember(id, memberId);
        if (exist == null) {
            // 删除也做成幂等的：删一个已经不在的地址不算失败。
            // 和购物车的删除语义保持一致（见 CartServiceImpl.remove）。
            // 用户重复点删除、或者两个标签页同时删，都不该看到报错。
            log.info("删除地址：不存在或不属于该会员，按幂等处理: memberId={}, addressId={}",
                    memberId, id);
            return;
        }

        addressMapper.deleteByIdAndMember(id, memberId);

        // ★ 如果删掉的正好是默认地址，要把剩下的一条提为默认。
        //
        //   不这么做的话，用户删掉默认地址后，下单页会变成「一个都没选中」——
        //   虽然功能上没错（他自己选一个就行），但体验上像丢了东西。
        //   这里自动提一条，让不变量「有地址就恰好有一个默认」继续成立。
        //
        //   为什么不直接在用户下次下单时兜底（没默认就取最新一条）？
        //   那样也行，但「不变量」就被破坏了 —— 数据长期处于
        //   「有地址却没有默认」的状态，每个读的地方都得自己兜底一次。
        //   **能在写入时把数据收拾干净，就不要留给每个读取方去猜。**
        if (exist.getIsDefault() != null && exist.getIsDefault() == 1) {
            // 按当前排序取第一条（也就是 id 最大的那条 —— 最新的地址）
            List<MemberAddress> rest = addressMapper.selectByMemberId(memberId);
            if (!rest.isEmpty()) {
                MemberAddress promote = rest.get(0);
                MemberAddress update = new MemberAddress();
                update.setId(promote.getId());
                update.setMemberId(memberId);
                update.setIsDefault(1);
                addressMapper.updateById(update);
                log.info("删除默认地址后自动提升新默认: memberId={}, 新的默认={}",
                        memberId, promote.getId());
            }
        }

        log.info("删除收货地址: memberId={}, addressId={}", memberId, id);
    }

    // ==========================================================================
    // 私有辅助方法
    // ==========================================================================

    /**
     * 取当前登录会员的 id。
     *
     * <p>和 {@code CartServiceImpl.currentMemberId} 同一个写法，理由也一样：
     * 用 {@code require()} 而不是 {@code get()}，多一道防线。
     *
     * <p><b>★ 这个方法是整个地址模块安全性的根基。</b>
     * 所有查询和写入的 memberId 都从这里来，绝不允许从请求参数里取。
     * 因为拦截器已经保证了「进到这里的请求都是登录的」，
     * 所以这个 id 是<b>可信的</b>——它来自 JWT，不是来自客户端说的。
     *
     * <p>反过来说：如果哪天有人为了「方便」加了一个
     * {@code listByMember(Long memberId)} 这样的方法，把 memberId 当参数传进来，
     * 那这个模块的越权防护就全废了 —— 因为参数是客户端能控制的。
     * <b>安全边界一旦被"为了方便"开了一个口子，很快就会到处都是口子。</b>
     */
    private Long currentMemberId() {
        LoginUser user = UserContext.require();
        return user.id();
    }
}
