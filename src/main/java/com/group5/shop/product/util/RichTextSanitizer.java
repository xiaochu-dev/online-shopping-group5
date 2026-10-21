package com.group5.shop.product.util;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.Set;

/** 发布时过滤，详情渲染仅使用过滤后的片段。iframe 只允许播放器 URL。 */
@Component
public class RichTextSanitizer {
    private static final Set<String> VIDEO_HOSTS = Set.of("player.bilibili.com", "www.youtube.com", "www.youtube-nocookie.com");
    private static final Safelist ALLOWED = Safelist.none()
        .addTags("p", "br", "b", "strong", "em", "i", "ul", "ol", "li", "blockquote", "h2", "h3", "a", "img", "iframe")
        .addAttributes("a", "href", "title").addProtocols("a", "href", "https", "http")
        .addEnforcedAttribute("a", "rel", "nofollow noopener noreferrer")
        .addAttributes("img", "src", "alt", "title").addProtocols("img", "src", "https", "http")
        .addAttributes("iframe", "src", "title", "allowfullscreen").addProtocols("iframe", "src", "https");

    public String clean(String html) {
        Document doc = Jsoup.parseBodyFragment(html == null ? "" : html);
        doc.select("script,style,object,embed,svg,math").remove();
        for (Element frame : doc.select("iframe")) {
            if (!trustedVideo(frame.attr("src"))) frame.remove();
        }
        String clean = Jsoup.clean(doc.body().html(), "", ALLOWED, new Document.OutputSettings().prettyPrint(false));
        Document result = Jsoup.parseBodyFragment(clean);
        for (Element frame : result.select("iframe")) {
            frame.attr("sandbox", "allow-scripts allow-same-origin allow-presentation");
            frame.attr("referrerpolicy", "no-referrer");
            frame.attr("loading", "lazy");
        }
        result.outputSettings().prettyPrint(false);
        return result.body().html();
    }
    private boolean trustedVideo(String src) {
        try {
            URI uri = URI.create(src);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null ||
                (uri.getPort() != -1 && uri.getPort() != 443) || !VIDEO_HOSTS.contains(uri.getHost())) return false;
            return "player.bilibili.com".equals(uri.getHost()) ? "/player.html".equals(uri.getPath()) : uri.getPath().startsWith("/embed/");
        } catch (RuntimeException e) { return false; }
    }
}
