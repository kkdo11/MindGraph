package org.mg.mindgraph.config.rabbitmq;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE_NAME = "mindgraph.exchange";
    public static final String QUEUE_NAME = "mindgraph.extraction.queue";
    public static final String ROUTING_KEY = "mindgraph.extraction.key";

    // Dead Letter Queue (실패 메시지 보관)
    public static final String DEAD_LETTER_EXCHANGE_NAME = "mindgraph.exchange.dlx";
    public static final String DEAD_LETTER_QUEUE_NAME = "mindgraph.extraction.queue.dlq";

    @Bean
    public TopicExchange exchange() {
        return new TopicExchange(EXCHANGE_NAME);
    }

    /**
     * 메인 큐 설정.
     * 실패한 메시지는 'mindgraph.exchange.dlx' Dead Letter Exchange로 보내지도록 설정합니다.
     */
    @Bean
    public Queue queue() {
        return QueueBuilder.durable(QUEUE_NAME)
                .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE_NAME)
                .withArgument("x-dead-letter-routing-key", DEAD_LETTER_QUEUE_NAME) // DLQ로 보낼 때 사용할 라우팅 키
                .build();
    }

    @Bean
    public Binding binding(Queue queue, TopicExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(ROUTING_KEY);
    }

    // === Dead Letter Queue 설정 ===
    @Bean
    public TopicExchange deadLetterExchange() {
        return new TopicExchange(DEAD_LETTER_EXCHANGE_NAME);
    }

    @Bean
    public Queue deadLetterQueue() {
        return new Queue(DEAD_LETTER_QUEUE_NAME, true);
    }

    @Bean
    public Binding deadLetterBinding(Queue deadLetterQueue, TopicExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(DEAD_LETTER_QUEUE_NAME);
    }
}
