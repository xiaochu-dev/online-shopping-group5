package com.group5.shop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 应用入口。
 *
 * <p>迭代 2 技术栈：Java 17 + Spring Boot 3 + MyBatis-Plus + Thymeleaf + SQLite。</p>
 *
 * <p>说明：SQLite 使用文件库，而 SQLite 驱动<b>不会</b>自动创建目录，因此这里在 Spring
 * 容器启动前先把 {@code ./data} 目录建好（静态初始化块在类加载时执行，早于数据源初始化）。</p>
 *
 * @author 胡成溯（组长）
 */
@SpringBootApplication
public class ShopApplication {

    /** 数据库文件所在目录，与 application.yml 中 jdbc:sqlite:./data/shop.db 保持一致。 */
    private static final Path DB_DIR = Path.of("data");

    /**
     * 商品图片上传目录，与 WebMvcConfig 中 {@code file:./uploads/} 的资源映射保持一致。
     *
     * <p>在这里预建目录的原因：Spring 的 {@code file:} 资源映射不会自动创建目录，
     * 黄程宇第一次上传图片时如果目录不存在，会抛 IOException 变成 500，
     * 报错信息是「系统找不到指定的路径」，与「图片校验」完全无关，很容易误判。</p>
     */
    private static final Path UPLOAD_DIR = Path.of("uploads");

    static {
        try {
            Files.createDirectories(DB_DIR);
            Files.createDirectories(UPLOAD_DIR);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建运行期目录：" + UPLOAD_DIR.toAbsolutePath(), e);
        }
    }

    public static void main(String[] args) {
        SpringApplication.run(ShopApplication.class, args);
    }
}
