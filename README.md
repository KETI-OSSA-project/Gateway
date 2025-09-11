# Gateway
---

# Azena Messaging Gateway
<p align="center">
  <img src="https://github.com/user-attachments/assets/d15b5f1f-2052-4b2e-b914-270107ee7431" alt="<프로젝트명> Banner" width="70%" />
</p>

<p align="center">
  <b>Validate and integrate MQTT messaging services and brokers with a unified tool.</b>
</p>

<div align="center">

  <!-- 윗줄: Android 관련 2개 -->
  <img src="https://img.shields.io/badge/Android_Studio-Koala-3DDC84?logo=androidstudio&logoColor=white" />
  <img src="https://img.shields.io/badge/Android-12_(API_32)-3DDC84?logo=android&logoColor=white" />
  <!-- 아랫줄: 나머지 3개 -->
  <img src="https://img.shields.io/badge/Java-11-007396?logo=openjdk&logoColor=white" />
  <img src="https://img.shields.io/badge/Mosquitto-2.0.22-3C5280?logo=eclipseide&logoColor=white" />

</div>

## 개요

Gateway 모듈은 **내부 Broker**와 **외부 MQTT Broker(Mosquitto)** 사이의 메시지 중계 역할을 담당합니다.
Uplink/Downlink 토픽 규칙을 기반으로, 내부 메시지를 외부로 전달하거나 외부 메시지를 내부로 전달하는 기능을 제공합니다.

---

## Demo
### 내부 ↔ 내부 통신
<p align="center">
  <img src="https://github.com/user-attachments/assets/5fda5904-9d37-4324-8dae-87ddd36bc08a" width="40%" />
</p>

### 내부 ↔ 외부 통신
<table>
<tr>
    <td><img src="https://github.com/user-attachments/assets/3372b3b6-22df-4a4c-b832-d55950039a4c" alt="uplink-broker"></td>
    <td><img src="https://github.com/user-attachments/assets/e029e60a-968f-4e0d-9743-4cc5d612e071" alt="downlink-subscriber"></td>
</tr>
</table>
<p align="center">
  <a href="https://drive.google.com/file/d/1_TukQ0CwD708QUjqD8ZCfM2t8XZdoEBp/view?usp=drive_link">
    <b>📽️ MQTT Demo</b>
  </a>
</p>


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

* **외부 MQTT Broker URL** : `tcp://외부 브로커 url`
* **Uplink Topic Pattern** : `uplink/#`
* **Downlink Topic Pattern** : `downlink/#`
-> 토픽 패턴은 사용자 설정이 가능합니다.

### 실행 흐름

1. 내부 Publisher → `uplink/test` 토픽 발행
2. Gateway → 외부 MQTT Broker로 메시지 전달
3. 외부 Subscriber → 메시지 수신 확인

---
## Acknowledgement
**개발 및 유지 관리 기관** : 한국전자기술연구원(KETI)

※ 본 연구는 2025년도 정부(과학기술정보통신부)의 재원으로 정보통신기획평가원의 지원을 받아 수행된 연구임(No. RS-2024-00394190, 온-디바이스 자율보호가 내재화된 개방형 영상보안플랫폼 기술 개발).
