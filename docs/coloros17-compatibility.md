# ColorOS 17 适配说明

目标 OTA：PLK110_11.C.61_1610_202609130501，ColorOS 17.0.0.100(SP09CN01)，Android 17 / SDK 37。

桌面 APK：com.android.launcher，17.3.9（170030009），70,612,482 字节。

- APK SHA-256：e7adcf4059007da7afe3b3a28801869dda82a14ea7961f8a6b762e94e92917b9
- Oplus 签名证书 SHA-256：e49802409584ce53152a9000820a51e4fa8a723b7bcc263e335240acf100bf9e
- APK Signature Scheme v3 验证通过。

用户提供的是从 PLK110_11.A.73 升级的增量 OTA，其 system_ext 大部分数据依赖旧分区，不能直接当作全量镜像解包。本次分析使用本机已有同一 C.61 来源的完整、原始签名桌面备份；其长度和构建时间与增量包中的文件记录一致。未把缺失块填零的局部镜像当作完整 APK。

## 结果

SlantEffectAgent、EffectAgent、OplusWorkspace、CellLayout、状态查询、控制器字段与绘制入口仍然保持所需结构。倾斜处理增加了边缘页方向判断，模块仍读取原生变换，不改写它的几何逻辑。

OplusWorkspace 的状态切换存在提前返回分支，可能不调用 Workspace 父类。本版改为解析并 Hook 实际子类的状态切换和分离入口，提前清理自定义绘制与图层状态。

所有宿主 Hook 的类、字段和方法在接入前统一校验，检查返回类型、字段类型和页面绘制继承关系；读取元数据时不初始化桌面静态类。校验失败会停用本次进程的模块渲染并记录原因。

## 验证范围

- 16.6.17 和 17.3.9 的 APK 各通过 25 项静态结构检查。
- 0.5.1 正式版编译与 Android Lint 完成，40 项客户端测试和 7 项发布脚本测试通过。
- 新增测试覆盖子类提前返回、继承成员、错误签名拒绝以及元数据读取不触发静态初始化。
- 2026-09-27，用户确认模块支持 ColorOS 17，据此发布 0.5.1 正式版。该确认未附具体机型、桌面版本或帧率记录，不扩展为所有版本与设备的测试结论。

复核命令（APK 使用 apktool 解码后）：

```sh
python scripts/verify_launcher_contract.py path/to/decoded-launcher --json contract.json
```

桌面 APK、反编译内容、OTA 和本地设备材料均未加入源码仓库，也不包含在模块 APK 中。

## ColorOS 16 上替换桌面的实测

一加15 / PLK110_16.0.10.500(CN01) 上，17.3.9 的普通安装被 OSDK 校验拒绝（minOsdkVersion=40.23）。虽然 APK 的 minSdkVersion=35，通过系统挂载替换后仍因缺少 `android.window.TaskSnapshotListener` 与 `android.gui.EarlyWakeupInfo` 而无法启动。已回退原桌面。

因此本模块的 ColorOS 17 支持不包含将 ColorOS 17 桌面移植到 ColorOS 16。发布包只提供 ColorDuo APK，不能替代系统升级。


## 一加15 C.75 / 桌面17.3.12（2026-09-29）

从 NAS 官方 ROM 归档中的 `ColorOS PLK110_17.0.0.102(CN01) C.75/558a35a9c0714d4f852a02b28977118f.zip` 按需提取完整 `system_ext` 分区，取得原始桌面 APK：

- 版本：17.3.12（170030012），最低 SDK 35，目标 SDK 37。
- APK SHA-256：`22fcfa154961d7494bf641874a6e9c92b90373cb595ae7b68061312f742e0388`。
- 签名 SHA-256：`e49802409584ce53152a9000820a51e4fa8a723b7bcc263e335240acf100bf9e`，签名验证通过。
- 分区构建时间：2026-09-24，系统指纹：`oplus/ossi/ossi:17/CP2A.260605.016/1790197155508:user/release-keys`。

反编译完成后，25 项静态契约检查全部通过。去掉调试行号后，`SlantEffectAgent.applySlantEffect`、`restoreParameters`、`EffectAgent.interceptEffectWhenSwitchingState`、`recycle`、`CellLayout.dispatchDraw`、`enableHardwareLayer`、`OplusCellLayout.enableHardwareLayer`、`OplusWorkspace.setState`、`onDetachedFromWindow` 与 17.3.9 一致。可见页面范围方法只变更日志辅助类引用，页面切换回调存在辅助类混淆名称变化，模块依赖的入口与签名保持不变。

静态检查只确认入口和签名仍存在，不能证明运行时效果有效。用户实测 0.5.1 无效果，连接真机后定位到桌面开启统一渲染（`sys.unirender.com.android.launcher.enable=1`）：原有 `HardwareRenderer + ImageReader` 在提交返回成功后仍拿不到图像；增加等待及改用 `HardwareBufferRenderer` 均未解决。独立预览进程正常，临时关闭桌面的统一渲染可恢复纹理与绘制。对照测试结束已恢复该属性为 `1`。

0.5.2-beta.1 在 Android 17 的注入桌面进程中使用系统 `HardwareRenderer.createHardwareBitmap(RenderNode, int, int)` 快照入口构建 GPU 纹理。初始化时校验方法签名；此非 SDK 接口依赖 LSPosed 的隐藏 API 访问能力，仅在注入进程调用。独立预览和 Android 16 保留原路径。纹理构建仍在后台执行，翻页继续使用缓存多级纹理，不修改模糊曲线、颗粒或系统统一渲染开关。

实机验证：一加15 / PLK110_17.0.0.102(CN01)，桌面17.3.12。统一渲染开启时成功生成纹理，捕获到倾斜翻页的磨砂虚化中间帧；效果1与效果2均有 GPU 绘制记录，并验证切换模式无需重启桌面。未把本次结果扩展为其他 ColorOS 17 构建或设备的兼容保证，也未作完整帧率基准测试。

## 自适应适配（0.5.2-beta.2）

渲染入口不再按 Android 版本号硬选。在 LSPosed 桌面进程中按签名探测系统硬件快照入口；不存在或被限制时，仍允许公开的 ImageReader 路径工作。两个效果共用后端选择器。每个候选入口首次使用前绘制一个微小测试图，并读回验证颜色与透明区域；实际页面输出还校验尺寸和硬件位图类型。成功的入口会被记住，失败时尝试另一入口，连续失败按 2/4/8/16/30 秒退避，避免逐帧重试。失败的页面缓存可重新准备，不再永久卡在取消状态。

Hook 仍以已确认的倾斜代理、页面类型和关键状态接口为语义边界，不扫描并猜测任意混淆方法。允许中间继承层、桌面 Activity 子类，按实际 Workspace、页面和代理类解析具体方法；页面新增 dispatchDraw 重写时不再整模块拒绝。解析同时校验参数、返回值及实例方法属性。页面适配失败保留原生页面；安装失败撤销相应 Hook，模块停用时恢复裁剪和硬件层。未知版本如果保留这些语义契约即可尝试适配；类名、关键字段或行为契约变化仍可能需要人工适配。

验证：48 项客户端测试与 Android Lint 通过，三个已保存桌面版本（16.6.17 / 17.3.9 / 17.3.12）各通过25项静态检查。一加15 C.75 实测平台快照后端通过颜色/透明度探针，动态页面 Hook 正常绘制；独立预览的公开 ImageReader 后端也通过探针。自动回退、冷却重试、恢复及方法签名拒绝有单元测试覆盖；尚未在其他未知系统版本上实测。


## 文件夹底板统一虚化（0.5.3，2026-09-30）

17.3.12 的原生玻璃背景在系统后续合成阶段生成，离屏页面纹理不包含这层背景。单独回放原生背景会使文件夹边框保持清晰，与已经虚化的图标不协调。

录制页面纹理时，从已识别的 BlurTransitionDrawable 的 mDefaultDrawable 读取普通材质颜色、圆角和宿主尺寸，创建独立的普通底板，不修改原生 Drawable。原生内容录制后以 DST_OVER 合成底板，再与图标一起进入原有纹理金字塔。两种效果共用此路径；静止桌面保留原生材质，翻页不额外绘制清晰边框，也不逐帧截图或构建滤镜。

遍历页面子视图的 View 背景和 ImageView 内容，按 Drawable 实例去重；未识别的材质保留原路径。一加15 C.75 上确认覆盖 FolderRoundImageView 的普通/大文件夹，以及小布建议的 CarouselBlurBackgroundView 和内部文件夹背景。翻页中间帧已确认普通底板和图标共同虚化。

此前 UniRender 下 gfxinfo 返回 Total frames rendered: 0，该统计不能用于给出帧率或零卡顿结论。其他未知系统仍需实测。
