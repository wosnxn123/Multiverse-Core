## Fork 修改说明

本 fork 为 Multiverse-Core 添加了 Folia/Canvas 兼容支持（版本 `5.7.2-canvas`）。

### 修改内容

- **FoliaCompat 工具类**：运行时检测 Folia，并将调度路由到对应的 Scheduler。
- **WorldUnloadCompat（世界卸载兼容）**：
  - Canvas：反射调用 `unloadWorldAsync`（Canvas 专属 API，异步卸载）。
  - Paper/Purpur：同步 `unloadWorld`。
  - 上游 Folia：不支持（Folia 未实现世界卸载 API，功能禁用并输出警告）。
- **WorldManager**：`createWorld` 路由到 `GlobalRegionScheduler`，`unloadWorld` 改用 `WorldUnloadCompat`。
- **WorldConfigNodes**：`difficulty`/`pvp`/`weather` 等设置变更路由到 global region。
- **调度器迁移**：迁移 6 个调度器文件到 Folia 兼容调度。
- **readSpawnFromWorld**：Folia 下跳过 spawn 安全校验，避免启动死锁。
- **plugin.yml**：`folia-supported: true`，版本 `5.7.2-canvas`。

### 兼容性（重要）

| 服务端 | 兼容性 | 说明 |
|--------|--------|------|
| Canvas 26.2 | ✅ 完全兼容 | 世界创建/加载/卸载均可用（Canvas 实现了 `createWorld` 和 `unloadWorldAsync`） |
| Paper/Purpur | ✅ 完全兼容 | 走同步 Bukkit API |
| 上游 Folia | ⚠️ 部分兼容 | 世界创建/卸载不可用（上游 Folia 未实现 `createWorld`/`unloadWorld` API，会抛 `UnsupportedOperationException`）；其他功能如传送、命令、配置可用 |

### 构建方式

```bash
./gradlew shadowJar
```

**Java 版本要求**：Java 21

---

<p align="center">
<img src="config/multiverse-banner.png" alt="Multiverse Logo">
</p>

[![Modrinth](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/modrinth_vector.svg)](https://modrinth.com/plugin/multiverse-core)
[![Hangar](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/hangar_vector.svg)](https://hangar.papermc.io/Multiverse/Multiverse-Core)
[![Bukkit](https://raw.githubusercontent.com/intergrav/devins-badges/refs/heads/v3/assets/cozy/available/bukkit_vector.svg)](https://dev.bukkit.org/projects/multiverse-core)
[![Spigot](https://raw.githubusercontent.com/intergrav/devins-badges/refs/heads/v3/assets/cozy/available/spigot_vector.svg)](https://www.spigotmc.org/resources/multiverse-core.390/)

[![Release](https://img.shields.io/github/v/release/multiverse/multiverse-core)](https://github.com/Multiverse/Multiverse-Core/releases/latest)
[![Pre-Release](https://img.shields.io/github/v/release/multiverse/multiverse-core?include_prereleases&label=Pre-release)](https://github.com/Multiverse/Multiverse-Core/releases)
[![Discord](https://img.shields.io/discord/325459248047980545?label=Discord&logo=discord)](https://discord.gg/NZtfKky)
[![Donate on Github Sponsor](https://img.shields.io/badge/Github%20Sponsor-Donate-pink?logo=githubsponsors)](https://github.com/sponsors/Multiverse)
[![Donate on Open Collective](https://img.shields.io/badge/Open%20Collective-Donate-blue?style=flat&logo=opencollective)](https://opencollective.com/multiverse-plugins)

# About

[Multiverse](https://modrinth.com/plugin/multiverse-core) was created at the dawn of Bukkit multiworld support. It has since then grown into a **complete world management solution!** Multiverse provides the easiest to use world management solution for your Minecraft server, big or small, and with great addons like [Portals](https://dev.bukkit.org/projects/multiverse-portals) and [NetherPortals](https://dev.bukkit.org/projects/multiverse-netherportals/), what's not to love!

Now it's time to create your very own Multiverse server, do check out our [Wiki](https://github.com/Multiverse/Multiverse-Core/wiki) and [Usage Guide](https://github.com/Multiverse/Multiverse-Core/wiki/Basics) to get started. Feel free to hop onto our [Discord](https://discord.gg/NZtfKky) if you have any question or just want to have a chat with us!

## Amazing sub-modules available:

* [Multiverse-NetherPortals](https://github.com/Multiverse/Multiverse-NetherPortals) -> Have separate nether and end worlds for each of your overworlds!
* [Multiverse-Portals](https://github.com/Multiverse/Multiverse-Portals) -> Make custom portals to go to any destination!
* [Multiverse-Inventories](https://github.com/Multiverse/Multiverse-Inventories) -> Have separated players stats and inventories per world or per group of worlds.
* [Multiverse-SignPortals](https://github.com/Multiverse/Multiverse-SignPortals) -> Signs as teleporters!

## Usage Guide

We have a cool new website hosting our Wiki: https://mvplugins.org

## Building
Simply build the source with Gradle:
```
./gradlew build
```

## Contributing

**Want to help improve Multiverse?** There are several ways you can support and contribute to the project.
* Take a look at our "Bug: Unconfirmed" issues, where you can find issues that need extra testing and investigation.
* Want others to love Multiverse too? You can join the [Multiverse Discord community](https://discord.gg/NZtfKky) and help others with issues and setup!
* A Multiverse guru? You can update our [Wiki](https://github.com/Multiverse/multiverse-web) with your latest tip, tricks and guides! The wiki open for all to edit and improve.
* Love coding? You could look at ["State: Open to PR"](https://github.com/Multiverse/Multiverse-Core/labels/State%3A%20Open%20to%20PR) and ["Resolution: Accepted"](https://github.com/Multiverse/Multiverse-Core/labels/Resolution%3A%20Accepted) issues. We're always happy to receive bug fixes and feature additions as [pull requests](https://www.freecodecamp.org/news/how-to-make-your-first-pull-request-on-github-3/).
* If you'd like to make a financial contribution to the project, do consider donating to our [Github Sponsors](https://github.com/sponsors/Multiverse) or [Open Collective](https://opencollective.com/multiverse-plugins)!

Additionally, we would like to give a big thanks to everyone that has supported Multiverse over the years, as well as those in the years to come. Thank you!

## License
Multiverse-Core is licensed under BSD-3-Clause License. Please see [LICENSE.md](LICENSE.md) for more info.
