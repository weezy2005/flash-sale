package com.wesil.flashsale.inventory.reservation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs on every replica. There is no leader election: SKIP LOCKED in the
 * release query is what keeps replicas from processing the same rows.
 */
@Component
public class ExpiredReservationSweeper {

    private static final Logger log = LoggerFactory.getLogger(ExpiredReservationSweeper.class);

    private final ReservationService service;
    private final int batchSize;

    public ExpiredReservationSweeper(
            ReservationService service,
            @Value("${inventory.sweeper.batch-size:500}") int batchSize) {
        this.service = service;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${inventory.sweeper.interval-ms:5000}")
    public void sweep() {
        int released;
        do {
            released = service.releaseExpired(batchSize);
            if (released > 0) {
                log.info("released {} expired reservations", released);
            }
        } while (released == batchSize);
    }
}
