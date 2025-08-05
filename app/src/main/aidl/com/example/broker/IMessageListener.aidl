// IMessageListener.aidl
package com.example.broker;

import com.example.broker.Message;

interface IMessageListener {
    void onMessage(in Message message);
}
