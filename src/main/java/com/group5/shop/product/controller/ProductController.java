package com.group5.shop.product.controller;

import com.group5.shop.product.service.*;
import com.group5.shop.common.constant.SessionKeys;
import com.group5.shop.common.exception.BusinessException;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.util.List;

@Controller
@RequestMapping("/admin/products")
public class ProductController {
    private final ProductService products;
    private final CategoryQueryService categories;
    private final StockService stock;
    public ProductController(ProductService products,CategoryQueryService categories,StockService stock) { this.products=products; this.categories=categories; this.stock=stock; }
    @GetMapping
    public String list(@RequestParam(required=false) String status,HttpSession session,Model model) {
        model.addAttribute("products",products.list(seller(session),status)); model.addAttribute("selectedStatus",status==null?"":status);
        return "product/list";
    }
    @GetMapping({"/new","/publish"})
    public String form(Model model) { model.addAttribute("tree",categories.listTree()); return "product/publish"; }
    @PostMapping
    public String publish(@RequestParam(defaultValue="") String name,@RequestParam(defaultValue="") String price,
            @RequestParam(required=false) Long categoryId,@RequestParam(defaultValue="-1") int stock,
            @RequestParam(defaultValue="") String description,@RequestParam(required=false) List<MultipartFile> images,
            @RequestParam(required=false) List<Integer> imageOrder,@RequestParam(required=false) Integer primaryIndex,
            HttpSession session,RedirectAttributes flash) {
        try { products.publish(seller(session),name,price,categoryId,stock,description,images,imageOrder,primaryIndex); flash.addFlashAttribute("msg","商品发布成功"); return "redirect:/admin/products"; }
        catch(BusinessException e) { flash.addFlashAttribute("error",e.getMessage()); return "redirect:/admin/products/new"; }
    }
    @PostMapping("/{id}/stock")
    public String stock(@PathVariable Long id,@RequestParam int stock,HttpSession session,RedirectAttributes flash) {
        return action(() -> this.stock.changeStock(seller(session),id,stock),flash);
    }
    @PostMapping("/{id}/off-shelf")
    public String off(@PathVariable Long id,HttpSession session,RedirectAttributes flash) { return action(() -> stock.offShelf(seller(session),id),flash); }
    @PostMapping("/{id}/relist")
    public String relist(@PathVariable Long id,HttpSession session,RedirectAttributes flash) { return action(() -> stock.relist(seller(session),id),flash); }
    private String action(Runnable action,RedirectAttributes flash) {
        try { action.run(); flash.addFlashAttribute("msg","操作成功"); } catch(BusinessException e) { flash.addFlashAttribute("error",e.getMessage()); }
        return "redirect:/admin/products";
    }
    private Long seller(HttpSession session) { return ((Number)session.getAttribute(SessionKeys.LOGIN_SELLER_ID)).longValue(); }
}
