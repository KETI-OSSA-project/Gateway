package com.example.gateway;

import android.content.Context;
import org.eclipse.paho.android.service.MqttAndroidClient;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import java.util.ArrayList;
import java.util.List;

// MqttConnection.java
public class MqttConnection {
    public enum Protocol { TCP, SSL }

    private String name;
    private Protocol protocol;
    private String serverUrl;
    private int port;
    private String username;
    private String password;
    private String caCertPath;
    private boolean connected;
    private List<TopicMapping> outgoingTopics = new ArrayList<>();
    private List<TopicMapping> incomingTopics = new ArrayList<>();
    private MqttAndroidClient mqttClient;

    public MqttConnection(String name, Protocol protocol, String serverUrl, int port) {
        this.name = name;
        this.protocol = protocol;
        this.serverUrl = serverUrl;
        this.port = port;
    }

    // Getters, setters, and topic management methods
    public String getName() { return name; }
    public Protocol getProtocol() { return protocol; }
    public String getServerUrl() { return serverUrl; }
    public int getPort() { return port; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getCaCertPath() { return caCertPath; }
    public void setCaCertPath(String caCertPath) { this.caCertPath = caCertPath; }
    public boolean isConnected() { return connected; }
    public void setConnected(boolean connected) { this.connected = connected; }
    public List<TopicMapping> getOutgoingTopics() { return outgoingTopics; }
    public List<TopicMapping> getIncomingTopics() { return incomingTopics; }

    public void connect(Context context, IMqttActionListener callback) {
        String uri = (protocol == Protocol.SSL ? "ssl://" : "tcp://") + serverUrl + ":" + port;
        mqttClient = new MqttAndroidClient(context, uri, name);
        MqttConnectOptions options = new MqttConnectOptions();
        if (username != null && !username.isEmpty()) options.setUserName(username);
        if (password != null && !password.isEmpty()) options.setPassword(password.toCharArray());
        // SSL 인증서 설정은 별도 구현 필요
        try {
            mqttClient.connect(options, null, callback);
        } catch (MqttException e) {
            e.printStackTrace();
        }
    }

    public void disconnect(IMqttActionListener callback) {
        if (mqttClient != null && mqttClient.isConnected()) {
            try {
                mqttClient.disconnect(null, callback);
            } catch (MqttException e) {
                e.printStackTrace();
            }
        }
    }

    public void subscribe(String topic, int qos, IMqttActionListener callback) {
        if (mqttClient != null && mqttClient.isConnected()) {
            try {
                mqttClient.subscribe(topic, qos, null, callback);
            } catch (MqttException e) {
                e.printStackTrace();
            }
        }
    }

    public void publish(String topic, String payload, int qos, boolean retained) {
        if (mqttClient != null && mqttClient.isConnected()) {
            try {
                MqttMessage message = new MqttMessage(payload.getBytes());
                message.setQos(qos);
                message.setRetained(retained);
                mqttClient.publish(topic, message);
            } catch (MqttException e) {
                e.printStackTrace();
            }
        }
    }

    public void setCallback(MqttCallback callback) {
        if (mqttClient != null) {
            mqttClient.setCallback(callback);
        }
    }
}
