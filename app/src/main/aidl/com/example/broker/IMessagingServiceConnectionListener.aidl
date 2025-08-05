// IMessagingServiceConnectionListener.aidl
package com.example.broker;

import com.example.broker.Message;

interface IMessagingServiceConnectionListener {
    void onConnected();

    void onDisconnected();
}
