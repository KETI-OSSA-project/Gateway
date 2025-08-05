package com.example.gateway;

public class TopicMapping {
    private String topic;
    private String targetTopic;
    private boolean mapTopic;

    public TopicMapping(String topic, String targetTopic, boolean mapTopic) {
        this.topic = topic;
        this.targetTopic = targetTopic;
        this.mapTopic = mapTopic;
    }

    public String getTopic() { return topic; }
    public String getTargetTopic() { return targetTopic; }
    public boolean isMapTopic() { return mapTopic; }
}

