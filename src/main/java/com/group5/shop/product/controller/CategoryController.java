package com.group5.shop.product.controller;

import com.group5.shop.product.service.CategoryQueryService;
import com.group5.shop.common.exception.BusinessException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/categories")
public class CategoryController {
    private final CategoryQueryService service;
    public CategoryController(CategoryQueryService service) { this.service=service; }
    @GetMapping
    public String list(Model model) { model.addAttribute("categories",service.listAll()); return "product/categories"; }
    @PostMapping
    public String create(@RequestParam(defaultValue="") String name,@RequestParam(required=false) Long parentId,@RequestParam(defaultValue="0") Integer sortNo,RedirectAttributes flash) {
        return action(() -> service.create(name,parentId,sortNo),flash);
    }
    @PostMapping("/{id}/status")
    public String status(@PathVariable Long id,@RequestParam boolean enabled,RedirectAttributes flash) { return action(() -> service.setEnabled(id,enabled),flash); }
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id,RedirectAttributes flash) { return action(() -> service.delete(id),flash); }
    private String action(Runnable action,RedirectAttributes flash) {
        try { action.run(); flash.addFlashAttribute("msg","操作成功"); } catch(BusinessException e) { flash.addFlashAttribute("error",e.getMessage()); }
        return "redirect:/admin/categories";
    }
}
