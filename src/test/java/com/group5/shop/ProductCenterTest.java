package com.group5.shop;

import com.group5.shop.product.service.*;
import com.group5.shop.product.domain.*;
import com.group5.shop.product.util.RichTextSanitizer;
import com.group5.shop.common.constant.SessionKeys;
import com.group5.shop.common.exception.BusinessException;
import com.group5.shop.portal.service.SellerIntentService;
import com.group5.shop.portal.domain.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.multipart.MultipartFile;
import java.nio.file.*;
import java.io.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ProductCenterTest {
    static final String KEY=UUID.randomUUID().toString();
    static final Path DB=Path.of("target","product-"+KEY+".db").toAbsolutePath();
    static final Path UPLOADS=Path.of("target","uploads-"+KEY).toAbsolutePath();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->"jdbc:sqlite:"+DB.toString().replace('\\','/'));
        r.add("shop.product.upload-dir",()->UPLOADS.toString());
    }
    @Autowired ProductService service;
    @Autowired CategoryQueryService categories;
    @Autowired ProductMapper mapper;
    @Autowired StockService stock;
    @Autowired ProductDetailService details;
    @Autowired RichTextSanitizer sanitizer;
    @Autowired JdbcTemplate jdbc;
    @Autowired SellerIntentService trades;
    @Autowired IntentMapper intents;
    @Autowired MockMvc mvc;
    Long leaf;
    @BeforeEach void category() {
        String name="测试"+UUID.randomUUID();
        categories.create(name,1L,0);
        leaf=categories.listTree().stream().filter(t->t.id().equals(1L)).flatMap(t->t.children().stream()).filter(t->t.name().equals(name)).findFirst().orElseThrow().id();
    }
    Product publish(int quantity) { return service.publish(1L,"键盘","29.90",leaf,quantity,"<p>描述 <strong>完好</strong></p>",List.of(),List.of(),0); }
    MockMultipartFile image(String name) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",out);
        return new MockMultipartFile("images",name,"image/png",out.toByteArray());
    }
    @Test void twoLevelsAndSiblingNames() {
        categories.create("同名",1L,0); categories.create("同名",2L,0);
        assertThatThrownBy(()->categories.create("同名",1L,0)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->categories.create("第三级",leaf,0)).isInstanceOf(BusinessException.class);
    }
    @Test void disablingParentHidesSubtree() {
        categories.create("临时父级",null,0);
        Long top=categories.listAll().stream().filter(c->c.getName().equals("临时父级")).findFirst().orElseThrow().getId();
        categories.create("临时子级",top,0); categories.setEnabled(top,false);
        assertThat(categories.listTree()).noneMatch(t->t.id().equals(top));
    }
    @Test void referencedCategoryCannotDelete() {
        publish(2);
        assertThatThrownBy(()->categories.delete(leaf)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->categories.delete(1L)).isInstanceOf(BusinessException.class);
        categories.setEnabled(leaf,false);
        assertThatThrownBy(()->publish(1)).isInstanceOf(BusinessException.class);
    }
    @Test void publishThreeAndValidateFields() {
        for(int i=0;i<3;i++) assertThat(publish(2).getStatus()).isEqualTo("on_sale");
        assertThatThrownBy(()->service.publish(1L," ","1",leaf,1,"",null,null,null)).isInstanceOf(BusinessException.class);
        for(String price:List.of("0","-1","1.001","abc")) assertThatThrownBy(()->service.publish(1L,"x",price,leaf,1,"",null,null,null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.publish(1L,"x","1",1L,1,"",null,null,null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.publish(1L,"x","1",null,1,"",null,null,null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->publish(-1)).isInstanceOf(BusinessException.class);
    }
    @Test void zeroStockThenRefillNeedsExplicitRelist() {
        Product p=publish(0); assertThat(p.getStatus()).isEqualTo("off_shelf");
        stock.changeStock(1L,p.getId(),3); assertThat(details.get(p.getId()).getStatus()).isEqualTo("off_shelf");
        stock.relist(1L,p.getId()); assertThat(details.get(p.getId()).getStatus()).isEqualTo("on_sale");
    }
    @Test void fiveConcurrentDeductionsNeverOversell() throws Exception {
        Product p=publish(3); ExecutorService pool=Executors.newFixedThreadPool(5); CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<Boolean>> results=new ArrayList<>();
            for(int i=0;i<5;i++) results.add(pool.submit(()->{start.await();try {stock.deduct(p.getId()); return true;} catch(BusinessException e){return false;}}));
            start.countDown(); int successes=0; for(Future<Boolean> result:results) if(result.get(10,TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(3); assertThat(stock.stockOf(p.getId())).isZero();
            assertThat(details.get(p.getId()).getStatus()).isEqualTo("off_shelf");
            assertThat(details.get(p.getId()).getOffReason()).isEqualTo("auto");
            stock.release(p.getId()); assertThat(details.get(p.getId()).getStatus()).isEqualTo("off_shelf");
        } finally {pool.shutdownNow();}
    }
    @Test void manualOffShelfAndOwnership() {
        Product p=publish(3); assertThatThrownBy(()->stock.offShelf(2L,p.getId())).isInstanceOf(BusinessException.class);
        stock.offShelf(1L,p.getId()); assertThat(details.get(p.getId()).getOffReason()).isEqualTo("manual");
        stock.changeStock(1L,p.getId(),4); assertThat(details.get(p.getId()).getStatus()).isEqualTo("off_shelf");
        stock.relist(1L,p.getId()); assertThat(stock.stockOf(p.getId())).isEqualTo(4);
        stock.offShelf(1L,p.getId()); stock.changeStock(1L,p.getId(),0);
        assertThat(details.get(p.getId()).getOffReason()).isEqualTo("auto");
        assertThat(mapper.countByStatus("delisted")).isGreaterThanOrEqualTo(1);
    }
    Long buyer() {
        String name="买家"+UUID.randomUUID();
        jdbc.update("INSERT INTO buyer(username,password_hash,salt,phone,default_place,created_at) VALUES(?,'hash','salt','123','校门',1)",name);
        return jdbc.queryForObject("SELECT id FROM buyer WHERE username=?",Long.class,name);
    }
    Long intent(Long product) {
        Intent i=new Intent(); i.setProductId(product);i.setBuyerId(buyer());i.setBuyerName("买家");i.setPhone("123");i.setTradeAddress("校门");i.setStatus("pending");i.setCreatedAt(System.currentTimeMillis());intents.insert(i);return i.getId();
    }
    @Test void lastSaleFailsQueueInSameTransaction() {
        Product p=publish(1); Long first=intent(p.getId()),second=intent(p.getId());
        trades.confirmTrade(first); assertThat(stock.stockOf(p.getId())).isEqualTo(1);
        trades.markSuccess(first); assertThat(intents.selectById(first).getStatus()).isEqualTo("success");
        assertThat(intents.selectById(second).getStatus()).isEqualTo("failed");
        assertThat(intents.selectById(second).getFinishedAt()).isNotNull();
        assertThat(details.get(p.getId()).getStatus()).isEqualTo("off_shelf");
    }
    @Test void failureDoesNotDeductAndManualOffDuringTradeStaysOff() {
        Product p=publish(2); Long first=intent(p.getId()); trades.confirmTrade(first);
        assertThatThrownBy(()->stock.changeStock(1L,p.getId(),0)).isInstanceOf(BusinessException.class);
        stock.offShelf(1L,p.getId()); trades.markFailed(first,false,"未达成"); assertThat(stock.stockOf(p.getId())).isEqualTo(2);
        assertThat(details.get(p.getId()).getOffReason()).isEqualTo("manual");
        stock.relist(1L,p.getId()); assertThat(details.get(p.getId()).getStatus()).isEqualTo("on_sale");
    }
    @Test void queueFailureRollsBackDeduction() {
        Product p=publish(1); Long pending=intent(p.getId());
        jdbc.execute("CREATE TRIGGER deny_queue BEFORE UPDATE ON intent WHEN OLD.product_id="+p.getId()+" BEGIN SELECT RAISE(ABORT,'test failure'); END");
        try {
            assertThatThrownBy(()->stock.deduct(p.getId())).isInstanceOf(RuntimeException.class);
            assertThat(stock.stockOf(p.getId())).isEqualTo(1);
            assertThat(details.get(p.getId()).getStatus()).isEqualTo("on_sale");
            assertThat(intents.selectById(pending).getStatus()).isEqualTo("pending");
        } finally {jdbc.execute("DROP TRIGGER deny_queue");}
    }
    @Test void nineImagesSortedAndPrimary() throws Exception {
        List<MultipartFile> files=new ArrayList<>();for(int i=0;i<9;i++)files.add(image(i+".png"));
        Product p=service.publish(1L,"图集","1",leaf,1,"",files,List.of(8,7,6,5,4,3,2,1,0),4);
        assertThat(details.imagesOf(p.getId())).hasSize(9).startsWith(p.getImagePath());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_image WHERE product_id=? AND is_primary=1",Integer.class,p.getId())).isEqualTo(1);
        files.add(image("10.png"));
        assertThatThrownBy(()->service.publish(1L,"图集","1",leaf,1,"",files,null,0)).isInstanceOf(BusinessException.class);
    }
    @Test void rejectsInvalidImagesBeforeWriting() throws Exception {
        for(MockMultipartFile f:List.of(new MockMultipartFile("images","x.png","image/png",new byte[0]),
             new MockMultipartFile("images","x.png","image/png",new byte[5*1024*1024+1]),image("x.gif"),
             new MockMultipartFile("images","x.jpg","image/jpeg",image("x.png").getBytes()),
             new MockMultipartFile("images","x.png","image/png",new byte[]{(byte)137,80,78,71,13,10,26,10}))) {
            assertThatThrownBy(()->service.publish(1L,"坏图","1",leaf,1,"",List.of(f),null,0)).isInstanceOf(BusinessException.class);
        }
        assertThat(publish(1).getImagePath()).isEqualTo("images/placeholder.png");
    }
    @Test void preservesFormattingButRemovesActiveContent() {
        String html=sanitizer.clean("<p>介绍<strong>加粗</strong><img src='https://example.com/a.png' onerror='alert(1)'></p><script>alert(1)</script><iframe src='https://evil.example/embed/x'></iframe><iframe src='https://player.bilibili.com/player.html?bvid=BV1xx'></iframe>");
        assertThat(html).contains("<strong>加粗</strong>","<img","player.bilibili.com","sandbox=").doesNotContain("onerror","<script","evil.example");
        for(String src:List.of("javascript:alert(1)","https://player.bilibili.com.evil.example/player.html","https://player.bilibili.com@evil.example/player.html","//player.bilibili.com/player.html")) assertThat(sanitizer.clean("<iframe src='"+src+"'></iframe>")).doesNotContain("<iframe");
    }
    @Test void pagesRenderAndPublishingIsProtected() throws Exception {
        mvc.perform(get("/admin/products")).andExpect(status().is3xxRedirection());
        for(String path:List.of("/admin/products","/admin/products/new","/admin/categories"))
            mvc.perform(get(path).sessionAttr(SessionKeys.LOGIN_SELLER,"admin").sessionAttr(SessionKeys.LOGIN_SELLER_ID,1L)).andExpect(status().isOk());
        mvc.perform(post("/admin/products/1/edit").sessionAttr(SessionKeys.LOGIN_SELLER,"admin").sessionAttr(SessionKeys.LOGIN_SELLER_ID,1L)).andExpect(status().isNotFound());
    }
}
