package com.wesil.flashsale.inventory.outbox;

public record OutboxEvent(long id, String topic, String key, String payload) {
}
