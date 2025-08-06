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
    private IMessageListener messageListener;

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
            Log.d(TAG, "Connected to MessagingService"); // broker 연결됨
            if (connectionListener != null) {
                connectionListener.onConnected();
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

    public void publish(Message message) {
        if (messagingService != null) {
            try {
                messagingService.publish(message);
            } catch (RemoteException e) {
                Log.e(TAG, "Error publishing message", e);
            }
        }
    }

    public boolean subscribe(IMessageListener listener, Filter filter) {
        if (messagingService != null) {
            try {
                return messagingService.subscribe(listener, filter);
            } catch (RemoteException e) {
                Log.e(TAG, "Error subscribing", e);
                return false;
            }
        }
        return false;
    }

    public void disconnect() {
        if (isConnected && context != null) {
            context.unbindService(serviceConnection);
            isConnected = false;
        }
    }

    public void setMessageListener(IMessageListener listener) {
        this.messageListener = listener;
    }
}
