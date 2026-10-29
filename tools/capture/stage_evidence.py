"""Stage allowlisted real capture evidence; reject packages, symlinks and dumps."""
from pathlib import Path
import hashlib
import importlib.util
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('capture_evidence_types', ROOT / 'tools/qa/stage-public-evidence.py')
TYPES = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TYPES)
REPORTS = ('capture-source.json', 'capture-device.json', 'kvm-baseline.json',
           'one-job-approval.json', 'kvm-grant.json', 'kvm-cleanup.json')


def linked(root, path):
    relative = path.relative_to(root)
    return any((root / parent).is_symlink() for parent in (relative, *relative.parents))


def allowed_capture_name(name):
    if name.endswith('.mp4'):
        return re.fullmatch(r'(?:0[1-9]|1[0-5]|dev)-[a-z0-9-]+\.mp4', name) is not None
    if name.endswith('.png'):
        return re.fullmatch(r'(?:0[1-9]|1[0-5]|dev|FAILED)-[A-Za-z0-9_-]+\.png', name) is not None
    return (re.fullmatch(r'(?:0[1-9]|1[0-5]|dev|FAILED)-[A-Za-z0-9_-]+-geometry\.json', name) is not None or
            re.fullmatch(r'(?:recording|result|trackpad)-[A-Za-z0-9_-]+\.json', name) is not None)


def copy_checked(source, target):
    data = source.read_bytes()
    if not TYPES.permitted(data, source.suffix.lower()):
        raise ValueError('Rejected content type: ' + source.name)
    if source.suffix == '.json':
        json.loads(data)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
    return {'path': target.name, 'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data)}


def probe_video(path):
    result = subprocess.run(['ffprobe', '-v', 'error', '-count_frames', '-select_streams', 'v:0',
                             '-show_entries', 'stream=width,height,nb_read_frames,codec_name:format=duration',
                             '-of', 'json', str(path)], capture_output=True, text=True, check=True, timeout=120)
    info = json.loads(result.stdout)
    streams = info.get('streams', [])
    if len(streams) != 1 or int(streams[0].get('nb_read_frames', 0)) < 2:
        raise ValueError('Fewer than two decoded video frames')
    duration = float(info['format']['duration'])
    if not 0.8 <= duration <= 65:
        raise ValueError('Unexpected screenrecord duration')
    decoded = subprocess.run(['ffmpeg', '-v', 'error', '-i', str(path), '-f', 'framemd5', '-'],
                             capture_output=True, text=True, check=True, timeout=120)
    hashes = {line.rsplit(',', 1)[-1].strip() for line in decoded.stdout.splitlines()
              if line and not line.startswith('#')}
    return {'decode_passed': True, 'duration_seconds': duration, 'stream': streams[0],
            'distinct_decoded_frames': len(hashes), 'visual_review_required': True}


def stage(root, destination):
    root, destination = root.resolve(), destination.resolve()
    if destination.parent != root or destination.exists():
        raise ValueError('Evidence destination must be a new direct child of the checkout')
    destination.mkdir()
    included, excluded, clips = [], [], []
    for name in REPORTS:
        source = root / 'qa-artifacts' / name
        if source.is_file() and not linked(root, source):
            item = copy_checked(source, destination / 'reports' / name)
            item['path'] = 'reports/' + name
            included.append(item)
    capture = root / 'capture-output'
    candidates = [] if linked(root, capture) else sorted(capture.rglob('*'))
    for source in candidates:
        if linked(root, source) or not source.is_file():
            continue
        if not allowed_capture_name(source.name):
            excluded.append({'path': str(source.relative_to(root)), 'reason': 'not capture allowlist'})
            continue
        target = destination / 'raw' / source.relative_to(capture)
        item = None
        try:
            if not TYPES.permitted(source.read_bytes(), source.suffix.lower()):
                raise ValueError('Rejected capture content before decoder')
            if source.suffix == '.mp4':
                validation = probe_video(source)
            else:
                validation = None
                if source.suffix == '.png':
                    subprocess.run(['ffmpeg', '-v', 'error', '-i', str(source), '-frames:v', '1', '-f', 'null', '-'],
                                   capture_output=True, check=True, timeout=30)
            item = copy_checked(source, target)
            item['path'] = str(target.relative_to(destination))
            included.append(item)
            if validation is not None:
                name = source.stem
                proof_files = list(capture.rglob('recording-' + name + '.json'))
                proofs = [json.loads(p.read_text()) for p in proof_files if p.is_file() and not linked(root, p)]
                assertions = (len(proofs) == 1 and all(proofs[0].get(k) is True for k in
                              ('action_assertions_passed', 'recorder_startup_verified', 'recorder_finalized')))
                review = []
                for label, seconds in [('start', 0.5), ('middle', validation['duration_seconds']/2),
                                       ('end', max(0, validation['duration_seconds']-0.4))]:
                    preview = destination / 'review' / (name + '-' + label + '.png')
                    preview.parent.mkdir(exist_ok=True)
                    subprocess.run(['ffmpeg', '-v', 'error', '-ss', str(seconds), '-i', str(target),
                                    '-frames:v', '1', str(preview)], capture_output=True, check=True, timeout=30)
                    review.append(str(preview.relative_to(destination)))
                clips.append(item | validation | {'assertions_passed': assertions,
                    'classification': 'development-preview' if name.startswith('dev-') else 'public-feature',
                    'review_frames': review})
        except (ValueError, json.JSONDecodeError, subprocess.SubprocessError) as error:
            if target.exists():
                target.unlink()
            if item is not None and item in included:
                included.remove(item)
            for partial in (destination / 'review').glob(source.stem + '-*.png'):
                partial.unlink()
            excluded.append({'path': str(source.relative_to(root)), 'reason': type(error).__name__})
    plan = json.loads((root / 'tools/capture/feature-plan.json').read_text())
    manifest = {'policy': 'Raw real PNG/MP4 plus explicit JSON provenance/cleanup only; no APKs, keys, archives, dumps or build logs.',
                'product_source_sha': 'ce23cf74526b4864201d38503d2c9ad233d1787d',
                'clips': clips, 'included': included, 'excluded': excluded, 'feature_plan': plan,
                'runtime_success_claimed': False,
                'review_policy': 'Decode and assertion reports do not prove visible action coverage. Review extracted frames/full clip before editing. Masks are auxiliary source geometry, not screenshot pixels.'}
    (destination / 'feature-manifest.json').write_text(json.dumps(manifest, indent=2)+'\n')
    return manifest


if __name__ == '__main__':
    report = stage(Path.cwd(), Path.cwd() / 'teaser-public-evidence')
    print('Staged', len(report['clips']), 'decoded clips; visual review required. Non-allowlisted files excluded:', len(report['excluded']))
