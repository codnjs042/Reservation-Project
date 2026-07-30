// 사전 준비:
// 1) WireMock standalone을 9090 포트로, src/test/resources/wiremock를 root-dir로 기동
//    (kakao-mixed-stub.json / vworld-mixed-stub.json 매핑 포함)
// 2) .env에서 KAKAO_BASE_URL=http://localhost:9090/api/mock/kakao/mixed,
//    VWORLD_BASE_URL=http://localhost:9090/api/mock/vworld/mixed 로 설정 후 앱 기동
// 3) k6 run circuit-breaker-mixed-traffic.js
//
// 목적: kakao-mixed/vworld-mixed 스텁이 요청마다 독립적으로 랜덤 지연(성공/타임아웃 약 50:50)을
// 반환하므로, resilience4j 서킷브레이커(실패율 임계치 50%)가 OPEN/HALF_OPEN을 오가는 동작을
// 관찰한다. store_creation(Kakao 경로)과 area_lookup(VWorld 경로)을 비슷한 강도로 동시에 돌려
// 두 서킷의 개폐 타이밍을 비교한다.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

const kakaoDuration = new Trend('kakao_duration');
const vworldDuration = new Trend('vworld_duration');

function readEnvFile(path, key) {
  const content = open(path);
  const line = content.split('\n').find((l) => l.trim().startsWith(`${key}=`));
  if (!line) return null;
  return line.split('=').slice(1).join('=').trim().replace(/^"|"$/g, '');
}

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';
const KAKAO_VUS = parseInt(__ENV.KAKAO_VUS || '100', 10);
const VWORLD_VUS = parseInt(__ENV.VWORLD_VUS || '100', 10);
const DURATION = __ENV.DURATION || '30s';

// AdminInitializer가 서버 기동 시 만든 {TEST_USERNAME}0001~0030 계정을 그대로 사용 (.env 값 재사용)
const TEST_USERNAME = __ENV.TEST_USERNAME || readEnvFile('../.env', 'TEST_USERNAME');
const TEST_PASSWORD = __ENV.TEST_PASSWORD || readEnvFile('../.env', 'TEST_PASSWORD');

// businessNumber는 status!=SHUTDOWN 범위에서 유니크 제약이 있어(StoreService.existsByBusinessNumberAndStatusNot),
// 매 요청 동일 값으로 보내면 첫 요청 이후 전부 DUPLICATE_DATA(409)로 실패한다.
// __VU/__ITER 조합으로 요청마다 고유한 번호를 만들어 name/businessNumber에 반영한다.
function buildStorePayload() {
  const uniqueId = __VU * 1000000 + __ITER;

  return JSON.stringify({
    name: `부하테스트가게${uniqueId}`,
    category: 'KOREAN',
    address: '서울특별시 강남구 테헤란로 132',
    detailAddress: '1층',
    zipCode: '06134',
    sigunguCode: '11680',
    phone: '0212345678',
    ownerName: '김둘리',
    businessNumber: String(uniqueId).padStart(10, '0'),
  });
}

export const options = {
  scenarios: {
    // Kakao mixed stub 경로 - 서킷브레이커 "kakao" 인스턴스 관찰
    store_creation: {
      executor: 'constant-vus',
      exec: 'createStore',
      vus: KAKAO_VUS,
      duration: DURATION,
    },
    // VWorld mixed stub 경로 - 서킷브레이커 "vworld" 인스턴스 관찰
    area_lookup: {
      executor: 'constant-vus',
      exec: 'lookupArea',
      vus: VWORLD_VUS,
      duration: DURATION,
    },
  },
};

export function setup() {
  if (!TEST_USERNAME || !TEST_PASSWORD) {
    throw new Error('TEST_USERNAME/TEST_PASSWORD를 찾을 수 없습니다 (.env 또는 -e 플래그로 전달해 주세요).');
  }

  const tokens = [];

  for (let i = 1; i <= KAKAO_VUS; i++) {
    const username = `${TEST_USERNAME}${String(i).padStart(4, '0')}`;

    const loginRes = http.post(
      `${BASE_URL}/api/auth/login`,
      JSON.stringify({ username, password: TEST_PASSWORD }),
      { headers: { 'Content-Type': 'application/json' } }
    );
    if (loginRes.status !== 200) {
      throw new Error(`login 실패 (${username}): ${loginRes.status} ${loginRes.body} — AdminInitializer가 계정을 만들었는지 확인하세요.`);
    }
    tokens.push(loginRes.json('accessToken'));
  }

  return { tokens };
}

export function createStore(data) {
  const token = data.tokens[__VU % data.tokens.length];

  const res = http.post(`${BASE_URL}/api/stores`, buildStorePayload(), {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    },
    tags: { scenario: 'store_creation' },
  });

  kakaoDuration.add(res.timings.duration);
  check(res, { 'store_creation: 응답이 왔음 (성공이든 서킷 차단이든)': (r) => r.status !== 0 });
  sleep(1);
}

export function lookupArea() {
  const res = http.get(`${BASE_URL}/api/area`, {
    tags: { scenario: 'area_lookup' },
  });

  vworldDuration.add(res.timings.duration);
  check(res, { 'area_lookup: 응답이 왔음 (성공이든 서킷 차단이든)': (r) => r.status !== 0 });
  sleep(1);
}
