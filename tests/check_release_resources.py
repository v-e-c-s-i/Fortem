# SPDX-License-Identifier: GPL-3.0-only
"""Release packages must not ship the temporary test recording catalog."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
assert not (root / 'app/src/main/assets/demos/fortem-demos.json').exists()
project = root / 'apple/Fortem.xcodeproj/project.pbxproj'
if project.exists():
    assert 'fortem-demos.json' not in project.read_text()
print('Release resources OK: no bundled test recording catalog.')
