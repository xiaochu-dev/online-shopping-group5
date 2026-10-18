package com.group5.shop.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.group5.shop.common.entity.Category;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 商品分类 Mapper（T2-06）。
 *
 * <p>黄程宇的分类维护页（T2-09）直接用 {@link BaseMapper} 的增删改查；
 * 发布页的分类下拉框用 {@link #selectEnabled()}。</p>
 *
 * @author 胡成溯（组长）
 */
@Mapper
public interface CategoryMapper extends BaseMapper<Category> {

    /**
     * 查询所有「启用」状态的分类，按 sort_no 升序。
     *
     * @return 分类列表；无数据返回空列表（不会返回 null）
     */
    @Select("SELECT id, name, sort_no, status, created_at, updated_at "
            + "FROM category WHERE status = 'enabled' ORDER BY sort_no ASC, id ASC")
    List<Category> selectEnabled();
}
