#!/usr/bin/env python3
"""Capture real settings screens; fail closed when navigation cannot be verified."""
import os, re, subprocess, time, xml.etree.ElementTree as ET
from pathlib import Path
out = Path("qa-artifacts")
def adb(*args):
    return subprocess.check_output(["adb", *args], timeout=60)
def dump():
    adb("shell", "uiautomator", "dump", "/sdcard/settings-qa.xml")
    return adb("shell", "cat", "/sdcard/settings-qa.xml")
def tap(label):
    for _ in range(9):
        root = ET.fromstring(dump())
        for n in root.iter("node"):
            if n.get("text") == label or n.get("content-desc") == label:
                x1,y1,x2,y2 = map(int, re.findall(r"\d+", n.get("bounds", "")))
                adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
                time.sleep(2)
                return
        adb("shell", "input", "swipe", "300", "1000", "300", "400", "450")
        time.sleep(1)
    raise RuntimeError("Could not locate " + label)
for width, scale, label in [(720,"1.0","normal"),(640,"1.3","narrow"),(720,"2.0","large-text")]:
    adb("shell","wm","size",f"{width}x1280")
    adb("shell","wm","density","320")
    adb("shell","settings","put","system","font_scale",scale)
    for screen in ("Typing", "About", "Experimental"):
        adb("shell","am","force-stop","ai.boldkeys")
        adb("shell","am","start","-W","-n","ai.boldkeys/.SetupActivity")
        time.sleep(3)
        tap(screen)
        if screen == "Typing":
            tap("Layout and accents")
        for page in range(5):
            stem = f"{label}-{screen.lower()}-{page}"
            xml = dump()
            if b"ai.boldkeys" not in xml:
                raise RuntimeError("Expected Bold Keys foreground UI")
            (out / (stem+".xml")).write_bytes(xml)
            (out / (stem+".png")).write_bytes(adb("exec-out","screencap","-p"))
            adb("shell","input","swipe","300","1000","300","400","450")
            time.sleep(1)
    adb("shell","input","keyevent","KEYCODE_BACK")
(out/"capture-complete.txt").write_text("Captured normal, narrow and 200% text settings on a real Android emulator. Requires visual review.\n")
