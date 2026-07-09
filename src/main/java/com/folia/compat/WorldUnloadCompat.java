package com.folia.compat;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * 世界卸载兼容工具: 同时支持 Paper/Spigot、Canvas(Folia 分支)与上游 Folia.
 *
 * <p>核心问题:
 * <ul>
 *   <li>Paper/Spigot: {@code Server.unloadWorld(World, boolean)} 同步可用,返回 boolean.</li>
 *   <li>Canvas: 同步 {@code unloadWorld} 仍抛 {@link UnsupportedOperationException};
 *       但提供了异步 {@code unloadWorldAsync(World, boolean, Consumer<WorldUnloadResult>)},
 *       必须在 global tick 线程调用.</li>
 *   <li>上游 Folia: 同步与异步卸载均未实现(stub 抛异常),功能不可用,只能禁用并警告.</li>
 * </ul>
 *
 * <p>本工具通过反射访问 Canvas 专属类({@code io.canvasmc.canvas.WorldUnloadResult}),
 * 因此编译产物可在纯 Paper 环境下加载;运行时检测到 Canvas 才使用其异步卸载 API.
 *
 * <p>统一返回 {@code CompletableFuture<Boolean>}:true=卸载成功,false=卸载失败(玩家在场/主世界/事件取消等).
 * 失败时记录具体原因到日志(Canvas 下信息比原同步 API 更丰富).
 */
public final class WorldUnloadCompat {

    /** 运行环境类型. */
    public enum Env { PAPER, CANVAS, FOLIA_UNSUPPORTED }

    public static final Env ENV;

    // Canvas 反射句柄
    private static final Method UNLOAD_ASYNC_WORLD;      // Server.unloadWorldAsync(World, boolean, Consumer)
    private static final Method UNLOAD_ASYNC_NAME;       // Server.unloadWorldAsync(String, boolean, Consumer)
    private static final Method WORLD_UNLOAD_RESULT_SUCCESS; // WorldUnloadResult 枚举的 SUCCESS 值(反射)
    private static final Class<?> WORLD_UNLOAD_RESULT_CLASS;

    static {
        Method ua = null, uan = null, wur = null;
        Class<?> wurc = null;
        Env env;
        if (FoliaCompat.FOLIA) {
            // Folia 系:检测是否有 Canvas 的 WorldUnloadResult 类
            try {
                wurc = Class.forName("io.canvasmc.canvas.WorldUnloadResult");
                // 找 Server 上的 unloadWorldAsync(World, boolean, Consumer)
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
                    // 反射拿 SUCCESS 枚举常量
                    for (Object c : wurc.getEnumConstants()) {
                        if ("SUCCESS".equals(c.toString())) {
                            wur = wurc.getMethod("valueOf", String.class); // 占位,实际用枚举常量比较
                            break;
                        }
                    }
                    env = Env.CANVAS;
                } else {
                    // Folia 但无 Canvas API -> 上游 Folia,未实现
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
        WORLD_UNLOAD_RESULT_SUCCESS = wur; // 仅作标记,实际比较用 toString()
    }

    private WorldUnloadCompat() {
    }

    /**
     * 卸载世界(异步语义).
     * <ul>
     *   <li>Paper: 同步调 {@code Bukkit.unloadWorld(world, save)},立即完成 future.</li>
     *   <li>Canvas: 路由到 GlobalRegionScheduler,反射调 {@code unloadWorldAsync},回调 complete future.</li>
     *   <li>上游 Folia: future 异常完成 {@link UnsupportedOperationException}.</li>
     * </ul>
     *
     * @param plugin 插件实例
     * @param world  要卸载的世界
     * @param save   是否保存
     * @return 异步结果: true=成功
     */
    public static CompletableFuture<Boolean> unloadWorldAsync(Plugin plugin, World world, boolean save) {
        if (ENV == Env.PAPER) {
            // Paper: 同步卸载(主线程上下文由调用方保证),立即返回
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
        // Canvas: 必须在 global tick 线程调 unloadWorldAsync
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        // Route through AsyncScheduler to GlobalRegionScheduler to avoid deadlock
        // when the calling thread is a region thread.
        FoliaCompat.runAsync(plugin, () -> FoliaCompat.runGlobal(plugin, () -> {
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
        }));
        return result;
    }

    /**
     * 是否支持世界卸载(上游 Folia 不支持).
     */
    public static boolean isUnloadSupported() {
        return ENV != Env.FOLIA_UNSUPPORTED;
    }

    /**
     * 是否支持世界创建.
     * <ul>
     *   <li>Paper: 同步 createWorld,支持.</li>
     *   <li>Canvas: createWorld 已实现(需 global/startup 线程),支持.</li>
     *   <li>上游 Folia: createWorld 仍 stub 抛异常,不支持.</li>
     * </ul>
     */
    public static boolean isCreateWorldSupported() {
        return ENV != Env.FOLIA_UNSUPPORTED;
    }

    private static <T> CompletableFuture<T> failedFuture(Throwable t) {
        CompletableFuture<T> f = new CompletableFuture<>();
        f.completeExceptionally(t);
        return f;
    }
}
