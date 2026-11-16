package com.group5.shop;

import com.group5.shop.product.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.jsoup.Jsoup;
import java.nio.file.*;
import java.util.*;
import java.security.MessageDigest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Explicit opt-in: -Dtest=ImagePerformanceBenchmark -Dimage.perf.dir=<fixture directory>. */
@SpringBootTest
@AutoConfigureMockMvc
class ImagePerformanceBenchmark {
    static final Path ROOT=Path.of("target","image-bench-"+UUID.randomUUID()).toAbsolutePath();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->"jdbc:sqlite:"+ROOT+".db");
        r.add("shop.product.upload-dir",()->Path.of("uploads").toAbsolutePath().toString());
        r.add("logging.level.com.group5.shop",()->"ERROR");
    }
    @Autowired ProductService products;
    @Autowired MockMvc mvc;
    @Test void benchmark() throws Exception {
        var paths=Files.list(Path.of(System.getProperty("image.perf.dir")));
        List<MockMultipartFile> files=new ArrayList<>();
        try(paths){for(Path p:paths.filter(p->p.toString().endsWith(".jpg")).sorted().limit(9).toList())
            files.add(new MockMultipartFile("images",p.getFileName().toString(),"image/jpeg",Files.readAllBytes(p)));}
        assertThat(files).hasSize(9);
        long storedBefore=diskBytes();
        var durations=new ArrayList<Double>();Long last=null;
        for(int i=0;i<15;i++) {
            long start=System.nanoTime();
            var p=products.publish(1L,"图片基准商品"+i,"29.90",104L,3,"",new ArrayList<>(files),null,0);
            double elapsed=(System.nanoTime()-start)/1e6;
            if(i>=3)durations.add(elapsed);
            last=p.getId();
        }
        var list=Jsoup.parse(mvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        var detail=Jsoup.parse(mvc.perform(get("/products/"+last)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        long listBytes=payload(list.select("img.thumb").eachAttr("src"));
        long detailBytes=payload(detail.select("img").eachAttr("src"));
        var sorted=new ArrayList<>(durations);Collections.sort(sorted);
        var rows=new ArrayList<Map<String,Object>>();
        for(var f:files){
            var row=new LinkedHashMap<String,Object>();row.put("file",f.getOriginalFilename());row.put("original_bytes",f.getSize());
            row.put("sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(f.getBytes())));rows.add(row);
        }
        long stored;try(var all=Files.walk(Path.of("uploads"))){stored=all.filter(Files::isRegularFile).mapToLong(p->{try{return Files.size(p);}catch(Exception e){throw new RuntimeException(e);}}).sum();}
        var result=new LinkedHashMap<String,Object>();result.put("samples",rows);result.put("warmup_uploads",3);result.put("measured_uploads",12);
        result.put("upload_ms",durations);result.put("upload_median_ms",(sorted.get(5)+sorted.get(6))/2);result.put("upload_p95_ms",sorted.get(11));
        result.put("list_cover_count",list.select("img.thumb").size());result.put("list_all_cover_http_payload_bytes",listBytes);
        result.put("detail_all_image_http_payload_bytes",detailBytes);result.put("stored_image_bytes_for_15_products",stored-storedBefore);
        result.put("list_lazy_count",list.select("img[loading=lazy]").size());
        Files.writeString(Path.of("target/image-benchmark.json"),new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result));
        System.out.println("IMAGE_BENCHMARK "+result);
    }
    private long diskBytes() throws Exception {
        if(!Files.exists(Path.of("uploads")))return 0;
        try(var paths=Files.walk(Path.of("uploads"))){return paths.filter(Files::isRegularFile).mapToLong(p->{try{return Files.size(p);}catch(Exception e){throw new RuntimeException(e);}}).sum();}
    }
    private long payload(List<String> urls) throws Exception {
        long bytes=0;
        for(String url:new LinkedHashSet<>(urls))bytes+=mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray().length;
        return bytes;
    }
}
