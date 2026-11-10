import json, requests, re, os, time, random, ipaddress
from concurrent.futures import ThreadPoolExecutor, as_completed
from preferences import prefs
from logger import logger

IP_LIST = {}
accounts_list = {}

headers = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/86.0.4240.198 Safari/537.36',
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8',
    'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
    'Connection': 'keep-alive'
}

def validate_ip_port(ip, port):
    try:
        ip_obj = ipaddress.ip_address(ip)
        if ip_obj.is_multicast or ip_obj.is_unspecified:
            return False
    except ValueError:
        return False
    ip_parts = ip.split('.')
    for part in ip_parts:
        if not 0 <= int(part) <= 255:
            return False
    if not 1 <= int(port) <= 65535:
        return False
    return True

def verify(proxy):
    target_url = 'https://bbs.binmt.cc/forum.php?mod=guide&view=hot'
    proxies = {
        'https': f'http://{proxy}',
        'http': f'http://{proxy}'
    }
    start_time = time.time()
    try:
        response = requests.get(target_url, headers=headers, proxies=proxies, timeout=20)
        return proxy, response.ok, int((time.time() - start_time) * 1000)
    except:
        return proxy, False, -1

ACW_KEY = "3000176000856006061501533003690027800375"
ACW_BOX = [0xf, 0x23, 0x1d, 0x18, 0x21, 0x10, 0x1, 0x26, 0xa, 0x9, 0x13, 0x1f, 0x28, 0x1b,
           0x16, 0x17, 0x19, 0xd, 0x6, 0xb, 0x27, 0x12, 0x14, 0x8, 0xe, 0x15, 0x20, 0x1a,
           0x2, 0x1e, 0x7, 0x4, 0x11, 0x5, 0x3, 0x1c, 0x22, 0x25, 0xc, 0x24]

def _unsbox(arg1):
    box = [None] * len(ACW_BOX)
    for index, char in enumerate(arg1):
        for position in range(len(ACW_BOX)):
            if ACW_BOX[position] == index + 1:
                box[position] = char
    return ''.join(char for char in box if char)

def acw_sc_v2(arg1):
    raw = _unsbox(arg1)
    result = ''
    for index in range(0, min(len(raw), 40), 2):
        value = int(raw[index:index + 2], 16) ^ int(ACW_KEY[index:index + 2], 16)
        result += format(value, '02x')
    return result

def get(req, url, proxies):
    # 论坛启用了阿里云人机验证, 首次访问返回 js 挑战页, 需要计算 acw_sc__v2 cookie 后重试
    resp = req.get(url, proxies=proxies, timeout=20)
    resp.encoding = resp.apparent_encoding
    match = re.search(r"var arg1='([0-9A-Fa-f]+)'", resp.text)
    if match and 'acw_sc__v2' in resp.text:
        req.cookies.set('acw_sc__v2', acw_sc_v2(match.group(1)), domain='bbs.binmt.cc', path='/')
        resp = req.get(url, proxies=proxies, timeout=20)
        resp.encoding = resp.apparent_encoding
    return resp

def post(req, url, data, proxies):
    resp = req.post(url, data=data, proxies=proxies, timeout=20)
    resp.encoding = resp.apparent_encoding
    match = re.search(r"var arg1='([0-9A-Fa-f]+)'", resp.text)
    if match and 'acw_sc__v2' in resp.text:
        req.cookies.set('acw_sc__v2', acw_sc_v2(match.group(1)), domain='bbs.binmt.cc', path='/')
        resp = req.post(url, data=data, proxies=proxies, timeout=20)
        resp.encoding = resp.apparent_encoding
    return resp

def is_phone_number(username):
    pattern = r'^1[3-9]\d{9}$'
    return re.match(pattern, username) is not None

def format_phone_number(phone):
    if len(phone) == 11:
        return f"{phone[:3]}****{phone[-4:]}"
    return phone

def format_username(username):
    if is_phone_number(username):
        return format_phone_number(username)
    return username

def load():
    myset = set()
    successful_proxies = []
    try:
        with open("src/ips.txt", "r", encoding="utf-8") as f:
            for line in f:
                ip = line.strip()
                if ":" not in ip or not ip: continue
                newIp, newPort = ip.split(':', 1)
                if not validate_ip_port(newIp, newPort): continue
                myset.add(ip)
    except Exception as e:
        pass
    with ThreadPoolExecutor(max_workers=30) as executor:
        futures = [executor.submit(verify, proxy) for proxy in myset]
        for future in as_completed(futures):
            proxy, is_valid, requestTime = future.result()
            if is_valid:
                successful_proxies.append((proxy, requestTime))
    successful_proxies.sort(key=lambda x: x[1])
    logger.info("可用ip代理:")
    for index, (proxy, req_time) in enumerate(successful_proxies, 1):
        logger.info(f"{index}: {proxy} - {req_time}ms")
        IP_LIST[proxy] = True

SIGN_PAGE = 'https://bbs.binmt.cc/k_misign-sign.html'
# 签到插件(k_misign)真实返回: 成功为"签到成功"/"获得", 当天已签为"今日已签"/"重复签到"
SIGN_OK_KEYWORDS = ('签到成功', '获得')
SIGN_DONE_KEYWORDS = ('已签', '重复签到')
SIGN_AUTH_KEYWORDS = ('请先登录', '未登录', '需要先登录', '请登录')

def message_of(text):
    msg = CDATA(text)
    if not msg:
        msg = re.sub(r'<[^>]+>', ' ', text)
        msg = re.sub(r'\s+', ' ', msg).strip()
    return msg[:200]

def checkIn(user, pwd, ip):
    """返回 True 表示本次运行不必再换代理重试(签到成功/今日已签/密码错误等确定性结果)"""
    name = format_username(user)
    req = requests.session()
    req.headers.update(headers)
    proxies = {
        'http': f'http://{ip}',
        'https': f'http://{ip}'
    }
    req.proxies = proxies
    logger.info(f"{name} 开始签到")
    try:
        url = 'https://bbs.binmt.cc/member.php?mod=logging&action=login&infloat=yes&handlekey=login&inajax=1&ajaxtarget=fwin_content_login'
        resp = get(req, url, proxies)
        if not resp.ok:
            return False
        content = resp.text
        _loginhash = loginhash(content)
        _formhash = formhash(content)
        url = f'https://bbs.binmt.cc/member.php?mod=logging&action=login&loginsubmit=yes&handlekey=login&loginhash={_loginhash}&inajax=1'
        data = {
            'formhash': _formhash,
            'referer': SIGN_PAGE,
            'fastloginfield': 'username',
            'username': user,
            'password': pwd,
            'questionid': '0',
            'answer': '',
            'agreebbrule': ''
        }
        resp = post(req, url, data, proxies)
        if not resp.ok:
            return False
        if '失败' in resp.text and '欢迎您回来' not in resp.text:
            accounts_list.pop(user, None)
            logger.warning(f"{name} 登录失败(账号或密码错误)")
            return True
        # 签到页的 formhash 同时可用于确认登录状态已生效
        resp = get(req, SIGN_PAGE, proxies)
        if not resp.ok:
            return False
        _formhash = formhash(resp.text)
        if not _formhash:
            logger.warning(f"{name} 未获取到签到 formhash, 登录状态可能已失效")
            return False
        url = f'https://bbs.binmt.cc/plugin.php?id=k_misign:sign&operation=qiandao&format=text&formhash={_formhash}'
        resp = get(req, url, proxies)
        if not resp.ok:
            return False
        text = resp.text or ''
        msg = message_of(text)
        # 人机验证页/错误页等 HTML 也可能带"已签"字样, 不能当作签到结果
        head = text.lstrip()[:300].lower()
        if head.startswith('<!doctype') or '<html' in head:
            logger.warning(f"{name} 签到返回异常页面, 稍后重试: {msg}")
            return False
        if any(k in text for k in SIGN_AUTH_KEYWORDS):
            logger.warning(f"{name} 登录状态失效, 稍后重试: {msg}")
            return False
        if any(k in text for k in SIGN_OK_KEYWORDS):
            prefs.put(user, prefs.getTime())
            logger.info(f"{name} 签到成功: {msg}")
            return True
        if any(k in text for k in SIGN_DONE_KEYWORDS):
            # 只有本脚本今天确实签到成功过, "已签"才能当作今天已完成;
            # 否则可能是上次签到未满签到间隔(论坛未过零点)/手动签到,
            # 直接落库会导致当天后续运行全部跳过, 出现"显示已签但实际没签"
            if prefs.get(user, "") == prefs.getTime():
                logger.info(f"{name} 今日已签到: {msg}")
                return True
            # 论坛已签但今天不是本脚本签的: 不落库(否则当天后续运行全跳过),
            # 换代理也没用, 等下一次定时运行再试
            logger.warning(f"{name} 论坛提示已签到但今日无成功记录, 等待下次运行重试: {msg}")
            return True
        logger.warning(f"{name} 签到失败, 稍后重试: {msg or '无返回内容'}")
    except Exception as e:
        logger.warning(f"{name} 异常: {str(e)}")
        IP_LIST[ip] = False
    return False

def loginhash(data):
    pattern = r'loginhash.*?=(.*?)[\'"]>'
    match = re.search(pattern, data, re.IGNORECASE | re.UNICODE)
    if match and match.group(1):
        return match.group(1).strip()
    return ''

def formhash(data):
    pattern = r'formhash[\'"].*?value=[\'"](.*?)[\'"].*?/>'
    match = re.search(pattern, data, re.IGNORECASE | re.UNICODE)
    if match and match.group(1):
        return match.group(1).strip()
    return ''

def CDATA(data):
    pattern = r'CDATA.*?(.*?)]>'
    match = re.search(pattern, data, re.IGNORECASE | re.UNICODE)
    if match and match.group(1):
        return match.group(1).strip('[]')
    return ''

def start():
    ACCOUNTS = os.environ.get("ACCOUNTS", "")
    if not ACCOUNTS:
        logger.warning('github ACCOUNTS变量未设置')
        exit(1)
    for duo in ACCOUNTS.split("\n"):
        if ':' not in duo:
            continue
        username, password = duo.split(':', 1)
        username = username.strip()
        password = password.strip()
        YiQianDao = prefs.get(username, "") == prefs.getTime()
        if username and password and not YiQianDao:
            accounts_list[username] = password
        elif YiQianDao:
            logger.info(f"{format_username(username)} 今日已签, 跳过签到")
    if accounts_list:
        load()
    if IP_LIST:
        keys = list(accounts_list.keys())
        total = len(keys)
        for i, username in enumerate(keys):
            password = accounts_list.get(username)
            if not password:
                continue
            for proxy, status in list(IP_LIST.items()):
                if not status: continue
                try:
                    if checkIn(username, password, proxy): break
                except Exception as e:
                    logger.warning(f"{format_username(username)} 异常: {str(e)}")
                    IP_LIST[proxy] = False
            if i < total - 1:
                time.sleep(3)
start()
prefs.save()
