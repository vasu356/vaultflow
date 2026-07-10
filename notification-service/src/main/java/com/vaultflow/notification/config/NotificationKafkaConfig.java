package com.vaultflow.notification.config;

import com.vaultflow.common.event.AuditEvent;
import com.vaultflow.common.event.FileProcessedEvent;
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
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@EnableKafka
public class NotificationKafkaConfig {

  @Value("${spring.kafka.bootstrap-servers}")
  private String bootstrapServers;

  @Bean
  public ProducerFactory<String, Object> notificationProducerFactory() {
    Map<String, Object> props = new HashMap<>();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
    props.put(ProducerConfig.ACKS_CONFIG, "1");
    props.put(ProducerConfig.RETRIES_CONFIG, 3);
    return new DefaultKafkaProducerFactory<>(props);
  }

  @Bean
  public KafkaTemplate<String, Object> notificationKafkaTemplate() {
    return new KafkaTemplate<>(notificationProducerFactory());
  }

  /**
   * Generic consumer factory used for both audit and processed event consumers.
   * Uses Object as value type so both AuditEvent and FileProcessedEvent can be
   * deserialized by the same factory (type determined by topic).
   */
  @Bean
  public ConsumerFactory<String, Object> notificationConsumerFactory() {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);

    JsonDeserializer<Object> deserializer = new JsonDeserializer<>(Object.class, false);
    deserializer.addTrustedPackages("com.vaultflow.common.event");
    deserializer.setUseTypeMapperForKey(false);

    return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), deserializer);
  }

  /**
   * The bean name "auditKafkaListenerContainerFactory" matches the containerFactory
   * attribute in AuditEventConsumer's @KafkaListener annotations.
   */
  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, Object>
      auditKafkaListenerContainerFactory() {

    ConcurrentKafkaListenerContainerFactory<String, Object> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(notificationConsumerFactory());
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(3000L, 3L)));
    return factory;
  }
}
