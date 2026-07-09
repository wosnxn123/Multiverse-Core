package com.folia.compat;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class WorldUnloadCompat {

    public static final Env ENV;

    private static final Method UNLOAD_ASYNC_WORLD;
    private static final Method UNLOAD_ASYNC_NAME;
    private static final Method WORLD_UNLOAD_RESULT_SUCCESS;
    private static final Class<?> WORLD_UNLOAD_RESULT_CLASS;

    static {
        Method ua = null, uan = null, wur = null;
        Class<?> wurc = null;
        Env env;
        if (FoliaCompat.FOLIA) {
            try {
                wurc = Class.forName("io.canvasmc.canvas.WorldUnloadResult");
                for (Method m : Server.class.getMethods()) {
                    if ("unloadWorldAsync".equals(m.getName())) {
                        Class<?>[] pts = m.getParameterTypes();
                        if (pts.length == 3 && pts[0] == World.class) {
                            ua = m;
                        } else if (pts.length == 3 && pts[0] == String.class) {
                            uan = m;
                        }
                    }
                }
                if (wurc != null && ua != null) {
                    for (Object c : wurc.getEnumConstants()) {
                        if ("SUCCESS".equals(c.toString())) {
                            wur = wurc.getMethod("valueOf", String.class);
                            break;
                        }
                    }
                    env = Env.CANVAS;
                } else {
                    env = Env.FOLIA_UNSUPPORTED;
                }
            } catch (Throwable t) {
                env = Env.FOLIA_UNSUPPORTED;
                wurc = null;
            }
        } else {
            env = Env.PAPER;
        }
        ENV = env;
        UNLOAD_ASYNC_WORLD = ua;
        UNLOAD_ASYNC_NAME = uan;
        WORLD_UNLOAD_RESULT_CLASS = wurc;
        WORLD_UNLOAD_RESULT_SUCCESS = wur;
    }

    private WorldUnloadCompat() {
    }

    public enum Env { PAPER, CANVAS, FOLIA_UNSUPPORTED }

    public static CompletableFuture<Boolean> unloadWorldAsync(Plugin plugin, World world, boolean save) {
        if (ENV == Env.PAPER) {
            try {
                boolean ok = Bukkit.unloadWorld(world, save);
                return CompletableFuture.completedFuture(ok);
            } catch (Throwable t) {
                return failedFuture(t);
            }
        }
        if (ENV == Env.FOLIA_UNSUPPORTED) {
            plugin.getLogger().severe("World unload is not supported on upstream Folia " +
                    "(Canvas or patched Folia required). Cannot unload world: " + world.getName());
            return failedFuture(new UnsupportedOperationException(
                    "World unload not implemented on this Folia build (use Canvas)."));
        }
        // Canvas: unloadWorldAsync must be called on the global tick thread.
        // The caller (UnloadCommand) dispatches to GlobalRegionScheduler on Folia,
        // so we should already be on the correct thread. Call directly.
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        try {
            Consumer<Object> callback = r -> {
                try {
                    String name = String.valueOf(r);
                    boolean success = "SUCCESS".equals(name);
                    if (!success) {
                        plugin.getLogger().warning("World unload of '" + world.getName()
                                + "' failed on Canvas: " + name);
                    }
                    result.complete(success);
                } catch (Throwable t) {
                    result.completeExceptionally(t);
                }
            };
            UNLOAD_ASYNC_WORLD.invoke(Bukkit.getServer(), world, save, callback);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.SEVERE, "Failed to invoke Canvas unloadWorldAsync for " + world.getName(), t);
            result.completeExceptionally(t);
        }
        return result;
    }

    public static boolean isUnloadSupported() {
        return ENV != Env.FOLIA_UNSUPPORTED;
    }

    public static boolean isCreateWorldSupported() {
        return ENV != Env.FOLIA_UNSUPPORTED;
    }

    private static <T> CompletableFuture<T> failedFuture(Throwable t) {
        CompletableFuture<T> f = new CompletableFuture<>();
        f.completeExceptionally(t);
        return f;
    }
}
