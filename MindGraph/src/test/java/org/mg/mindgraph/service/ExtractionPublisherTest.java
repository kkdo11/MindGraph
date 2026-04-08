package org.mg.mindgraph.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mg.mindgraph.config.rabbitmq.RabbitMQConfig;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ExtractionPublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private ExtractionPublisher publisher;

    @Test
    @DisplayName("publish: RabbitTemplate.convertAndSend 호출 검증")
    void publish_RabbitTemplate_convertAndSend_호출() {
        // given
        String text = "Docker는 컨테이너 가상화 기술이다.";

        // when
        publisher.publishExtractionRequest(text);

        // then
        verify(rabbitTemplate).convertAndSend(
                RabbitMQConfig.EXCHANGE_NAME,
                RabbitMQConfig.ROUTING_KEY,
                text
        );
    }

    @Test
    @DisplayName("publish: 올바른 Exchange와 RoutingKey 사용 확인")
    void publish_올바른_Exchange_RoutingKey_사용() {
        // given
        String text = "테스트 텍스트";
        ArgumentCaptor<String> exchangeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> routingKeyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> messageCaptor = ArgumentCaptor.forClass(Object.class);

        // when
        publisher.publishExtractionRequest(text);

        // then
        verify(rabbitTemplate).convertAndSend(
                exchangeCaptor.capture(),
                routingKeyCaptor.capture(),
                messageCaptor.capture()
        );
        assertThat(exchangeCaptor.getValue()).isEqualTo("mindgraph.exchange");
        assertThat(routingKeyCaptor.getValue()).isEqualTo("mindgraph.extraction.key");
        assertThat(messageCaptor.getValue()).isEqualTo(text);
    }

    @Test
    @DisplayName("publish: 빈 문자열도 예외 없이 발행")
    void publish_빈문자열도_발행() {
        // given
        String emptyText = "";

        // when & then
        assertThatNoException().isThrownBy(() ->
                publisher.publishExtractionRequest(emptyText)
        );
        verify(rabbitTemplate).convertAndSend(
                RabbitMQConfig.EXCHANGE_NAME,
                RabbitMQConfig.ROUTING_KEY,
                emptyText
        );
    }
}
