package net.valcheff.villagerailways.build;

/**
 * Marks the thread that is building lines into the world. Before 1.21.5 Minecraft has no write flag that keeps a block
 * from reacting to being placed or removed, so a rail laid into a loaded chunk would turn to join its neighbours and a
 * container it replaces would spill. The loader's hook into the chunk skips those reactions while this is on. Paper,
 * which has no such hook, takes its own write flag that keeps a placed block from reacting; a removed one still does.
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

	static void run(Runnable action) {
		boolean outer = ACTIVE.get();
		ACTIVE.set(true);
		try {
			action.run();
		} finally {
			ACTIVE.set(outer);
		}
	}
}
