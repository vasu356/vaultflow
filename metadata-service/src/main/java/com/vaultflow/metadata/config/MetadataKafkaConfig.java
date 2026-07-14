package com.vaultflow.metadata.config;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka consumer configuration for metadata-service.
 *
 * <p>GROUP_ID_CONFIG is set at the factory level — this is required to prevent
 * NotCoordinatorException. When group.id is absent from the factory, each consumer thread
 * negotiates with a different coordinator broker resulting in an endless rebalance loop.
 *
 * <p>Consumer group {@code metadata-service} listens to {@code file.processed} to update
 * processing_status, thumbnail_key, preview_key and virus_scan_status on object_versions rows.
 */
@Configuration
@EnableKafka
public class MetadataKafkaConfig {

  @Value("${spring.kafka.bootstrap-servers}")
  private String bootstrapServers;

  @Bean
  public DefaultKafkaConsumerFactory<String, Object> metadataConsumerFactory() {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    // GROUP_ID set at factory level — required to prevent NotCoordinatorException
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "metadata-service");
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
      metadataKafkaListenerContainerFactory() {
    ConcurrentKafkaListenerContainerFactory<String, Object> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(metadataConsumerFactory());
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    // 3 retries, 5 s apart before logging and moving on (no DLT — metadata updates are idempotent)
    factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(5000L, 3L)));
    return factory;
  }
}
