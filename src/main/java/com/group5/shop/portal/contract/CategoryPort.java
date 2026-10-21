package com.group5.shop.portal.contract;

import java.util.List;

/**
 * 分类树查询出口（对应任务表 {@code CategoryQueryService.listTree()}，提供方：黄程宇 T2-08）。
 *
 * @author 周到
 */
public interface CategoryPort {

    /**
     * 启用中的两级分类树，一级按排序号升序，二级跟随父级。
     *
     * @return 一级节点列表；无分类返回空列表
     */
    List<CategoryNode> listTree();

    /**
     * 按 id 找节点（含其子级），用于把手填的 {@code ?categoryId=} 归一。
     *
     * @param categoryId 分类 id，可为 null
     * @return 命中的节点；找不到返回 {@code null}
     */
    default CategoryNode find(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        for (CategoryNode top : listTree()) {
            if (top.id().equals(categoryId)) {
                return top;
            }
            for (CategoryNode child : top.children()) {
                if (child.id().equals(categoryId)) {
                    // 选中二级时只筛它自己，不再往上扩
                    return child;
                }
            }
        }
        return null;
    }
}
