# ColorOS 17 适配检查

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
- 0.5.1 编译与 Android Lint 完成，21 项单元测试通过。
- 新增测试覆盖子类提前返回、继承成员、错误签名拒绝以及元数据读取不触发静态初始化。
- **尚未进行 ColorOS 17 真机注入、实时切换、帧率或长期稳定性测试。静态检查不等于运行兼容性保证。**

复核命令（APK 使用 apktool 解码后）：

```sh
python scripts/verify_launcher_contract.py path/to/decoded-launcher --json contract.json
```

桌面 APK、反编译内容、OTA 和本地设备材料均未加入源码仓库，也不包含在模块 APK 中。

## ColorOS 16 上替换桌面的实测

一加15 / PLK110_16.0.10.500(CN01) 上，17.3.9 的普通安装被 OSDK 校验拒绝（minOsdkVersion=40.23）。虽然 APK 的 minSdkVersion=35，通过系统挂载替换后仍因缺少 `android.window.TaskSnapshotListener` 与 `android.gui.EarlyWakeupInfo` 而无法启动。已回退原桌面。

因此本模块的 ColorOS 17 静态适配不包含将 ColorOS 17 桌面移植到 ColorOS 16。预发布版只提供 ColorDuo APK，不能替代系统升级。
