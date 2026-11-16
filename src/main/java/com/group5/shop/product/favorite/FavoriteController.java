package com.group5.shop.product.favorite;

import com.group5.shop.buyer.context.BuyerContext;
import com.group5.shop.buyer.domain.Buyer;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Controller
public class FavoriteController {

    private static final String FAVORITES = "/my/favorites";
    private static final String FAVORITES_REDIRECT = "redirect:" + FAVORITES;

    private final FavoriteService favorites;
    private final BuyerContext buyerContext;

    public FavoriteController(FavoriteService favorites, BuyerContext buyerContext) {
        this.favorites = favorites;
        this.buyerContext = buyerContext;
    }

    @GetMapping(FAVORITES)
    public String list(Model model) {
        Buyer buyer = buyerContext.current();
        if (buyer == null) {
            return loginRedirect(FAVORITES);
        }

        model.addAttribute("buyerName", buyer.getUsername());
        model.addAttribute("favorites", favorites.list(buyer.getId()));
        return "product/favorites";
    }

    @PostMapping("/products/{productId}/favorite")
    public String add(@PathVariable long productId, RedirectAttributes flash) {
        Long buyerId = buyerContext.currentId();
        if (buyerId == null) {
            return loginRedirect("/products/" + productId);
        }

        if (favorites.addToFavorites(buyerId, List.of(productId)) == 1) {
            flash.addFlashAttribute("msg", "已加入收藏");
        } else {
            flash.addFlashAttribute("error", "商品已收藏或当前不可收藏");
        }
        return FAVORITES_REDIRECT;
    }

    @PostMapping("/products/{productId}/favorite/remove")
    public String remove(@PathVariable long productId, RedirectAttributes flash) {
        Long buyerId = buyerContext.currentId();
        if (buyerId == null) {
            return loginRedirect(FAVORITES);
        }

        if (favorites.remove(buyerId, productId)) {
            flash.addFlashAttribute("msg", "已取消收藏");
        }
        return FAVORITES_REDIRECT;
    }

    private String loginRedirect(String path) {
        return "redirect:/buyer/login?redirect=" + UriUtils.encode(path, StandardCharsets.UTF_8);
    }
}
