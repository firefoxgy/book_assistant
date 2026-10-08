"""Build bundled SQLite: python3 scripts/build_offline_dictionary.py ECDICT.csv OEWN.zip.

Inputs: skywind3000/ECDICT master/ecdict.csv, english-wordnet-2025-json.zip.
Retain entries with Chinese definitions; no frequency-based coverage reduction.
"""
import csv
import hashlib
import json
import pathlib
import re
import sqlite3
import sys
import zipfile

target = pathlib.Path(__file__).resolve().parents[1] / 'app/src/main/assets/dictionary-v1.db'
target.unlink(missing_ok=True)
db = sqlite3.connect(target)
db.executescript('''
CREATE TABLE entries (word TEXT PRIMARY KEY, phonetic TEXT, definition TEXT, chinese TEXT);
CREATE TABLE forms (form TEXT, word TEXT, PRIMARY KEY(form, word));
CREATE TABLE examples (word TEXT, sentence TEXT, PRIMARY KEY(word, sentence));
''')
word_forms = {}
def clean_text(text):
    return text.replace('\\r', '').replace('\\n', '\n')

with open(sys.argv[1], encoding='utf-8-sig', newline='') as source:
    for row in csv.DictReader(source):
        word = row['word'].strip().lower()
        if not word or not row['translation'].strip():
            continue
        db.execute('INSERT OR IGNORE INTO entries VALUES (?,?,?,?)', (
            word, row['phonetic'], clean_text(row['definition']), clean_text(row['translation'])))
        for exchange in row['exchange'].split('/'):
            kind, _, values = exchange.partition(':')
            if kind in ('p', 'd', 'i', '3', 'r', 't', 's'):
                for form in values.split(','):
                    if form:
                        db.execute('INSERT OR IGNORE INTO forms VALUES (?,?)', (form.lower(), word))
                        word_forms.setdefault(word, set()).add(form.lower())
            elif kind == '0' and values:
                db.execute('INSERT OR IGNORE INTO forms VALUES (?,?)', (word, values.lower()))
with zipfile.ZipFile(sys.argv[2]) as archive:
    for name in archive.namelist():
        if not name.endswith('.json') or name.startswith('entries-'):
            continue
        for synset in json.loads(archive.read(name)).values():
            if not isinstance(synset, dict):
                continue
            for member in synset.get('members', []):
                word = (member if isinstance(member, str) else member[0]).replace('_', ' ').lower()
                for example in synset.get('example', []):
                    sentence = example if isinstance(example, str) else example['text']
                    # Keep examples containing this word, not another synonym in the synset.
                    variants = [word, *sorted(word_forms.get(word, set()))]
                    if re.search(r'(?<!\w)(?:' + '|'.join(map(re.escape, variants)) + r')(?!\w)', sentence, re.I):
                        db.execute('INSERT OR IGNORE INTO examples VALUES (?,?)', (word, sentence))
db.commit()
db.execute('VACUUM')
counts = {table: db.execute('SELECT COUNT(*) FROM ' + table).fetchone()[0]
          for table in ('entries', 'forms', 'examples')}
counts['example_words'] = db.execute('SELECT COUNT(DISTINCT word) FROM examples').fetchone()[0]
counts['sources_sha256'] = {pathlib.Path(source).name: hashlib.sha256(pathlib.Path(source).read_bytes()).hexdigest()
                            for source in sys.argv[1:3]}
target.with_suffix('.json').write_text(json.dumps(counts, indent=2) + '\n')
print(counts)
db.close()
