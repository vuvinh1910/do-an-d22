package com.platform.worker.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class RabbitMQConfigTest {

    @Test
    void configuresFullAmqpTopologyWithDlxAndResultQueue() {
        var config = new RabbitMQConfig();
        ReflectionTestUtils.setField(config, "exchangeName", "test.exchange");
        ReflectionTestUtils.setField(config, "dlxExchangeName", "test.exchange.dlx");
        ReflectionTestUtils.setField(config, "executionQueueName", "test.execution.queue");
        ReflectionTestUtils.setField(config, "executionDlqName", "test.execution.dlq");
        ReflectionTestUtils.setField(config, "executionRoutingKey", "test.execution.key");
        ReflectionTestUtils.setField(config, "executionDlkKey", "test.execution.dlk");
        ReflectionTestUtils.setField(config, "resultQueueName", "test.result.queue");
        ReflectionTestUtils.setField(config, "resultRoutingKey", "test.result.key");

        // 1. Core Exchange & Queue with DLX arguments
        DirectExchange exchange = config.exchange();
        assertEquals("test.exchange", exchange.getName());
        assertTrue(exchange.isDurable());

        Queue queue = config.executionQueue();
        assertEquals("test.execution.queue", queue.getName());
        assertTrue(queue.isDurable());
        assertEquals("test.exchange.dlx", queue.getArguments().get("x-dead-letter-exchange"));
        assertEquals("test.execution.dlk", queue.getArguments().get("x-dead-letter-routing-key"));

        Binding binding = config.executionBinding(queue, exchange);
        assertEquals("test.execution.key", binding.getRoutingKey());
        assertEquals("test.exchange", binding.getExchange());
        assertEquals("test.execution.queue", binding.getDestination());

        // 2. Result Queue
        Queue resultQueue = config.resultQueue();
        assertEquals("test.result.queue", resultQueue.getName());
        assertTrue(resultQueue.isDurable());

        Binding resultBinding = config.resultBinding(resultQueue, exchange);
        assertEquals("test.result.key", resultBinding.getRoutingKey());
        assertEquals("test.result.queue", resultBinding.getDestination());

        // 3. DLX & DLQ
        DirectExchange dlx = config.dlxExchange();
        assertEquals("test.exchange.dlx", dlx.getName());

        Queue dlq = config.executionDlq();
        assertEquals("test.execution.dlq", dlq.getName());

        Binding dlqBinding = config.executionDlqBinding(dlq, dlx);
        assertEquals("test.execution.dlk", dlqBinding.getRoutingKey());
        assertEquals("test.execution.dlq", dlqBinding.getDestination());

        // 4. Converter
        var converter = config.jackson2JsonMessageConverter(new ObjectMapper());
        assertNotNull(converter);
        assertInstanceOf(Jackson2JsonMessageConverter.class, converter);
    }
}
