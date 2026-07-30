package com.example.demo.global.infra.vworld;

import com.example.demo.global.exception.BusinessException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("manual")
class VWorldClientWireMockTest {

    private static final Duration VWORLD_READ_TIMEOUT = Duration.ofMillis(400);

    @Test
    void normal_get_result() {
        VWorldClient client = buildClient("http://localhost:9090/api/mock/vworld/normal");

        List<AreaResponse> result = client.getArea("11110");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).cd()).isEqualTo("11110");
        assertThat(result.get(0).name()).isEqualTo("종로구");
    }

    @Test
    void slow_get_exception() {
        VWorldClient client = buildClient("http://localhost:9090/api/mock/vworld/slow");

        assertThatThrownBy(() -> client.getArea("11"))
                .isInstanceOf(BusinessException.class);
    }

    private VWorldClient buildClient(String baseUrl) {
        RestTemplate restTemplate = new RestTemplate();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(VWORLD_READ_TIMEOUT);
        restTemplate.setRequestFactory(factory);

        VWorldClient client = new VWorldClient(restTemplate);
        ReflectionTestUtils.setField(client, "apiKey", "dummy");
        ReflectionTestUtils.setField(client, "baseUrl", baseUrl);
        ReflectionTestUtils.setField(client, "domain", "http://localhost:8081");
        return client;
    }
}