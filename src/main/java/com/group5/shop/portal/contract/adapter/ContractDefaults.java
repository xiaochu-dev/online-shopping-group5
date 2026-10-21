package com.group5.shop.portal.contract.adapter;

import com.group5.shop.common.constant.ProductStatus;
import com.group5.shop.common.constant.SessionKeys;
import com.group5.shop.common.entity.Product;
import com.group5.shop.common.mapper.CategoryMapper;
import com.group5.shop.common.mapper.ProductMapper;
import com.group5.shop.common.util.TimeUtils;
import com.group5.shop.portal.contract.BuyerContext;
import com.group5.shop.portal.contract.BuyerProfile;
import com.group5.shop.portal.contract.CategoryNode;
import com.group5.shop.portal.contract.CategoryPort;
import com.group5.shop.portal.contract.ProductPort;
import com.group5.shop.portal.contract.SellerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpSession;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨模块契约的<b>临时适配实现</b>（过渡用，非最终架构）。
 *
 * <p>任务表规定四个出口由队友提供：{@code BuyerContext} / {@code SellerContext}（胡成溯 T2-03）、
 * {@code CategoryQueryService} / {@code StockService}（黄程宇 T2-08 / T2-10）。
 * 他们未合入前，门户若直接调用就编译不过、也演示不了。</p>
 *
 * <p><b>机制</b>：这里的每个 Bean 都带 {@link ConditionalOnMissingBean}——
 * 队友把正式 Service 注册成 Bean 的那一刻，本类对应实现<b>自动退让</b>，门户代码零改动。</p>
 *
 * <p><b>纪律</b>：本类只允许存在到三个正式接口全部合入为止，之后整个 {@code adapter} 包删除。
 * 不要在这里添加新能力，它只是为了「今天能跑」。</p>
 *
 * @author 周到
 */
@Configuration
public class ContractDefaults {

    private static final Logger log = LoggerFactory.getLogger(ContractDefaults.class);

    /** 与账号模块约定的买家会话键名；正式实现落地后此常量随之废弃 */
    private static final String SESSION_BUYER_ID = "LOGIN_BUYER_ID";

    // ==================================================================
    // 买家登录态
    // ==================================================================

    @Bean
    @ConditionalOnMissingBean(BuyerContext.class)
    public BuyerContext buyerContextDefault(SchemaCapability caps, PortalProbeMapper probe) {
        return new BuyerContext() {

            @Override
            public Long currentId() {
                Object value = sessionAttribute(SESSION_BUYER_ID);
                return value == null ? null : toLong(value);
            }

            @Override
            public BuyerProfile profile(Long buyerId) {
                if (buyerId == null || !caps.hasBuyerTable()) {
                    return null;
                }
                Map<String, Object> row = probe.selectBuyer(buyerId);
                if (row == null) {
                    return null;
                }
                return new BuyerProfile(
                        toLong(row.get("id")),
                        str(row.get("username")),
                        str(row.get("phone")),
                        str(row.get("default_place")));
            }
        };
    }

    // ==================================================================
    // 卖家登录态（现有登录模块已具备，直接复用其会话键）
    // ==================================================================

    @Bean
    @ConditionalOnMissingBean(SellerContext.class)
    public SellerContext sellerContextDefault() {
        return new SellerContext() {

            @Override
            public Long currentId() {
                Object value = sessionAttribute(SessionKeys.LOGIN_SELLER_ID);
                return value == null ? null : toLong(value);
            }

            @Override
            public String currentName() {
                Object value = sessionAttribute(SessionKeys.LOGIN_SELLER);
                return value == null ? null : value.toString();
            }
        };
    }

    // ==================================================================
    // 分类树
    // ==================================================================

    @Bean
    @ConditionalOnMissingBean(CategoryPort.class)
    public CategoryPort categoryPortDefault(SchemaCapability caps,
                                            PortalProbeMapper probe,
                                            CategoryMapper categoryMapper) {
        return () -> {
            // 单级降级：商品中心还没加 parent_id 时，把每个分类当一级展示
            if (!caps.hasCategoryParent()) {
                List<CategoryNode> flat = new ArrayList<>();
                categoryMapper.selectEnabled()
                        .forEach(c -> flat.add(CategoryNode.leaf(c.getId(), c.getName())));
                return flat;
            }

            List<Map<String, Object>> rows = probe.selectEnabledCategoryRows();
            Map<Long, List<CategoryNode>> childrenByParent = new LinkedHashMap<>();
            List<Map<String, Object>> tops = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Long parentId = toLong(row.get("parent_id"));
                if (parentId == null || parentId == 0L) {
                    tops.add(row);
                } else {
                    childrenByParent
                            .computeIfAbsent(parentId, k -> new ArrayList<>())
                            .add(CategoryNode.leaf(toLong(row.get("id")), str(row.get("name"))));
                }
            }

            List<CategoryNode> tree = new ArrayList<>();
            for (Map<String, Object> top : tops) {
                Long id = toLong(top.get("id"));
                tree.add(CategoryNode.parent(id, str(top.get("name")),
                        childrenByParent.getOrDefault(id, List.of())));
            }
            return tree;
        };
    }

    // ==================================================================
    // 库存与商品状态
    // ==================================================================

    @Bean
    @ConditionalOnMissingBean(ProductPort.class)
    public ProductPort productPortDefault(SchemaCapability caps,
                                          PortalProbeMapper probe,
                                          ProductMapper productMapper) {
        return new ProductPort() {

            @Override
            public void freeze(Long productId) {
                updateStatus(productId, ProductStatus.FROZEN);
            }

            @Override
            public void release(Long productId) {
                updateStatus(productId, ProductStatus.ON_SALE);
            }

            @Override
            public void deduct(Long productId) {
                if (!caps.hasProductStock()) {
                    // 过渡期：商品表还没有 stock 字段，库存按「不限」处理，成交只记账不扣数
                    log.debug("product.stock 未就绪，跳过库存扣减（productId={}）", productId);
                    return;
                }
                int affected = probe.deductStock(productId, TimeUtils.nowMillis());
                if (affected == 0) {
                    throw new com.group5.shop.common.exception.BusinessException(
                            com.group5.shop.common.exception.ErrorCode.PARAM_INVALID,
                            "该商品库存不足，无法标记交易成功");
                }
            }

            @Override
            public Integer stockOf(Long productId) {
                if (!caps.hasProductStock()) {
                    return null;
                }
                return probe.selectStock(productId);
            }

            @Override
            public List<String> imagesOf(Long productId) {
                if (!caps.hasProductImage()) {
                    return List.of();
                }
                List<String> paths = probe.selectImagePaths(productId);
                return paths == null ? List.of() : paths;
            }

            /** 只改状态，不动库存：冻结/解冻与库存归零自动下架是两件事 */
            private void updateStatus(Long productId, String status) {
                Product patch = new Product();
                patch.setId(productId);
                patch.setStatus(status);
                patch.setUpdatedAt(TimeUtils.nowMillis());
                productMapper.updateById(patch);
            }
        };
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 取当前请求的会话属性；非 Web 线程（定时任务、测试）返回 null */
    private static Object sessionAttribute(String key) {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return null;
        }
        HttpSession session = attrs.getRequest().getSession(false);
        return session == null ? null : session.getAttribute(key);
    }

    /**
     * SQLite 的 INTEGER 列在不同取值下会返回 Integer 或 Long，
     * 统一转 Long，避免 {@code (Long) row.get("id")} 在数据量大时 ClassCastException。
     */
    static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String str(Object value) {
        return value == null ? "" : value.toString();
    }
}
