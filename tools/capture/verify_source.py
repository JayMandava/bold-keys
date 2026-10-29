"""Read-only product-source gate for the reviewed URL-fix and capture branch."""
from pathlib import Path
import json
import subprocess

BASE = 'e75b623f2cdac7b496e3f04695dd783d6f9fa0ca'
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
            'product_tree_objects': actual, 'unchanged_product_source': True,
            'previous_product_source_sha': 'ce23cf74526b4864201d38503d2c9ad233d1787d',
            'approved_fix': 'URI Link field and URI-only English i capitalisation guard'}


if __name__ == '__main__':
    result = verify()
    Path('qa-artifacts').mkdir(exist_ok=True)
    Path('qa-artifacts/capture-source.json').write_text(json.dumps(result, indent=2) + '\n')
    print('Verified exact corrected product source; capture tooling is separate.')
