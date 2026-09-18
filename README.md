# ColorDuo

ColorOS 系统桌面的 Xposed / LSPosed 空间景深模块：近侧清晰，远侧逐渐虚化。保留原生「倾斜」翻页的旋转和手感。

[下载 v0.5.0](https://github.com/daxiaamu/ColorDuo/releases/tag/v0.5.0) · [MIT 许可证](LICENSE)

## 两种效果

| 选项 | 观感 | 距离曲线 |
| --- | --- | --- |
| 效果1 · 高斯景深 | 首个 Release v0.3.2 的柔和渐进虚化 | 4dp 清晰区，之后按原版 smoothstep 曲线增强 |
| 效果2 · 磨砂雾化 | 多点散射、微折射和细颗粒 | 0 时清晰，3dp 达到 20%，随后线性增强 |

两种效果都在虚拟深度 110dp 达到最大 26dp 半径，空白处保持透明，光晕自然衰减。效果2颗粒尺寸为 0.28dp（页面局部坐标下最小 1px），默认选择效果2。

在模块主界面选择效果，**无需重启桌面**，返回桌面翻页即可体验；选择会自动保存，预览使用当前选择。切换后纹理缓存异步准备，首次翻页可能短暂显示原生效果，缓存就绪后自动接入。

## 安装与兼容性

已验证：Android 16、ColorOS 16 / V16.1.0，系统桌面 OplusLauncher **16.6.17（160060017）**。

1. 从 Releases 下载并安装 APK。
2. 在 LSPosed 中启用 ColorDuo，作用域只勾选系统桌面 `com.android.launcher`。
3. **首次安装或更新 APK 后**，重启系统桌面或手机一次。
4. 将桌面「翻页效果」设为「倾斜」。之后模块内切换效果1/效果2不需要重启。

APK 最低 Android 13；其他 Android / ColorOS / 桌面版本尚未验证。系统桌面更新后可能需要重新适配。

模块内预览只验证 GPU 渲染，不代表 LSPosed 已注入；注入状态请查看 LSPosed 的 ColorDuo 日志。

停用或恢复：切换其他翻页效果，或禁用模块并重启桌面。桌面无法启动时禁用或卸载模块，**无需清除桌面数据**。历史 0.1.0 存在启动故障，已撤回。

当前 APK 为开发签名的实验版本，与此前官方附件签名一致。自行构建会使用自己的签名，可能无法覆盖安装官方附件。

## 实现与限制

- GPU 工作线程预计算多尺度纹理金字塔，翻页按景深分区，每个区域采样相邻两级纹理；不逐帧重新生成整页纹理。
- 效果1保留 v0.3.2 的渲染器和曲线。效果2使用有界散射核、页面坐标下固定的微折射与细颗粒，颜色处理遵循预乘 Alpha。
- 翻页期间避免原生硬件缓存层反复创建及矩形裁切，结束后恢复原生状态。
- 选择保存在模块私有设置中，桌面通过只读 ContentProvider 和 ContentObserver 异步读取、接收更新；不在绘制回调中进行跨进程设置读取。公开接口只返回效果编号，不允许外部写入。
- 翻页时使用页面缓存快照，小组件在此期间不会实时更新；结束后恢复原生内容。首次加载、缓存时效、复杂部件及长期内存表现仍需继续验证。
- 只适配原生「倾斜」。未验证与其他修改桌面渲染的模块共存。不修改壁纸，也不是完整光学模拟。

## 验证

v0.5.0 构建、15 项单元测试通过；Android Lint 为 0 errors（存在 warnings）。测试覆盖启动门控、两种距离曲线、起雾拐点、反向映射、LOD 反函数与效果编号处理。

真机通过模块按钮切换效果1和效果2，桌面 PID 保持不变；模块进程关闭后选择仍保留。预览跟随当前选择，桌面画面分别检查。

同机短时性能测试，每种效果各 8 次 550ms 双向翻页：

| 效果 | 帧数 | P90 | P95 | GPU P90 | Janky / legacy |
| --- | --- | --- | --- | --- | --- |
| 效果1 | 568 | 7ms | 7ms | 3ms | 0.18% / 0.70% |
| 效果2 | 553 | 7ms | 8ms | 4ms | 0.18% / 0.36% |

温度、刷新率和系统负载都会影响结果，不代表所有设备长期稳定满帧。

## 构建

需要 JDK 17、Android SDK Platform 37。项目使用 Gradle 9.3.1、Android Gradle Plugin 9.1.1；设置 `ANDROID_HOME` 或本地 `local.properties` 的 `sdk.dir`。

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Windows 验证脚本支持中文用户目录的 JUnit 启动兼容处理：

```powershell
.\scripts\verify.ps1 -JavaHome 'C:\Path\To\jdk-17'
```

输出为 `app/build/outputs/apk/debug/app-debug.apk`。GPU 材质与跨进程切换仍需真机验证。

## 参考与许可证

- [Mac-Duo](https://github.com/sumimakito/Mac-Duo)：高斯纹理金字塔及按景深选取 LOD 的架构参考。
- [DuoLikeAnimation](https://github.com/elijah-semyonov/DuoLikeAnimation)：空间景深及采样方案参考。
- [Android GPU 离屏渲染](https://developer.android.com/guide/topics/renderscript/migrate)。

ColorDuo 的 Android 实现独立编写，采用 MIT 许可证。构建工具及依赖保留各自许可证，参见 [第三方说明](THIRD_PARTY_NOTICES.md)。
