package com.example.broker;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

public class MessagingManager {
    private static final String TAG = "MessagingManager";
    private IMessagingService messagingService;
    private Context context;
    private boolean isConnected = false;
    private MessagingServiceConnectionListener connectionListener;
    private IMessageListener gatewayListener;

    public interface MessagingServiceConnectionListener {
        void onConnected();
        void onDisconnected();
    }

    public static MessagingManager getConnectedInstance(Context context, MessagingServiceConnectionListener listener) {
        MessagingManager manager = new MessagingManager();
        manager.context = context;
        manager.connectionListener = listener;
        manager.connectToService();
        return manager;
    }

    private void connectToService() {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName("com.example.broker", "com.example.broker.MessagingService"));
        boolean bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        if (!bound) {
            Log.e(TAG, "Failed to bind to MessagingService");
        }
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            messagingService = IMessagingService.Stub.asInterface(service);
            isConnected = true;
            Log.d(TAG, "Connected to MessagingService");
            if (connectionListener != null) {
                connectionListener.onConnected();
            }
            // 게이트웨이 리스너 등록
            if (messagingService != null && gatewayListener != null) {
                try {
                    messagingService.GatewayListener(gatewayListener);
                    Log.d(TAG, "GatewayListener registered to MessagingService");
                } catch (RemoteException e) {
                    Log.e(TAG, "Error registering GatewayListener", e);
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            messagingService = null;
            isConnected = false;
            Log.d(TAG, "Disconnected from MessagingService");
            if (connectionListener != null) {
                connectionListener.onDisconnected();
            }
        }
    };

    // 게이트웨이 리스너만 등록하는 기능만 남김
    public void setGatewayListener(IMessageListener listener) {
        Log.d(TAG, "setGatewayListener 호출됨, listener: " + listener);
        this.gatewayListener = listener;
        if (messagingService != null && listener != null) {
            try {
                Log.d(TAG, "messagingService.GatewayListener(listener) 호출 시도");
                // AIDL에 정의된 함수 이름과 정확히 일치하도록 호출 (대문자 G)
                messagingService.GatewayListener(listener);
                Log.d(TAG, "GatewayListener registered to MessagingService (setGatewayListener 호출됨)");
            } catch (RemoteException e) {
                Log.e(TAG, "Error registering GatewayListener", e);
            }
        } else {
            Log.d(TAG, "GatewayListener 등록 대기 중: messagingService(" + messagingService + ") 또는 listener(" + listener + ")가 null");
        }
    }

    public void disconnect() {
        if (isConnected && context != null) {
            context.unbindService(serviceConnection);
            isConnected = false;
        }
    }
}
