package com.wesil.flashsale.inventory.outbox;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes outbox rows to Kafka. Delivery is at-least-once: a row is only
 * marked published after the broker acknowledges it, so a crash in between
 * means the row is sent again on the next run. Consumers dedupe on eventId.
 *
 * Like the sweeper, this runs on every replica and relies on SKIP LOCKED to
 * split the work instead of electing a leader.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final int batchSize;
    private final Duration sendTimeout;

    public OutboxRelay(
            OutboxRepository outbox,
            KafkaTemplate<String, String> kafka,
            TransactionTemplate tx,
            @Value("${inventory.outbox.batch-size:200}") int batchSize,
            @Value("${inventory.outbox.send-timeout:10s}") Duration sendTimeout) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.tx = tx;
        this.batchSize = batchSize;
        this.sendTimeout = sendTimeout;
    }

    @Scheduled(fixedDelayString = "${inventory.outbox.interval-ms:500}")
    public void relay() {
        try {
            int published;
            do {
                published = publishBatch();
            } while (published == batchSize);
        } catch (Exception e) {
            // The batch rolled back and stays unpublished; the next run retries it.
            log.warn("outbox relay failed, will retry: {}", e.toString());
        }
    }

    /**
     * Claims a batch, sends it, and marks it published in one transaction.
     * The row locks are held while waiting for the broker so no other replica
     * can send the same rows concurrently.
     */
    int publishBatch() {
        Integer published = tx.execute(status -> {
            List<OutboxEvent> batch = outbox.claimUnpublished(batchSize);
            if (batch.isEmpty()) {
                return 0;
            }
            CompletableFuture<?>[] sends = batch.stream()
                    .map(e -> kafka.send(e.topic(), e.key(), e.payload()))
                    .toArray(CompletableFuture[]::new);
            awaitAll(sends);
            outbox.markPublished(batch.stream().map(OutboxEvent::id).toList());
            return batch.size();
        });
        return published == null ? 0 : published;
    }

    private void awaitAll(CompletableFuture<?>[] sends) {
        try {
            CompletableFuture.allOf(sends).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while publishing outbox", e);
        } catch (Exception e) {
            throw new IllegalStateException("broker did not acknowledge outbox batch", e);
        }
    }
}
