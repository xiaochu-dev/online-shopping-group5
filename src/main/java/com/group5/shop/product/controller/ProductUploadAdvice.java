package com.group5.shop.product.controller;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProductUploadAdvice {
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String tooLarge(MaxUploadSizeExceededException e,RedirectAttributes flash) {
        flash.addFlashAttribute("error","图片总量过大：最多9张，单张不超过5MB");
        return "redirect:/admin/products/new";
    }
}
