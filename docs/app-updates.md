# 应用内更新

按 [android-app-update 技能](https://github.com/daxiaamu/update-by-github-skill/blob/main/SKILL.md) 接入，仅运行在模块设置应用中，不进入桌面绘制进程。

- 主界面可交互 2 秒后自动检查，每进程一次；手动检查总是发起新会话，进行中则加入同一任务。
- 设置中可选择预发布渠道。stable 只检查 stable；beta 同时检查 stable/beta，以 versionCode 比较。首次默认由 APK versionName 是否含预发布后缀决定。
- 普通更新提供跳过版本、忽略、下载安装；已知更新显示红点。强制边界持久化，优先于跳过状态。
- 固定操作区与滚动 Markdown 日志；发布时间按设备语言与时区显示。安装包进度节流 300 ms，校验期间不显示完成。
- 下载到私有缓存临时文件，失败切换源，完整校验并原子重命名。每次安装和未知来源授权返回都重新核验当前清单 SHA-256、包名、versionCode 和签名。当前采用相同签名者集合，不自动接受签名轮换。
- 使用只读、不可导出的 content provider 临时授予安装器读取权限，使用系统安装器，无 Root 静默安装。网络/存储/身份校验失败提供重试；进程重建后重新检查清单，再校验缓存文件，不信任历史完成状态。

## 元数据与发布

`updates/policy.json` 是唯一人工维护的策略文件。初始 maxForcedVersionCode=0，不强制更新；之后调整边界需要单独授权和审查。每次发布前递增对应渠道 policyRevision，记录原因，不能复用修订覆盖已发布内容。

GitHub Contents API 是唯一权威元数据源，同时并发查询 Raw、jsDelivr 多入口和 Statically（共 7 个地址）。权威结果返回后收集 800 ms 镜像用于冲突检测。拒绝已接受修订回退、同修订摘要冲突、版本或强制边界降低。无权威结果时要求两个独立主机结果一致，多个 jsDelivr 子域只算一个信任来源。

`latest.json` 指向含 SHA-256 的不可变清单路径，并带 90 天有效期。维护者需在到期前递增策略修订并运行 workflow_dispatch 刷新。客户端持久化已接受策略；检查失败不会撤销已知强制边界。此版本使用 HTTPS 权威指针和清单哈希，未实现离线应用层签名。

Release 发布后 `.github/workflows/publish-update.yml` 下载唯一 APK，从 APK 提取版本/包名，验证既有证书，验证所有 CDN 候选的完整 SHA-256。至少 5 个不同第三方主机通过后，才将它们和官方地址写入清单；不足时 Actions 失败、保留旧清单。默认候选来自技能推荐池，不保证第三方服务持续可用。

清单和 latest 指针在同一个 Git 提交中发布。workflow_dispatch 可为现有 Release 初始化渠道。生成文件不手改，APK 使用带版本的 Release URL，不覆盖同版本资产。

## 验证

`./scripts/verify.ps1` 覆盖客户端解析、强制边界、渠道、防回退、同修订冲突、独立镜像门槛、手动/自动共享会话、发布时间和文件变更检测。

`python -m unittest discover -s scripts/tests -v` 验证发布清单的 CDN 数量、主机去重、HTTPS、版本类型和强制边界。

授权页返回、真实安装器和不同网络条件仍需真机持续验证。ColorOS 17 的验证范围见 [适配说明](coloros17-compatibility.md)。
