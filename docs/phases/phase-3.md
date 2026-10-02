# Phase 3：应用内更新器

## 目标

实现静默检查 GitHub Releases + 有更新时弹窗提示，用户可选「下载安装」或「忽略本次」。

## 行为

- **检查时机**：App 启动时（在首启引导完成后、主内容渲染前），后台静默检查
- **API**：GitHub Releases 公开端点 `GET /repos/wochatchat/LiveRecorder/releases/latest`（无需登录）
- **取最新版本**：`tag_name` 去掉前缀 `v` 得到版本号字符串（如 `v0.2.3` → `0.2.3`）
- **取 APK asset**：`assets` 里找第一个 `.apk` 文件，取 `browser_download_url`
- **跳过条件**：prerelease / draft release / 当前 commit 是 latest tag → 不提示
- **比较版本**：`0.2.3` vs `BuildConfig.VERSION_NAME`（`0.2.3`）字符串相等则忽略

## 更新弹窗 UpdateDialog

- `Surface` 大弹窗（`AlertDialog`），标题「发现新版本 v{latest}」，正文当前版本 v{current}
- changelog 块：Release body 里 `<h1>` / `## ` 分隔的变更说明（取前 500 字符截断）
- 按钮：左侧「忽略此版本」+ 右侧「下载安装」
  - 「忽略此版本」→ 写入 `DataStore`（`ignored_version`），弹窗消失，下次启动不再提示
  - 「下载安装」→ 用 `Intent.FLAG_ACTIVITY_NEW_TASK` 调 `android-open` 打开 APK 下载 URL（浏览器下载）

## 存储

`AppSettingsStore` 新增 `ignoredVersion: Flow<String?>`（`ignored_version` 键）。
忽略某版本后，直到发布新版本才再次提示。

## 入口

`AppNavigation` 中，`MainScaffold` 渲染前插一个 `LaunchedEffect(Unit)` 检查更新：
```kotlin
if (onboardingDone == true) {
    UpdateChecker.check(context) { dialogState ->
        if (dialogState != null) UpdateDialog(dialogState, ...)
    }
}
```

## 文件变更

| 文件 | 操作 |
|---|---|
| `data/UpdateChecker.kt` | 新建；检查 API + 版本比较逻辑 |
| `ui/screens/UpdateDialog.kt` | 新建；更新弹窗 Composable |
| `data/AppSettings.kt` | 修改；加 `ignoredVersion` 持久化 |
| `ui/navigation/AppNavigation.kt` | 修改；启动时调用 UpdateChecker |
| `res/values/strings.xml` | 新增相关文案 |
| `data/UpdateCheckerTest.kt` | 新建；版本解析单测 |

## 验收标准

1. 非 latest 版本启动时弹出 UpdateDialog（可截图验证 UI）
2. 点「忽略此版本」后重启不再弹（`ignoredVersion` 写入）
3. `UpdateCheckerTest` 全绿（最新版本号解析、prerelease 跳过、draft 跳过、APK asset URL 提取）
4. compile-check 全绿