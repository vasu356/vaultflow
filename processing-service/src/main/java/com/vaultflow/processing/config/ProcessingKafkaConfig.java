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
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka configuration for processing-service.
 *
 * <p>GROUP_ID_CONFIG is set at the consumer factory level — this permanently fixes the
 * NotCoordinatorException / endless-rebalance loop. When GROUP_ID is absent from the factory, each
 * consumer thread negotiates with a different coordinator broker, producing "NOT_COORDINATOR"
 * errors in a cycle.
 *
 * <p>Consumer group {@code processing-service} reads from {@code file.uploaded} (16 partitions).
 * With concurrency=4, each pod owns up to 4 partitions. DLT: after 3 retries (5 s gap), failed
 * messages land in {@code file.uploaded.DLT}.
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

  /**
   * Consumer factory for file.uploaded events. GROUP_ID_CONFIG is set here at the factory level so
   * all consumer threads in this pod join the same coordinator-assigned group.
   */
  @Bean
  public DefaultKafkaConsumerFactory<String, FileUploadedEvent> processingConsumerFactory() {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    // GROUP_ID at factory level — prevents NotCoordinatorException
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "processing-service");
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);
    props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000);
    props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 10000);
    // Processing can be slow (virus scan + thumbnail). Allow up to 5 min between polls.
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
    DefaultErrorHandler errorHandler =
        new DefaultErrorHandler(recoverer, new FixedBackOff(5000L, 3L));
    errorHandler.addNotRetryableExceptions(
        IllegalArgumentException.class, NullPointerException.class);
    factory.setCommonErrorHandler(errorHandler);
    return factory;
  }
}
