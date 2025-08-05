package com.example.gateway;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// MQTT 연결 관리 클래스
public class MqttGatewayManager {
    private final List<MqttConnection> connections = new ArrayList<>();

    public void addConnection(MqttConnection connection) {
        connections.add(connection);
    }

    public void removeConnection(MqttConnection connection) {
        connections.remove(connection);
    }

    public List<MqttConnection> getConnections() {
        return connections;
    }

    public MqttConnection getConnectionByName(String name) {
        for (MqttConnection conn : connections) {
            if (conn.getName().equals(name)) {
                return conn;
            }
        }
        return null;
    }
}

