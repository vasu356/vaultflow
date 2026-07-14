package com.vaultflow.notification.config;

import java.util.HashMap;
import java.util.Map;
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
 * Kafka configuration for notification-service.
 *
 * <p>Two separate consumer factories are declared — one per consumer group — so each group has its
 * own GROUP_ID_CONFIG set at the factory level. This is the permanent fix for the
 * NotCoordinatorException / endless-rebalance loop: when GROUP_ID_CONFIG is absent from the
 * factory, Kafka initialises each consumer with an empty group, then each thread negotiates with a
 * different coordinator broker, producing "NOT_COORDINATOR" errors in a loop.
 *
 * <p>Consumer groups: - {@code notification-audit-service}: audit.events (8 partitions, 2 threads)
 * - {@code notification-processing-service}: file.processed (16 partitions, 2 threads)
 *
 * <p>DLT: after 3 retries (3 s gap) undeliverable messages land in {@code *.DLT} topics.
 */
@Configuration
@EnableKafka
public class NotificationKafkaConfig {

  @Value("${spring.kafka.bootstrap-servers}")
  private String bootstrapServers;

  // ── Producer ──────────────────────────────────────────────────────────────

  @Bean
  public ProducerFactory<String, Object> notificationProducerFactory() {
    Map<String, Object> props = new HashMap<>();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
    props.put(ProducerConfig.ACKS_CONFIG, "1");
    props.put(ProducerConfig.RETRIES_CONFIG, 3);
    props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false);
    return new DefaultKafkaProducerFactory<>(props);
  }

  @Bean
  public KafkaTemplate<String, Object> notificationKafkaTemplate() {
    return new KafkaTemplate<>(notificationProducerFactory());
  }

  // ── Audit consumer (audit.events → notification-audit-service) ────────────

  /**
   * Consumer factory for the audit.events consumer group. GROUP_ID_CONFIG is set here at the
   * factory level so every consumer thread joins the same coordinator-assigned group.
   */
  @Bean
  public DefaultKafkaConsumerFactory<String, Object> auditConsumerFactory() {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    // GROUP_ID set at factory level — required to prevent NotCoordinatorException
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "notification-audit-service");
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);
    props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000);
    props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 10000);
    props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000);

    JsonDeserializer<Object> deserializer = new JsonDeserializer<>(Object.class, false);
    deserializer.addTrustedPackages("com.vaultflow.common.event");
    deserializer.setUseTypeMapperForKey(false);

    return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), deserializer);
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, Object>
      auditKafkaListenerContainerFactory() {
    ConcurrentKafkaListenerContainerFactory<String, Object> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(auditConsumerFactory());
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(notificationKafkaTemplate());
    DefaultErrorHandler errorHandler =
        new DefaultErrorHandler(recoverer, new FixedBackOff(3000L, 3L));
    errorHandler.addNotRetryableExceptions(IllegalArgumentException.class);
    factory.setCommonErrorHandler(errorHandler);
    return factory;
  }

  // ── Processed-event consumer (file.processed → notification-processing-service) ──

  /**
   * Separate consumer factory for file.processed events. Different group ID so coordinator
   * assignment is independent of the audit consumer group.
   */
  @Bean
  public DefaultKafkaConsumerFactory<String, Object> processedEventConsumerFactory() {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "notification-processing-service");
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);
    props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000);
    props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 10000);
    props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000);

    JsonDeserializer<Object> deserializer = new JsonDeserializer<>(Object.class, false);
    deserializer.addTrustedPackages("com.vaultflow.common.event");
    deserializer.setUseTypeMapperForKey(false);

    return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), deserializer);
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, Object>
      processedKafkaListenerContainerFactory() {
    ConcurrentKafkaListenerContainerFactory<String, Object> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(processedEventConsumerFactory());
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(notificationKafkaTemplate());
    DefaultErrorHandler errorHandler =
        new DefaultErrorHandler(recoverer, new FixedBackOff(3000L, 3L));
    errorHandler.addNotRetryableExceptions(IllegalArgumentException.class);
    factory.setCommonErrorHandler(errorHandler);
    return factory;
  }
}
