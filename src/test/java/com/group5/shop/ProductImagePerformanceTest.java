package com.group5.shop;

import com.group5.shop.product.service.ProductService;
import com.group5.shop.product.util.ProductImages;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.jsoup.Jsoup;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ProductImagePerformanceTest {
    static final Path ROOT=Path.of("target","image-test-"+UUID.randomUUID()).toAbsolutePath();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->"jdbc:sqlite:"+ROOT+".db");
        r.add("shop.product.upload-dir",()->ROOT.toString());
    }
    @Autowired ProductService products;
    @Autowired ProductImages images;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @BeforeEach void isolateRows() {
        jdbc.update("DELETE FROM payment");jdbc.update("DELETE FROM intent");
        jdbc.update("DELETE FROM product_image");jdbc.update("DELETE FROM product");
    }
    MockMultipartFile photo() throws Exception {
        BufferedImage picture=new BufferedImage(1200,800,BufferedImage.TYPE_INT_RGB);
        Random random=new Random(123);
        for(int y=0;y<800;y++)for(int x=0;x<1200;x++) picture.setRGB(x,y,random.nextInt(0x1000000));
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();ImageIO.write(picture,"jpg",bytes);
        return new MockMultipartFile("images","test.jpg","image/jpeg",bytes.toByteArray());
    }
    com.group5.shop.product.domain.Product publish(List<MockMultipartFile> files) {
        return products.publish(1L,"图片性能商品","29.90",104L,3,"",new ArrayList<>(files),null,0);
    }
    @Test void originalsRemainUnchangedAndSmallImagesAreServedFromConfiguredDirectory() throws Exception {
        var source=photo();var product=publish(List.of(source));
        String original=product.getImagePath(),small=images.small(original);
        assertThat(small).startsWith("uploads/thumbs/").isNotEqualTo(original);
        assertThat(Files.readAllBytes(ROOT.resolve(original.substring(8)))).isEqualTo(source.getBytes());
        byte[] data=mvc.perform(get("/"+small)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(data.length).isLessThan(source.getBytes().length);
        var decoded=ImageIO.read(new ByteArrayInputStream(data));
        assertThat(decoded.getWidth()).isEqualTo(400);assertThat(decoded.getHeight()).isEqualTo(267);
        mvc.perform(get("/"+original)).andExpect(status().isOk());
    }
    @Test void listsUseSmallImagesAndDetailSwitchesToOriginal() throws Exception {
        var first=publish(List.of(photo(),photo()));var second=publish(List.of(photo()));
        var list=Jsoup.parse(mvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        var covers=list.select("img.thumb");
        assertThat(covers).hasSize(2);assertThat(covers.get(0).attr("loading")).isEqualTo("eager");
        assertThat(covers.get(1).attr("loading")).isEqualTo("lazy");
        assertThat(covers).allSatisfy(img->assertThat(img.attr("src")).startsWith("/uploads/thumbs/"));
        var detail=Jsoup.parse(mvc.perform(get("/products/"+first.getId())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(detail.selectFirst("#mainImage").attr("src")).isEqualTo("/"+first.getImagePath());
        assertThat(detail.select("img[data-full-src]")).hasSize(2).allSatisfy(img->{
            assertThat(img.attr("src")).startsWith("/uploads/thumbs/");
            assertThat(img.attr("data-full-src")).startsWith("/uploads/").doesNotContain("thumbs/");
            assertThat(img.attr("onclick")).contains("dataset.fullSrc");
        });
    }
    @Test void oldMissingAndPlaceholderPathsFallBackWithoutWriting() throws Exception {
        Files.createDirectories(ROOT);Files.write(ROOT.resolve("legacy.jpg"),photo().getBytes());
        assertThat(images.small("uploads/legacy.jpg")).isEqualTo("uploads/legacy.jpg");
        assertThat(images.small("uploads/missing.jpg")).isEqualTo("uploads/missing.jpg");
        assertThat(images.small("images/placeholder.png")).isEqualTo("images/placeholder.png");
        assertThat(images.small("uploads/../secret.jpg")).isEqualTo("uploads/../secret.jpg");
        mvc.perform(get("/uploads/legacy.jpg")).andExpect(status().isOk());
    }
    @Test void rollbackRemovesOriginalAndThumbnailTogether() throws Exception {
        jdbc.execute("CREATE TRIGGER reject_image_product BEFORE INSERT ON product BEGIN SELECT RAISE(ABORT,'rollback test'); END");
        Set<Path> before;
        Files.createDirectories(ROOT);
        try(var files=Files.walk(ROOT)){before=files.filter(Files::isRegularFile).collect(java.util.stream.Collectors.toSet());}
        try {assertThatThrownBy(()->publish(List.of(photo()))).isInstanceOf(RuntimeException.class);}
        finally {jdbc.execute("DROP TRIGGER reject_image_product");}
        try(var files=Files.walk(ROOT)){assertThat(files.filter(Files::isRegularFile).toList()).containsExactlyInAnyOrderElementsOf(before);}
    }
    @Test void invalidBatchWritesNoNewFiles() throws Exception {
        var bad=new MockMultipartFile("images","bad.png","image/png",new byte[]{1,2,3});
        long before=jdbc.queryForObject("SELECT count(*) FROM product",Long.class);
        Files.createDirectories(ROOT);
        List<Path> beforeFiles;try(var files=Files.walk(ROOT)){beforeFiles=files.filter(Files::isRegularFile).toList();}
        assertThatThrownBy(()->publish(List.of(photo(),bad))).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product",Long.class)).isEqualTo(before);
        try(var files=Files.walk(ROOT)){assertThat(files.filter(Files::isRegularFile).toList()).containsExactlyInAnyOrderElementsOf(beforeFiles);}
    }
    @Test void tinyOriginalIsNotReplacedWithLargerDerivative() throws Exception {
        var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",bytes);
        var product=publish(List.of(new MockMultipartFile("images","tiny.png","image/png",bytes.toByteArray())));
        assertThat(images.small(product.getImagePath())).isEqualTo(product.getImagePath());
        assertThat(Files.exists(ROOT.resolve("thumbs").resolve(product.getImagePath().substring(8)+".jpg"))).isFalse();
    }
    @Test void thumbnailDoesNotUpscaleAndHandlesTransparentPng() throws Exception {
        var input=new BufferedImage(20,10,BufferedImage.TYPE_INT_ARGB);
        var decoded=ImageIO.read(new ByteArrayInputStream(ProductImages.thumbnail(input)));
        assertThat(decoded.getWidth()).isEqualTo(20);assertThat(decoded.getHeight()).isEqualTo(10);
        assertThat(decoded.getRGB(0,0)&0xffffff).isEqualTo(0xffffff);
    }
}
