package com.group5.shop.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置（T2-02）。
 *
 * <p>只做一件事：注册分页插件。没有它，{@code selectPage()} 不会真正分页，
 * 而是把全表查出来再在内存里截断 —— 数据量一大就崩，且 total 恒为 0。</p>
 *
 * <p><b>必须显式指定 {@link DbType#SQLITE}</b>：分页插件要按数据库方言生成
 * {@code LIMIT ? OFFSET ?}。不指定时插件会从 JDBC 连接自动探测，
 * 某些驱动版本探测失败会退化成「一次查全部」，这里写死更稳。</p>
 *
 * @author 胡成溯（组长）
 */
@Configuration
public class MybatisPlusConfig {

    /** 单页最大条数，防止前端传 pageSize=999999 把库拖死 */
    private static final long MAX_PAGE_SIZE = 100L;

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.SQLITE);
        pagination.setMaxLimit(MAX_PAGE_SIZE);
        // 页码超出总页数时返回空列表，而不是自动跳回第一页（避免用户看到重复数据）
        pagination.setOverflow(false);

        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
