// IMessagingService.aidl
package com.example.broker;

import com.example.broker.Message;
import com.example.broker.Filter;
import com.example.broker.IMessageListener;

/**
 * MQTT Broker Service Interface
 * 다른 앱들이 브로커 서비스에 접근하기 위한 인터페이스
 */
interface IMessagingService {
    /**
     * 메시지를 발행합니다.
     */
    void publish(in Message message);

    /**
     * Filter 조건에 맞는 메시지를 구독합니다.
     */
    boolean subscribe(in IMessageListener client, in Filter filter);

    /**
     * 특정 Filter에 대한 구독을 해제합니다.
     */
    boolean unsubscribe(in IMessageListener client, in Filter filter);

    /**
     * 클라이언트의 모든 구독을 해제합니다.
     */
    boolean unsubscribeAll(in IMessageListener client);
}
