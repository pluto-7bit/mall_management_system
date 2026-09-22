package com.example.mall.controller.shop;

import com.example.mall.common.Result;
import com.example.mall.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 图片上传接口（<b>用户端</b>）—— 会员晒图用。
 *
 * <h3>★★ 已经有 {@code /api/admin/images} 了，为什么还要一个？</h3>
 *
 * <p>因为<b>会员的 token 进不了管理端的拦截器</b>：
 * <pre>
 *   /api/admin/**  → AdminAuthInterceptor → 要求【管理员】token
 *   /api/shop/**   → MemberAuthInterceptor → 要求【会员】token
 * </pre>
 * 这两条是一刀切的路径规则，没有「这个接口两种身份都收」的档位
 * （{@code AbstractAuthInterceptor} 的子类各自只认一种 token）。
 *
 * <p>所以「会员要传图」这件事，只有两种做法：
 * <ol>
 *   <li><b>再开一个会员侧的上传端点</b> ← 本项目选的</li>
 *   <li>把图片上传挪到 {@code /api/**} 之外变成公开的 ← <b>绝对不行</b>，
 *       那等于「任何人都能往服务器上写文件」</li>
 * </ol>
 * 第一条的代价是「多一个 Controller」，而它的收益是
 * <b>两个端点各自待在自己那一侧的权限树里</b> ——
 * 「谁能传」这个问题继续由路径回答，不需要在每个接口内部判身份。
 *
 * <h3>★ 它为什么能这么薄（两行）</h3>
 *
 * <p>因为<b>所有规则都在 Service 里</b>：魔数校验（读文件头判断是不是真图片）、
 * 2MB 上限、UUID 重命名、按年月分目录、{@code /uploads/} 前缀 ——
 * 这一行代码<b>全部继承，一字不改</b>。
 *
 * <p><b>★ 这正是把逻辑放在 {@code FileStorageService} 而不是
 * {@code ImageAdminController} 里的回报。</b>
 * 里程碑 11 写那个接口时，把「判断是不是图片」留在 Controller 里
 * 会快得多（少一层跳转），但那样今天就不得不把整段逻辑复制一遍 ——
 * 而复制出来的第二份，就是「晒图能传 exe、商品图不能」这种
 * 谁也说不清的不一致的来源。
 *
 * <p>顺带一提，{@code FileStorageService.requireUploadedImages}
 * （里程碑 12 加的）也是同一个道理的第三次兑现：
 * 那条「地址必须是本服务上传的」规则，现在只定义在一个地方，
 * 被商品图集和评价晒图两条路共用。
 *
 * <h3>⚠️ 一个明知的、写出来的简化</h3>
 *
 * <p><b>任何登录会员都能不限量地传文件</b>：没有配额、没有频率限制、
 * 没有「这个会员已经传了多少张」的账。真实项目里这里必须有限流
 * （否则「注册一个号、写个脚本、把磁盘塞满」是第一件会发生的事）。
 *
 * <p>本项目不做，理由是它属于<b>「防滥用」</b>这个独立话题
 * （和敏感词过滤、验证码是同一类），和本里程碑要讲的
 * 「从另一个域推导出的业务规则」没有关系。
 * <b>明确写出来，比让读代码的人以为这里有防护要好。</b>
 */
@RestController
@RequestMapping("/api/shop/images")
@RequiredArgsConstructor
public class ImageShopController {

    private final FileStorageService fileStorageService;

    /**
     * 上传一张晒图。
     *
     * <p>{@code POST /api/shop/images}，请求体是 {@code multipart/form-data}，
     * 文件字段名固定为 {@code file}（和管理端那个接口一致 ——
     * 前端两处的上传代码因此可以长得一样）。
     *
     * <p>★ <b>这是全项目第二个不用 JSON 的接口</b>（第一个是
     * {@code /api/admin/images}）。图片是二进制，塞不进 JSON
     * （硬塞就得 base64，体积涨 33% 且要多一次编解码）。
     *
     * <p>★ 返回 {@code Result<String>} —— 一个裸的 URL，
     * 前端拿到后塞进 {@code images} 数组，随评价一起提交。
     * 形状和管理端那个接口完全一致。
     *
     * <p>⚠️ <b>「上传」和「提交评价」是两个请求</b>，中间隔着一个
     * 「用户还在编辑」的时间窗。所以会出现「图传上去了、
     * 评价没提交」的孤儿文件 —— 这是里程碑 11 就确认过的取舍
     * （文件只增不减，由将来的定时清理负责），
     * 见 {@code FileStorageServiceImpl} 的类注释。
     *
     * @param file 上传的图片，字段名必须叫 {@code file}
     * @return 形如 {@code /uploads/2026/09/<uuid>.png} 的可访问路径
     */
    @PostMapping
    public Result<String> upload(@RequestParam("file") MultipartFile file) {
        return Result.success(fileStorageService.saveImage(file));
    }
}
