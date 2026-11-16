package com.group5.shop.product.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.imageio.ImageIO;
import javax.imageio.IIOImage;
import javax.imageio.ImageWriteParam;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;

/** Original paths stay in the database. Derivatives are optional and never replace originals. */
@Component("productImages")
public class ProductImages {
    private final Path uploads;
    public ProductImages(@Value("${shop.product.upload-dir:./uploads}") String directory) {
        uploads = Path.of(directory).toAbsolutePath().normalize();
    }

    public String small(String original) {
        if (original == null || !original.matches("uploads/[A-Za-z0-9_-]+\\.(?i:png|jpe?g)")) return original;
        String filename = original.substring("uploads/".length());
        return Files.isRegularFile(uploads.resolve("thumbs").resolve(filename + ".jpg"))
            ? "uploads/thumbs/" + filename + ".jpg" : original;
    }

    public static byte[] thumbnail(BufferedImage source) throws IOException {
        double scale = Math.min(1.0, 400.0 / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int)Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int)Math.round(source.getHeight() * scale));
        BufferedImage small = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = small.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally { graphics.dispose(); }
        var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (var buffer = new ByteArrayOutputStream(); var output = ImageIO.createImageOutputStream(buffer)) {
            writer.setOutput(output);
            var parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(0.80f);
            writer.write(null, new IIOImage(small, null, null), parameters);
            output.flush();
            return buffer.toByteArray();
        } finally { writer.dispose(); small.flush(); }
    }
}
