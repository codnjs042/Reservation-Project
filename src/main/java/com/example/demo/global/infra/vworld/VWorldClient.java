package com.example.demo.global.infra.vworld;

import com.example.demo.global.exception.BusinessException;
import com.example.demo.global.exception.ErrorCode;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class VWorldClient {
    @Value("${vworld.api.key}")
    private String apiKey;

    @Value("${vworld.api.base-url}")
    private String baseUrl;

    @Value("${app.backend-url}")
    private String domain;

    @Qualifier("vworldRestTemplate")
    private final RestTemplate restTemplate;

    @CircuitBreaker(name = "vworld", fallbackMethod = "fallbackGetArea")
    @Bulkhead(name = "vworld", fallbackMethod = "fallbackGetArea")
    public List<AreaResponse> getArea(String cd){
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(baseUrl)
                .queryParam("request", "GetFeature")
                .queryParam("key", apiKey)
                .queryParam("geometry", "false")
                .queryParam("domain", domain);

        if(cd==null || cd.isBlank()){
            URI uri = builder.queryParam("size", "17")
                    .queryParam("data", "LT_C_ADSIDO_INFO")
                    .queryParam("attrFilter", "ctprvn_cd:like:%")
                    .build()
                    .toUri();

            return fetch(uri, new ParameterizedTypeReference<VWorldAreaResponse<Region>>(){});
        }

        else{
            URI uri = builder
                    .queryParam("size", "50")
                    .queryParam("data", "LT_C_ADSIGG_INFO")
                    .queryParam("attrFilter", "sig_cd:like:"+cd+"%")
                    .build()
                    .toUri();

            return fetch(uri, new ParameterizedTypeReference<VWorldAreaResponse<District>>(){});
        }
    }

    private <T> List<AreaResponse> fetch(URI uri, ParameterizedTypeReference<VWorldAreaResponse<T>> type){
        try {
            ResponseEntity<VWorldAreaResponse<T>> response = restTemplate.exchange(
                    uri, HttpMethod.GET, null, type
            );

            log.info("[V-WORLD API Response] 응답 상태코드: {}", response.getStatusCode());
            log.info("[V-WORLD API Response] 응답 결과: {}", response.getBody());

            if (response.getBody() != null && response.getBody().response().result() != null) {
                return response.getBody().response().result().featureCollection().features().stream()
                        .map(feature -> {
                            T prop = feature.properties();
                            if(prop instanceof Region r){
                                return new AreaResponse(r.ctprvn_cd(), r.ctp_kor_nm());
                            }
                            else if(prop instanceof District d){
                                return new AreaResponse(d.sig_cd(), d.sig_kor_nm());
                            }
                            else
                                return null;
                        })
                        .filter(java.util.Objects::nonNull)
                        .toList();
            } else {
                log.warn("[V-WORLD API Empty] 검색 결과가 없습니다.");
            }
        } catch (Exception e) {
            log.error("[V-WORLD API error] API 호출 중 오류 발생: {}", e.getMessage());
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
        }
        return List.of();
    }

    private List<AreaResponse> fallbackGetArea(String cd, Throwable t) {
        log.warn("[V-WORLD] 서킷브레이커/벌크헤드로 호출 차단됨. cd: {}, 원인: {}", cd, t.toString());
        throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
    }
}