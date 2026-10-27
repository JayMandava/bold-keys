#!/usr/bin/env python3
"""Bounded normal-UI startup recovery, never a substitute for product assertions."""
import argparse
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path


COMPONENT = re.compile(r'([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+)/([.A-Za-z_$][.A-Za-z0-9_$]*)')


def normalized_component(value):
    match = COMPONENT.fullmatch(value.strip())
    if not match:
        raise ValueError('Invalid resolved Android HOME component')
    package, activity = match.groups()
    return package + '/' + (package + activity if activity.startswith('.') else activity)


def resolved_home(output):
    candidates = [line.strip() for line in output.splitlines() if COMPONENT.fullmatch(line.strip())]
    if len(candidates) != 1:
        raise ValueError('Android did not resolve exactly one default HOME activity')
    return normalized_component(candidates[0])


def home_is_focused(focus, home):
    # Match the actual resolved component, not an image-specific launcher package.
    components = [normalized_component(match.group()) for match in COMPONENT.finditer(focus)]
    return components == [home]


def wait_button(xml, focus):
    """Select only the observed System UI ANR's ordinary Wait action."""
    if 'Application Not Responding: com.android.systemui' not in focus:
        raise ValueError('Not the verified System UI startup dialog')
    nodes = list(ET.fromstring(xml).iter('node'))
    texts = [n.get('text', '').replace('\u2019', "'") for n in nodes]
    if "System UI isn't responding" not in texts:
        raise ValueError('System UI ANR title absent from actual hierarchy')
    targets = [n for n in nodes if n.get('text') == 'Wait' and n.get('package') == 'android'
               and n.get('resource-id') == 'android:id/aerr_wait' and n.get('clickable') == 'true']
    if len(targets) != 1:
        raise ValueError('Wait action is not uniquely verified')
    match = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', targets[0].get('bounds', ''))
    if not match:
        raise ValueError('Missing actual Wait bounds')
    x1, y1, x2, y2 = map(int, match.groups())
    if not (0 <= x1 < x2 <= 720 and 0 <= y1 < y2 <= 1280):
        raise ValueError('Wait action outside configured display')
    return (x1 + x2) // 2, (y1 + y2) // 2


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--timeout', type=int, required=True)
    args = parser.parse_args()
    if not 1 <= args.timeout <= 600:
        parser.error('Readiness must fit the remaining startup budget, at most ten minutes')
    output = Path('qa-artifacts/readiness')
    output.mkdir(parents=True, exist_ok=True)
    deadline = time.monotonic() + args.timeout
    events = []
    recovered = False
    quiet_since = time.monotonic()
    last_anrs = None
    home_since = None

    def record(event, **fields):
        events.append({'event': event, 'monotonic': time.monotonic(), **fields})
        (output / 'events.json').write_text(json.dumps(events, indent=2) + '\n')
        print(event, fields, flush=True)

    def adb(*arguments, timeout=20, binary=False):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError('Ordinary UI readiness budget expired')
        result = subprocess.run(['adb', *arguments], capture_output=True, timeout=min(timeout, remaining), check=True)
        return result.stdout if binary else result.stdout.decode(errors='replace')

    def hierarchy(name):
        adb('shell', 'uiautomator', 'dump', '/sdcard/Download/bold-readiness.xml', timeout=45)
        xml = adb('exec-out', 'cat', '/sdcard/Download/bold-readiness.xml')
        (output / (name + '.xml')).write_text(xml)
        return xml

    try:
        if adb('shell', 'id', '-u').strip() != '2000':
            raise RuntimeError('Guest must retain normal shell identity')
        resolution = adb('shell', 'cmd', 'package', 'resolve-activity', '--brief', '--user', '0',
                         '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
        (output / 'resolved-home.txt').write_text(resolution)
        home = resolved_home(resolution)
        home_package = home.partition('/')[0]
        record('Resolved default Android HOME activity', component=home)
        adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
        while time.monotonic() < deadline:
            window = adb('shell', 'dumpsys', 'window')
            (output / 'latest-windows.txt').write_text(window)
            focus = next((line.strip() for line in window.splitlines() if 'mCurrentFocus=' in line), '')
            logs = adb('logcat', '-d', '-v', 'threadtime', '-s', 'ActivityManager:E')
            (output / 'system-errors.txt').write_text(logs)
            anrs = [line for line in logs.splitlines() if 'ANR in ' in line]
            if anrs != last_anrs:
                quiet_since = time.monotonic()
                last_anrs = anrs
                record('System ANR history updated; restarting quiet period', count=len(anrs))
            if 'Application Not Responding:' in focus:
                (output / 'startup-dialog.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
                xml = hierarchy('startup-dialog')
                if recovered:
                    raise RuntimeError('Another ANR dialog appeared after the single startup recovery')
                x, y = wait_button(xml, focus)
                record('Observed System UI startup ANR; pressing its verified Wait action once', focus=focus)
                adb('shell', 'input', 'tap', str(x), str(y))
                recovered = True
                home_since = None
                quiet_since = time.monotonic()
            elif home_is_focused(focus, home):
                if home_since is None:
                    home_since = time.monotonic()
                if time.monotonic() - home_since >= 30 and time.monotonic() - quiet_since >= 60:
                    xml = hierarchy('ready-home')
                    if not any(n.get('package') == home_package for n in ET.fromstring(xml).iter('node')):
                        raise RuntimeError('Focused launcher is not present in actual hierarchy')
                    (output / 'ready-home.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
                    record('Ordinary home UI ready; product tests still required', recovered_startup_dialog=recovered)
                    return
            else:
                home_since = None
            time.sleep(min(5, max(0, deadline - time.monotonic())))
        raise TimeoutError('No stable launcher and sixty-second ANR-free interval within readiness budget')
    except Exception as error:
        record('Readiness failed; do not start product tests', error=str(error))
        raise


if __name__ == '__main__':
    main()
