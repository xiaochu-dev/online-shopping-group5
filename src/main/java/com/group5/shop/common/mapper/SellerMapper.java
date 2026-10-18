package com.group5.shop.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.group5.shop.common.entity.Seller;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 卖家 Mapper（T2-06）。
 *
 * <p>继承 {@link BaseMapper} 后已自带 {@code insert / deleteById / updateById / selectById /
 * selectList / selectPage} 等通用方法，无需再写。此处只补一个按用户名查询。</p>
 *
 * @author 胡成溯（组长）
 */
@Mapper
public interface SellerMapper extends BaseMapper<Seller> {

    /**
     * 按用户名查账号（登录用）。
     *
     * @param username 用户名
     * @return 账号；不存在返回 null
     */
    @Select("SELECT id, username, password_hash, salt, created_at FROM seller WHERE username = #{username} LIMIT 1")
    Seller selectByUsername(@Param("username") String username);
}
