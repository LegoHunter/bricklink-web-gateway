package io.legohunter.bricklink.webgateway.lot;

import java.util.Optional;

import com.bricklink.api.rest.client.BricklinkRestClient;
import com.bricklink.api.rest.exception.BricklinkClientException;
import com.bricklink.api.rest.model.v1.BricklinkResource;
import com.bricklink.api.rest.model.v1.Inventory;
import io.legohunter.bricklink.webgateway.lot.LotExceptions.GuardUnavailableException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Reads the stockroom from the official BrickLink REST API ({@code GET /inventories/{id}}:
 * {@code is_stock_room}, {@code stock_room_id}). The gateway uses the API for this read only; it
 * never writes through it.
 *
 * <p>A 401 or 403 answer means the API token was refused, most often because the home IP address
 * changed and the IP-bound token no longer matches (HOMELAB-6). The read is still reported as
 * "guard unavailable", which callers defer on, so the refusal is counted in
 * {@code bricklink_api_credentials_rejected_total{source="stockroom_guard"}} and an alert on that
 * counter tells the owner. lego-data-ingress exports the same counter for its own BrickLink jobs.
 */
@Component
public class RestStockroomReader implements StockroomReader {

    private static final Logger log = LoggerFactory.getLogger(RestStockroomReader.class);

    private final ObjectProvider<BricklinkRestClient> client;
    private final Counter credentialsRejected;

    public RestStockroomReader(ObjectProvider<BricklinkRestClient> client, MeterRegistry meters) {
        this.client = client;
        // Registered at zero on startup, so the first refusal shows as an increase.
        this.credentialsRejected = Counter.builder("bricklink.api.credentials.rejected")
                .description("BrickLink REST calls answered with 401 or 403: the API token was refused.")
                .tag("source", "stockroom_guard")
                .register(meters);
    }

    @Override
    public Optional<Stockroom> read(long lotId) {
        BricklinkRestClient rest = client.getIfAvailable();
        if (rest == null) {
            throw new GuardUnavailableException("The BrickLink API client is not configured");
        }
        BricklinkResource<Inventory> resource;
        try {
            resource = rest.getInventories(lotId);
        } catch (BricklinkClientException e) {
            if (e.isNotFound()) {
                return Optional.empty();
            }
            countIfCredentialsRejected(e.getCode());
            log.warn("BrickLink API refused the stockroom read: HTTP {}", e.getCode());
            throw new GuardUnavailableException("The BrickLink API refused the stockroom read (HTTP " + e.getCode() + ")");
        } catch (RuntimeException e) {
            log.warn("BrickLink API stockroom read failed: {}", e.getClass().getSimpleName());
            throw new GuardUnavailableException("The BrickLink API could not be reached for the stockroom read");
        }
        Integer code = resource == null || resource.getMeta() == null ? null : resource.getMeta().getCode();
        if (code != null && code == 404) {
            return Optional.empty();
        }
        Inventory inventory = resource == null ? null : resource.getData();
        if (code == null || code < 200 || code > 299 || inventory == null) {
            countIfCredentialsRejected(code);
            throw new GuardUnavailableException("The BrickLink API gave no usable answer for the stockroom read (code " + code + ")");
        }
        return Optional.of(new Stockroom(Boolean.TRUE.equals(inventory.getIs_stock_room()),
                inventory.getStock_room_id() == null ? "" : inventory.getStock_room_id().trim().toUpperCase()));
    }

    private void countIfCredentialsRejected(Integer code) {
        if (code != null && (code == 401 || code == 403)) {
            credentialsRejected.increment();
        }
    }
}
