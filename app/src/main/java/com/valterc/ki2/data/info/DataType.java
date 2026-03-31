package com.valterc.ki2.data.info;

public enum DataType {

    UNKNOWN(0, false),
    BATTERY(1, false),
    SHIFTING(2, false),
    SWITCH(3, true),
    KEY(4, true),
    MANUFACTURER_INFO(5, false),
    SIGNAL(6, true),
    BATTERY_RD(7, false),
    SHIFTER_L_VOLTAGE(8, false),
    SHIFTER_R_VOLTAGE(9, false),
    OTHER(255, false);

    public static DataType fromFlag(int flag) {
        for (DataType dataType : values()) {
            if (dataType.flag == flag) {
                return dataType;
            }
        }

        return UNKNOWN;
    }

    private final int flag;
    private final boolean event;

    DataType(int flag, boolean event) {
        this.flag = flag;
        this.event = event;
    }

    public int getFlag() {
        return flag;
    }

    public boolean isEvent() {
        return event;
    }
}
