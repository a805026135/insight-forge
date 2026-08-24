package com.insightforge.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** Protects Kafka's producer timeout invariant from configuration drift. */
class KafkaProducerConfigTest {

    @Test
    void deliveryTimeoutCoversRequestTimeoutAndLinger() {
        YamlPropertiesFactoryBean loader = new YamlPropertiesFactoryBean();
        loader.setResources(new ClassPathResource("application.yml"));
        Properties properties = loader.getObject();

        assertThat(properties).isNotNull();
        int lingerMs = integer(properties, "spring.kafka.producer.properties.linger.ms");
        int requestTimeoutMs = integer(properties,
                "spring.kafka.producer.properties.request.timeout.ms");
        int deliveryTimeoutMs = integer(properties,
                "spring.kafka.producer.properties.delivery.timeout.ms");

        assertThat(deliveryTimeoutMs)
                .as("Kafka requires delivery.timeout.ms >= request.timeout.ms + linger.ms")
                .isGreaterThanOrEqualTo(requestTimeoutMs + lingerMs);
    }

    private int integer(Properties properties, String key) {
        return Integer.parseInt(properties.getProperty(key));
    }
}
