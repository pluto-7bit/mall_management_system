package com.example.mall.controller.admin;

import com.example.mall.common.Result;
import com.example.mall.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 图片上传接口（<b>管理端</b>）。
 *
 * <p><b>为什么挂在 {@code /api/admin/images}，而不是 {@code /api/admin/products/upload}？</b>
 *
 * <p>因为<b>上传的这个文件不属于任何某个商品</b>。一个很具体的证据：
 * 运营新增商品时，是<b>先传图、后提交表单</b>的 —— 图传上来的那一刻，
 * 商品在数据库里还不存在，连 id 都没有。
 *
 * <p>把上传挂在 {@code products} 下面，是硬把两个生命周期完全不同的东西
 * 绑在一起：商品会改会删，而上传产生的文件是只增不减的。
 * 顺带还避开一个路由冲突 —— {@code @PostMapping} 挂在
 * {@code /api/admin/products} 上不冲突，但 {@code /upload} 和
 * {@code @GetMapping("/{id}")} 在同一棵树里迟早要让人多想一秒。
 *
 * <p><b>⚠️ 这个接口必须在 {@code /api/admin/**} 下 —— 这是一条红线。</b>
 *
 * <p>{@code AdminAuthInterceptor} 的拦截范围就是 {@code /api/admin/**}，
 * 所以挂在这里 = 自动要求管理员 token。如果哪天有人图省事把它挪到
 * {@code /api/**} 之外（比如为了「让前端少传个 token」），
 * 那就等于<b>任何人都能往服务器上写文件</b> ——
 * 这是整个图片功能里最大的一次安全边界移动，而且不会有任何报错。
 *
 * <p><b>Controller 这一层应该是「薄」的</b>（详见
 * {@link ProductAdminController} 的类注释）：接收文件 → 调 Service → 包装返回值。
 * 「这是不是一张图片」「大小超没超」「落盘文件名怎么起」全部在 Service 里，
 * 这里一个判断都不写。
 */
@RestController
@RequestMapping("/api/admin/images")
@RequiredArgsConstructor
public class ImageAdminController {

    private final FileStorageService fileStorageService;

    /**
     * 上传一张图片。
     *
     * <p>{@code POST /api/admin/images}，请求体是 {@code multipart/form-data}，
     * 文件字段名固定为 {@code file}。
     *
     * <p>★ <b>这是全项目唯一一个不用 JSON 的接口。</b>
     * 其他所有接口的请求体都是 {@code application/json}（{@code @RequestBody} + DTO），
     * 而图片是二进制，塞不进 JSON（硬塞就得 base64，体积涨 33% 且要多一次编解码）。
     * 所以这里用的是 {@code @RequestParam("file") MultipartFile} ——
     * 由 Spring 的 multipart 解析器从请求体里把文件抽出来。
     *
     * <p><b>返回 {@code Result<String>}，一个裸的 URL 字符串。</b>
     * 照 {@link ProductAdminController#create} 返回 {@code Result<Long>} 的先例 ——
     * 前端需要的就只有这一样东西（拿到路径后塞进表单的图片字段里），
     * 为一个只有 {@code url} 一个字段的返回值专门造一个 VO 是多余的。
     *
     * <p>⚠️ <b>这个方法没有 {@code @Valid}，也没有任何参数校验注解。</b>
     * 不是因为忘了 —— 是因为能放的校验都在 {@code MultipartFile} 上做不了
     * （「是不是图片」要读字节，「多大」由 {@code application.yml} 的
     * multipart 配置在进入本方法<b>之前</b>就拦掉了）。
     *
     * @param file 上传的图片，字段名必须叫 {@code file}
     * @return 形如 {@code /uploads/2026/09/<uuid>.png} 的可访问路径
     */
    @PostMapping
    public Result<String> upload(@RequestParam("file") MultipartFile file) {
        return Result.success(fileStorageService.saveImage(file));
    }
}
