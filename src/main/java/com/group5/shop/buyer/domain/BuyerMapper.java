package com.group5.shop.buyer.domain;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 买家 Mapper（T2-02）。
 *
 * <p>只有买家模块与卖家后台的客户管理模块可以调用本 Mapper；
 * 商品中心（黄程宇）与买家门户（周到）不得直接查 {@code buyer} 表，
 * 需要买家信息时请调 {@link com.group5.shop.buyer.context.BuyerContext#current()}。</p>
 *
 * @author 胡成溯（组长）
 */
@Mapper
public interface BuyerMapper extends BaseMapper<Buyer> {

    /**
     * 按用户名查账号（登录、注册查重用）。
     *
     * @param username 用户名
     * @return 买家；不存在返回 null
     */
    @Select("SELECT id, username, password_hash, salt, phone, default_place, created_at "
            + "FROM buyer WHERE username = #{username} LIMIT 1")
    Buyer selectByUsername(@Param("username") String username);

    /**
     * 统计同名账号数量（注册查重）。
     *
     * @param username 用户名
     * @return 数量，0 表示可注册
     */
    @Select("SELECT COUNT(*) FROM buyer WHERE username = #{username}")
    int countByUsername(@Param("username") String username);

    /**
     * 注册客户总数（卖家后台「客户管理」顶部统计用）。
     *
     * @return 客户总数
     */
    @Select("SELECT COUNT(*) FROM buyer")
    long countAll();
}
