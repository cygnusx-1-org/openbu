#!/usr/bin/python

import re, json, subprocess

result = subprocess.run(
    ['wget', 'https://wiki.bambulab.com/en/hms/home', '-O', 'hms.html', '-o', '/dev/null']
)

if result.returncode != 0:
    print("HMS data error")
    exit(1)

with open('hms.html', 'r', encoding='utf-8') as f:
    html = f.read()

# Split into blockquotes, each representing one HMS error entry
blocks = re.split(r'<blockquote>', html)

lookup = {}

for block in blocks:
    # Extract wiki URLs (model, primary_code) from hrefs in this block
    href_pattern = r'href="https://wiki\.bambulab\.com/en/([^/]+)/troubleshooting/hmscode/([^"]+)"'
    hrefs = re.findall(href_pattern, block)
    if not hrefs:
        continue

    primary = hrefs[0][1].upper().replace('-', '_')

    paths = []
    for model, code in hrefs:
        model = model.lower()
        if model not in paths:
            paths.append(model)

    # Extract synonyms from <small>...</small> after "Synonyms:"
    synonym_match = re.search(r'Synonyms:.*?<small>(.*?)</small>', block, re.DOTALL)
    if synonym_match:
        raw = synonym_match.group(1)
        codes = [c.strip().upper().replace('-', '_') for c in re.split(r'[,\s]+', raw) if c.strip()]
    else:
        codes = [primary]

    for code in codes:
        if not code:
            continue
        if code not in lookup:
            lookup[code] = {}
        lookup[code]['primary'] = primary
        if 'paths' not in lookup[code]:
            lookup[code]['paths'] = []
        for path in paths:
            if path not in lookup[code]['paths']:
                lookup[code]['paths'].append(path)

for code in lookup:
    lookup[code]['paths'].sort()
lookup = dict(sorted(lookup.items()))

with open('assets/hms-errors.json', 'w', encoding='utf-8') as f:
    json.dump(lookup, f, indent=2)

print(f"Written {len(lookup)} codes to assets/hms-errors.json")
