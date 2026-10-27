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


def home_snapshot(output):
    # No resolved HOME can be a normal, bounded handoff between provisioning and a launcher.
    # Multiple or malformed candidates are never accepted or silently guessed.
    if output.strip() in ('', 'No activity found'):
        return None
    return resolved_home(output)


def provisioning_flag(output):
    value = output.strip()
    if value not in ('0', '1', 'null'):
        raise ValueError('Unexpected provisioning flag; do not assume setup completed')
    return value == '1'


def home_is_focused(focus, home):
    # Resolve/query user 0 and require that user's actual focused Activity window.
    match = re.fullmatch(r'mCurrentFocus=Window\{[^{}\s]+\s+u0\s+([^{}\s]+)\}', focus.strip())
    if not match:
        return False
    try:
        return normalized_component(match.group(1)) == home
    except ValueError:
        return False


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

    last_identity = None
    server_pid = None
    ever_provisioned = False
    observation_count = 0

    def observe(phase):
        nonlocal observation_count
        boot = adb('shell', 'getprop', 'sys.boot_completed').strip()
        pid = adb('shell', 'pidof', 'system_server').strip()
        device_ready = provisioning_flag(adb('shell', 'settings', 'get', 'global', 'device_provisioned'))
        user_ready = provisioning_flag(adb('shell', 'settings', '--user', '0', 'get', 'secure', 'user_setup_complete'))
        resolution = adb('shell', 'cmd', 'package', 'resolve-activity', '--brief', '--user', '0',
                         '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
        (output / 'resolved-home.txt').write_text(resolution)
        if observation_count == 0:
            (output / 'initial-resolved-home.txt').write_text(resolution)
        home = home_snapshot(resolution)
        window = adb('shell', 'dumpsys', 'window')
        (output / 'latest-windows.txt').write_text(window)
        focus_lines = [line.strip() for line in window.splitlines() if line.strip().startswith('mCurrentFocus=')]
        if len(focus_lines) > 1:
            raise RuntimeError('Ambiguous focused window; no default-display UI proof')
        focus = focus_lines[0] if focus_lines else ''
        logs = adb('logcat', '-d', '-v', 'threadtime', '-s', 'ActivityManager:E', 'Watchdog:W', 'AndroidRuntime:E')
        (output / 'system-errors.txt').write_text(logs)
        snapshot = {'phase': phase, 'monotonic': time.monotonic(), 'boot_completed': boot,
                    'system_server_pid': pid, 'device_provisioned': device_ready,
                    'user_setup_complete': user_ready, 'home': home, 'focus': focus,
                    'anrs': [line for line in logs.splitlines() if 'ANR in ' in line],
                    'fatal': [line for line in logs.splitlines()
                              if 'FATAL EXCEPTION' in line or 'WATCHDOG KILLING SYSTEM PROCESS' in line]}
        with (output / 'observations.jsonl').open('a') as stream:
            stream.write(json.dumps(snapshot) + '\n')
        observation_count += 1
        return snapshot

    def inspect(snapshot):
        nonlocal last_identity, server_pid, ever_provisioned, home_since, quiet_since, last_anrs
        if snapshot['boot_completed'] != '1':
            raise RuntimeError('Android boot-completed state regressed during readiness')
        pid = snapshot['system_server_pid']
        if not re.fullmatch(r'[1-9][0-9]*', pid):
            raise RuntimeError('Cannot verify one live system_server process')
        if server_pid is not None and server_pid != pid:
            raise RuntimeError('system_server restarted during readiness; preserve the failure')
        server_pid = pid
        if snapshot['fatal']:
            raise RuntimeError('System fatal exception or watchdog failure observed before product tests')
        provisioned = snapshot['device_provisioned'] and snapshot['user_setup_complete']
        if ever_provisioned and not provisioned:
            raise RuntimeError('Completed Android provisioning regressed during readiness')
        ever_provisioned = ever_provisioned or provisioned
        identity = (snapshot['device_provisioned'], snapshot['user_setup_complete'], snapshot['home'])
        if identity != last_identity:
            record('Provisioning or resolved HOME changed; restarting stability gates',
                   previous=last_identity, current=identity)
            last_identity = identity
            home_since = None
            quiet_since = time.monotonic()
        if snapshot['anrs'] != last_anrs:
            quiet_since = time.monotonic()
            last_anrs = snapshot['anrs']
            record('System ANR history updated; restarting quiet period', count=len(last_anrs))
        matches = provisioned and snapshot['home'] is not None and home_is_focused(snapshot['focus'], snapshot['home'])
        if not matches:
            home_since = None
        elif home_since is None:
            home_since = time.monotonic()
        return matches

    def stable():
        return (home_since is not None and time.monotonic() - home_since >= 30
                and time.monotonic() - quiet_since >= 60)

    try:
        if adb('shell', 'id', '-u').strip() != '2000':
            raise RuntimeError('Guest must retain normal shell identity')
        # A normal HOME press does not complete, skip or modify Android provisioning.
        adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
        while time.monotonic() < deadline:
            snapshot = observe('poll')
            matches = inspect(snapshot)
            focus = snapshot['focus']
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
            elif matches and stable():
                candidate_home = snapshot['home']
                xml = hierarchy('candidate-home')
                after_hierarchy = observe('after-hierarchy')
                if inspect(after_hierarchy) and stable() and after_hierarchy['home'] == candidate_home:
                    package = candidate_home.partition('/')[0]
                    if not any(n.get('package') == package for n in ET.fromstring(xml).iter('node')):
                        raise RuntimeError('Focused launcher is not present in actual hierarchy')
                    screenshot = adb('exec-out', 'screencap', '-p', binary=True)
                    (output / 'candidate-home.png').write_bytes(screenshot)
                    after_capture = observe('after-capture')
                    if inspect(after_capture) and stable() and after_capture['home'] == candidate_home:
                        (output / 'ready-home.xml').write_text(xml)
                        (output / 'ready-home.png').write_bytes(screenshot)
                        record('Ordinary home UI ready; product tests still required',
                               component=candidate_home, recovered_startup_dialog=recovered)
                        return
                record('Android state changed during evidence capture; continuing within original deadline')
            time.sleep(min(5, max(0, deadline - time.monotonic())))
        raise TimeoutError('No provisioned, stable HOME and sixty-second healthy interval within readiness budget')
    except Exception as error:
        record('Readiness failed; do not start product tests', error=str(error))
        raise


if __name__ == '__main__':
    main()
