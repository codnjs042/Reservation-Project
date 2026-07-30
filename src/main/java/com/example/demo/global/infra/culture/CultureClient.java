package com.example.demo.global.infra.culture;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CultureClient {

    @Value("${culture.api.key}")
    private String apiKey;

    @Value("${culture.api.base-url}")
    private String baseUrl;

    public static final int PAGE_SIZE = 1000;

    @Qualifier("cultureRestTemplate")
    private final RestTemplate restTemplate;

    public List<CultureDto> fetchCultureData(String areaNm, String clNm) {
        URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                .queryParam("serviceKey", apiKey)
                .queryParam("numOfRows", PAGE_SIZE)
                .queryParam("pageNo", 1)
                .queryParam("areaNm", URLEncoder.encode(areaNm, StandardCharsets.UTF_8))
                .queryParam("clNm", URLEncoder.encode(clNm, StandardCharsets.UTF_8))
                .build(true)
                .toUri();

        log.info("[Culture API Request] areaNm: {}, clNm: {}", areaNm, clNm);

        try {
            CultureApiResponse response = restTemplate.getForObject(uri, CultureApiResponse.class);

            if (response != null
                    && response.response() != null
                    && response.response().body() != null
                    && response.response().body().items() != null
                    && response.response().body().items().item() != null) {

                List<CultureItem> items = response.response().body().items().item();
                log.info("[Culture API] 총 {}건 조회 (areaNm: {}, clNm: {})", items.size(), areaNm, clNm);
                return items.stream().map(CultureDto::from).toList();
            }
            log.warn("[Culture API Empty] 결과 없음 (areaNm: {}, clNm: {})", areaNm, clNm);
        } catch (Exception e) {
            log.error("[Culture API Error] 오류 발생: {}", e.getMessage());
        }
        return List.of();
    }
}