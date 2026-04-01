package com.valterc.ki2.data.device;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

public class BatteryInfo implements Parcelable {

    private int value;

    public static final Parcelable.Creator<BatteryInfo> CREATOR = new Parcelable.Creator<BatteryInfo>() {
        public BatteryInfo createFromParcel(Parcel in) {
            return new BatteryInfo(in);
        }

        public BatteryInfo[] newArray(int size) {
            return new BatteryInfo[size];
        }
    };

    private BatteryInfo(Parcel in) {
        readFromParcel(in);
    }

    public BatteryInfo(int value) {
        this.value = value;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(value);
    }

    public void readFromParcel(Parcel in) {
        value = in.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public int getValue() {
        return value;
    }

    /**
     * Convert a raw Wheeltop battery voltage value to percentage (0-100).
     * Lookup table from the EDS protocol specification.
     *
     * @param rawValue Raw voltage × 100 (e.g. 810 = 8.10V).
     * @return Battery percentage (0-100).
     */
    public static int toPercentage(int rawValue) {
        if (rawValue >= 820) return 100;
        if (rawValue >= 816) return 95;
        if (rawValue >= 812) return 90;
        if (rawValue >= 808) return 85;
        if (rawValue >= 800) return 80;
        if (rawValue >= 792) return 75;
        if (rawValue >= 780) return 70;
        if (rawValue >= 776) return 65;
        if (rawValue >= 770) return 60;
        if (rawValue >= 766) return 55;
        if (rawValue >= 760) return 50;
        if (rawValue >= 750) return 40;
        if (rawValue >= 746) return 35;
        if (rawValue >= 740) return 30;
        if (rawValue >= 736) return 25;
        if (rawValue >= 730) return 20;
        if (rawValue >= 710) return 10;
        if (rawValue >= 690) return 5;
        return 0;
    }

    /**
     * Get the battery percentage for this battery info.
     */
    public int getPercentage() {
        return toPercentage(value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        BatteryInfo that = (BatteryInfo) o;

        return value == that.value;
    }

    @Override
    public int hashCode() {
        return value;
    }

    @NonNull
    @Override
    public String toString() {
        return "BatteryInfo{" +
                "value=" + value +
                '}';
    }
}
