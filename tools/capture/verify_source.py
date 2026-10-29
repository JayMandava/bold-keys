"""Read-only product-source gate for the approved, capture-only branch."""
from pathlib import Path
import json
import subprocess

BASE = 'ce23cf74526b4864201d38503d2c9ad233d1787d'
PRODUCT_PATHS = ('app', 'third_party', '.gitmodules', 'build.gradle.kts',
                 'settings.gradle.kts', 'gradle.properties', 'gradle', 'gradlew', 'gradlew.bat')


def git(*args):
    return subprocess.check_output(['git', *args], text=True).strip()


def verify():
    subprocess.run(['git', 'merge-base', '--is-ancestor', BASE, 'HEAD'], check=True)
    actual = {}
    for path in PRODUCT_PATHS:
        expected = git('rev-parse', BASE + ':' + path)
        actual[path] = git('rev-parse', 'HEAD:' + path)
        if actual[path] != expected:
            raise RuntimeError('Product source changed outside capture tooling: ' + path)
    if git('diff', '--name-only', 'HEAD', '--', *PRODUCT_PATHS):
        raise RuntimeError('Uncommitted product source changes')
    return {'product_source_sha': BASE, 'capture_commit': git('rev-parse', 'HEAD'),
            'product_tree_objects': actual, 'unchanged_product_source': True}


if __name__ == '__main__':
    result = verify()
    Path('qa-artifacts').mkdir(exist_ok=True)
    Path('qa-artifacts/capture-source.json').write_text(json.dumps(result, indent=2) + '\n')
    print('Verified exact corrected product source; capture tooling is separate.')
