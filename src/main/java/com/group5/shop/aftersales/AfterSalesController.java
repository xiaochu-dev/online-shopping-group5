package com.group5.shop.aftersales;

import com.group5.shop.aftersales.AfterSalesRequest.Outcome;
import com.group5.shop.aftersales.AfterSalesService.Side;
import com.group5.shop.buyer.context.BuyerContext;
import com.group5.shop.buyer.domain.Buyer;
import com.group5.shop.common.exception.BusinessException;
import com.group5.shop.common.exception.ErrorCode;
import com.group5.shop.portal.contract.SellerContext;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
public class AfterSalesController {

    private static final String BUYER_PATH = "/my/after-sales";
    private static final String SELLER_PATH = "/admin/after-sales";
    private static final String APPLICATION_PATH = BUYER_PATH + "/new/{orderId}/{productId}";
    private static final String IMAGE_PATH = "/{requestId}/images/{imageId}";

    private final AfterSalesService afterSales;
    private final BuyerContext buyerContext;
    private final SellerContext sellerContext;

    public AfterSalesController(AfterSalesService afterSales, BuyerContext buyerContext, SellerContext sellerContext) {
        this.afterSales = afterSales;
        this.buyerContext = buyerContext;
        this.sellerContext = sellerContext;
    }

    @GetMapping(BUYER_PATH)
    public String buyerList(@RequestParam(defaultValue = "1") int page, Model model) {
        Buyer buyer = requireBuyer(model);
        return list(Side.BUYER, buyer.getId(), page, model);
    }

    @GetMapping(APPLICATION_PATH)
    public String application(@PathVariable long orderId, @PathVariable long productId, Model model) {
        Buyer buyer = requireBuyer(model);
        return afterSales.existing(buyer.getId(), orderId, productId)
                .map(id -> "redirect:" + BUYER_PATH + "/" + id)
                .orElseGet(() -> form(buyer.getId(), orderId, productId, model));
    }

    @PostMapping(APPLICATION_PATH)
    public String submit(@PathVariable long orderId, @PathVariable long productId,
                         @RequestParam(defaultValue = "") String title,
                         @RequestParam(defaultValue = "") String description,
                         @RequestParam(required = false) List<MultipartFile> images,
                         Model model, HttpServletResponse response) {
        Buyer buyer = requireBuyer(model);
        try {
            long id = afterSales.submit(buyer.getId(), orderId, productId, title, description, images);
            return "redirect:" + BUYER_PATH + "/" + id;
        } catch (BusinessException ex) {
            if (ex.getCode() != ErrorCode.PARAM_INVALID.getCode()) {
                throw ex;
            }
            response.setStatus(ex.getHttpStatus());
            model.addAttribute("error", ex.getMessage());
            model.addAttribute("title", title);
            model.addAttribute("description", description);
            return form(buyer.getId(), orderId, productId, model);
        }
    }

    @GetMapping(BUYER_PATH + "/{requestId}")
    public String buyerDetail(@PathVariable long requestId, Model model) {
        Buyer buyer = requireBuyer(model);
        return detail(Side.BUYER, buyer.getId(), requestId, model);
    }

    @GetMapping(BUYER_PATH + IMAGE_PATH)
    public ResponseEntity<byte[]> buyerImage(@PathVariable long requestId, @PathVariable long imageId) {
        long buyerId = requireIdentity(buyerContext.currentId());
        return imageResponse(afterSales.image(Side.BUYER, buyerId, requestId, imageId));
    }

    @GetMapping(SELLER_PATH)
    public String sellerList(@RequestParam(defaultValue = "1") int page, Model model) {
        return list(Side.SELLER, requireIdentity(sellerContext.currentId()), page, model);
    }

    @GetMapping(SELLER_PATH + "/{requestId}")
    public String sellerDetail(@PathVariable long requestId, Model model) {
        model.addAttribute("outcomes", Outcome.values());
        return detail(Side.SELLER, requireIdentity(sellerContext.currentId()), requestId, model);
    }

    @GetMapping(SELLER_PATH + IMAGE_PATH)
    public ResponseEntity<byte[]> sellerImage(@PathVariable long requestId, @PathVariable long imageId) {
        long sellerId = requireIdentity(sellerContext.currentId());
        return imageResponse(afterSales.image(Side.SELLER, sellerId, requestId, imageId));
    }

    @PostMapping(SELLER_PATH + "/{requestId}/negotiate")
    public String startNegotiation(@PathVariable long requestId, RedirectAttributes redirect) {
        try {
            afterSales.startNegotiation(requireIdentity(sellerContext.currentId()), requestId);
        } catch (BusinessException ex) {
            flashValidation(ex, redirect);
        }
        return "redirect:" + SELLER_PATH + "/" + requestId;
    }

    @PostMapping(SELLER_PATH + "/{requestId}/resolve")
    public String resolve(@PathVariable long requestId,
                          @RequestParam(defaultValue = "") String outcome,
                          @RequestParam(defaultValue = "") String note, RedirectAttributes redirect) {
        long sellerId = requireIdentity(sellerContext.currentId());
        try {
            afterSales.resolve(sellerId, requestId, parseOutcome(outcome), note);
            redirect.addFlashAttribute("message", "已登记处理结果，买家可在「我的售后」查看。");
        } catch (BusinessException ex) {
            flashValidation(ex, redirect);
            redirect.addFlashAttribute("selectedOutcome", outcome);
            redirect.addFlashAttribute("note", note);
        }
        return "redirect:" + SELLER_PATH + "/" + requestId;
    }

    private String form(long buyerId, long orderId, long productId, Model model) {
        model.addAttribute("orderId", orderId);
        model.addAttribute("productId", productId);
        model.addAttribute("purchase", afterSales.purchase(buyerId, orderId, productId));
        return "after-sales/apply";
    }

    private String list(Side side, long ownerId, int page, Model model) {
        setViewSide(side, model);
        model.addAttribute("pageResult", afterSales.list(side, ownerId, page));
        return "after-sales/list";
    }

    private String detail(Side side, long ownerId, long requestId, Model model) {
        setViewSide(side, model);
        model.addAttribute("claim", afterSales.detail(side, ownerId, requestId));
        model.addAttribute("images", afterSales.imageIds(side, ownerId, requestId));
        return "after-sales/detail";
    }

    private void setViewSide(Side side, Model model) {
        boolean seller = side == Side.SELLER;
        model.addAttribute("sellerView", seller);
        model.addAttribute("basePath", seller ? SELLER_PATH : BUYER_PATH);
    }

    private Buyer requireBuyer(Model model) {
        Buyer buyer = buyerContext.current();
        if (buyer == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        model.addAttribute("buyerName", buyer.getUsername());
        return buyer;
    }

    private long requireIdentity(Long id) {
        if (id == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return id;
    }

    private static Outcome parseOutcome(String value) {
        try {
            return Outcome.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static void flashValidation(BusinessException ex, RedirectAttributes redirect) {
        if (ex.getCode() != ErrorCode.PARAM_INVALID.getCode()) {
            throw ex;
        }
        redirect.addFlashAttribute("error", ex.getMessage());
    }

    private static ResponseEntity<byte[]> imageResponse(AfterSalesImage image) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(image.mediaType()))
                .body(image.content());
    }
}
