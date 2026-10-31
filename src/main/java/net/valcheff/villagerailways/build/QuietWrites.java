package net.valcheff.villagerailways.build;

import net.minecraft.world.level.WorldGenLevel;
import net.valcheff.villagerailways.Platform;

/**
 * Marks the thread that is building lines into the world. Minecraft 1.21.1 has no write flag that keeps a block from
 * reacting to being placed or removed, so a rail laid into a loaded chunk would turn to join its neighbours and a
 * container it replaces would spill. The loader's hook into the chunk skips those reactions while this is on.
 */
public final class QuietWrites {
	/** Paper's write flag that keeps the placed block from reacting. The game itself does not use it. */
	static final int NO_PLACE = 1024;

	private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);

	private QuietWrites() {
	}

	public static boolean active() {
		return ACTIVE.get();
	}

	static void run(WorldGenLevel level, Runnable action) {
		boolean outer = ACTIVE.get();
		ACTIVE.set(true);
		try {
			Platform.get().quietly(level, action);
		} finally {
			ACTIVE.set(outer);
		}
	}
}
