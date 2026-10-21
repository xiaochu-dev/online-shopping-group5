package com.group5.shop.product.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.group5.shop.product.domain.*;
import com.group5.shop.product.util.RichTextSanitizer;
import com.group5.shop.common.exception.*;
import com.group5.shop.common.util.MoneyUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.file.*;
import javax.imageio.ImageIO;
import java.util.*;

@Service
public class ProductService {
    private final ProductMapper products;
    private final CategoryQueryService categories;
    private final RichTextSanitizer sanitizer;
    private final JdbcTemplate jdbc;
    private final Path uploads;
    public ProductService(ProductMapper products, CategoryQueryService categories, RichTextSanitizer sanitizer,
                          JdbcTemplate jdbc, @Value("${shop.product.upload-dir:./uploads}") String uploads) {
        this.products=products; this.categories=categories; this.sanitizer=sanitizer; this.jdbc=jdbc;
        this.uploads=Path.of(uploads).toAbsolutePath();
    }
    public List<Product> list(Long seller,String status) {
        var q=Wrappers.<Product>lambdaQuery().eq(Product::getSellerId,seller).orderByDesc(Product::getPublishedAt,Product::getId);
        if(status!=null && !status.isBlank()) {
            if(!Set.of("on_sale","frozen","off_shelf","delisted").contains(status)) throw bad("商品状态无效");
            q.eq(Product::getStatus,status);
        }
        return products.selectList(q);
    }
    @Transactional
    public Product publish(Long seller,String name,String price,Long category,int stock,String description,
                           List<MultipartFile> supplied,List<Integer> order,Integer primary) {
        if(name==null || name.isBlank() || name.trim().length()>120) throw bad("商品名称须为1～120个字符");
        if(stock<0) throw bad("库存不能为负数");
        if(description!=null && description.length()>100000) throw bad("描述过长");
        categories.requireLeaf(category);
        long cents;
        try { cents=MoneyUtils.yuanToCents(price); } catch(ArithmeticException e) { throw bad("价格超出范围"); }
        List<MultipartFile> files=supplied==null?List.of():supplied.stream().filter(f -> !(f.isEmpty() && (f.getOriginalFilename()==null || f.getOriginalFilename().isBlank()))).toList();
        if(files.size()>9) throw bad("单商品最多上传9张图片");
        List<Integer> indices=new ArrayList<>();
        for(int i=0;i<files.size();i++) indices.add(i);
        if(order!=null && !order.isEmpty()) {
            if(order.size()!=files.size() || new HashSet<>(order).size()!=files.size() || !indices.containsAll(order)) throw bad("图片排序无效");
            indices=new ArrayList<>(order);
        }
        int cover=primary==null?0:primary;
        if(!files.isEmpty() && (cover<0 || cover>=files.size())) throw bad("主图序号无效");
        List<byte[]> bytes=new ArrayList<>(); List<String> suffixes=new ArrayList<>();
        // 全部校验成功后才写文件，拒绝的请求不会留下半批上传。
        for(MultipartFile f:files) {
            if(f.isEmpty() || f.getSize()>5L*1024*1024) throw bad("图片不能为空且单张不能超过5MB");
            String filename=Objects.toString(f.getOriginalFilename(),"").toLowerCase(Locale.ROOT);
            boolean png=filename.endsWith(".png"), jpg=filename.endsWith(".jpg") || filename.endsWith(".jpeg");
            if(!png && !jpg) throw bad("只支持JPG/PNG图片");
            try {
                byte[] b=f.getBytes();
                boolean magicPng=b.length>=8 && Arrays.equals(Arrays.copyOf(b,8),new byte[]{(byte)137,80,78,71,13,10,26,10});
                boolean magicJpg=b.length>=3 && b[0]==(byte)255 && b[1]==(byte)216 && b[2]==(byte)255;
                if(!(png?magicPng:magicJpg)) throw bad("图片扩展名与文件内容不匹配");
                try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(b))) {
                    var readers=ImageIO.getImageReaders(input);
                    if(!readers.hasNext()) throw bad("图片无法解码");
                    var reader=readers.next();
                    try {
                        reader.setInput(input);
                        if((long)reader.getWidth(0)*reader.getHeight(0)>40000000L || reader.read(0)==null) throw bad("图片尺寸过大或已损坏");
                    } finally { reader.dispose(); }
                }
                bytes.add(b); suffixes.add(png?".png":".jpg");
            } catch(IOException e) { throw bad("图片读取失败"); }
        }
        Product p=new Product(); long now=System.currentTimeMillis();
        p.setSellerId(seller); p.setCategoryId(category); p.setName(name.trim()); p.setPriceCents(cents);
        p.setStock(stock); p.setStatus(stock==0?"off_shelf":"on_sale"); p.setOffReason(stock==0?"auto":null);
        p.setDescription(sanitizer.clean(description)); p.setPublishedAt(now); p.setUpdatedAt(now);
        List<Path> written=new ArrayList<>(); List<String> paths=new ArrayList<>();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if(status!=STATUS_COMMITTED) written.forEach(path -> { try {Files.deleteIfExists(path);} catch(IOException ignored) {} });
            }
        });
        try {
            Files.createDirectories(uploads);
            for(int i=0;i<files.size();i++) {
                String filename=UUID.randomUUID()+suffixes.get(i); Path path=uploads.resolve(filename);
                written.add(path); Files.write(path,bytes.get(i),StandardOpenOption.CREATE_NEW); paths.add("uploads/"+filename);
            }
        } catch(IOException e) { throw bad("图片保存失败"); }
        p.setImagePath(files.isEmpty()?"images/placeholder.png":paths.get(cover)); products.insert(p);
        int pos=0;
        for(int index:indices) jdbc.update("INSERT INTO product_image(product_id,file_path,sort_no,is_primary) VALUES(?,?,?,?)",p.getId(),paths.get(index),pos++,index==cover?1:0);
        return p;
    }
    private BusinessException bad(String s) { return new BusinessException(ErrorCode.PARAM_INVALID,s); }
}
