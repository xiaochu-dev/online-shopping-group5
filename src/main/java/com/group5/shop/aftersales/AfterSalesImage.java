package com.group5.shop.aftersales;

import com.group5.shop.common.exception.BusinessException;
import com.group5.shop.common.exception.ErrorCode;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

record AfterSalesImage(String mediaType, byte[] content) {

    private static final int MAX_IMAGES = 3;
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final long MAX_PIXELS = 16_000_000;

    static List<AfterSalesImage> readAll(List<MultipartFile> supplied) {
        if (supplied == null) {
            return List.of();
        }
        List<MultipartFile> files = supplied.stream()
                .filter(file -> !(file.isEmpty() && "".equals(file.getOriginalFilename())))
                .toList();
        if (files.size() > MAX_IMAGES) {
            throw invalid("最多上传 3 张售后图片");
        }
        List<AfterSalesImage> images = new ArrayList<>(files.size());
        for (MultipartFile file : files) {
            images.add(read(file));
        }
        return images;
    }

    private static AfterSalesImage read(MultipartFile file) {
        if (file.isEmpty() || file.getSize() > MAX_BYTES) {
            throw invalid("图片不能为空，且单张不能超过 5 MB");
        }
        try {
            byte[] bytes = file.getBytes();
            try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) {
                    throw invalid("请选择有效的 JPG 或 PNG 图片");
                }
                var reader = readers.next();
                try {
                    String mediaType = switch (reader.getFormatName().toLowerCase(Locale.ROOT)) {
                        case "jpeg" -> "image/jpeg";
                        case "png" -> "image/png";
                        default -> throw invalid("仅支持 JPG 或 PNG 图片");
                    };
                    reader.setInput(input, true, true);
                    if ((long) reader.getWidth(0) * reader.getHeight(0) > MAX_PIXELS) {
                        throw invalid("图片像素过多，请缩小图片后重试");
                    }
                    if (reader.read(0) == null) {
                        throw invalid("图片无法读取，请重新选择");
                    }
                    return new AfterSalesImage(mediaType, bytes);
                } finally {
                    reader.dispose();
                }
            }
        } catch (IOException e) {
            throw invalid("图片无法读取，请重新选择");
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.PARAM_INVALID, message);
    }
}
