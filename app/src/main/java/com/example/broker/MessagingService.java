package com.example.broker;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;
import android.os.RemoteException;
import com.example.broker.IMessageListener;
import com.example.broker.Message;
import com.example.broker.IMessagingService;
import com.example.broker.Filter;

public class MessagingService extends Service {
    private static final String TAG = "MessagingService";

    // gateway 리스너 등록 변수 (중복 제거, AIDL용만 남김)
    private IMessageListener gatewayListener;

    private final IMessagingService.Stub messagingServiceBinder = new IMessagingService.Stub() {
        @Override
        public void GatewayListener(IMessageListener listener) {
            gatewayListener = listener;
            Log.i(TAG, "GatewayListener 등록됨: " + (listener != null ? listener.asBinder() : "null"));
        }
        @Override
        public void publish(Message message) throws RemoteException {
            // 현재는 사용하지 않음 - 브로커에서 오는 메시지만 전달
            Log.w(TAG, "publish 호출됨 - 현재 구현에서는 사용하지 않음");
        }

        @Override
        public boolean subscribe(IMessageListener client, Filter filter) throws RemoteException {
            // 현재는 사용하지 않음 - GatewayListener 방식 사용
            Log.w(TAG, "subscribe 호출됨 - 현재 구현에서는 GatewayListener 사용");
            return false;
        }

        @Override
        public boolean unsubscribe(IMessageListener client, Filter filter) throws RemoteException {
            // 현재는 사용하지 않음
            Log.w(TAG, "unsubscribe 호출됨 - 현재 구현에서는 사용하지 않음");
            return false;
        }

        @Override
        public boolean unsubscribeAll(IMessageListener client) throws RemoteException {
            // 현재는 사용하지 않음
            Log.w(TAG, "unsubscribeAll 호출됨 - 현재 구현에서는 사용하지 않음");
            return false;
        }
        //
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service Created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service Started");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service Destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "🔗 클라이언트가 브로커에 연결됨");
        return messagingServiceBinder;
    }

    // AIDL GatewayListener 관련 메서드들
    // 브로커에서 메시지를 받아서 등록된 게이트웨이 리스너에게 전달
    public void forwardMessageToGateway(Message message) {
        if (gatewayListener != null) {
            try {
                gatewayListener.onMessage(message);
                Log.d(TAG, "게이트웨이로 메시지 전달: " + message.getTopic());
            } catch (RemoteException e) {
                Log.e(TAG, "게이트웨이 메시지 전달 실패", e);
            }
        } else {
            Log.w(TAG, "등록된 게이트웨이 리스너가 없음");
        }
    }
}
