package com.example.broker;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import androidx.annotation.NonNull;

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

    private final Bundle bundle;

    /**
     * @deprecated Use Filter.Builder instead
     */
    @Deprecated
    public Filter(String topic, String publisherId) {
        this.bundle = new Bundle();
        if (topic != null) {
            bundle.putString(Message.TOPIC, topic);
        }
        if (publisherId != null) {
            bundle.putString(Message.PUBLISHER_ID, publisherId);
        }
    }

    private Filter(Builder builder) {
        this.bundle = new Bundle();
        if (builder.topic != null) {
            bundle.putString(Message.TOPIC, builder.topic);
        }
        if (builder.publisherId != null) {
            bundle.putString(Message.PUBLISHER_ID, builder.publisherId);
        }
    }

    protected Filter(Parcel in) {
        bundle = in.readBundle(getClass().getClassLoader());
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeBundle(bundle);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public String getTopic() {
        return bundle.getString(Message.TOPIC);
    }

    public String getPublisherId() {
        return bundle.getString(Message.PUBLISHER_ID);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Filter filter = (Filter) o;
        return bundle.toString().equals(filter.bundle.toString());
    }

    @Override
    public int hashCode() {
        return bundle.toString().hashCode();
    }

    public static class Builder {
        private String topic;
        private String publisherId;

        /**
         * Public constructor.
         */
        public Builder() {
        }

        public Builder setTopic(@NonNull String topic) {
            this.topic = topic;
            return this;
        }

        public Builder setPublisherId(String publisherId) {
            this.publisherId = publisherId;
            return this;
        }

        public Filter build() {
            return new Filter(this);
        }
    }
}
