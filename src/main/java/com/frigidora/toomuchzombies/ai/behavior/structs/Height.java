package com.frigidora.toomuchzombies.ai.behavior.structs;

public enum Height {
    UP(8), DOWN(8), NONE(6), VERTICAL(4);

    public final int limit;

    Height(int limit) {
        this.limit = limit;
    }
}
