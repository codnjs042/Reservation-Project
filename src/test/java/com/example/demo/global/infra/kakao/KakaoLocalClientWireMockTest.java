package com.example.demo.global.infra.kakao;

import com.example.demo.global.exception.BusinessException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("manual")
class KakaoLocalClientWireMockTest {

    private static final Duration KAKAO_READ_TIMEOUT = Duration.ofMillis(100);

    @Test
    void normal_get_result() {
        KakaoLocalClient client = buildClient("http://localhost:9090/api/mock/kakao/normal");

        PointDto result = client.getCoordinates("서울특별시 강남구 테헤란로 132");

        assertThat(result.latitude()).isEqualTo(37.4979806737437);
        assertThat(result.longitude()).isEqualTo(127.028361926941);
    }

    @Test
    void slow_get_exception() {
        KakaoLocalClient client = buildClient("http://localhost:9090/api/mock/kakao/slow");

        assertThatThrownBy(() -> client.getCoordinates("서울특별시 강남구 테헤란로 132"))
                .isInstanceOf(BusinessException.class);
    }

    private KakaoLocalClient buildClient(String baseUrl) {
        RestTemplate restTemplate = new RestTemplate();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(KAKAO_READ_TIMEOUT);
        restTemplate.setRequestFactory(factory);

        KakaoLocalClient client = new KakaoLocalClient(restTemplate);
        ReflectionTestUtils.setField(client, "apiKey", "dummy");
        ReflectionTestUtils.setField(client, "baseUrl", baseUrl);
        return client;
    }
}