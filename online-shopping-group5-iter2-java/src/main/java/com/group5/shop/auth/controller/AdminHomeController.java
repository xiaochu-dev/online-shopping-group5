package com.group5.shop.auth.controller;

import com.group5.shop.auth.service.AuthService;
import com.group5.shop.common.constant.IntentStatus;
import com.group5.shop.common.constant.ProductStatus;
import com.group5.shop.common.constant.SessionKeys;
import com.group5.shop.product.domain.Category;
import com.group5.shop.auth.domain.Seller;
import com.group5.shop.product.domain.CategoryMapper;
import com.group5.shop.portal.domain.IntentMapper;
import com.group5.shop.product.domain.ProductMapper;
import com.group5.shop.common.util.TimeUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台首页与基座自检页（T2-01 ~ T2-06 的可见交付物）。
 *
 * <p>为什么组长要提供这个页面：<b>验收要靠看得见的东西</b>。
 * 骨架搭好后，如果只有一堆 jar 和一个空白的 hello world，
 * 老师和组员都无法判断「数据源通了没有」「表建对了没有」。
 * 本页把数据库连通性、四张表的结构与行数、种子数据直接摊开，
 * 迭代 2 评审时可以当作 T2-02 / T2-03 / T2-06 的现场证据。</p>
 *
 * @author 胡成溯（组长）
 */
@Controller
public class AdminHomeController {

    private final AuthService authService;
    private final CategoryMapper categoryMapper;
    private final ProductMapper productMapper;
    private final IntentMapper intentMapper;
    private final JdbcTemplate jdbcTemplate;
    private final String datasourceUrl;

    public AdminHomeController(AuthService authService,
                               CategoryMapper categoryMapper,
                               ProductMapper productMapper,
                               IntentMapper intentMapper,
                               JdbcTemplate jdbcTemplate,
                               @Value("${spring.datasource.url}") String datasourceUrl) {
        this.authService = authService;
        this.categoryMapper = categoryMapper;
        this.productMapper = productMapper;
        this.intentMapper = intentMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.datasourceUrl = datasourceUrl;
    }

    /**
     * 后台首页。
     *
     * <p>展示：当前账号、数据概览、三个模块的接入状态、修改密码表单。
     * 商品模块（黄程宇）与门户模块（周到）的页面尚未接入时，
     * 首页只给出入口卡片，不会 404 —— 避免并行开发期互相阻塞演示。</p>
     */
    @GetMapping("/admin")
    public String adminHome(@RequestParam(value = "msg", required = false) String msg,
                            @RequestParam(value = "error", required = false) String error,
                            HttpServletRequest request,
                            Model model) {
        String username = currentUsername(request);
        Seller seller = username == null ? null : authService.findByUsername(username);

        model.addAttribute("username", username);
        model.addAttribute("sellerCreatedAt", seller == null ? "" : TimeUtils.formatDate(seller.getCreatedAt()));
        model.addAttribute("msg", msg);
        model.addAttribute("error", error);

        // 数据概览：直接调 Mapper，顺便验证 T2-06 的 Mapper 可正常注入与查询
        model.addAttribute("categoryCount", categoryMapper.selectCount(null));
        model.addAttribute("productTotal", productMapper.selectCount(null));
        model.addAttribute("productOnSale", productMapper.countByStatus(ProductStatus.ON_SALE));
        model.addAttribute("productDelisted", productMapper.countByStatus(ProductStatus.DELISTED));
        model.addAttribute("intentPending", intentMapper.countByStatus(IntentStatus.PENDING));
        model.addAttribute("intentTotal", intentMapper.selectCount(null));

        return "auth/admin-home";
    }

    /**
     * 基座自检页：把 SQLite 的真实结构摊开。
     *
     * <p>这就是 T2-02「能对 SQLite 执行增删改查」和 T2-03「字段与规格书一致」的现场证据：
     * 表有哪些、每列什么类型、主键是谁、现在几行数据，一目了然。</p>
     */
    @GetMapping("/admin/diagnostics")
    public String diagnostics(HttpServletRequest request, Model model) {
        model.addAttribute("username", currentUsername(request));
        model.addAttribute("datasourceUrl", datasourceUrl);
        model.addAttribute("dbFileExists", java.nio.file.Files.exists(java.nio.file.Path.of("data", "shop.db")));
        model.addAttribute("javaVersion", System.getProperty("java.version"));
        model.addAttribute("osName", System.getProperty("os.name"));

        // 1. 列出所有业务表
        List<String> tableNames = jdbcTemplate.queryForList(
                "SELECT name FROM sqlite_master WHERE type = 'table' "
                        + "AND name NOT LIKE 'sqlite_%' ORDER BY name",
                String.class);

        List<Map<String, Object>> tables = new ArrayList<>();
        for (String tableName : tableNames) {
            Map<String, Object> table = new LinkedHashMap<>();
            table.put("name", tableName);
            // 行数
            Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Long.class);
            table.put("rowCount", count == null ? 0L : count);
            // 列结构（PRAGMA table_info：cid, name, type, notnull, dflt_value, pk）
            table.put("columns", jdbcTemplate.queryForList("PRAGMA table_info(" + tableName + ")"));
            tables.add(table);
        }
        model.addAttribute("tables", tables);

        // 2. 分类字典（黄程宇的发布页下拉框数据源，顺便证明种子数据生效）
        List<Category> categories = categoryMapper.selectEnabled();
        model.addAttribute("categories", categories);

        return "auth/diagnostics";
    }

    /** 取当前登录用户名 */
    private String currentUsername(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object value = session.getAttribute(SessionKeys.LOGIN_SELLER);
        return value == null ? null : value.toString();
    }
}
