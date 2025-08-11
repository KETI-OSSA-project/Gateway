// IMessagingService.aidl
package com.example.broker;

import com.example.broker.Message;
import com.example.broker.IMessageListener;
import com.example.broker.Filter;

/** MQTT Broker Service Interface */
interface IMessagingService {
    /** 메시지를 발행합니다. */
    void publish(in Message message);

    /** Filter 조건에 맞는 메시지를 구독합니다. */
    boolean subscribe(in IMessageListener client, in Filter filter);

    /** 특정 Filter에 대한 구독을 해제합니다. */
    boolean unsubscribe(in IMessageListener client, in Filter filter);

    /** 클라이언트의 모든 구독을 해제합니다. */
    boolean unsubscribeAll(in IMessageListener client);

    /**
     * 게이트웨이 메시지 리스너를 등록합니다.
     * subscribe 없이 직접 메시지를 받을 수 있습니다.
     */
    void GatewayListener(in IMessageListener listener);
}
