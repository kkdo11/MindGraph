package org.mg.mindgraph.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.mg.mindgraph.config.rabbitmq.RabbitMQConfig;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExtractionPublisher {

    private final RabbitTemplate rabbitTemplate;

    public void publishExtractionRequest(String text) {
        log.info("Publishing extraction request to RabbitMQ for text starting with: '{}...'", text.substring(0, Math.min(text.length(), 50)));
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_NAME, RabbitMQConfig.ROUTING_KEY, text);
    }
}
