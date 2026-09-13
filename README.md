# LiveRecorder

安卓原生直播监控录制 App（Kotlin + Compose），移植自 [ihmily/DouyinLiveRecorder](https://github.com/ihmily/DouyinLiveRecorder)（Python）。

移植进度与架构评估见仓库内 `PORTING.md`。

## 功能（Phase 0）

- 监控直播间列表（增删，DataStore 持久化）
- 后台监控录制：分阶段开发中

## CI

| Workflow | 职责 |
|---|---|
| `build.yml` | push main：版本自增 + 签名 APK 归档 `apk/` + GitHub Release |
| `build-test.yml` | 手动：仅构建 Artifact，不 bump 不发布 |
| `compile-check.yml` | feature/fix 分支 + PR 编译校验 |
| `cleanup-artifacts.yml` | 每日清理 Artifact（保留最新 10 个） |

版本号唯一真源：根目录 `version.properties`（build.sh 自动管理，不手动编辑）。
