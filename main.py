#!/usr/bin/env python
# -*- coding: utf-8 -*-
import sys
import os
from datetime import datetime

# --- 日志文件：同时输出到控制台和 logs/run_*.log ---
_LOG_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "logs")
os.makedirs(_LOG_DIR, exist_ok=True)
_LOG_FILE = os.path.join(_LOG_DIR, "run_" + datetime.now().strftime("%Y%m%d_%H%M%S") + ".log")


class _Tee:
    def __init__(self, *streams):
        self.streams = streams

    def write(self, data):
        for s in self.streams:
            s.write(data)
            s.flush()

    def flush(self):
        for s in self.streams:
            s.flush()


_log_fp = open(_LOG_FILE, "w", encoding="utf-8")
sys.stdout = _Tee(sys.stdout, _log_fp)
sys.stderr = _Tee(sys.stderr, _log_fp)
print(f"[日志] 运行日志已保存到: {_LOG_FILE}", flush=True)

print("Python script starting...", flush=True)

import time
import random
import re
import threading

# ===== 暂停/恢复 功能（空格键暂停/继续，q 退出）=====
_paused = False
_pause_lock = threading.Lock()
_exit_requested = False
_listener_stop = False


def _check_pause():
    """若处于暂停状态则原地等待；若请求退出则抛出 SystemExit"""
    while _paused and not _exit_requested:
        time.sleep(0.3)
    if _exit_requested:
        print("[退出] 收到退出指令，程序结束", flush=True)
        raise SystemExit(0)


def _pause_sleep(seconds):
    """可被暂停/退出打断的 sleep（每 0.2 秒检查一次暂停状态）"""
    if seconds <= 0:
        return
    end = time.time() + seconds
    while time.time() < end:
        _check_pause()
        remaining = end - time.time()
        time.sleep(min(0.2, max(0.0, remaining)))


def _keyboard_listener():
    """后台监听键盘：空格=暂停/继续，q=退出"""
    global _paused, _exit_requested, _listener_stop
    try:
        import msvcrt
    except ImportError:
        print("[控制] 当前系统不支持键盘监听，暂停功能不可用", flush=True)
        return
    print("[控制] 空格键 = 暂停/继续；q = 退出程序", flush=True)
    while not _listener_stop:
        try:
            if msvcrt.kbhit():
                ch = msvcrt.getch()
                if ch in (b" ", b"p", b"P"):
                    with _pause_lock:
                        _paused = not _paused
                    if _paused:
                        print("\n[暂停] 已暂停，按 空格键 继续运行...", flush=True)
                    else:
                        print("[恢复] 继续运行", flush=True)
                elif ch in (b"q", b"Q"):
                    _exit_requested = True
                    print("\n[退出] 已请求退出，将在当前任务结束后停止...", flush=True)
        except Exception:
            pass
        time.sleep(0.1)


print("Importing uiautomator2...", flush=True)
import uiautomator2 as u2

print("Importing utils...", flush=True)
from utils.utils import check_chars_exist, get_current_app
from utils.config_manager import get_config

print("Loading configuration...", flush=True)
config = get_config()
config.print_config()

print("=" * 60, flush=True)
print("淘宝双11自动化脚本启动", flush=True)
print("=" * 60)

print("正在连接设备...")
d = u2.connect()
print("✓ 设备连接成功")

# 从配置文件获取应用包名和等待时间
package_name = config.get('app.package_name', 'com.taobao.taobao')
launch_wait_time = config.get('app.launch_wait_time', 5)

print("正在启动淘宝应用...")
d.app_start(package_name, stop=True, use_monkey=True)
print("✓ 淘宝应用已启动")

print("获取屏幕信息...")
screen_width = d.info["displayWidth"]
screen_height = d.info["displayHeight"]
print(f"✓ 屏幕尺寸: {screen_width}x{screen_height}")

print(f"等待 {launch_wait_time} 秒让应用完全加载...")
_pause_sleep(launch_wait_time)
print("✓ 等待完成")

in_search = False
in_other_app = False
have_clicked = []
current_task_type = "coin"

print("启动监视器上下文...")
ctx = d.watch_context()
# ctx.when("O1CN012qVB9n1tvZ8ATEQGu_!!6000000005964-2-tps-144-144").click()
# ctx.when("O1CN01TkBa3v1zgLfbNmfp7_!!6000000006743-2-tps-72-72").click()
ctx.when("点击刷新").click()
# ctx.when(xpath="//android.app.Dialog//android.widget.Button[@text='关闭']").click()
# ctx.when(xpath="//android.widget.TextView[@package='com.eg.android.AlipayGphone']").click()
ctx.when(
    xpath="//android.widget.FrameLayout[@resource-id='com.taobao.taobao:id/poplayer_native_state_center_layout_frame_id']/android.widget.ImageView"
).click()
ctx.start()
print("✓ 监视器已启动")
print("=" * 60)
print()


def close_all_dialog():
    btn1 = d(className="android.widget.TextView", text="去使用")
    if btn1.exists:
        btn1.right(className="android.widget.ImageView").click()
        _pause_sleep(2)


def check_in_task():
    """检查是否在任务界面，如果不在则返回False（不主动导航）"""
    global current_task_type
    temp_package, temp_activity = get_current_app(d)
    if (
        temp_package == "com.taobao.taobao"
        and temp_activity == "com.taobao.themis.container.app.TMSActivity"
    ):
        phy_view = d(className="android.widget.TextView", text="做任务赚体力")
        if phy_view.exists(timeout=5):
            return True
    # 移除Welcome的自动导航逻辑，让返回流程自己处理
    # elif (
    #     temp_package == "com.taobao.taobao"
    #     and temp_activity == "com.taobao.tao.welcome.Welcome"
    # ):
    #     to_11(task_type=current_task_type)
    #     return True
    return False


def to_11(task_type="coin"):
    """
    导航到任务列表界面的完整流程
    
    参数:
        task_type: 任务类型，"coin"=金币任务(默认), "physical"=体力任务
    """
    print(f"开始导航到任务列表界面 (任务类型: {'赚金币' if task_type == 'coin' else '赚体力'})...")
    max_attempts = config.get('retry.navigation_max_attempts', 5)
    attempt = 0

    while attempt < max_attempts:
        package_name, activity_name = get_current_app(d)
        print(f"[to_11] 当前界面: {package_name}--{activity_name}")

        # 检查是否已在任务界面
        if (
            package_name == "com.taobao.taobao"
            and activity_name == "com.taobao.themis.container.app.TMSActivity"
        ):
            print("✓ 已在任务列表界面")
            _pause_sleep(2)
            break

        # 检查是否在 Welcome 首屏（使用 elif 避免被后面的 elif 覆盖）
        elif (
            package_name == "com.taobao.taobao"
            and activity_name == "com.taobao.tao.welcome.Welcome"
        ):
            print("[to_11] 在 Welcome 首屏，寻找'领淘金币'...")
            _pause_sleep(2)  # 等待页面完全加载

            # 第1步：多种方式查找"领淘金币"按钮
            coin_btn = None

            # 方式1: 通过 description 查找
            coin_btn = d(description="领淘金币")
            if not coin_btn.exists:
                print("[to_11] description方式未找到，尝试text方式...")
                # 方式2: 通过 text 查找
                coin_btn = d(text="领淘金币")

            if not coin_btn.exists:
                print("[to_11] text方式未找到，尝试textContains方式...")
                # 方式3: 通过 textContains 查找
                coin_btn = d(textContains="淘金币")

            if not coin_btn.exists:
                print("[to_11] 尝试通过TextView查找...")
                # 方式4: 直接查找TextView
                coin_btn = d(
                    className="android.widget.TextView", textMatches=".*淘金币.*"
                )

            if coin_btn.exists:
                print("✓ 找到'领淘金币'元素，点击...")
                # 获取元素中心坐标并点击，避免边缘点击失败
                bounds = coin_btn.info["bounds"]
                center_x = (bounds["left"] + bounds["right"]) // 2
                center_y = (bounds["top"] + bounds["bottom"]) // 2
                d.click(center_x, center_y)
                print(f"   点击坐标: ({center_x}, {center_y})")
                _pause_sleep(4)  # 增加等待时间

                # 第2步：等待页面切换，根据task_type查找对应按钮
                target_text = "赚金币" if task_type == "coin" else "赚体力"
                fallback_text = "赚体力" if task_type == "coin" else "赚金币"
                # 淘宝改版后入口文字可能变化（如"赚金币"→"赚更多金币"），用宽松正则优先匹配
                target_regex = ".*赚.*金币.*" if task_type == "coin" else ".*赚.*体力.*"
                fallback_regex = ".*赚.*体力.*" if task_type == "coin" else ".*赚.*金币.*"
                print(f"[to_11] 等待任务入口按钮出现（{'金币' if task_type == 'coin' else '体力'}任务）...")
                _pause_sleep(3)  # 增加等待时间

                # 多种方式查找按钮（优先用正则宽松匹配，能覆盖改版文案）
                physical_btn = None

                # 优先：正则宽松匹配（"赚金币"/"赚更多金币"等都能命中）
                physical_btn = d(textMatches=target_regex)
                if not physical_btn.exists:
                    print(f"[to_11] 正则未找到'{target_regex}'，尝试'{fallback_regex}'...")
                    physical_btn = d(textMatches=fallback_regex)

                # 回退：精确匹配
                if not physical_btn.exists:
                    physical_btn = d(text=target_text)
                    if not physical_btn.exists:
                        print(f"[to_11] 未找到'{target_text}'，尝试'{fallback_text}'...")
                        physical_btn = d(text=fallback_text)

                # 回退：textContains
                if not physical_btn.exists:
                    print("[to_11] 尝试textContains方式...")
                    physical_btn = d(textContains=target_text)
                    if not physical_btn.exists:
                        physical_btn = d(textContains=fallback_text)

                if not physical_btn.exists:
                    print("[to_11] 尝试通过Button查找...")
                    # 使用更精确的匹配，根据task_type决定
                    if task_type == "coin":
                        physical_btn = d(className="android.widget.Button", textMatches=".*金币.*")
                        if not physical_btn.exists:
                            physical_btn = d(className="android.widget.Button", textMatches=".*体力.*")
                    else:
                        physical_btn = d(className="android.widget.Button", textMatches=".*体力.*")
                        if not physical_btn.exists:
                            physical_btn = d(className="android.widget.Button", textMatches=".*金币.*")

                if physical_btn.exists:
                    print(
                        f"✓ 找到按钮: {physical_btn.info.get('text', '未知')}，点击进入任务列表..."
                    )
                    # 同样使用坐标点击
                    bounds = physical_btn.info["bounds"]
                    center_x = (bounds["left"] + bounds["right"]) // 2
                    center_y = (bounds["top"] + bounds["bottom"]) // 2
                    d.click(center_x, center_y)
                    print(f"   点击坐标: ({center_x}, {center_y})")
                    _pause_sleep(5)

                    # 验证是否成功进入任务列表
                    pkg, act = get_current_app(d)
                    if act == "com.taobao.themis.container.app.TMSActivity":
                        print("✓ 成功进入任务列表界面")
                        break
                    else:
                        print(f"✗ 点击后未立即进入任务列表，当前: {act}")
                        attempt += 1
                else:
                    print("✗ 未找到'赚体力'/'赚金币'按钮")
                    # 打印当前页面信息用于调试
                    print("[to_11] 打印当前页面元素信息...")
                    btns = d(className="android.widget.Button")
                    tvs = d(className="android.widget.TextView")
                    print(f"   页面有 {len(btns)} 个 Button, {len(tvs)} 个 TextView")
                    print("   所有 Button 文本:")
                    for i in range(min(20, len(btns))):
                        try:
                            text = btns[i].get_text()
                            if text:
                                print(f"      [Button {i}] {text}")
                        except:
                            pass
                    print("   所有 TextView 文本(前20个):")
                    for i in range(min(20, len(tvs))):
                        try:
                            text = tvs[i].get_text()
                            if text:
                                print(f"      [TextView {i}] {text}")
                        except:
                            pass

                    # 尝试通过屏幕区域点击
                    print("[to_11] 尝试通过屏幕区域估算点击...")
                    # 假设"赚体力"按钮在屏幕中下方
                    d.click(screen_width // 2, int(screen_height * 0.7))
                    _pause_sleep(3)
                    attempt += 1
            else:
                print("✗ 未找到'领淘金币'元素")
                # 打印页面信息用于调试
                print("[to_11] 打印当前Welcome页面元素...")
                xml = d.dump_hierarchy()
                if "淘金币" in xml:
                    print("   XML中有'淘金币'关键字")
                    # 尝试查找所有包含"金币"的元素
                    all_views = d(textMatches=".*金币.*")
                    print(f"   找到 {len(all_views)} 个包含'金币'的元素")
                    for i in range(min(5, len(all_views))):
                        try:
                            info = all_views[i].info
                            print(
                                f"      [{i}] class={info.get('className')}, text={info.get('text')}, desc={info.get('contentDescription')}"
                            )
                        except:
                            pass
                else:
                    print("   XML中没有'淘金币'关键字")

                # 尝试通过屏幕坐标点击（估算领淘金币的位置）
                print("[to_11] 尝试通过屏幕坐标点击（估算位置）...")
                # 通常这类入口在屏幕中央或下方
                d.click(screen_width // 2, int(screen_height * 0.5))
                _pause_sleep(3)
                attempt += 1

        # 其他非Welcome、非目标界面，重启淘宝
        elif package_name == "com.taobao.taobao":
            print(f"[to_11] 在其他淘宝界面: {activity_name}，重启淘宝...")
            d.app_start("com.taobao.taobao", stop=False)
            _pause_sleep(5)  # 增加等待时间
            attempt += 1
        else:
            print(f"[to_11] 非淘宝应用: {package_name}，启动淘宝...")
            d.app_start("com.taobao.taobao", stop=False)
            _pause_sleep(5)  # 增加等待时间
            attempt += 1

        if attempt < max_attempts and attempt > 0:
            print(f"[to_11] 第 {attempt}/{max_attempts} 次尝试...")
            _pause_sleep(2)

    if attempt >= max_attempts:
        print("⚠ 导航到任务界面达到最大尝试次数")
    else:
        print("✓ 成功导航到任务列表界面")

    print("导航流程完成")


def human_like_swipe():
    """模拟真人滑动：随机弧线轨迹 + 随机微抖动（防黑号，参数可在配置调整）"""
    # 从配置读取滑动时长范围和抖动概率
    dur_min = config.get('operation.human_swipe.duration_min', 0.5)
    dur_max = config.get('operation.human_swipe.duration_max', 1.2)
    jitter_prob = config.get('operation.human_swipe.jitter_probability', 0.3)

    # 随机起始点（偏左区域，避开屏幕边缘）
    start_x = random.randint(screen_width // 6, screen_width // 2)
    start_y = random.randint(screen_height // 2, screen_height - screen_width // 4)
    # 随机上滑距离
    end_y = random.randint(start_y - 1200, start_y - 300)
    # 水平漂移（模拟手指弧线，而非直线）
    end_x = max(0, min(screen_width, start_x + random.randint(-100, 100)))

    # 用多段点模拟曲线轨迹
    steps = random.randint(3, 5)
    points = []
    for i in range(steps):
        t = i / (steps - 1)
        x = start_x + (end_x - start_x) * t + random.randint(-25, 25)
        y = start_y + (end_y - start_y) * t + random.randint(-20, 20)
        points.append((max(0, min(screen_width, x)), max(0, min(screen_height, y))))

    duration = random.uniform(dur_min, dur_max)
    print(f"模拟滑动(曲线) {points[0]} -> {points[-1]} {duration:.2f}s")
    d.swipe_points(points, duration)

    # 随机微抖动（模拟手抖）
    if random.random() < jitter_prob:
        jx = random.randint(screen_width // 6, screen_width // 2)
        jy = random.randint(screen_height // 3, screen_height // 2)
        d.swipe(jx, jy, jx + random.randint(-10, 10), jy + random.randint(-10, 10), 0.08)


def operate_task(is_search_task=False, task_type="coin"):
    """
    执行任务浏览和返回操作
    
    参数:
        is_search_task: 是否是搜索类任务（需要多返回一次）
        task_type: 任务类型，"coin"=金币任务, "physical"=体力任务
    """
    start_time = time.time()
    cancel_btn = d(resourceId="android:id/button2", text="取消")
    if cancel_btn.exists:
        cancel_btn.click()
        _pause_sleep(2)
        return

    # 从配置获取任务完成等待上限、缓冲秒数和停顿范围
    max_wait_duration = config.get('operation.max_wait_duration', 35)
    required_buffer = config.get('operation.required_buffer', 8)
    pause_min = config.get('operation.human_swipe.pause_min', 1.5)
    pause_max = config.get('operation.human_swipe.pause_max', 4.0)

    # 尝试读取任务要求的浏览秒数（如"浏览25秒可领XX金币"）
    required_seconds = None
    try:
        xml_text = d.dump_hierarchy()
        m = re.search(r"浏览\s*(\d+)\s*秒", xml_text)
        if m:
            required_seconds = int(m.group(1))
            print(f"[任务] 检测到要求浏览 {required_seconds} 秒")
    except Exception:
        pass

    # 超时上限：有要求秒数则加 buffer，否则用配置默认值
    timeout_seconds = (required_seconds + required_buffer) if required_seconds else max_wait_duration

    # 执行模拟滑动，同时检测完成弹窗
    while True:
        _check_pause()

        # 检测完成提示：
        # 1. 好物沉浸看：右侧"已得XX"悬浮气泡
        # 2. 浏览精选好物：结束时"完成！XX金币已到账"
        # 3. 芭芭农场等：右侧弹窗"任务已完成"
        if d(textMatches=".*(已得|已到账|任务已完成).*").exists(timeout=1):
            print("✓ 检测到完成提示（已得/已到账/任务已完成），任务完成")
            break

        # 超时兜底
        if time.time() - start_time > timeout_seconds:
            print(f"⚠ 已等待 {timeout_seconds} 秒未检测到完成弹窗，按超时处理返回")
            break

        # 模拟真人滑动（弧线 + 抖动）保持页面活跃
        human_like_swipe()
        _pause_sleep(random.uniform(pause_min, pause_max))

    print("开始返回界面")

    # 从配置获取后退次数参数
    max_back = config.get('retry.max_back_times', 10)
    min_back_times_search = config.get('retry.min_back_times_search', 2)
    min_back_times_normal = config.get('retry.min_back_times_normal', 1)
    
    # 返回到任务列表的逻辑
    back_count = 0
    consecutive_welcome = 0
    # 搜索任务需要至少后退2次，普通任务至少1次
    min_back_times = min_back_times_search if is_search_task else min_back_times_normal
    had_external_app = False  # 是否经历过外部应用或其他页面

    while back_count < max_back:
        temp_package, temp_activity = get_current_app(d)
        if temp_package is None or temp_activity is None:
            _pause_sleep(0.5)
            continue

        print(f"当前界面: {temp_package}--{temp_activity}")

        # 优先检查：如果已经在任务界面
        if (
            temp_package == "com.taobao.taobao"
            and temp_activity == "com.taobao.themis.container.app.TMSActivity"
        ):
            # 如果之前经历过外部应用或其他页面，至少要后退2次
            if had_external_app and min_back_times < 2:
                min_back_times = 2
                print("检测到之前跳转过其他页面，增加最小后退次数到2次")
            
            if back_count < min_back_times:
                # 还没达到最小后退次数
                print(f"在任务界面，但还需要后退 {min_back_times - back_count} 次确保回到任务列表")
                d.press("back")
                _pause_sleep(1)
                back_count += 1
                continue
            else:
                print(f"✓ 已在任务列表界面，停止后退（共后退 {back_count} 次）")
                break

        # 成功返回到任务列表（使用check_in_task进一步验证）
        if check_in_task():
            print("✓ 成功返回到任务列表")
            break

        # Welcome 界面处理
        if temp_activity == "com.taobao.tao.welcome.Welcome":
            consecutive_welcome += 1
            # 修改策略：如果已经完成了足够的后退次数，直接导航
            if back_count >= min_back_times:
                print(
                    f"⚠ 已后退{back_count}次后到达Welcome界面，直接导航到任务列表"
                )
                to_11(task_type=task_type)
                break
            elif consecutive_welcome >= 2:
                print(
                    f"⚠ 连续检测到 Welcome {consecutive_welcome} 次，停止返回，调用 to_11() 重新导航"
                )
                to_11(task_type=task_type)
                break
            else:
                print(f"检测到 Welcome 界面，再尝试后退一次 (已后退{back_count}次，需要{min_back_times}次)")
                d.press("back")
                _pause_sleep(1)
                back_count += 1

        # 跳转到外部应用处理（支付宝、百度等跳转任务）
        elif "com.taobao.taobao" not in temp_package:
            had_external_app = True  # 标记经历过外部应用
            if temp_activity == "com.bbk.launcher2.Launcher":
                # 到达启动器，说明后退过头了
                print("✗ 到达启动器界面，重新导航")
                to_11(task_type=task_type)
                break
            else:
                # 在外部应用（支付宝、百度等），继续后退尝试回到淘宝
                print(f"检测到外部应用: {temp_package}，继续后退尝试返回淘宝")
                d.press("back")
                _pause_sleep(1.5)  # 等待时间长一些，给应用切换时间
                back_count += 1
                
                # 如果后退多次仍在外部应用，尝试直接启动淘宝
                if back_count >= 5 and "com.taobao.taobao" not in temp_package:
                    print("⚠ 多次后退仍在外部应用，直接启动淘宝重新导航")
                    d.app_start("com.taobao.taobao")
                    _pause_sleep(3)
                    to_11(task_type=task_type)
                    break

        # 淘宝内部其他界面，继续后退
        else:
            # 如果不是TMSActivity，说明在其他淘宝页面（如闪购页等）
            if temp_activity != "com.taobao.themis.container.app.TMSActivity":
                had_external_app = True  # 标记经历过其他页面
            
            print("点击后退")
            d.press("back")
            _pause_sleep(0.5)
            back_count += 1
            consecutive_welcome = 0  # 重置 Welcome 计数

    print(f"返回界面流程完成（执行了 {back_count} 次后退）")


def check_task_progress(target_count=40):
    """检查任务进度是否达到目标次数"""
    # 先尝试滚动到页面顶部，确保进度信息可见
    try:
        d.swipe(screen_width // 2, screen_height // 3, screen_width // 2, screen_height * 2 // 3, 0.3)
        _pause_sleep(1)
    except Exception:
        pass
    
    # 查找"完成进度"或"当前进度"文本
    progress_texts = d(className="android.widget.TextView", textMatches=".*进度.*")

    found_progress = False
    for progress_view in progress_texts:
        try:
            text = progress_view.get_text()

            # 尝试从文本中提取数字，格式可能是 "完成进度 30/40" 或 "当前进度：30/40" 等
            match = re.search(r"(\d+)\s*/\s*(\d+)", text)
            if match:
                found_progress = True
                current = int(match.group(1))
                total = int(match.group(2))
                print(f"[进度检查] 找到进度: {text} → 当前进度: {current}/{total}")

                if current >= target_count:
                    print(f"✓ 已达到目标进度 {target_count}次 ({current}/{total})")
                    return True
                elif current >= total:
                    print(f"✓ 已完成全部进度 ({current}/{total})")
                    return True
                else:
                    print(f"[进度检查] 未达到目标 (当前: {current}, 目标: {target_count})")
                    return False
        except Exception as e:
            print(f"[进度检查] 解析进度失败: {e}")
            continue

    if not found_progress:
        print("[进度检查] ⚠ 未找到进度信息，可能页面未加载完成，继续执行任务")

    # 如果没有找到进度信息，返回 False 继续执行任务（而不是结束）
    return False


# 从配置获取任务参数（在执行任务前加载）
print("\n正在加载任务配置...")
coin_enabled = config.get('task.coin.enabled', True)
coin_target = config.get('task.coin.target_count', 40)
physical_enabled = config.get('task.physical.enabled', True)
physical_target = config.get('task.physical.target_count', 50)
jump_enabled = config.get('task.jump.enabled', True)
jump_min_physical = config.get('task.jump.min_physical', 10)
max_no_task = config.get('retry.max_no_task_count', 3)
wait_between_tasks = config.get('operation.wait_between_tasks', 4)

print(f"  - 金币任务: {'启用' if coin_enabled else '禁用'} (目标: {coin_target}次)")
print(f"  - 体力任务: {'启用' if physical_enabled else '禁用'} (目标: {physical_target}次)")
print(f"  - 跳一跳: {'启用' if jump_enabled else '禁用'} (保留体力: {jump_min_physical})")
print(f"  - 完成等待上限: {config.get('operation.max_wait_duration', 35)}秒")
print(f"  - 任务间等待: {wait_between_tasks}秒")
print("✓ 配置加载完成\n")

# 启动键盘监听线程（空格暂停/继续，q 退出）
_listener_thread = threading.Thread(target=_keyboard_listener, daemon=True)
_listener_thread.start()

to_11()
finish_count = 0
time1 = time.time()

# 第一阶段：完成赚金币任务
if coin_enabled:
    print("\n" + "=" * 60)
    print(f"第一阶段：开始赚金币任务（目标：{coin_target}次）")
    print("=" * 60)

    no_task_count_coin = 0  # 连续未找到任务的计数

    while True:
        try:
            _check_pause()
            # 检查任务进度
            if check_task_progress(coin_target):
                print(f"✓ 金币任务进度已达到{coin_target}次，结束金币任务")
                break

            print("开始查找金币任务。。。")
            get_btn = d(className="android.widget.Button", text="立即领取")
            if get_btn.exists:
                get_btn.click()
                _pause_sleep(3)
            
            # 查找任务按钮（支持多种文本，使用正则匹配）
            # 匹配："去完成"、"去逛逛"、"逛一逛"、"去浏览"、"去查看"等
            to_btn = d(className="android.widget.Button", textMatches="去完成|去逛逛|逛一逛")
            
            if not to_btn.exists:
                print("[金币任务] 未找到任务按钮，尝试向下滚动...")
                d.swipe(screen_width // 2, screen_height * 2 // 3, screen_width // 2, screen_height // 3, 0.3)
                _pause_sleep(2)
                # 滚动后重新查找
                to_btn = d(className="android.widget.Button", textMatches="去完成|去逛逛|逛一逛")
                
                # 如果还找不到，打印当前页面所有按钮文本用于调试
                if not to_btn.exists:
                    print("[金币任务] [调试] 仍未找到任务按钮，打印当前页面所有Button:")
                    all_buttons = d(className="android.widget.Button")
                    for i, btn in enumerate(all_buttons):
                        try:
                            btn_text = btn.get_text()
                            if btn_text and btn_text.strip():
                                print(f"   Button[{i}]: '{btn_text}'")
                        except Exception:
                            pass
            
            if to_btn.exists:
                need_click_view = None
                need_click_index = 0
                task_name = None
                for index, view in enumerate(to_btn):
                    text_div = view.sibling(
                        className="android.view.View", instance=0
                    ).child(className="android.widget.TextView", instance=0)
                    if text_div.exists:
                        if check_chars_exist(text_div.get_text()):
                            continue
                        task_name = text_div.get_text()
                        if task_name in have_clicked:
                            continue
                        need_click_index = index
                        need_click_view = view
                        break
                if need_click_view:
                    print(f"点击金币任务按钮: {task_name}")
                    if task_name not in have_clicked:
                        have_clicked.append(task_name)
                        finish_count += 1
                    d.click(
                        random.randint(
                            need_click_view.bounds()[0] + 10,
                            need_click_view.bounds()[2] - 10,
                        ),
                        random.randint(
                            need_click_view.bounds()[1] + 10,
                            need_click_view.bounds()[3] - 10,
                        ),
                    )
                    _pause_sleep(4)
                    
                    # 检测是否是搜索任务
                    is_search = False
                    search_keyword = config.get('operation.search_keyword', '笔记本电脑')
                    search_view = d(className="android.view.View", text="搜索有福利")
                    if search_view.exists:
                        d(className="android.widget.EditText", instance=0).send_keys(
                            search_keyword
                        )
                        d(className="android.widget.Button", text="搜索").click()
                        is_search = True
                        in_search = True
                        _pause_sleep(4)
                    
                    # 执行任务，传入任务类型
                    operate_task(is_search_task=is_search, task_type="coin")
                    no_task_count_coin = 0  # 重置计数器
                else:
                    no_task_count_coin += 1
                    print(f"[金币任务] 未找到可执行的任务按钮 ({no_task_count_coin}/{max_no_task})")
                    if no_task_count_coin >= max_no_task:
                        print("⚠ 连续多次未找到任务按钮，可能页面状态异常")
                        print("   重新启动淘宝并导航到金币任务界面...")
                        d.app_stop(package_name)
                        _pause_sleep(2)
                        d.app_start(package_name)
                        _pause_sleep(3)
                        to_11(task_type="coin")
                        _pause_sleep(2)
                        # 重置计数器，给一次机会
                        no_task_count_coin = 0
                        continue
                    
                    # 尝试滚动页面查找更多任务
                    print("[金币任务] 尝试滚动页面查找任务...")
                    d.swipe(screen_width // 2, screen_height * 2 // 3, screen_width // 2, screen_height // 3, 0.3)
                    _pause_sleep(2)
            else:
                no_task_count_coin += 1
                print(f"[金币任务] 未找到任务按钮('去完成'/'去逛逛'/'逛一逛') ({no_task_count_coin}/{max_no_task})")
                if no_task_count_coin >= max_no_task:
                    print("⚠ 连续多次未找到任务按钮，可能页面状态异常")
                    print("   重新启动淘宝并导航到金币任务界面...")
                    d.app_stop(package_name)
                    _pause_sleep(2)
                    d.app_start(package_name)
                    _pause_sleep(3)
                    to_11(task_type="coin")
                    _pause_sleep(2)
                    # 重置计数器，给一次机会
                    no_task_count_coin = 0
                    continue
                # 尝试滚动页面
                print("[金币任务] 尝试滚动页面查找任务...")
                d.swipe(screen_width // 2, screen_height * 2 // 3, screen_width // 2, screen_height // 3, 0.3)
                _pause_sleep(2)
            _pause_sleep(wait_between_tasks)
        except Exception as e:
            print("出现异常，继续下一轮", str(e))
else:
    print("金币任务已禁用，跳过")

# 第二阶段：切换到赚体力任务
if physical_enabled:
    print("\n" + "=" * 60)
    print(f"第二阶段：切换到赚体力任务（目标：{physical_target}次）")
    print("=" * 60)

    # 更新当前任务类型为体力任务
    current_task_type = "physical"
    print(f"[切换体力] 已更新任务类型为: {current_task_type}")

    # 检查当前界面，如果在任务界面，先返回到淘金币主页
    temp_package, temp_activity = get_current_app(d)
    print(f"[切换体力] 当前界面: {temp_package}--{temp_activity}")

    if temp_activity == "com.taobao.themis.container.app.TMSActivity":
        print("[切换体力] 在任务列表界面，返回到淘金币主页...")
        d.press("back")
        _pause_sleep(3)
        temp_package, temp_activity = get_current_app(d)
        print(f"[切换体力] 返回后界面: {temp_package}--{temp_activity}")

    # 如果返回到了 Welcome 首页，需要重新点击"领淘金币"进入淘金币页面
    if temp_activity == "com.taobao.tao.welcome.Welcome":
        print("[切换体力] 在 Welcome 首页，需要重新进入淘金币页面...")

        # 查找并点击"领淘金币"
        coin_btn = d(textContains="淘金币")
        if not coin_btn.exists:
            coin_btn = d(description="领淘金币")
        if not coin_btn.exists:
            coin_btn = d(text="领淘金币")

        if coin_btn.exists:
            print("✓ 找到'领淘金币'，点击进入...")
            bounds = coin_btn.info["bounds"]
            center_x = (bounds["left"] + bounds["right"]) // 2
            center_y = (bounds["top"] + bounds["bottom"]) // 2
            d.click(center_x, center_y)
            print(f"   点击坐标: ({center_x}, {center_y})")
            _pause_sleep(4)
        else:
            print("✗ 未找到'领淘金币'，尝试估算位置点击...")
            d.click(screen_width // 2, int(screen_height * 0.35))
            _pause_sleep(4)

    # 现在应该在淘金币主页，查找"赚体力"按钮
    print("[切换体力] 在淘金币页面，查找'赚体力'按钮...")
    _pause_sleep(2)

    # 多种方式查找"赚体力"按钮
    physical_btn = d(text="赚体力")
    if not physical_btn.exists:
        print("[切换体力] text方式未找到，尝试textContains...")
        physical_btn = d(textContains="赚体力")

    if not physical_btn.exists:
        print("[切换体力] 尝试Button类型查找...")
        physical_btn = d(className="android.widget.Button", textMatches=".*赚体力.*")

    if not physical_btn.exists:
        print("[切换体力] 尝试TextView类型查找...")
        physical_btn = d(className="android.widget.TextView", textMatches=".*赚体力.*")

    if physical_btn.exists:
        print("✓ 找到'赚体力'按钮，点击切换到体力任务...")
        bounds = physical_btn.info["bounds"]
        center_x = (bounds["left"] + bounds["right"]) // 2
        center_y = (bounds["top"] + bounds["bottom"]) // 2
        d.click(center_x, center_y)
        print(f"   点击坐标: ({center_x}, {center_y})")
        _pause_sleep(5)

        # 验证是否进入体力任务页面
        pkg, act = get_current_app(d)
        if act == "com.taobao.themis.container.app.TMSActivity":
            print("✓ 成功进入体力任务列表界面")
        else:
            print(f"⚠ 点击后界面: {act}，可能未成功进入")
    else:
        print("✗ 未找到'赚体力'按钮")
        # 打印当前页面所有元素信息用于调试
        print("[切换体力] 打印当前页面元素信息...")
        btns = d(className="android.widget.Button")
        tvs = d(className="android.widget.TextView")
        print(f"   页面有 {len(btns)} 个 Button, {len(tvs)} 个 TextView")
        print("   所有 Button 文本:")
        for i in range(min(10, len(btns))):
            try:
                text = btns[i].get_text()
                if text:
                    print(f"      [Button {i}] {text}")
            except Exception:
                pass
        print("   包含'体力'的 TextView:")
        for tv in tvs:
            try:
                text = tv.get_text()
                if text and "体力" in text:
                    print(f"      TextView: {text}")
            except Exception:
                pass

    # 执行体力任务
    have_clicked_physical = []  # 重置已点击列表，专门用于体力任务
    no_task_count_physical = 0  # 连续未找到任务的计数

    while True:
        try:
            _check_pause()
            # 检查任务进度（体力任务目标）
            if check_task_progress(physical_target):
                print(f"✓ 体力任务进度已达到{physical_target}次，结束体力任务")
                break

            print("开始查找体力任务。。。")
            get_btn = d(className="android.widget.Button", text="立即领取")
            if get_btn.exists:
                get_btn.click()
                _pause_sleep(3)
            
            # 查找任务按钮（支持多种文本，使用正则匹配）
            # 匹配："去完成"、"去逛逛"、"逛一逛"、"去浏览"、"去查看"等
            to_btn = d(className="android.widget.Button", textMatches="去完成|去逛逛|逛一逛")
            
            if not to_btn.exists:
                print("[体力任务] 未找到任务按钮，尝试向下滚动...")
                d.swipe(screen_width // 2, screen_height * 2 // 3, screen_width // 2, screen_height // 3, 0.3)
                _pause_sleep(2)
                # 滚动后重新查找
                to_btn = d(className="android.widget.Button", textMatches="去完成|去逛逛|逛一逛")
                
                # 如果还找不到，打印当前页面所有按钮文本用于调试
                if not to_btn.exists:
                    print("[体力任务] [调试] 仍未找到任务按钮，打印当前页面所有Button:")
                    all_buttons = d(className="android.widget.Button")
                    for i, btn in enumerate(all_buttons):
                        try:
                            btn_text = btn.get_text()
                            if btn_text and btn_text.strip():
                                print(f"   Button[{i}]: '{btn_text}'")
                        except Exception:
                            pass
            
            if to_btn.exists:
                need_click_view = None
                need_click_index = 0
                task_name = None
                for index, view in enumerate(to_btn):
                    text_div = view.sibling(
                        className="android.view.View", instance=0
                    ).child(className="android.widget.TextView", instance=0)
                    if text_div.exists:
                        if check_chars_exist(text_div.get_text()):
                            continue
                        task_name = text_div.get_text()
                        if task_name in have_clicked_physical:
                            continue
                        need_click_index = index
                        need_click_view = view
                        break
                if need_click_view:
                    print(f"点击体力任务按钮: {task_name}")
                    if task_name not in have_clicked_physical:
                        have_clicked_physical.append(task_name)
                        finish_count += 1
                    d.click(
                        random.randint(
                            need_click_view.bounds()[0] + 10,
                            need_click_view.bounds()[2] - 10,
                        ),
                        random.randint(
                            need_click_view.bounds()[1] + 10,
                            need_click_view.bounds()[3] - 10,
                        ),
                    )
                    _pause_sleep(4)
                    
                    # 检测是否是搜索任务
                    is_search = False
                    search_keyword = config.get('operation.search_keyword', '笔记本电脑')
                    search_view = d(className="android.view.View", text="搜索有福利")
                    if search_view.exists:
                        d(className="android.widget.EditText", instance=0).send_keys(
                            search_keyword
                        )
                        d(className="android.widget.Button", text="搜索").click()
                        is_search = True
                        in_search = True
                        _pause_sleep(4)
                    
                    # 执行任务，传入任务类型
                    operate_task(is_search_task=is_search, task_type="physical")
                    no_task_count_physical = 0  # 重置计数器
                else:
                    no_task_count_physical += 1
                    print(f"[体力任务] 未找到可执行的任务按钮 ({no_task_count_physical}/{max_no_task})")
                    if no_task_count_physical >= max_no_task:
                        print("⚠ 连续多次未找到可执行任务，可能页面状态异常")
                        print("   重新启动淘宝并导航到体力任务界面...")
                        d.app_stop(package_name)
                        _pause_sleep(2)
                        d.app_start(package_name)
                        _pause_sleep(3)
                        to_11(task_type="physical")
                        _pause_sleep(2)
                        # 重置计数器，给一次机会
                        no_task_count_physical = 0
                        continue
                    # 尝试滚动页面
                    print("[体力任务] 尝试滚动页面查找更多任务...")
                    d.swipe(screen_width // 2, screen_height * 2 // 3, screen_width // 2, screen_height // 3, 0.3)
                    _pause_sleep(2)
            else:
                no_task_count_physical += 1
                print(f"[体力任务] 未找到任务按钮('去完成'/'去逛逛'/'逛一逛') ({no_task_count_physical}/{max_no_task})")
                if no_task_count_physical >= max_no_task:
                    print("⚠ 连续多次未找到任务按钮，可能页面状态异常")
                    print("   重新启动淘宝并导航到体力任务界面...")
                    d.app_stop(package_name)
                    _pause_sleep(2)
                    d.app_start(package_name)
                    _pause_sleep(3)
                    to_11(task_type="physical")
                    _pause_sleep(2)
                    # 重置计数器，给一次机会
                    no_task_count_physical = 0
                    continue
                
                # 尝试滚动页面查找更多任务
                print("[体力任务] 尝试滚动页面查找任务...")
                d.swipe(screen_width // 2, screen_height * 2 // 3, screen_width // 2, screen_height // 3, 0.3)
                _pause_sleep(2)
            
            _pause_sleep(wait_between_tasks)
        except Exception as e:
            print("出现异常，继续下一轮", str(e))
else:
    print("体力任务已禁用，跳过")

print(f"\n共自动化完成 {finish_count} 个任务")

# 第三阶段：跳一跳
if jump_enabled:
    print("\n" + "=" * 60)
    print("第三阶段：开始跳一跳")
    print("=" * 60)

    temp_btn = d(className="android.widget.TextView", text="做任务赚体力")
    if temp_btn.exists:
        print("点击缩回弹框")
        temp_btn.right(className="android.widget.Button").click()
    _pause_sleep(4)
    while True:
        _check_pause()
        print("开始跳一跳。。。")
        share_view = d(
            className="android.view.View",
            textMatches=r"分享给好友立得体力|去抢频道额外优惠",
        )
        if share_view.exists:
            print("存在分享给好友立得体力弹框，关闭它")
            close_btn = d.xpath(
                '//android.view.View[@text="分享给好友立得体力" or @text="去抢频道额外优惠"]/preceding-sibling::android.view.View[3]'
            )
            if close_btn.exists:
                print("关闭按钮存在，关闭它")
                close_btn.click()
                _pause_sleep(3)
        dump_btn = d(className="android.widget.Button", textContains="跳一跳拿钱")
        if dump_btn.exists:
            dump_text = dump_btn.get_text()
            match = re.search(r"剩余 (\d+) 体力", dump_text)
            if match:
                phy_num = int(match.group(1))
                if phy_num <= jump_min_physical:
                    print(f"剩余体力 {phy_num} 已达到最小保留值 {jump_min_physical}，停止跳一跳")
                    break
                print(f"当前剩余体力：{phy_num}")
                # d.shell(f"input touchscreen swipe {dump_btn.center()[0]} {dump_btn.center()[1]} {dump_btn.center()[0]} {dump_btn.center()[1]} 5000")
                dump_btn.long_click(duration=5)
                _pause_sleep(7)
            else:
                break
        else:
            break
else:
    print("\n跳一跳已禁用，跳过")

d.watcher.remove()
d.shell("settings put system accelerometer_rotation 0")
print("关闭手机自动旋转")
time2 = time.time()
minutes, seconds = divmod(int(time2 - time1), 60)  # 同时计算分钟和秒
print(f"共耗时: {minutes} 分钟 {seconds} 秒")

print("\n" + "=" * 60)
print("✓ 所有任务已完成！")
print("=" * 60)
print("\n按任意键退出程序...")
_listener_stop = True  # 停止键盘监听线程，避免与 input 抢按键
try:
    input()
except (EOFError, KeyboardInterrupt):
    pass
