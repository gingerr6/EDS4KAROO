package com.valterc.ki2.data.device;

public enum SignalStrength {

    EXCELLENT("Excellent"),
    GOOD("Good"),
    WEAK("Weak"),
    VERY_WEAK("Very Weak");

    private final String label;

    SignalStrength(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
