package com.example.ollama.brochure;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/** Fetches the text and links needed to create a brochure from a website. */
public final class Scraper {

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/117.0.0.0 Safari/537.36";
    private static final int MAX_CONTENT_LENGTH = 2_000;

    private Scraper() {
    }

    /** Returns the page title and body text, truncated to 2,000 characters. */
    public static String fetchWebsiteContents(String url) throws IOException {
        Document document = fetch(url);
        Element title = document.selectFirst("title");
        String heading = title == null ? "No title found" : title.text();

        StringBuilder text = new StringBuilder();
        Element body = document.body();
        if (body != null) {
            body.select("script, style, img, input").remove();
            appendText(body, text);
        }

        String contents = heading + "\n\n" + text;
        return contents.length() > MAX_CONTENT_LENGTH
                ? contents.substring(0, MAX_CONTENT_LENGTH)
                : contents;
    }

    /** Returns nonempty href attributes from all links on the page. */
    public static List<String> fetchWebsiteLinks(String url) throws IOException {
        Document document = fetch(url);
        List<String> links = new ArrayList<>();
        for (Element link : document.select("a[href]")) {
            String href = link.attr("href");
            if (!href.isEmpty()) {
                links.add(href);
            }
        }
        return links;
    }

    private static Document fetch(String url) throws IOException {
        return Jsoup.connect(url).userAgent(USER_AGENT).get();
    }

    private static void appendText(Node node, StringBuilder text) {
        if (node instanceof TextNode textNode) {
            String value = textNode.getWholeText().strip();
            if (!value.isEmpty()) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(value);
            }
        } else {
            for (Node child : node.childNodes()) {
                appendText(child, text);
            }
        }
    }
}
