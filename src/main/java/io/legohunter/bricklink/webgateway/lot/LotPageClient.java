package io.legohunter.bricklink.webgateway.lot;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.legohunter.bricklink.webgateway.browser.BrowserSession;
import io.legohunter.bricklink.webgateway.browser.BrowserSession.FilePart;
import io.legohunter.bricklink.webgateway.browser.BrowserSession.HttpResult;
import io.legohunter.bricklink.webgateway.browser.BrowserSession.PageAction;
import io.legohunter.bricklink.webgateway.browser.BrowserSession.PageShapeException;
import io.legohunter.bricklink.webgateway.config.GatewayProperties;
import io.legohunter.bricklink.webgateway.logging.SafeUrl;
import io.legohunter.bricklink.webgateway.lot.LotExceptions.LotNotSupportedException;
import io.legohunter.bricklink.webgateway.lot.LotExceptions.UnexpectedPageException;
import org.springframework.stereotype.Component;

/**
 * The BrickLink website requests behind the lot operations.
 *
 * <p>Reads use the inventory detail page's own data call, which answers JSON. The image upload is
 * the classic upload form. The image delete and the Extended Description are changed by opening the
 * inventory detail page, changing the one control and pressing the page's own submit button, so the
 * page's script builds the request and every other field of the lot keeps its current value.
 *
 * <p>Every method must run on the browser thread (through OperationQueue). Consecutive BrickLink
 * requests are spaced by the configured minimum gap.
 */
@Component
public class LotPageClient {

    private static final String LIST_PATH = "/ajax/renovate/storeInventoryDetail/list.ajax";
    private static final String UPDATE_PATH = "/ajax/renovate/storeInventoryDetail/update.ajax";
    /**
     * The lot's inventory detail page. The older address ({@code /inventory_detail.asp}) answers with a
     * redirect to this page over plain HTTP, which the pod's network policy (outbound 443 only) refuses,
     * so the page is opened at its final HTTPS address.
     */
    private static final String DETAIL_PAGE_PATH = "/v2/inventory_detail.page";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BrowserSession browser;
    private final String baseUrl;
    private final Duration minimumGap;

    private long lastRequestNanos;
    private boolean requestedBefore;

    public LotPageClient(BrowserSession browser, GatewayProperties properties) {
        this.browser = browser;
        this.baseUrl = properties.baseUrl();
        this.minimumGap = properties.minRequestGap();
    }

    /** @return the lot, or empty if BrickLink shows no such lot for the account */
    public Optional<LotState> read(long lotId) {
        pace();
        HttpResult result = browser.postForm(baseUrl + LIST_PATH, listForm(lotId));
        JsonNode json = json(result, "the lot data call");
        if (json.path("returnCode").asInt(-1) != 0) {
            throw new UnexpectedPageException("The lot data call answered returnCode "
                    + json.path("returnCode").asText("<missing>"));
        }
        for (JsonNode lot : json.path("inventoryList")) {
            if (lot.path("invID").asLong(-1) == lotId) {
                String imageType = lot.path("invImgType").asText("");
                return Optional.of(new LotState(lotId,
                        "Y".equalsIgnoreCase(lot.path("invStock").asText("")),
                        lot.path("strStockID").asText("").trim().toUpperCase(),
                        // G and J mark an uploaded picture; anything else is the catalog picture.
                        imageType.equals("G") || imageType.equals("J"),
                        lot.path("imgThumbnailURL").isNull() ? null : lot.path("imgThumbnailURL").asText(null),
                        lot.path("invExtended").isNull() ? "" : lot.path("invExtended").asText("")));
            }
        }
        return Optional.empty();
    }

    /** Uploads a custom image. BrickLink rejects this while the lot already has one. */
    public void uploadImage(long lotId, FilePart image) {
        String formUrl = baseUrl + "/imgAdd.asp?invID=" + lotId;
        pace();
        HttpResult form = browser.get(formUrl);
        requireBricklinkPage(form, "the image upload form");
        pace();
        HttpResult posted = browser.postFile(formUrl + "&a=a", formUrl, "FILE", image);
        requireBricklinkPage(posted, "the image upload");
    }

    /** Ticks "Delete Custom Image" on the lot's inventory page and submits it. */
    public void deleteImage(long lotId) {
        submitDetailPage(lotId, new PageAction.Check("#delImg"), "the lot has no \"Delete Custom Image\" control");
    }

    /** Replaces the Extended Description on the lot's inventory page and submits it. */
    public void setExtendedDescription(long lotId, String text) {
        submitDetailPage(lotId, new PageAction.Fill("#invExtended" + lotId, text),
                "the lot's page has no Extended Description field");
    }

    private void submitDetailPage(long lotId, PageAction action, String missingControl) {
        pace();
        String body;
        try {
            body = browser.submitPage(baseUrl + DETAIL_PAGE_PATH + "?invID=" + lotId, "#InvStock" + lotId,
                    List.of(action), "#submitUpdateButton", UPDATE_PATH);
        } catch (PageShapeException e) {
            if (e.getMessage().contains("no control")) {
                throw new LotNotSupportedException(missingControl);
            }
            throw new UnexpectedPageException(e.getMessage());
        } finally {
            lastRequestNanos = System.nanoTime();
        }
        JsonNode json;
        try {
            json = MAPPER.readTree(body);
        } catch (JsonProcessingException e) {
            throw new UnexpectedPageException("The lot update did not answer JSON");
        }
        if (json == null || json.path("returnCode").asInt(-1) != 0) {
            throw new UnexpectedPageException("The lot update answered returnCode "
                    + (json == null ? "<empty>" : json.path("returnCode").asText("<missing>")));
        }
        if (json.path("invErrList").size() > 0 || json.path("superLotErrList").size() > 0) {
            throw new UnexpectedPageException("BrickLink reported an error for the lot update");
        }
    }

    private static JsonNode json(HttpResult result, String what) {
        requireBricklinkPage(result, what);
        try {
            JsonNode json = MAPPER.readTree(result.body());
            if (json == null || !json.isObject()) {
                throw new UnexpectedPageException("Expected JSON from " + what);
            }
            return json;
        } catch (JsonProcessingException e) {
            throw new UnexpectedPageException("Expected JSON from " + what + " (ended at " + SafeUrl.of(result.finalUrl()) + ")");
        }
    }

    private static void requireBricklinkPage(HttpResult result, String what) {
        String location = SafeUrl.of(result.finalUrl());
        if (result.httpStatus() != 200 || !location.startsWith("www.bricklink.com/")
                || location.contains("login") || location.contains("/auth/")) {
            throw new UnexpectedPageException("Unexpected answer from " + what + ": HTTP " + result.httpStatus()
                    + ", ended at " + location);
        }
    }

    /** The filter the inventory detail page itself posts, narrowed to one lot. */
    private static Map<String, String> listForm(long lotId) {
        Map<String, String> form = new LinkedHashMap<>();
        for (String minusOne : List.of("n4ItemID", "n4CatID", "n4ColorID", "n4ColorType", "n4ItemYear", "n4BindType",
                "n4InvDays", "n4InvSoldDays", "n4QMin", "n4QMax", "n4BMin", "n4BMax", "mPMin", "mPMax", "n4BindID")) {
            form.put(minusOne, "-1");
        }
        for (String empty : List.of("strCatType", "strInvNew", "strInvAvailable", "strItemObsolete", "strItemWanted",
                "strNoIn", "strViewUnique", "strSetNo", "strBreakType", "strItemStatus", "strItemReserved",
                "strItemStock", "strItemChild", "strNoImageXref", "strNoImageS", "strNoImageL", "strNoInv",
                "strNoWeight", "strNoMyWeight", "strNoRemarks", "strNoCost", "strInvTier", "strInvRetain", "strNoIC",
                "strNoPackDim", "strInvComplete", "strInvDaysType", "strInvSoldDaysType", "strQuery", "strRmkTP",
                "strItemStockExclude", "viewPriceGuide", "uploadDone", "invSort", "invSize", "invAsc", "invBindSort",
                "invBindSize", "invBindAsc")) {
            form.put(empty, "");
        }
        form.put("n1SetSeq", "0");
        form.put("n4ViewSale", "-100");
        form.put("strInvSearch", "D");
        form.put("pg", "1");
        form.put("n4InvID", Long.toString(lotId));
        return form;
    }

    private void pace() {
        if (requestedBefore) {
            long waitNanos = minimumGap.toNanos() - (System.nanoTime() - lastRequestNanos);
            if (waitNanos > 0) {
                try {
                    TimeUnit.NANOSECONDS.sleep(waitNanos);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        requestedBefore = true;
        lastRequestNanos = System.nanoTime();
    }
}
