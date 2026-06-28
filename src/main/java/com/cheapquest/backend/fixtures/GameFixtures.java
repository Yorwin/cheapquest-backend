package com.cheapquest.backend.fixtures;

import java.util.List;

public final class GameFixtures {

    public static final HardcodedGame PORTAL = new HardcodedGame("Portal");
    public static final HardcodedGame HALF_LIFE_2 = new HardcodedGame("Half-Life 2");
    public static final HardcodedGame STARDEW_VALLEY = new HardcodedGame("Stardew Valley");

    private GameFixtures() {
    }

    public static List<HardcodedGame> all() {
        return List.of(PORTAL, HALF_LIFE_2, STARDEW_VALLEY);
    }
}
