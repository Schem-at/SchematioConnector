# IPC Sub-project A — Verified Handshake (Protocol v2, Ed25519 Attestation)

**Spec:** `docs/superpowers/specs/2026-07-10-connector-cleanup-handshake-v2-design.md`, section "Phase 2 — Verified handshake (protocol v2)". Phase 1 is already merged.

## Goal

A client that joins a Schematio-enabled server learns, with cryptographic proof, that the
server's plugin is bound to a real community on the client's own backend:

```
S→C  HELLO_SERVER   (v2: platform, versions, backendHost, communityId/slug; unattested)
C→S  HELLO_CLIENT   (v2: 16-byte SecureRandom nonce)
S→B  POST /api/v1/plugin/attest {nonce_hex, platform}     (community JWT)
S→C  ATTEST         (backend-signed attestation, relayed verbatim)
```

The client verifies the Ed25519 signature against keys fetched from the backend **it** is
configured against (never the server-claimed `backendHost`), checks the nonce, freshness, and
community binding, and lands on a trust state. Failure paths never block gameplay or v1 features.

## Architecture

- **schemati (Laravel 12)**: Ed25519 seed in `config/schematio.php`; a key service derives the
  keypair via libsodium; `GET /.well-known/schematio-keys.json` (public, rotation-capable list);
  `POST /api/v1/plugin/attest` under the existing community-JWT plugin route group, rate-limited
  30/min per token, audited via `CommunityTokenAudit`.
- **:core (Kotlin, shared)**: protocol `VERSION = 2`; extended `HelloServer`/`HelloClient`; new
  `ATTEST` opcode 4; `crypto/Ed25519` verify (JDK `java.security`, zero deps);
  `attest/AttestationVerifier` (pure, clock-injectable); `attest/AttestationClient` (HTTP POST via
  existing `ApiTransport`, nonce-keyed cache, 5 s timeout).
- **bukkit plugin**: `PluginIpcService` sends v2 `HELLO_SERVER` (Paper platform/software/versions,
  backend origin, community identity cached at connect time); on nonce-bearing `HELLO_CLIENT`,
  requests attestation off-thread and relays `ATTEST` on the main thread.
- **fabric client**: `ServerSession` gains v2 fields + `TrustState { NONE, LEGACY_V1, UNVERIFIED,
  VERIFIED }` + per-connection nonce; `BackendKeyCache` fetches/caches the well-known doc from the
  client's configured backend (refetch once on unknown `kid` = rotation); `AttestFlow` verifies
  off-thread and updates the session.

**Out of scope for Sub-project A** (other sub-projects / later phases): the toolbar connection
indicator UI, `ActionRouter`, the fabric *server* gaining the HELLO_SERVER/ATTEST sender role,
Phase 3 parity, releasing/tagging v1.4.0.

## Tech stack

- SchematioConnector: Kotlin 2.4, JDK 21, Gradle + Stonecutter, JUnit 5 + kotlin-test +
  kotlinx-coroutines-test, gson, Apache HttpClient (existing `ApiTransport`/`HttpTransport`).
- schemati: Laravel 12, Pest, libsodium (`sodium_crypto_sign_*`, bundled with PHP), SQLite
  in-memory tests.

## Global Constraints

- **Do NOT git commit in either repo (user preference — skip every commit step).** Leave both
  trees dirty for review. Stay on branch `feature/ingame-diff-viewer` in SchematioConnector.
- **Touch only the files each task names.** The schemati tree is dirty with unrelated in-flight
  work — never reformat, revert, or "fix" anything a task does not name.
- **Test commands:**
  - SchematioConnector (always prefix with JAVA_HOME):
    - `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:test`
    - `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :bukkit:test`
    - `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:1.21.11:test`
    (run from `/Users/harrison/IdeaProjects/SchematioConnector`)
  - schemati (run from `/Users/harrison/Documents/code/schemati`):
    - `php artisan test --filter=AttestationKeysEndpoint`
    - `php artisan test --filter=PluginAttest`
- schemati tests MUST live under `tests/Feature/` (Unit tests have no app/DB) and each file MUST
  declare `uses(RefreshDatabase::class);` itself (not global). Pest helper function names are
  global — prefix them (`attest…`) to avoid collisions.
- TDD per task: write the failing test, run it and see it fail, implement, run it and see it pass.

---

## Cross-Repo Interface Contract (single source of truth)

Every task below references this section verbatim. **Any deviation here is the #1 failure mode.**

### C1. Canonical attestation payload

JSON object with **exactly these five fields**, keys sorted ASCII-ascending, **no whitespace**,
signed as UTF-8 bytes:

```json
{"communityId":"<community uuid>","issuedAt":<unix seconds, integer>,"nonce":"<32 lowercase hex chars = 16 bytes>","platform":"PAPER_PLUGIN"|"FABRIC_SERVER","tokenId":"<community-token jti uuid>"}
```

Key order is `communityId, issuedAt, nonce, platform, tokenId` (ASCII sort — enforced by
`ksort()` on the PHP side). PHP produces it with
`json_encode($claims, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE)` after `ksort($claims)`.
**The JVM never re-serializes**: the backend returns the exact signed string, the game server
relays it verbatim inside `ATTEST.payloadJson`, and the client verifies the signature over the
received string's UTF-8 bytes, then parses it. Byte-exactness therefore only has to hold in one
place (PHP).

### C2. Signature & keys

- Algorithm: **Ed25519** detached signature, 64 bytes. PHP: `sodium_crypto_sign_detached`.
  JVM: `java.security.Signature.getInstance("Ed25519")` (JDK ≥ 15, project is JDK 21 — no deps).
- Private key config: **32-byte Ed25519 seed**, standard base64 (RFC 4648, padded), env
  `SCHEMATIO_ATTEST_SEED`. Keypair derived with `sodium_crypto_sign_seed_keypair`.
- Public keys on the wire: **raw 32-byte key**, standard base64 (padded).
- JVM raw→`PublicKey` conversion: prepend the 12-byte X.509/DER header
  `30 2a 30 05 06 03 2b 65 70 03 21 00` to the raw 32 bytes and feed `X509EncodedKeySpec` to
  `KeyFactory.getInstance("Ed25519")`.

### C3. Golden vector (verified end-to-end during planning: PHP sodium sign → JDK 21 verify = true)

- Seed (32 × `0x42`), base64: `QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI=`
- Public key, base64: `IVL40Zt5HSRFMkLhXy6rbLfP+ntqXtMAl5YOBpiB2xI=`
- Payload (exact string):
  `{"communityId":"11111111-2222-3333-4444-555555555555","issuedAt":1760000000,"nonce":"000102030405060708090a0b0c0d0e0f","platform":"PAPER_PLUGIN","tokenId":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"}`
- Signature, base64:
  `WgS1p0Ug618bFMzOPVGYanT9ZwKUe8eRPStRN9KxTRSWqbCJc0vLs3SJcws6qsR8V69P8v/s+p6Xe1rpXgaJCw==`

### C4. `GET /.well-known/schematio-keys.json` (public, no auth, `Cache-Control: public, max-age=300`)

```json
{"keys":[{"kid":"<key id>","alg":"Ed25519","key":"<base64 raw 32-byte public key>"}]}
```

List form enables rotation: the active key first, then retired-but-still-valid public keys.
Unconfigured backend serves `{"keys":[]}` with 200.

### C5. `POST /api/v1/plugin/attest` (community JWT required; the spec's `/api/plugin/attest`
resolves to `/api/v1/plugin/attest` — this codebase's plugin group lives under the `v1` prefix
and the plugin's `api-endpoint` config already ends in `/api/v1`)

Request: `{"nonce_hex":"<32 hex chars, case-insensitive>","platform":"PAPER_PLUGIN"|"FABRIC_SERVER"}`
Response 200: `{"payload":"<C1 canonical string>","signature_base64":"<C2, 64 bytes b64>","key_id":"<kid>"}`
Errors: 403 `{"error":"community_token_required",...}` (non-community JWT) · 422 (validation) ·
429 (rate limit `attest`, 30/min per bearer token) · 503 `{"error":"attestation_unavailable"}`
(no seed configured). `nonce_hex` is lowercased into the payload.

### C6. IPC wire format v2 (`IpcProtocol.VERSION = 2`; primitives are the existing IpcWriter/IpcReader varint/string/bytes)

- `HELLO_SERVER` (opcode 1): `byte opcode, varint protocolVersion, string pluginVersion,
  varint capabilities` — then **iff protocolVersion ≥ 2**: `varint platform (0=PAPER_PLUGIN,
  1=FABRIC_SERVER), string serverSoftware, string mcVersion, string backendHost,
  string communityId, string communitySlug`.
- `HELLO_CLIENT` (opcode 2): `byte opcode, varint protocolVersion, string modVersion,
  varint clientFlags` — then **iff protocolVersion ≥ 2**: `bytes nonce` (varint length + raw;
  16 bytes when attestation is wanted, length 0 = "no nonce, skip attestation").
- `ATTEST` (opcode 4): `byte opcode, varint protocolVersion, string payloadJson,
  bytes signature (64), string keyId`.
- **v1 compat:** decoders read the v2 tail only when the *sent* protocolVersion ≥ 2 and bytes
  remain; v1 peers ignore trailing bytes (IpcReader never asserts full consumption), so a v2
  HELLO is safely readable by a v1 peer. A v1 `HELLO_SERVER` yields trust `LEGACY_V1`; a v2
  server receiving a nonce-less `HELLO_CLIENT` skips attestation.
- `backendHost` = the plugin's `api-endpoint` origin (strip trailing `/api/v<N>`), e.g.
  `https://schemat.io`. Empty string = unknown/not yet fetched (fields may race the plugin's
  async backend connect; empty is legal and leaves the client at UNVERIFIED).

### C7. Client verification rules (fabric)

Signature valid against a key **from the client's own configured backend** (`ClientAuthManager.apiEndpoint`
origin) for `ATTEST.keyId` (one forced refetch on unknown kid = rotation) · payload `nonce` ==
lowercase hex of the nonce the client sent this connection · `|now - issuedAt| ≤ 600` s ·
payload `communityId` == `HELLO_SERVER.communityId` (when the server claimed one). Any failure →
stay `UNVERIFIED` (log, no user-facing error). Trust: `NONE` → (v1 hello) `LEGACY_V1` | (v2
hello) `UNVERIFIED` → (verified ATTEST) `VERIFIED`. Reset to `NONE` + fresh nonce on join/disconnect.

---

## Task 1 — schemati: attestation config + key service + well-known endpoint

**Files**
- Create: `/Users/harrison/Documents/code/schemati/config/schematio.php`
- Create: `/Users/harrison/Documents/code/schemati/app/Services/Attestation/AttestationKeyService.php`
- Create: `/Users/harrison/Documents/code/schemati/app/Http/Controllers/Api/PluginAttestController.php` (keys action only; attest lands in Task 2)
- Modify: `/Users/harrison/Documents/code/schemati/routes/web.php` (one route, added right after the `/up` health check)
- Test: `/Users/harrison/Documents/code/schemati/tests/Feature/Api/AttestationKeysEndpointTest.php`

**Interfaces**
- Consumes: contract C2, C3, C4; `config('schematio.attestation.*')`.
- Produces: `AttestationKeyService::isConfigured(): bool`, `keyId(): string`,
  `publicKeys(): array` (list of `{kid, alg, key}`),
  `signAttestation(array $claims): array{payload: string, signature: string}` (C1 canonical form,
  signature base64); route `GET /.well-known/schematio-keys.json` (C4).

**Step 1.1 — failing test.** Write the test file:

```php
<?php

use Illuminate\Foundation\Testing\RefreshDatabase;

uses(RefreshDatabase::class);

beforeEach(function () {
    config([
        'schematio.attestation.key_id' => 'test-k1',
        'schematio.attestation.seed' => 'QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI=',
        'schematio.attestation.retired_public_keys' => '',
    ]);
});

it('serves the well-known key document with the derived public key', function () {
    $response = $this->getJson('/.well-known/schematio-keys.json');

    $response->assertOk()
        ->assertHeader('Cache-Control', 'max-age=300, public')
        ->assertJsonCount(1, 'keys')
        ->assertJsonPath('keys.0.kid', 'test-k1')
        ->assertJsonPath('keys.0.alg', 'Ed25519')
        // Golden vector: public key derived from the fixed 0x42 seed (contract C3).
        ->assertJsonPath('keys.0.key', 'IVL40Zt5HSRFMkLhXy6rbLfP+ntqXtMAl5YOBpiB2xI=');

    expect(strlen(base64_decode($response->json('keys.0.key'), true)))->toBe(32);
});

it('lists retired public keys after the active key (rotation)', function () {
    config([
        'schematio.attestation.retired_public_keys' =>
            'old-k0:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=',
    ]);

    $this->getJson('/.well-known/schematio-keys.json')
        ->assertOk()
        ->assertJsonCount(2, 'keys')
        ->assertJsonPath('keys.0.kid', 'test-k1')
        ->assertJsonPath('keys.1.kid', 'old-k0')
        ->assertJsonPath('keys.1.alg', 'Ed25519')
        ->assertJsonPath('keys.1.key', 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=');
});

it('serves an empty key list when no seed is configured', function () {
    config(['schematio.attestation.seed' => null]);

    $this->getJson('/.well-known/schematio-keys.json')
        ->assertOk()
        ->assertExactJson(['keys' => []]);
});

it('rejects a malformed seed (wrong length) as unconfigured', function () {
    config(['schematio.attestation.seed' => base64_encode('too-short')]);

    $this->getJson('/.well-known/schematio-keys.json')
        ->assertOk()
        ->assertExactJson(['keys' => []]);
});
```

Run: `php artisan test --filter=AttestationKeysEndpoint` → **fails** (404, no route).

Note on the Cache-Control assertion: Laravel's `->header('Cache-Control', 'public, max-age=300')`
is normalized alphabetically by Symfony to `max-age=300, public`; the assertion above matches the
normalized form. If the assertion still fails on this header only, replace it with
`expect($response->headers->get('Cache-Control'))->toContain('max-age=300');` — the cache header
is advisory, not contractual.

**Step 1.2 — implement.**

`config/schematio.php`:

```php
<?php

return [

    /*
    |--------------------------------------------------------------------------
    | Plugin attestation (verified handshake v2)
    |--------------------------------------------------------------------------
    |
    | Ed25519 keypair used to sign plugin attestations (contract: connector
    | docs/superpowers/plans/2026-07-17-ipc-a-handshake-v2.md). The private
    | key is a 32-byte seed, base64-encoded; the public key is derived.
    | Generate a seed:  php -r "echo base64_encode(random_bytes(32)).PHP_EOL;"
    |
    | retired_public_keys keeps previously-active PUBLIC keys in the
    | well-known document during rotation. Format: "kid1:base64pub,kid2:base64pub".
    |
    */

    'attestation' => [
        'key_id' => env('SCHEMATIO_ATTEST_KEY_ID', 'k1'),
        'seed' => env('SCHEMATIO_ATTEST_SEED'),
        'retired_public_keys' => env('SCHEMATIO_ATTEST_RETIRED_KEYS', ''),
    ],

];
```

`app/Services/Attestation/AttestationKeyService.php`:

```php
<?php

namespace App\Services\Attestation;

/**
 * Ed25519 attestation signing for the plugin handshake (protocol v2).
 *
 * Canonical payload rules (must match the JVM verifier exactly): keys sorted
 * ascending (ksort), no whitespace (json_encode default), UTF-8 bytes signed.
 */
class AttestationKeyService
{
    public function isConfigured(): bool
    {
        return $this->seed() !== null;
    }

    public function keyId(): string
    {
        return (string) config('schematio.attestation.key_id');
    }

    /**
     * Keys served by /.well-known/schematio-keys.json: active key first,
     * then retired public keys (rotation).
     *
     * @return list<array{kid: string, alg: string, key: string}>
     */
    public function publicKeys(): array
    {
        $keys = [];

        $seed = $this->seed();
        if ($seed !== null) {
            $keypair = sodium_crypto_sign_seed_keypair($seed);
            $keys[] = [
                'kid' => $this->keyId(),
                'alg' => 'Ed25519',
                'key' => base64_encode(sodium_crypto_sign_publickey($keypair)),
            ];
        }

        foreach (explode(',', (string) config('schematio.attestation.retired_public_keys')) as $entry) {
            $entry = trim($entry);
            if ($entry === '' || ! str_contains($entry, ':')) {
                continue;
            }
            [$kid, $key] = explode(':', $entry, 2);
            $keys[] = ['kid' => $kid, 'alg' => 'Ed25519', 'key' => $key];
        }

        return $keys;
    }

    /**
     * Canonical-JSON encode + Ed25519-sign the claims.
     *
     * @param  array<string, mixed>  $claims
     * @return array{payload: string, signature: string} signature is base64 of 64 raw bytes
     */
    public function signAttestation(array $claims): array
    {
        ksort($claims);
        $payload = json_encode($claims, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);

        $keypair = sodium_crypto_sign_seed_keypair($this->seed());
        $signature = sodium_crypto_sign_detached($payload, sodium_crypto_sign_secretkey($keypair));

        return ['payload' => $payload, 'signature' => base64_encode($signature)];
    }

    private function seed(): ?string
    {
        $b64 = config('schematio.attestation.seed');
        if (! is_string($b64) || $b64 === '') {
            return null;
        }
        $raw = base64_decode($b64, true);

        return ($raw !== false && strlen($raw) === SODIUM_CRYPTO_SIGN_SEEDBYTES) ? $raw : null;
    }
}
```

`app/Http/Controllers/Api/PluginAttestController.php` (Task 1 version — `attest()` added in Task 2):

```php
<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Services\Attestation\AttestationKeyService;
use Illuminate\Http\JsonResponse;

/**
 * Plugin handshake attestation (protocol v2).
 *
 * Serves the Ed25519 public-key document and signs per-connection
 * attestations for community-token plugins. Contract:
 * SchematioConnector docs/superpowers/plans/2026-07-17-ipc-a-handshake-v2.md.
 */
class PluginAttestController extends Controller
{
    public function __construct(private AttestationKeyService $keys)
    {
    }

    /**
     * GET /.well-known/schematio-keys.json (public, cached).
     */
    public function keys(): JsonResponse
    {
        return response()
            ->json(['keys' => $this->keys->publicKeys()])
            ->header('Cache-Control', 'public, max-age=300');
    }
}
```

`routes/web.php` — directly below the existing `/up` health check route, add:

```php
// Ed25519 public keys for plugin handshake attestation (protocol v2).
// Public + cacheable; list form enables key rotation.
Route::get('/.well-known/schematio-keys.json', [App\Http\Controllers\Api\PluginAttestController::class, 'keys'])
    ->name('well-known.schematio-keys');
```

**Step 1.3 — run.** `php artisan test --filter=AttestationKeysEndpoint` → all 4 tests pass.

---

## Task 2 — schemati: POST /api/v1/plugin/attest (+ rate limit + audit)

**Files**
- Modify: `/Users/harrison/Documents/code/schemati/app/Http/Controllers/Api/PluginAttestController.php`
- Modify: `/Users/harrison/Documents/code/schemati/routes/api.php` (one route inside the existing `Route::prefix('plugin')` community-JWT group)
- Modify: `/Users/harrison/Documents/code/schemati/app/Providers/RouteServiceProvider.php` (register the `attest` limiter)
- Modify: `/Users/harrison/Documents/code/schemati/app/Models/CommunityTokenAudit.php` (add `ACTION_ATTEST_ISSUED` constant + display-name arm)
- Test: `/Users/harrison/Documents/code/schemati/tests/Feature/Api/PluginAttestTest.php`

**Interfaces**
- Consumes: contracts C1, C2, C5; `AttestationKeyService` (Task 1); request attributes merged by
  `EnsureValidJWT` (`is_community_token`, `community_id`, `token_payload['jti']`);
  `CommunityToken::findByJti(string $jti): ?CommunityToken`;
  `CommunityTokenAudit::log(string $action, ?string $tokenId, ?string $actorId, ?array $metadata): self`.
- Produces: route `POST /api/v1/plugin/attest` named `api.plugin.attest`, response per C5.

**Step 2.1 — failing test.** Write the test file (setup mirrors the proven
`tests/Feature/Api/PluginVersionApiTest.php` pattern; helper names are `attest…`-prefixed because
Pest helpers are global):

```php
<?php

use App\Helpers\JWT;
use App\Models\Community;
use App\Models\CommunityTokenAudit;
use App\Models\Player;
use App\Models\Tag;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\Http;
use Illuminate\Support\Str;

uses(RefreshDatabase::class);

const ATTEST_TEST_SEED_B64 = 'QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI=';

beforeEach(function () {
    // Stop the Player boot hook from hitting the Mojang API.
    Http::fake(['*' => Http::response(['name' => 'TestPlayer', 'id' => 'some-uuid'], 200)]);
    app(\Spatie\Permission\PermissionRegistrar::class)->forgetCachedPermissions();

    config([
        'schematio.attestation.key_id' => 'test-k1',
        'schematio.attestation.seed' => ATTEST_TEST_SEED_B64,
        'schematio.attestation.retired_public_keys' => '',
    ]);

    attestSetupTagHierarchy();

    $this->creator = Player::create([
        'id' => Str::uuid()->toString(),
        'last_seen_name' => 'AttestCreator',
    ]);

    $this->community = Community::create([
        'name' => 'Attest Community',
        'slug' => 'attest-community',
        'description' => 'Community for attest endpoint tests',
        'is_public' => true,
        'is_active' => true,
        'created_by' => $this->creator->id,
    ]);
    $this->community->addMember($this->creator, Community::ROLE_ADMIN);

    $this->token = JWT::generateCommunityToken($this->community, $this->creator, 'Attest Token')['token'];
});

/** Root + 'community' parent tags, required by Community::getOrCreateTag(). */
function attestSetupTagHierarchy(): void
{
    $rootTag = Tag::create([
        'id' => '00000000-0000-0000-0000-000000000000',
        'name' => 'root',
        'parent_id' => null,
        'description' => 'Root tag for testing',
        'scope' => 'private',
    ]);

    Tag::create([
        'id' => Str::uuid()->toString(),
        'name' => 'community',
        'parent_id' => $rootTag->id,
        'description' => 'Community-specific tags',
        'scope' => 'private',
    ]);
}

function attestPublicKey(): string
{
    return sodium_crypto_sign_publickey(
        sodium_crypto_sign_seed_keypair(base64_decode(ATTEST_TEST_SEED_B64, true)),
    );
}

it('signs a canonical attestation for a community token', function () {
    $response = $this->withToken($this->token)
        ->postJson('/api/v1/plugin/attest', [
            // Uppercase on purpose: the payload must carry it lowercased.
            'nonce_hex' => '000102030405060708090A0B0C0D0E0F',
            'platform' => 'PAPER_PLUGIN',
        ]);

    $response->assertOk()->assertJsonStructure(['payload', 'signature_base64', 'key_id']);

    $payload = $response->json('payload');
    $decoded = json_decode($payload, true);

    // Canonical form: exactly these keys, sorted, no whitespace (contract C1).
    expect(array_keys($decoded))->toBe(['communityId', 'issuedAt', 'nonce', 'platform', 'tokenId']);
    expect($payload)->not->toContain(' ');
    expect($decoded['communityId'])->toBe((string) $this->community->id);
    expect($decoded['nonce'])->toBe('000102030405060708090a0b0c0d0e0f');
    expect($decoded['platform'])->toBe('PAPER_PLUGIN');
    expect($decoded['issuedAt'])->toBeInt()
        ->toBeGreaterThan(time() - 10)
        ->toBeLessThanOrEqual(time());
    expect($decoded['tokenId'])->toBeString()->not->toBe('');

    // Signature verifies against the derived public key (contract C2).
    $signature = base64_decode($response->json('signature_base64'), true);
    expect(strlen($signature))->toBe(64);
    expect(sodium_crypto_sign_verify_detached($signature, $payload, attestPublicKey()))->toBeTrue();

    expect($response->json('key_id'))->toBe('test-k1');
});

it('writes an audit row for issued attestations', function () {
    $this->withToken($this->token)
        ->postJson('/api/v1/plugin/attest', [
            'nonce_hex' => str_repeat('ab', 16),
            'platform' => 'FABRIC_SERVER',
        ])
        ->assertOk();

    $audit = CommunityTokenAudit::where('action', CommunityTokenAudit::ACTION_ATTEST_ISSUED)->first();
    expect($audit)->not->toBeNull();
    expect($audit->metadata['community_id'])->toBe($this->community->id);
    expect($audit->metadata['platform'])->toBe('FABRIC_SERVER');
});

it('rejects a malformed nonce', function () {
    $this->withToken($this->token)
        ->postJson('/api/v1/plugin/attest', ['nonce_hex' => 'zz00', 'platform' => 'PAPER_PLUGIN'])
        ->assertStatus(422);

    $this->withToken($this->token)
        ->postJson('/api/v1/plugin/attest', ['nonce_hex' => 'abcd', 'platform' => 'PAPER_PLUGIN'])
        ->assertStatus(422);
});

it('rejects an unknown platform', function () {
    $this->withToken($this->token)
        ->postJson('/api/v1/plugin/attest', ['nonce_hex' => str_repeat('00', 16), 'platform' => 'VELOCITY'])
        ->assertStatus(422);
});

it('rejects non-community tokens with 403 community_token_required', function () {
    $this->withToken(JWT::getTestToken())
        ->postJson('/api/v1/plugin/attest', ['nonce_hex' => str_repeat('00', 16), 'platform' => 'PAPER_PLUGIN'])
        ->assertStatus(403)
        ->assertJson(['error' => 'community_token_required']);
});

it('returns 503 when signing is not configured', function () {
    config(['schematio.attestation.seed' => null]);

    $this->withToken($this->token)
        ->postJson('/api/v1/plugin/attest', ['nonce_hex' => str_repeat('00', 16), 'platform' => 'PAPER_PLUGIN'])
        ->assertStatus(503)
        ->assertJson(['error' => 'attestation_unavailable']);
});

it('rate limits attestations to 30 per minute per token', function () {
    for ($i = 0; $i < 30; $i++) {
        $this->withToken($this->token)
            ->postJson('/api/v1/plugin/attest', ['nonce_hex' => str_repeat('00', 16), 'platform' => 'PAPER_PLUGIN'])
            ->assertOk();
    }

    $this->withToken($this->token)
        ->postJson('/api/v1/plugin/attest', ['nonce_hex' => str_repeat('00', 16), 'platform' => 'PAPER_PLUGIN'])
        ->assertStatus(429);
});
```

Run: `php artisan test --filter=PluginAttest` → **fails** (404 / missing constant).

(Note: `EnsureValidJWT`'s own per-route-name limiter is a no-op here — the route name
`api.plugin.attest` has no `rate_limits` config entry and community tokens carry none by default,
so the only limiter in play is the `throttle:attest` middleware below. The 31-request loop is
therefore deterministic.)

**Step 2.2 — implement.**

`app/Models/CommunityTokenAudit.php` — add below the existing action constants:

```php
    const ACTION_ATTEST_ISSUED = 'attest_issued';
```

and add one arm to `getActionNameAttribute()`'s `match`, above the `default` arm:

```php
            self::ACTION_ATTEST_ISSUED => 'Attestation Issued',
```

`app/Providers/RouteServiceProvider.php` — in `boot()`, immediately after the existing
`RateLimiter::for('search-api', ...)` registration, add (the file already imports
`Illuminate\Cache\RateLimiting\Limit`, `Illuminate\Support\Facades\RateLimiter`, and
`Illuminate\Http\Request` for the `api` limiter; if any is missing, add the `use` line):

```php
        // Handshake attestation: 30/min per community token (spec: headroom ≫ join rates).
        RateLimiter::for('attest', function (Request $request) {
            return Limit::perMinute(30)->by('attest:'.sha1((string) ($request->bearerToken() ?? $request->ip())));
        });
```

`routes/api.php` — inside the existing `Route::prefix('plugin')->middleware('ensure_valid_jwt')->name('api.plugin.')`
group (next to the other plugin routes), add:

```php
            Route::post('attest', [App\Http\Controllers\Api\PluginAttestController::class, 'attest'])
                ->middleware('throttle:attest')
                ->name('attest');
```

`app/Http/Controllers/Api/PluginAttestController.php` — extend to the final version:

```php
<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Models\Community;
use App\Models\CommunityToken;
use App\Models\CommunityTokenAudit;
use App\Services\Attestation\AttestationKeyService;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;

/**
 * Plugin handshake attestation (protocol v2).
 *
 * Serves the Ed25519 public-key document and signs per-connection
 * attestations for community-token plugins. Contract:
 * SchematioConnector docs/superpowers/plans/2026-07-17-ipc-a-handshake-v2.md.
 */
class PluginAttestController extends Controller
{
    public function __construct(private AttestationKeyService $keys)
    {
    }

    /**
     * GET /.well-known/schematio-keys.json (public, cached).
     */
    public function keys(): JsonResponse
    {
        return response()
            ->json(['keys' => $this->keys->publicKeys()])
            ->header('Cache-Control', 'public, max-age=300');
    }

    /**
     * POST /api/v1/plugin/attest (community JWT, throttle:attest).
     *
     * Signs the canonical payload
     * {"communityId":…,"issuedAt":…,"nonce":…,"platform":…,"tokenId":…}
     * (sorted keys, no whitespace, UTF-8) and returns it verbatim with a
     * detached Ed25519 signature. The game server relays payload+signature
     * unmodified to the client (ATTEST opcode).
     */
    public function attest(Request $request): JsonResponse
    {
        $validated = $request->validate([
            'nonce_hex' => ['required', 'string', 'regex:/^[0-9a-fA-F]{32}$/'],
            'platform' => ['required', 'string', 'in:PAPER_PLUGIN,FABRIC_SERVER'],
        ]);

        $community = $this->getCommunityFromToken($request);
        if (! $community) {
            return response()->json([
                'error' => 'community_token_required',
                'message' => 'This endpoint requires a community JWT token. '
                    .'Please use a token generated for your community.',
            ], 403);
        }

        if (! $this->keys->isConfigured()) {
            return response()->json(['error' => 'attestation_unavailable'], 503);
        }

        $jti = $request->token_payload['jti'] ?? null;
        $nonce = strtolower($validated['nonce_hex']);

        $signed = $this->keys->signAttestation([
            'communityId' => (string) $community->id,
            'issuedAt' => time(),
            'nonce' => $nonce,
            'platform' => $validated['platform'],
            'tokenId' => (string) $jti,
        ]);

        $token = $jti ? CommunityToken::findByJti($jti) : null;
        CommunityTokenAudit::log(
            CommunityTokenAudit::ACTION_ATTEST_ISSUED,
            $token?->id,
            null,
            [
                'community_id' => $community->id,
                'platform' => $validated['platform'],
                'nonce' => $nonce,
            ],
        );

        return response()->json([
            'payload' => $signed['payload'],
            'signature_base64' => $signed['signature'],
            'key_id' => $this->keys->keyId(),
        ]);
    }

    private function getCommunityFromToken(Request $request): ?Community
    {
        if (! ($request->is_community_token ?? false)) {
            return null;
        }

        $communityId = $request->community_id;
        if (! $communityId) {
            return null;
        }

        return Community::find($communityId);
    }
}
```

**Step 2.3 — run.** `php artisan test --filter=PluginAttest` → all 7 tests pass. Then re-run
`php artisan test --filter=AttestationKeysEndpoint` (still green) and
`php artisan test --filter=PluginVersionApi` (proves the plugin group is untouched).

---

## Task 3 — :core — protocol v2 messages + codec (v1↔v2 matrix)

**Files**
- Modify: `/Users/harrison/IdeaProjects/SchematioConnector/core/src/main/kotlin/io/schemat/connector/core/ipc/IpcProtocol.kt`
- Modify: `/Users/harrison/IdeaProjects/SchematioConnector/core/src/main/kotlin/io/schemat/connector/core/ipc/IpcMessages.kt`
- Modify (existing tests updated + new cases): `/Users/harrison/IdeaProjects/SchematioConnector/core/src/test/kotlin/io/schemat/connector/core/ipc/IpcCodecTest.kt`

**Interfaces**
- Consumes: contract C6; existing `IpcWriter`/`IpcReader` primitives (`writeByte/writeVarInt/writeString/writeBytes`, mirrored reads).
- Produces (exact signatures):

```kotlin
// IpcProtocol.kt
object IpcProtocol { const val CHANNEL: String = "schematio:c"; const val VERSION: Int = 2 }
object IpcOpcode { /* existing 1..3 */ const val ATTEST: Int = 4 }
enum class IpcPlatform(val wire: Int) { PAPER_PLUGIN(0), FABRIC_SERVER(1);
    companion object { fun fromWire(wire: Int): IpcPlatform? } }

// IpcMessages.kt
data class HelloServer(
    val protocolVersion: Int, val pluginVersion: String, val capabilities: Int,
    val platform: IpcPlatform? = null, val serverSoftware: String = "", val mcVersion: String = "",
    val backendHost: String = "", val communityId: String = "", val communitySlug: String = "")
class HelloClient(val protocolVersion: Int, val modVersion: String, val clientFlags: Int,
    val nonce: ByteArray = ByteArray(0))            // equals/hashCode by contentEquals
class Attest(val protocolVersion: Int, val payloadJson: String, val signature: ByteArray,
    val keyId: String)                              // equals/hashCode by contentEquals
// IpcCodec gains: encodeAttest(msg: Attest): ByteArray, decodeAttest(bytes: ByteArray): Attest
```

**Step 3.1 — failing tests.** In `IpcCodecTest.kt`, replace the two hello round-trip tests and add
new ones (keep the LoadClipboard and error tests untouched):

```kotlin
    @Test
    fun `hello server v2 round-trips with identity fields`() {
        val msg = HelloServer(
            protocolVersion = IpcProtocol.VERSION,
            pluginVersion = "1.3.0",
            capabilities = Capabilities.DOWNLOAD_CMD or Capabilities.LOAD_CLIPBOARD,
            platform = IpcPlatform.PAPER_PLUGIN,
            serverSoftware = "Paper 1.21.8",
            mcVersion = "1.21.8",
            backendHost = "https://schemat.io",
            communityId = "11111111-2222-3333-4444-555555555555",
            communitySlug = "build-team",
        )
        val bytes = IpcCodec.encodeHelloServer(msg)
        assertEquals(IpcOpcode.HELLO_SERVER, IpcCodec.peekOpcode(bytes))
        assertEquals(msg, IpcCodec.decodeHelloServer(bytes))
    }

    @Test
    fun `hello server v1 decodes with v2 defaults (legacy peer)`() {
        // Hand-built v1 frame: exactly what a 1.3.0 peer puts on the wire.
        val bytes = IpcWriter().apply {
            writeByte(IpcOpcode.HELLO_SERVER)
            writeVarInt(1)
            writeString("1.2.4")
            writeVarInt(Capabilities.DOWNLOAD_CMD)
        }.toByteArray()
        val decoded = IpcCodec.decodeHelloServer(bytes)
        assertEquals(1, decoded.protocolVersion)
        assertEquals("1.2.4", decoded.pluginVersion)
        assertEquals(null, decoded.platform)
        assertEquals("", decoded.communityId)
    }

    @Test
    fun `hello server v2 frame is v1-readable (trailing bytes ignored)`() {
        val bytes = IpcCodec.encodeHelloServer(
            HelloServer(2, "1.4.0", 1, IpcPlatform.FABRIC_SERVER, "Fabric", "1.21.11", "https://x", "c", "s"),
        )
        // Simulate a v1 decoder: read only the v1 fields and stop.
        val r = IpcReader(bytes)
        assertEquals(IpcOpcode.HELLO_SERVER, r.readByte())
        assertEquals(2, r.readVarInt())
        assertEquals("1.4.0", r.readString())
        assertEquals(1, r.readVarInt())
        // v1 peers simply never read the remainder — must not have thrown by here.
    }

    @Test
    fun `unknown platform wire value decodes as null (forward compat)`() {
        val bytes = IpcWriter().apply {
            writeByte(IpcOpcode.HELLO_SERVER)
            writeVarInt(2)
            writeString("9.9.9")
            writeVarInt(0)
            writeVarInt(9) // platform from the future
            writeString(""); writeString(""); writeString(""); writeString(""); writeString("")
        }.toByteArray()
        assertEquals(null, IpcCodec.decodeHelloServer(bytes).platform)
    }

    @Test
    fun `hello client v2 round-trips with nonce`() {
        val nonce = ByteArray(16) { it.toByte() }
        val msg = HelloClient(IpcProtocol.VERSION, "1.4.0", 0, nonce)
        val bytes = IpcCodec.encodeHelloClient(msg)
        assertEquals(IpcOpcode.HELLO_CLIENT, IpcCodec.peekOpcode(bytes))
        assertEquals(msg, IpcCodec.decodeHelloClient(bytes))
    }

    @Test
    fun `hello client v1 decodes with empty nonce (server skips attestation)`() {
        val bytes = IpcWriter().apply {
            writeByte(IpcOpcode.HELLO_CLIENT)
            writeVarInt(1)
            writeString("1.2.4")
            writeVarInt(0)
        }.toByteArray()
        val decoded = IpcCodec.decodeHelloClient(bytes)
        assertEquals(1, decoded.protocolVersion)
        assertEquals(0, decoded.nonce.size)
    }

    @Test
    fun `attest round-trips`() {
        val msg = Attest(
            protocolVersion = IpcProtocol.VERSION,
            payloadJson = """{"communityId":"c","issuedAt":1,"nonce":"00","platform":"PAPER_PLUGIN","tokenId":"t"}""",
            signature = ByteArray(64) { (it + 1).toByte() },
            keyId = "k1",
        )
        val bytes = IpcCodec.encodeAttest(msg)
        assertEquals(IpcOpcode.ATTEST, IpcCodec.peekOpcode(bytes))
        assertEquals(msg, IpcCodec.decodeAttest(bytes))
    }

    @Test
    fun `decoding attest with wrong opcode throws`() {
        val bytes = IpcCodec.encodeHelloClient(HelloClient(1, "x", 0))
        assertThrows(IpcFormatException::class.java) { IpcCodec.decodeAttest(bytes) }
    }
```

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:test` → **fails to compile**
(missing `IpcPlatform`, `Attest`, nonce param).

**Step 3.2 — implement.**

`IpcProtocol.kt` — change `VERSION` and extend `IpcOpcode`; append `IpcPlatform` after
`Capabilities`:

```kotlin
    /** Current protocol version. Sent as a varint in every message; bump on breaking changes. */
    const val VERSION: Int = 2
```

```kotlin
    /** S2C: backend-signed attestation of the server's community binding (protocol v2). */
    const val ATTEST: Int = 4
```

```kotlin
/** Server platform advertised in a v2 HELLO_SERVER. */
enum class IpcPlatform(val wire: Int) {
    PAPER_PLUGIN(0),
    FABRIC_SERVER(1),
    ;

    companion object {
        /** Null for wire values from newer protocol revisions (forward compat). */
        fun fromWire(wire: Int): IpcPlatform? = entries.firstOrNull { it.wire == wire }
    }
}
```

`IpcMessages.kt` — replace `HelloServer` and `HelloClient`, append `Attest`, and extend
`IpcCodec` (full new content of the message classes + codec functions):

```kotlin
data class HelloServer(
    val protocolVersion: Int,
    val pluginVersion: String,
    val capabilities: Int,
    // --- v2 identity fields; defaults represent "not sent" (v1 peer) ---
    val platform: IpcPlatform? = null,
    val serverSoftware: String = "",
    val mcVersion: String = "",
    val backendHost: String = "",
    val communityId: String = "",
    val communitySlug: String = "",
)

/**
 * v2 adds [nonce]: 16 SecureRandom bytes the client expects back inside the signed
 * attestation payload (hex-lowercase). Empty nonce = "don't attest me" (v1, or opt-out).
 * Not a data class: ByteArray needs content equality for round-trip tests.
 */
class HelloClient(
    val protocolVersion: Int,
    val modVersion: String,
    val clientFlags: Int,
    val nonce: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HelloClient) return false
        return protocolVersion == other.protocolVersion &&
            modVersion == other.modVersion &&
            clientFlags == other.clientFlags &&
            nonce.contentEquals(other.nonce)
    }

    override fun hashCode(): Int {
        var result = protocolVersion
        result = 31 * result + modVersion.hashCode()
        result = 31 * result + clientFlags
        result = 31 * result + nonce.contentHashCode()
        return result
    }

    override fun toString(): String =
        "HelloClient(protocolVersion=$protocolVersion, modVersion=$modVersion, clientFlags=$clientFlags, nonce=${nonce.size}B)"
}

/**
 * S2C: the backend's signed attestation, relayed VERBATIM by the server. [payloadJson] is the
 * exact canonical string the backend signed (the client must verify the received bytes, never
 * re-serialize); [signature] is a 64-byte detached Ed25519 signature; [keyId] selects the
 * public key in the backend's /.well-known/schematio-keys.json document.
 */
class Attest(
    val protocolVersion: Int,
    val payloadJson: String,
    val signature: ByteArray,
    val keyId: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Attest) return false
        return protocolVersion == other.protocolVersion &&
            payloadJson == other.payloadJson &&
            signature.contentEquals(other.signature) &&
            keyId == other.keyId
    }

    override fun hashCode(): Int {
        var result = protocolVersion
        result = 31 * result + payloadJson.hashCode()
        result = 31 * result + signature.contentHashCode()
        result = 31 * result + keyId.hashCode()
        return result
    }

    override fun toString(): String =
        "Attest(protocolVersion=$protocolVersion, payload=${payloadJson.length}ch, signature=${signature.size}B, keyId=$keyId)"
}
```

`IpcCodec` changes (encode/decode pairs; the LoadClipboard pair is untouched):

```kotlin
    fun encodeHelloServer(msg: HelloServer): ByteArray = IpcWriter().apply {
        writeByte(IpcOpcode.HELLO_SERVER)
        writeVarInt(msg.protocolVersion)
        writeString(msg.pluginVersion)
        writeVarInt(msg.capabilities)
        if (msg.protocolVersion >= 2) {
            writeVarInt(requireNotNull(msg.platform) { "platform is required for v2 HELLO_SERVER" }.wire)
            writeString(msg.serverSoftware)
            writeString(msg.mcVersion)
            writeString(msg.backendHost)
            writeString(msg.communityId)
            writeString(msg.communitySlug)
        }
    }.toByteArray()

    fun encodeHelloClient(msg: HelloClient): ByteArray = IpcWriter().apply {
        writeByte(IpcOpcode.HELLO_CLIENT)
        writeVarInt(msg.protocolVersion)
        writeString(msg.modVersion)
        writeVarInt(msg.clientFlags)
        if (msg.protocolVersion >= 2) {
            writeBytes(msg.nonce)
        }
    }.toByteArray()

    fun encodeAttest(msg: Attest): ByteArray = IpcWriter().apply {
        writeByte(IpcOpcode.ATTEST)
        writeVarInt(msg.protocolVersion)
        writeString(msg.payloadJson)
        writeBytes(msg.signature)
        writeString(msg.keyId)
    }.toByteArray()
```

```kotlin
    fun decodeHelloServer(bytes: ByteArray): HelloServer {
        val r = IpcReader(bytes)
        val op = r.readByte()
        if (op != IpcOpcode.HELLO_SERVER) throw IpcFormatException("expected HELLO_SERVER, got $op")
        val protocolVersion = r.readVarInt()
        val pluginVersion = r.readString()
        val capabilities = r.readVarInt()
        if (protocolVersion < 2) {
            return HelloServer(protocolVersion, pluginVersion, capabilities)
        }
        return HelloServer(
            protocolVersion = protocolVersion,
            pluginVersion = pluginVersion,
            capabilities = capabilities,
            platform = IpcPlatform.fromWire(r.readVarInt()),
            serverSoftware = r.readString(),
            mcVersion = r.readString(),
            backendHost = r.readString(),
            communityId = r.readString(),
            communitySlug = r.readString(),
        )
    }

    fun decodeHelloClient(bytes: ByteArray): HelloClient {
        val r = IpcReader(bytes)
        val op = r.readByte()
        if (op != IpcOpcode.HELLO_CLIENT) throw IpcFormatException("expected HELLO_CLIENT, got $op")
        val protocolVersion = r.readVarInt()
        val modVersion = r.readString()
        val clientFlags = r.readVarInt()
        val nonce = if (protocolVersion >= 2 && r.remaining() > 0) r.readBytes() else ByteArray(0)
        return HelloClient(protocolVersion, modVersion, clientFlags, nonce)
    }

    fun decodeAttest(bytes: ByteArray): Attest {
        val r = IpcReader(bytes)
        val op = r.readByte()
        if (op != IpcOpcode.ATTEST) throw IpcFormatException("expected ATTEST, got $op")
        return Attest(
            protocolVersion = r.readVarInt(),
            payloadJson = r.readString(),
            signature = r.readBytes(),
            keyId = r.readString(),
        )
    }
```

**Step 3.3 — run.** `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:test` → BUILD
SUCCESSFUL. Also run `:bukkit:test` and `:fabric:1.21.11:test` now — both must still compile and
pass (`HelloClient` construction sites use positional args that are unchanged; `HelloServer`
gains only defaulted params).

---

## Task 4 — :core — Ed25519 verification (golden vector)

**Files**
- Create: `/Users/harrison/IdeaProjects/SchematioConnector/core/src/main/kotlin/io/schemat/connector/core/crypto/Ed25519.kt`
- Test: `/Users/harrison/IdeaProjects/SchematioConnector/core/src/test/kotlin/io/schemat/connector/core/crypto/Ed25519Test.kt`

**Interfaces**
- Consumes: contracts C2, C3; JDK `java.security` only.
- Produces: `object Ed25519 { fun verify(publicKeyRaw: ByteArray, message: ByteArray, signature: ByteArray): Boolean }`.

**Step 4.1 — failing test.**

```kotlin
package io.schemat.connector.core.crypto

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class Ed25519Test {

    // Golden vector generated by PHP libsodium (sodium_crypto_sign_detached) with the
    // fixed 32x0x42 seed — the exact cross-repo contract (plan §C3). If this test fails,
    // schemati and the connector have diverged on the signing contract.
    private val publicKey = Base64.getDecoder().decode("IVL40Zt5HSRFMkLhXy6rbLfP+ntqXtMAl5YOBpiB2xI=")
    private val payload =
        """{"communityId":"11111111-2222-3333-4444-555555555555","issuedAt":1760000000,"nonce":"000102030405060708090a0b0c0d0e0f","platform":"PAPER_PLUGIN","tokenId":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"}"""
    private val signature = Base64.getDecoder().decode(
        "WgS1p0Ug618bFMzOPVGYanT9ZwKUe8eRPStRN9KxTRSWqbCJc0vLs3SJcws6qsR8V69P8v/s+p6Xe1rpXgaJCw==",
    )

    @Test
    fun `verifies the PHP-signed golden vector`() {
        assertTrue(Ed25519.verify(publicKey, payload.toByteArray(Charsets.UTF_8), signature))
    }

    @Test
    fun `rejects a tampered payload`() {
        val tampered = payload.replace("PAPER_PLUGIN", "FABRIC_SERVER")
        assertFalse(Ed25519.verify(publicKey, tampered.toByteArray(Charsets.UTF_8), signature))
    }

    @Test
    fun `rejects a tampered signature`() {
        val bad = signature.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFalse(Ed25519.verify(publicKey, payload.toByteArray(Charsets.UTF_8), bad))
    }

    @Test
    fun `rejects the wrong key and malformed inputs`() {
        assertFalse(Ed25519.verify(ByteArray(32), payload.toByteArray(Charsets.UTF_8), signature))
        assertFalse(Ed25519.verify(ByteArray(31), payload.toByteArray(Charsets.UTF_8), signature)) // wrong key length
        assertFalse(Ed25519.verify(publicKey, payload.toByteArray(Charsets.UTF_8), ByteArray(63))) // wrong sig length
    }

    @Test
    fun `round-trips against a JVM-generated keypair`() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val message = "handshake-v2".toByteArray(Charsets.UTF_8)
        val sig = Signature.getInstance("Ed25519").run {
            initSign(keyPair.private)
            update(message)
            sign()
        }
        // X.509 SubjectPublicKeyInfo for Ed25519 is a fixed 12-byte prefix + 32 raw bytes.
        val raw = keyPair.public.encoded.copyOfRange(keyPair.public.encoded.size - 32, keyPair.public.encoded.size)
        assertTrue(Ed25519.verify(raw, message, sig))
        assertFalse(Ed25519.verify(raw, "other".toByteArray(Charsets.UTF_8), sig))
    }
}
```

Run `:core:test` → **fails to compile** (no `Ed25519`).

**Step 4.2 — implement** `Ed25519.kt`:

```kotlin
package io.schemat.connector.core.crypto

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Verify-only Ed25519 over JDK `java.security` (JDK >= 15; this project targets 21 — no
 * dependencies). The backend serves raw 32-byte public keys; the JDK wants X.509
 * SubjectPublicKeyInfo, which for Ed25519 is a constant 12-byte DER header + the raw key.
 */
object Ed25519 {

    /** DER: SEQUENCE(42) { SEQUENCE(5) { OID 1.3.101.112 } BIT STRING(33, 0 unused) }. */
    private val DER_PREFIX = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
    )

    /**
     * @param publicKeyRaw raw 32-byte Ed25519 public key (as served in schematio-keys.json)
     * @param message the exact signed bytes (canonical JSON payload as UTF-8)
     * @param signature 64-byte detached signature
     * @return true iff the signature verifies; false on any malformed input (never throws)
     */
    fun verify(publicKeyRaw: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKeyRaw.size != 32 || signature.size != 64) return false
        return try {
            val key = KeyFactory.getInstance("Ed25519")
                .generatePublic(X509EncodedKeySpec(DER_PREFIX + publicKeyRaw))
            Signature.getInstance("Ed25519").run {
                initVerify(key)
                update(message)
                verify(signature)
            }
        } catch (_: Exception) {
            false
        }
    }
}
```

**Step 4.3 — run.** `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:test` → green.

---

## Task 5 — :core — hex helper + AttestationVerifier (negative paths, rotation)

**Files**
- Create: `/Users/harrison/IdeaProjects/SchematioConnector/core/src/main/kotlin/io/schemat/connector/core/attest/Hex.kt`
- Create: `/Users/harrison/IdeaProjects/SchematioConnector/core/src/main/kotlin/io/schemat/connector/core/attest/AttestationVerifier.kt`
- Test: `/Users/harrison/IdeaProjects/SchematioConnector/core/src/test/kotlin/io/schemat/connector/core/attest/AttestationVerifierTest.kt`

**Interfaces**
- Consumes: contract C1/C7; `Ed25519.verify` (Task 4); `io.schemat.connector.core.json.parseJsonSafe/safeGetString` (existing).
- Produces:

```kotlin
fun bytesToHexLower(bytes: ByteArray): String

sealed class AttestOutcome {
    data class Verified(val communityId: String, val tokenId: String, val platform: String) : AttestOutcome()
    data class Rejected(val reason: Reason) : AttestOutcome()
    enum class Reason { UNKNOWN_KEY, BAD_SIGNATURE, MALFORMED_PAYLOAD, NONCE_MISMATCH, STALE_ISSUED_AT, COMMUNITY_MISMATCH }
}

object AttestationVerifier {
    const val MAX_CLOCK_SKEW_SECONDS: Long = 600
    fun verify(payloadJson: String, signature: ByteArray, keyId: String,
        keysByKid: Map<String, ByteArray>, expectedNonceHex: String,
        expectedCommunityId: String?, nowEpochSeconds: Long = System.currentTimeMillis() / 1000): AttestOutcome
}
```

**Step 5.1 — failing test.** Tests self-sign with a JVM keypair (raw key = last 32 bytes of the
X.509 encoding) so every rule is exercised without fixtures; the PHP interop is already pinned by
Task 4's golden vector.

```kotlin
package io.schemat.connector.core.attest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature

class AttestationVerifierTest {

    private val keyPair: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val rawPublic: ByteArray =
        keyPair.public.encoded.copyOfRange(keyPair.public.encoded.size - 32, keyPair.public.encoded.size)
    private val nonceHex = "000102030405060708090a0b0c0d0e0f"
    private val now = 1_760_000_000L

    private fun payload(
        communityId: String = "community-1",
        issuedAt: Long = now,
        nonce: String = nonceHex,
        platform: String = "PAPER_PLUGIN",
        tokenId: String = "token-1",
    ): String =
        """{"communityId":"$communityId","issuedAt":$issuedAt,"nonce":"$nonce","platform":"$platform","tokenId":"$tokenId"}"""

    private fun sign(payload: String): ByteArray = Signature.getInstance("Ed25519").run {
        initSign(keyPair.private)
        update(payload.toByteArray(Charsets.UTF_8))
        sign()
    }

    private fun verify(
        payload: String,
        signature: ByteArray = sign(payload),
        keyId: String = "k1",
        keys: Map<String, ByteArray> = mapOf("k1" to rawPublic),
        expectedNonceHex: String = nonceHex,
        expectedCommunityId: String? = "community-1",
    ): AttestOutcome = AttestationVerifier.verify(
        payloadJson = payload,
        signature = signature,
        keyId = keyId,
        keysByKid = keys,
        expectedNonceHex = expectedNonceHex,
        expectedCommunityId = expectedCommunityId,
        nowEpochSeconds = now,
    )

    @Test
    fun `accepts a valid attestation`() {
        val outcome = verify(payload())
        assertTrue(outcome is AttestOutcome.Verified)
        assertEquals("community-1", (outcome as AttestOutcome.Verified).communityId)
        assertEquals("token-1", outcome.tokenId)
        assertEquals("PAPER_PLUGIN", outcome.platform)
    }

    @Test
    fun `rejects an unknown key id (rotation refetch trigger)`() {
        val outcome = verify(payload(), keyId = "k2")
        assertEquals(AttestOutcome.Rejected(AttestOutcome.Reason.UNKNOWN_KEY), outcome)
    }

    @Test
    fun `accepts a signature made by a rotated (second) key in the document`() {
        val outcome = verify(
            payload(),
            keyId = "k2",
            keys = mapOf("k1" to ByteArray(32), "k2" to rawPublic),
        )
        assertTrue(outcome is AttestOutcome.Verified)
    }

    @Test
    fun `rejects a bad signature`() {
        val bad = sign(payload()).also { it[3] = (it[3].toInt() xor 0x40).toByte() }
        assertEquals(AttestOutcome.Rejected(AttestOutcome.Reason.BAD_SIGNATURE), verify(payload(), signature = bad))
    }

    @Test
    fun `rejects a signed-but-wrong nonce (replay)`() {
        val outcome = verify(payload(nonce = "ffffffffffffffffffffffffffffffff"))
        assertEquals(AttestOutcome.Rejected(AttestOutcome.Reason.NONCE_MISMATCH), outcome)
    }

    @Test
    fun `rejects a stale issuedAt beyond plus-minus 10 minutes`() {
        assertEquals(
            AttestOutcome.Rejected(AttestOutcome.Reason.STALE_ISSUED_AT),
            verify(payload(issuedAt = now - 601)),
        )
        assertEquals(
            AttestOutcome.Rejected(AttestOutcome.Reason.STALE_ISSUED_AT),
            verify(payload(issuedAt = now + 601)),
        )
        assertTrue(verify(payload(issuedAt = now - 600)) is AttestOutcome.Verified)
    }

    @Test
    fun `rejects a community mismatch (server claimed a different community)`() {
        val outcome = verify(payload(communityId = "community-2"))
        assertEquals(AttestOutcome.Rejected(AttestOutcome.Reason.COMMUNITY_MISMATCH), outcome)
    }

    @Test
    fun `skips the community check when the server claimed none (empty or null)`() {
        assertTrue(verify(payload(), expectedCommunityId = null) is AttestOutcome.Verified)
        assertTrue(verify(payload(), expectedCommunityId = "") is AttestOutcome.Verified)
    }

    @Test
    fun `rejects malformed payloads that are validly signed`() {
        assertEquals(
            AttestOutcome.Rejected(AttestOutcome.Reason.MALFORMED_PAYLOAD),
            verify("""{"not":"an attestation"}"""),
        )
        assertEquals(
            AttestOutcome.Rejected(AttestOutcome.Reason.MALFORMED_PAYLOAD),
            verify("not json at all"),
        )
    }

    @Test
    fun `hex helper is lowercase and byte-exact`() {
        assertEquals("000102ff", bytesToHexLower(byteArrayOf(0, 1, 2, -1)))
        assertEquals("", bytesToHexLower(ByteArray(0)))
    }
}
```

Run `:core:test` → **fails to compile**.

**Step 5.2 — implement.**

`Hex.kt`:

```kotlin
package io.schemat.connector.core.attest

private const val HEX_DIGITS = "0123456789abcdef"

/** Lowercase hex, the encoding used for nonces in the attestation payload (contract C1). */
fun bytesToHexLower(bytes: ByteArray): String = buildString(bytes.size * 2) {
    for (b in bytes) {
        val v = b.toInt() and 0xFF
        append(HEX_DIGITS[v ushr 4])
        append(HEX_DIGITS[v and 0x0F])
    }
}
```

`AttestationVerifier.kt`:

```kotlin
package io.schemat.connector.core.attest

import io.schemat.connector.core.crypto.Ed25519
import io.schemat.connector.core.json.parseJsonSafe
import io.schemat.connector.core.json.safeGetString
import kotlin.math.abs

/** Outcome of verifying a relayed ATTEST message on the client. */
sealed class AttestOutcome {
    data class Verified(
        val communityId: String,
        val tokenId: String,
        val platform: String,
    ) : AttestOutcome()

    data class Rejected(val reason: Reason) : AttestOutcome()

    enum class Reason {
        /** keyId not in the key document — caller should refetch once (rotation), then give up. */
        UNKNOWN_KEY,
        BAD_SIGNATURE,
        MALFORMED_PAYLOAD,
        NONCE_MISMATCH,
        STALE_ISSUED_AT,
        COMMUNITY_MISMATCH,
    }
}

/**
 * Pure attestation verification (contract C7). Signature first (over the received payload
 * bytes VERBATIM — never re-serialized), then payload claims. Clock injectable for tests.
 */
object AttestationVerifier {

    /** ±10 min issuedAt window; the nonce carries the real freshness (spec §Risks). */
    const val MAX_CLOCK_SKEW_SECONDS: Long = 600

    fun verify(
        payloadJson: String,
        signature: ByteArray,
        keyId: String,
        keysByKid: Map<String, ByteArray>,
        expectedNonceHex: String,
        expectedCommunityId: String?,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
    ): AttestOutcome {
        val key = keysByKid[keyId]
            ?: return AttestOutcome.Rejected(AttestOutcome.Reason.UNKNOWN_KEY)

        if (!Ed25519.verify(key, payloadJson.toByteArray(Charsets.UTF_8), signature)) {
            return AttestOutcome.Rejected(AttestOutcome.Reason.BAD_SIGNATURE)
        }

        val obj = parseJsonSafe(payloadJson)
            ?: return AttestOutcome.Rejected(AttestOutcome.Reason.MALFORMED_PAYLOAD)
        val communityId = obj.safeGetString("communityId")
        val nonce = obj.safeGetString("nonce")
        val platform = obj.safeGetString("platform")
        val tokenId = obj.safeGetString("tokenId")
        val issuedAt = try {
            obj.get("issuedAt")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
        } catch (_: Exception) {
            null
        }
        if (communityId == null || nonce == null || platform == null || tokenId == null || issuedAt == null) {
            return AttestOutcome.Rejected(AttestOutcome.Reason.MALFORMED_PAYLOAD)
        }

        if (nonce != expectedNonceHex) {
            return AttestOutcome.Rejected(AttestOutcome.Reason.NONCE_MISMATCH)
        }
        if (abs(nowEpochSeconds - issuedAt) > MAX_CLOCK_SKEW_SECONDS) {
            return AttestOutcome.Rejected(AttestOutcome.Reason.STALE_ISSUED_AT)
        }
        if (!expectedCommunityId.isNullOrEmpty() && communityId != expectedCommunityId) {
            return AttestOutcome.Rejected(AttestOutcome.Reason.COMMUNITY_MISMATCH)
        }

        return AttestOutcome.Verified(communityId = communityId, tokenId = tokenId, platform = platform)
    }
}
```

Note: `parseJsonSafe` returns a `JsonObject?` — check its actual signature in
`core/src/main/kotlin/io/schemat/connector/core/json/JsonExtensions.kt` when wiring imports; it is
the same helper `VersionApi.kt` already imports.

**Step 5.3 — run.** `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:test` → green.

---

## Task 6 — :core — AttestationClient (server→backend call, cache, timeout)

**Files**
- Create: `/Users/harrison/IdeaProjects/SchematioConnector/core/src/main/kotlin/io/schemat/connector/core/attest/AttestationClient.kt`
- Test: `/Users/harrison/IdeaProjects/SchematioConnector/core/src/test/kotlin/io/schemat/connector/core/attest/AttestationClientTest.kt`

**Interfaces**
- Consumes: contract C5; `ApiTransport`/`ApiRequest`/`HttpMethod`/`TransportException` (existing
  `core/modapi/transport`); `IpcPlatform` (Task 3); `kotlinx.coroutines.withTimeoutOrNull`.
- Produces:

```kotlin
class Attestation(val payloadJson: String, val signature: ByteArray, val keyId: String)
class AttestationClient(
    private val transport: ApiTransport,
    private val tokenProvider: () -> String?,
    private val timeoutMs: Long = 5_000,
) {
    suspend fun requestAttestation(nonceHex: String, platform: IpcPlatform): Attestation?
}
```

**Step 6.1 — failing test.**

```kotlin
package io.schemat.connector.core.attest

import io.schemat.connector.core.ipc.IpcPlatform
import io.schemat.connector.core.modapi.transport.ApiRequest
import io.schemat.connector.core.modapi.transport.ApiResponse
import io.schemat.connector.core.modapi.transport.ApiTransport
import io.schemat.connector.core.modapi.transport.HttpMethod
import io.schemat.connector.core.modapi.transport.TransportException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class AttestationClientTest {

    private val sig64 = Base64.getEncoder().encodeToString(ByteArray(64) { 7 })
    private val okBody =
        """{"payload":"{\"communityId\":\"c\"}","signature_base64":"$sig64","key_id":"k1"}"""

    private class FakeTransport(private val respond: (ApiRequest) -> ApiResponse) : ApiTransport {
        val requests = mutableListOf<Pair<ApiRequest, String?>>()
        override suspend fun execute(request: ApiRequest, bearerToken: String?): ApiResponse {
            requests += request to bearerToken
            return respond(request)
        }
    }

    private fun ok(body: String) = ApiResponse(200, body.toByteArray(Charsets.UTF_8))

    @Test
    fun `posts nonce and platform with the community token and parses the response`() = runTest {
        val transport = FakeTransport { ok(okBody) }
        val client = AttestationClient(transport, { "jwt-token" })

        val attestation = client.requestAttestation("00ff", IpcPlatform.PAPER_PLUGIN)!!

        assertEquals("""{"communityId":"c"}""", attestation.payloadJson)
        assertEquals(64, attestation.signature.size)
        assertEquals("k1", attestation.keyId)

        val (request, token) = transport.requests.single()
        assertEquals(HttpMethod.POST, request.method)
        assertEquals("/plugin/attest", request.path)
        assertEquals("jwt-token", token)
        assertEquals("""{"nonce_hex":"00ff","platform":"PAPER_PLUGIN"}""", request.jsonBody)
    }

    @Test
    fun `caches by nonce (one backend call per connection)`() = runTest {
        val transport = FakeTransport { ok(okBody) }
        val client = AttestationClient(transport, { "jwt" })

        val first = client.requestAttestation("aa", IpcPlatform.PAPER_PLUGIN)
        val second = client.requestAttestation("aa", IpcPlatform.PAPER_PLUGIN)

        assertTrue(first === second)
        assertEquals(1, transport.requests.size)

        client.requestAttestation("bb", IpcPlatform.PAPER_PLUGIN)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `returns null without a token, on http error, transport failure, or bad body`() = runTest {
        assertNull(AttestationClient(FakeTransport { ok(okBody) }, { null })
            .requestAttestation("00", IpcPlatform.PAPER_PLUGIN))

        assertNull(AttestationClient(FakeTransport { ApiResponse(429, null) }, { "jwt" })
            .requestAttestation("00", IpcPlatform.PAPER_PLUGIN))

        assertNull(AttestationClient(FakeTransport { throw TransportException("boom") }, { "jwt" })
            .requestAttestation("00", IpcPlatform.PAPER_PLUGIN))

        assertNull(AttestationClient(FakeTransport { ok("""{"payload":"x"}""") }, { "jwt" })
            .requestAttestation("00", IpcPlatform.PAPER_PLUGIN))

        // Signature must be exactly 64 bytes.
        val shortSig = Base64.getEncoder().encodeToString(ByteArray(10))
        assertNull(
            AttestationClient(
                FakeTransport { ok("""{"payload":"x","signature_base64":"$shortSig","key_id":"k"}""") },
                { "jwt" },
            ).requestAttestation("00", IpcPlatform.PAPER_PLUGIN),
        )
    }
}
```

Run `:core:test` → **fails to compile**.

**Step 6.2 — implement** `AttestationClient.kt`:

```kotlin
package io.schemat.connector.core.attest

import com.google.gson.JsonObject
import io.schemat.connector.core.ipc.IpcPlatform
import io.schemat.connector.core.json.parseJsonSafe
import io.schemat.connector.core.json.safeGetString
import io.schemat.connector.core.modapi.transport.ApiRequest
import io.schemat.connector.core.modapi.transport.ApiTransport
import io.schemat.connector.core.modapi.transport.HttpMethod
import io.schemat.connector.core.modapi.transport.TransportException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** A backend-signed attestation, relayed verbatim to the client in an ATTEST message. */
class Attestation(
    val payloadJson: String,
    val signature: ByteArray,
    val keyId: String,
)

/**
 * Server-side attestation fetcher shared by the Bukkit plugin and (later) the Fabric server:
 * POST /plugin/attest with the community token; response cached by nonce so retried HELLOs
 * on the same connection never re-hit the backend. All failures (timeout, HTTP error, junk
 * body) return null — the server then simply sends no ATTEST and the client settles at
 * UNVERIFIED. Never blocks gameplay.
 */
class AttestationClient(
    private val transport: ApiTransport,
    private val tokenProvider: () -> String?,
    private val timeoutMs: Long = 5_000,
) {

    private val cache = ConcurrentHashMap<String, Attestation>()

    suspend fun requestAttestation(nonceHex: String, platform: IpcPlatform): Attestation? {
        cache[nonceHex]?.let { return it }
        val token = tokenProvider() ?: return null

        val body = JsonObject().apply {
            addProperty("nonce_hex", nonceHex)
            addProperty("platform", platform.name)
        }
        val response = try {
            withTimeoutOrNull(timeoutMs) {
                transport.execute(
                    ApiRequest(HttpMethod.POST, "/plugin/attest", jsonBody = body.toString()),
                    token,
                )
            }
        } catch (_: TransportException) {
            null
        } ?: return null

        if (!response.isSuccess) return null
        val json = parseJsonSafe(response.bodyAsString() ?: return null) ?: return null
        val payload = json.safeGetString("payload") ?: return null
        val signatureB64 = json.safeGetString("signature_base64") ?: return null
        val keyId = json.safeGetString("key_id") ?: return null
        val signature = try {
            Base64.getDecoder().decode(signatureB64)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (signature.size != 64) return null

        val attestation = Attestation(payload, signature, keyId)
        cache[nonceHex] = attestation
        return attestation
    }
}
```

**Step 6.3 — run.** `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:test` → green.

---

## Task 7 — bukkit — v2 HELLO_SERVER + community identity + ATTEST relay

**Files**
- Modify: `/Users/harrison/IdeaProjects/SchematioConnector/bukkit/src/main/kotlin/io/schemat/schematioConnector/SchematioConnector.kt`
- Modify: `/Users/harrison/IdeaProjects/SchematioConnector/bukkit/src/main/kotlin/io/schemat/schematioConnector/ipc/PluginIpcService.kt`
- Test: `/Users/harrison/IdeaProjects/SchematioConnector/bukkit/src/test/kotlin/io/schemat/schematioConnector/ipc/PluginIpcAttestGateTest.kt`

**Interfaces**
- Consumes: `AttestationClient` (Task 6), `Attest`/`IpcPlatform`/v2 `HelloServer`/`HelloClient.nonce`
  (Task 3), `bytesToHexLower` (Task 5); existing `versionTransport: HttpTransport?`,
  `communityToken`, `apiEndpoint` fields; Bukkit scheduler.
- Produces: `SchematioConnector.communityId/communitySlug: String` (empty until connect),
  `SchematioConnector.attestationClient: AttestationClient?`;
  `PluginIpcService.Companion.wantsAttestation(hello: HelloClient): Boolean`.

**Step 7.1 — failing test** (pure JVM — no Bukkit types):

```kotlin
package io.schemat.schematioConnector.ipc

import io.schemat.connector.core.ipc.HelloClient
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PluginIpcAttestGateTest {

    @Test
    fun `attests a v2 hello with a 16-byte nonce`() {
        assertTrue(PluginIpcService.wantsAttestation(HelloClient(2, "1.4.0", 0, ByteArray(16))))
    }

    @Test
    fun `skips v1 hellos (legacy client)`() {
        assertFalse(PluginIpcService.wantsAttestation(HelloClient(1, "1.2.4", 0)))
    }

    @Test
    fun `skips v2 hellos with a missing or malformed nonce`() {
        assertFalse(PluginIpcService.wantsAttestation(HelloClient(2, "1.4.0", 0, ByteArray(0))))
        assertFalse(PluginIpcService.wantsAttestation(HelloClient(2, "1.4.0", 0, ByteArray(8))))
        assertFalse(PluginIpcService.wantsAttestation(HelloClient(2, "1.4.0", 0, ByteArray(32))))
    }
}
```

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :bukkit:test` → **fails to compile**.

**Step 7.2 — implement.**

`SchematioConnector.kt` — three edits:

(a) Next to the existing `var communityToken: String = ""` / `var apiEndpoint` state fields, add:

```kotlin
    // Community identity fetched from /plugin/community after a successful connect;
    // advertised (best-effort — may still be empty if a player joins first) in v2 HELLO_SERVER.
    var communityId: String = ""
        private set
    var communitySlug: String = ""
        private set

    // Shared attestation fetcher for the verified handshake (protocol v2).
    var attestationClient: io.schemat.connector.core.attest.AttestationClient? = null
        private set
```

(b) In `loadConfiguration()`: the early-return teardown block that nulls `versionApi`/
`versionTransport` also gets `attestationClient = null`; and immediately after the line

```kotlin
        versionApi = io.schemat.connector.core.modapi.VersionApi(versionTransport!!) { communityToken.takeIf { it.isNotEmpty() } }
```

add:

```kotlin
        attestationClient = io.schemat.connector.core.attest.AttestationClient(versionTransport!!) {
            communityToken.takeIf { it.isNotEmpty() }
        }
```

(c) In the async connection check inside `loadConfiguration()`, capture the transport and fetch
the community identity when connected. Replace:

```kotlin
        val http = httpUtil!!  // safe: just assigned on line above
        server.scheduler.runTaskAsynchronously(this, Runnable {
            offlineMode.recordAttempt()
            val connected = runBlocking { http.checkConnection() }

            server.scheduler.runTask(this, Runnable {
                isApiConnected = connected
```

with:

```kotlin
        val http = httpUtil!!  // safe: just assigned on line above
        val identityTransport = versionTransport!!
        server.scheduler.runTaskAsynchronously(this, Runnable {
            offlineMode.recordAttempt()
            val connected = runBlocking { http.checkConnection() }
            val identity: Pair<String, String>? =
                if (connected) runBlocking { fetchCommunityIdentity(identityTransport) } else null

            server.scheduler.runTask(this, Runnable {
                isApiConnected = connected
                if (identity != null) {
                    communityId = identity.first
                    communitySlug = identity.second
                }
```

and add this private helper to the class (bottom, near the other private helpers):

```kotlin
    /**
     * GET /plugin/community → (id, slug), used by the v2 HELLO_SERVER identity fields.
     * Best-effort: null on any failure keeps the fields empty (client stays UNVERIFIED).
     */
    private suspend fun fetchCommunityIdentity(
        transport: io.schemat.connector.core.modapi.transport.HttpTransport,
    ): Pair<String, String>? {
        return try {
            val response = transport.execute(
                io.schemat.connector.core.modapi.transport.ApiRequest(
                    io.schemat.connector.core.modapi.transport.HttpMethod.GET,
                    "/plugin/community",
                ),
                communityToken.takeIf { it.isNotEmpty() },
            )
            if (!response.isSuccess) return null
            val json = io.schemat.connector.core.json.parseJsonSafe(response.bodyAsString() ?: return null)
            val community = io.schemat.connector.core.json.safeGetObject(json, "community")
                ?: return null
            val id = io.schemat.connector.core.json.safeGetString(community, "id") ?: return null
            id to (io.schemat.connector.core.json.safeGetString(community, "slug") ?: "")
        } catch (_: Exception) {
            null
        }
    }
```

Note: `safeGetObject`/`safeGetString` in `core/json/JsonExtensions.kt` are **extension functions**
(`JsonObject?.safeGetString(key)`), so the idiomatic form is
`json.safeGetObject("community")?.let { ... }` with imports
`io.schemat.connector.core.json.parseJsonSafe`, `io.schemat.connector.core.json.safeGetObject`,
`io.schemat.connector.core.json.safeGetString` at the top of the file — prefer imported extension
call syntax over the fully-qualified spelling above:

```kotlin
            val json = parseJsonSafe(response.bodyAsString() ?: return null)
            val community = json.safeGetObject("community") ?: return null
            val id = community.safeGetString("id") ?: return null
            id to (community.safeGetString("slug") ?: "")
```

`PluginIpcService.kt` — five edits:

(a) Imports: add `io.schemat.connector.core.attest.bytesToHexLower`,
`io.schemat.connector.core.ipc.Attest`, `io.schemat.connector.core.ipc.IpcPlatform`,
`io.schemat.schematioConnector.SchematioConnector`, `kotlinx.coroutines.runBlocking`; the
`org.bukkit.plugin.java.JavaPlugin` import can stay (unused imports warn, so remove it).

(b) Constructor: `class PluginIpcService(private val plugin: SchematioConnector) : PluginMessageListener, Listener {`
(the sole call site `PluginIpcService(this).register()` in `SchematioConnector.onEnable` already
passes a `SchematioConnector`).

(c) `greet(player)` — replace the `HelloServer` construction with the v2 form:

```kotlin
        val hello = HelloServer(
            protocolVersion = IpcProtocol.VERSION,
            pluginVersion = plugin.description.version,
            capabilities = capabilities,
            platform = IpcPlatform.PAPER_PLUGIN,
            serverSoftware = "${plugin.server.name} ${plugin.server.minecraftVersion}",
            mcVersion = plugin.server.minecraftVersion,
            backendHost = plugin.apiEndpoint.replace(Regex("/api/v\\d+$"), ""),
            communityId = plugin.communityId,
            communitySlug = plugin.communitySlug,
        )
```

(d) In `onPluginMessageReceived`, extend the `HELLO_CLIENT` branch:

```kotlin
                IpcOpcode.HELLO_CLIENT -> {
                    val hello: HelloClient = IpcCodec.decodeHelloClient(message)
                    plugin.logger.info("Schematio mod present for ${player.name}: v${hello.modVersion} (proto ${hello.protocolVersion})")
                    greet(player) // fallback path; deduped
                    if (wantsAttestation(hello)) {
                        requestAndSendAttest(player, hello.nonce)
                    }
                }
```

(e) Add the relay + the pure gate (companion) at the bottom of the class:

```kotlin
    /**
     * Fetches a backend attestation for [nonce] off-thread and relays it verbatim as an
     * ATTEST message on the main thread. Failure (no client configured, backend down,
     * timeout, rate limit) sends nothing — the client settles at UNVERIFIED (spec §Flow).
     */
    private fun requestAndSendAttest(player: Player, nonce: ByteArray) {
        val client = plugin.attestationClient ?: run {
            plugin.logger.info("No attestation client (API unconfigured); ${player.name} will stay UNVERIFIED")
            return
        }
        val nonceHex = bytesToHexLower(nonce)
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val attestation = runBlocking { client.requestAttestation(nonceHex, IpcPlatform.PAPER_PLUGIN) }
            if (attestation == null) {
                plugin.logger.info("Attestation unavailable for ${player.name}; client will settle at UNVERIFIED")
                return@Runnable
            }
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (!player.isOnline) return@Runnable
                val msg = Attest(IpcProtocol.VERSION, attestation.payloadJson, attestation.signature, attestation.keyId)
                player.sendPluginMessage(plugin, IpcProtocol.CHANNEL, IpcCodec.encodeAttest(msg))
            })
        })
    }

    companion object {
        /** Pure gate: only v2 hellos carrying a well-formed 16-byte nonce get attested. */
        fun wantsAttestation(hello: HelloClient): Boolean =
            hello.protocolVersion >= 2 && hello.nonce.size == 16
    }
```

**Step 7.3 — run.**
`JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :bukkit:test` → green (3 new tests + the
existing vcs tests). Then `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :bukkit:build` →
BUILD SUCCESSFUL (shadowJar compiles against the new core APIs).

---

## Task 8 — fabric client — ServerSession v2: trust states + per-connection nonce

**Files**
- Modify: `/Users/harrison/IdeaProjects/SchematioConnector/fabric/src/client/kotlin/io/schemat/connector/fabric/client/ipc/ServerSession.kt` (full rewrite below)
- Modify: `/Users/harrison/IdeaProjects/SchematioConnector/fabric/src/client/kotlin/io/schemat/connector/fabric/client/ipc/ServerIpc.kt` (`sendClientHello` only in this task)
- Test: `/Users/harrison/IdeaProjects/SchematioConnector/fabric/src/test/kotlin/io/schemat/connector/fabric/client/ipc/ServerSessionTrustTest.kt`

**Interfaces**
- Consumes: v2 `HelloServer`/`IpcPlatform` (Task 3).
- Produces:

```kotlin
enum class TrustState { NONE, LEGACY_V1, UNVERIFIED, VERIFIED }
object ServerSession {
    val pluginPresent: Boolean; val pluginVersion: String?; val protocolVersion: Int
    val capabilities: Int; val platform: IpcPlatform?; val serverSoftware: String
    val mcVersion: String; val backendHost: String; val communityId: String
    val communitySlug: String; val trust: TrustState
    val nonce: ByteArray                       // 16 bytes, regenerated by reset()
    fun adopt(hello: HelloServer)              // trust := LEGACY_V1 (v1) | UNVERIFIED (v2)
    fun markVerified()                         // UNVERIFIED -> VERIFIED only
    fun markHelloSent(): Boolean               // unchanged semantics
    fun reset()                                // everything cleared, fresh nonce, trust = NONE
}
```

**Step 8.1 — failing test.** (`ServerSession` is a process-global singleton — every test starts
and ends with `reset()`.)

```kotlin
package io.schemat.connector.fabric.client.ipc

import io.schemat.connector.core.ipc.HelloServer
import io.schemat.connector.core.ipc.IpcPlatform
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ServerSessionTrustTest {

    @BeforeEach
    fun setUp() = ServerSession.reset()

    @AfterEach
    fun tearDown() = ServerSession.reset()

    private fun v2Hello() = HelloServer(
        protocolVersion = 2,
        pluginVersion = "1.4.0",
        capabilities = 1,
        platform = IpcPlatform.PAPER_PLUGIN,
        serverSoftware = "Paper 1.21.8",
        mcVersion = "1.21.8",
        backendHost = "https://schemat.io",
        communityId = "community-1",
        communitySlug = "build-team",
    )

    @Test
    fun `starts at NONE with a 16-byte nonce`() {
        assertEquals(TrustState.NONE, ServerSession.trust)
        assertEquals(16, ServerSession.nonce.size)
    }

    @Test
    fun `v1 hello lands at LEGACY_V1 with empty identity`() {
        ServerSession.adopt(HelloServer(1, "1.2.4", 1))
        assertEquals(TrustState.LEGACY_V1, ServerSession.trust)
        assertTrue(ServerSession.pluginPresent)
        assertEquals(null, ServerSession.platform)
        assertEquals("", ServerSession.communityId)
    }

    @Test
    fun `v2 hello lands at UNVERIFIED and carries identity`() {
        ServerSession.adopt(v2Hello())
        assertEquals(TrustState.UNVERIFIED, ServerSession.trust)
        assertEquals(IpcPlatform.PAPER_PLUGIN, ServerSession.platform)
        assertEquals("community-1", ServerSession.communityId)
        assertEquals("https://schemat.io", ServerSession.backendHost)
    }

    @Test
    fun `markVerified only upgrades UNVERIFIED`() {
        ServerSession.markVerified() // NONE — must not move
        assertEquals(TrustState.NONE, ServerSession.trust)

        ServerSession.adopt(HelloServer(1, "1.2.4", 0))
        ServerSession.markVerified() // LEGACY_V1 — must not move
        assertEquals(TrustState.LEGACY_V1, ServerSession.trust)

        ServerSession.reset()
        ServerSession.adopt(v2Hello())
        ServerSession.markVerified()
        assertEquals(TrustState.VERIFIED, ServerSession.trust)
    }

    @Test
    fun `reset clears trust and rotates the nonce`() {
        val before = ServerSession.nonce.copyOf()
        ServerSession.adopt(v2Hello())
        ServerSession.markVerified()

        ServerSession.reset()

        assertEquals(TrustState.NONE, ServerSession.trust)
        assertFalse(ServerSession.pluginPresent)
        assertEquals(16, ServerSession.nonce.size)
        assertFalse(before.contentEquals(ServerSession.nonce)) // 2^-128 flake risk: acceptable
    }

    @Test
    fun `markHelloSent stays single-shot per connection`() {
        assertTrue(ServerSession.markHelloSent())
        assertFalse(ServerSession.markHelloSent())
        ServerSession.reset()
        assertTrue(ServerSession.markHelloSent())
    }
}
```

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:1.21.11:test` → **fails to
compile** (no `TrustState`, etc.).

**Step 8.2 — implement.** Full new `ServerSession.kt`:

```kotlin
package io.schemat.connector.fabric.client.ipc

import io.schemat.connector.core.ipc.HelloServer
import io.schemat.connector.core.ipc.IpcPlatform
import java.security.SecureRandom

/**
 * How much we trust the connected Schematio server (spec: handshake v2).
 * NONE = no plugin; LEGACY_V1 = v1 plugin (works, unproven identity);
 * UNVERIFIED = v2 plugin, attestation absent/failed; VERIFIED = attested by our backend.
 */
enum class TrustState { NONE, LEGACY_V1, UNVERIFIED, VERIFIED }

/** Per-connection state about the server's Schematio plugin. Reset on join/disconnect. */
object ServerSession {

    private val random = SecureRandom()

    @Volatile var pluginPresent: Boolean = false
        private set
    @Volatile var pluginVersion: String? = null
        private set
    @Volatile var protocolVersion: Int = 0
        private set
    @Volatile var capabilities: Int = 0
        private set

    // --- v2 identity (empty/null until a v2 HELLO_SERVER arrives) ---
    @Volatile var platform: IpcPlatform? = null
        private set
    @Volatile var serverSoftware: String = ""
        private set
    @Volatile var mcVersion: String = ""
        private set
    @Volatile var backendHost: String = ""
        private set
    @Volatile var communityId: String = ""
        private set
    @Volatile var communitySlug: String = ""
        private set

    @Volatile var trust: TrustState = TrustState.NONE
        private set

    /** 16-byte nonce sent inside HELLO_CLIENT this connection; rotated by [reset]. */
    @Volatile var nonce: ByteArray = newNonce()
        private set

    /** Whether we've already announced ourselves to the server this connection. */
    @Volatile private var helloSent: Boolean = false

    private fun newNonce(): ByteArray = ByteArray(16).also { random.nextBytes(it) }

    fun adopt(hello: HelloServer) {
        pluginVersion = hello.pluginVersion
        protocolVersion = hello.protocolVersion
        capabilities = hello.capabilities
        platform = hello.platform
        serverSoftware = hello.serverSoftware
        mcVersion = hello.mcVersion
        backendHost = hello.backendHost
        communityId = hello.communityId
        communitySlug = hello.communitySlug
        pluginPresent = true
        trust = if (hello.protocolVersion >= 2) TrustState.UNVERIFIED else TrustState.LEGACY_V1
    }

    /** Called by the ATTEST flow after the verifier accepts the payload. UNVERIFIED-only. */
    fun markVerified() {
        if (trust == TrustState.UNVERIFIED) trust = TrustState.VERIFIED
    }

    /**
     * Marks the client HELLO as sent; returns true only the first time per connection.
     * Both the join-fallback and the reply-to-HELLO_SERVER paths call this, so it keeps
     * us to a single HELLO_CLIENT on the wire when both fire (the common case).
     */
    fun markHelloSent(): Boolean = if (helloSent) false else { helloSent = true; true }

    fun reset() {
        pluginPresent = false
        pluginVersion = null
        protocolVersion = 0
        capabilities = 0
        platform = null
        serverSoftware = ""
        mcVersion = ""
        backendHost = ""
        communityId = ""
        communitySlug = ""
        trust = TrustState.NONE
        nonce = newNonce()
        helloSent = false
    }
}
```

`ServerIpc.kt` — in `sendClientHello()`, replace the encode line:

```kotlin
        val bytes = IpcCodec.encodeHelloClient(HelloClient(IpcProtocol.VERSION, version, 0))
```

with:

```kotlin
        val bytes = IpcCodec.encodeHelloClient(
            HelloClient(IpcProtocol.VERSION, version, 0, ServerSession.nonce),
        )
```

**Step 8.3 — run.** `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:1.21.11:test` →
green (6 new tests + existing fabric tests, including the untouched UI/render suites).

---

## Task 9 — fabric client — BackendKeyCache + AttestFlow + ATTEST handling

**Files**
- Create: `/Users/harrison/IdeaProjects/SchematioConnector/fabric/src/client/kotlin/io/schemat/connector/fabric/client/ipc/BackendKeyCache.kt`
- Create: `/Users/harrison/IdeaProjects/SchematioConnector/fabric/src/client/kotlin/io/schemat/connector/fabric/client/ipc/AttestFlow.kt`
- Modify: `/Users/harrison/IdeaProjects/SchematioConnector/fabric/src/client/kotlin/io/schemat/connector/fabric/client/ipc/ServerIpc.kt` (add the ATTEST branch)
- Test: `/Users/harrison/IdeaProjects/SchematioConnector/fabric/src/test/kotlin/io/schemat/connector/fabric/client/ipc/BackendKeyCacheTest.kt`

**Interfaces**
- Consumes: contracts C4, C7; `AttestationVerifier`/`AttestOutcome`/`bytesToHexLower` (Task 5);
  `Attest` + `IpcCodec.decodeAttest` (Task 3); `HttpTransport`/`ApiRequest`/`HttpMethod`
  (existing core transport); `ClientAuthManager.apiEndpoint` + `.trustAllCertificates`
  (existing); `SchematioClientMod.instance.authManager` (existing singleton).
- Produces:

```kotlin
class BackendKeyCache(private val fetchDocument: () -> String?) {
    fun keysByKid(): Map<String, ByteArray>   // fetches once, then cached
    fun invalidate()                          // force refetch on next keysByKid (rotation)
    companion object { fun parse(json: String): Map<String, ByteArray> }
}
object AttestFlow {
    fun originOf(apiEndpoint: String): String  // strips trailing /api/v<N>
    fun onAttest(attest: Attest)               // async verify -> ServerSession.markVerified()
}
```

**Step 9.1 — failing test.**

```kotlin
package io.schemat.connector.fabric.client.ipc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class BackendKeyCacheTest {

    private val keyB64 = Base64.getEncoder().encodeToString(ByteArray(32) { 5 })

    @Test
    fun `parses the well-known document`() {
        val keys = BackendKeyCache.parse(
            """{"keys":[{"kid":"k1","alg":"Ed25519","key":"$keyB64"}]}""",
        )
        assertEquals(setOf("k1"), keys.keys)
        assertTrue(keys.getValue("k1").contentEquals(ByteArray(32) { 5 }))
    }

    @Test
    fun `parses multiple keys (rotation) and skips junk entries`() {
        val keys = BackendKeyCache.parse(
            """{"keys":[
                {"kid":"k1","alg":"Ed25519","key":"$keyB64"},
                {"kid":"k0","alg":"Ed25519","key":"$keyB64"},
                {"kid":"rsa","alg":"RS256","key":"$keyB64"},
                {"kid":"short","alg":"Ed25519","key":"AAAA"},
                {"kid":"bad-b64","alg":"Ed25519","key":"!!!"},
                {"alg":"Ed25519","key":"$keyB64"},
                "not-an-object"
            ]}""",
        )
        assertEquals(setOf("k1", "k0"), keys.keys)
    }

    @Test
    fun `malformed documents parse to empty`() {
        assertEquals(emptyMap<String, ByteArray>(), BackendKeyCache.parse("not json"))
        assertEquals(emptyMap<String, ByteArray>(), BackendKeyCache.parse("{}"))
        assertEquals(emptyMap<String, ByteArray>(), BackendKeyCache.parse("""{"keys":"nope"}"""))
    }

    @Test
    fun `fetches once, caches, and refetches after invalidate`() {
        var calls = 0
        val cache = BackendKeyCache {
            calls++
            """{"keys":[{"kid":"k$calls","alg":"Ed25519","key":"$keyB64"}]}"""
        }
        assertEquals(setOf("k1"), cache.keysByKid().keys)
        assertEquals(setOf("k1"), cache.keysByKid().keys)
        assertEquals(1, calls)

        cache.invalidate()
        assertEquals(setOf("k2"), cache.keysByKid().keys)
        assertEquals(2, calls)
    }

    @Test
    fun `a failed fetch keeps the previous keys and does not cache the failure forever`() {
        var fail = false
        var calls = 0
        val cache = BackendKeyCache {
            calls++
            if (fail) null else """{"keys":[{"kid":"k1","alg":"Ed25519","key":"$keyB64"}]}"""
        }
        assertEquals(setOf("k1"), cache.keysByKid().keys)
        fail = true
        cache.invalidate()
        assertEquals(setOf("k1"), cache.keysByKid().keys) // stale-but-valid beats empty
        assertEquals(2, calls)
    }

    @Test
    fun `origin strips a trailing api version segment`() {
        assertEquals("https://schemat.io", AttestFlow.originOf("https://schemat.io/api/v1"))
        assertEquals("https://schemati.test", AttestFlow.originOf("https://schemati.test/api/v1/"))
        assertEquals("http://localhost:8080", AttestFlow.originOf("http://localhost:8080/api/v2"))
        assertEquals("https://schemat.io", AttestFlow.originOf("https://schemat.io"))
    }
}
```

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:1.21.11:test` → **fails to
compile**.

**Step 9.2 — implement.**

`BackendKeyCache.kt`:

```kotlin
package io.schemat.connector.fabric.client.ipc

import io.schemat.connector.core.json.parseJsonSafe
import io.schemat.connector.core.json.safeGetArray
import io.schemat.connector.core.json.safeGetString
import java.util.Base64

/**
 * Ed25519 public keys from the CLIENT'S OWN configured backend's
 * /.well-known/schematio-keys.json — never from the server-claimed backendHost, which is
 * what makes spoofing collapse into "signature does not verify" (spec §Attestation payload).
 *
 * Fetches lazily once per session; [invalidate] forces one refetch (unknown kid = rotation).
 * A failed fetch keeps previously-known keys.
 */
class BackendKeyCache(private val fetchDocument: () -> String?) {

    @Volatile private var keys: Map<String, ByteArray> = emptyMap()
    @Volatile private var fetched = false

    fun keysByKid(): Map<String, ByteArray> {
        if (!fetched) {
            val doc = fetchDocument()
            fetched = true
            if (doc != null) keys = parse(doc)
        }
        return keys
    }

    fun invalidate() {
        fetched = false
    }

    companion object {
        /** kid -> raw 32-byte Ed25519 key; unknown algs / bad base64 / bad lengths skipped. */
        fun parse(json: String): Map<String, ByteArray> {
            val obj = parseJsonSafe(json) ?: return emptyMap()
            val arr = obj.safeGetArray("keys") ?: return emptyMap()
            val out = LinkedHashMap<String, ByteArray>()
            for (element in arr) {
                if (!element.isJsonObject) continue
                val entry = element.asJsonObject
                val kid = entry.safeGetString("kid") ?: continue
                if (entry.safeGetString("alg") != "Ed25519") continue
                val keyB64 = entry.safeGetString("key") ?: continue
                val raw = try {
                    Base64.getDecoder().decode(keyB64)
                } catch (_: IllegalArgumentException) {
                    continue
                }
                if (raw.size == 32) out[kid] = raw
            }
            return out
        }
    }
}
```

(Check `safeGetArray`'s exact signature in `JsonExtensions.kt` — it is already imported by
`core/modapi/VersionApi.kt`; mirror that usage. If it returns `JsonArray?`, the loop above is
correct as written.)

`AttestFlow.kt`:

```kotlin
package io.schemat.connector.fabric.client.ipc

import io.schemat.connector.core.attest.AttestOutcome
import io.schemat.connector.core.attest.AttestationVerifier
import io.schemat.connector.core.attest.bytesToHexLower
import io.schemat.connector.core.ipc.Attest
import io.schemat.connector.core.modapi.transport.ApiRequest
import io.schemat.connector.core.modapi.transport.HttpMethod
import io.schemat.connector.core.modapi.transport.HttpTransport
import io.schemat.connector.core.modapi.transport.TransportException
import io.schemat.connector.fabric.client.SchematioClientMod
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.logging.Logger

/**
 * Verifies relayed ATTEST messages off-thread against keys from OUR configured backend and
 * upgrades [ServerSession] to VERIFIED on success. Every failure path logs and leaves the
 * session at UNVERIFIED — never surfaces an error to the player (spec §Flow).
 */
object AttestFlow {

    private val LOGGER = LoggerFactory.getLogger("SchematioAttest")

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "schematio-attest").apply { isDaemon = true }
    }

    @Volatile private var keyCache: BackendKeyCache? = null

    /** Strips a trailing /api/v<N> from an API endpoint, yielding the backend origin. */
    fun originOf(apiEndpoint: String): String =
        apiEndpoint.trimEnd('/').replace(Regex("/api/v\\d+$"), "")

    private fun cache(): BackendKeyCache {
        keyCache?.let { return it }
        val auth = SchematioClientMod.instance.authManager
        val transport = HttpTransport(
            apiEndpoint = originOf(auth.apiEndpoint),
            logger = Logger.getLogger("schematio-attest"),
            trustAllCertificates = auth.trustAllCertificates,
        )
        val created = BackendKeyCache {
            try {
                runBlocking {
                    transport.execute(ApiRequest(HttpMethod.GET, "/.well-known/schematio-keys.json"), null)
                }.takeIf { it.isSuccess }?.bodyAsString()
            } catch (e: TransportException) {
                LOGGER.warn("Failed to fetch backend key document: ${e.message}")
                null
            }
        }
        keyCache = created
        return created
    }

    /**
     * Handles an ATTEST from the server. Captures the session's nonce/community expectations
     * on the caller's thread, verifies on the attest thread (network fetch for keys), and
     * flips trust on success. Late/duplicate ATTESTs after a reconnect fail NONCE_MISMATCH.
     */
    fun onAttest(attest: Attest) {
        val expectedNonceHex = bytesToHexLower(ServerSession.nonce)
        val expectedCommunityId = ServerSession.communityId
        executor.execute {
            val cache = cache()
            var keys = cache.keysByKid()
            if (!keys.containsKey(attest.keyId)) {
                cache.invalidate() // rotation: one forced refetch on unknown kid
                keys = cache.keysByKid()
            }
            when (val outcome = AttestationVerifier.verify(
                payloadJson = attest.payloadJson,
                signature = attest.signature,
                keyId = attest.keyId,
                keysByKid = keys,
                expectedNonceHex = expectedNonceHex,
                expectedCommunityId = expectedCommunityId,
            )) {
                is AttestOutcome.Verified -> {
                    ServerSession.markVerified()
                    LOGGER.info("Server attestation VERIFIED (community ${outcome.communityId})")
                }
                is AttestOutcome.Rejected -> {
                    LOGGER.warn("Server attestation rejected (${outcome.reason}); staying UNVERIFIED")
                }
            }
        }
    }
}
```

`ServerIpc.kt` — two edits: add `io.schemat.connector.core.ipc.Attest` to the imports is NOT
needed (decode returns it inferred), but add the branch in `handle()`'s `when`, between the
`HELLO_SERVER` branch and `else`:

```kotlin
                IpcOpcode.ATTEST -> {
                    val attest = IpcCodec.decodeAttest(data)
                    LOGGER.info("Received attestation (keyId=${attest.keyId}); verifying against our backend…")
                    AttestFlow.onAttest(attest)
                }
```

**Step 9.3 — run.** `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:1.21.11:test` →
green (6 new tests).

---

## Task 10 — Integration checkpoint: full suites + manual run-paper checklist

**Files:** none created/modified (verification only).

**Step 10.1 — full automated sweep.** All must pass:

```bash
cd /Users/harrison/IdeaProjects/SchematioConnector
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:test :bukkit:test :fabric:1.21.11:test
# Expected: BUILD SUCCESSFUL, zero failures.

cd /Users/harrison/Documents/code/schemati
php artisan test --filter=AttestationKeysEndpoint
php artisan test --filter=PluginAttest
php artisan test --filter=PluginVersionApi   # regression: plugin route group untouched
# Expected: all green.
```

Optionally, if time permits, the full schemati suite (`php artisan test`) — the unrelated
in-flight VCS work was green at 1486 tests; this plan must not change that count except for the
new attest tests.

**Step 10.2 — manual run-paper checklist** (record results in the task notes; do not commit):

1. Backend: in `/Users/harrison/Documents/code/schemati/.env` add
   `SCHEMATIO_ATTEST_KEY_ID=local-k1` and `SCHEMATIO_ATTEST_SEED=<php -r "echo base64_encode(random_bytes(32)).PHP_EOL;">`,
   then `php artisan config:clear`. Verify `https://schemati.test/.well-known/schematio-keys.json`
   shows one Ed25519 key in the browser.
2. `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :bukkit:runServer` — config bootstraps
   against the local backend; set a community token via `/schematio settoken <jwt>` (generate in
   Community Settings → Plugin Tokens), then `/schematio reload`. Expect log
   `Connected to schemat.io API at …`.
3. `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:1.21.11:runClient`, join
   `localhost`.
   - Server log: `Schematio mod present for … (proto 2)`.
   - Laravel log (or `storage/logs`): one `POST /api/v1/plugin/attest` → 200.
   - Client log: `Received attestation (keyId=local-k1)` then
     `Server attestation VERIFIED (community …)`.
4. Failure fallback: stop the Laravel backend (or blank `SCHEMATIO_ATTEST_SEED` +
   `config:clear` → attest returns 503), rejoin. Client log shows
   `Attestation unavailable…`/`rejected…` and NO error to the player; downloads/uploads keep
   working (UNVERIFIED never gates v1 features).
5. v1 compat (spot check): connect the current client to a server running the released v1.2.4
   plugin jar — client log shows proto 1 and no attestation; the session sits at LEGACY_V1
   (verify via debugger/log if convenient). Conversely the new plugin against an old client jar
   sends v2 HELLO_SERVER that the old client reads fine (trailing bytes ignored).
6. Repeat step 3 once more after a client reconnect: the plugin's nonce-keyed cache must NOT
   reuse the old attestation for the new nonce (a fresh POST appears in the Laravel log — the
   cache key is the nonce, which rotated).

**Done-when:** both suites green, checklist items 1–4 observed (5–6 as feasible), trees left
dirty (NO commits) for Harrison's review.

---

## Self-review notes (spec coverage)

- Spec §Flow: HELLO_SERVER→HELLO_CLIENT(nonce)→POST attest→ATTEST relay — Tasks 2, 3, 7, 8, 9.
  Client-first join reuses the existing join-fallback HELLO_CLIENT which now always carries the
  nonce (Task 8). Attestation failure/timeout sends nothing; client rests at UNVERIFIED (Tasks 6,
  7, 9). The spec's "settles at UNVERIFIED after 10 s" needs no timer: UNVERIFIED is the resting
  state from the moment a v2 HELLO_SERVER arrives; only a verified ATTEST upgrades it. (The 10 s
  figure matters to the connection-indicator UI, which is out of scope for Sub-project A.)
- Spec §Message changes: VERSION=2, v2 HELLO_SERVER/HELLO_CLIENT fields, ATTEST=4, v1 decoding
  kept — Task 3, with an explicit v1↔v2 cross-version matrix.
- Spec §Attestation payload: canonical JSON C1, Ed25519 C2, client checks C7 (nonce, ±10 min,
  community match, own-backend keys) — Tasks 2, 4, 5, 9; PHP↔JVM golden vector C3 verified
  during planning (PHP sodium signature verifies under JDK 21 `java.security`).
- Spec §Backend: config keypair, well-known list (rotation), attest endpoint under the community
  JWT plugin group, 30/min, `CommunityTokenAudit`, Pest tests — Tasks 1, 2.
- Spec §Server side: shared `:core` attestation client (nonce cache, 5 s timeout) — Task 6;
  Bukkit relay — Task 7. Fabric-server sender role: **deferred out of Sub-project A** (see header).
- Spec §Client: ServerSession v2 + trust — Task 8; BackendKeyCache (once per session, refetch on
  unknown kid) — Task 9. Toolbar indicator + ActionRouter — out of scope here.
- Negative paths covered: bad signature, tampered payload, wrong nonce, expired issuedAt (both
  directions), community mismatch, unknown kid + rotation (multi-key doc), malformed key docs,
  v1 peer fallbacks in both directions, backend 429/500/timeout, non-community JWT, malformed
  nonce/platform input, unconfigured backend (503 / empty key doc).
