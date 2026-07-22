# 내장 MQTT 브로커 전환 — 작업 진행 문서

> 외부 PC의 Mosquitto 브로커를 게이트웨이 앱 안으로 흡수하는 작업.
> 최종 업데이트: **2026-07-22**
> 대상 파일: `app/src/main/java/com/example/gateway/MqttGatewayService.java`, `app/build.gradle.kts`

---

## 1. 목적

외부 브로커는 uplink·downlink **양방향 모두 반드시 거치는 길목**이다. 별도 PC 프로세스로 둘 이유가 없으니, 게이트웨이 앱이 그 브로커 역할을 직접 하게 만든다.

- **PC 의존 제거** — PC의 Mosquitto를 꺼도 폰 단독으로 동작
- **불필요한 중간 단계 생략** — 외부 클라이언트가 PC 대신 폰 IP에 직접 접속

---

## 2. 구조 변경 (Before → After)

### Before (기존)
```
[폰 안 앱들] --AIDL--> [내장 broker] --게이트웨이 브리지(Paho x2)--> [PC Mosquitto] <--MQTT-- [외부 클라이언트]
   com.example.broker                                              (별도 PC 프로세스)
```

### After (목표)
```
[폰 안 앱들] --AIDL--> [내장 broker] --게이트웨이 브리지(Paho x2)--> [게이트웨이 내장 Moquette] <--MQTT-- [외부 클라이언트]
   com.example.broker                                              (구 PC Mosquitto 자리, 같은 앱 프로세스)
```

**핵심**: PC Mosquitto가 사라지고 그 자리에 앱 내장 Moquette가 들어옴. 외부 클라이언트는 이제 폰 IP:1883으로 붙는다.

---

## 3. 확정된 설계 결정

| 항목 | 결정 | 이유 |
|---|---|---|
| 내장 broker(`com.example.broker`) | **그대로 유지** | 폰 안 pub/sub 앱들이 AIDL로 붙는 부분은 손대지 않음 (최소 병합) |
| 브로커 라이브러리 | **Moquette 0.15** | Android에서 도는 사실상 유일한 현실적 임베디드 MQTT 브로커 |
| 브리지 구현 방식 | **접근 A (루프백)** | 기존 Paho 클라이언트 2개를 그대로 두고 접속 주소만 localhost로 교체 → 검증된 릴레이 로직(토픽 매핑·루프 방지·재접속) 전부 재사용, diff 최소 |
| 저장소 | **in-memory** | Moquette 기본 H2 파일 저장소는 Android에서 죽음 → 메모리 전용으로 회피 |

> 대안이었던 **접근 B(Moquette 내부 API `internalPublish`+`InterceptHandler`로 재작성)** 는 개념적으로 깔끔하나, 검증된 브리지 코드를 다시 써야 해서 이번 목적엔 불필요하다고 판단.

---

## 4. 작업 로그 (게이트별 검증)

가장 위험한 것부터 싼 순서로 게이트를 두고 진행.

### ✅ 게이트 1 — 빌드가 되나
- `app/build.gradle.kts`에 Moquette 의존성 추가.
- **문제**: Moquette가 끌어오는 Netty jar 여러 개가 같은 `META-INF/INDEX.LIST`를 가져 리소스 병합 충돌.
- **해결**: `packaging { resources { excludes ... } }`로 중복 META-INF 제외.
- **결과**: `assembleDebug` **성공**. minSdk 24에서 multidex 없이 DEX까지 통과. Android에 없는 API 참조 문제 없음.

### ✅ 게이트 2 — 폰에서 브로커가 뜨나
- `MqttGatewayService.onCreate`에서 Moquette를 `0.0.0.0:1883`, 익명 허용, in-memory로 기동.
- **주의점**: 설정 상수는 `IConfig`가 아니라 **`io.moquette.BrokerConstants`** 에 있음.
- **결과**: 실기기(Android 14, MediaTek)에서 로그 확인:
  ```
  ✅ 내장 브로커 기동 성공: 0.0.0.0:1883 리슨
  ```
- **무해한 노이즈 2개**:
  - `log4j:WARN No appenders` — Moquette 로깅 설정 없음, 동작 무관.
  - `avc denied somaxconn` — SELinux가 proc 파일 읽기 차단, Netty가 기본 backlog로 폴백.

### ✅ 게이트 A-배선 — 게이트웨이가 내장 브로커를 쓰나
- Paho 클라이언트 2개의 접속 대상을 `tcp://127.0.0.1:1883`으로 교체.
- **결과**: 실기기 로그 — 브리지 전체가 내장 브로커에 물림:
  ```
  Uplink 연결 성공                              (이전엔 옛 외부 URL로 EHOSTUNREACH)
  ✅ Downlink connected: downlink/app/+/in
  ✅ Downlink subscribed (reconnect=false): downlink/app/+/in
  ```

### ✅ 게이트 3 — 외부 클라이언트 LAN 왕복
- 환경: 폰 wlan `192.168.1.26`, 외부 PC(구 Mosquitto 호스트) `192.168.1.30` — 같은 서브넷.
- TCP 도달성 `192.168.1.26:1883` → **True** (AP 격리 없음).
- 왕복: 외부 PC에서 `mosquitto_pub` → 폰 브로커 → 외부 `mosquitto_sub` 수신 성공:
  ```
  ✅ 수신됨: test/hello gate3-ok
  ```
- **PC Mosquitto 없이 폰이 외부 클라이언트를 직접 서빙함을 확인.**
- **주의 — 발견된 실제 동작**: Moquette 0.15는 mosquitto가 자동 생성한 **빈 client ID를 거부**한다(`Connection Refused: identifier rejected`). 명시적 client ID(`-i`)를 주면 정상 동작. Mosquitto 브로커는 빈 ID를 허용했으므로, **기존 외부 클라이언트가 빈 client ID로 접속하던 경우 호환성 문제 발생 가능.** → 필요 시 Moquette 설정으로 zero-byte client ID 허용 여부 검토.

---

## 5. 코드 변경 내역

### `app/build.gradle.kts`
```kotlin
// dependencies
implementation("io.moquette:moquette-broker:0.15")

// android { }
packaging {
    resources {
        excludes += setOf(
            "META-INF/INDEX.LIST",
            "META-INF/io.netty.versions.properties",
            "META-INF/DEPENDENCIES",
            "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA"
        )
    }
}
```

### `MqttGatewayService.java`
| 위치 | 변경 |
|---|---|
| import | `io.moquette.BrokerConstants`, `io.moquette.broker.Server`, `IConfig`, `MemoryConfig`, `java.util.Properties` 추가 |
| 상수 | `LOCAL_BROKER_URL = "tcp://127.0.0.1:1883"` 추가 |
| 필드 | `private Server embeddedBroker;` 추가 |
| `onCreate()` | `startEmbeddedBroker()` 호출 (config 로드·브리지 연결 이전) |
| `startEmbeddedBroker()` | 신규 메서드 — Moquette를 in-memory로 기동, 성공/예외 로그 |
| `connectExternalForUplink/Downlink` | `new MqttClient(...)` 대상을 `cfg.externalBrokerUrl` → `LOCAL_BROKER_URL`로 교체 |
| `onDestroy()` | `embeddedBroker.stopServer()` 정리 추가 |

```java
private void startEmbeddedBroker() {
    try {
        Properties props = new Properties();
        props.setProperty(BrokerConstants.HOST_PROPERTY_NAME, "0.0.0.0");
        props.setProperty(BrokerConstants.PORT_PROPERTY_NAME, "1883");
        props.setProperty(BrokerConstants.ALLOW_ANONYMOUS_PROPERTY_NAME, "true");
        props.setProperty(BrokerConstants.PERSISTENT_STORE_PROPERTY_NAME, "");  // H2 파일 저장 끄고 in-memory
        IConfig cfg = new MemoryConfig(props);

        Server broker = new Server();
        broker.startServer(cfg);
        embeddedBroker = broker;
        Log.i(TAG, "✅ 내장 브로커 기동 성공: 0.0.0.0:1883 리슨");
    } catch (Throwable t) {
        Log.e(TAG, "❌ 내장 브로커 기동 실패", t);
    }
}
```

---

## 6. 현재 상태 요약

- ✅ Moquette가 이 프로젝트에서 **빌드·기동·연결까지 실제 동작** 확인.
- ✅ 게이트웨이 브리지가 내장 브로커에 물려 uplink/downlink 연결·구독 성공.
- ✅ **외부 클라이언트 LAN 왕복 성공** — PC Mosquitto 없이 폰이 외부 클라이언트 서빙 확인.
- ✅ **브리지 양방향 릴레이 실증** — 업링크(폰 내부 앱 → 외부 구독자), 다운링크(외부 발행 → 내부 broker) 모두 실기기에서 확인.
- ➡️ **핵심 목표(PC 브로커 제거)가 실기기에서 end-to-end 검증됨.** 남은 건 정리/호환성 항목뿐.

> 📎 검증 증거(로그·스크린샷·재현법): [`docs/evidence/README.md`](evidence/README.md)

---

## 7. 남은 일 / 정리 필요 항목

- [x] ~~**게이트 3**: 외부 PC MQTT 클라이언트로 폰 IP:1883 왕복 검증.~~ ✅ 완료
- [ ] **빈 client ID 호환성**: Moquette 0.15가 빈 client ID를 거부함. 기존 외부 클라이언트가 빈 ID로 접속했다면 깨짐. Moquette 설정으로 허용할지, 클라이언트에 ID를 강제할지 결정.
- [ ] **MainActivity 브로커 URL 필드 정리**: 이제 항상 localhost로 붙으므로 이 입력 필드는 의미 없음. 숨기거나 라벨을 "내장 브로커 포트" 등으로 바꿀지 결정 필요. (`📋 설정 로드` 로그에 옛 URL이 찍히는 것도 이 필드 때문 — 동작에는 영향 없음)
- [ ] **재접속 로직 재검토**: localhost 대상이라 사실상 안 끊김. 기존 exponential backoff 재접속 로직은 무해하게 남아있으나 단순화 여지 있음. (지금은 그대로 둠)
- [ ] **포트/권한**: 1883 하드코딩. 필요 시 설정화. 방화벽·핫스팟 AP 격리 환경에서 외부 접근 가능한지 확인.

---

## 8. 알아둘 것 (하드 학습)

- Moquette 설정 상수는 `io.moquette.BrokerConstants`에 있음 (`IConfig` 아님).
- Moquette 기본 H2 파일 저장소는 Android에서 문제 → `PERSISTENT_STORE_PROPERTY_NAME=""`로 in-memory 강제.
- Netty jar들의 `META-INF` 중복은 packaging exclude로 해결.
- 이 저장소 빌드에는 Android Studio 번들 JDK 사용: `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr` (시스템 기본 Java가 1.7이라 gradlew가 거부함).
