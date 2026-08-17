import time
import random
import re
import cv2
import numpy as np
import ddddocr


def check_chars_exist(text, chars=None):
    """任务名命中 chars 中任一关键词则跳过。chars 必须由配置传入，代码不内置名单。"""
    if not text or not chars:
        return False
    for char in chars:
        if char and char in text:
            return True
    return False


def is_external_jump_task(text, keywords=None):
    """是否秒返。keywords 必须由配置传入。"""
    if not text or not keywords:
        return False
    for key in keywords:
        if key and key in text:
            return True
    return False


def is_quiz_classroom_task(text, keywords=None):
    """是否趣味课堂。keywords 必须由配置传入。"""
    if not text or not keywords:
        return False
    for key in keywords:
        if key and key in text:
            return True
    return False


def get_current_app(d):
    info = d.shell("dumpsys window | grep mCurrentFocus").output
    match = re.search(r"mCurrentFocus=Window\{.*? u0 (.*?)/(.*?)\}", info)
    if match:
        package_name = match.group(1).strip()
        activity_name = match.group(2).strip()
        return package_name, activity_name
    return None, None


# 明确的外部 App 包名（浏览中误判外跳时只用这份白名单，避免把淘宝内浮层当外跳）
KNOWN_EXTERNAL_PACKAGES = (
    "com.eg.android.AlipayGphone",  # 支付宝
    "com.taobao.idlefish",  # 闲鱼
    "com.baidu.searchbox",
    "com.sina.weibo",
    "com.ss.android.article.news",
    "com.achievo.vipshop",
    "me.ele",  # 饿了么 / 闪购相关
    "com.cainiao.wireless",  # 菜鸟
    "com.cainiao.ebai",
)

EXTERNAL_PACKAGE_PREFIXES = (
    "com.cainiao.",  # 菜鸟系
    "com.eg.android.Alipay",
    "me.ele",
)


def is_ignored_focus_package(pkg):
    """输入法/系统浮层等，不应当成「跳转到其他页面」。"""
    if not pkg:
        return True
    ignore_prefixes = (
        "com.android.",
        "com.google.android.",
        "com.miui.",
        "com.samsung.",
        "com.huawei.",
        "com.coloros.",
        "com.oplus.",
        "com.bbk.",
        "com.vivo.",
        "com.netease.nemaui",
    )
    return any(pkg.startswith(p) for p in ignore_prefixes)


def is_taobao_family_package(pkg):
    if not pkg:
        return False
    return (
        pkg == "com.taobao.taobao"
        or pkg.startswith("com.taobao.")
        or pkg.startswith("com.tmall.")
    )


def is_real_external_app(pkg):
    """
    是否真正跳到了外部 App。
    菜鸟/支付宝/饿了么等第三方优先判定为外跳；淘宝内 H5/气泡不算。
    """
    if not pkg or is_ignored_focus_package(pkg):
        return False
    if pkg in KNOWN_EXTERNAL_PACKAGES:
        return True
    if any(pkg.startswith(p) for p in EXTERNAL_PACKAGE_PREFIXES):
        return True
    if is_taobao_family_package(pkg):
        return False
    return True


def fish_not_click(text, chars=None):
    if chars is None:
        chars = ["发布一件新宝贝", "买到或卖出", "快手", "中国移动", "视频", "下单", "点淘", "一淘", "收藏", "购买"]
    for char in chars:
        if char in text:
            return True
    return False


def find_button(image, btn_path, region=None):
    template = cv2.imread(btn_path)
    # 如果指定了区域，裁剪图像
    if region is not None:
        x, y, w_region, h_region = region
        image = image[y:y + h_region, x:x + w_region]
    # 转换为灰度图像
    screenshot_gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    template_gray = cv2.cvtColor(template, cv2.COLOR_BGR2GRAY)
    # 获取模板图像的宽度和高度
    w, h = template_gray.shape[::-1]
    # 使用模板匹配
    res = cv2.matchTemplate(screenshot_gray, template_gray, cv2.TM_CCOEFF_NORMED)
    threshold = 0.8
    loc = np.where(res >= threshold)
    for pt in zip(*loc[::-1]):
        return pt
    return None


def find_text_position(image, text):
    ocr = ddddocr.DdddOcr(show_ad=False)
    ocr_result = ocr.classification(image)
    # 将 OCR 结果按行解析
    lines = ocr_result.split('\n')
    # 遍历每一行，查找目标文本的位置
    for line in lines:
        if text in line:
            # 获取文本的位置
            start_index = line.find(text)
            end_index = start_index + len(text)
            return start_index, end_index
    return None


# 判断一个字符是否为中文字符
def is_chinese(char):
    return '\u4e00' <= char <= '\u9fff'


def majority_chinese(text):
    if not text:
        return False
    chinese_count = sum(1 for char in text if is_chinese(char))
    return chinese_count > len(text) / 2


search_keys = ["华硕a豆air", "机械革命星耀14", "ipadmini7", "iphone16", "红米note13", "macbookairm4", "华硕灵耀14", "微星星影15"]


def task_loop(d, func):
    history_lst = d.xpath('(//android.widget.TextView[@text="历史搜索"]/following-sibling::android.widget.ListView)/android.view.View[1]')
    if history_lst.exists:
        print("查找到搜索关键字", history_lst)
        history_lst.click()
        time.sleep(2)
    else:
        search_view = d(className="android.view.View", text="搜索有福利")
        if search_view.exists:
            search_edit = d.xpath("//android.widget.EditText")
            if search_edit.exists:
                search_edit.set_text(random.choice(search_keys))
                search_btn = d(className="android.widget.Button", text="搜索")
                if search_btn.exists:
                    search_btn.click()
                    time.sleep(2)
    screen_width, screen_height = d.window_size()
    package_name, _ = get_current_app(d)
    check_count = 3
    while check_count >= 0:
        if not func():
            break
        print(f"检查次数：{check_count}当前在任务页面，没有执行任务。。。")
        check_count -= 1
        if check_count <= 0:
            return
        time.sleep(2)
    start_time = time.time()
    while True:
        bt_open = d(resourceId="android:id/button1", text="浏览器打开")
        if bt_open.exists:
            bt_close = d(resourceId="android:id/button2", text="取消")
            if bt_close.exists:
                bt_close.click()
                time.sleep(2)
                break
        if time.time() - start_time > 22:
            break
        if package_name == "com.taobao.taobao":
            start_x = random.randint(screen_width // 6, screen_width // 2)
            start_y = random.randint(screen_height // 2, screen_height - screen_width // 4)
            end_x = random.randint(start_x - 100, start_x)
            end_y = random.randint(start_y - 1200, start_y - 300)
            swipe_time = random.uniform(0.4, 1) if end_y - start_y > 500 else random.uniform(0.2, 0.5)
            print("模拟滑动", start_x, start_y, end_x, end_y, swipe_time)
            d.swipe(start_x, start_y, end_x, end_y, swipe_time)
            time.sleep(random.uniform(1, 5))
        else:
            time.sleep(5)
    try_count = 0
    print("开始返回任务页面")
    while True:
        if func():
            print("当前是任务列表画面，不能继续返回")
            break
        else:
            temp_package, temp_activity = get_current_app(d)
            if temp_package is None or temp_activity is None:
                continue
            print(f"{temp_package}--{temp_activity}")
            if "com.taobao.taobao" not in temp_package:
                print("回到淘宝APP")
                d.app_start("com.taobao.taobao", stop=False)
                time.sleep(3)
            else:
                print("点击后退")
                d.press("back")
                time.sleep(0.5)


def close_xy_dialog(d):
    dialog_view1 = d.xpath('//android.webkit.WebView[@text="闲鱼币首页"]/android.view.View/android.view.View[2]//android.widget.Image[1]')
    if dialog_view1.exists:
        dialog_view1.click()
        time.sleep(2)