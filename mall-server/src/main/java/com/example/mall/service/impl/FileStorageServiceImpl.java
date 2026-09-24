package com.example.mall.service.impl;

import com.example.mall.common.BusinessException;
import com.example.mall.common.ResultCode;
import com.example.mall.service.FileStorageService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * 文件存储实现 —— 把上传的图片写进本机磁盘。
 *
 * <p>这是里程碑 11 里知识点最密的一个类。它同时在做三件事：
 * 校验「这是不是一张图片」、生成一个安全的落盘路径、把文件写下去。
 *
 * <h3>★ 一、扩展名从「文件内容」反查，不从客户端文件名截取</h3>
 *
 * <p>这是整个功能里最值得写下来的一段安全代码。判断一个上传文件是什么格式，
 * 有三样东西可以看，而它们的可信度天差地别：
 * <pre>
 *   filename     完全由客户端提供 → 不可信
 *   Content-Type 完全由客户端提供 → 不可信
 *   文件头字节   是文件本身的一部分 → 可信（浏览器和 HTTP 协议改不了它）
 * </pre>
 *
 * <p>把一个 {@code evil.exe} 改名成 {@code cute-cat.png} 提交上来，
 * 只验文件名的话服务器会高高兴兴地收下。更要紧的是第二层后果：
 * <b>扩展名决定浏览器拿到这个文件之后怎么解释它</b> ——
 * 如果存成 {@code .html}，那就是把一个「任意文件上传」直接变成了 XSS：
 * 访问这个 URL 的人会执行里面的脚本，而且域名还是我们自己的。
 *
 * <p>所以：先读前几个字节，反查出真正的格式，再由这个格式决定存什么扩展名。
 * 认不出来的一律拒绝 —— <b>白名单，不是黑名单</b>。
 * 黑名单（「拒绝 .exe / .jsp / .php」）永远列不全，而且换个后缀就绕过去了。
 *
 * <h3>★ 二、绝不使用客户端提交的文件名</h3>
 *
 * <p>落盘的文件名是 {@code UUID.randomUUID()} + 反查出来的扩展名。
 * 原文件名一个字符都不用，它至少有三个问题：
 * <ol>
 *   <li>{@code ../../../../etc/passwd} —— 路径穿越，可以直接覆盖系统文件</li>
 *   <li>中文、空格、{@code ;}、{@code &} —— 在 URL 和命令行里都要转义，
 *       不转义就是一个不知道哪天会炸的雷</li>
 *   <li>两个用户同时传 {@code photo.jpg}，会互相覆盖</li>
 * </ol>
 * UUID 一次解决全部三个：它不含特殊字符、不会重复、和用户输入完全无关。
 *
 * <h3>★ 三、大小上限不在这个类里判</h3>
 *
 * <p>2MB 的上限是 {@code application.yml} 里
 * {@code spring.servlet.multipart.max-file-size} 兜住的，
 * 在这里再判一次是<b>把同一条规则写在两个地方</b> ——
 * 将来改了配置忘了改代码（或者反过来），就会出现
 * 「配置说 2MB、代码说 5MB」这种谁也说不清的行为。
 * 一条规则只有一个定义处。
 *
 * <p>⚠️ 但要记住这件事发生在<b>哪一层</b>：Spring 的 multipart 解析发生在
 * <b>进入 Controller 之前</b>，抛的是 {@code MaxUploadSizeExceededException}。
 * 那个异常不可能在这里被接住（代码还没跑），只能由
 * {@code GlobalExceptionHandler} 处理。
 *
 * <h3>★ 四、明知的简化（写在这里免得读代码的人以为漏了）</h3>
 *
 * <ul>
 *   <li><b>文件只增不减。</b>换图、删商品、改图集，都不删磁盘上旧的文件。
 *       删除是不可逆的，而「这张图还有没有别处在用」在数据层面判断不了
 *       （{@code product.cover} 和图集里可以出现同一个路径）。
 *       真实项目的做法是定时扫孤儿文件，那是另一个话题。</li>
 *   <li><b>上传了但没提交表单</b>，服务器上就留了一个没人引用的文件。
 *       同上，由定时清理负责，本轮不做。</li>
 * </ul>
 */
@Slf4j
@Service
public class FileStorageServiceImpl implements FileStorageService {

    /** 存储根目录，来自 application.yml 的 mall.upload.dir */
    @Value("${mall.upload.dir}")
    private String uploadDir;

    /** 访问路径的前缀。和 WebMvcConfig.addResourceHandlers 注册的路径必须一致。 */
    private static final String URL_PREFIX = "/uploads";

    /** 按 yyyy/MM 分目录 */
    private static final DateTimeFormatter DIR_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM");

    /**
     * ★ 启动时建目录，而且把解析后的绝对路径打进日志。
     *
     * <p>建目录的理由只有一条，但是承重的：<b>第一次上传时如果目录不存在，
     * {@code Files.copy} 会抛 {@code NoSuchFileException}</b>，
     * 而它会被 {@code GlobalExceptionHandler} 的兜底接住 ——
     * 用户看到的是「系统繁忙，请稍后重试」，一个和真实原因毫无关系的提示。
     * 在这里建一次，那个错误就永远不会发生。
     *
     * <p>★ 打日志的理由更值得记：{@code mall.upload.dir} 的默认值是相对路径
     * {@code ./uploads}，而<b>相对路径是相对「进程的工作目录」的</b>。
     * 从 {@code mall-server/} 目录用 {@code mvn spring-boot:run} 启动，
     * 落在 {@code mall-server/uploads/}；从项目根目录启动，落在
     * {@code java/uploads/}。换一次启动方式，之前上传的图片和
     * {@code addResourceHandlers} 指向的就是两个不同的空目录 ——
     *
     * <p>⚠️ 而这个故障的症状是「上传成功、返回了 URL、但图片显示不出来」，
     * 日志里却什么都看不出来（两边都没报错，只是目录不同）。
     * 把绝对路径打出来，这类问题一眼就能定位。
     */
    @PostConstruct
    public void init() {
        try {
            Path root = rootDir();
            Files.createDirectories(root);
            log.info("上传目录已就绪：{}", root);
        } catch (IOException e) {
            // 启动就失败，比跑到第一次上传才失败好得多 ——
            // 那样错误会在一个离原因很远的地方冒出来。
            throw new IllegalStateException("无法创建上传目录：" + uploadDir, e);
        }
    }

    @Override
    public String saveImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请选择要上传的图片");
        }

        // ★ 先读文件头判断格式 —— 在这之前不能碰文件名，也不信 Content-Type
        String ext = detectImageExtension(file);

        // 按年月分目录。不是为了现在（库里 40 多件商品），
        // 是为了「一个目录里塞进几万个文件之后连 ls 都卡」这件事
        // 不要在两年后才发现。代价只是多建两级目录，
        // Files.createDirectories 本来就会递归建。
        String datePath = LocalDate.now().format(DIR_FORMAT);

        // ★ UUID + 反查出来的扩展名。客户端提交的文件名一个字符都不用。
        String filename = UUID.randomUUID().toString().replace("-", "") + "." + ext;

        // ⚠️ 年份/月份这两段是代码生成的（一定是 "2026" / "09" 这种形状），
        //    UUID 也是代码生成的，所以这个相对路径里【不存在】任何用户输入 ——
        //    resolve 之后不会跳出 root。这是「不信客户端」这条规矩的红利：
        //    一旦不碰用户给的字符串，路径穿越就从根上没有了可能。
        Path target = rootDir().resolve(datePath).resolve(filename);

        try {
            Files.createDirectories(target.getParent());
            // 用 MultipartFile.transferTo(Path) 而不是自己写流：
            // 它内部会处理「临时文件直接改名」这条快路径，
            // 大文件不必先读进内存再写出去。
            file.transferTo(target);
        } catch (IOException e) {
            // 这个异常【不能】往外抛原始类型：IOException 会被兜底处理器
            // 翻译成「系统繁忙」，而用户真正需要知道的（磁盘满了？权限不对？）
            // 应该进 error 日志，给用户的那句话保持模糊是对的 ——
            // 文件系统路径不该出现在给前端的响应里。
            log.error("保存上传图片失败：{}", target, e);
            throw new BusinessException(ResultCode.BAD_REQUEST, "图片保存失败，请稍后重试");
        }

        log.info("已保存上传图片：{}", target);

        // ★ 返回 URL 路径，不返回 File、也不返回绝对路径。
        //   这个字符串会被直接存进数据库，而数据库里的东西不能指着一台具体的机器。
        return URL_PREFIX + "/" + datePath + "/" + filename;
    }

    /**
     * {@inheritDoc}
     *
     * <p>★ 这个方法是从 {@code ProductServiceImpl.checkImageUrls} 搬过来的
     * （里程碑 12）—— 原来的私有方法原封不动地成了这个实现体，
     * {@code ProductServiceImpl} 改成调这里。
     *
     * <p>★★ <b>搬家的理由不是「两个地方都在用」，是「这条规则属于谁」：</b>
     * {@link #saveImage} 定义了「什么算一个合法的图片地址」，
     * 所以校验地址形状的规则就该和它待在一起。
     * 一条安全边界出现两处定义，一定会有一天分岔 ——
     * 而分岔之后两处代码看起来都完全正确。
     *
     * <p>⚠️ <b>别把「长度 255」当成顺手加的一道校验。</b>
     * 那个数不是拍出来的，它来自 {@code product_image.url} 和
     * {@code product_review_image.url} 两列的 {@code VARCHAR(255)}。
     * 也就是说：<b>这条规则和这个存储服务是绑在一起的</b> ——
     * 哪天 URL 的生成方式变了（比如换成 OSS 之后长了很多），
     * 该改的是这里和那两列，而不是在每个调用方各加一道。
     */
    @Override
    public void requireUploadedImages(List<String> urls) {
        // null 当作空集合：「没传图」和「传了空数组」在这条规则下是同一件事。
        // 这一步归一化放在这里，是为了让调用方不必先判空再调 ——
        // 一条「什么都不校验」的规则，调用方还得分支一次，那是把空处理的负担推给了下游。
        if (urls == null) {
            return;
        }
        for (String url : urls) {
            // 空字符串在这里被拦下来（startsWith 对 null 会 NPE，所以先判 null）。
            // ⚠️ 注意不能用 @NotBlank 那样的注解 —— 那个注解加在集合上
            //    会变成「这个集合不能为空」，而不是「集合里每个元素不能为空」。
            if (url == null || !url.startsWith(URL_PREFIX + "/")) {
                throw new BusinessException(ResultCode.BAD_REQUEST,
                        "图片地址不合法，只能使用本服务上传的图片");
            }
            // 长度上限。和两处 url 列的 VARCHAR(255) 对齐 ——
            // 数据库那一列会拦，但拦下来的是一条 SQL 异常，
            // 在这里拦住才能给用户一句人话。
            if (url.length() > 255) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "图片地址不能超过 255 个字符");
            }
        }
    }

    // ======================================================================
    // 以下是私有工具方法
    // ======================================================================

    /** 上传根目录的绝对路径（normalize 过，日志里打出来是干净的） */
    private Path rootDir() {
        return Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    /**
     * 读取文件头几个字节，反查出图片格式；认不出来就拒绝。
     *
     * <p>各格式的文件头（魔数）：
     * <pre>
     *   PNG   89 50 4E 47 0D 0A 1A 0A      头 8 字节（顺便还能当完整性校验）
     *   JPEG  FF D8 FF                     头 3 字节
     *   GIF   47 49 46 38  ("GIF8")        头 4 字节
     *   WEBP  52 49 46 46 ("RIFF") + 第 8~11 字节 57 45 42 50 ("WEBP")
     *         ★ WEBP 要读两段，因为 RIFF 是一个通用容器头，
     *           .wav / .avi 也用 RIFF 开头 —— 只看前 4 字节会把它们放进来
     * </pre>
     *
     * <p>⚠️ <b>为什么白名单里没有 SVG？</b>
     * SVG 是 XML，可以内嵌 {@code <script>} 和事件属性 ——
     * 它是最经典的「图片 XSS」载体。浏览器直接打开一个 SVG 是会执行脚本的，
     * 而这个文件又是从我们自己的域名下提供的。
     * 本项目里那 105 个种子图恰好全是 SVG，但它们是<b>随工程走的静态文件</b>
     * （由用户端的 Vite 提供），不经过这条上传通道 —— 所以不放它进白名单
     * 对现有数据没有任何影响。
     */
    private String detectImageExtension(MultipartFile file) {
        // 8 字节够 PNG 用，也够 WEBP 读到第 8~11 字节（下面单独再读一次）
        byte[] head = new byte[12];
        int read;
        try (InputStream in = file.getInputStream()) {
            read = in.readNBytes(head, 0, head.length);
        } catch (IOException e) {
            log.error("读取上传文件失败", e);
            throw new BusinessException(ResultCode.BAD_REQUEST, "图片读取失败，请重新上传");
        }

        if (read < 4) {
            // 连 4 个字节都读不满，不可能是一张图片
            throw new BusinessException(ResultCode.BAD_REQUEST, "只支持 PNG / JPEG / GIF / WEBP 格式的图片");
        }

        if (head[0] == (byte) 0x89 && head[1] == 0x50 && head[2] == 0x4E && head[3] == 0x47) {
            return "png";
        }
        if (head[0] == (byte) 0xFF && head[1] == (byte) 0xD8 && head[2] == (byte) 0xFF) {
            return "jpg";
        }
        if (head[0] == 'G' && head[1] == 'I' && head[2] == 'F' && head[3] == '8') {
            return "gif";
        }
        if (read >= 12
                && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
            return "webp";
        }

        // ⚠️ 这句话是给用户看的，所以不能写成「魔数不匹配」——
        //   用户不知道魔数是什么，他只知道「我传的是一张图」。
        //   ★ 而它同时又是安全的：报错信息里【不提】检测到了什么格式，
        //     否则就成了一个「帮你探测服务器接受什么」的探针。
        throw new BusinessException(ResultCode.BAD_REQUEST, "只支持 PNG / JPEG / GIF / WEBP 格式的图片");
    }
}
