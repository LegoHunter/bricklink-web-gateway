package io.legohunter.bricklink.webgateway.lot;

import java.util.Optional;

import com.bricklink.api.rest.client.BricklinkRestClient;
import com.bricklink.api.rest.exception.BricklinkClientException;
import com.bricklink.api.rest.model.v1.BricklinkResource;
import com.bricklink.api.rest.model.v1.Inventory;
import io.legohunter.bricklink.webgateway.lot.LotExceptions.GuardUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Reads the stockroom from the official BrickLink REST API ({@code GET /inventories/{id}}:
 * {@code is_stock_room}, {@code stock_room_id}). The gateway uses the API for this read only; it
 * never writes through it.
 */
@Component
public class RestStockroomReader implements StockroomReader {

    private static final Logger log = LoggerFactory.getLogger(RestStockroomReader.class);

    private final ObjectProvider<BricklinkRestClient> client;

    public RestStockroomReader(ObjectProvider<BricklinkRestClient> client) {
        this.client = client;
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
            throw new GuardUnavailableException("The BrickLink API gave no usable answer for the stockroom read (code " + code + ")");
        }
        return Optional.of(new Stockroom(Boolean.TRUE.equals(inventory.getIs_stock_room()),
                inventory.getStock_room_id() == null ? "" : inventory.getStock_room_id().trim().toUpperCase()));
    }
}
