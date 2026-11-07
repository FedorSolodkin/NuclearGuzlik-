"""Мини-обвязка над adb для тестов GuzlikVoice на реальном телефоне.

Поиск элементов по тексту/resource-id через `uiautomator dump`, нажатия, ожидания,
скриншоты и сбор падений из logcat. Без зависимостей, только adb в PATH.
"""
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

import os
SERIAL = os.environ.get("ANDROID_SERIAL")  # задать, если подключено несколько устройств


def adb(*args, check=True, text=True, timeout=600):
    cmd = ["adb"] + (["-s", SERIAL] if SERIAL else []) + list(args)
    r = subprocess.run(cmd, capture_output=True, text=text, timeout=timeout)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)}: {r.stderr or r.stdout}")
    return r.stdout


def sh(cmd, **kw):
    return adb("shell", cmd, **kw)


# ── UI ───────────────────────────────────────────────────────────────────────

_BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


class Node:
    def __init__(self, el):
        self.text = el.get("text", "")
        self.desc = el.get("content-desc", "")
        self.rid = el.get("resource-id", "")
        self.cls = el.get("class", "")
        self.pkg = el.get("package", "")
        self.enabled = el.get("enabled") == "true"
        self.checked = el.get("checked") == "true"
        x1, y1, x2, y2 = map(int, _BOUNDS.match(el.get("bounds")).groups())
        self.bounds = (x1, y1, x2, y2)
        self.center = ((x1 + x2) // 2, (y1 + y2) // 2)

    def __repr__(self):
        return f"<{self.cls.split('.')[-1]} id={self.rid.split('/')[-1]} text={self.text[:40]!r}>"


def dump():
    """Все узлы текущего экрана."""
    for _ in range(3):
        out = sh("uiautomator dump /sdcard/vm_ui.xml >/dev/null 2>&1; cat /sdcard/vm_ui.xml", check=False)
        if out.strip().startswith("<?xml"):
            root = ET.fromstring(out[out.index("<?xml"):].encode("utf-8"))
            return [Node(el) for el in root.iter("node")]
        time.sleep(1)  # dump падает, пока экран анимируется
    return []


def find(text=None, rid=None, contains=None, nodes=None):
    nodes = nodes if nodes is not None else dump()
    res = []
    for n in nodes:
        if text is not None and n.text != text and n.desc != text:
            continue
        if contains is not None and contains.lower() not in (n.text + " " + n.desc).lower():
            continue
        if rid is not None and not n.rid.endswith("/" + rid):
            continue
        res.append(n)
    return res


def tap_xy(x, y):
    sh(f"input tap {x} {y}")


def tap(text=None, rid=None, contains=None, timeout=15, index=0):
    n = wait_for(text=text, rid=rid, contains=contains, timeout=timeout)[index]
    tap_xy(*n.center)
    return n


def wait_for(text=None, rid=None, contains=None, timeout=30, poll=1.5):
    t0 = time.time()
    while True:
        res = find(text=text, rid=rid, contains=contains)
        if res:
            return res
        if time.time() - t0 > timeout:
            raise TimeoutError(f"не дождались элемента text={text} rid={rid} contains={contains}")
        time.sleep(poll)


def exists(**kw):
    return bool(find(**kw))


def swipe_up(dist=900, dur=300):
    sh(f"input swipe 540 1700 540 {1700 - dist} {dur}")


def back():
    sh("input keyevent KEYCODE_BACK")


def screenshot(path):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    data = adb("exec-out", "screencap", "-p", text=False)
    Path(path).write_bytes(data)
    return path


def top_activity():
    out = sh("dumpsys activity activities | grep -m1 topResumedActivity", check=False)
    m = re.search(r"u0 (\S+)", out)
    return m.group(1) if m else ""


# ── Процесс и логи ─────────────────────────────────────────────────────────────

def in_call():
    out = sh("dumpsys telephony.registry | grep mCallState", check=False)
    return any(v != "0" for v in re.findall(r"mCallState=(\d)", out))


def logcat_clear():
    adb("logcat", "-c", "-b", "all", check=False)


def crashes(pkg):
    """FATAL/ANR/нативные падения пакета с последнего logcat_clear."""
    out = adb("logcat", "-d", "-b", "crash", check=False)
    main = adb("logcat", "-d", "-b", "main", "-b", "system", check=False)
    found = []
    if pkg in out:
        found.append(out[-4000:])
    for line in main.splitlines():
        if ("ANR in " + pkg) in line or ("Fatal signal" in line) or ("FATAL EXCEPTION" in line and pkg in main):
            found.append(line)
    return found


def pid(pkg):
    return sh(f"pidof {pkg}", check=False).strip()


def launch(pkg):
    sh(f"monkey -p {pkg} -c android.intent.category.LAUNCHER 1", check=False)


def force_stop(pkg):
    sh(f"am force-stop {pkg}")


def run_as(pkg, cmd, **kw):
    return sh(f"run-as {pkg} {cmd}", **kw)


# ── GuzlikVoice: экраны ─────────────────────────────────────────────────────────

_MONTHS = ["января", "февраля", "марта", "апреля", "мая", "июня", "июля",
           "августа", "сентября", "октября", "ноября", "декабря"]


_MONTHS_NOM = ["январь", "февраль", "март", "апрель", "май", "июнь", "июль",
               "август", "сентябрь", "октябрь", "ноябрь", "декабрь"]


def history_pick_date(day, month, year):
    """История → «Изменить» → листаем календарь до нужного месяца → день → ОК."""
    tap(text="Изменить"); time.sleep(1.5)
    for _ in range(24):
        hdr = wait_for(rid="month_navigation_fragment_toggle")[0].text.lower()   # «октябрь 2026 г.»
        m = next(k for k, name in enumerate(_MONTHS_NOM, 1) if name in hdr)
        y = int(re.search(r"\d{4}", hdr).group())
        diff = (year - y) * 12 + (month - m)
        if diff == 0:
            break
        tap(contains="следующему месяцу" if diff > 0 else "предыдущему месяцу"); time.sleep(0.8)
    tap(contains=f" {day} {_MONTHS[month - 1]}"); time.sleep(0.5)
    tap(text="ОК"); time.sleep(2.5)


def history_times(max_swipes=20):
    """Время всех записей выбранного дня (скролл мелкими шагами, чтобы ничего не пропустить)."""
    seen, stale = [], 0
    for _ in range(max_swipes):
        before = len(seen)
        for n in dump():
            if re.fullmatch(r"\d\d:\d\d", n.text) and n.text not in seen:
                seen.append(n.text)
        stale = stale + 1 if len(seen) == before else 0
        if stale >= 3:
            break
        sh("input swipe 540 1600 540 1100 600"); time.sleep(0.8)
    return seen
