# 淘金币自动化

用 uiautomator2 驱动 Android 手机，自动完成淘宝「淘金币」浏览类任务。

> 基于 [coin11](https://github.com/Yingyi11/coin11) / [coin11-tb](https://github.com/czl0325/coin11-tb) 改造；已去掉赚体力、跳一跳，仅保留淘金币。

## 功能

- 自动完成淘金币任务（目标次数可配置，默认 40）
- 跳过需人工参与的任务（拉好友、下单、游戏等）
- 完成判定：检测「已得 / 已到账 / 任务已完成」弹窗；读取「浏览 XX 秒」
- 拟人滑动（弧线 + 抖动）
- 运行中空格暂停/继续，`q` 退出；日志写入 `logs/run_*.log`

## 快速开始

### 1. 连接手机

- USB 连接电脑，开启 **USB 调试**，并在手机上点「允许」

### 2. 配置（可选）

编辑 `conf/config.yaml`：

```yaml
task:
  coin:
    target_count: 40  # 目标次数
```

### 3. 运行

双击 `run.bat`，或：

```bash
python taojinbi.py
```

临时改目标次数：

```bash
python taojinbi.py task.coin.target_count=50
```

## 项目结构

```
taojinbi/
├── taojinbi.py          # 主脚本（仅淘金币）
├── run.bat              # 一键启动
├── conf/config.yaml     # 配置
├── utils/               # 工具与配置加载
├── platform-tools/      # adb
├── logs/                # 运行日志
└── builder/             # 可选：打包 exe
```

## 依赖

见 `requirements.txt`（uiautomator2、opencv、ddddocr 等）。`run.bat` 首次运行会自动安装。

## 流程概要

1. 连接设备 → 启动淘宝  
2. 首页「领淘金币」→「赚金币 / 赚更多金币」进入任务列表  
3. 循环：点「去完成/去逛逛」→ 浏览至完成弹窗 → 返回  
4. 进度达到目标或无可做任务后结束  

## 常见问题

- **找不到设备**：检查 USB 调试、授权弹窗、`adb devices`  
- **进不去任务页 / 按钮对不上**：淘宝改版了，看 `logs/run_*.log` 里打印的按钮文案，再改正则  
- **杀毒误报**：PyInstaller 打包常见，加白名单即可  

## 许可

仅供学习交流，请勿用于商业目的。自动化有账号风险，自行承担。
