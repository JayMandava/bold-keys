#!/usr/bin/env python3
"""Capture real settings screens; fail closed when navigation cannot be verified."""
import os, re, subprocess, time, xml.etree.ElementTree as ET
from pathlib import Path
out = Path("qa-artifacts")
def adb(*args):
    return subprocess.check_output(["adb", *args], timeout=60)
def dump():
    # UiAutomator may report success while leaving an old file behind on a slow guest.
    for attempt in range(6):
        adb("shell", "rm", "-f", "/sdcard/settings-qa.xml")
        result = adb("shell", "uiautomator", "dump", "/sdcard/settings-qa.xml")
        try:
            xml = adb("shell", "cat", "/sdcard/settings-qa.xml")
            root = ET.fromstring(xml)
            if not list(root.iter("node")):
                raise ValueError("Empty accessibility tree")
            (out/"last-ui.xml").write_bytes(xml)
            return xml
        except (subprocess.CalledProcessError, ET.ParseError, ValueError):
            time.sleep(5)
    raise RuntimeError("Android accessibility tree never became ready")
def tap(label):
    for attempt in range(16):
        root = ET.fromstring(dump())
        for n in root.iter("node"):
            if n.get("text") == label or n.get("content-desc", "").startswith(label + ":") or n.get("content-desc") == label:
                x1,y1,x2,y2 = map(int, re.findall(r"\d+", n.get("bounds", "")))
                adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
                time.sleep(8)
                return
        if attempt >= 4:
            adb("shell", "input", "swipe", "300", "1000", "300", "400", "450")
        time.sleep(5)
    (out/"navigation-failure.png").write_bytes(adb("exec-out","screencap","-p"))
    raise RuntimeError("Could not locate " + label)
for width, scale, label in [(720,"1.0","normal"),(640,"1.3","narrow"),(720,"2.0","large-text")]:
    adb("shell","wm","size",f"{width}x1280")
    adb("shell","wm","density","320")
    adb("shell","settings","put","system","font_scale",scale)
    for screen in ("Typing", "Appearance", "Context model", "About", "Experimental"):
        adb("shell","am","force-stop","ai.boldkeys")
        adb("shell","am","start","-W","-n","ai.boldkeys/.SetupActivity")
        time.sleep(10)
        tap(screen)
        # Settings shares its list across destinations. Always return to the top
        # before looking for help or starting ordered visual coverage.
        for _ in range(12):
            adb("shell","input","swipe","300","350","300","1100","150")
        time.sleep(2)
        # Save the initial screen before attempting expansion for failure diagnosis.
        (out/f"{label}-{screen.lower()}-initial.png").write_bytes(adb("exec-out","screencap","-p"))
        if screen == "Typing":
            tap("Layout and accents")
        previous_xml = None
        for page in range(20):
            stem = f"{label}-{screen.lower()}-{page}"
            xml = dump()
            if b"ai.boldkeys" not in xml:
                raise RuntimeError("Expected Bold Keys foreground UI")
            (out / (stem+".xml")).write_bytes(xml)
            (out / (stem+".png")).write_bytes(adb("exec-out","screencap","-p"))
            if xml == previous_xml:
                break
            previous_xml = xml
            adb("shell","input","swipe","300","1000","300","400","450")
            time.sleep(1)
    adb("shell","input","keyevent","KEYCODE_BACK")
(out/"capture-complete.txt").write_text("Captured normal, narrow and 200% text settings on a real Android emulator. Requires visual review.\n")
