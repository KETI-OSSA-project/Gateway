# Gateway
---

# Azena Messaging Gateway

**개요**
Gateway 모듈은 **내부 Broker**와 **외부 MQTT Broker(Mosquitto)** 사이의 메시지 중계 역할을 담당합니다.
Uplink/Downlink 토픽 규칙을 기반으로, 내부 메시지를 외부로 전달하거나 외부 메시지를 내부로 전달하는 기능을 제공합니다.

---

## 📂 디렉토리 구조

```
gateway/
├─ app/
│  └─ java/com/example/
│     ├─ broker/
│     │  ├─ Filter.java            # 메시지 토픽 패턴 필터링
│     │  └─ Message.java           # 메시지 데이터 구조 정의
│     │
│     └─ gateway/
│        ├─ MainActivity.java      # 안드로이드 진입점 (UI 및 서비스 제어)
│        └─ MqttGatewayService.java # 게이트웨이 핵심 로직 (중계/패턴 적용)
└─ README.md
```

---

## ⚙️ 주요 기능

* **내부 Broker ↔ 외부 MQTT Broker 연동**
* **Uplink (내부 → 외부)** 및 **Downlink (외부 → 내부)** 토픽 라우팅
* 토픽 패턴 필터링 (`Filter.java`)
* 메시지 데이터 구조화 (`Message.java`)
* 안드로이드 환경에서 실행 가능한 Gateway Service (`MqttGatewayService.java`)

---

## 🚀 실행 방법

### \[1] 환경 준비

* Android Studio (Koala, 2024.1.1 Patch 1 이상)
* Android 12 이하 (SDK/API 32 이하)
* Mosquitto MQTT Broker 설치

### \[2] 빌드 및 실행

1. Android Studio로 `gateway` 프로젝트 열기
2. `MainActivity` 실행 → 게이트웨이 UI 실행
3. 외부 MQTT Broker 주소, Uplink/Downlink 패턴 설정
4. `Start` 버튼 클릭 → Gateway Service 시작

---

## 📌 사용 예시

### 게이트웨이 설정

* **외부 MQTT Broker URL** : `tcp://192.168.0.10:1883`
* **Uplink Topic Pattern** : `uplink/#`
* **Downlink Topic Pattern** : `downlink/#`

### 실행 흐름

1. 내부 Publisher → `uplink/test` 토픽 발행
2. Gateway → 외부 MQTT Broker로 메시지 전달
3. 외부 Subscriber → 메시지 수신 확인

---
