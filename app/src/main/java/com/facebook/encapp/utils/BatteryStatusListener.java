package com.facebook.encapp.utils;

public interface BatteryStatusListener {
    // Will be send below a configured value
    public void lowCapacity();

    // Will be send when the level have been recovered
    public void recovered();

    // If power is low and not charging or other error, abort
    public void shutdown();
}
