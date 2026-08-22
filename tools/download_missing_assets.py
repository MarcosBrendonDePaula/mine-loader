from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
from pathlib import Path
from urllib.request import Request, urlopen

INDEX = Path.home() / '.gradle/caches/fabric-loom/assets/indexes/1.21.1-17.json'
OBJECTS = Path.home() / '.gradle/caches/fabric-loom/assets/objects'
MAX_WORKERS = 16
TIMEOUT = 30


def is_valid(path: Path, entry: dict) -> bool:
    return path.is_file() and path.stat().st_size == entry.get('size', path.stat().st_size)


def download(item):
    name, entry = item
    digest = entry['hash']
    destination = OBJECTS / digest[:2] / digest
    if is_valid(destination, entry):
        return True, name, 'cached'
    destination.parent.mkdir(parents=True, exist_ok=True)
    url = f'https://resources.download.minecraft.net/{digest[:2]}/{digest}'
    temporary = destination.with_suffix('.part')
    try:
        request = Request(url, headers={'User-Agent': 'Minecraft-Lua-Loader-asset-prep/0.1'})
        with urlopen(request, timeout=TIMEOUT) as response, temporary.open('wb') as output:
            digest_hash = hashlib.sha1()
            total = 0
            while True:
                chunk = response.read(64 * 1024)
                if not chunk:
                    break
                output.write(chunk)
                digest_hash.update(chunk)
                total += len(chunk)
        if digest_hash.hexdigest() != digest or total != entry.get('size', total):
            temporary.unlink(missing_ok=True)
            return False, name, 'hash-or-size-mismatch'
        temporary.replace(destination)
        return True, name, 'downloaded'
    except Exception as error:
        temporary.unlink(missing_ok=True)
        return False, name, type(error).__name__


def main():
    data = json.loads(INDEX.read_text())
    missing = []
    for item in data.get('objects', {}).items():
        name, entry = item
        digest = entry['hash']
        path = OBJECTS / digest[:2] / digest
        if not is_valid(path, entry):
            missing.append(item)
    print(f'missing_before={len(missing)}', flush=True)
    downloaded = 0
    failed = []
    with ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
        futures = [executor.submit(download, item) for item in missing]
        for index, future in enumerate(as_completed(futures), 1):
            ok, name, status = future.result()
            if ok:
                downloaded += 1
            else:
                failed.append((name, status))
            if index % 100 == 0 or index == len(futures):
                print(f'progress={index}/{len(futures)} downloaded={downloaded} failed={len(failed)}', flush=True)
    print(f'missing_after={len(failed)}', flush=True)
    for name, status in failed[:50]:
        print(f'FAILED {status} {name}', flush=True)


if __name__ == '__main__':
    main()
