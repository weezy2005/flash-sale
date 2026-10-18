package com.wesil.flashsale.order.orders;

import com.wesil.flashsale.order.inventory.FakeInventoryClient;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
class OrderTestConfiguration {

    @Bean
    @Primary
    FakeInventoryClient fakeInventory() {
        return new FakeInventoryClient();
    }
}
