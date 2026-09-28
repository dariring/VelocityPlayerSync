# VelocityPlayerSync 기획안

> 상태: **구현 완료 (v1.0.0)** · 작성일 2026-09-28 · 사용법은 README.md
> 패키지: `kr.hirest.velocityplayersync` · 언어: Kotlin · 플랫폼: Velocity 4.2.x (Java 25)

### 확정된 결정
| 항목 | 결정 |
|---|---|
| Velocity | **4.x 최신 (API 4.2.0, Java 25)** |
| TAB 연동 | **TAB 내장 `proxy-support: REDIS` 사용**, VPS는 핑·플레이스홀더·명령어 담당 |
| 추가 기능 | **중복 접속 방지, MiniPlaceholders, 전역 명령어** (개발자 API는 제외) |
| 버니시 | **CMI**: 백엔드용 보조 플러그인 `VPS-Bridge`로 버니시 상태를 프록시에 전달 (4.6) |
| 기존 플러그인 | AdvancedGuilds · AdvancedChat · AdvancedTeleport는 **연동 작업 없이 그대로 동작** (11장) |

---

## 1. 목표

같은 Redis에 연결된 여러 Velocity 프록시가 **접속 플레이어 정보를 공유**해서, 각 프록시에서 보이는 인원수와 플레이어 목록이 **네트워크 전체 기준**이 되도록 한다.

```
            ┌──────────── Redis ────────────┐
            │  proxy-a: 30명 / proxy-b: 33명 │
            └───────▲───────────────▲───────┘
                    │               │
   ┌────────────────┴──┐     ┌──────┴────────────┐
   │ Velocity A (30명)  │     │ Velocity B (33명)  │
   │ 서버목록: 63명      │     │ 서버목록: 63명      │
   │ TAB %online%: 63   │     │ TAB %online%: 63   │
   │ 탭리스트: 63명 이름  │     │ 탭리스트: 63명 이름  │
   └───────────────────┘     └───────────────────┘
```

**완료 기준 (예시: A 30명 + B 33명)**
- 서버 목록(핑)의 인원수가 두 프록시 모두 63으로 보인다 (MiniMOTD 사용 시 `<online_players>`도 63).
- TAB의 `%online%`이 63, 탭리스트에 63명의 이름이 모두 보인다.
- 프록시 하나가 비정상 종료돼도 일정 시간(기본 15초) 안에 해당 프록시의 인원이 목록에서 빠진다.

---

## 2. 사전 조사 결과 (설계에 직접 영향)

### 2.1 MiniMOTD: 별도 연동 없이 해결됨
- MiniMOTD(Velocity)는 `ProxyPingEvent`를 **기본 우선순위**로 받고, 인원수를 `event.ping.onlinePlayers`에서 읽는다 (`PingListener.java`).
- 따라서 **VPS가 더 높은 우선순위에서 핑의 온라인 수를 전체 인원으로 바꿔 두면** MiniMOTD의 `<online_players>`, `just-one-more` 같은 기능이 자동으로 전체 인원 기준으로 동작한다.
- ⚠️ 예외: MiniMOTD의 `target-servers`를 설정하면 MiniMOTD가 `RegisteredServer.playersConnected`(로컬 인원)를 직접 세기 때문에 반영되지 않는다. 이 설정은 비워 둬야 한다.
- MiniMOTD는 **MiniPlaceholders**를 지원하므로, VPS가 MiniPlaceholders 확장을 제공하면 서버별 인원 같은 추가 정보도 MOTD에 넣을 수 있다.

### 2.2 TAB: 이미 멀티 프록시 동기화 기능이 내장돼 있음 ⭐
- TAB에는 `proxy-support` 설정이 있고, `type: REDIS`를 쓰면 **TAB끼리 Redis로 직접 통신**한다.
  ```yaml
  proxy-support:
    enabled: true
    type: REDIS          # PLUGIN | REDIS | RABBITMQ
    redis:
      url: 'redis://:password@localhost:6379/0'
  ```
- 이 기능이 켜져 있으면:
  - `%online%`에 **다른 프록시의 플레이어 수가 합산**된다 (`UniversalPlaceholderRegistry`: 로컬 + `proxy.getProxyPlayers()`, 버니시 제외).
  - `global-playerlist`에 **다른 프록시의 플레이어가 탭리스트에 표시**된다.
  - 탭리스트 이름 포맷(접두사/접미사), 네임태그, 정렬, belowname, playerlist objective도 함께 동기화된다. (Layout 기능은 미지원)
- `type: PLUGIN`은 RedisBungee API 클래스(`com.imaginarycode...RedisBungeeAPI`)만 인식한다. VPS가 여기에 끼어들려면 RedisBungee API를 흉내 내야 해서 권장하지 않는다.

> **결론:** "TAB에서 63명, 탭리스트에 63명 이름"이라는 요구사항은 **TAB 내장 `proxy-support: REDIS`가 가장 정확하게 해결한다** (접두사·정렬·스킨·핑까지 맞음).
> VPS가 탭리스트 항목을 직접 넣는 방식은 가능하지만, TAB의 포맷·정렬 체계 밖에 있어서 다른 프록시 플레이어만 접두사 없이 맨 아래에 정렬되는 등 품질이 떨어진다. → **결정: TAB 내장 기능을 쓴다.** 각 프록시의 TAB `config.yml`에서 `proxy-support`를 켜고 VPS와 같은 Redis를 지정한다.

### 2.3 Velocity 버전과 Java 버전
| Velocity API | 필요한 Java | 비고 |
|---|---|---|
| 3.4.0 (현재 베이스) | Java 17 | 베이스는 `3.4.0-SNAPSHOT` |
| 4.2.0 (현재 최신 릴리스) | **Java 25** | |

→ **결정: 4.2.x / Java 25** 기준으로 빌드한다.

---

## 3. 역할 분담 (확정)

| 기능 | 담당 | 방식 |
|---|---|---|
| 전역 플레이어 목록 (누가, 어느 프록시·서버에 있는지) | **VPS** | Redis 레지스트리 + 로컬 캐시 |
| 서버 목록 인원수 / 호버 플레이어 목록 | **VPS** | `ProxyPingEvent` 수정 |
| MiniMOTD `<online_players>` | VPS (자동) | 위 핑 수정이 그대로 반영됨 |
| TAB `%online%`, 탭리스트 63명 표시 | **TAB 내장** | `proxy-support: REDIS` (같은 Redis) |
| 서버별·프록시별 인원 플레이스홀더 | **VPS** | MiniPlaceholders 확장 |
| 전역 명령어 (`/vps list`, `find` 등) | **VPS** | |
| 중복 접속 방지 | **VPS** | Redis 조회 + Pub/Sub 킥 요청 |
| 버니시 플레이어 제외 | **VPS + VPS-Bridge** | 백엔드에서 CMI 버니시 상태를 읽어 프록시로 전달 (4.6) |

---

## 4. 기능 명세

### 4.1 [코어] 전역 플레이어 레지스트리
- 각 프록시는 자기 플레이어 정보를 Redis에 기록하고, 모든 프록시의 정보를 **로컬 메모리 캐시**로 유지한다.
- 핑·플레이스홀더·명령어는 **로컬 캐시만 읽는다** (핑 처리 중 Redis 호출 없음 → 지연 없음).
- 플레이어 데이터: `GlobalPlayer(uuid, name, proxyId, serverName?, joinedAt, vanished)`
- 반영 시점: 접속(`PostLoginEvent`), 서버 이동(`ServerConnectedEvent`), 퇴장(`DisconnectEvent`)
- 같은 UUID가 두 프록시에 동시에 잡히면 **1명으로 센다** (UUID 기준 중복 제거).

### 4.2 서버 목록(핑) 인원 공유
- `ProxyPingEvent`를 **MiniMOTD보다 높은 우선순위**로 처리한다.
- 온라인 수를 전체 인원(버니시 제외)으로 교체한다.
- 최대 인원(max players) 처리 모드: `KEEP`(프록시 설정 유지, 기본) / `FIXED`(고정값) / `ONLINE_PLUS`(온라인+N)
- 호버 샘플(서버 목록에서 인원수에 마우스 올리면 나오는 이름 목록): 전체 플레이어 중 최대 N명(기본 12)을 무작위로 표시(버니시 제외). 끌 수 있음.
- Redis 장애 시: 로컬 인원만 표시한다 (설정으로 변경 가능).

### 4.3 플레이스홀더 (MiniPlaceholders 확장)
| 플레이스홀더 | 설명 |
|---|---|
| `<vps_online>` | 전체 인원 (버니시 제외) |
| `<vps_online_local>` | 이 프록시 인원 |
| `<vps_online_server:lobby>` | 특정 서버(모든 프록시 합산) 인원 |
| `<vps_online_proxy:proxy-a>` | 특정 프록시 인원 |
| `<vps_proxy_count>` | 살아있는 프록시 수 |
| `<vps_proxy_id>` | 이 프록시의 proxy-id (플레이어가 접속한 프록시와 같으므로 전역 플레이스홀더로 구현) |

- MiniPlaceholders는 선택 의존성(soft depend)으로 둔다. 설치돼 있을 때만 확장을 등록한다.
- TAB의 `%online%`은 TAB 자체 기능으로 이미 전체 인원이 되므로 TAB용 플레이스홀더는 만들지 않는다.

### 4.4 명령어 (권한 `velocityplayersync.admin`)
| 명령어 | 설명 |
|---|---|
| `/vps status` | 연결된 프록시 목록, 프록시별 인원, 마지막 하트비트, Redis 지연 시간 |
| `/vps list [서버]` | 전체(또는 서버별) 플레이어 목록 |
| `/vps find <닉네임>` | 플레이어가 있는 프록시와 서버 |
| `/vps reload` | 설정 다시 불러오기 |
| (선택) `/glist` 대체 | Velocity 기본 `/glist`를 전체 네트워크 기준으로 덮어쓰기 |

### 4.5 중복 접속 방지
- 이미 다른 프록시에 접속 중인 UUID로 접속하면 기존 접속을 끊거나(`KICK_OLD`, 기본값) 새 접속을 거부한다(`DENY_NEW`).
- 기본값이 `KICK_OLD`인 이유: 바닐라와 같은 동작이고, 비정상으로 끊긴 유령 세션이 남아 있어도 플레이어가 다른 프록시로 바로 다시 들어올 수 있다.
- 기존 접속 끊기는 Pub/Sub로 해당 프록시에 킥 요청을 보낸다.
- 판정 시점은 `LoginEvent`(인증 후, 입장 전). 로컬 캐시가 아니라 **Redis에서 직접 확인**해서 캐시 지연으로 인한 누락을 막는다.
- 두 프록시에 거의 동시에 들어오는 경우는 `SET vps:lock:{uuid} {proxyId} NX PX 5000`으로 한쪽만 통과시킨다.

### 4.6 버니시 연동 (CMI)
**문제:** CMI는 백엔드(Paper) 플러그인이라 Velocity에서는 누가 버니시 상태인지 알 수 없다.

**확인한 사실**
- CMI는 버니시 시 Bukkit 플레이어 메타데이터 `vanished = true`를 설정한다 (TAB 위키에 CMI가 명시돼 있음).
- TAB도 프록시에 설치된 경우 **백엔드의 TAB-Bridge**가 이 메타데이터를 읽어 프록시로 전달한다. 즉 TAB의 `%online%`에서 버니시 제외가 되려면 **TAB-Bridge가 모든 백엔드에 설치돼 있어야 한다.**
- TAB API에는 프록시 쪽 버니시 상태를 외부 플러그인이 읽는 공개 메서드가 없다 → VPS가 TAB에서 값을 빌려 올 수 없다.

**방식: `VPS-Bridge` (백엔드 Paper용 보조 플러그인, 같은 프로젝트의 별도 모듈)**
```
[Paper + CMI + VPS-Bridge] ──플러그인 메시지──▶ [Velocity + VPS] ──Redis──▶ 다른 프록시
      vanished 메타데이터 감시          velocityplayersync:bridge
```
- Bridge는 접속 중인 플레이어의 `vanished` 메타데이터를 10틱(0.5초)마다 확인하고, **바뀌었을 때만** 해당 플레이어의 연결로 플러그인 메시지를 보낸다. CMI API에 의존하지 않아서 CMI 버전이 바뀌어도 깨지지 않는다(다른 버니시 플러그인으로 바꿔도 동작).
- 입장 직후에는 CMI가 버니시를 다시 적용할 시간을 주기 위해 1초 뒤 첫 상태를 보낸다.
- Bridge에는 Redis 설정이 필요 없다 (Redis는 프록시만 연결).
- 🔒 VPS는 플러그인 메시지의 출처가 **백엔드 서버(`ServerConnection`)인 경우에만** 받아들인다. 클라이언트도 같은 채널로 메시지를 보낼 수 있어서, 이 검사가 없으면 아무 플레이어나 자기 자신을 버니시로 위장할 수 있다. 처리한 메시지는 클라이언트로 전달하지 않는다.

**접속 직후 노출 방지**
- 관리자가 재접속하면 Bridge가 상태를 보내기 전 1~2초 동안 인원수에 잡힐 수 있다.
- → 마지막 버니시 상태를 `vps:vanish-last:{uuid}`(30일 TTL)에 기억해 두고, 접속 시 이 값으로 먼저 판정한 뒤 Bridge 보고로 확정한다.

**적용 범위**
- 버니시 상태 플레이어는 **서버 목록 인원수·호버 샘플·`<vps_online>`·`/vps list`(일반 권한)**에서 제외한다. 관리자(`velocityplayersync.see-vanished`)는 `/vps list`, `/vps find`에서 `[V]` 표시와 함께 볼 수 있다.
- 버니시 상태는 `GlobalPlayer.vanished`로 Redis에 함께 동기화하고, 바뀌면 `VANISH` 이벤트를 발행한다.

---

## 5. Redis 설계

### 5.1 키 구조 (`prefix` 기본값 `vps`)
| 키 | 타입 | 내용 | TTL |
|---|---|---|---|
| `vps:proxies` | SET | 등록된 proxyId 목록 | 없음 (정리는 아래 규칙으로) |
| `vps:proxy:{id}` | HASH | `instance`(부팅마다 랜덤 UUID), `startedAt`, `version` | 15초, 하트비트마다 갱신 |
| `vps:players:{id}` | HASH | `uuid → JSON{name, server, joinedAt, vanished}` | 15초, 하트비트마다 갱신 |
| `vps:lock:{uuid}` | STRING | 중복 접속 판정용 잠금 | 5초 |
| `vps:vanish-last:{uuid}` | STRING | 마지막 버니시 상태 (재접속 직후 노출 방지) | 30일 |
| `vps:events` | Pub/Sub 채널 | `JOIN` / `QUIT` / `SWITCH` / `VANISH` / `PROXY_UP` / `PROXY_DOWN` / `KICK` | – |

- 프록시 단위로 키를 나눈 이유: **프록시가 죽으면 TTL 만료만으로 그 프록시 플레이어가 한꺼번에 정리된다.**
- `vps:proxies`에 남아 있지만 `vps:proxy:{id}`가 만료된 ID는 죽은 프록시로 보고 제거한다.

### 5.2 동기화 방식
1. **실시간 반영**: 입장·퇴장·이동 시 자기 해시를 수정하고 `vps:events`로 발행 → 다른 프록시가 즉시 캐시에 반영한다.
2. **정기 전체 동기화**(기본 10초): 모든 프록시의 `vps:players:*`를 파이프라인으로 읽어 캐시를 새로 만든다. Pub/Sub는 메시지가 유실될 수 있어서(재연결 중 등) 이 과정으로 어긋난 부분을 보정한다.
3. **하트비트**(기본 5초): 자기 키의 TTL을 연장한다.

### 5.3 생명주기
- **시작**: 같은 `proxy-id`가 살아 있는지 확인 (다른 `instance`의 하트비트가 살아 있으면 쓰기를 멈추고 경고. 비정상 종료 직후 재시작이면 약 11초 뒤 저절로 이어받고, 계속되면 오류로 알림) → 자기 키 초기화 → 현재 접속자 등록 → `PROXY_UP` 발행 → 전체 동기화.
- **종료**: 자기 키 삭제 → `PROXY_DOWN` 발행.
- **비정상 종료**: 최대 `proxy-timeout`(15초) 후 TTL 만료로 자동 정리.

### 5.4 경쟁 상황 처리
- 프록시 A에서 나가 B로 바로 들어가는 경우, A의 `QUIT`이 B의 `JOIN`보다 늦게 도착할 수 있다. → `QUIT`은 **캐시의 해당 플레이어 `proxyId`가 보낸 쪽과 같을 때만** 제거한다.
- 프록시 간 시계 차이는 Redis TTL로 판정하므로 영향이 없다.

---

## 6. 설정 파일 초안 (`config.yml`)

```yaml
# 프록시마다 반드시 다르게 설정
proxy-id: "proxy-a"

redis:
  # TAB proxy-support와 같은 URL 형식
  url: "redis://:password@localhost:6379/0"
  key-prefix: "vps"
  timeout-ms: 2000

sync:
  heartbeat-interval-seconds: 5
  proxy-timeout-seconds: 15
  full-resync-interval-seconds: 10

ping:
  enabled: true
  max-players:
    mode: KEEP          # KEEP | FIXED | ONLINE_PLUS
    fixed: 500
    online-plus: 1
  sample:
    enabled: true
    max-entries: 12
  on-redis-failure: LOCAL_ONLY   # LOCAL_ONLY | LAST_KNOWN

duplicate-login:
  enabled: true
  action: KICK_OLD      # KICK_OLD | DENY_NEW

messages:               # MiniMessage 형식
  prefix: "<gray>[<aqua>VPS</aqua>]</gray> "
  duplicate-login: "<red>이미 다른 프록시에 접속 중입니다.</red>"
```

---

## 7. 기술 스택 / 빌드

| 항목 | 선택 | 이유 |
|---|---|---|
| Redis 클라이언트 | **Jedis** (shade + relocate) | 단순한 동기 API, Netty 의존성이 없어 Velocity 내장 Netty와 충돌 위험이 적음. I/O는 전부 비동기 스레드에서 실행 |
| 직렬화 | Velocity에 포함된 **Gson** | 추가 의존성 없음 |
| 설정 | Configurate 4 (YAML) | Velocity 가 제공하므로 shade 하지 않음 |
| 메시지 | Adventure MiniMessage | Velocity 기본 포함 |
| 스레딩 | Pub/Sub 전용 스레드 1개(끊기면 백오프하며 재연결) + 작업용 스레드 풀 | Netty 이벤트 루프나 이벤트 스레드에서 Redis를 호출하지 않음 |

### 7.1 모듈 구조
```
VelocityPlayerSync/
├─ velocity/   Velocity 플러그인 본체 (VPS)        · Velocity API 4.2.0 · Java 25
└─ bridge/     Paper용 보조 플러그인 (VPS-Bridge)  · Paper API (api-version 1.21) · Java 21
```
- Bridge는 메타데이터 확인과 플러그인 메시지 전송만 하므로 가능한 한 낮은 버전으로 빌드해서 백엔드 버전(1.21.x / 26.x)에 상관없이 쓸 수 있게 한다.
- 빌드하면 `VelocityPlayerSync-<버전>.jar`, `VelocityPlayerSync-Bridge-<버전>.jar` 두 개가 나온다.

### 7.2 베이스 프로젝트 정리 항목
- [x] 패키지명 `kr.hirest.velocityPlayerSync` → **`kr.hirest.velocityplayersync`** (현재 카멜케이스, `package` 줄 끝에 불필요한 `;`)
- [x] Kotlin `2.0.20-Beta1`(베타) → 최신 안정판 (현재 2.4.20)
- [x] `com.github.johnrengelman.shadow` 8.1.1(더 이상 관리 안 됨) → `com.gradleup.shadow` 9.x
- [x] `kotlin-stdlib-jdk8` → `kotlin-stdlib` (jdk8 아티팩트는 통합됨), Kotlin stdlib·Jedis 등 **relocate**해서 다른 Kotlin 플러그인과 충돌 방지
- [x] `run-velocity` 2.3.1 → 3.1.0, Gradle 8.8 → 9.7.1 (run-velocity 3.1 이 Gradle 9.7 이상 요구)
- [x] kapt 제거: `velocity-plugin.json` 을 직접 두고 버전만 치환 (Velocity 는 `@Plugin` 애너테이션 없이 이 파일로 로드함)
- [x] Velocity API `3.4.0-SNAPSHOT` → **`4.2.0`**, `jvmToolchain(17)` → **`jvmToolchain(25)`**, `runVelocity`도 4.2.0
- [x] MiniPlaceholders API를 `compileOnly`로 추가 (선택 의존성)
- [x] `git init`

---

## 8. 개발 단계

| 단계 | 내용 | 결과물 |
|---|---|---|
| 0 | 베이스 정리 (7.2), 멀티 모듈 구성 (7.1) | 빌드 통과, 빈 플러그인 로드 확인 |
| 1 | 코어: 설정, Redis 연결, 레지스트리, 하트비트, Pub/Sub, 정기 동기화 | 두 프록시가 서로의 인원을 로그로 확인 |
| 2 | 핑 연동 | 서버 목록 63명, MiniMOTD 반영 확인 |
| 3 | 명령어 (`/vps status/list/find/reload`) | |
| 4 | MiniPlaceholders 확장 | MiniMOTD에서 `<vps_online_server:lobby>` 표시 확인 |
| 5 | 중복 접속 방지, `bridge` 모듈 + CMI 버니시 연동 | CMI `/vanish` 시 두 프록시의 서버 목록 인원이 1 줄어듦 |
| 6 | 테스트: 내장 Redis 단위·통합 테스트 28개 + 실제 Velocity 4.2.0 두 대 스모크 테스트 | 아래 8.1 |

---

### 8.1 검증 결과 (2026-09-28)
- 자동 테스트 28개 (`./gradlew :velocity:test`): 30+33=63 공유, 늦게 켜진 프록시의 전체 동기화, 버니시 제외·재접속 시 유지, 정상/비정상 종료, 중복 접속(KICK_OLD/DENY_NEW/동시 접속 잠금), 같은 proxy-id 충돌과 이어받기, Redis 재시작 복구, 설정 파싱. 3회 반복 모두 통과.
- 실제 Velocity 4.2.0 두 대 + Redis 6.2:
  - 플러그인 로드, Redis 등록, `/vps status·list·find·reload` 정상
  - 다른 프록시 인원이 두 프록시의 서버 목록 인원·호버 이름에 반영, 버니시 제외
  - 실제 오프라인 로그인 시 Redis 등록 → 끊기면 삭제·잠금 해제
  - KICK_OLD: 킥 요청 발행 후 3초 대기 뒤 허용, DENY_NEW: 설정한 메시지로 거부
  - `kill -9` 후 약 16초 안에 목록에서 제거, 즉시 재시작 시 충돌 경고 후 약 10초 뒤 자동 해결
  - Redis 중지 시 이 프록시 인원만 표시(경고 1회), 재시작 후 자동 복구
  - MiniPlaceholders 3.2.0 에서 `<vps_*>` 6개 해석 확인

### 8.2 전체 환경 테스트 (EULA 동의 후, 2026-09-28)
구성: Redis 6.2 + Velocity 4.2.0 ×2 (VPS, TAB 6.2.0 `proxy-support: REDIS`, MiniMOTD 2.2.5, MiniPlaceholders 3.2.0) + Paper 26.1.2 ×2 (VPS-Bridge, TAB-Bridge 6.2.3, 버니시 테스트 플러그인) + mineflayer 봇.

- 시나리오 1 (25/25): 봇 30명 + 33명 → 두 프록시 모두 서버 목록 63, 호버 12명, MiniMOTD `ONLINE 63/200`, `<vps_online_server:...>` 55/8, TAB 탭리스트 63명·`%online%` 63. 버니시 → 두 프록시 62 (TAB 포함), 해제 → 63. 다른 프록시로 같은 계정 접속 → 기존 접속이 설정한 메시지로 끊기고 새 접속 성공. B `kill -9` → 약 17초 뒤 A 의 서버 목록·TAB 탭리스트·`%online%` 모두 30, 유령 없음.
- 시나리오 2 (12/12): 접속자 있는 상태로 `/vps reload`, Redis 중지(각자 인원만 표시, 접속 유지) → 빈 Redis 로 재시작 후 4.8초 만에 복구, 버니시한 플레이어가 다른 프록시로 재접속해도 바로 숨김 유지, B 정상 종료 → 0.3초 만에 A 에서 제외, TAB 도 일치.
- **CMI 는 유료라 받을 수 없어**, CMI 와 같은 `vanished` 메타데이터를 다는 테스트 플러그인으로 대신했다. Bridge 는 CMI API 가 아니라 이 메타데이터만 읽으므로 확인한 흐름은 같다.

### 8.3 테스트 중 발견해 고친 것
| 문제 | 조치 |
|---|---|
| TAB 은 프록시가 비정상 종료되면 그 프록시 플레이어를 탭리스트·`%online%` 에 영구히 남김 (TAB 소스로 확인: 정리 기능 없음) | **TAB 유령 정리** 추가: TAB 형식의 PlayerQuit 을 대신 발행. 프록시마다 중복 발행하지 않도록 잠금 |
| TAB 은 정상 종료 때도 퇴장 알림 일부를 못 보내고 꺼짐 (`%online%` 6, 실제 1) | 플레이어 퇴장·프록시 종료 때 VPS 도 TAB 퇴장을 한 번 더 발행. 종료 시 쌓인 쓰기를 버리지 않고 마저 처리 |
| 위 정리가 Redis 가 빈 상태로 재시작된 직후 **살아 있는 플레이어 10명**을 지우려 함 | Redis 문제(연결 실패·자기 키 유실·구독 끊김) 직후 30초간 비정상 종료 정리 중단. 수정 전 코드에서 실패하는 테스트로 확인 |
| 비정상 종료 직후 재시작 시 proxy-id 충돌을 바로 ERROR 로 기록 | WARN 으로 시작, 계속될 때만 ERROR |
| Jedis 가 연결마다 RESP3 경고 출력 | 자동 협상 끔 |

## 9. 참고: 기존 대안
- **RedisBungee (Velocity 포크)**: 전역 인원수·API·중복 접속 방지를 제공하고 TAB의 `PLUGIN` 모드와도 연동된다. 다만 무겁고 커스터마이징이 어렵다. VPS는 **필요한 기능만 가볍게, TAB 내장 Redis 동기화와 함께 쓰는 것**을 목표로 한다.

---

## 10. 질문

### 결정 완료
- ~~Q1. Velocity 버전~~ → 4.x 최신 (Java 25)
- ~~Q2. TAB 연동 방식~~ → TAB 내장 `proxy-support: REDIS`
- ~~Q3. 추가 기능~~ → 중복 접속 방지, MiniPlaceholders, 전역 명령어 (개발자 API 제외)
- ~~Q4. 버니시~~ → CMI (VPS-Bridge로 연동)

### 답이 없으면 기본값으로 진행할 것
| 질문 | 기본값 |
|---|---|
| Redis 구성 (단일 / Sentinel / Cluster) | 단일 인스턴스 |
| 서버 목록의 최대 인원 표시 | `KEEP` (각 프록시 설정 유지) |
| 호버 샘플 이름 수 | 12명, 무작위 |
| 프록시 장애 판정 시간 | 15초 |
| 중복 접속 시 처리 | `KICK_OLD` (기존 접속 끊기, 바닐라와 동일) |
| 메시지 언어 | 한국어 기본, 설정 파일에서 수정 |
| 설정 파일 형식 | YAML |
| Velocity 기본 `/glist` 덮어쓰기 | 안 함 (`/vps list`만 제공) |
| 프록시 수 규모 | 2~10대 기준으로 설계 |

---

## 11. 기존 플러그인 연동 검토 (AdvancedGuilds · AdvancedChat · AdvancedTeleport)

### 11.1 결론: 연동 작업 필요 없음
세 플러그인 모두 **백엔드(Paper) 플러그인**이고, 프록시가 아니라 **백엔드 서버끼리 Redis로 통신**한다. 프록시가 몇 대든 백엔드 입장에서는 달라지는 게 없다.

| 플러그인 | 접속자 파악 방식 | 서버 이동 방식 | 여러 프록시 영향 |
|---|---|---|---|
| AdvancedGuilds | 로컬 `getPlayer` + Redis Pub/Sub으로 각 서버가 자기 플레이어에게 전달 | BungeeCord `Connect` | 없음 |
| AdvancedChat | 백엔드 서버별 Redis 집합 `advancedchat:online:{서버}` | – | 없음 |
| AdvancedTeleport | 백엔드 서버 기준 메모리 캐시 (`GlobalPlayerCache`, Pub/Sub) | BungeeCord `Connect` | 없음 |

- `Connect` 메시지는 **그 플레이어가 접속한 프록시**가 처리하므로 여러 프록시에서도 정상 동작한다.
- Redis를 함께 써도 키·채널 접두사가 모두 달라서(`advancedguilds`, `advancedchat`, `advancedteleport`, `vps`, TAB) 충돌하지 않는다.
- 중복 접속 방지(`KICK_OLD`)로 기존 접속이 끊겨도 백엔드에는 평범한 퇴장→입장으로 보이므로 문제없다.

### 11.2 운영 시 지켜야 할 것 (설정 문제)
- **모든 프록시의 `velocity.toml` `[servers]` 목록이 같아야 한다.** 프록시 B에 `survival`이 등록돼 있지 않으면 B로 들어온 플레이어는 길드 홈·크로스서버 `/tpa`로 이동할 수 없다.
- 각 플러그인의 `server.name` / `server-name` 값은 `velocity.toml`의 서버 이름과 같아야 한다 (지금도 필요한 조건).
- TAB `%online%`에서 CMI 버니시가 빠지려면 모든 백엔드에 **TAB-Bridge**가 필요하다.

### 11.3 범위 밖
- 버니시 관리자가 `/msg`, `/tpa` 자동완성에 노출되는 문제는 기존 플러그인 쪽 문제라 **이번 범위에서 제외**한다.
