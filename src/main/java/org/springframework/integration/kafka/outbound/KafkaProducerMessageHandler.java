/*
 * Copyright 2013-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.kafka.outbound;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;

import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.integration.MessageTimeoutException;
import org.springframework.integration.expression.ExpressionUtils;
import org.springframework.integration.expression.ValueExpression;
import org.springframework.integration.handler.AbstractMessageProducingHandler;
import org.springframework.integration.kafka.support.KafkaSendFailureException;
import org.springframework.integration.support.DefaultErrorMessageStrategy;
import org.springframework.integration.support.ErrorMessageStrategy;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.DefaultKafkaHeaderMapper;
import org.springframework.kafka.support.JacksonPresent;
import org.springframework.kafka.support.KafkaHeaderMapper;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.KafkaNull;
import org.springframework.kafka.support.SendResult;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.ErrorMessage;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.ListenableFutureCallback;

/**
 * Kafka Message Handler.
 *
 * @param <K> the key type.
 * @param <V> the value type.
 *
 * @author Soby Chacko
 * @author Artem Bilan
 * @author Gary Russell
 * @author Marius Bogoevici
 * @author Biju Kunjummen
 *
 * @since 0.5
 */
public class KafkaProducerMessageHandler<K, V> extends AbstractMessageProducingHandler {

	private static final long DEFAULT_SEND_TIMEOUT = 10000;

	private final KafkaTemplate<K, V> kafkaTemplate;

	private EvaluationContext evaluationContext;

	private volatile Expression topicExpression;

	private volatile Expression messageKeyExpression;

	private volatile Expression partitionIdExpression;

	private volatile Expression timestampExpression;

	private boolean sync;

	private Expression sendTimeoutExpression = new ValueExpression<>(DEFAULT_SEND_TIMEOUT);

	private KafkaHeaderMapper headerMapper;

	private MessageChannel sendFailureChannel;

	private String sendFailureChannelName;

	private ErrorMessageStrategy errorMessageStrategy = new DefaultErrorMessageStrategy();

	public KafkaProducerMessageHandler(final KafkaTemplate<K, V> kafkaTemplate) {
		Assert.notNull(kafkaTemplate, "kafkaTemplate cannot be null");
		this.kafkaTemplate = kafkaTemplate;
		if (JacksonPresent.isJackson2Present()) {
			this.headerMapper = new DefaultKafkaHeaderMapper();
		}
	}

	public void setTopicExpression(Expression topicExpression) {
		this.topicExpression = topicExpression;
	}

	public void setMessageKeyExpression(Expression messageKeyExpression) {
		this.messageKeyExpression = messageKeyExpression;
	}

	public void setPartitionIdExpression(Expression partitionIdExpression) {
		this.partitionIdExpression = partitionIdExpression;
	}

	/**
	 * Specify a SpEL expression to evaluate a timestamp that will be added in the Kafka record.
	 * The resulting value should be a {@link Long} type representing epoch time in milliseconds.
	 * @param timestampExpression the {@link Expression} for timestamp to wait for result
	 * fo send operation.
	 * @since 2.3
	 */
	public void setTimestampExpression(Expression timestampExpression) {
		this.timestampExpression = timestampExpression;
	}

	/**
	 * Set the header mapper to use.
	 * @param headerMapper the mapper; can be null to disable header mapping.
	 * @since 2.3
	 */
	public void setHeaderMapper(KafkaHeaderMapper headerMapper) {
		this.headerMapper = headerMapper;
	}

	public KafkaTemplate<?, ?> getKafkaTemplate() {
		return this.kafkaTemplate;
	}

	/**
	 * A {@code boolean} indicating if the {@link KafkaProducerMessageHandler}
	 * should wait for the send operation results or not. Defaults to {@code false}.
	 * In {@code sync} mode a downstream send operation exception will be re-thrown.
	 * @param sync the send mode; async by default.
	 * @since 2.0.1
	 */
	public void setSync(boolean sync) {
		this.sync = sync;
	}

	/**
	 * Specify a timeout in milliseconds for how long this
	 * {@link KafkaProducerMessageHandler} should wait wait for send operation
	 * results. Defaults to 10 seconds. The timeout is applied only in {@link #sync} mode.
	 * Also applies when sending to the success or failure channels.
	 * @param sendTimeout the timeout to wait for result fo send operation.
	 * @since 2.0.1
	 */
	@Override
	public void setSendTimeout(long sendTimeout) {
		super.setSendTimeout(sendTimeout);
		setSendTimeoutExpression(new ValueExpression<>(sendTimeout));
	}

	/**
	 * Specify a SpEL expression to evaluate a timeout in milliseconds for how long this
	 * {@link KafkaProducerMessageHandler} should wait wait for send operation
	 * results. Defaults to 10 seconds. The timeout is applied only in {@link #sync} mode.
	 * @param sendTimeoutExpression the {@link Expression} for timeout to wait for result
	 * fo send operation.
	 * @since 2.1.1
	 */
	public void setSendTimeoutExpression(Expression sendTimeoutExpression) {
		Assert.notNull(sendTimeoutExpression, "'sendTimeoutExpression' must not be null");
		this.sendTimeoutExpression = sendTimeoutExpression;
	}

	/**
	 * Set the failure channel. After a send failure, an {@link ErrorMessage} will be sent
	 * to this channel with a payload of a {@link KafkaSendFailureException} with the
	 * failed message and cause.
	 * @param sendFailureChannel the failure channel.
	 * @since 2.1.2
	 */
	public void setSendFailureChannel(MessageChannel sendFailureChannel) {
		this.sendFailureChannel = sendFailureChannel;
	}

	/**
	 * Set the failure channel name. After a send failure, an {@link ErrorMessage} will be
	 * sent to this channel name with a payload of a {@link KafkaSendFailureException}
	 * with the failed message and cause.
	 * @param sendFailureChannelName the failure channel name.
	 * @since 2.1.2
	 */
	public void setSendFailureChannelName(String sendFailureChannelName) {
		this.sendFailureChannelName = sendFailureChannelName;
	}

	/**
	 * Set the error message strategy implementation to use when sending error messages after
	 * send failures. Cannot be null.
	 * @param errorMessageStrategy the implementation.
	 * @since 2.1.2
	 */
	public void setErrorMessageStrategy(ErrorMessageStrategy errorMessageStrategy) {
		Assert.notNull(errorMessageStrategy, "'errorMessageStrategy' cannot be null");
		this.errorMessageStrategy = errorMessageStrategy;
	}

	protected MessageChannel getSendFailureChannel() {
		if (this.sendFailureChannel != null) {
			return this.sendFailureChannel;
		}
		else if (this.sendFailureChannelName != null) {
			this.sendFailureChannel = getChannelResolver().resolveDestination(this.sendFailureChannelName);
			return this.sendFailureChannel;
		}
		return null;
	}

	@Override
	protected void onInit() throws Exception {
		super.onInit();
		this.evaluationContext = ExpressionUtils.createStandardEvaluationContext(getBeanFactory());
	}

	@SuppressWarnings("unchecked")
	@Override
	protected void handleMessageInternal(final Message<?> message) throws Exception {
		String topic = this.topicExpression != null ?
				this.topicExpression.getValue(this.evaluationContext, message, String.class)
				: message.getHeaders().get(KafkaHeaders.TOPIC, String.class);

		Assert.state(StringUtils.hasText(topic), "The 'topic' can not be empty or null");

		Integer partitionId = this.partitionIdExpression != null ?
				this.partitionIdExpression.getValue(this.evaluationContext, message, Integer.class)
				: message.getHeaders().get(KafkaHeaders.PARTITION_ID, Integer.class);

		Object messageKey = this.messageKeyExpression != null
				? this.messageKeyExpression.getValue(this.evaluationContext, message)
				: message.getHeaders().get(KafkaHeaders.MESSAGE_KEY);

		Long timestamp = this.timestampExpression != null
				? this.timestampExpression.getValue(this.evaluationContext, message, Long.class)
				: message.getHeaders().get(KafkaHeaders.TIMESTAMP, Long.class);

		V payload = (V) message.getPayload();
		if (payload instanceof KafkaNull) {
			payload = null;
		}

		Headers headers = null;
		if (this.headerMapper != null) {
			headers = new RecordHeaders();
			this.headerMapper.fromHeaders(message.getHeaders(), headers);
		}
		final ProducerRecord<K, V> producerRecord = new ProducerRecord<K, V>(topic, partitionId, timestamp,
				(K) messageKey, payload, headers);
		ListenableFuture<SendResult<K, V>> future = this.kafkaTemplate.send(producerRecord);
		if (getSendFailureChannel() != null || getOutputChannel() != null) {
			future.addCallback(new ListenableFutureCallback<SendResult<K, V>>() {

				@Override
				public void onSuccess(SendResult<K, V> result) {
					if (getOutputChannel() != null) {
						KafkaProducerMessageHandler.this.messagingTemplate.send(getOutputChannel(),
								getMessageBuilderFactory().fromMessage(message)
										// TODO: change to constant when available
										.setHeader("kafka_recordMetadata", result.getRecordMetadata()).build());
					}
				}

				@Override
				public void onFailure(Throwable ex) {
					if (getSendFailureChannel() != null) {
						KafkaProducerMessageHandler.this.messagingTemplate.send(getSendFailureChannel(),
								KafkaProducerMessageHandler.this.errorMessageStrategy.buildErrorMessage(
										new KafkaSendFailureException(message, producerRecord, ex), null));
					}
				}

			});
		}

		if (this.sync) {
			Long sendTimeout = this.sendTimeoutExpression.getValue(this.evaluationContext, message, Long.class);
			if (sendTimeout == null || sendTimeout < 0) {
				future.get();
			}
			else {
				try {
					future.get(sendTimeout, TimeUnit.MILLISECONDS);
				}
				catch (TimeoutException te) {
					throw new MessageTimeoutException(message, "Timeout waiting for response from KafkaProducer", te);
				}
			}
		}
	}

	@Override
	public String getComponentType() {
		return "kafka:outbound-channel-adapter";
	}

}
