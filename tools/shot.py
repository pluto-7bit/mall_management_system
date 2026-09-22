# -*- coding: utf-8 -*-
"""
给页面截图的小工具（开发时肉眼验证界面用）。

用法：
    python tools/shot.py http://localhost:5174/ /tmp/home.png
    python tools/shot.py http://localhost:5174/cart /tmp/cart.png --wait 3
    python tools/shot.py http://localhost:5174/ /tmp/x.png --eval "document.querySelectorAll('.product-card').length"
    python tools/shot.py http://localhost:5174/ /tmp/x.png --height 900   # 只截首屏

<h3>★ 为什么不用 `chrome --headless --screenshot`？</h3>

<p>那条命令简单，但它只认 {@code --virtual-time-budget} 这一个「等待」手段，
而虚拟时间是<b>不等网络请求的</b>：

<pre>
  Vue 挂载 → 发 fetch 拿商品列表 → 商品才进 DOM → 才开始请求商品图片
</pre>

<p>虚拟时间会瞬间冲过那 30 秒的预算，于是截到的是
「页面框架 + 空商品区」，或者图片全是白框。更糟的是<b>它时好时坏</b> ——
同一份代码同一条命令，这次截出来是完整的，下次就是空的。
拿这种东西判断「界面有没有问题」，会得到随机结论。

<p>所以这里走 CDP（Chrome DevTools Protocol）：
起一个真的 headless Chrome，用<b>真实时间</b>等，
等多久由 {@code --wait} 决定，再用 {@code Page.captureScreenshot} 截图。
还能顺手 {@code Runtime.evaluate} 跑一段 JS 把结果打出来 ——
「页面上到底有几个 .product-card」这种问题，比看截图可靠得多。

<h3>依赖</h3>
websocket-client（已装）。Chrome 路径见下面 CHROME_CANDIDATES。
"""

import argparse
import base64
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.request

import websocket  # websocket-client

sys.stdout.reconfigure(encoding="utf-8")

CHROME_CANDIDATES = [
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
]

DEBUG_PORT = 9333


def find_chrome():
    for p in CHROME_CANDIDATES:
        if os.path.exists(p):
            return p
    sys.exit("✗ 找不到 Chrome/Edge，请手动改 CHROME_CANDIDATES")


class Chrome:
    """一个最小可用的 CDP 客户端：够截图和跑 JS 就行。"""

    def __init__(self, width, height):
        self.width = width
        self.height = height
        self.chrome_path = find_chrome()
        self.profile = tempfile.mkdtemp(prefix="shot-profile-")
        self.proc = subprocess.Popen(
            [
                self.chrome_path,
                "--headless=new",
                "--disable-gpu",
                "--hide-scrollbars",
                "--no-first-run",
                "--no-default-browser-check",
                f"--user-data-dir={self.profile}",
                f"--remote-debugging-port={DEBUG_PORT}",
                # ★ 少了这一条，Chrome 会以「来源不合法」为由拒绝 WebSocket 握手：
                #     Rejected an incoming WebSocket connection from the
                #     http://127.0.0.1:9333 origin
                #   调试端口默认只允许空来源，而我们是从 Python 连过去的。
                "--remote-allow-origins=*",
                f"--window-size={width},{height}",
                "about:blank",
            ],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        self.ws = self._connect()
        self._id = 0

    def _connect(self, timeout=25):
        """轮询 /json/list 直到 Chrome 起来并给出页面 target。

        ★ 不能「起完进程 sleep 1 秒就连」—— 机器忙的时候 Chrome
          要好几秒才开调试端口，脚本会随机失败。
          轮询到能连为止，才是稳的。
        """
        deadline = time.time() + timeout
        last_err = None
        while time.time() < deadline:
            try:
                with urllib.request.urlopen(
                    f"http://127.0.0.1:{DEBUG_PORT}/json/list", timeout=1
                ) as r:
                    targets = json.load(r)
                pages = [t for t in targets if t.get("type") == "page"]
                if pages:
                    return websocket.create_connection(
                        pages[0]["webSocketDebuggerUrl"], timeout=30
                    )
            except Exception as e:  # 端口还没开、连接被拒、空列表……
                last_err = e
            time.sleep(0.3)
        sys.exit(f"✗ 连不上 Chrome 调试端口 {DEBUG_PORT}：{last_err}")

    def send(self, method, params=None):
        self._id += 1
        mid = self._id
        self.ws.send(json.dumps({"id": mid, "method": method, "params": params or {}}))
        # 一直读到 id 对上的那条 —— 中间会插进来一堆事件通知，跳过即可
        while True:
            msg = json.loads(self.ws.recv())
            if msg.get("id") == mid:
                if "error" in msg:
                    raise RuntimeError(f"{method} 失败：{msg['error']}")
                return msg.get("result", {})

    def prepare(self):
        self.send("Page.enable")
        self.send(
            "Emulation.setDeviceMetricsOverride",
            {
                "width": self.width,
                "height": self.height,
                "deviceScaleFactor": 1,
                "mobile": False,
            },
        )
        # ★ 必须在 navigate 【之前】注入，否则页面自己的脚本先跑，
        #   最开头那几条报错就丢了 —— 而「页面打不开」的报错恰恰在最开头。
        self.send(
            "Page.addScriptToEvaluateOnNewDocument",
            {
                "source": """
                    window.__shotErrors__ = [];
                    const push = (kind, args) => {
                        try {
                            window.__shotErrors__.push(
                                kind + ': ' + args.map(a =>
                                    a instanceof Error ? (a.stack || a.message)
                                    : (typeof a === 'object' ? JSON.stringify(a) : String(a))
                                ).join(' ')
                            );
                        } catch (e) {}
                    };
                    const _err = console.error.bind(console);
                    console.error = (...a) => { push('ERROR', a); _err(...a); };
                    const _warn = console.warn.bind(console);
                    console.warn = (...a) => { push('WARN', a); _warn(...a); };
                    window.addEventListener('error', e => push('UNCAUGHT', [e.message]));
                    window.addEventListener('unhandledrejection', e => push('REJECT', [e.reason]));
                """
            },
        )
    def goto(self, url):
        self.send("Page.navigate", {"url": url})

    def eval(self, expression):
        r = self.send(
            "Runtime.evaluate",
            {"expression": expression, "returnByValue": True, "awaitPromise": True},
        )
        if r.get("exceptionDetails"):
            return f"<JS 异常> {r['exceptionDetails'].get('text')}"
        return r.get("result", {}).get("value")

    def screenshot(self, path, full_page):
        params = {"format": "png"}
        if full_page:
            # captureBeyondViewport 一次画完整页，不用滚动、不用拼图
            params["captureBeyondViewport"] = True
        r = self.send("Page.captureScreenshot", params)
        with open(path, "wb") as f:
            f.write(base64.b64decode(r["data"]))
        return len(base64.b64decode(r["data"]))

    def close(self):
        try:
            self.send("Browser.close")
        except Exception:
            pass
        try:
            self.ws.close()
        except Exception:
            pass
        try:
            self.proc.wait(timeout=5)
        except Exception:
            self.proc.kill()


def main():
    ap = argparse.ArgumentParser(description="给页面截图 / 在页面上跑 JS")
    ap.add_argument("url")
    ap.add_argument("out", nargs="?", default=None, help="不传就只跑 --eval")
    ap.add_argument("--width", type=int, default=1400)
    ap.add_argument("--height", type=int, default=1200)
    ap.add_argument("--wait", type=float, default=2.5, help="导航后等多少【真实】秒")
    ap.add_argument("--eval", dest="js", default=None, help="截图后在这页上执行的 JS")
    ap.add_argument("--viewport-only", action="store_true", help="只截首屏，不截整页")
    ap.add_argument(
        "--setup",
        default=None,
        help="先打开一次页面、跑这段 JS（比如写 localStorage 里的 token）、再重新打开。"
        "用来截「已登录」的页面 —— 登录状态存在 localStorage 里，"
        "而 localStorage 是按来源存的，必须先真的到过那个源才能写。",
    )
    args = ap.parse_args()

    c = Chrome(args.width, args.height)
    try:
        c.prepare()
        c.goto(args.url)
        if args.setup:
            time.sleep(args.wait)
            print("setup:", c.eval(args.setup))
            c.goto(args.url)
        time.sleep(args.wait)

        if args.js:
            print(c.eval(args.js))

        if args.out:
            size = c.screenshot(args.out, full_page=not args.viewport_only)
            print(f"✓ {size} 字节 → {args.out}")

        # 页面上的报错是「界面看起来正常但功能坏了」的唯一线索，顺手报一下
        errs = c.eval(
            "JSON.stringify({"
            "errs: (window.__shotErrors__ || []).slice(0, 10),"
            "cards: document.querySelectorAll('.product-card').length,"
            "imgs: [...document.images].filter(i => i.complete && i.naturalWidth > 0).length,"
            "brokenImgs: [...document.images].filter(i => i.complete && i.naturalWidth === 0).length"
            "})"
        )
        print("页面状态：", errs)
    finally:
        c.close()


if __name__ == "__main__":
    main()
