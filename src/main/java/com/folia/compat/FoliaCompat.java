package com.folia.compat;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * 调度器兼容工具: 同时支持 Paper/Spigot 与 Folia(及其分支如 Canvas).
 *
 * <p>核心思想:Folia 没有单一主线程,不能用 {@code BukkitScheduler.runTask}。
 * 所有"主线程"操作必须按操作对象路由到正确的调度器:
 * <ul>
 *   <li>玩家/实体相关(打开GUI、操作背包、掉落物品) -> EntityScheduler</li>
 *   <li>全局/无特定位置(广播、遍历世界) -> GlobalRegionScheduler</li>
 *   <li>异步任务 -> AsyncScheduler</li>
 *   <li>定时重复任务 -> 对应调度器的 runAtFixedRate</li>
 * </ul>
 *
 * <p>本工具通过反射访问 Folia 专属类({@code io.papermc.paper.threadedregions.scheduler.*}),
 * 因此编译产物可在纯 Paper 环境下加载而不会 NoClassDefFoundError;
 * 运行时检测到 Folia 才使用其调度器,否则回退到 BukkitScheduler。
 */
public final class FoliaCompat {

    /** 运行环境是否为 Folia(或 Canvas 等 Folia 分支). 在类加载时检测一次. */
    public static final boolean FOLIA;

    private static final Method SERVER_GET_GLOBAL;
    private static final Method SERVER_GET_ASYNC;
    private static final Method SERVER_GET_REGION;
    private static final Method ENTITY_GET_SCHEDULER;

    // GlobalRegionScheduler 方法
    private static final Method GLOBAL_RUN;
    private static final Method GLOBAL_RUN_DELAYED;
    private static final Method GLOBAL_RUN_AT_FIXED_RATE;
    private static final Method GLOBAL_EXECUTE;

    // AsyncScheduler 方法
    private static final Method ASYNC_RUN_NOW;
    private static final Method ASYNC_RUN_DELAYED;
    private static final Method ASYNC_RUN_AT_FIXED_RATE;

    // RegionScheduler.run(Location) 方法
    private static final Method REGION_RUN_LOCATION;
    private static final Method REGION_RUN_DELAYED_LOCATION;

    // EntityScheduler 方法
    private static final Method ENTITY_RUN;
    private static final Method ENTITY_RUN_DELAYED;
    private static final Method ENTITY_EXECUTE;

    // 线程检测方法 (Folia only)
    private static final Method BUKKIT_IS_GLOBAL_TICK_THREAD;
    private static final Method BUKKIT_IS_OWNED_BY_CURRENT_REGION;

    static {
        boolean folia;
        Method gGlobal = null, gAsync = null, gRegion = null, eSched = null;
        Method glRun = null, glRunDel = null, glRate = null, glExec = null;
        Method asRun = null, asRunDel = null, asRate = null;
        Method rgRunLoc = null, rgRunDelLoc = null;
        Method enRun = null, enRunDel = null, enExec = null;
        Method bkIsGlobal = null, bkIsOwned = null;
        try {
            // Folia 检测必须用「只存在于服务端实现」的类.
            //
            // 不要用 io.papermc.paper.threadedregions.scheduler.* —— 那一整个包属于 **paper-api**,
            // 普通 Paper 也自带(Paper 让插件能无条件编译 Folia 调度器代码), 用它检测会把
            // 普通 Paper 误判成 Folia. 那会导致本类所有 Folia 分支在 Paper 上被错误启用,
            // 其中 WorldUnloadCompat 会把 ENV 判成 FOLIA_UNSUPPORTED, 使世界卸载/删除/重生成
            // 在 Paper 上直接失效.
            //
            // RegionizedServer 属于服务端, 只有 Folia 及其分支(Canvas 等)才有.
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;
            Class<?> globalCls = Class.forName("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler");
            Class<?> asyncCls = Class.forName("io.papermc.paper.threadedregions.scheduler.AsyncScheduler");
            Class<?> regionCls = Class.forName("io.papermc.paper.threadedregions.scheduler.RegionScheduler");
            Class<?> entitySchedCls = Class.forName("io.papermc.paper.threadedregions.scheduler.EntityScheduler");
            Class<?> taskCls = Class.forName("io.papermc.paper.threadedregions.scheduler.ScheduledTask");

            // 线程检测: Bukkit.isGlobalTickThread() / Bukkit.isOwnedByCurrentRegion(Location)
            try {
                bkIsGlobal = Bukkit.class.getMethod("isGlobalTickThread");
            } catch (NoSuchMethodException ignored) {}
            try {
                bkIsOwned = Bukkit.class.getMethod("isOwnedByCurrentRegion", Location.class);
            } catch (NoSuchMethodException ignored) {}

            Method getServer = Bukkit.class.getMethod("getServer");
            Class<?> serverCls = getServer.getReturnType();
            gGlobal = serverCls.getMethod("getGlobalRegionScheduler");
            gAsync = serverCls.getMethod("getAsyncScheduler");
            gRegion = serverCls.getMethod("getRegionScheduler");
            eSched = Entity.class.getMethod("getScheduler");

            // GlobalRegionScheduler
            Class<?> consumerTask = Class.forName("java.util.function.Consumer");
            glRun = globalCls.getMethod("run", Plugin.class, consumerTask);
            glRunDel = globalCls.getMethod("runDelayed", Plugin.class, consumerTask, long.class);
            glRate = globalCls.getMethod("runAtFixedRate", Plugin.class, consumerTask, long.class, long.class);
            glExec = globalCls.getMethod("execute", Plugin.class, Runnable.class);

            // AsyncScheduler
            asRun = asyncCls.getMethod("runNow", Plugin.class, consumerTask);
            asRunDel = asyncCls.getMethod("runDelayed", Plugin.class, consumerTask, long.class, TimeUnit.class);
            asRate = asyncCls.getMethod("runAtFixedRate", Plugin.class, consumerTask, long.class, long.class, TimeUnit.class);

            // RegionScheduler.run(Plugin, Location, Consumer)
            rgRunLoc = regionCls.getMethod("run", Plugin.class, Location.class, consumerTask);
            rgRunDelLoc = regionCls.getMethod("runDelayed", Plugin.class, Location.class, consumerTask, long.class);

            // EntityScheduler
            enRun = entitySchedCls.getMethod("run", Plugin.class, consumerTask, Runnable.class);
            enRunDel = entitySchedCls.getMethod("runDelayed", Plugin.class, consumerTask, Runnable.class, long.class);
            enExec = entitySchedCls.getMethod("execute", Plugin.class, Runnable.class, Runnable.class, long.class);
        } catch (Throwable t) {
            folia = false;
        }
        FOLIA = folia;
        SERVER_GET_GLOBAL = gGlobal;
        SERVER_GET_ASYNC = gAsync;
        SERVER_GET_REGION = gRegion;
        ENTITY_GET_SCHEDULER = eSched;
        GLOBAL_RUN = glRun;
        GLOBAL_RUN_DELAYED = glRunDel;
        GLOBAL_RUN_AT_FIXED_RATE = glRate;
        GLOBAL_EXECUTE = glExec;
        ASYNC_RUN_NOW = asRun;
        ASYNC_RUN_DELAYED = asRunDel;
        ASYNC_RUN_AT_FIXED_RATE = asRate;
        REGION_RUN_LOCATION = rgRunLoc;
        REGION_RUN_DELAYED_LOCATION = rgRunDelLoc;
        ENTITY_RUN = enRun;
        ENTITY_RUN_DELAYED = enRunDel;
        ENTITY_EXECUTE = enExec;
        BUKKIT_IS_GLOBAL_TICK_THREAD = bkIsGlobal;
        BUKKIT_IS_OWNED_BY_CURRENT_REGION = bkIsOwned;
    }

    private FoliaCompat() {
    }

    private static Plugin cachedPlugin;

    /**
     * 获取 Multiverse-Core 插件实例 (缓存).
     * 用于 FoliaCompat 调度器注册 (需要 Plugin 参数).
     */
    public static Plugin getPlugin() {
        if (cachedPlugin == null) {
            cachedPlugin = Bukkit.getPluginManager().getPlugin("Multiverse-Core");
        }
        return cachedPlugin;
    }

    // ============================ 任务句柄 ============================

    /** 可取消的任务句柄,同时兼容 BukkitTask(Folia 上无统一类型时也用反射取消). */
    public static final class TaskHandle {
        private final Object bukkitTask; // org.bukkit.scheduler.BukkitTask (Paper) 或 null
        private final Object foliaTask;  // io.papermc.paper.threadedregions.scheduler.ScheduledTask

        TaskHandle(Object bukkitTask, Object foliaTask) {
            this.bukkitTask = bukkitTask;
            this.foliaTask = foliaTask;
        }

        public void cancel() {
            try {
                if (bukkitTask != null) {
                    bukkitTask.getClass().getMethod("cancel").invoke(bukkitTask);
                } else if (foliaTask != null) {
                    foliaTask.getClass().getMethod("cancel").invoke(foliaTask);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    // ============================ 同步主线程(全局区域) ============================
    // 用于:广播消息、遍历世界实体等无特定实体绑定的操作.

    /** 立即在主线程(全局区域)执行一次. */
    public static TaskHandle runTask(Plugin plugin, Runnable runnable) {
        if (!FOLIA) {
            return new TaskHandle(Bukkit.getScheduler().runTask(plugin, runnable), null);
        }
        // 保留返回的 ScheduledTask, 否则 TaskHandle#cancel 在 Folia 上是静默空操作.
        return new TaskHandle(null, invokeGlobal(plugin, runnable, GLOBAL_RUN, 0, 0, false, false));
    }

    /** 延迟 delayTicks 后在主线程(全局区域)执行一次. */
    public static TaskHandle runTaskLater(Plugin plugin, Runnable runnable, long delayTicks) {
        if (!FOLIA) {
            return new TaskHandle(Bukkit.getScheduler().runTaskLater(plugin, runnable, delayTicks), null);
        }
        return new TaskHandle(null, invokeGlobal(plugin, runnable, GLOBAL_RUN_DELAYED, delayTicks, 0, false, false));
    }

    /**
     * 定时重复在主线程(全局区域)执行.
     * Folia 用 GlobalRegionScheduler.runAtFixedRate, 注意其 period 单位为 tick.
     */
    public static TaskHandle runTaskTimer(Plugin plugin, Runnable runnable, long delayTicks, long periodTicks) {
        if (!FOLIA) {
            return new TaskHandle(Bukkit.getScheduler().runTaskTimer(plugin, runnable, delayTicks, periodTicks), null);
        }
        Object task = invokeGlobal(plugin, runnable, GLOBAL_RUN_AT_FIXED_RATE, delayTicks, periodTicks, true, false);
        return new TaskHandle(null, task);
    }

    /**
     * 在全局区域主线程执行一次. 语义别名,专门用于世界管理等全局操作(createWorld/unloadWorldAsync 等).
     * Folia/Canvas 用 GlobalRegionScheduler.execute; Paper 用主线程 runTask.
     *
     * <p><b>两个平台的时序不同, 调用前务必确认:</b>
     * <ul>
     *   <li>Paper: 若已在主线程, {@code runnable} <b>立即同步执行</b>, 返回后副作用已生效。</li>
     *   <li>Folia/Canvas: <b>总是入队</b>, 即使当前已在全局 tick 线程。返回后副作用<b>尚未</b>生效。</li>
     * </ul>
     *
     * <p>因此<b>不要</b>在调用之后立刻读回被修改的值 —— 在 Folia 上会读到修改前的状态。
     * 需要读回或需要顺序保证时, 把读取一并放进 {@code runnable} 内部。
     *
     * <p>这里刻意<b>不</b>加"已在全局 tick 线程就内联执行"的快捷路径: 本方法有 14 处调用点,
     * 分布在 world create/delete/regen/unload 路径上, 内联执行会带来重入风险
     * (参见历史提交 "fix: thread-aware Folia dispatch to avoid deadlock")。
     * 只有 {@link WorldUnloadCompat} 在确实需要时自行用 {@link #isGlobalTickThread()} 判断。
     */
    public static TaskHandle runGlobal(Plugin plugin, Runnable runnable) {
        if (!FOLIA) {
            // Paper: 若已在主线程直接执行,否则切主线程
            if (Bukkit.isPrimaryThread()) {
                runnable.run();
                return new TaskHandle(null, null);
            }
            return new TaskHandle(Bukkit.getScheduler().runTask(plugin, runnable), null);
        }
        // Folia/Canvas: GlobalRegionScheduler.execute
        try {
            Object server = Bukkit.getServer();
            Object global = SERVER_GET_GLOBAL.invoke(server);
            GLOBAL_EXECUTE.invoke(global, plugin, runnable);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
        return new TaskHandle(null, null);
    }

    // ============================ 世界变更调度 ============================

    /**
     * 执行一个「会创建/卸载世界」的操作。
     *
     * <p><b>为什么不能直接用 {@link #runGlobal}</b>: 在 Canvas 上, 世界卸载只有在该世界的所有
     * region 停止 tick 之后才算完成 —— 而 region 是靠自己 tick 时读到 unload ticket 才把自己标记为
     * 不可调度的 (Canvas {@code MinecraftServer#tickServer} 里读 {@code canvas$unloadTicket}),
     * 且 region tick 与 global tick 共用同一个 {@code TickRegionScheduler} 线程池。
     * Canvas 默认线程数是 {@code 核数/2}, 且 {@code <=4} 时取 <b>1</b>, 所以 8 核及以下只有一个
     * tick 线程。若卸载流程本身跑在 global tick 线程上并阻塞等待卸载结果, 那个唯一的 tick 线程就
     * 再也不会去 tick 那个 region, 卸载永远不会完成 —— <b>整个服务器永久冻结</b>。
     *
     * <p>所以: Folia/Canvas 上把整条流程放到异步线程 (在那里阻塞是合法的), 流程内部真正需要
     * global 线程的单步 (如 {@code WorldCreator#createWorld}) 再用 {@link #callGlobal} 单独跳过去。
     * Paper 上世界创建/卸载必须在主线程且是同步的, 所以仍走 {@link #runGlobal}。
     */
    public static TaskHandle runWorldMutation(Plugin plugin, Runnable runnable) {
        if (!FOLIA) {
            return runGlobal(plugin, runnable);
        }
        return runAsync(plugin, runnable);
    }

    /**
     * 在全局 tick 线程上执行 {@code supplier} 并<b>等待</b>其结果。
     *
     * <p>已在全局 tick 线程上时直接内联执行。否则派发过去并阻塞当前线程等待。
     *
     * <p><b>调用方必须不是 tick 线程</b>(全局或 region 都不行): 在 tick 线程上阻塞会饿死
     * {@code TickRegionScheduler} 线程池, 默认单线程配置下等于挂服。检测到这种调用会直接抛
     * {@link IllegalStateException} —— 明确报错远好于静默冻结。
     *
     * @param timeoutMs 等待上限, 超时抛 {@link IllegalStateException} 而不是无限期挂住
     */
    public static <T> T callGlobal(Plugin plugin, java.util.function.Supplier<T> supplier, long timeoutMs) {
        if (!FOLIA) {
            return supplier.get();
        }
        if (isGlobalTickThread()) {
            return supplier.get();
        }
        if (isAnyTickThread()) {
            throw new IllegalStateException("callGlobal must not be called from a region tick thread: "
                    + "blocking a tick thread can starve the tick scheduler and freeze the server. "
                    + "Run the enclosing operation via runWorldMutation/runAsync first.");
        }
        final java.util.concurrent.CompletableFuture<T> future = new java.util.concurrent.CompletableFuture<>();
        runGlobal(plugin, () -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            throw new IllegalStateException("Timed out after " + timeoutMs
                    + "ms waiting for a task on the global tick thread", te);
        } catch (java.util.concurrent.ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new RuntimeException(cause);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for a task on the global tick thread", ie);
        }
    }

    // ============================ 线程检测 ============================

    /**
     * 当前线程是否是<b>任何</b>一种 tick 线程(全局或 region)。
     *
     * <p>在 Folia/Canvas 上 {@code Bukkit.isPrimaryThread()} 的语义被重定义为「当前线程是不是
     * 一个 tick 线程」, 正好是我们需要的判据 —— 用来拒绝在 tick 线程上做阻塞等待。
     * Paper 上返回 false(Paper 没有这个危险, 主线程阻塞由调用方自己负责)。
     */
    public static boolean isAnyTickThread() {
        if (!FOLIA) return false;
        try {
            return Bukkit.isPrimaryThread();
        } catch (Throwable t) {
            // 拿不准时按「是 tick 线程」处理: 宁可报错也不要冒冻结服务器的风险
            return true;
        }
    }

    /**
     * 当前线程是否为全局 tick 线程 (Folia/Canvas).
     * Paper 始终返回 false (用 isPrimaryThread 判断).
     * 用于: createWorld/unloadWorld 等操作前判断是否已在正确线程, 避免死锁.
     */
    public static boolean isGlobalTickThread() {
        if (!FOLIA || BUKKIT_IS_GLOBAL_TICK_THREAD == null) return false;
        try {
            return (boolean) BUKKIT_IS_GLOBAL_TICK_THREAD.invoke(null);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 当前线程是否拥有 location 所在区域的 tick 权限 (Folia/Canvas).
     * Paper 始终返回 false.
     * 用于: 读取方块 (安全出生点检查) 前判断是否已在正确 region 线程, 避免死锁.
     */
    public static boolean isOwnedByCurrentRegion(Location location) {
        if (!FOLIA || BUKKIT_IS_OWNED_BY_CURRENT_REGION == null) return false;
        try {
            return (boolean) BUKKIT_IS_OWNED_BY_CURRENT_REGION.invoke(null, location);
        } catch (Throwable t) {
            return false;
        }
    }

    // ============================ 实体调度 ============================
    // 用于:打开/关闭背包、操作玩家背包、掉落物品、传送等绑定到某实体的操作.
    // 这些在 Folia 必须经 EntityScheduler, 否则会抛 IllegalStateException.

    /** 在该实体所在区域的主线程执行一次(实体退役时执行 retired). */
    public static TaskHandle runEntity(Plugin plugin, Entity entity, Runnable runnable, Runnable retired) {
        if (!FOLIA) {
            return new TaskHandle(Bukkit.getScheduler().runTask(plugin, runnable), null);
        }
        Object scheduler = getEntityScheduler(entity);
        invokeEntity(plugin, scheduler, runnable, retired, ENTITY_RUN, 0, false);
        return new TaskHandle(null, null);
    }

    /** 在该实体所在区域的主线程延迟 delayTicks 后执行一次. */
    public static TaskHandle runEntityLater(Plugin plugin, Entity entity, Runnable runnable, Runnable retired, long delayTicks) {
        if (!FOLIA) {
            return new TaskHandle(Bukkit.getScheduler().runTaskLater(plugin, runnable, delayTicks), null);
        }
        Object scheduler = getEntityScheduler(entity);
        invokeEntity(plugin, scheduler, runnable, retired, ENTITY_RUN_DELAYED, delayTicks, false);
        return new TaskHandle(null, null);
    }

    // ============================ 区域调度 ============================
    // 用于:操作某个位置所在区域(掉落方块、读区块等). 插件较少直接用.

    /** 在 location 所在区域主线程执行一次. */
    public static TaskHandle runRegion(Plugin plugin, Location location, Runnable runnable) {
        if (!FOLIA) {
            return new TaskHandle(Bukkit.getScheduler().runTask(plugin, runnable), null);
        }
        Object region = getServerRegion();
        invokeRegionLocation(region, plugin, location, runnable, REGION_RUN_LOCATION, 0);
        return new TaskHandle(null, null);
    }

    // ============================ 异步 ============================

    /** 立即在异步线程执行一次. */
    public static TaskHandle runAsync(Plugin plugin, Runnable runnable) {
        if (!FOLIA) {
            return new TaskHandle(Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable), null);
        }
        return new TaskHandle(null, invokeAsync(plugin, runnable, ASYNC_RUN_NOW, 0, 0, false));
    }

    /** 延迟 delayTicks(换算为 ms)后在异步线程执行一次. */
    public static TaskHandle runAsyncLater(Plugin plugin, Runnable runnable, long delayTicks) {
        if (!FOLIA) {
            return new TaskHandle(Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, runnable, delayTicks), null);
        }
        return new TaskHandle(null, invokeAsync(plugin, runnable, ASYNC_RUN_DELAYED, delayTicks, 0, false));
    }

    /** 异步定时重复, periodTicks 为周期. */
    public static TaskHandle runAsyncTimer(Plugin plugin, Runnable runnable, long delayTicks, long periodTicks) {
        if (!FOLIA) {
            return new TaskHandle(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, runnable, delayTicks, periodTicks), null);
        }
        Object task = invokeAsync(plugin, runnable, ASYNC_RUN_AT_FIXED_RATE, delayTicks, periodTicks, true);
        return new TaskHandle(null, task);
    }

    // ============================ 反射调用内部实现 ============================

    private static Consumer<Object> wrap(final Runnable r) {
        return task -> {
            try {
                r.run();
            } catch (Throwable t) {
                // 防止单次异常中断 Folia 的 Consumer. 走插件 logger 而不是 printStackTrace,
                // 否则在服务器日志里没有插件归属, 排查时定位不到来源.
                com.dumptruckman.minecraft.util.Logging.severe("Scheduled Multiverse task threw: %s", t);
                t.printStackTrace();
            }
        };
    }

    private static Object getGlobal() {
        try {
            Object server = Bukkit.getServer();
            return SERVER_GET_GLOBAL.invoke(server);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private static Object getServerRegion() {
        try {
            Object server = Bukkit.getServer();
            return SERVER_GET_REGION.invoke(server);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private static Object getEntityScheduler(Entity entity) {
        try {
            return ENTITY_GET_SCHEDULER.invoke(entity);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private static Object invokeGlobal(Plugin plugin, Runnable runnable, Method method, long delay, long period, boolean isRate, boolean isAsyncFallback) {
        try {
            Object global = getGlobal();
            Consumer<Object> c = wrap(runnable);
            if (isRate) {
                return method.invoke(global, plugin, c, delay, period);
            }
            if (method.getParameterTypes().length == 3) {
                return method.invoke(global, plugin, c, delay);
            }
            return method.invoke(global, plugin, c);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private static void invokeEntity(Plugin plugin, Object scheduler, Runnable runnable, Runnable retired, Method method, long delay, boolean isRate) {
        try {
            Consumer<Object> c = wrap(runnable);
            Runnable ret = retired != null ? retired : () -> {
            };
            // EntityScheduler.run 是 3 参数(Plugin, Consumer, Runnable);
            // EntityScheduler.runDelayed 是 4 参数(Plugin, Consumer, Runnable, long).
            // 按方法参数个数决定传不传 delay.
            int paramCount = method.getParameterCount();
            if (paramCount == 4) {
                method.invoke(scheduler, plugin, c, ret, delay);
            } else {
                method.invoke(scheduler, plugin, c, ret);
            }
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private static void invokeRegionLocation(Object region, Plugin plugin, Location location, Runnable runnable, Method method, long delay) {
        try {
            Consumer<Object> c = wrap(runnable);
            if (method.getParameterTypes().length == 4) {
                method.invoke(region, plugin, location, c, delay);
            } else {
                method.invoke(region, plugin, location, c);
            }
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private static Object invokeAsync(Plugin plugin, Runnable runnable, Method method, long delay, long period, boolean isRate) {
        try {
            Object server = Bukkit.getServer();
            Object async = SERVER_GET_ASYNC.invoke(server);
            Consumer<Object> c = wrap(runnable);
            if (isRate) {
                // runAtFixedRate(Plugin, Consumer, long delayMs, long periodMs, TimeUnit)
                return method.invoke(async, plugin, c, delay * 50L, period * 50L, TimeUnit.MILLISECONDS);
            }
            if (method.getParameterTypes().length == 4) {
                // runDelayed(Plugin, Consumer, long delay, TimeUnit)
                return method.invoke(async, plugin, c, delay * 50L, TimeUnit.MILLISECONDS);
            }
            // runNow
            return method.invoke(async, plugin, c);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }
}
