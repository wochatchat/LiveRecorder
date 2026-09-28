# Phase 6f：引导与打磨

## 6f-1 — R21 首启引导 + R23 空态 ✅（commit 14c4be4）

**R21 Onboarding**
- `ui/screens/OnboardingScreen.kt`：HorizontalPager 3 页
  - 第1页：欢迎+支持平台；第2页：权限卡片×2；第3页：使用流程+示例链接点击复制
  - Lifecycle ON_RESUME 重算授权态；enabled=!done 可跳过
- 门控：AppSettingsStore 新增 `onboarding_completed` + `completeOnboarding()`
- AppNavigation collectAsState(null=null帧/false=引导/true=MainScaffold)
- Manifest 加 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS；strings.xml +20 条

**R23 空态**
- MonitorScreen EmptyState：图标+Button+TextButton 示例链接预填
- RecordsScreen EmptyRecords：图标+双行说明 strings 资源化

## 6f-2 — R22 通知增强
- [ ] MonitorService NotificationCompat 增 Action：暂停/恢复监控
- [ ] 开播通知点击跳对应 MonitorCard

## 6f-3 — R24 字符串资源化（无障碍/动效后续做）
- [x] 全量硬编码中文迁 strings.xml（2026-09-28 ✅，新增 ~130 条，CI run 36412043843 全绿）
  - 覆盖：MonitorScreen / MonitorCard / RecordStatusLine / StatusBadge / CookieDialog / CookieManagementScreen / RecordsScreen / SettingsScreen + Records/SettingsViewModel（getApplication<Application>().getString）
  - 非 composable 回调用 context.getString；MonitorScreen notifyRemoved 的 context 前向引用已修复
  - 保留：平台品牌名（PLATFORM_LABELS）、画质选项（DataStore 持久化值）、Onboarding 示例链接、AppLog 调试日志
- [ ] contentDescription + 触达面积 ≥48dp
- [ ] 列表增删动画

## 6f-4 — R25 全量截图验收
- [ ] 深浅色截图（含攒批的 5e/5f/6b~6e 真机走查）
- [ ] build-test 出包

## 红线
- 录制服务接线不动；ViewModel 只加不减
