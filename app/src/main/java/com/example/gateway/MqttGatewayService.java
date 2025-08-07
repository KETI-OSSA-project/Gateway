package com.example.gateway;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.example.broker.Message;
import com.example.broker.IMessageListener;
import com.example.broker.IMessagingService;

import org.eclipse.paho.client.mqttv3.*;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * MQTT Gateway Service that bridges internal broker and external MQTT brokers
 */
public class MqttGatewayService extends Service {
    private static final String TAG = "GATEWAY";

    // 브로커 서비스 연결 관련
    private IMessagingService messagingService;
    private ServiceConnection brokerConnection;

    private Map<String, MqttClient> externalConnections;

    @Override
    public void onCreate() {
        super.onCreate();
        externalConnections = new ConcurrentHashMap<>();
        Log.d(TAG, "MqttGatewayService created");

        // 포그라운드 서비스로 전환
        startForegroundServiceWithNotification();

        // 브로커 서비스에 바인드
        connectToBrokerService();
    }

    private void startForegroundServiceWithNotification() {
        String channelId = "gateway_service_channel";
        String channelName = "Gateway Service Channel";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        Notification notification = new NotificationCompat.Builder(this, channelId)
                .setContentTitle("Gateway 서비스 동작 중")
                .setContentText("메시지 브로커와 연결됨")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();
        startForeground(1, notification);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "MqttGatewayService started");
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        // 바인딩이 필요 없으면 null 반환
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "MqttGatewayService destroyed");

        // 브로커 서비스 연결 해제
        if (brokerConnection != null) {
            try {
                unbindService(brokerConnection);
            } catch (Exception e) {
                Log.e(TAG, "Error unbinding broker service", e);
            }
        }

        // 리소스 정리 로직 작성
    }

    private void connectToBrokerService() {
        Log.d(TAG, "🔗 Connecting to broker service...");

        brokerConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName componentName, IBinder service) {
                Log.i(TAG, "✅ Broker service connected");
                messagingService = IMessagingService.Stub.asInterface(service);

                // 게이트웨이 리스너 등록
                registerGatewayListener();
            }

            @Override
            public void onServiceDisconnected(ComponentName componentName) {
                Log.w(TAG, "❌ Broker service disconnected");
                messagingService = null;
            }
        };

        // 브로커 앱의 MessagingService에 바인드
        Intent brokerIntent = new Intent();
        brokerIntent.setComponent(new ComponentName("com.example.broker", "com.example.broker.MessagingService"));

        boolean bound = bindService(brokerIntent, brokerConnection, BIND_AUTO_CREATE);
        if (!bound) {
            Log.e(TAG, "Failed to bind to broker service");
        }
    }

    // 게이트웨이 리스너 등록
    private void registerGatewayListener() {
        if (messagingService != null) {
            try {
                IMessageListener gatewayListener = new IMessageListener.Stub() {
                    @Override
                    public void onMessage(Message message) {
                        Log.d(TAG, "🔔 게이트웨이에서 브로커 메시지 수신: " + message.getTopic());

                        // 외부 MQTT 브로커로 메시지 전달
                        forwardToExternal(message.getTopic(), message.getPayload());
                    }
                };

                messagingService.GatewayListener(gatewayListener);
                Log.i(TAG, "✅ Gateway listener registered to broker");

            } catch (RemoteException e) {
                Log.e(TAG, "Failed to register gateway listener", e);
            }
        }
    }

    // 외부 MQTT 브로커로 메시지 전달
    private void forwardToExternal(String topic, byte[] payload) {
        MqttClient client = externalConnections.get("main");
        if (client != null && client.isConnected()) {
            try {
                MqttMessage message = new MqttMessage(payload);
                message.setQos(0);
                client.publish(topic, message);
                Log.i(TAG, "📡 ✅ Forwarded to external: " + topic + " (" + payload.length + " bytes)");
            } catch (Exception e) {
                Log.e(TAG, "Failed to forward to external: " + topic, e);
            }
        } else {
            Log.w(TAG, "⚠️ External client not connected, cannot forward: " + topic);
        }
    }

    public void stop() {
        Log.i(TAG, "🛑 Stopping MQTT Gateway Service");

        // Disconnect from external brokers
        for (MqttClient client : externalConnections.values()) {
            try {
                if (client.isConnected()) {
                    client.disconnect();
                }
                client.close();
            } catch (Exception e) {
                Log.e(TAG, "Error disconnecting external client", e);
            }
        }
        externalConnections.clear();

        // 브로커 서비스 연결 해제는 onDestroy에서 처리됨
        Log.d(TAG, "Gateway service stopped");
    }
}
