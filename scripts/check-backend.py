#!/usr/bin/env python3
"""Require a usable public attestation key before publishing the Paper bridge."""
import argparse
import base64
import json
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--origin', default='https://schemat.io')
    args = parser.parse_args()
    url = args.origin.rstrip('/') + '/.well-known/schematio-keys.json'
    request = urllib.request.Request(url, headers={'User-Agent': 'SchematioConnector-release-check/1.0'})
    document = json.load(urllib.request.urlopen(request, timeout=20))
    keys = document.get('keys', [])
    assert keys, f'{url} has no public keys; configure SCHEMATIO_ATTEST_SEED before publishing the bridge'
    ids = set()
    for key in keys:
        assert key.get('alg') == 'Ed25519', 'Unsupported attestation key algorithm'
        assert key.get('kid') and key['kid'] not in ids, 'Missing or duplicate key ID'
        assert len(base64.b64decode(key['key'], validate=True)) == 32, 'Invalid Ed25519 public key'
        ids.add(key['kid'])
    print(f'{url}: {len(keys)} usable public attestation key(s)')


if __name__ == '__main__':
    main()
