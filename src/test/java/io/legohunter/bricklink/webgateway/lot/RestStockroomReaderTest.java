package io.legohunter.bricklink.webgateway.lot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bricklink.api.rest.client.BricklinkRestClient;
import com.bricklink.api.rest.exception.BricklinkClientException;
import com.bricklink.api.rest.model.v1.BricklinkResource;
import com.bricklink.api.rest.model.v1.Inventory;
import io.legohunter.bricklink.webgateway.lot.LotExceptions.GuardUnavailableException;
import io.legohunter.bricklink.webgateway.lot.StockroomReader.Stockroom;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;

class RestStockroomReaderTest {

    private static final long LOT_ID = 1001L;

    private final BricklinkRestClient rest = mock(BricklinkRestClient.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final RestStockroomReader reader = new RestStockroomReader(provider(rest), meters);

    @Test
    void theCounterStartsAtZero() {
        assertThat(meters.find("bricklink.api.credentials.rejected").tag("source", "stockroom_guard").counter()).isNotNull();
        assertThat(rejected()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void aRefusedTokenIsCountedAndTheGuardIsUnavailable(int code) {
        when(rest.getInventories(LOT_ID)).thenThrow(new BricklinkClientException(code, "BAD_OAUTH_REQUEST", "TOKEN_IP_MISMATCHED"));

        assertThatThrownBy(() -> reader.read(LOT_ID)).isInstanceOf(GuardUnavailableException.class);

        assertThat(rejected()).isEqualTo(1.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aRefusalReportedInTheAnswerBodyIsCountedToo() {
        BricklinkResource<Inventory> resource = mock(BricklinkResource.class, RETURNS_DEEP_STUBS);
        when(resource.getMeta().getCode()).thenReturn(401);
        when(rest.getInventories(LOT_ID)).thenReturn(resource);

        assertThatThrownBy(() -> reader.read(LOT_ID)).isInstanceOf(GuardUnavailableException.class);

        assertThat(rejected()).isEqualTo(1.0);
    }

    @Test
    void aMaintenanceRedirectIsNotCounted() {
        when(rest.getInventories(LOT_ID)).thenThrow(new BricklinkClientException(302,
                "GET https://api.bricklink.com/api/store/v1/inventories/1001",
                "Unexpected redirect to [https://maintenance.bricklink.com:443/recovery.html]."));

        assertThatThrownBy(() -> reader.read(LOT_ID)).isInstanceOf(GuardUnavailableException.class);

        assertThat(rejected()).isZero();
    }

    @Test
    void otherFailuresAreNotCounted() {
        when(rest.getInventories(LOT_ID))
                .thenThrow(new BricklinkClientException(429, "m", "d"))
                .thenThrow(new IllegalStateException("connection reset"))
                .thenThrow(new BricklinkClientException(404, "m", "d"));

        assertThatThrownBy(() -> reader.read(LOT_ID)).isInstanceOf(GuardUnavailableException.class);
        assertThatThrownBy(() -> reader.read(LOT_ID)).isInstanceOf(GuardUnavailableException.class);
        assertThat(reader.read(LOT_ID)).isEmpty();

        assertThat(rejected()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aStockroomLotIsReadWithoutCounting() {
        BricklinkResource<Inventory> resource = mock(BricklinkResource.class, RETURNS_DEEP_STUBS);
        when(resource.getMeta().getCode()).thenReturn(200);
        Inventory lot = mock(Inventory.class);
        when(lot.getIs_stock_room()).thenReturn(true);
        when(lot.getStock_room_id()).thenReturn("c");
        when(resource.getData()).thenReturn(lot);
        when(rest.getInventories(LOT_ID)).thenReturn(resource);

        assertThat(reader.read(LOT_ID)).contains(new Stockroom(true, "C"));
        assertThat(rejected()).isZero();
    }

    private double rejected() {
        return meters.counter("bricklink.api.credentials.rejected", "source", "stockroom_guard").count();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<BricklinkRestClient> provider(BricklinkRestClient client) {
        ObjectProvider<BricklinkRestClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        return provider;
    }
}
