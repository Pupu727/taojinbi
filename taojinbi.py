#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""淘金币自动化：仅自动完成淘宝「赚金币/赚更多金币」浏览类任务。"""
import sys
import os
from datetime import datetime

# --- 日志：同时输出到控制台和 logs/run_*.log ---
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

import time
import random
import re
import threading
import xml.etree.ElementTree as ET


# ===== 暂停/恢复（空格=暂停/继续，q=退出）=====
_paused = False
_pause_lock = threading.Lock()
_ui_lock = threading.Lock()
_popup_watch_hold = False
_exit_requested = False
_listener_stop = False
_home_entry_clicked = False
_TASK_LIST_MARKERS = ("去完成", "去逛逛", "逛一逛", "去浏览", "去看看")
_BOUNDS_RE = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def _check_pause():
    while _paused and not _exit_requested:
        time.sleep(0.3)
    if _exit_requested:
        print("[退出] 收到退出指令，程序结束", flush=True)
        raise SystemExit(0)


def _pause_sleep(seconds):
    if seconds <= 0:
        return
    end = time.time() + seconds
    while time.time() < end:
        _check_pause()
        remaining = end - time.time()
        time.sleep(min(0.2, max(0.0, remaining)))


def _keyboard_listener():
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
from utils.utils import (
    check_chars_exist,
    get_current_app,
    is_external_jump_task,
    is_quiz_classroom_task,
    is_real_external_app,
    is_taobao_family_package,
)
from utils.config_manager import get_config

print("Loading configuration...", flush=True)
config = get_config()
config.print_config()

print("=" * 60, flush=True)
print("淘金币自动化脚本启动", flush=True)
print("=" * 60)

print("正在连接设备...")
d = u2.connect()
print("✓ 设备连接成功")

# uiautomator2 默认 HTTP 超时 300 秒；淘金币 H5 页 dump/exists 经常不返回，会像卡死。
_RPC_TIMEOUT = 8.0
d.settings["wait_timeout"] = 1.5
_orig_jsonrpc_call = d.jsonrpc_call


def _jsonrpc_call_capped(method, params=None, timeout=10):
    try:
        t = float(timeout)
    except Exception:
        t = _RPC_TIMEOUT
    if t > _RPC_TIMEOUT:
        t = _RPC_TIMEOUT
    return _orig_jsonrpc_call(method, params, t)


d.jsonrpc_call = _jsonrpc_call_capped
print(f"✓ 已限制控件查询超时为 {_RPC_TIMEOUT:.0f} 秒，避免首页卡死")

package_name = config.get("app.package_name", "com.taobao.taobao")
launch_wait_time = config.get("app.launch_wait_time", 5)

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

have_clicked = []

print("准备监视器（进任务列表后再启动，避免首页 H5 dump 卡死）...")
ctx = d.watch_context()
ctx.when("点击刷新").click()
ctx.when(
    xpath="//android.widget.FrameLayout[@resource-id='com.taobao.taobao:id/poplayer_native_state_center_layout_frame_id']/android.widget.ImageView"
).click()
# 不要匹配光「关闭」：任务列表右上角叉叉 content-desc 就是「关闭」，监视器会把列表关掉。
_watch_skip = {
    "允许",
    "始终允许",
    "同意并继续",
    "取消",
    "关闭",
    "跳过",
}
for _wk in (config.get("operation.popup_close_keywords", []) or []):
    if _wk and str(_wk) not in _watch_skip:
        ctx.when(str(_wk)).click()
print("✓ 监视器已注册，导航完成后再开启")
print("=" * 60)
print()


def _popup_overlay_likely(xml=""):
    """只有明确弹层才算遮罩。整页出现「广告」两个字不够，任务列表里到处都是。"""
    if not xml:
        return False
    return "android.app.Dialog" in xml or "poplayer_native_state_center" in xml


def _set_popup_watch_hold(on):
    global _popup_watch_hold
    _popup_watch_hold = bool(on)


def _wait_activity_contains(substr, timeout=8):
    """等 Activity 名称包含 substr。dumpsys，不 dump 控件树。"""
    end = time.time() + timeout
    last = None
    while time.time() < end:
        _check_pause()
        pkg, act = get_current_app(d)
        last = act
        if act and substr in act:
            return True
        _pause_sleep(0.4)
    print(f"[导航] {timeout:.0f}秒内未等到 {substr}，当前: {last}", flush=True)
    return False


def _dump_xml(timeout=6.0, quiet=False):
    """读控件树，HTTP 超时后立刻放弃，不再重启 uiautomator（重启更容易卡死）。"""
    acquired = _ui_lock.acquire(timeout=1.0)
    if not acquired:
        if not quiet:
            print("[界面] 设备正被占用，跳过本次读页", flush=True)
        return None
    try:
        if not quiet:
            print(f"[界面] 正在读取页面（最多 {timeout:.0f} 秒）...", flush=True)
        max_depth = int(d.settings["max_depth"] or 50)
        xml = d.jsonrpc.dumpWindowHierarchy(False, max_depth, http_timeout=float(timeout))
        xml = xml or ""
        if not quiet:
            print(f"[界面] 页面已读取（{len(xml)} 字符）", flush=True)
        return xml
    except Exception as e:
        print(f"[界面] 读页面失败或超时: {e}", flush=True)
        return None
    finally:
        _ui_lock.release()


def _iter_xml_nodes(xml):
    if not xml:
        return
    raw = xml
    start = raw.find("<hierarchy")
    if start < 0:
        start = raw.find("<node")
    if start > 0:
        raw = raw[start:]
    try:
        root = ET.fromstring(raw)
    except ET.ParseError:
        return
    for n in root.iter():
        yield n


def _node_label(node):
    return (node.attrib.get("text") or node.attrib.get("content-desc") or "").strip()


def _bounds_center(bounds_str):
    m = _BOUNDS_RE.search(bounds_str or "")
    if not m:
        return None
    left, top, right, bottom = map(int, m.groups())
    if right <= left or bottom <= top:
        return None
    return (left + right) // 2, (top + bottom) // 2


def _looks_like_goods_card(text):
    """商品卡常带价格/已抵，不能当签到或赚金币入口。"""
    if not text:
        return False
    marks = ("¥", "￥", "已抵", "已售", "icon_arrow", "再赚", "低至", "爆款", "限时领取")
    return any(m in text for m in marks)


def _xml_locate_keyword(
    xml, keywords, ignore_in=None, max_text_len=20, upper_only=False
):
    """在 XML 里找短文案按钮，返回 (cx, cy, text, key)，不点击。"""
    ignore_in = [str(x) for x in (ignore_in or []) if x]
    keywords = [str(k) for k in (keywords or []) if k]
    best = None
    for node in _iter_xml_nodes(xml):
        text = _node_label(node)
        if not text:
            continue
        if _looks_like_goods_card(text):
            continue
        if len(text) > max_text_len:
            continue
        if any(ig and ig in text for ig in ignore_in):
            continue
        for idx, key in enumerate(keywords):
            if key not in text:
                continue
            bounds = node.attrib.get("bounds") or ""
            center = _bounds_center(bounds)
            if not center:
                break
            cx, cy = center
            if upper_only and cy > screen_height * 0.72:
                break
            m = _BOUNDS_RE.search(bounds)
            area = 0
            if m:
                left, top, right, bottom = map(int, m.groups())
                area = max(0, right - left) * max(0, bottom - top)
            if area > screen_width * screen_height * 0.35:
                break
            clickable = node.attrib.get("clickable") == "true"
            exact = 0 if text == key else 1
            score = (idx, exact, len(text), area, 0 if clickable else 1)
            if best is None or score < best[0]:
                best = (score, center, text, key)
            break
    if best is None:
        return None
    _, (cx, cy), text, key = best
    return cx, cy, text, key


def _xml_click_keyword(
    xml, keywords, ignore_in=None, tag="", max_text_len=20, upper_only=False
):
    """在已 dump 的 XML 里找短文案按钮并点中心。优先精确、短、小、可点。"""
    hit = _xml_locate_keyword(
        xml,
        keywords,
        ignore_in=ignore_in,
        max_text_len=max_text_len,
        upper_only=upper_only,
    )
    if hit is None:
        return False, None
    cx, cy, text, key = hit
    print(f"[{tag}] 点击「{text}」（匹配 {key}）({cx},{cy})", flush=True)
    d.click(cx, cy)
    return True, text


def _dismiss_jump_popups(aggressive_x=False):
    """关掉广告/授权弹窗：读一次 XML 再点，避免 exists() 在 H5 页卡死。"""
    xml = _dump_xml(timeout=5, quiet=True)
    if not xml:
        return False
    return _dismiss_from_xml(xml, aggressive_x=aggressive_x)


def _dismiss_from_xml(xml, aggressive_x=False):
    # 任务列表右上角叉叉文案就是「关闭」；一旦点了就把列表关掉，绝不能当弹窗处理
    if any(k in xml for k in _TASK_LIST_MARKERS):
        print("[弹窗] 已在任务列表（有去完成/去逛逛），不点关闭", flush=True)
        return False

    keys = [
        str(x)
        for x in (config.get("operation.popup_close_keywords", []) or [])
        if x and str(x) not in ("关闭", "跳过")  # 过宽，会误关任务列表
    ]
    # 外跳广告仍可用更明确的词
    if not keys:
        keys = ["关闭广告", "跳过广告", "我知道了", "知道了", "暂不", "以后再说"]
    ignore = ["去完成", "去逛逛", "立即领取", "赚金币", "签到领金币", "赚更多金币"]
    ok, text = _xml_click_keyword(xml, keys, ignore_in=ignore, tag="弹窗")
    if ok:
        _pause_sleep(0.4)
        return True

    # 必须描述/id 明确是关闭，不能把右上角任意小图标当叉叉
    desc_keys = [str(x) for x in (config.get("operation.popup_close_desc_keywords", []) or []) if x]
    id_pats = [str(x).lower() for x in (config.get("operation.popup_close_id_patterns", []) or []) if x]
    for node in _iter_xml_nodes(xml):
        desc = (node.attrib.get("content-desc") or "").strip()
        rid = (node.attrib.get("resource-id") or "").lower()
        hit = None
        if desc and desc in desc_keys:
            hit = f"desc={desc}"
        if hit is None:
            rid_tail = rid.split("/")[-1] if rid else ""
            for pat in id_pats:
                if pat and (rid_tail == pat or rid_tail.endswith("_" + pat)):
                    hit = f"id={rid}"
                    break
        if not hit:
            continue
        center = _bounds_center(node.attrib.get("bounds") or "")
        if not center:
            continue
        print(f"[弹窗] 点击叉叉（{hit}）{center}", flush=True)
        d.click(*center)
        _pause_sleep(0.4)
        return True

    # 猜右上角小图标极易误点任务页按钮，只在外跳页允许
    if not aggressive_x:
        return False
    return _dismiss_x_corner_from_xml(xml)


def _dismiss_x_corner_from_xml(xml):
    xcfg = config.get("operation.popup_x", {}) or {}
    if isinstance(xcfg, dict):
        enabled = xcfg.get("enabled", True)
        min_size = int(xcfg.get("min_size", 28))
        max_size = int(xcfg.get("max_size", 160))
        right_ratio = float(xcfg.get("right_ratio", 0.55))
        top_ratio = float(xcfg.get("top_ratio", 0.50))
    else:
        enabled, min_size, max_size = True, 28, 160
        right_ratio, top_ratio = 0.55, 0.50
    if not enabled:
        return False

    candidates = []
    for node in _iter_xml_nodes(xml):
        cls = node.attrib.get("class") or ""
        if cls not in (
            "android.widget.ImageView",
            "android.widget.ImageButton",
            "android.widget.Button",
        ):
            continue
        if node.attrib.get("clickable") != "true":
            continue
        bounds = node.attrib.get("bounds") or ""
        m = _BOUNDS_RE.search(bounds)
        if not m:
            continue
        left, top, right, bottom = map(int, m.groups())
        w, h = right - left, bottom - top
        if w < min_size or h < min_size or w > max_size or h > max_size:
            continue
        cx, cy = (left + right) // 2, (top + bottom) // 2
        if cx < screen_width * right_ratio or cy > screen_height * top_ratio:
            continue
        if w > 3 * h and w > 120:
            continue
        text = (node.attrib.get("text") or node.attrib.get("content-desc") or "")
        if any(bad in text for bad in ("分享", "搜索", "更多", "菜单")):
            continue
        candidates.append((cy, -cx, w, h, cx, cy))
    if not candidates:
        return False
    candidates.sort()
    _, _, w, h, cx, cy = candidates[0]
    print(f"[弹窗] 点击右上角疑似叉叉 ({cx},{cy}) size={w}x{h}", flush=True)
    d.click(cx, cy)
    _pause_sleep(0.4)
    return True


def check_in_task():
    """是否在淘金币任务列表页。搜索/精选好物等也是 TMSActivity，不能只靠 Activity 名。"""
    return is_on_coin_task_list()


def is_on_coin_task_list(xml=None):
    """真正的任务列表：能看到「去完成 / 去逛逛 / 逛一逛」。可传入已 dump 的 xml，避免再读一遍。"""
    if xml is None:
        xml = _dump_xml(timeout=4, quiet=True)
    if not xml:
        return False
    return any(k in xml for k in _TASK_LIST_MARKERS)


def is_search_like_task(task_name):
    if not task_name:
        return False
    keys = ("搜一搜", "搜索有福利", "搜一搜你心仪")
    return any(k in task_name for k in keys)


def _do_search_task_flow():
    """
    搜一搜：优先点搜索框下方的提示词；没有提示词再输入配置里的兜底关键词。
    成功进入搜索结果返回 True。
    """
    fallback = str(config.get("operation.search_keyword", "笔记本电脑") or "笔记本电脑")
    edit = d(className="android.widget.EditText", instance=0)
    if not edit.exists(timeout=1.0):
        print("[搜索] 没找到搜索输入框", flush=True)
        return False

    edit.click()
    _pause_sleep(0.8)

    # 点开输入框后，收集搜索栏下方的可点提示词
    ban = {
        "搜索",
        "取消",
        "清空",
        "历史搜索",
        "猜你想搜",
        "热门搜索",
        "搜索发现",
        "淘宝搜索",
        "搜索有福利",
        "搜一搜",
    }
    candidates = []
    try:
        # 输入框大致在上半屏；提示词一般在它下面
        edit_info = edit.info
        eb = edit_info.get("bounds") or {}
        edit_bottom = int(eb.get("bottom", int(screen_height * 0.2)))
        nodes = d(className="android.widget.TextView")
        n = min(len(nodes), 40)
        for i in range(n):
            try:
                node = nodes[i]
                t = (node.get_text() or "").strip()
                if not t or t in ban or len(t) < 2 or len(t) > 18:
                    continue
                if any(x in t for x in ("搜索", "历史", "发现", "清空", "取消")):
                    continue
                info = node.info
                b = info.get("bounds") or {}
                top = int(b.get("top", 0))
                left = int(b.get("left", 0))
                right = int(b.get("right", 0))
                bottom = int(b.get("bottom", 0))
                if top < edit_bottom - 20:
                    continue
                if top > screen_height * 0.72:
                    continue
                if right - left < 40 or bottom - top < 24:
                    continue
                cx = (left + right) // 2
                cy = (top + bottom) // 2
                candidates.append((t, cx, cy))
            except Exception:
                continue
    except Exception as e:
        print(f"[搜索] 收集提示词失败: {e}", flush=True)

    # 去重保序
    uniq = []
    seen = set()
    for t, cx, cy in candidates:
        if t in seen:
            continue
        seen.add(t)
        uniq.append((t, cx, cy))


    if uniq:
        t, cx, cy = random.choice(uniq)
        print(f"[搜索] 随机点提示词「{t}」({cx},{cy})，候选 {len(uniq)} 个", flush=True)
        d.click(cx, cy)
        _pause_sleep(2.5)
        return True

    print(f"[搜索] 没有可用提示词，兜底输入: {fallback}", flush=True)
    try:
        edit.click()
        _pause_sleep(0.3)
        edit.send_keys(fallback)
    except Exception:
        try:
            d.send_keys(fallback)
        except Exception as e:
            print(f"[搜索] 输入失败: {e}", flush=True)
            return False
    search_btn = d(className="android.widget.Button", text="搜索")
    if not search_btn.exists(timeout=0.5):
        search_btn = d(text="搜索")
    if search_btn.exists(timeout=0.4):
        search_btn.click()
    else:
        d.press("enter")
    _pause_sleep(2.5)
    return True


def _xml_debug_labels(xml, needles=("签", "金币", "赚")):
    seen = []
    for node in _iter_xml_nodes(xml):
        t = _node_label(node)
        if t and any(n in t for n in needles) and t not in seen:
            seen.append(t)
        if len(seen) >= 15:
            break
    if seen:
        print(f"[界面] 相关文案: {seen}", flush=True)
    else:
        print("[界面] XML 里没看到签到/金币相关文案", flush=True)


def _checkin_keys():
    keys = [str(x) for x in (config.get("operation.checkin_keywords", []) or []) if x]
    return keys or ["立即签到", "今日签到", "签到领金币"]


def _entry_keys():
    keys = [str(x) for x in (config.get("operation.coin_entry_keywords", []) or []) if x]
    return keys or ["赚更多金币", "去赚金币", "赚金币"]


def _xml_looks_like_coin_home(xml):
    if not xml:
        return False
    if any(k in xml for k in _TASK_LIST_MARKERS):
        return False
    return any(
        k in xml
        for k in ("淘金币首页", "已帮你自动签到", "签到领金币", "提醒我来领淘金币")
    )


def _click_homepage_cta(xml, tag):
    """同一颗主按钮：没签是签到领金币，签完是赚更多金币。任务列表里即使还有这四个字也绝不能再点。"""
    if any(k in xml for k in _TASK_LIST_MARKERS):
        print(f"[{tag}] 已经能看到去完成/去逛逛，不再点赚更多金币", flush=True)
        return "on_list", None, None
    ignore = [str(x) for x in (config.get("operation.checkin_ignore_in_text", []) or []) if x]
    hit = _xml_locate_keyword(
        xml, _checkin_keys(), ignore_in=ignore, max_text_len=10, upper_only=True
    )
    if hit:
        cx, cy, text, key = hit
        print(f"[{tag}] 主按钮还是「{text}」，先签到 ({cx},{cy})", flush=True)
        d.click(cx, cy)
        return "checkin", (cx, cy), text
    hit = _xml_locate_keyword(
        xml, _entry_keys(), ignore_in=["签到", "再赚"], max_text_len=12, upper_only=True
    )
    if hit:
        cx, cy, text, key = hit
        print(f"[{tag}] 主按钮已是「{text}」，进入任务列表 ({cx},{cy})", flush=True)
        d.click(cx, cy)
        return "entry", (cx, cy), text
    return None, None, None


def _wait_cta_become_entry(saved_xy, wait_seconds=2.5):
    """
    签到后不要再 dump 控件树：H5 读节点会把页面滑走，主按钮就跑到上面去了。
    同一颗按钮还在原坐标，文案会自己变成「赚更多金币」，等一下再点原位置。
    """
    if not saved_xy:
        print("[导航] 没有记下主按钮坐标，无法再点赚更多金币", flush=True)
        return False
    print(
        f"[签到] 等 {wait_seconds:.0f} 秒让按钮变成「赚更多金币」（不再读屏，避免下滑）",
        flush=True,
    )
    _pause_sleep(wait_seconds)
    print(f"[导航] 在原位置再点一次 {saved_xy}（现在应是赚更多金币）", flush=True)
    d.click(saved_xy[0], saved_xy[1])
    return True


def _enter_tasks_from_coin_home():
    """
    首页主按钮只点一次。点进任务列表后再点「赚更多金币」会把列表关掉。
    点完后不再 dump 校验，避免 H5 被读屏退回首页。
    """
    global _home_entry_clicked
    print("[导航] 看首页主按钮：签到领金币 / 赚更多金币", flush=True)

    if _home_entry_clicked:
        print("[导航] 已经点过赚更多金币，不再点，以免把任务列表关掉", flush=True)
        _pause_sleep(2)
        return True

    _pause_sleep(1.5)
    xml = _dump_xml(timeout=6)
    if not xml:
        print("[导航] 读不到首页", flush=True)
        return False
    _xml_debug_labels(xml)

    if any(k in xml for k in _TASK_LIST_MARKERS):
        print("✓ 已在任务列表（看到去完成/去逛逛），不点赚更多金币", flush=True)
        _home_entry_clicked = True
        return True

    state, xy, text = _click_homepage_cta(xml, "导航")
    if state == "on_list":
        _home_entry_clicked = True
        return True
    if state is None:
        if _xml_looks_like_coin_home(xml):
            print("[导航] 像首页但没点到主按钮（不盲点）", flush=True)
            return False
        print("[导航] 没找到主按钮，且不像首页；可能已在任务列表，不再乱点", flush=True)
        _home_entry_clicked = True
        return True

    if state == "checkin":
        if not _wait_cta_become_entry(xy, wait_seconds=2.5):
            return False
        _home_entry_clicked = True
        print("[导航] 已签到并点过原位置，等待任务列表（不再读屏、不再点第二次）", flush=True)
        _pause_sleep(4)
        return True

    _home_entry_clicked = True
    print("[导航] 已点击「赚更多金币」，等待任务列表（不再读屏、不再点第二次）", flush=True)
    _pause_sleep(4)
    return True


def _reopen_task_list():
    """列表被关掉后，只点「赚更多金币」，不要 dump、不要往下滑首页。"""
    global _home_entry_clicked
    _home_entry_clicked = False
    try:
        el = d(text="赚更多金币")
        if not el.exists(timeout=1):
            print("[淘金币] 找不到「赚更多金币」，无法重开列表", flush=True)
            return False
        b = (el.info.get("bounds") or {})
        cx = (int(b.get("left", 0)) + int(b.get("right", 0))) // 2
        cy = (int(b.get("top", 0)) + int(b.get("bottom", 0))) // 2
        print(f"[淘金币] 重开任务列表 ({cx},{cy})", flush=True)
        d.click(cx, cy)
        _home_entry_clicked = True
        _pause_sleep(4)
        return True
    except Exception as e:
        print(f"[淘金币] 重开列表失败: {e}", flush=True)
        return False


def _click_center(elem, label=""):
    try:
        info = elem.info
        if callable(info):
            info = info()
        bounds = info["bounds"]
        center_x = (bounds["left"] + bounds["right"]) // 2
        center_y = (bounds["top"] + bounds["bottom"]) // 2
        d.click(center_x, center_y)
        if label:
            print(f"   点击{label}: ({center_x}, {center_y})")
        return
    except Exception:
        pass
    try:
        elem.click()
        if label:
            print(f"   点击{label}(elem.click)")
    except Exception as e:
        print(f"   点击{label}失败: {e}")


def _dump_page_buttons(prefix=""):
    btns = d(className="android.widget.Button")
    tvs = d(className="android.widget.TextView")
    print(f"{prefix}页面有 {len(btns)} 个 Button, {len(tvs)} 个 TextView")
    print(f"{prefix}Button 文本:")
    for i in range(min(20, len(btns))):
        try:
            text = btns[i].get_text()
            if text:
                print(f"   [Button {i}] {text}")
        except Exception:
            pass
    print(f"{prefix}TextView 文本(前20个):")
    for i in range(min(20, len(tvs))):
        try:
            text = tvs[i].get_text()
            if text:
                print(f"   [TextView {i}] {text}")
        except Exception:
            pass


def navigate_to_coin_tasks():
    """导航到淘金币任务列表。"""
    global _home_entry_clicked
    _home_entry_clicked = False
    print("开始导航到淘金币任务列表...")
    _set_popup_watch_hold(True)
    max_attempts = config.get("retry.navigation_max_attempts", 5)
    attempt = 0

    try:
        _navigate_to_coin_tasks_inner(max_attempts)
    finally:
        _set_popup_watch_hold(False)


def _navigate_to_coin_tasks_inner(max_attempts):
    attempt = 0
    while attempt < max_attempts:
        pkg, act = get_current_app(d)
        print(f"[导航] 当前界面: {pkg}--{act}", flush=True)

        if (
            pkg == "com.taobao.taobao"
            and act == "com.taobao.themis.container.app.TMSActivity"
        ):
            # TMSActivity 可能是首页也可能是任务列表；不要先 dump 再点赚更多金币（会把列表关掉）
            print("[导航] 在淘金币容器页，处理首页主按钮...")
            if _enter_tasks_from_coin_home():
                break
            attempt += 1
            _pause_sleep(1)
            continue

        if pkg == "com.taobao.taobao" and act == "com.taobao.tao.welcome.Welcome":
            print("[导航] 在 Welcome 首屏，寻找「领淘金币」...")
            _pause_sleep(2)

            coin_btn = d(description="领淘金币")
            if not coin_btn.exists(timeout=1.5):
                coin_btn = d(text="领淘金币")
            if not coin_btn.exists(timeout=1.0):
                coin_btn = d(textContains="淘金币")
            if not coin_btn.exists(timeout=1.0):
                coin_btn = d(className="android.widget.TextView", textMatches=".*淘金币.*")

            if coin_btn.exists(timeout=0.8):
                print("✓ 找到「领淘金币」，点击...")
                _click_center(coin_btn, "领淘金币")
                print("[导航] 等待进入淘金币首页...", flush=True)
                _wait_activity_contains("TMSActivity", timeout=8)
                if _enter_tasks_from_coin_home():
                    break
                attempt += 1
            else:
                print("✗ 未找到「领淘金币」，稍后重试，不盲点")
                xml = _dump_xml(timeout=5)
                if xml and "淘金币" in xml:
                    print("   XML 中有「淘金币」关键字")
                else:
                    print("   XML 中没有「淘金币」关键字")
                _pause_sleep(2)
                attempt += 1

        elif pkg == "com.taobao.taobao":
            print(f"[导航] 在其他淘宝界面: {act}，重启淘宝...")
            d.app_start("com.taobao.taobao", stop=False)
            _pause_sleep(5)
            attempt += 1
        else:
            print(f"[导航] 非淘宝应用: {pkg}，启动淘宝...")
            d.app_start("com.taobao.taobao", stop=False)
            _pause_sleep(5)
            attempt += 1

        if 0 < attempt < max_attempts:
            print(f"[导航] 第 {attempt}/{max_attempts} 次尝试...")
            _pause_sleep(2)

    if attempt >= max_attempts:
        print("⚠ 导航到任务界面达到最大尝试次数")
    else:
        print("✓ 成功导航到淘金币任务列表")


def human_like_swipe():
    """模拟真人上滑：大幅程 + 多点贝塞尔；速度在配置区间内随机（可快于原先手感）。"""
    dur_min = config.get("operation.human_swipe.duration_min", 0.06)
    dur_max = config.get("operation.human_swipe.duration_max", 0.14)
    jitter_prob = config.get("operation.human_swipe.jitter_probability", 0.08)
    dist_min = config.get("operation.human_swipe.distance_ratio_min", 0.68)
    dist_max = config.get("operation.human_swipe.distance_ratio_max", 0.88)
    steps_min = int(config.get("operation.human_swipe.steps_min", 5))
    steps_max = int(config.get("operation.human_swipe.steps_max", 8))

    # 从更靠下起滑到更靠上，幅度更大
    margin_x = max(40, screen_width // 10)
    start_x = random.randint(margin_x, screen_width - margin_x)
    start_y = random.randint(int(screen_height * 0.78), int(screen_height * 0.94))
    distance = int(screen_height * random.uniform(dist_min, dist_max))
    end_y = max(int(screen_height * 0.06), start_y - distance)
    end_x = max(
        margin_x,
        min(screen_width - margin_x, start_x + random.randint(-int(screen_width * 0.12), int(screen_width * 0.12))),
    )
    ctrl_x = (start_x + end_x) // 2 + random.randint(-int(screen_width * 0.06), int(screen_width * 0.06))
    ctrl_y = (start_y + end_y) // 2 + random.randint(-40, 40)

    steps = random.randint(max(4, steps_min), max(steps_min, steps_max))
    points = []
    for i in range(steps):
        t = i / (steps - 1)
        x = (1 - t) ** 2 * start_x + 2 * (1 - t) * t * ctrl_x + t ** 2 * end_x
        y = (1 - t) ** 2 * start_y + 2 * (1 - t) * t * ctrl_y + t ** 2 * end_y
        points.append((int(x), int(y)))

    # duration 越小越快；区间内随机
    if dur_max < dur_min:
        dur_min, dur_max = dur_max, dur_min
    duration = random.uniform(dur_min, dur_max)
    print(
        f"模拟滑动(流畅曲线) {points[0]} -> {points[-1]} "
        f"幅度约{start_y - end_y}px / {duration:.2f}s / {steps}点"
    )
    d.swipe_points(points, duration)

    if random.random() < jitter_prob:
        jx = random.randint(margin_x, screen_width - margin_x)
        jy = random.randint(int(screen_height * 0.35), int(screen_height * 0.55))
        d.swipe(jx, jy, jx + random.randint(-8, 8), jy + random.randint(-12, 12), 0.05)


_COUNTDOWN_RE = re.compile(
    r"(浏览\s*[1-9]\d*\s*秒|[1-9]\d*\s*秒.*(完成任务|可领|得金币))"
)


def _completion_keywords():
    keys = list(config.get("operation.completion_keywords", []) or [])
    if not keys:
        keys = [
            "已完成任务",
            "任务已完成",
            "已完成浏览",
            "浏览已完成",
            "浏览完成",
            "已得",
            "已到账",
            "已获得",
            "金币已到账",
            "领取成功",
        ]
    return [str(k) for k in keys if k]


def _looks_like_countdown(text):
    """「浏览15秒完成任务」这类倒计时气泡，不算已经完成。"""
    if not text:
        return False
    if "已完成" in text or "已得" in text or "已到账" in text:
        return False
    return bool(_COUNTDOWN_RE.search(text))


def _looks_like_done(text, keywords=None):
    if not text:
        return False
    if _looks_like_countdown(text):
        return False
    keys = keywords if keywords is not None else _completion_keywords()
    for k in keys:
        if k and k in text:
            return True
    # 浏览 0 秒：倒计时走完
    if re.search(r"浏览\s*0\s*秒", text):
        return True
    return False


def detect_task_completion(use_hierarchy=True):
    """
    多路识别完成提示。
    精选好物/沉浸看顶栏「已得XX」经常不在 text 选择器里，必须扫控件树。
    浏览页可以 dump；任务列表弹层不要在选任务时 dump。
    """
    keywords = _completion_keywords()
    fast_keys = ("已完成任务", "任务已完成", "已得", "已到账", "已获得", "浏览完成", "领取成功", "金币已到账")

    for key in fast_keys:
        try:
            node = d(textContains=key)
            if not node.exists(timeout=0.05):
                continue
            t = ""
            try:
                t = node.get_text() or ""
            except Exception:
                t = key
            if _looks_like_done(t or key, keywords):
                return True, f"contains:{key}"
        except Exception:
            continue

    # 顶栏有时只在 content-desc
    for key in ("已得", "已到账", "已完成任务", "任务已完成"):
        try:
            if d(descriptionContains=key).exists(timeout=0.05):
                return True, f"desc:{key}"
        except Exception:
            continue

    # 「已得12」这类数字拼在一起
    try:
        if d(textMatches=r".*已得\s*\d+.*").exists(timeout=0.08):
            return True, "textMatches:已得N"
    except Exception:
        pass

    if use_hierarchy:
        try:
            xml = _dump_xml(timeout=2.5, quiet=True) or ""
            has_yide = "已得" in xml
            has_done = any(k in xml for k in ("已完成任务", "任务已完成", "已到账", "已获得"))
            if not xml:
                return False, None
            # 倒计时文案里也可能带「完成任务」，但不能挡住真正的「已得」
            if has_yide or has_done:
                if _looks_like_done(xml, keywords):
                    return True, "hierarchy"
                # 控件树里有「已得」但被倒计时误伤时，仍算完成
                if has_yide and not _looks_like_countdown(xml):
                    return True, "hierarchy:已得"
                if has_yide:
                    # 同页既有倒计时又有已得：以已得为准
                    return True, "hierarchy:已得优先"
            if _looks_like_countdown(xml):
                return False, None
            if _looks_like_done(xml, keywords):
                return True, "hierarchy"
        except Exception:
            pass

    return False, None


def return_to_task_list(is_search_task=False, force_external=False):
    """
    返回淘金币任务列表。
    搜索/精选好物/沉浸看都可能仍是 TMSActivity，必须以「去完成」按钮判断是否真回到列表。
    """
    print("开始返回界面")
    max_back = config.get("retry.max_back_times", 10)
    min_back_times_search = config.get("retry.min_back_times_search", 2)
    min_back_times_normal = config.get("retry.min_back_times_normal", 1)
    back_count = 0
    consecutive_welcome = 0
    min_back_times = min_back_times_search if is_search_task else min_back_times_normal
    left_taobao_family = bool(force_external)

    if force_external and min_back_times < 2:
        min_back_times = 2

    while back_count < max_back:
        temp_package, temp_activity = get_current_app(d)
        if temp_package is None or temp_activity is None:
            _pause_sleep(0.5)
            continue

        print(f"当前界面: {temp_package}--{temp_activity}")

        # 先看是否已在任务列表（用 exists，不要 dump：dump 也会把 H5 列表弹层关掉）
        on_list = _task_list_open()
        if on_list and back_count >= min_back_times:
            print(f"✓ 已回到任务列表（看到去完成/去逛逛，共后退 {back_count} 次）")
            break
        if on_list and back_count == 0:
            print("⚠ 当前就能看到任务按钮，可能未进入子页，不再后退")
            return False
        if on_list and back_count > 0:
            print(f"✓ 已回到任务列表（共后退 {back_count} 次）")
            break

        # 不在列表上才清广告；列表页绝对不要点「关闭」
        if is_real_external_app(temp_package):
            _dismiss_jump_popups(aggressive_x=True)
        else:
            _dismiss_text_popups()

        on_list = _task_list_open()
        if on_list and back_count > 0:
            print(f"✓ 已回到任务列表（清弹窗后确认，共后退 {back_count} 次）")
            break

        if temp_activity == "com.taobao.tao.welcome.Welcome":
            consecutive_welcome += 1
            if back_count >= min_back_times or consecutive_welcome >= 2:
                print("⚠ 到达 Welcome，重新导航到淘金币任务列表")
                navigate_to_coin_tasks()
                break
            print(f"检测到 Welcome，再后退一次 (已后退{back_count}次)")
            d.press("back")
            _pause_sleep(1)
            back_count += 1

        elif is_real_external_app(temp_package):
            left_taobao_family = True
            if temp_activity == "com.bbk.launcher2.Launcher":
                print("✗ 到达启动器界面，重新导航")
                navigate_to_coin_tasks()
                break
            print(f"检测到外部应用: {temp_package}，继续后退")
            d.press("back")
            _pause_sleep(1.5)
            back_count += 1
            if back_count >= 5 and is_real_external_app(temp_package):
                print("⚠ 多次后退仍在外部应用，直接启动淘宝重新导航")
                d.app_start("com.taobao.taobao")
                _pause_sleep(3)
                navigate_to_coin_tasks()
                break
        else:
            # 仍在淘宝家族内的二级页（沉浸看、商品页等）：只 back，不标记外跳
            print(f"淘宝内页面 ({temp_activity})，点击后退")
            d.press("back")
            _pause_sleep(0.8)
            back_count += 1
            consecutive_welcome = 0

    print(f"返回界面流程完成（执行了 {back_count} 次后退）")
    return _task_list_open()


def _confirm_external_leave(samples=2, interval=0.35):
    """连续多次确认当前焦点真是外部 App，避免气泡/浮层导致的单次误判。"""
    hits = 0
    last_pkg = None
    for _ in range(samples):
        pkg, _ = get_current_app(d)
        last_pkg = pkg
        if is_real_external_app(pkg):
            hits += 1
        else:
            return False, pkg
        _pause_sleep(interval)
    return hits >= samples, last_pkg


def _is_nav_or_noise_option(text):
    if not text:
        return True
    t = text.strip()
    if len(t) < 1:
        return True
    noise = (
        "返回",
        "关闭",
        "取消",
        "确定",
        "提交",
        "下一题",
        "上一题",
        "去完成",
        "去逛逛",
        "逛一逛",
        "立即领取",
        "搜索",
        "分享",
        "规则",
        "说明",
        "淘金币",
        "赚金币",
        "赚更多",
    )
    return any(n in t for n in noise)


def click_classroom_option():
    """
    趣味课堂：识别屏幕上的选项，随机点一个。
    优先 RadioButton / 选项 Button / 可选 TextView。
    """
    print("[趣味课堂] 等待题目加载...")
    _pause_sleep(2.5)

    candidates = []

    # 1) RadioButton
    try:
        radios = d(className="android.widget.RadioButton")
        for i in range(min(len(radios), 12)):
            try:
                rb = radios[i]
                if not rb.exists:
                    continue
                info = rb.info
                text = (info.get("text") or info.get("contentDescription") or "").strip()
                bounds = info.get("bounds") or {}
                cy = (bounds.get("top", 0) + bounds.get("bottom", 0)) // 2
                if not (screen_height * 0.2 < cy < screen_height * 0.85):
                    continue
                if text and _is_nav_or_noise_option(text):
                    continue
                candidates.append((rb, text or f"Radio[{i}]"))
            except Exception:
                continue
    except Exception:
        pass

    # 2) 像 A/B/C/D 或较长文案的 Button
    try:
        btns = d(className="android.widget.Button")
        for i in range(min(len(btns), 20)):
            try:
                btn = btns[i]
                if not btn.exists:
                    continue
                text = (btn.get_text() or "").strip()
                if _is_nav_or_noise_option(text):
                    continue
                # 选项常见：单字母、A. xxx、或一句答案
                if (
                    re.match(r"^[A-Da-d]([.\s、].*)?$", text)
                    or (2 <= len(text) <= 40)
                ):
                    bounds = btn.info.get("bounds") or {}
                    cy = (bounds.get("top", 0) + bounds.get("bottom", 0)) // 2
                    if screen_height * 0.18 < cy < screen_height * 0.88:
                        candidates.append((btn, text))
            except Exception:
                continue
    except Exception:
        pass

    # 3) 可点击 TextView（题目选项常是 TextView）
    if not candidates:
        try:
            tvs = d(className="android.widget.TextView", clickable=True)
            for i in range(min(len(tvs), 20)):
                try:
                    tv = tvs[i]
                    if not tv.exists:
                        continue
                    text = (tv.get_text() or "").strip()
                    if _is_nav_or_noise_option(text):
                        continue
                    if len(text) < 1 or len(text) > 60:
                        continue
                    bounds = tv.info.get("bounds") or {}
                    cy = (bounds.get("top", 0) + bounds.get("bottom", 0)) // 2
                    if screen_height * 0.22 < cy < screen_height * 0.85:
                        candidates.append((tv, text))
                except Exception:
                    continue
        except Exception:
            pass

    # 4) 兜底：带 A/B/C/D 前缀的任意 TextView（哪怕 clickable=false，点坐标）
    if not candidates:
        try:
            for letter in ("A", "B", "C", "D", "a", "b", "c", "d"):
                node = d(textMatches=rf"^{letter}([.\s、].*)?$")
                if node.exists:
                    candidates.append((node, node.get_text() or letter))
        except Exception:
            pass

    if not candidates:
        print("[趣味课堂] ⚠ 未识别到选项，直接返回")
        return False

    # 去重相近文案，随机选一个
    uniq = []
    seen = set()
    for elem, text in candidates:
        key = text or id(elem)
        if key in seen:
            continue
        seen.add(key)
        uniq.append((elem, text))

    elem, text = random.choice(uniq)
    print(f"[趣味课堂] 候选 {len(uniq)} 个，随机点击: 「{text}」")
    try:
        bounds = elem.info["bounds"]
        cx = (bounds["left"] + bounds["right"]) // 2
        cy = (bounds["top"] + bounds["bottom"]) // 2
        d.click(cx, cy)
    except Exception:
        try:
            elem.click()
        except Exception as e:
            print(f"[趣味课堂] 点击失败: {e}")
            return False

    _pause_sleep(2)
    _dismiss_jump_popups()
    # 若点完出现完成提示，记一下日志
    done, how = detect_task_completion(use_hierarchy=False)
    if done:
        print(f"[趣味课堂] 点选后检测到完成提示（via {how}）")
    return True


def operate_task(is_search_task=False, quick_return=False, quiz_classroom=False):
    """
    进入任务页后处理，走完闭环（做完并回到任务列表）返回 True，否则 False。
    - 普通任务：浏览至完成弹窗或超时，再返回
    - 外跳逛一逛（quick_return）：跳转后立刻按返回策略回淘宝
    - 趣味课堂（quiz_classroom）：点一个选项再返回
    """
    _dismiss_text_popups()
    cancel_btn = d(resourceId="android:id/button2", text="取消")
    if cancel_btn.exists:
        cancel_btn.click()
        _pause_sleep(2)
        return False

    if quiz_classroom:
        print("[趣味课堂] 进入趣味课堂流程：点选项 → 返回")
        click_classroom_option()
        return return_to_task_list(is_search_task=False, force_external=False)

    if quick_return:
        settle = float(config.get("operation.quick_return_settle_seconds", 3) or 3)
        print(f"[外跳] 任务名含外跳字眼：等待最多 {settle}s，关广告后返回，再看是否「任务已完成」")
        end = time.time() + settle
        jumped = False
        while time.time() < end:
            _check_pause()
            _dismiss_jump_popups(aggressive_x=True)
            ok, pkg = _confirm_external_leave(samples=2, interval=0.25)
            if ok:
                print(f"[外跳] 已确认跳转到外部应用: {pkg}")
                jumped = True
                break
            _pause_sleep(0.3)

        # 外跳页再清一轮弹窗，然后返回
        for _ in range(3):
            _check_pause()
            if not _dismiss_jump_popups(aggressive_x=True):
                break

        back_ok = return_to_task_list(is_search_task=is_search_task, force_external=True)
        # 回到列表后看完成气泡/「任务已完成」——有则算外跳任务完成
        done, how = detect_task_completion(use_hierarchy=True)
        if done:
            print(f"✓ 外跳返回后检测到完成提示（via {how}），本任务结束")
        else:
            print("[外跳] 已返回任务列表，未看到完成文案（可能稍后到账，不再停留）")
        return back_ok

    start_time = time.time()
    max_wait_duration = config.get("operation.max_wait_duration", 35)
    required_buffer = config.get("operation.required_buffer", 8)
    swipe_interval = config.get("operation.human_swipe.interval_seconds", None)
    if swipe_interval is None:
        swipe_interval = config.get("operation.human_swipe.pause_min", 0.8)
    swipe_interval = float(swipe_interval)

    # 浏览页不要先卡在读「浏览XX秒」上；用配置超时，读到再缩短
    timeout_seconds = float(max_wait_duration)
    print(f"[任务] 滑动间隔 {swipe_interval} 秒，超时 {timeout_seconds} 秒", flush=True)


    swipe_round = 0
    while True:
        _check_pause()

        if time.time() - start_time > timeout_seconds:
            done, how = detect_task_completion(use_hierarchy=True)
            if done:
                print(f"✓ 超时前补检到完成提示（via {how}），任务完成")
                break
            print(f"⚠ 已等待 {timeout_seconds} 秒未检测到完成弹窗，按超时处理返回")
            break

        # 先滑再检：避免进页后卡在完成检测上迟迟不滑
        human_like_swipe()
        swipe_round += 1

        # 精选好物「已得XX」必须扫控件树，textContains 经常看不到
        done, how = detect_task_completion(use_hierarchy=True)
        if done:
            print(f"✓ 滑动后检测到完成提示（via {how}），任务完成")
            break

        # 外跳兜底：少做 dumpsys，每 5 轮一次
        if swipe_round % 5 == 0:
            ok, pkg = _confirm_external_leave(samples=1, interval=0.15)
            if ok:
                print(f"[任务] 连续确认已离开淘宝 → 外部应用 {pkg}，立即返回")
                return return_to_task_list(is_search_task=is_search_task, force_external=True)

        _pause_sleep(swipe_interval)

    # 沉浸看等淘宝内任务：不要 force_external，避免回到列表后再多按一次返回
    return return_to_task_list(is_search_task=is_search_task, force_external=False)


def check_task_progress(target_count=40):
    """
    检查任务进度是否达到配置的目标次数。
    以 conf 里的 target_count 为准；页面上的「完成进度 x/y」仅作参考，
    不会因为 x>=y（如 20/20）就提前结束。
    """
    try:
        d.swipe(
            screen_width // 2,
            screen_height // 3,
            screen_width // 2,
            screen_height * 2 // 3,
            0.3,
        )
        _pause_sleep(1)
    except Exception:
        pass

    progress_texts = d(className="android.widget.TextView", textMatches=".*进度.*")
    found_progress = False
    for progress_view in progress_texts:
        try:
            text = progress_view.get_text()
            match = re.search(r"(\d+)\s*/\s*(\d+)", text)
            if match:
                found_progress = True
                current = int(match.group(1))
                total = int(match.group(2))
                print(f"[进度检查] 页面进度: {text} → {current}/{total}（配置目标: {target_count}）")
                if current >= target_count:
                    print(f"✓ 已达到配置目标 {target_count} 次（页面显示 {current}/{total}）")
                    return True
                if current >= total:
                    print(
                        f"[进度检查] 页面进度条已满 {current}/{total}，"
                        f"但配置目标为 {target_count}，继续查找可做任务"
                    )
                    return False
                print(f"[进度检查] 未达目标 (页面: {current}, 目标: {target_count})")
                return False
        except Exception as e:
            print(f"[进度检查] 解析进度失败: {e}")
            continue

    if not found_progress:
        print("[进度检查] ⚠ 未找到进度信息，继续执行任务")
    return False


def _restart_and_navigate():
    print("   重新启动淘宝并导航到淘金币任务界面...")
    have_clicked.clear()
    d.app_stop(package_name)
    _pause_sleep(2)
    d.app_start(package_name)
    _pause_sleep(3)
    navigate_to_coin_tasks()
    _pause_sleep(2)


def _load_task_keywords():
    """只从配置读取名单，代码不内置跳过/秒返/课堂词。"""
    skip = [str(x) for x in (config.get("task.coin.skip_keywords", []) or []) if x]
    quiz = [str(x) for x in (config.get("task.coin.quiz_keywords", []) or []) if x]
    quick = [str(x) for x in (config.get("operation.quick_return_keywords", []) or []) if x]
    return skip, quiz, quick


def _task_name_near_bounds(xml, left, top, right, bottom):
    """按坐标找「去完成」左侧同一行的标题，避免 sibling/xpath 在 H5 上解析失败。"""
    if not xml:
        return None
    cy = (top + bottom) // 2
    best = None
    skip_exact = ("去完成", "去逛逛", "逛一逛", "去浏览", "去看看", "立即领取")
    for node in _iter_xml_nodes(xml):
        t = _node_label(node)
        if not t or len(t) < 2 or t in skip_exact:
            continue
        if _looks_like_goods_card(t):
            continue
        m = _BOUNDS_RE.search(node.attrib.get("bounds") or "")
        if not m:
            continue
        l, t0, r, b0 = map(int, m.groups())
        ncy = (t0 + b0) // 2
        if abs(ncy - cy) > 100:
            continue
        if l >= left:
            continue
        if len(t) > 36:
            continue
        dist = abs(ncy - cy) + max(0, left - r)
        score = (dist, -len(t))
        if best is None or score < best[0]:
            best = (score, t)
    return best[1] if best else None


def _get_task_name(btn, xml=None):
    """尽量从按钮周围解析任务标题。"""
    bounds = None
    try:
        info = btn.info
        b = info.get("bounds") or {}
        bounds = (
            int(b.get("left", 0)),
            int(b.get("top", 0)),
            int(b.get("right", 0)),
            int(b.get("bottom", 0)),
        )
        desc = (info.get("contentDescription") or info.get("text") or "").strip()
        if desc and desc not in ("去完成", "去逛逛", "逛一逛", "去浏览", "去看看"):
            return desc
    except Exception:
        pass

    if xml is not None and bounds:
        near = _task_name_near_bounds(xml, *bounds)
        if near:
            return near

    try:
        text_div = btn.sibling(className="android.view.View", instance=0).child(
            className="android.widget.TextView", instance=0
        )
        if text_div.exists:
            t = text_div.get_text()
            if t and t.strip():
                return t.strip()
    except Exception:
        pass

    try:
        left = btn.left(className="android.widget.TextView")
        if left.exists:
            t = left.get_text()
            if t and t.strip() and t.strip() not in ("去完成", "去逛逛", "逛一逛"):
                return t.strip()
    except Exception:
        pass

    if bounds:
        return None
    return None


_GO_BTN_TEXTS = ("去完成", "去逛逛", "逛一逛", "去浏览", "去看看")


def _go_buttons():
    return d(className="android.widget.Button", textMatches="去完成|去逛逛|逛一逛|去浏览|去看看")


def _task_list_open():
    try:
        return bool(d(text="去完成").exists(timeout=0.25) or d(text="去逛逛").exists(timeout=0.25))
    except Exception:
        return False


def _dismiss_text_popups():
    """只点明确广告文案，不 dump 整页。"""
    for key in ("关闭广告", "跳过广告", "我知道了", "知道了"):
        try:
            n = d(text=key)
            if n.exists(timeout=0.15):
                n.click()
                print(f"[弹窗] 点击「{key}」", flush=True)
                return True
        except Exception:
            pass
    return False


def _bounds_from_info(info):
    b = info.get("bounds") or {}
    return (
        int(b.get("left", 0)),
        int(b.get("top", 0)),
        int(b.get("right", 0)),
        int(b.get("bottom", 0)),
    )


def _capture_go_rows():
    """先记下每个「去完成」的坐标，再读名字。dump 可能关掉弹层。"""
    to_btn = _go_buttons()
    if not to_btn.exists:
        return []
    rows = []
    for i in range(len(to_btn)):
        try:
            view = to_btn[i]
            info = view.info
            bounds = _bounds_from_info(info)
            desc = (info.get("contentDescription") or info.get("text") or "").strip()
            name = None
            if desc and desc not in _GO_BTN_TEXTS:
                name = desc
            else:
                name = _get_task_name(view)
            rows.append({"i": i, "bounds": bounds, "name": name})
        except Exception as e:
            continue
    return rows


def _fill_names_from_xml(rows):
    """用整页 XML 按坐标取标题；若弹层被关掉，立刻重开，再按序号点。"""
    xml = _dump_xml(timeout=6, quiet=True)
    names = []
    if xml:
        for r in rows:
            near = _task_name_near_bounds(xml, *r["bounds"])
            if near:
                r["name"] = near
            names.append(r.get("name"))
    still = _task_list_open()
    if not still:
        print("[选任务] 读任务名时列表被关掉，重新打开（不滑动）", flush=True)
        _reopen_task_list()
    return rows


def _live_button_bounds(index):
    to_btn = _go_buttons()
    if not to_btn.exists:
        return None
    try:
        return _bounds_from_info(to_btn[index].info)
    except Exception:
        return None


def pick_next_coin_task(skip_keywords, clicked_names):
    """
    扫描当前可见的「去完成/去逛逛/逛一逛」，跳过屏蔽与已点过的。
    先 sibling 取名（旧逻辑）；取不到再 dump 一次按坐标取名，列表被关掉就重开。
    """
    to_btn = _go_buttons()
    if not to_btn.exists:
        return None, None, 0, None

    rows = _capture_go_rows()
    total = len(rows)
    if total == 0:
        return None, None, 0, None
    if not any(r.get("name") for r in rows):
        print("[选任务] 按钮旁读不到标题，改为整页解析任务名", flush=True)
        rows = _fill_names_from_xml(rows)

    print(f"[选任务] 当前可见可点按钮 {total} 个，开始筛选...")
    resolved = []
    for r in rows:
        name = r.get("name") or ("未命名任务#%s" % r["i"])
        r["name"] = name
        resolved.append(name)
        if check_chars_exist(name, skip_keywords):
            print(f"  [{r['i']}] 「{name}」→ 屏蔽，跳过")
            continue
        if name in clicked_names:
            print(f"  [{r['i']}] 「{name}」→ 本轮已点过，跳过")
            continue
        bounds = _live_button_bounds(r["i"]) or r["bounds"]
        print(f"  [{r['i']}] 「{name}」→ 选中执行 {bounds}")
        return to_btn, name, total, bounds

    print("[选任务] 可见任务均已屏蔽或已点过")
    return None, None, total, None


# --- 主流程：仅淘金币 ---
print("\n正在加载任务配置...")
coin_target = config.get("task.coin.target_count", 40)
max_no_task = config.get("retry.max_no_task_count", 3)
wait_between_tasks = config.get("operation.wait_between_tasks", 2)
skip_keywords, quiz_keywords, quick_return_keywords = _load_task_keywords()
print(f"  - 淘金币任务目标: {coin_target} 次闭环（点进→做完→回到列表才 +1）")
print(f"  - 完成等待上限: {config.get('operation.max_wait_duration', 35)} 秒")
print(f"  - 任务间等待: {wait_between_tasks} 秒")
print(f"  - 跳过（不点击）{len(skip_keywords)} 个关键词: {skip_keywords}")
print(f"  - 秒返 {len(quick_return_keywords)} 个关键词: {quick_return_keywords}")
print(f"  - 趣味课堂 {len(quiz_keywords)} 个关键词: {quiz_keywords}")
print(f"  - 完成提示 {len(_completion_keywords())} 个关键词: {_completion_keywords()}")
print("✓ 配置加载完成（名单只来自 conf/config.yaml）\n")

def _popup_watch_loop():
    """后台不再 dump 猜叉叉：会把任务页右上角图标连点。文字关闭交给 watch_context。"""
    return


_listener_thread = threading.Thread(target=_keyboard_listener, daemon=True)
_listener_thread.start()

navigate_to_coin_tasks()

# watch_context 后台会不停 dump_hierarchy，浏览 H5 时会把滑动卡死。
# conf 里 popup_watch_enabled=false 时绝不启动。
if config.get("operation.popup_watch_enabled", False):
    print("启动文字弹窗监视...", flush=True)
    try:
        ctx.start()
        print("✓ 监视器已启动", flush=True)
    except Exception as e:
        print(f"[弹窗] 监视器启动失败: {e}", flush=True)
else:
    print("跳过后台弹窗监视（popup_watch_enabled=false，避免 dump 卡死滑动）", flush=True)


finish_count = 0
time1 = time.time()
no_task_count = 0

print("\n" + "=" * 60)
print(f"开始淘金币任务（目标：完成 {coin_target} 次闭环）")
print("=" * 60)

while True:
    try:
        _check_pause()
        if finish_count >= coin_target:
            print(f"✓ 已完成闭环 {finish_count}/{coin_target}，达到配置目标，结束")
            break

        print(f"开始查找淘金币任务... 当前进度 {finish_count}/{coin_target}")
        get_btn = d(className="android.widget.Button", text="立即领取")
        if get_btn.exists:
            get_btn.click()
            _pause_sleep(3)

        need_click_view, task_name, visible_n, click_bounds = pick_next_coin_task(
            skip_keywords, have_clicked
        )
        if need_click_view is None and visible_n == 0:
            list_open = _task_list_open()
            entry_visible = False
            try:
                entry_visible = bool(d(text="赚更多金币").exists(timeout=0.25))
            except Exception:
                pass
            if (not list_open) and entry_visible:
                print("[淘金币] 任务列表已关闭，重新点「赚更多金币」打开（不向下滑首页）", flush=True)
                _reopen_task_list()
                continue
            print("[淘金币] 未找到任务按钮，尝试向下滚动...")
            d.swipe(
                screen_width // 2,
                screen_height * 2 // 3,
                screen_width // 2,
                screen_height // 3,
                0.3,
            )
            _pause_sleep(2)
            need_click_view, task_name, visible_n, click_bounds = pick_next_coin_task(
                skip_keywords, have_clicked
            )
            if need_click_view is None and visible_n == 0 and config.get(
                "debug.print_buttons", True
            ):
                print("[淘金币] [调试] 仍未找到任务按钮，打印当前页面 Button:")
                all_buttons = d(className="android.widget.Button")
                for i, btn in enumerate(all_buttons):
                    try:
                        btn_text = btn.get_text()
                        if btn_text and btn_text.strip():
                            print(f"   Button[{i}]: '{btn_text}'")
                    except Exception:
                        pass

        if need_click_view is not None and click_bounds:
            print(f"点击淘金币任务: {task_name}  （开始前进度 {finish_count}/{coin_target}）")
            if task_name not in have_clicked:
                have_clicked.append(task_name)
            left, top, right, bottom = click_bounds
            pad = 10
            x1, x2 = left + pad, right - pad
            y1, y2 = top + pad, bottom - pad
            if x2 <= x1 or y2 <= y1:
                cx, cy = (left + right) // 2, (top + bottom) // 2
            else:
                cx = random.randint(x1, x2)
                cy = random.randint(y1, y2)
            d.click(cx, cy)
            _pause_sleep(1.2)
            _dismiss_text_popups()
            still_list = _task_list_open()
            if still_list:
                print("[任务] 点完仍在任务列表，未进入任务页，不滑动", flush=True)
                continue

            is_search = False
            search_view = d(className="android.view.View", text="搜索有福利")
            need_search = False
            try:
                need_search = bool(search_view.exists(timeout=0.4))
            except Exception:
                need_search = False
            if need_search or is_search_like_task(task_name):
                if _do_search_task_flow():
                    is_search = True
                elif is_search_like_task(task_name):
                    print("[搜索] 任务名像搜索，但没完成搜索动作，仍按搜索任务返回（至少退 2 次）")
                    is_search = True

            quiz = is_quiz_classroom_task(task_name, keywords=quiz_keywords)
            quick = (not quiz) and is_external_jump_task(
                task_name, keywords=quick_return_keywords
            )
            if quiz:
                print(f"[趣味课堂] 识别到趣味课堂任务: {task_name}")
            elif quick:
                print(f"[外跳] 识别为秒返任务: {task_name}")
            loop_ok = operate_task(
                is_search_task=is_search,
                quick_return=quick,
                quiz_classroom=quiz,
            )
            if loop_ok:
                finish_count += 1
                print(
                    f"✓ 闭环完成「{task_name}」+1 → {finish_count}/{coin_target}",
                    flush=True,
                )
            else:
                print(
                    f"✗ 「{task_name}」未走完闭环（未回到任务列表），本次不计数，"
                    f"进度仍为 {finish_count}/{coin_target}",
                    flush=True,
                )
            no_task_count = 0
        else:
            no_task_count += 1
            print(
                f"[淘金币] 本屏无可执行任务（已屏蔽/已点过/未找到）"
                f" ({no_task_count}/{max_no_task})"
            )
            if no_task_count >= max_no_task:
                print("⚠ 连续多次未找到可执行任务，可能页面异常")
                _restart_and_navigate()
                no_task_count = 0
                continue
            print("[淘金币] 向下滚动查找更多任务...")
            d.swipe(
                screen_width // 2,
                screen_height * 2 // 3,
                screen_width // 2,
                screen_height // 3,
                0.3,
            )
            _pause_sleep(2)

        _pause_sleep(wait_between_tasks)
    except SystemExit:
        raise
    except Exception as e:
        print("出现异常，继续下一轮", str(e))

print(f"\n共完成 {finish_count}/{coin_target} 个任务闭环")

d.watcher.remove()
d.shell("settings put system accelerometer_rotation 0")
print("关闭手机自动旋转")
time2 = time.time()
minutes, seconds = divmod(int(time2 - time1), 60)
print(f"共耗时: {minutes} 分钟 {seconds} 秒")

print("\n" + "=" * 60)
print("✓ 淘金币任务流程结束")
print("=" * 60)
print("\n按任意键退出程序...")
_listener_stop = True
try:
    input()
except (EOFError, KeyboardInterrupt):
    pass
