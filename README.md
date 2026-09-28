# VelocityPlayerSync

같은 Redis에 연결된 여러 Velocity 프록시가 접속자를 공유합니다.
A 프록시에 30명, B 프록시에 33명이 있으면 두 프록시 모두 서버 목록에 **63명**으로 보입니다.

- 서버 목록(멀티플레이 화면) 인원수와 이름 목록을 네트워크 전체 기준으로 표시 (MiniMOTD `<online_players>`에도 그대로 반영)
- 같은 계정이 두 프록시에 동시에 접속하지 못하게 막기 (기존 접속 끊기 / 새 접속 거부)
- CMI 등 버니시 중인 플레이어는 인원수에서 제외
- MiniPlaceholders 플레이스홀더, `/vps` 관리 명령어
- 프록시가 비정상 종료돼도 15초 안에 그 프록시 인원이 목록에서 빠짐
- TAB 탭리스트에 남는 유령 플레이어 정리 (아래 [TAB 유령 정리](#tab-유령-정리))

> TAB 탭리스트의 63명 표시는 TAB에 내장된 Redis 동기화가 맡습니다. 아래 [TAB 설정](#tab-설정)을 참고하세요.

## 요구 사항

| 대상 | 버전 |
|---|---|
| Velocity | 4.2.x (Java 25) |
| Redis | 5.0 이상 (모든 프록시가 같은 Redis 사용) |
| 백엔드 (Bridge) | Paper 1.21.x ~ 26.x |
| MiniPlaceholders | 3.x (선택) |

## 설치

1. 빌드: `./gradlew build` → `build/libs/` 에 두 파일이 생깁니다.
   - `VelocityPlayerSync-<버전>.jar` → **모든 Velocity** 의 `plugins/`
   - `VelocityPlayerSync-Bridge-<버전>.jar` → **모든 백엔드 서버** 의 `plugins/` (버니시 제외 기능에 필요)
2. 한 번 실행하면 `plugins/velocityplayersync/config.yml` 이 생깁니다.
3. 프록시마다 **`proxy-id` 를 다르게**, `redis.url` 은 **모두 같게** 설정합니다.
4. 재시작하거나 `/vps reload`.

## 설정 (`config.yml`)

```yaml
proxy-id: "proxy-1"                    # 프록시마다 다르게
redis:
  url: "redis://localhost:6379/0"      # 비밀번호: redis://:비밀번호@호스트:6379/0
  key-prefix: "vps"
sync:
  heartbeat-interval-seconds: 5
  proxy-timeout-seconds: 15            # 이 시간 동안 소식이 없는 프록시는 꺼진 것으로 봄
  full-resync-interval-seconds: 10
ping:
  enabled: true
  max-players: { mode: KEEP }          # KEEP | FIXED | ONLINE_PLUS
  sample: { enabled: true, max-entries: 12 }
  on-redis-failure: LOCAL_ONLY         # LOCAL_ONLY | LAST_KNOWN
duplicate-login:
  enabled: true
  action: KICK_OLD                     # KICK_OLD | DENY_NEW
tab-ghost-cleanup:
  enabled: true
  channel: "TAB_4_custom"              # "TAB_4_" + TAB 의 proxy-support.channel-name-suffix
```

전체 항목과 설명, 메시지(MiniMessage)는 생성된 파일의 주석을 참고하세요.

## 명령어 · 권한

| 명령어 | 설명 |
|---|---|
| `/vps status` | 연결된 프록시, 프록시별 인원, 하트비트, Redis 응답 시간 |
| `/vps list [서버]` | 전체(또는 서버별) 접속자 |
| `/vps find <닉네임>` | 플레이어가 있는 프록시와 서버 |
| `/vps reload` | 설정 다시 불러오기 |

| 권한 | 설명 |
|---|---|
| `velocityplayersync.admin` | `/vps` 사용 |
| `velocityplayersync.see-vanished` | `/vps list`, `/vps find` 에서 버니시 플레이어 보기 (`[V]` 표시) |

## MiniPlaceholders

MiniPlaceholders 가 설치돼 있으면 자동으로 등록됩니다. 버니시 플레이어는 세지 않습니다.

| 플레이스홀더 | 값 |
|---|---|
| `<vps_online>` | 전체 인원 |
| `<vps_online_local>` | 이 프록시 인원 |
| `<vps_online_server:lobby>` | 특정 서버 인원 (모든 프록시 합산) |
| `<vps_online_proxy:proxy-1>` | 특정 프록시 인원 |
| `<vps_proxy_count>` | 살아 있는 프록시 수 |
| `<vps_proxy_id>` | 이 프록시의 proxy-id |

확인: 콘솔에서 `vminiplaceholders parse --null <vps_online>`

## 다른 플러그인과 함께 쓰기

### TAB 설정

각 프록시의 TAB `config.yml` 에서 내장 Redis 동기화를 켜고 **VelocityPlayerSync 와 같은 Redis** 를 지정합니다.
`%online%` 과 탭리스트(global-playerlist)가 모든 프록시 기준이 됩니다.

```yaml
proxy-support:
  enabled: true
  type: REDIS
  redis:
    url: 'redis://localhost:6379/0'
```

CMI 버니시를 TAB `%online%` 에서도 빼려면 모든 백엔드에 **TAB-Bridge** 가 필요합니다.

### TAB 유령 정리

TAB 6.2 의 Redis 동기화에는 두 가지 빈틈이 있습니다 (실제 서버로 확인).

- 프록시가 **비정상 종료**되면 그 프록시의 플레이어가 다른 프록시의 탭리스트와 `%online%` 에 계속 남습니다.
- 프록시를 **정상 종료**해도 TAB 이 퇴장 알림 일부를 보내지 못하고 꺼져, `%online%` 에 몇 명이 남습니다.

`tab-ghost-cleanup` 을 켜 두면(기본값) VelocityPlayerSync 가 TAB 형식의 퇴장 알림을 대신 보냅니다.

- 플레이어가 나갈 때와 프록시가 꺼질 때: 그 프록시가 한 번 더 알림 (TAB 은 중복 알림을 무시)
- 프록시가 비정상 종료됐을 때: 하트비트가 끊긴 것을 알아챈 프록시 한 곳이 알림 (다른 프록시로 다시 들어온 플레이어는 제외)
- Redis 가 재시작되는 등 연결이 불안정했던 직후 30초 동안은 살아 있는 플레이어를 지우지 않도록 비정상 종료 정리를 하지 않습니다.

TAB 의 내부 메시지 형식을 쓰므로, TAB 을 크게 업데이트한 뒤 TAB 로그에 `Failed to decode proxy message` 가 보이면 `enabled: false` 로 끄세요.
TAB 의 `proxy-support.channel-name-suffix` 를 바꿨다면 `channel` 도 맞춰 주세요.

### MiniMOTD

추가 설정 없이 `<online_players>` 가 전체 인원이 됩니다.
단, MiniMOTD 의 `target-servers` 를 쓰면 MiniMOTD 가 이 프록시 인원만 직접 세므로 **비워 두세요.**

### 모든 프록시 공통

- `velocity.toml` 의 `[servers]` 목록은 모든 프록시에서 같아야 합니다. (다른 서버로 이동하는 플러그인이 플레이어가 접속한 프록시를 거치기 때문)

## 동작 방식

```
[Paper + CMI + Bridge] ──플러그인 메시지(버니시)──▶ [Velocity A + VPS] ──┐
                                                                      ├── Redis
[Paper + CMI + Bridge] ──플러그인 메시지(버니시)──▶ [Velocity B + VPS] ──┘
```

- 각 프록시는 자기 접속자를 `vps:players:<proxy-id>` 에 기록하고, 입장·이동·퇴장·버니시를 `vps:events` 로 알립니다.
- 다른 프록시 정보는 메모리에 들고 있어, 서버 목록 핑에는 Redis 를 거치지 않습니다.
- 10초마다 전체 목록을 다시 읽어 놓친 메시지를 보정합니다.
- 하트비트가 끊기면 키가 만료되어 그 프록시의 접속자가 자동으로 빠집니다.
- 같은 `proxy-id` 를 쓰는 프록시가 둘 살아 있으면 뒤에 켜진 쪽은 공유를 멈추고 로그를 남깁니다.

| Redis 키 | 내용 |
|---|---|
| `vps:proxies` | 등록된 proxy-id 목록 |
| `vps:proxy:<id>` | 프록시 상태 (인스턴스, 시작 시각, 버전, 하트비트) |
| `vps:players:<id>` | 접속자 (uuid → JSON) |
| `vps:lock:<uuid>` | 중복 접속 판정 잠금 (5초) |
| `vps:vanish-last:<uuid>` | 마지막 버니시 상태 (30일, 재접속 직후 노출 방지) |
| `vps:tab-quit:<uuid>` | TAB 유령 정리를 한 프록시만 하도록 거는 잠금 (60초) |
| `vps:events` | Pub/Sub 채널 |

## 개발

```bash
./gradlew build          # 두 jar 빌드 + 테스트 35개 (build/libs)
./gradlew :velocity:test # 내장 Redis 로 프록시 여러 대를 띄워 동기화 테스트
./gradlew :velocity:runVelocity
```

모듈: `velocity/` (Velocity 플러그인), `bridge/` (Paper 보조 플러그인)
