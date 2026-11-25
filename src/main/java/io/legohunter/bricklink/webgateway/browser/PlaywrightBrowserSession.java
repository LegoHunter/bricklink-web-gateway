package io.legohunter.bricklink.webgateway.browser;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.FilePayload;
import com.microsoft.playwright.options.FormData;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.RequestOptions;
import com.microsoft.playwright.options.SameSiteAttribute;
import com.microsoft.playwright.options.WaitForSelectorState;
import io.legohunter.bricklink.webgateway.config.GatewayProperties;
import io.legohunter.bricklink.webgateway.logging.SafeUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Chromium under Playwright, with a persistent profile directory.
 *
 * <p>All BrickLink traffic goes through the browser's own request API, which shares the browser's
 * cookie jar. A separate HTTP client would need the ASP session and the firewall token copied over
 * and was seen to get HTTP 202 (the firewall's challenge) where the browser got the page.
 *
 * <p>The browser starts on first use and is closed when idle. Closing loses only the session-only
 * ASP cookies, which BrickLink rebuilds from the token cookies on the next visit.
 */
@Component
public class PlaywrightBrowserSession implements BrowserSession {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightBrowserSession.class);

    /** The firewall answers 202 with a JavaScript challenge when its token cookie is missing or stale. */
    private static final String DRIVER_ERROR_MESSAGE_PREFIX = "message='";
    private static final int FIREWALL_CHALLENGE = 202;
    private static final double CHALLENGE_SETTLE_TIMEOUT_MS = 15_000;

    /**
     * Chromium flags. The profile lives on a small persistent volume, so the HTTP cache, the one part
     * of the profile that grows with use, gets a fixed ceiling (50 MB).
     */
    static final List<String> LAUNCH_ARGS = List.of(
            // Pods have a small /dev/shm; Chromium falls back to /tmp with this flag.
            "--disable-dev-shm-usage",
            "--disk-cache-size=" + 50 * 1024 * 1024);

    private final GatewayProperties properties;

    // Only ever touched on the OperationQueue thread.
    private Playwright playwright;
    private BrowserContext context;

    private volatile boolean open;

    public PlaywrightBrowserSession(GatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    public FetchResult fetch(String url) {
        try {
            BrowserContext ctx = context();
            FetchResult result = get(ctx, url);
            if (result.httpStatus() == FIREWALL_CHALLENGE) {
                log.info("Firewall challenge on {}; loading the page so the browser can answer it", SafeUrl.of(url));
                answerChallenge(ctx, url);
                result = get(ctx, url);
            }
            return result;
        } catch (PlaywrightException e) {
            throw failure("Browser request failed", e);
        }
    }

    private FetchResult get(BrowserContext ctx, String url) {
        APIResponse response = ctx.request().get(url, RequestOptions.create()
                .setFailOnStatusCode(false)
                .setTimeout(properties.navigationTimeout().toMillis()));
        try {
            return new FetchResult(response.status(), response.url());
        } finally {
            response.dispose();
        }
    }

    /** A real page load runs the firewall's script, which sets a fresh token cookie in the jar. */
    private void answerChallenge(BrowserContext ctx, String url) {
        Page page = ctx.newPage();
        try {
            page.navigate(url);
            try {
                page.waitForLoadState(LoadState.NETWORKIDLE,
                        new Page.WaitForLoadStateOptions().setTimeout(CHALLENGE_SETTLE_TIMEOUT_MS));
            } catch (PlaywrightException settleTimeout) {
                // Some pages never go idle; the retry that follows shows whether the challenge passed.
            }
        } finally {
            page.close();
        }
    }

    @Override
    public HttpResult get(String url) {
        try {
            BrowserContext ctx = context();
            HttpResult result = read(ctx.request().get(url, options()));
            if (result.httpStatus() == FIREWALL_CHALLENGE) {
                log.info("Firewall challenge on {}; loading the page so the browser can answer it", SafeUrl.of(url));
                answerChallenge(ctx, url);
                result = read(ctx.request().get(url, options()));
            }
            return result;
        } catch (PlaywrightException e) {
            throw failure("Browser request failed", e);
        }
    }

    @Override
    public HttpResult postForm(String url, Map<String, String> form) {
        try {
            FormData data = FormData.create();
            form.forEach(data::set);
            return read(context().request().post(url, options().setForm(data)));
        } catch (PlaywrightException e) {
            throw failure("Browser form post failed", e);
        }
    }

    @Override
    public HttpResult postFile(String url, String referer, String fieldName, FilePart file) {
        try {
            FormData data = FormData.create()
                    .set(fieldName, new FilePayload(file.fileName(), file.mimeType(), file.content()));
            return read(context().request().post(url, options().setHeader("referer", referer).setMultipart(data)));
        } catch (PlaywrightException e) {
            throw failure("Browser file post failed", e);
        }
    }

    @Override
    public String submitPage(String pageUrl, String readySelector, List<PageAction> actions, String submitSelector,
                             String responseUrlPart) {
        Page page = null;
        try {
            page = context().newPage();
            // A confirmation dialog would block the page; the submit was asked for, so accept it.
            page.onDialog(dialog -> dialog.accept());
            page.navigate(pageUrl);
            try {
                page.waitForSelector(readySelector, new Page.WaitForSelectorOptions().setState(WaitForSelectorState.ATTACHED));
            } catch (PlaywrightException e) {
                throw new PageShapeException("The page did not show its form (ended at " + SafeUrl.of(page.url()) + ")");
            }
            for (PageAction action : actions) {
                String selector = switch (action) {
                    case PageAction.Check check -> check.selector();
                    case PageAction.Fill fill -> fill.selector();
                };
                if (page.locator(selector).count() == 0) {
                    throw new PageShapeException("The page has no control " + selector);
                }
                switch (action) {
                    case PageAction.Check check -> page.check(check.selector());
                    case PageAction.Fill fill -> page.fill(fill.selector(), fill.value());
                }
            }
            if (page.locator(submitSelector).count() == 0) {
                throw new PageShapeException("The page has no submit control " + submitSelector);
            }
            Page submitting = page;
            Response response = page.waitForResponse(
                    candidate -> candidate.url().contains(responseUrlPart),
                    () -> submitting.locator(submitSelector).first().click());
            return response.text();
        } catch (PlaywrightException e) {
            throw failure("Submitting the page failed", e);
        } finally {
            if (page != null) {
                try {
                    page.close();
                } catch (PlaywrightException closeFailure) {
                    log.warn("Closing the page failed: {}", describe(closeFailure));
                }
            }
        }
    }

    private RequestOptions options() {
        return RequestOptions.create().setFailOnStatusCode(false).setTimeout(properties.navigationTimeout().toMillis());
    }

    private static HttpResult read(APIResponse response) {
        try {
            return new HttpResult(response.status(), response.url(), response.text());
        } finally {
            response.dispose();
        }
    }

    @Override
    public List<CookieInfo> cookies() {
        try {
            List<CookieInfo> result = new ArrayList<>();
            for (Cookie cookie : context().cookies()) {
                result.add(new CookieInfo(cookie.name, cookie.domain, cookie.expires == null ? -1 : cookie.expires));
            }
            return result;
        } catch (PlaywrightException e) {
            throw failure("Reading the cookie jar failed", e);
        }
    }

    @Override
    public void replaceCookies(List<ImportedCookie> cookies) {
        try {
            BrowserContext ctx = context();
            List<Cookie> converted = new ArrayList<>();
            for (ImportedCookie imported : cookies) {
                Cookie cookie = new Cookie(imported.name(), imported.value())
                        .setDomain(imported.domain())
                        .setPath(imported.path())
                        .setHttpOnly(imported.httpOnly())
                        .setSecure(imported.secure());
                if (imported.expiresEpochSeconds() > 0) {
                    cookie.setExpires(imported.expiresEpochSeconds());
                }
                SameSiteAttribute sameSite = sameSite(imported.sameSite());
                if (sameSite != null) {
                    cookie.setSameSite(sameSite);
                }
                converted.add(cookie);
            }
            ctx.clearCookies();
            ctx.addCookies(converted);
        } catch (PlaywrightException e) {
            throw failure("Importing the cookies failed", e);
        }
        // A clean close makes Chromium write the jar to the profile directory now, rather than on its
        // own schedule. The next operation reopens the browser from disk, which also proves the write.
        close();
    }

    private static SameSiteAttribute sameSite(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.toLowerCase()) {
            case "strict" -> SameSiteAttribute.STRICT;
            case "lax" -> SameSiteAttribute.LAX;
            case "none" -> SameSiteAttribute.NONE;
            default -> null;
        };
    }

    @Override
    public void close() {
        try {
            if (context != null) {
                context.close();
            }
        } catch (PlaywrightException e) {
            log.warn("Closing the browser context failed: {}", describe(e));
        }
        try {
            if (playwright != null) {
                playwright.close();
            }
        } catch (PlaywrightException e) {
            log.warn("Closing Playwright failed: {}", describe(e));
        }
        if (open) {
            log.info("Browser closed");
        }
        context = null;
        playwright = null;
        open = false;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    private BrowserContext context() {
        if (context != null) {
            return context;
        }
        try {
            Files.createDirectories(properties.profileDir());
        } catch (IOException e) {
            throw new BrowserException("Profile directory is not usable: " + e.getClass().getSimpleName());
        }
        try {
            // The browser is provided by the container image (or an installed Chrome on a PC), so the
            // driver must not try to download one.
            playwright = Playwright.create(new Playwright.CreateOptions()
                    .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
            BrowserType.LaunchPersistentContextOptions options = new BrowserType.LaunchPersistentContextOptions()
                    .setHeadless(properties.headless())
                    .setArgs(LAUNCH_ARGS);
            if (properties.browserChannel() != null && !properties.browserChannel().isBlank()) {
                options.setChannel(properties.browserChannel());
            }
            context = playwright.chromium().launchPersistentContext(properties.profileDir(), options);
            context.setDefaultTimeout(properties.navigationTimeout().toMillis());
            context.setDefaultNavigationTimeout(properties.navigationTimeout().toMillis());
            open = true;
            log.info("Browser started");
            return context;
        } catch (PlaywrightException e) {
            close();
            throw failure("Starting the browser failed", e);
        }
    }

    private static BrowserException failure(String what, PlaywrightException e) {
        return new BrowserException(what + ": " + describe(e));
    }

    /**
     * Playwright puts the requested URL in its messages, and sign-in redirects carry OAuth codes in the
     * query string. Keep the first line of the error text only and cut every query string; the cause is
     * not chained, so the raw message cannot reach a stack trace either.
     */
    static String describe(Throwable e) {
        String message = firstLineOfErrorText(e.getMessage()).replaceAll("[?#]\\S*", "?<redacted>");
        if (message.length() > 200) {
            message = message.substring(0, 200) + "...";
        }
        return e.getClass().getSimpleName() + (message.isBlank() ? "" : " (" + message + ")");
    }

    /**
     * An error raised inside the browser arrives as {@code "Error {\n  message='<text>\n  name='<name>\n  stack='..."}:
     * its first line says nothing, and the text is on the second.
     */
    private static String firstLineOfErrorText(String raw) {
        if (raw == null) {
            return "";
        }
        List<String> lines = raw.lines().map(String::strip).toList();
        if (lines.isEmpty()) {
            return "";
        }
        if (lines.size() > 1 && lines.get(0).equals("Error {")) {
            String text = lines.get(1);
            return text.startsWith(DRIVER_ERROR_MESSAGE_PREFIX) ? text.substring(DRIVER_ERROR_MESSAGE_PREFIX.length()) : text;
        }
        return lines.get(0);
    }
}
