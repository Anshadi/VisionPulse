package com.visionpulse.idgen;

public interface IdGeneratorStrategy {
    long generateId();
    String getStrategyName();
    boolean isAvailable();
}
