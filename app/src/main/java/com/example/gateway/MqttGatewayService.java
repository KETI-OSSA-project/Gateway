package com.example.gateway;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.example.broker.Message;
import com.example.broker.IMessageListener;
import com.example.broker.IMessagingService;

import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

// 외부 MQTT 와 연결하는 코드
public class MqttGatewayService extends Service {
    private static final String TAG = "GATEWAY";
    private IMessagingService messagingService;
    private ServiceConnection brokerConnection;
    private boolean isConnected = false;
    private Handler reconnectHandler;
    private static final long RECONNECT_DELAY = 5000; // 5초 후 재연결 시도

    private Map<String, MqttClient> externalConnections;

    // 외부 MQTT 브로커 설정
    private static final String EXTERNAL_BROKER_URL = "tcp://192.168.0.6:1883"; // PC IP 주소로 변경
    private static final String CLIENT_ID = "AndroidGateway_" + System.currentTimeMillis();

    @Override
    public void onCreate() {
        super.onCreate();
        externalConnections = new ConcurrentHashMap<>();
        reconnectHandler = new Handler(Looper.getMainLooper());
        Log.d(TAG, "MqttGatewayService created");

        // 포그라운드 서비스로 전환
        startForegroundServiceWithNotification();

        // 외부 MQTT 브로커에 연결
        connectToExternalMqtt();

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

        // 재연결 스케줄 취소
        if (reconnectHandler != null) {
            reconnectHandler.removeCallbacksAndMessages(null);
        }

        // 브로커 서비스 연결 해제
        if (brokerConnection != null) {
            try {
                unbindService(brokerConnection);
            } catch (Exception e) {
                Log.e(TAG, "Error unbinding broker service", e);
            }
        }
    }

    // 내부 브로커에 게이트웨이 연결
    private void connectToBrokerService() {
        if (isConnected) {
            Log.d(TAG, "Already connected to broker service");
            return;
        }

        Log.d(TAG, "🔗 Connecting to broker service...");

        brokerConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName componentName, IBinder service) {
                Log.i(TAG, "✅ Broker service connected");
                messagingService = IMessagingService.Stub.asInterface(service);
                isConnected = true;

                // 게이트웨이 리스너 등록
                registerGatewayListener();
            }

            @Override
            public void onServiceDisconnected(ComponentName componentName) {
                Log.w(TAG, "❌ Broker service disconnected");
                messagingService = null;
                isConnected = false;

                // 자동 재연결 스케줄링
                scheduleReconnect();
            }
        };

        // 브로커 앱의 MessagingService에 바인드
        Intent brokerIntent = new Intent();
        brokerIntent.setComponent(new ComponentName("com.example.broker", "com.example.broker.MessagingService"));

        boolean bound = bindService(brokerIntent, brokerConnection, BIND_AUTO_CREATE);
        if (!bound) {
            Log.e(TAG, "Failed to bind to broker service");
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (reconnectHandler != null) {
            Log.d(TAG, "⏳ Scheduling reconnect in " + RECONNECT_DELAY + "ms");
            reconnectHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!isConnected) {
                        Log.d(TAG, "🔄 Attempting to reconnect to broker service...");
                        connectToBrokerService();
                    }
                }
            }, RECONNECT_DELAY);
        }
    }

    // 내부 브로커에서 메시지 수신
    private void registerGatewayListener() {
        if (messagingService != null) {
            try {
                IMessageListener gatewayListener = new IMessageListener.Stub() {
                    @Override
                    public void onMessage(Message message) {
                        // 디버깅: Message 객체 전체 정보 출력
                        Log.d(TAG, "📋 Message 전체 정보: " + message.toString());
                        Log.d(TAG, "📋 getTopic() 결과: " + message.getTopic());
                        Log.d(TAG, "📋 getTopic() 타입: " + (message.getTopic() != null ? message.getTopic().getClass().getName() : "null"));

                        // topic을 안전하게 문자열로 변환
                        String topicStr = String.valueOf(message.getTopic());

                        // "java.util.UUID" 문자열이 온 경우 기본 토픽으로 변환
                        if ("java.util.UUID".equals(topicStr)) {
                            topicStr = "sensor/data"; // 기본 토픽으로 변환
                            Log.w(TAG, "⚠️ 브로커에서 잘못된 토픽 수신, 기본 토픽으로 변환: " + topicStr);
                        }

                        Log.d(TAG, "🔔 게이트웨이에서 브로커 메시지 수신: " + topicStr);

                        // 외부 MQTT 브로커로 메시지 전달
                        forwardToExternal(topicStr, message.getPayload());
                    }
                };

                messagingService.GatewayListener(gatewayListener);
                Log.i(TAG, "✅ Gateway listener registered to broker");

            } catch (RemoteException e) {
                Log.e(TAG, "Failed to register gateway listener", e);
                isConnected = false;
                scheduleReconnect();
            }
        }
    }

    // 외부 MQTT 브로커가 연결됐다면, 메시지 전달
    private void forwardToExternal(String topic, byte[] payload) {
        MqttClient client = externalConnections.get("main");
        if (client != null && client.isConnected()) {
            try {
                // payload가 null인 경우 빈 배열로 처리
                if (payload == null) {
                    payload = new byte[0];
                    Log.w(TAG, "⚠️ Received null payload, using empty byte array instead");
                }

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

    // 외부 MQTT 브로커 연결 부분만 백그라운드 스레드에서 실행
    private void connectToExternalMqtt() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Log.d(TAG, "🌐 Connecting to external MQTT broker: " + EXTERNAL_BROKER_URL);

                    // 파일 대신 메모리 퍼시스턴스 사용
                    MqttClient client = new MqttClient(EXTERNAL_BROKER_URL, CLIENT_ID, new MemoryPersistence());

                    MqttConnectOptions options = new MqttConnectOptions();
                    options.setAutomaticReconnect(true);
                    options.setCleanSession(true);
                    options.setKeepAliveInterval(30);
                    options.setConnectionTimeout(10); // 타임아웃을 10초로 설정

                    client.setCallback(new MqttCallback() {
                        @Override
                        public void connectionLost(Throwable cause) {
                            Log.w(TAG, "❌ External MQTT connection lost", cause);
                            // 연결이 끊어지면 재연결 시도
                            scheduleExternalReconnect();
                        }

                        @Override
                        public void messageArrived(String topic, MqttMessage message) throws Exception {
                            Log.d(TAG, "📨 Received from external MQTT: " + topic);
                            // 외부에서 오는 메시지는 현재 구현에서 사용하지 않음 (단방향 전달)
                        }

                        @Override
                        public void deliveryComplete(IMqttDeliveryToken token) {
                            Log.d(TAG, "📤 Message delivered to external MQTT");
                        }
                    });

                    client.connect(options);
                    externalConnections.put("main", client);
                    Log.i(TAG, "✅ Connected to external MQTT broker");

                } catch (Exception e) {
                    Log.e(TAG, "❌ Failed to connect to external MQTT broker", e);
                    // 연결 실패시 재연결 시도
                    scheduleExternalReconnect();
                }
            }
        }).start();
    }

    // 외부 MQTT 브로커 재연결 스케줄링
    private void scheduleExternalReconnect() {
        if (reconnectHandler != null) {
            Log.d(TAG, "⏳ Scheduling external MQTT reconnect in " + RECONNECT_DELAY + "ms");
            reconnectHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    // 현재 연결이 없거나 끊어진 경우에만 재연결
                    MqttClient client = externalConnections.get("main");
                    if (client == null || !client.isConnected()) {
                        Log.d(TAG, "🔄 Attempting to reconnect to external MQTT broker...");
                        connectToExternalMqtt();
                    }
                }
            }, RECONNECT_DELAY);
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
