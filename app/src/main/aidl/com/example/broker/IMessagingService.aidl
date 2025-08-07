// IMessagingService.aidl
package com.example.broker;

import com.example.broker.Message;
import com.example.broker.Filter;
import com.example.broker.IMessageListener;

interface IMessagingService {
    void publish(in Message message);
    boolean subscribe(in IMessageListener client, in Filter filter);
    boolean unsubscribe(in IMessageListener client, in Filter filter);
    boolean unsubscribeAll(in IMessageListener client);
    void GatewayListener(in IMessageListener listener); // 게이트웨이에서만 사용
}
