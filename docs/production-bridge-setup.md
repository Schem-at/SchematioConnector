# Production bridge signing key

On 5 September 2026, `https://schemat.io/.well-known/schematio-keys.json` returned
an empty key list. A Paper server cannot become verified by clients until the
backend can sign attestations and advertise its public key.

Configure these variables in the production application's secret/environment
manager:

- `SCHEMATIO_ATTEST_KEY_ID`: a stable identifier for this key, such as `k1`.
- `SCHEMATIO_ATTEST_SEED`: a base64-encoded, cryptographically random 32-byte seed.

Generate the seed on the production host or in the secret manager. Keep it out of
source control, terminal recordings, release notes, and client/plugin config.
Use a separate key for local development. Preserve the production key across
redeployments so previously issued attestations remain verifiable.

Refresh Laravel's configuration cache through the deployment's normal process.
Then run:

```sh
python3 scripts/check-backend.py
```

The endpoint must contain a unique key ID, `alg: Ed25519`, and a 32-byte public
key. Join a Paper server with a valid community token and confirm the client logs
`Server attestation VERIFIED`. Check both clipboard directions using a private
test schematic before publishing the article.

For rotation, set `SCHEMATIO_ATTEST_RETIRED_KEYS` to the previous public key entries
in `kid:base64pub` format, separated by commas. Never put an old private seed in
that variable. The implementation is in Schematio's `config/schematio.php` and the
attestation/key endpoints; the plugin and mod need no private signing keys.

The Release workflow checks the public document before creating a GitHub release.
It does not change production configuration.
