package com.group5.shop.portal.contract;

import java.util.ArrayList;
import java.util.List;

/**
 * 两级分类树节点（A.1.2）。
 *
 * <p>本迭代只允许两级：{@code children} 只在一级节点上有值，二级节点的 {@code children} 恒为空。
 * 分类的增删改归商品中心（黄程宇 T2-08），门户侧只读这棵树做筛选下拉框。</p>
 *
 * @param id       分类主键
 * @param name     分类名
 * @param children 子分类；二级节点为空列表（不是 null，省掉页面判空）
 * @author 周到
 */
public record CategoryNode(Long id, String name, List<CategoryNode> children) {

    /** 无子节点的构造：二级节点与「分类能力未就绪时的降级结果」都用它 */
    public static CategoryNode leaf(Long id, String name) {
        return new CategoryNode(id, name, List.of());
    }

    /** 一级节点 */
    public static CategoryNode parent(Long id, String name, List<CategoryNode> children) {
        return new CategoryNode(id, name, children == null ? List.of() : children);
    }

    /** 是否一级分类 */
    public boolean isTopLevel() {
        return !children.isEmpty();
    }

    /**
     * 本节点及其全部后代 id。
     *
     * <p>买家选了一级分类时，列表要能筛出它下面所有二级分类的商品，
     * 否则「选了数码电子却一件都搜不到」——这正是 T2-13 要修的问题。</p>
     *
     * @return id 集合，至少含本节点
     */
    public List<Long> selfAndDescendantIds() {
        List<Long> ids = new ArrayList<>();
        collect(this, ids);
        return ids;
    }

    private static void collect(CategoryNode node, List<Long> sink) {
        sink.add(node.id());
        for (CategoryNode child : node.children()) {
            collect(child, sink);
        }
    }
}
