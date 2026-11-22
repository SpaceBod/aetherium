"""Checks Aetherium's language files against en_us.json: same keys, same placeholders, valid JSON.

Usage: python scripts/check_lang.py [lang_code ...]   (no codes: every file in the lang folder)
"""
import json
import os
import re
import sys
from collections import Counter

LANG_DIR = os.path.join(os.path.dirname(__file__), '..', 'common', 'src', 'client', 'resources', 'assets', 'aetherium', 'lang')
# %s, %d, %1$s and %% (a literal percent sign).
PLACEHOLDER = re.compile(r'%(?:(\d+)\$)?([sd%])')


def placeholders(text):
    found = Counter()
    position = 0
    for m in PLACEHOLDER.finditer(text):
        if m.group(2) == '%':
            found['%%'] += 1
            continue
        position += 1
        found[int(m.group(1)) if m.group(1) else position] += 1
    return found


def check(code, english):
    path = os.path.join(LANG_DIR, code + '.json')
    try:
        with open(path, encoding='utf-8') as f:
            data = json.load(f)
    except (OSError, ValueError) as e:
        return [f'cannot read: {e}']
    problems = []
    missing = [k for k in english if k not in data]
    extra = [k for k in data if k not in english]
    if missing:
        problems.append(f'{len(missing)} missing keys, e.g. {missing[:5]}')
    if extra:
        problems.append(f'{len(extra)} keys not in en_us, e.g. {extra[:5]}')
    for key, text in english.items():
        if key in data and placeholders(text) != placeholders(data[key]):
            problems.append(f'placeholders differ in {key}: {data[key]!r}')
        if key in data and not data[key].strip():
            problems.append(f'empty text in {key}')
    return problems


def main():
    with open(os.path.join(LANG_DIR, 'en_us.json'), encoding='utf-8') as f:
        english = json.load(f)
    codes = sys.argv[1:] or sorted(n[:-5] for n in os.listdir(LANG_DIR) if n.endswith('.json') and n != 'en_us.json')
    failed = False
    for code in codes:
        problems = check(code, english)
        print(f'{code}: ' + ('ok' if not problems else f'{len(problems)} problem(s)'))
        for p in problems[:20]:
            print('   ', p)
        failed |= bool(problems)
    sys.exit(1 if failed else 0)


if __name__ == '__main__':
    main()
