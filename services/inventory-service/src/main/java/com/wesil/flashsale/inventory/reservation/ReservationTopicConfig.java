package com.wesil.flashsale.inventory.reservation;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration(proxyBeanMethods = false)
class ReservationTopicConfig {

    /** Created on startup if missing. Partitions bound how many consumers can share the load. */
    @Bean
    NewTopic reservationEventsTopic(
            @Value("${inventory.kafka.partitions:6}") int partitions,
            @Value("${inventory.kafka.replicas:1}") int replicas) {
        return TopicBuilder.name(ReservationEvents.TOPIC).partitions(partitions).replicas(replicas).build();
    }
}
