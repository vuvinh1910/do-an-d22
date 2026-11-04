package com.platform.worker.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Enterprise AMQP Topology:
 * - Direct exchange for jobs & results
 * - Dead Letter Exchange (DLX) & Queue (DLQ) for poison pill isolation
 * - Result queue declared for Platform integration
 */
@Configuration
public class RabbitMQConfig {

    @Value("${app.rabbitmq.exchange:test.exchange}")
    private String exchangeName;

    @Value("${app.rabbitmq.exchange.dlx:test.exchange.dlx}")
    private String dlxExchangeName;

    @Value("${app.rabbitmq.queue.execution:test.execution.queue}")
    private String executionQueueName;

    @Value("${app.rabbitmq.queue.execution-dlq:test.execution.dlq}")
    private String executionDlqName;

    @Value("${app.rabbitmq.routing-key.execution:test.execution.key}")
    private String executionRoutingKey;

    @Value("${app.rabbitmq.routing-key.execution-dlk:test.execution.dlk}")
    private String executionDlkKey;

    @Value("${app.rabbitmq.queue.result:test.result.queue}")
    private String resultQueueName;

    @Value("${app.rabbitmq.routing-key.result:test.result.key}")
    private String resultRoutingKey;

    // --- Core Exchange & Execution Queue ---

    @Bean
    public DirectExchange exchange() {
        return new DirectExchange(exchangeName, true, false);
    }

    @Bean
    public Queue executionQueue() {
        return QueueBuilder.durable(executionQueueName)
                .withArgument("x-dead-letter-exchange", dlxExchangeName)
                .withArgument("x-dead-letter-routing-key", executionDlkKey)
                .build();
    }

    @Bean
    public Binding executionBinding(Queue executionQueue, DirectExchange exchange) {
        return BindingBuilder.bind(executionQueue).to(exchange).with(executionRoutingKey);
    }

    // --- Result Queue (Platform / Reporting) ---

    @Bean
    public Queue resultQueue() {
        return QueueBuilder.durable(resultQueueName).build();
    }

    @Bean
    public Binding resultBinding(Queue resultQueue, DirectExchange exchange) {
        return BindingBuilder.bind(resultQueue).to(exchange).with(resultRoutingKey);
    }

    // --- Dead Letter Exchange & Queue (Poison Pill Handling) ---

    @Bean
    public DirectExchange dlxExchange() {
        return new DirectExchange(dlxExchangeName, true, false);
    }

    @Bean
    public Queue executionDlq() {
        return QueueBuilder.durable(executionDlqName).build();
    }

    @Bean
    public Binding executionDlqBinding(Queue executionDlq, DirectExchange dlxExchange) {
        return BindingBuilder.bind(executionDlq).to(dlxExchange).with(executionDlkKey);
    }

    @Bean
    public MessageConverter jackson2JsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
