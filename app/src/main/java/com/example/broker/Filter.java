package com.example.broker;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Filter for message subscription with topic-based filtering.
 */
public class Filter implements Parcelable {

    public static final Creator<Filter> CREATOR = new Creator<Filter>() {
        @Override
        public Filter createFromParcel(Parcel in) {
            return new Filter(in);
        }

        @Override
        public Filter[] newArray(int size) {
            return new Filter[size];
        }
    };

    private Bundle criteria;


    private Filter(Builder builder) {
        criteria = new Bundle();
        if (builder.topic != null) {
            criteria.putString(Message.TOPIC, builder.topic);
        }
        if (builder.publisherId != null) {
            criteria.putString(Message.PUBLISHER_ID, builder.publisherId);
        }
    }

    protected Filter(Parcel in) {
        criteria = in.readBundle();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Filter filter = (Filter) o;

        return criteria != null ? criteria.toString().equals(filter.criteria.toString()) : filter.criteria == null;
    }

    @Override
    public int hashCode() {
        return criteria != null ? criteria.toString().hashCode() : 0;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeBundle(criteria);
    }

    @Override
    public String toString() {
        return "Filter{" + "criteria=" + criteria + '}';
    }

    public static class Builder {
        private String topic;
        private String publisherId;

        public Builder setTopic(String topic) {
            this.topic = topic;
            return this;
        }

        public Filter build() {
            return new Filter(this);
        }
    }
}
