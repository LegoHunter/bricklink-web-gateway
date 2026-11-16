package io.legohunter.bricklink.webgateway.browser;

import java.util.ArrayList;
import java.util.List;

/** A scriptable stand-in for the real browser. */
public class FakeBrowserSession implements BrowserSession {

    public FetchResult nextResult = new FetchResult(200, "https://www.bricklink.com/my.asp");
    public RuntimeException nextFailure;
    public List<CookieInfo> jar = new ArrayList<>();
    public List<ImportedCookie> imported;
    public int fetches;
    public int closes;
    public boolean open;
    public final List<String> calls = new ArrayList<>();

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
}
