package com.wude.nexusmind.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ObservabilityConfiguration {

    @Bean
    NexusMindMetrics nexusMindMetrics(MeterRegistry meterRegistry) {
        return new NexusMindMetrics(meterRegistry);
    }
}
