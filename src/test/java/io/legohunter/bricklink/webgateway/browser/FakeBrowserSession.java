package io.legohunter.bricklink.webgateway.browser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A scriptable stand-in for the real browser. For the lot operations it behaves like the BrickLink
 * pages the gateway uses: it keeps a few lots and answers the data call, the image upload and the
 * inventory page submit from them.
 */
public class FakeBrowserSession implements BrowserSession {

    /** A lot as the fake BrickLink holds it. */
    public static class FakeLot {
        public boolean inStockroom = true;
        public String stockroomId = "C";
        public boolean customImage;
        public String extendedDescription = "";
        public boolean hasExtendedField = true;
        public FilePart uploaded;
    }

    public FetchResult nextResult = new FetchResult(200, "https://www.bricklink.com/my.asp");
    public RuntimeException nextFailure;
    public List<CookieInfo> jar = new ArrayList<>();
    public List<ImportedCookie> imported;
    public int fetches;
    public int closes;
    public boolean open;
    public final List<String> calls = new ArrayList<>();

    public final Map<Long, FakeLot> lots = new LinkedHashMap<>();
    /** When set, the lot pages answer as if the session were signed out. */
    public boolean lotPagesSignedOut;
    /** When set, BrickLink accepts a change but does not apply it. */
    public boolean ignoreWrites;

    @Override
    public FetchResult fetch(String url) {
        calls.add("fetch");
        fetches++;
        open = true;
        if (nextFailure != null) {
            throw nextFailure;
        }
        return nextResult;
    }

    @Override
    public HttpResult get(String url) {
        calls.add("get " + path(url));
        return page("<html>upload form</html>", url);
    }

    @Override
    public HttpResult postForm(String url, Map<String, String> form) {
        calls.add("postForm " + path(url));
        if (lotPagesSignedOut) {
            return new HttpResult(200, "https://identity.lego.com/en-US/login?code=SECRET", "<html>login</html>");
        }
        long lotId = Long.parseLong(form.get("n4InvID"));
        FakeLot lot = lots.get(lotId);
        String list = lot == null ? "" : """
                {"invID":%d,"invStock":"%s","strStockID":"%s","invImgType":%s,"imgThumbnailURL":"//img.bricklink.com/t/%d.jpg","invExtended":%s}"""
                .formatted(lotId, lot.inStockroom ? "Y" : "N", lot.stockroomId,
                        lot.customImage ? "\"J\"" : "null", lotId, quote(lot.extendedDescription));
        return new HttpResult(200, url, "{\"returnCode\":0,\"returnMessage\":\"SUCC\",\"inventoryList\":[" + list + "]}");
    }

    @Override
    public HttpResult postFile(String url, String referer, String fieldName, FilePart file) {
        calls.add("postFile " + path(url) + " " + fieldName);
        FakeLot lot = lots.get(lotId(url));
        // Like BrickLink: a lot that already has a custom image keeps it.
        if (lot != null && !lot.customImage && !ignoreWrites) {
            lot.customImage = true;
            lot.uploaded = file;
        }
        return page("<html>uploaded</html>", url);
    }

    @Override
    public String submitPage(String pageUrl, String readySelector, List<PageAction> actions, String submitSelector,
                             String responseUrlPart) {
        calls.add("submitPage " + path(pageUrl) + " " + actions);
        if (lotPagesSignedOut) {
            throw new PageShapeException("The page did not show its form (ended at identity.lego.com/en-US/login)");
        }
        FakeLot lot = lots.get(lotId(pageUrl));
        if (lot == null) {
            throw new PageShapeException("The page did not show its form (ended at www.bricklink.com/v2/inventory_detail.page)");
        }
        for (PageAction action : actions) {
            switch (action) {
                case PageAction.Check check -> {
                    if (!lot.customImage) {
                        throw new PageShapeException("The page has no control " + check.selector());
                    }
                    if (!ignoreWrites) {
                        lot.customImage = false;
                    }
                }
                case PageAction.Fill fill -> {
                    if (!lot.hasExtendedField) {
                        throw new PageShapeException("The page has no control " + fill.selector());
                    }
                    if (!ignoreWrites) {
                        // BrickLink stores the text as HTML.
                        lot.extendedDescription = fill.value().replaceAll("&(?!(?:[a-zA-Z]+|#[0-9]+);)", "&amp;")
                                .replace("\"", "&quot;");
                    }
                }
            }
        }
        return "{\"returnCode\":0}";
    }

    @Override
    public List<CookieInfo> cookies() {
        return jar;
    }

    @Override
    public void replaceCookies(List<ImportedCookie> cookies) {
        calls.add("replaceCookies");
        imported = cookies;
    }

    @Override
    public void close() {
        calls.add("close");
        closes++;
        open = false;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    private HttpResult page(String body, String url) {
        if (lotPagesSignedOut) {
            return new HttpResult(200, "https://identity.lego.com/en-US/login?code=SECRET", "<html>login</html>");
        }
        return new HttpResult(200, url, body);
    }

    private static long lotId(String url) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("invID=(\\d+)").matcher(url);
        if (!m.find()) {
            throw new IllegalArgumentException("no lot id in " + url);
        }
        return Long.parseLong(m.group(1));
    }

    private static String path(String url) {
        return url.replaceFirst("^https://www\\.bricklink\\.com", "").replaceFirst("\\?.*", "");
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
