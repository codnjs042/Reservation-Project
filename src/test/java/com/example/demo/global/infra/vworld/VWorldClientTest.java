package com.example.demo.global.infra.vworld;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;

@Tag("manual")
class VWorldClientTest {

    private static final Logger log = LoggerFactory.getLogger(VWorldClientTest.class);

    private static final int SAMPLE_SIZE = 30;

    @Test
    void observeP95() {
        RestTemplate vworldRestTemplate = new RestTemplate();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        vworldRestTemplate.setRequestFactory(factory);

        VWorldClient vworldClient = new VWorldClient(vworldRestTemplate);
        ReflectionTestUtils.setField(vworldClient, "apiKey", readEnvValue("DEV_VWORLD_API_KEY"));
        ReflectionTestUtils.setField(vworldClient, "baseUrl", "https://api.vworld.kr/req/data");
        ReflectionTestUtils.setField(vworldClient, "domain", "http://localhost:8081");

        long[] times = new long[SAMPLE_SIZE];

        for (int i = 0; i < SAMPLE_SIZE; i++) {
            long start = System.nanoTime();
            vworldClient.getArea("11");  // 실제 호출
            times[i] = (System.nanoTime() - start) / 1_000_000;
            log.info("[V-WorldAPI {}/{}] {}ms", i + 1, SAMPLE_SIZE, times[i]);
        }

        Arrays.sort(times);
        log.info("[V-WorldAPI] min={}ms, p95={}ms, max={}ms",
                times[0], p95(times), times[SAMPLE_SIZE - 1]);
    }

    private long p95(long[] sortedMillis) {
        int index = (int) Math.ceil(sortedMillis.length * 0.95) - 1;
        return sortedMillis[index];
    }

    private String readEnvValue(String key) {
        try {
            return Files.readAllLines(Path.of(".env")).stream()
                    .map(String::trim)
                    .filter(line -> line.startsWith(key + "="))
                    .map(line -> line.substring(key.length() + 1).trim().replaceAll("^\"|\"$", ""))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(key + " 값을 .env에서 찾을 수 없습니다."));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}