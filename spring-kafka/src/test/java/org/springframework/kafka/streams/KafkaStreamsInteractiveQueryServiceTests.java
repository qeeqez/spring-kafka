/*
 * Copyright 2024-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.kafka.streams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StoreQueryParameters;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.processor.WallclockTimestampExtractor;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.KafkaStreamsDefaultConfiguration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaStreamsConfiguration;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.retry.RetryPolicy;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * @author Soby Chacko
 * @since 3.2.0
 */
@SpringJUnitConfig
@DirtiesContext
@EmbeddedKafka(partitions = 1,
		topics = { "iqs-test-in", "iqs-test-out" })
class KafkaStreamsInteractiveQueryServiceTests {

	public static final String IQS_TEST_IN = "iqs-test-in";

	public static final String IQS_TEST_OUT = "iqs-test-out";

	public static final String STATE_STORE = "my-state-store";

	public static final String NON_EXISTENT_STORE = "my-non-existent-store";

	@Autowired
	private EmbeddedKafkaKraftBroker embeddedKafka;

	@Autowired
	private StreamsBuilderFactoryBean streamsBuilderFactoryBean;

	@Autowired
	private KafkaTemplate<Integer, String> kafkaTemplate;

	@Autowired
	private KafkaStreamsInteractiveQueryService interactiveQueryService;

	@Autowired
	private CompletableFuture<ConsumerRecord<?, String>> resultFuture;

	@Test
	void retrieveQueryableStore() throws Exception {
		this.kafkaTemplate.sendDefault(123, "123");
		this.kafkaTemplate.flush();

		ConsumerRecord<?, String> result = resultFuture.get(600, TimeUnit.SECONDS);
		assertThat(result).isNotNull();

		ReadOnlyKeyValueStore<Object, Object> objectObjectReadOnlyKeyValueStore = this.interactiveQueryService
				.retrieveQueryableStore(STATE_STORE,
						QueryableStoreTypes.keyValueStore());

		assertThat((Long) objectObjectReadOnlyKeyValueStore.get(123)).isGreaterThanOrEqualTo(1);
	}

	@SuppressWarnings("unchecked")
	@Test
	void retrieveNonExistentStateStoreAndVerifyRetries() throws Exception {
		this.kafkaTemplate.sendDefault(123, "123");
		this.kafkaTemplate.flush();

		ConsumerRecord<?, String> result = resultFuture.get(600, TimeUnit.SECONDS);
		assertThat(result).isNotNull();

		assertThat(this.streamsBuilderFactoryBean.getKafkaStreams()).isNotNull();
		KafkaStreams kafkaStreams = spy(this.streamsBuilderFactoryBean.getKafkaStreams());
		assertThat(kafkaStreams).isNotNull();

		Field kafkaStreamsField = KafkaStreamsInteractiveQueryService.class.getDeclaredField("kafkaStreams");
		kafkaStreamsField.setAccessible(true);
		kafkaStreamsField.set(interactiveQueryService, kafkaStreams);

		assertThatExceptionOfType(IllegalStateException.class)
				.isThrownBy(() -> {
					this.interactiveQueryService
					.retrieveQueryableStore(NON_EXISTENT_STORE, QueryableStoreTypes.keyValueStore());
				})
				.withMessageContaining("Error retrieving state store: my-non-existent-store");

		verify(kafkaStreams, times(3)).store(any(StoreQueryParameters.class));
	}

	@Configuration
	@EnableKafka
	@EnableKafkaStreams
	public static class KafkaStreamsConfig {

		@Value("${" + EmbeddedKafkaBroker.SPRING_EMBEDDED_KAFKA_BROKERS + "}")
		private String brokerAddresses;

		@Bean
		public ProducerFactory<Integer, String> producerFactory() {
			return new DefaultKafkaProducerFactory<>(producerConfigs());
		}

		@Bean
		public Map<String, Object> producerConfigs() {
			return KafkaTestUtils.producerProps(this.brokerAddresses);
		}

		@Bean
		public KafkaTemplate<Integer, String> template() {
			KafkaTemplate<Integer, String> kafkaTemplate = new KafkaTemplate<>(producerFactory(), true);
			kafkaTemplate.setDefaultTopic("iqs-test-in");
			return kafkaTemplate;
		}

		@Bean
		public Map<String, Object> consumerConfigs() {
			return KafkaTestUtils.consumerProps(this.brokerAddresses, "testGroup",
					"false");
		}

		@Bean
		public ConsumerFactory<Integer, String> consumerFactory() {
			return new DefaultKafkaConsumerFactory<>(consumerConfigs());
		}

		@Bean
		public KafkaListenerContainerFactory<ConcurrentMessageListenerContainer<Integer, String>>
		kafkaListenerContainerFactory() {

			ConcurrentKafkaListenerContainerFactory<Integer, String> factory =
					new ConcurrentKafkaListenerContainerFactory<>();
			factory.setConsumerFactory(consumerFactory());
			return factory;
		}

		@Bean(name = KafkaStreamsDefaultConfiguration.DEFAULT_STREAMS_CONFIG_BEAN_NAME)
		public KafkaStreamsConfiguration kStreamsConfigs() {
			Map<String, Object> props = new HashMap<>();
			props.put(StreamsConfig.APPLICATION_ID_CONFIG, "iqs-testStreams");
			props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, this.brokerAddresses);
			props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.Integer().getClass().getName());
			props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
			props.put(StreamsConfig.DEFAULT_TIMESTAMP_EXTRACTOR_CLASS_CONFIG,
					WallclockTimestampExtractor.class.getName());
			props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, "100");
			return new KafkaStreamsConfiguration(props);
		}

		@Bean
		public KStream<Integer, String> kStream(StreamsBuilder kStreamBuilder) {
			KStream<Integer, String> input = kStreamBuilder.stream(IQS_TEST_IN);
			final KStream<Integer, String> outbound = input.filter((key, value) -> key == 123)
					.map((key, value) -> new KeyValue<>(123, value))
					.groupByKey(Grouped.with(new Serdes.IntegerSerde(),
							new Serdes.StringSerde()))
					.count(Materialized.as(STATE_STORE)).toStream()
					.map((key, value) -> new KeyValue<>(key, "Count for ID 123: " + value));
			outbound.to(IQS_TEST_OUT);
			return outbound;
		}

		@Bean
		public KafkaStreamsInteractiveQueryService kafkaStreamsInteractiveQueryService(StreamsBuilderFactoryBean streamsBuilderFactoryBean) {
			final KafkaStreamsInteractiveQueryService kafkaStreamsInteractiveQueryService =
					new KafkaStreamsInteractiveQueryService(streamsBuilderFactoryBean);
			RetryTemplate retryTemplate = new RetryTemplate();
			retryTemplate.setBackOffPolicy(new FixedBackOffPolicy());
			RetryPolicy retryPolicy = new SimpleRetryPolicy(3);
			retryTemplate.setRetryPolicy(retryPolicy);
			kafkaStreamsInteractiveQueryService.setRetryTemplate(retryTemplate);
			return kafkaStreamsInteractiveQueryService;
		}

		@Bean
		public CompletableFuture<ConsumerRecord<?, String>> resultFuture() {
			return new CompletableFuture<>();
		}

		@KafkaListener(topics = "iqs-test-out")
		public void listener(ConsumerRecord<?, String> payload) {
			resultFuture().complete(payload);
		}

	}

}
