<div align="center">

# CleanLetv

**乐视电视的 CleanYourTV 深度适配版**

按键重映射 · 每日一言 · Le 键全局应用库

[![基于 CleanYourTV](https://img.shields.io/badge/基于-CleanYourTV-111111?style=flat-square&logo=github)](https://github.com/Gathin23/cleanyourtv)
[![协议 AGPL-3.0](https://img.shields.io/badge/协议-AGPL--3.0-blue?style=flat-square)](./LICENSE)
[![上游 MIT](https://img.shields.io/badge/上游源码-MIT-green?style=flat-square)](./launcher/LICENSE)
[![实测机型](https://img.shields.io/badge/实测-乐视%20X4--65S%20(Android%206.0)-ff6b35?style=flat-square)](#这是什么)

</div>

---

## 这是什么

[CleanYourTV](https://github.com/Gathin23/cleanyourtv) 是一个把 Android TV / Google TV 的广告桌面换成极简 Apple TV 风格启动器的项目，MIT 协议，214 个机型可用。

**CleanLetv 是它的乐视电视适配版。** 乐视的固件有四层机制会跟桌面打架——遥控器按键在框架层就被吃掉、桌面进程会被系统主动强杀、原厂桌面杀不掉、常驻系统窗口盖住设置面板——这些在通用版里没有解。

**实测机型：乐视 X4-65S（DemeterUHD，Android 6.0 / SDK 23，MStar 芯片，1920×1080）**

**实测机型：乐视 X4-65S（DemeterUHD，Android 6.0 / SDK 23，MStar 芯片，1920×1080）**

---

## 三个特色

### 1️⃣ 按键重映射：让遥控器每个键都听话

乐视固件的 `PhoneWindowManager` 在框架层按 keycode 拦截遥控器按键，应用层和无障碍层**根本收不到**。本项目改写系统的 keylayout 文件，把这些键从固件的拦截网里"换"出来，交还给桌面。

![按键重映射](docs/images/tile-menu.png)

**完整按键映射表**（红外 `Vendor_3697_Product_0001.kl` + 蓝牙 `Vendor_4c65_Product_3d4c.kl`）：

| 遥控器按键 | scancode | 原 keycode | 现 keycode | 桌面行为 |
|---|---|---|---|---|
| 设置 | 357 | 176 SETTINGS | 176 | 直接进「桌面设置」 |
| 菜单 | 139 | 82 MENU | 82 | 焦点图标弹右键菜单（打开/移除/排序/应用信息） |
| **Le 键** | 189 | 4431 LE | **135 F5** | 全局唤出应用库 / 每日一言面板 |
| **分享（截图）** | 377 | 4415 LETV | 4415（不变） | 固件自己截图，存 `/sdcard/Pictures/Screenshots/` |
| **语音** | 167 | 173 DVR | **136 F6** | 一键打开用户指定的 App |
| 输入源 | 187 | 无（VIRTUAL_NUMBER） | 无 | 乐视信号源面板（HDMI 1/2/3 + 数字电视） |

**关键点**：`KeyEvent.getScanCode()` 返回的是 **Linux EV_KEY 码**（`.kl` 文件里的 `key N`），不是 `getevent` 打印的 `MSC_SCAN` 值——后者是 USB HID 层的另一套编码，`KeyEvent` 永远不返回它。分不清这两者，按键映射必然错。

改映射用 `scripts/fixkl.sh`（幂等，重刷固件后重跑即可）：

```bash
su -c 'mount -o rw,remount /system'   # SuperSU 需要 --mount-master，见 docs/开发文档.md §9
sh fixkl.sh
reboot                                 # .kl 开机只读一次，必须重启生效
```

### 2️⃣ 每日一言：本地语句库，不联网

桌面右上角每天一句，同一天固定；Le 键面板每次打开随机一句。**9896 条语句打包进 APK，完全离线**。

![每日一言](docs/images/home-hitokoto.png)

- 12 个分类（动画、诗词、影视、哲学、抖机灵……），**首页与 Le 键两套独立选择**
- 分类用位掩码存储，12 位全选 = 4095
- 同一天同一句：日期做种子混洗后对**命中条数**取模（不是对总数取模，否则改分类范围会偏）
- 语句库来自[一言开源社区官方语句库](https://github.com/hitokoto-osc/sentences-bundle)，随附抓取与过滤脚本

Le 键面板里可以左右方向键翻句：

![Le 键每日一言](docs/images/le-quote.png)

### 3️⃣ Le 键：在任何 App 里唤出应用库

乐视遥控器上那颗 **Le 键**，被固件劫持成"打开乐视视频"（`startService(com.stv.lekey)` + 启动 `com.letv.tv/.WelcomeActivity`）。本项目把它夺了回来。

![Le 键全局应用库](docs/images/le-in-app.png)

- **在任何 App 里都能唤出**，且**不跳回桌面**——透明 Activity 悬浮在当前 App 之上
- 切换 App 无缝：关掉面板就回到原来那个 App
- 面板内容二选一：应用库 / 每日一言
- **关掉面板后视频自动续播**（见下）

**⚠️ 需要 root**：固件会在别的 App 到前台约 0.5 秒后 force-stop **所有**持有无障碍服务的包（连新建的单服务小包也一样），所以 App 级的全局按键监听在这台机器上**根本做不到**。本项目用 `/system/bin/lekeyd`——一个 12KB 的 root 守护进程，直接读 `/dev/input/event*`，不依赖任何 App 存活。

**顺带的"避广告"效果**：应用库面板盖上去时，下面的视频 App 会进入 `STOPPED`（不只是 PAUSED），音频轨道直接归零——**这是 App 主动触发的暂停，不会弹暂停广告**。而关闭面板后播放守卫会确认回到了同一个 App 且确实没声音，再补一个媒体播放键，**把视频还给你**。

> 判定"现在到底有没有在播"必须读 `dumpsys media.audio_flinger`——`dumpsys media_session` 的 `state=3` 在这台机器上是**陈旧的**，面板盖上去后 session 还挂着"播放中"而音频轨道早就没了。

---

## 目录结构

```
CleanLetv/
├── launcher/              桌面启动器源码（Android Studio 直接打开）
│   └── app/src/main/
│       ├── java/com/gathin/tvhome/
│       │   ├── MainActivity.java         桌面主 Activity，按键分发链在这里
│       │   ├── HomeKeyService.java        无障碍服务，占住设置键
│       │   ├── AppLibraryActivity.java    透明 Activity，全局应用库外壳
│       │   ├── AppLibraryDialog.java      应用库 / 每日一言面板
│       │   ├── Sentences.java             本地语句库索引（行偏移，不全量进内存）
│       │   ├── Hitokoto.java               取句入口（首页固定 / 面板随机）
│       │   ├── Weather.java                天气（和风天气）
│       │   ├── Lunar.java                  农历
│       │   ├── Compat.java                 高版本 API 收口（minSdk 23 的地基）
│       │   └── ...
│       └── assets/sentences.json          9896 条语句（AGPL-3.0，见 NOTICE）
├── themes/letv-x4/        乐视专属主题（按键映射、列数、程序坞…）
├── scripts/
│   ├── fixkl.sh           按键重映射（幂等）
│   └── findkl.sh          找出遥控器绑定的 .kl 文件
├── dbg/
│   ├── lekeyd/            全局 Le 键守护进程（root，纯 C）
│   │   ├── lekeyd.c       主程序
│   │   ├── lekeyd.sh      看护脚本（单实例）
│   │   ├── leplay.sh      播放守卫（关面板后把视频还回去）
│   │   └── build.sh       NDK 交叉编译
│   └── kl-backup-orig/    .kl 原件备份（用于还原）
├── tools/
│   ├── tv.py              纯 Python ADB 客户端（不依赖 adb 二进制）
│   ├── b64push.py         文件传输（这台电视的 adbd SYNC 协议会挂死）
│   ├── b64pull.py
│   └── letv-buttons.sh    一键修复设置键（禁乐视抽屉）
└── docs/
    ├── 开发文档.md         全部定制点、固件机制、坑与禁忌
    ├── 使用指引.md         面向最终用户的操作手册
    └── 迁移文档.md         装到另一台同型号电视的清单
```

---

## 快速上手

### 前置条件

- 一台已 root 的乐视电视（SuperSU / Magisk 均可）
- ADB 已开启（网络 ADB 5555 或 USB）
- 本机装了 Android SDK 与 JDK 17+

### 1. 编译安装

```bash
cd launcher
export ANDROID_HOME=/path/to/Android/Sdk
./gradlew assembleDebug
```

> 这台电视是 **Android 6.0**，所以 `minSdk` 从 26 降到了 23。`java.time` 靠 core library desugaring 跑在 API 23 上，**别为了省 1.5MB 把它去掉**。

### 2. 推送安装

```bash
# 这台固件的 adbd SYNC 协议会挂死，adb push 传不动，必须用 b64push
python tools/b64push.py launcher/app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/tvhome.apk
adb shell "pm install -r -d /data/local/tmp/tvhome.apk"
```

**装完必须做这三步**（`pm install -r` 会清空无障碍设置，`am force-stop` 会连带关掉同进程的无障碍服务）：

```bash
adb shell "am force-stop com.gathin.tvhome"
adb shell "settings put secure enabled_accessibility_services com.gathin.tvhome/.HomeKeyService"
adb shell "settings put secure accessibility_enabled 1"
adb shell "input keyevent 3"    # HOME，把被屏保抢走的焦点唤回桌面
```

### 3. 部署按键重映射与守护进程（需要 root）

```bash
adb push scripts/fixkl.sh /data/local/tmp/
adb shell "su -c 'mount -o rw,remount /system'"
adb shell "su -c 'sh /data/local/tmp/fixkl.sh'"

adb push dbg/lekeyd/lekeyd dbg/lekeyd/lekeyd.sh dbg/lekeyd/leplay.sh /data/local/tmp/
adb shell "su -c 'cp /data/local/tmp/lekeyd /system/bin/ && chmod 755 /system/bin/lekeyd'"
adb shell "su -c 'cp /data/local/tmp/lekeyd.sh /system/bin/ && cp /data/local/tmp/leplay.sh /system/bin/ && chmod 755 /system/bin/lekeyd.sh /system/bin/leplay.sh'"
adb shell "su -c 'cp /data/local/tmp/install-recovery-2.sh /system/etc/ && chmod 755 /system/etc/install-recovery-2.sh'"

adb reboot
```

> ⚠️ SuperSU 的挂载命名空间隔离：普通 `su -c` 里的 remount 只作用于该会话的私有命名空间，**返回成功但全局仍是 ro**。必须用 `su --mount-master -c "mount -o remount,rw /system; ..."`，且 cp/chmod 要写在同一个会话里。写入前先 `grep ' /system ' /proc/mounts` 确认是 `rw`，别信返回码。

### 4. 修复设置键

乐视的 `com.stv.globalsetting` 服务常驻一个 `TYPE_PHONE` 窗口（`ty=2008`），**永远压在 `TYPE_APPLICATION` 之上**——我们的设置面板确实启动了、焦点也是我们的，但被整层盖住。代码层面无解，只能让那扇窗消失：

```bash
TV_HOST=192.168.1.50 bash tools/letv-buttons.sh
```

### 5. 推主题

```bash
python tools/b64push.py themes/letv-x4/config.json /sdcard/Android/data/com.gathin.tvhome/files/config.json
adb shell "su -c 'cp -f /sdcard/Android/data/com.gathin.tvhome/files/config.json /data/data/com.gathin.tvhome/files/config.json'"
```

主题 JSON 可以改列数、程序坞行数、每日一言开关与分类、按键映射等，不用重编译。完整键表见 `themes/README.md`。

---

## 完整文档

| 文档 | 内容 |
|---|---|
| [docs/开发文档.md](docs/开发文档.md) | 固件机制分析、全部定制点、**26 条踩坑与禁忌**、验证方法 |
| [docs/使用指引.md](docs/使用指引.md) | 面向最终用户的操作手册 |
| [docs/迁移文档.md](docs/迁移文档.md) | 装到另一台同型号电视的完整清单 |
| [NOTICE](NOTICE) | 协议分层说明（MIT 上游 + AGPL 语句库） |

---

## 协议

本项目**整体采用 AGPL-3.0**，原因见 [NOTICE](NOTICE)：

- **桌面源码**（`launcher/`）：来自 CleanYourTV，MIT 协议，Copyright (c) 2026 Gathin。修改后同样以 MIT 发布，原授权声明保留在 `launcher/LICENSE`
- **每日一言语句库**（`assets/sentences.json`）：AGPL-3.0，打包进 APK 分发要求衍生作品同样以 AGPL-3.0 发布
- **乐视适配脚本与守护进程**：本项目作者编写，AGPL-3.0

> 如果你只想要 MIT 许可的那部分：删掉 `launcher/app/src/main/assets/sentences.json` 即可（每日一言功能失效，其余不受影响），那一份代码适用 MIT。

字体 [Outfit](https://fonts.google.com/specimen/Outfit) 采用 SIL Open Font License 1.1。

---

## 致谢

**[CleanYourTV](https://github.com/Gathin23/cleanyourtv)** — Gathin23

本项目基于它开发。这个项目让两百多种电视摆脱了广告桌面，本仓库做的是把其中一个固执的国产厂商——乐视——也拉进队伍里。

**[hitokoto-osc/sentences-bundle](https://github.com/hitokoto-osc/sentences-bundle)** — 一言开源社区

每日一言的语句库来源。

---

<div align="center">

**⚠️ 本项目会修改电视的系统分区（keylayout 文件）并安装 root 守护进程。**
**请先完整备份，务必阅读 [docs/开发文档.md](docs/开发文档.md) 的固件机制章节后再动手。**

</div>
