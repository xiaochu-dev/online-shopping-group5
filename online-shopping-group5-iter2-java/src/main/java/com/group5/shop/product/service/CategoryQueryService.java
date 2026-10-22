package com.group5.shop.product.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.group5.shop.product.domain.*;
import com.group5.shop.portal.contract.CategoryNode;
import com.group5.shop.portal.contract.CategoryPort;
import com.group5.shop.common.exception.*;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@Primary
public class CategoryQueryService implements CategoryPort {
    private final CategoryMapper categories;
    private final ProductMapper products;
    public CategoryQueryService(CategoryMapper categories, ProductMapper products) { this.categories=categories; this.products=products; }
    public List<Category> listAll() {
        return categories.selectList(Wrappers.<Category>lambdaQuery().orderByAsc(Category::getSortNo, Category::getId));
    }
    @Override
    public List<CategoryNode> listTree() {
        List<Category> rows = listAll().stream().filter(c -> "enabled".equals(c.getStatus())).toList();
        return rows.stream().filter(c -> c.getParentId()==null).map(top -> CategoryNode.parent(top.getId(),top.getName(),
            rows.stream().filter(c -> top.getId().equals(c.getParentId())).map(c -> CategoryNode.leaf(c.getId(),c.getName())).toList())).toList();
    }
    public Category requireLeaf(Long id) {
        Category leaf = id==null ? null : categories.selectById(id);
        Category parent = leaf==null || leaf.getParentId()==null ? null : categories.selectById(leaf.getParentId());
        if (leaf==null || parent==null || !"enabled".equals(leaf.getStatus()) || !"enabled".equals(parent.getStatus())) throw bad("请选择启用中的二级分类");
        return leaf;
    }
    @Transactional
    public void create(String name, Long parentId, Integer sortNo) {
        if (name==null || name.isBlank() || name.trim().length()>80) throw bad("分类名称须为1～80个字符");
        if (parentId!=null) {
            Category parent=categories.selectById(parentId);
            if (parent==null || parent.getParentId()!=null || !"enabled".equals(parent.getStatus())) throw bad("只能在启用的一级分类下新增二级分类");
        }
        var query=Wrappers.<Category>lambdaQuery().eq(Category::getName,name.trim());
        if(parentId==null) query.isNull(Category::getParentId); else query.eq(Category::getParentId,parentId);
        if(categories.selectCount(query)>0) throw bad("同一父级下分类名称不能重复");
        Category c=new Category(); c.setName(name.trim()); c.setParentId(parentId); c.setSortNo(sortNo==null?0:sortNo);
        c.setStatus("enabled"); c.setCreatedAt(System.currentTimeMillis()); c.setUpdatedAt(c.getCreatedAt());
        try { categories.insert(c); } catch(DataIntegrityViolationException e) { throw bad("同一父级下分类名称不能重复"); }
    }
    @Transactional
    public void setEnabled(Long id, boolean enabled) {
        Category c=categories.selectById(id); if(c==null) throw bad("分类不存在");
        c.setStatus(enabled?"enabled":"disabled"); c.setUpdatedAt(System.currentTimeMillis()); categories.updateById(c);
    }
    @Transactional
    public void delete(Long id) {
        if(categories.selectCount(Wrappers.<Category>lambdaQuery().eq(Category::getParentId,id))>0 ||
           products.selectCount(Wrappers.<Product>lambdaQuery().eq(Product::getCategoryId,id))>0) throw bad("分类有子类或商品引用，只能停用");
        if(categories.deleteById(id)==0) throw bad("分类不存在");
    }
    private BusinessException bad(String text) { return new BusinessException(ErrorCode.PARAM_INVALID,text); }
}
