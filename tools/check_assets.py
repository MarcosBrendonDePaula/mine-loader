import json
from pathlib import Path

index = Path.home() / '.gradle/caches/fabric-loom/assets/indexes/1.21.1-17.json'
objects = Path.home() / '.gradle/caches/fabric-loom/assets/objects'
data = json.loads(index.read_text())
missing = []
for name, entry in data.get('objects', {}).items():
    digest = entry['hash']
    path = objects / digest[:2] / digest
    if not path.is_file() or path.stat().st_size != entry.get('size', path.stat().st_size):
        missing.append((name, digest, entry.get('size', 0)))
print(f'total={len(data.get("objects", {}))}')
print(f'missing={len(missing)}')
for name, digest, size in missing[:100]:
    print(f'{digest} {size} {name}')
