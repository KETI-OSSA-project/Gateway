package com.skt.tmap.traffic;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;
import android.os.RemoteException;
import com.example.broker.IMessageListener;
import com.example.broker.Message;
import com.example.broker.IMessagingService;

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
        // ...기존 AIDL 메서드 구현들...
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
    // ...existing code...
}
