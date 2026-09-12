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
 * 世界卸载兼容工具: 同时支持 Paper/Spigot、Canvas(Folia 分支)与上游 Folia(含 Mili 等直接基于 Folia 的分支).
 *
 * <p>核心问题:
 * <ul>
 *   <li>Paper/Spigot: {@code Server.unloadWorld(World, boolean)} 同步可用,返回 boolean.</li>
 *   <li>Canvas: 同步 {@code unloadWorld} 仍抛 {@link UnsupportedOperationException};
 *       但提供了异步 {@code unloadWorldAsync(World, boolean, Consumer<WorldUnloadResult>)},
 *       必须在 global tick 线程调用.</li>
 *   <li>上游 Folia(含 Mili): {@code CraftServer#unloadWorld}/{@code createWorld} 均为
 *       {@code if (true) throw new UnsupportedOperationException()} 的 stub(2026-08 Folia HEAD
 *       与 Mili 26.2 df5b131 均如此),运行时世界创建/加载/卸载全部不可用,只能禁用并警告.</li>
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
                    // Folia 但无 Canvas API -> 上游 Folia/Mili: CraftServer stub 直接 throw,未实现
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
            plugin.getLogger().severe("World unload is not supported on this Folia platform " +
                    "(upstream Folia and forks like Mili do not implement runtime world unload; " +
                    "only Canvas/Petiole provide Server#unloadWorldAsync). Cannot unload world: " + world.getName());
            return failedFuture(new UnsupportedOperationException(
                    "runtime world unload is not supported on this Folia platform (upstream Folia/Mili " +
                    "do not implement it); worlds can only go away by stopping the server"));
        }
        // Canvas: 必须在 global tick 线程调 unloadWorldAsync
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        Runnable unloadTask = () -> {
            try {
                Consumer<Object> callback = r -> {
                    try {
                        // r 是 io.canvasmc.canvas.WorldUnloadResult 枚举常量; 用 name() 而不是
                        // toString(), 避免将来上游给枚举加 toString 覆写时静默失配.
                        String name = (r instanceof Enum<?> e) ? e.name() : String.valueOf(r);
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
        };
        // 已在 global 线程: 直接调用, 避免 runGlobal + .get() 死锁
        // 不在 global 线程 (region/async): runGlobal 路由, 调用方 .get() 阻塞等待
        if (FoliaCompat.isGlobalTickThread()) {
            unloadTask.run();
        } else {
            FoliaCompat.runGlobal(plugin, unloadTask);
        }
        return result;
    }

    /**
     * 是否支持世界卸载(上游 Folia/Mili 不支持; Canvas 经 unloadWorldAsync 支持).
     */
    public static boolean isUnloadSupported() {
        return ENV != Env.FOLIA_UNSUPPORTED;
    }

    /**
     * 是否支持世界创建/运行时加载.
     * <ul>
     *   <li>Paper: 同步 createWorld,支持.</li>
     *   <li>Canvas: createWorld 已实现(需 global/startup 线程),支持.</li>
     *   <li>上游 Folia/Mili: {@code CraftServer#createWorld} 为无消息的
     *       {@code UnsupportedOperationException} stub,不支持;世界只能由服务器启动时加载
     *       (server.properties level-name / bukkit.yml worlds).</li>
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
