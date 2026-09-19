package com.wude.nexusmind;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class NexusMindApplicationTests {

    @Autowired
    private WebEndpointsSupplier webEndpointsSupplier;

    @Test
    void contextLoads() {
        assertThat(webEndpointsSupplier.getEndpoints())
                .extracting(endpoint -> endpoint.getEndpointId().toString())
                .containsExactlyInAnyOrder("health", "info", "prometheus");
    }
}
