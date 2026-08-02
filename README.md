# 🍽️ YarmSpot — 온라인 식당 예약 시스템

> 유저·점주·관리자 3계층 권한 구조의 식당 예약 플랫폼 백엔드 API 서버

**배포 주소**: https://yarmspot.store  

---

## 주요 기능

### 유저
- 로컬 회원가입 / 로그인 + Google · Kakao · Naver 소셜 로그인
- 반경 3km 가게 지도 검색, 키워드·카테고리·지역 필터 검색, 인기 가게 TOP 9 조회
- 예약 가능 타임슬롯 조회 → 예약 신청 → 예약 취소
- 관심 가게 등록·해제
- 닉네임·이메일·비밀번호 변경, 회원 탈퇴

### 점주
- 가게 등록 시 USER → OWNER 자동 승격
- 가게 정보·상태 관리 (READY / OPEN / HIDDEN)
- 요일별 영업시간·예약 슬롯 간격 설정
- 테이블 그룹 등록·수정 (수용 인원, 수량)
- 예약 목록 조회, 예약 상태 일괄 변경 (REJECTED · VISITED · NO_SHOW)
- 가게 일괄 삭제

### 관리자
- 유저·가게·예약 전체 검색 및 상세 조회
- 유저 강제 탈퇴 (영구 정지)
- 서버 기동 시 CSV 기반 초기 가게 데이터 시딩 + 부하테스트용 유저 200명 자동 생성

---

## 기술 스택

| 분류 | 기술 |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 4.0.1 |
| ORM | Spring Data JPA (Hibernate) |
| Security | Spring Security, JJWT 0.13.0, Spring OAuth2 Client |
| Database | MySQL, Redis |
| Migration | Flyway |
| External API | Kakao Local API, V-World API |
| Resilience | Resilience4j (CircuitBreaker, Bulkhead) |
| Load/Fault Testing | k6, WireMock |
| Infra | AWS EC2 (t3.micro), Nginx, GitHub Actions |
| Docs | SpringDoc OpenAPI 3.0.1 (Swagger UI) |
| Etc | Lombok, P6Spy, Spring Actuator |

---

## 아키텍처 / 설계

### 계층 구조

```
Controller
    └─► Facade        여러 Service를 조합하는 오케스트레이션 레이어
            └─► Service   단일 도메인의 데이터 접근 + 유효성 검사 + 트랜잭션
                    └─► Repository   JpaRepository + JPQL / Native Query
```

- **Service**는 자신의 도메인 Repository만 접근합니다. 도메인 간 협력이 필요한 경우 반드시 **Facade**에서 처리합니다.
- 모든 Facade · Service는 `@Transactional(readOnly = true)` 기본, 쓰기 메서드에 `@Transactional` 오버라이드 패턴을 사용합니다.

### 인증 구조

```
[로컬 로그인]  POST /api/auth/login
    → Access Token (응답 body)
    → Refresh Token (HttpOnly Cookie) + Redis 저장

[소셜 로그인]  GET /oauth2/authorization/{provider}
    → OAuth2 인증 완료 후 프론트로 리다이렉트 (?accessToken=...)

[토큰 갱신]   POST /api/auth/refresh
    → 쿠키 Refresh Token ↔ Redis 검증 → 새 Access Token 발급

[로그아웃]    POST /api/auth/logout
    → Access Token → Redis 블랙리스트 등록
    → Refresh Token Redis 삭제 + 쿠키 만료
```

- **Access Token** — 클라이언트 메모리 보관, 매 요청 헤더(`Authorization: Bearer`) 전송
- **Refresh Token** — HttpOnly 쿠키 + Redis 이중 검증으로 탈취 위험 최소화
- **블랙리스트** — 로그아웃된 Access Token을 남은 TTL만큼 Redis에 등록, 필터에서 매 요청마다 조회

### 동시성 제어

경합이 발생할 수 있는 흐름 전반에서 **Pessimistic Write Lock** (3초 타임아웃)을 사용합니다. 초과 시 발생하는 `PessimisticLockingFailureException`은 전역 예외 처리기가 자동으로 409(`LOCK_TIMEOUT`)로 매핑합니다.

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000")})
Optional<Store> findByIdWithLock(@Param("id") Long id);
```

`Store`, `User`, `Reservation` Repository에 동일 패턴 적용 — 가게 등록 시 유저 락, 영업시간/테이블 변경·가게 삭제 시 가게 락 등에 사용됩니다.

**예약 생성만은 락 범위를 더 좁혔습니다.** 처음엔 예약 생성 시에도 가게 전체(`Store`)를 잠갔지만, 그러면 같은 가게에 대한 서로 다른 시간대·다른 테이블 예약 요청까지 전부 직렬화되어 처리량이 떨어졌습니다. 그래서 실제로 경합하는 자원인 "해당 시간대에 배정 가능한 테이블 1건"만 행 단위로 잠그도록 바꿨습니다.

```java
// StoreTableRepository — 배정 가능한 테이블 1건만 락
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000")})
@Query("""
        select t from StoreTable t
        where t.store.id = :storeId
        and :headCount between t.minCapacity and t.maxCapacity
        and t.status = :storeTableStatus
        and not exists (
            select 1 from Reservation r
            where r.targetDateTime = :targetDateTime
            and r.storeTable.id = t.id
            and r.status = :reservationStatus)
        order by t.maxCapacity asc
        limit 1
        """)
List<StoreTable> findFreeTableWithLock(...);
```

다만 락 범위 축소만으로는 MySQL 기본 격리수준(REPEATABLE READ)의 트랜잭션 스냅샷 읽기 때문에, 동시 트랜잭션이 서로 상대방의 커밋을 못 보고 같은 테이블에 중복 배정하는 문제가 남아있었습니다. `spring.datasource.hikari.transaction-isolation`을 `READ COMMITTED`로 전역 조정해 해결했고, `CountDownLatch`/`ExecutorService` 기반 동시성 테스트(50 스레드 동시 요청 → 테이블 수만큼만 성공, 중복 배정 0건)로 검증했습니다.

### 소프트 삭제 전략

| 엔티티 | 삭제 방식 | 특이사항 |
|---|---|---|
| User | `status=DELETED`, `deletedVersion=user.id` | `(username, deletedVersion)` 복합 유니크 → 탈퇴 후 재가입 허용 |
| Store | `status=SHUTDOWN` | 복구 불가 |
| Schedule / StoreTable / Favorite | `status=DELETED` | ACTIVE 복귀 가능 |

### ERD

![erd](./docs/erd.png)

### 가게 상태 전환 규칙

```
READY ──► OPEN     (활성 Schedule + 활성 StoreTable 이 모두 존재할 때만)
OPEN  ──► HIDDEN   (점주 직접 전환)
HIDDEN ──► OPEN / READY
OPEN/HIDDEN ──► SHUTDOWN  (삭제 흐름만, 직접 전환 불가)

Schedule 전체 삭제 또는 StoreTable 전체 삭제 → Store 자동 READY 복귀
```

### 외부 API 장애 격리 & 부하테스트

Kakao/V-World 같은 외부 API가 느려지거나 죽었을 때 서버 스레드가 함께 물려 죽지 않도록, 각 클라이언트를 전용 커넥션 풀 + 타임아웃 + Resilience4j `CircuitBreaker`/`Bulkhead`로 감쌌습니다.

- API별로 별도 `RestTemplate` 빈 분리 (Apache HttpClient5 풀링, 연결 5초 / 응답 100~400ms 타임아웃)
- 실패율 50% 초과 시 서킷 OPEN → 5초 후 HALF_OPEN 전환, 동시 호출 20건 제한(Bulkhead)
- 서킷 차단/타임아웃 시에도 곧바로 도메인 예외(`ADDRESS_NOT_FOUND`, `EXTERNAL_API_ERROR`)로 귀결시켜 호출부가 실패 원인을 구분할 필요 없게 처리

이 동작을 재현 가능한 상태로 검증하기 위해 WireMock으로 두 API를 모킹(요청마다 랜덤 지연으로 성공/타임아웃을 절반씩 섞음)하고, k6로 두 경로(가게 등록=Kakao, 지역조회=V-World)에 동시 트래픽을 발생시켜 서킷브레이커의 OPEN/HALF_OPEN 전환을 관찰했습니다 (`k6/circuit-breaker-bulkhead-mixed-traffic.js`). 부하테스트용 로그인 계정 200개는 서버 기동 시 자동 생성됩니다.

### 시스템 아키텍처

![infra](./docs/infra.png)

---

## 패키지 구조

```
src/main/java/com/example/demo/
├── domain/
│   ├── admin/       관리자 API
│   ├── favorite/    관심 가게
│   ├── owner/       점주 API
│   ├── reservation/ 예약
│   ├── schedule/    영업시간
│   ├── store/       가게
│   ├── storeTable/  테이블
│   └── user/        유저
└── global/
    ├── auth/        로그인·토큰·OAuth2
    ├── config/      Security·JPA·Redis·CORS 설정
    ├── exception/   BusinessException·ErrorCode·GlobalExceptionHandler
    ├── filter/      JwtAuthenticationFilter
    ├── infra/       외부 API 클라이언트 (Kakao·VWorld, Culture는 현재 미사용)
    ├── init/        서버 기동 시 관리자 계정·CSV 기반 가게 시딩·부하테스트 유저 초기화
    ├── provider/    JwtTokenProvider
    ├── security/    CustomUserDetails
    └── util/        JwtUtil·RedisUtil·SecurityUtil
```

---

## 배포 주소

| 구분 | URL |
|---|---|
| 서비스 | https://yarmspot.store |
| 백엔드 GitHub | https://github.com/codnjs042/Reservation-Project-BE |
| 프론트엔드 GitHub | https://github.com/codnjs042/Reservation-Project-FE |
