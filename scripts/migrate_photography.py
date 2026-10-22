"""Upload the existing gallery once. Dry run by default; use --upload to send files."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--upload', action='store_true')
    args = parser.parse_args()
    photos = json.loads((ROOT / 'src/main/resources/photography-seed.json').read_text())
    static = ROOT / 'src/main/resources/static'
    for photo in photos:
        if not (static / photo['url'].lstrip('/')).is_file():
            sys.exit('Missing source photo: ' + photo['id'])
    if not args.upload:
        for photo in photos:
            print(photo['url'] + ' -> portfolio/photography/' + photo['id'])
        print(f'Dry run: {len(photos)} photos. Set Cloudinary environment variables and pass --upload to migrate.')
        return
    required = ['CLOUDINARY_CLOUD_NAME', 'CLOUDINARY_API_KEY', 'CLOUDINARY_API_SECRET']
    if any(not os.environ.get(name, '').strip() for name in required):
        sys.exit('Set CLOUDINARY_CLOUD_NAME, CLOUDINARY_API_KEY and CLOUDINARY_API_SECRET first.')
    cloud, key, secret = [os.environ[name].strip() for name in required]
    if not re.fullmatch(r'[a-zA-Z0-9_-]+', cloud):
        sys.exit('Invalid cloud name.')
    tag = os.environ.get('CLOUDINARY_GALLERY_TAG', 'portfolio-photography')
    failures = 0
    for index, photo in enumerate(photos):
        file = static / photo['url'].lstrip('/')
        alt = photo['alt'].replace('\\', '\\\\').replace('=', '\\=').replace('|', '\\|')
        params = {
            'context': f'alt={alt}|gallery_order={(index + 1) * 10}',
            'overwrite': 'false',
            'public_id': 'portfolio/photography/' + photo['id'],
            'tags': tag,
            'timestamp': str(int(time.time())),
        }
        signature_input = '&'.join(f'{k}={params[k]}' for k in sorted(params)) + secret
        params['signature'] = hashlib.sha1(signature_input.encode()).hexdigest()
        params['api_key'] = key
        boundary = uuid.uuid4().hex
        body = bytearray()
        for name, value in params.items():
            body.extend(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode())
        body.extend(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{file.name}"\r\nContent-Type: image/jpeg\r\n\r\n'.encode())
        body.extend(file.read_bytes())
        body.extend(f'\r\n--{boundary}--\r\n'.encode())
        request = urllib.request.Request(
            f'https://api.cloudinary.com/v1_1/{cloud}/image/upload', data=bytes(body),
            headers={'Content-Type': f'multipart/form-data; boundary={boundary}'})
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                result = json.load(response)
            print(('Already exists: ' if result.get('existing') else 'Uploaded: ') + photo['id'])
        except (urllib.error.URLError, TimeoutError, ValueError):
            # Don't print request credentials or the provider's response body.
            print('Upload failed: ' + photo['id'] + '. Check credentials, account limits and connectivity.')
            failures += 1
    if failures:
        sys.exit(f'{failures} upload(s) failed. Rerun to retry; existing assets will not be overwritten.')


if __name__ == '__main__':
    main()
