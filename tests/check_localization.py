# SPDX-License-Identifier: GPL-3.0-only
"""Check complete translations and matching Android format arguments without an SDK."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'app/src/main/res'

def resources(folder):
    return {node.attrib['name']: node for node in ET.parse(RES / folder / 'strings.xml').getroot()}

def formats(text):
    # Literal percent signs are escaped as %% and are not format arguments.
    return sorted(re.findall(r'%(\d+\$)?[-+0 #,]*(?:\d+)?(?:\.\d+)?([sdf])', text.replace('%%', '')))

english, russian = resources('values'), resources('values-ru')
assert english.keys() == russian.keys(), 'Translation keys differ'
for key, node in english.items():
    translated = russian[key]
    assert node.tag == translated.tag, f'{key}: resource types differ'
    if node.tag == 'string':
        assert (node.text or '').strip('" ').strip(), f'{key}: empty English text'
        assert (translated.text or '').strip('" ').strip(), f'{key}: empty Russian text'
        assert formats(node.text or '') == formats(translated.text or ''), f'{key}: format arguments differ'
    elif node.tag == 'plurals':
        assert 'other' in {item.attrib['quantity'] for item in node}, f'{key}: missing English fallback'
        assert {'one', 'few', 'many', 'other'} <= {item.attrib['quantity'] for item in translated}, f'{key}: Russian plurals incomplete'
        expected = formats(node[0].text or '')
        assert all(formats(item.text or '') == expected for item in list(node) + list(translated)), f'{key}: plural arguments differ'

# Catch typos in resource references before Gradle generates the R class.
for path in (ROOT / 'app/src/main/java').rglob('*.kt'):
    for kind, key in re.findall(r'R\.(string|plurals)\.(\w+)', path.read_text()):
        assert key in english, f'{path.name}: missing resource {key}'
        assert english[key].tag == ('string' if kind == 'string' else 'plurals'), f'{path.name}: wrong resource type {key}'
print(f'Localization OK: {len(english)} matching resources, valid format arguments and references.')
