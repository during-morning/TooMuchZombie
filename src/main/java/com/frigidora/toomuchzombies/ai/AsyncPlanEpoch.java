package com.frigidora.toomuchzombies.ai;

/**
 * Asynchronous planning version stamp.
 * Newer epochs must win against older async results.
 */
public record AsyncPlanEpoch(long tick, long sequence) {
    public long value() {
        return (tick << 20) ^ (sequence & 0xFFFFF);
    }
}
