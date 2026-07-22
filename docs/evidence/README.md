# 내장 브로커 전환 — 실동작 검증 증거

검증 일시: **2026-07-22** / 실기기: Android 14 (무선 adb `192.168.1.26`)
외부 클라이언트 = 같은 LAN의 PC(`192.168.1.30`, `mosquitto_pub/sub`)

전 구간이 **한 게이트웨이 세션(pid 3718)** 안에서 검증됨. 원본 로그: [`gateway_full_log.txt`](gateway_full_log.txt)

---

## 검증한 것

| # | 경로 | 방법 | 증거 |
|---|---|---|---|
| 1 | 브로커 기동 | 게이트웨이 서비스 시작 | 로그 `✅ 내장 브로커 기동 성공: 0.0.0.0:1883 리슨` |
| 2 | 브리지 연결 | 서비스 시작 시 자동 | 로그 `Uplink 연결 성공`, `✅ Downlink connected` |
| 3 | 외부 접속·왕복 | 외부 PC `mosquitto_pub/sub` (test/#) | 수신 `test/hello gate3-ok` (TCP 도달성 True) |
| 4 | **다운링크** (외부→내부) | 외부 PC가 `downlink/app/2/in` 발행 | 로그 `외부에서 발신: downlink/app/2/in` |
| 5 | **업링크** (내부→외부) | 폰 publisher 앱이 `uplink/app/1/out` 발행 | 로그 `외부로 발신: uplink/app/1/out` + 외부 구독자 수신 `uplink/app/1/out uplink-relay-evidence` |

**결론**: PC Mosquitto 없이, 폰 내장 브로커를 통해 내부 앱 ↔ 외부 클라이언트가 **양방향으로 실제 통신**함.

---

## 원본 로그 핵심 라인 (`gateway_full_log.txt`)

```
07-22 06:32:41.082  I GATEWAY : ✅ 내장 브로커 기동 성공: 0.0.0.0:1883 리슨
07-22 06:32:41.475  I GATEWAY : Uplink 연결 성공
07-22 06:32:41.796  I GATEWAY : ✅ Downlink connected: downlink/app/+/in
07-22 06:33:03.554  I GATEWAY : 외부에서 발신: downlink/app/2/in     ← 다운링크 릴레이
07-22 06:40:34.559  I GATEWAY : 외부로 발신: uplink/app/1/out        ← 업링크 릴레이
```

## 외부 클라이언트(PC) 측 출력

```
# 게이트 3 (브로커 왕복)
$ mosquitto_pub -h 192.168.1.26 -i extPub -t test/hello -m gate3-ok
$ mosquitto_sub -h 192.168.1.26 -i extSub -t "test/#" -v
  test/hello gate3-ok

# 업링크 (폰 내부 앱 발행 → 외부 수신)
$ mosquitto_sub -h 192.168.1.26 -i extSubUp -t "uplink/#" -v
  uplink/app/1/out uplink-relay-evidence
```

## 스크린샷 — 반대편 입장 증거 (핵심)

| 방향 | 파일 | 내용 |
|---|---|---|
| **다운링크** (밖→안) | [`subscriber_downlink.png`](subscriber_downlink.png) | 폰 subscriber 앱이 **외부 PC가 보낸** 메시지를 수신: `📥 [downlink/app/5/in] 수신: from-outside-PC` (실기기 스크린샷) |
| **업링크** (안→밖) | [`external_uplink_receive.png`](external_uplink_receive.png) | 외부 PC 구독자가 **폰 내부 publisher 앱이 보낸** 메시지를 수신: `uplink/app/1/out hello-from-phone-inside` |

> ⚠️ `external_uplink_receive.png`는 PC 터미널이라 raw 스크린샷 대신 **실제 `mosquitto_sub` 출력값을 그대로 이미지로 렌더링**한 것. 원본 수신 문자열은 `uplink/app/1/out hello-from-phone-inside`.

## 스크린샷 — 발신 측 (참고)

- [`publisher_app.png`](publisher_app.png) / [`publisher_uplink.png`](publisher_uplink.png)
  — 폰 publisher 앱: 토픽 `uplink/app/1/out`, 상태 `✅ 브로커 연결 완료!` + `🚀 메시지 발행 [uplink/app/1/out]`

---

## 재현 방법 요약

1. `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr` 설정 후 `gradlew installDebug`
2. 게이트웨이 앱 실행 → START → 로그에서 `내장 브로커 기동 성공` 확인
3. 다운링크: 외부 PC `mosquitto_pub -h <폰IP> -i extPub -t downlink/app/2/in -m ...` → 로그 `외부에서 발신`
4. 업링크: 외부 PC `mosquitto_sub -h <폰IP> -i extSubUp -t "uplink/#" -v` 대기 → 폰 publisher 앱에서 `uplink/app/1/out` 발행 → 외부 수신

> 주의: Moquette 0.15는 **빈 client ID 거부** → mosquitto 명령에 `-i <id>` 필수.
