package com.vaultflow.processing.config;

import com.vaultflow.common.event.FileUploadedEvent;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka configuration for processing-service.
 *
 * Declares both consumer factory (for reading file.uploaded events) and
 * producer factory (for publishing to file.processed and DLT topics).
 * Both are explicit beans so we have full control over serialisation config.
 */
@Configuration
@EnableKafka
@Slf4j
public class ProcessingKafkaConfig {

  @Value("${spring.kafka.bootstrap-servers}")
  private String bootstrapServers;

  // ── Producer ──────────────────────────────────────────────────────────────

  @Bean
  public ProducerFactory<String, Object> processingProducerFactory() {
    Map<String, Object> props = new HashMap<>();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
    props.put(ProducerConfig.ACKS_CONFIG, "all");
    props.put(ProducerConfig.RETRIES_CONFIG, 3);
    props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
    return new DefaultKafkaProducerFactory<>(props);
  }

  @Bean
  public KafkaTemplate<String, Object> kafkaTemplate() {
    return new KafkaTemplate<>(processingProducerFactory());
  }

  // ── Consumer ──────────────────────────────────────────────────────────────

  @Bean
  public ConsumerFactory<String, FileUploadedEvent> processingConsumerFactory() {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "processing-service");
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);
    props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000);
    props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000);

    JsonDeserializer<FileUploadedEvent> deserializer =
        new JsonDeserializer<>(FileUploadedEvent.class, false);
    deserializer.addTrustedPackages("com.vaultflow.common.event");

    return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), deserializer);
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, FileUploadedEvent>
      kafkaListenerContainerFactory() {

    ConcurrentKafkaListenerContainerFactory<String, FileUploadedEvent> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(processingConsumerFactory());
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

    // DLT: after 3 retries with 5 s gap, dead-letter the message
    DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate());
    DefaultErrorHandler errorHandler = new DefaultErrorHandler(
        recoverer, new FixedBackOff(5000L, 3L));
    errorHandler.addNotRetryableExceptions(
        IllegalArgumentException.class, NullPointerException.class);

    factory.setCommonErrorHandler(errorHandler);
    return factory;
  }
}
