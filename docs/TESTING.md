# 验证与兼容范围

本文件记录测试能证明的范围，不把本地演示数据当作官方 Gemini 兼容性证据。

## 2026-11-03 预览版验证记录

- 网页扩展：8 个场景在 Linux Chromium、Windows Server 2022 Chromium 各运行一次，均通过；测试实际安装了扩展。
- Android：4 个纯 Java 索引测试，以及 7 个节点／服务场景在 Android API 28、35 的 Robolectric 环境各执行一次，共 18 个测试，全部通过。
- Android debug、release 构建通过；Lint 没有错误，有 4 条非阻断警告（旧系统忽略无障碍工具标记、建议提供 Android 12 备份规则、两条中文 UI 文案国际化建议）。对话内容不写入磁盘，`allowBackup=false`。
- 发布 APK 使用项目专用 RSA 3072 签名，`apksigner verify` 验证 v2／v3 签名。检查 APK Manifest 确认没有 `INTERNET` 权限。
- 最终工作流结果见 [GitHub Actions](https://github.com/maplecat707-cmd/gemini-conversation-navigator/actions)，发布文件校验值见 Releases 中的 `SHA256SUMS.txt`。

官方 App 真机及登录后的在线 Gemini 兼容性仍待验证，详见下文。

## 网页扩展

`npm test` 使用 Playwright，在真实 Chromium 中安装仓库里的 Manifest V3 扩展。浏览器请求的 `https://gemini.google.com/app/...` 被测试夹具拦截，不访问账号。

覆盖场景：

- 多轮长对话的目录提取，点击后滚动正确的嵌套滚动容器。
- 搜索命中、关键词标记、空结果，以及提问中的 HTML 字符串不能执行脚本。
- 同样的提问多次出现、嵌套提问标签、流式文本更新。
- 单页应用切换会话，包括空会话，旧目录和筛选词被清除。
- 通过滚动触发懒加载历史，并恢复原阅读位置。
- 加载中停止、加载中切换会话。
- Escape、窄屏布局、窄屏点击后收起。
- 深色模式、无提问状态；生成 `web-preview.png` 供视觉检查。

已验证 DOM 形式为 `user-query`、`.query-text` 等 Gemini 常见结构。还支持 `[data-message-author-role="user"]` 与 `.user-query-container`。**未使用真实登录账号验证当前在线 Gemini 的所有页面版本。** Canvas、嵌入式页面、共享页及实验版布局没有专门适配。扩展在 Linux 和 Windows Chromium 中测试；Chrome／Edge 使用相同扩展 API，但不等于已在每个浏览器版本上实测。

## Android

构建与测试命令：

```bash
cd android
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

纯 Java 索引测试覆盖历史页重叠合并、顺序、重复文字、搜索、空页和清空。Robolectric 节点测试覆盖滚动容器选择、文本提取、过滤输入框／密码／按钮／隐藏节点、父子重复文本、应用包过滤与 Google 宿主开关。

Robolectric 服务测试驱动实际 `NavigatorService`，使用 10 个模拟无障碍页面，验证收录、返回起点、点击旧内容定位、取消后没有后续滚动、离开 Gemini 清空、目标不存在时停止。

**模拟节点测试不能证明官方 Gemini Android App 当前版本暴露了同样的节点和滚动动作。当前开发环境没有连接 Android 真机，尚未完成官方 App 真机验证。** Android 首版因此为实验性预览，不承诺支持每一款设备／Gemini 版本。

## 真机验收清单

在发布兼容性结论前，请记录手机型号、Android 版本、Gemini 版本、语言，并验证：

1. 安装 APK，打开引导页面；未同意前不读取内容。
2. 同意并启用无障碍服务，打开官方 Gemini 长对话。侧栏只在 Gemini 前台出现。
3. 至少 20 轮对话，从末尾点「收录前文」。目录顺序合理，早期文字确实被收录；无法读取时有明确提示。
4. 搜索前面独特的一段文字，点击后确实回到它在官方 App 中的位置。
5. 定位中点停止、切到其他 App，都不再自动滚动。
6. 回到 Gemini 后重新收录；在 Gemini 内换会话时清空后重新收录。
7. 重复提问、很长的回复、图片附件、加载慢和旋转屏幕，均不会无限滚动或崩溃。
8. 系统设置关闭服务后，悬浮窗消失且不再读取。

Windows 真机验收：按 README 安装扩展，登录 Gemini，使用至少 20 轮长对话验证搜索、回到前文、懒加载、编辑提问、切换会话、页面刷新及 Chrome／Edge 快捷键。

报告问题时只需版本与复现步骤。请勿提交真实私人对话、账号令牌或其他敏感材料。
