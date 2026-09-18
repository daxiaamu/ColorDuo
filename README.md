# ColorDuo

ColorOS 系统桌面的 Xposed / LSPosed 空间景深模块。在原生「倾斜」翻页中，让页面靠近屏幕的一侧清晰，向屏幕后方倾斜的一侧渐进虚化。

[下载 v0.3.2](https://github.com/daxiaamu/ColorDuo/releases/tag/v0.3.2) · [MIT 许可证](LICENSE)

## 兼容性

已验证：Android 16、ColorOS 16 / V16.1.0，系统桌面 OplusLauncher **16.6.17（160060017）**。

APK 最低 Android 13，但其他 Android / ColorOS / 桌面版本尚未验证。Hook 依赖桌面内部实现，更新系统桌面后可能需要重新适配。

## 安装

1. 从 Releases 下载并安装 APK。
2. 在 LSPosed 中启用 ColorDuo，作用域只勾选系统桌面 `com.android.launcher`。
3. 重启系统桌面或手机。
4. 将桌面「翻页效果」设为「倾斜」。

模块应用内提供景深预览。预览只验证 GPU 渲染，不代表 LSPosed 已注入；注入状态请查看 LSPosed 的 ColorDuo 日志。

停用或恢复：切换其他翻页效果，或禁用模块并重启桌面。桌面无法启动时，禁用或卸载模块；不需要清除桌面数据。

当前 APK 为**开发签名的实验版本**。自行构建会使用自己的签名，可能无法覆盖安装官方附件。历史 0.1.0 存在启动故障，已撤回，不应安装。

## 渲染与性能

- 保留原生滑动、旋转及透视变换，根据页面旋转和横向位置计算空间深度，最大虚化半径为 26dp。
- GPU 工作线程预计算高斯纹理金字塔，翻页期间不逐帧重做整页高斯模糊。
- 按景深划分绘制区域，每个区域仅使用相邻两级纹理，连续插值保持近清远糊。
- 透明留白、Alpha 合成及边界采样让光晕自然衰减；翻页期间避免原生硬件层反复创建及矩形裁切，清理时恢复状态。

v0.3.2 同机短时测试：连续 16 次 550ms 双向翻页，共 1107 帧；P90 / P95 为 **7ms**，GPU P90 为 **4ms**。Android gfxinfo 新口径 Janky 0%，legacy 0.09%。测试环境、温度、刷新率与系统负载都会影响结果，不代表所有设备长期稳定满帧。

## 已知限制

- 翻页时使用页面缓存快照，小组件在此期间不会实时更新，结束后恢复原生内容。
- 首次缓存可能早于小组件加载完成；缓存时效、复杂部件及长期内存表现仍需继续验证。
- 仅适配原生「倾斜」，与其他修改桌面渲染的模块共存情况未验证。
- 不修改壁纸；使用实时景深近似，不是完整的光学散景模拟。

## 构建

需要 JDK 17、Android SDK Platform 37。项目使用 Gradle 9.3.1、Android Gradle Plugin 9.1.1；设置 `ANDROID_HOME` 或本地 `local.properties` 中的 `sdk.dir`。

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Windows 可使用验证脚本（支持中文用户目录的 JUnit 启动兼容处理）：

```powershell
.\scripts\verify.ps1 -JavaHome 'C:\Path\To\jdk-17'
```

输出位于 `app/build/outputs/apk/debug/app-debug.apk`。v0.3.2 构建、Android Lint 和 10 项单元测试通过；GPU 效果仍需真机验证。

## 参考与许可证

- [Mac-Duo](https://github.com/sumimakito/Mac-Duo)：高斯纹理金字塔与按景深选取 LOD 的架构参考。
- [DuoLikeAnimation](https://github.com/elijah-semyonov/DuoLikeAnimation)：空间景深及采样方案参考。
- [Android GPU 离屏渲染](https://developer.android.com/guide/topics/renderscript/migrate)。

ColorDuo 的 Android 实现独立编写，采用 MIT 许可证。构建工具及第三方依赖保留各自许可证，参见 [第三方说明](THIRD_PARTY_NOTICES.md)。
