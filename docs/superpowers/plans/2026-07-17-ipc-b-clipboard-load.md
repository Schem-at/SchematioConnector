# IPC Sub-project B — Reference-Pull Clipboard Load + Status Channel

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Spec:** `docs/superpowers/specs/2026-07-17-ipc-clipboard-load-design.md`
**Depends on:** Sub-project A (`docs/superpowers/plans/2026-07-17-ipc-a-handshake-v2.md`) — its classes (`TrustState`, `ServerSession` v2, `AttestationClient`, `requestAndSendAttest`, the `/api/v1/plugin` route group patterns) are treated as EXISTING. Where the B spec says "ATTESTED", this plan maps it to A's `TrustState.VERIFIED`.

**Goal:** Client sends a schematic *reference* over `schematio:c`; the Bukkit plugin pulls the bytes itself from schemati (community JWT + requesting player's UUID), loads them into that player's server-side WorldEdit clipboard, and drives real UI feedback through a generic `STATUS` opcode. The v1 raw-bytes `LOAD_CLIPBOARD` (opcode 3) is deleted.

**Architecture:** One new backend endpoint (`POST /api/v1/plugin/clipboard/resolve`) that reuses the exact web-download policy (`SchematicPolicy::view` via the Gate) and the QuickShare validity rules; two new opcodes in `core/ipc` with hard decode-size caps; a pure-testable guard/orchestration layer in the Bukkit plugin; a pending-request tracker with timeout on the Fabric client, wired into the schematic detail panel gated on `VERIFIED` + capability.

**Tech Stack:** schemati: Laravel 12 + Pest (SQLite in-memory). SchematioConnector: Kotlin 2.4, JDK 21, Gradle + Stonecutter, JUnit 5, gson, Apache HttpClient (existing `ApiTransport`/`HttpTransport`), kotlinx-coroutines-test.

## Global Constraints

- **Do NOT git commit in either repo (user preference — skip every commit step).** Leave both trees dirty for review. Stay on branch `feature/ingame-diff-viewer` in SchematioConnector.
- **Touch only the files each task names.** Both trees are dirty with unrelated in-flight work — never reformat, revert, or "fix" anything a task does not name.
- **Test commands:**
  - SchematioConnector (run from `/Users/harrison/IdeaProjects/SchematioConnector`, ALWAYS prefix with JAVA_HOME):
    - `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:test`
    - `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :bukkit:test`
    - `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:1.21.11:test`
  - schemati (run from `/Users/harrison/Documents/code/schemati`):
    - `php artisan test --filter=PluginClipboardResolve`
- schemati tests MUST live under `tests/Feature/Api/` (Unit tests have no app/DB) and each file MUST declare `uses(RefreshDatabase::class);` itself (not global). Pest helper function names are global — prefix them (`clip…`) to avoid collisions.
- **Every opcode payload has a max size enforced in decode** (`IpcCaps`, Task 3): over-cap payloads throw `IpcPayloadTooLargeException` BEFORE any parsing; handlers drop them quietly. Unknown opcodes are ignored with one rate-limited log line.
- **No schematic bytes ever travel on the MC channel in either direction** (spec invariant 1): no new opcode carries a byte-array field.
- Hard payload cap everywhere: **8 MiB = 8388608 bytes** (backend 413, plugin client-side re-check, byte-counted transport stream).
- TDD per task: write the failing test, run it and see it fail, implement, run it and see it pass.

---

## Resolved ambiguities (spec ↔ sub-project A)

1. **Opcode numbering.** The spec says `LOAD_REQUEST = 4` / `STATUS = 5`, but sub-project A (authoritative, already landing) assigned **4 to `ATTEST`**. B therefore uses **`LOAD_REQUEST = 5`, `STATUS = 6`**; opcode **3** stays dead-reserved as the spec requires.
2. **"ATTESTED" trust state.** A's client trust enum is `NONE | LEGACY_V1 | UNVERIFIED | VERIFIED`. Everywhere the spec says ATTESTED, read `VERIFIED` (client) or "the plugin successfully relayed an ATTEST for this connection" (server — the server cannot observe the client's verification result, so a per-player `attested` set marking a successful ATTEST relay is the server-side gate).
3. **Unlinked player uuid.** Spec says "404 → DENIED if unlinked", which conflates the HTTP code and the plugin status. The backend returns **403 `player_not_linked`** so the plugin's uniform mapping (403→DENIED, 404→NOT_FOUND) yields DENIED as the spec's flow demands.
4. **Private non-member = DENIED vs existence-hiding.** The web UI hides private schematics with 404, but the spec's auth matrix demands the player see DENIED. The endpoint reuses the *same policy predicate* (`Gate::forUser($user)->allows('view', $schematic)`, i.e. `SchematicPolicy::view` incl. the admin `before()` hook) but returns **403 `access_denied`** — only trusted community-JWT plugins can reach the endpoint, so the existence leak is acceptable and the player gets accurate feedback.
5. **"Share token"** = the existing `QuickShare` model (access-code shares; bytes live in the Laravel cache, not media). "Revoked" = `revoke()` (`is_active = false`). Password-protected and non-whitelisted shares are DENIED (the plugin flow cannot supply passwords). `version_id` is ignored for `ref_type=share` (shares are unversioned snapshots).
6. **"DOWNLOADING while streaming".** The transport reads the body in one blocking call, so the plugin emits `STATUS DOWNLOADING` (main-thread hop) immediately before the blocking fetch — the wire order RESOLVING → DOWNLOADING → terminal is preserved.

---

## Cross-Repo Contract (single source of truth — referenced verbatim by both repos' tasks)

### B1. HTTP: `POST /api/v1/plugin/clipboard/resolve`

Community JWT required (`ensure_valid_jwt` group); rate limit `throttle:clipboard-resolve` = **10/min per (bearer token, player_uuid)**.

Request (JSON):

```json
{
  "player_uuid": "<minecraft uuid, dashed or undashed>",
  "ref_type": "schematic" | "share",
  "ref_id": "<schematic uuid|short_id|slug OR quick-share access code; ≤64 chars>",
  "version_id": "<schematic version uuid; ≤64 chars; optional/absent = default branch head; ignored for shares>"
}
```

Success **200**: the raw schematic bytes with headers:

- `Content-Type: application/octet-stream`
- `X-Schematio-Format: litematic|schem|schematic|mcstructure` (never `unknown`; falls back to `schem`)
- `Content-Length: <byte length>` — **mandatory**

Errors (JSON `{"error": <code>, "message": <human text>}`):

| HTTP | error code | when | plugin STATUS |
|---|---|---|---|
| 403 | `community_token_required` | non-community JWT | DENIED |
| 403 | `player_not_linked` | no `User` with `uuid == player_uuid` | DENIED |
| 403 | `access_denied` | `SchematicPolicy::view` refuses for that user | DENIED |
| 403 | `share_denied` | share revoked / expired / password-protected / not whitelisted | DENIED |
| 404 | `not_found` | unknown schematic / version / share code, or file bytes / cached share data missing | NOT_FOUND |
| 413 | `too_large` | bytes > **8388608** (8 MiB) | TOO_LARGE |
| 422 | (Laravel validation) | malformed input | ERROR |
| 429 | (throttle / share usage or rate limit) | rate limited | RATE_LIMITED |

Plugin-side mapping (Task 4): `200 → Bytes`, `401/403 → Denied`, `404 → NotFound`, `413 → TooLarge`, `429 → RateLimited`, `5xx / timeout / transport failure → Unavailable`, anything else → `Error`. Client-side caps regardless of backend: `Content-Length` header **required** and ≤ 8388608; body byte-counted during transfer (transport hard cap) and re-checked after read.

### B2. Wire format (core/ipc, protocol VERSION = 2)

- `LOAD_REQUEST` (**opcode 5**, C→S): `byte 5, varint protocolVersion, varint requestId, byte refType (0=SCHEMATIC, 1=SHARE_TOKEN), string refId (1..64 chars), string versionId (0..64 chars; "" = default branch head)`. Max encoded size **256 bytes**.
- `STATUS` (**opcode 6**, S→C): `byte 6, varint protocolVersion, varint requestId, byte state, string detail (≤256 chars, may be "")`. Max encoded size **512 bytes**. States: `0 RESOLVING, 1 DOWNLOADING, 2 OK, 3 DENIED, 4 NOT_FOUND, 5 TOO_LARGE, 6 RATE_LIMITED, 7 UNAVAILABLE, 8 ERROR`; states ≥ 2 are terminal. STATUS is generic — sub-project C reuses it with its own requestIds.
- **Opcode 3 is dead-reserved** (legacy raw-bytes LOAD_CLIPBOARD): receivers ignore it with one rate-limited log line; never reuse the number.
- Decode-size caps (`IpcCaps`): HELLO_SERVER 2048, HELLO_CLIENT 512, ATTEST 4096, LOAD_REQUEST 256, STATUS 512. Over cap → `IpcPayloadTooLargeException` thrown before parsing; handlers swallow it silently (spec: "drop quietly, no parse attempt").
- All strings are varint-length-prefixed and **strictly validated UTF-8** (malformed sequences → `IpcFormatException`).
- `LOAD_REQUEST` is accepted ONLY from players whose connection the plugin attested (ATTEST relayed this session) — the `Capabilities.LOAD_CLIPBOARD` bit (1 shl 4) is kept, advertised only when WorldEdit is present, and its semantics become reference-pull. The client offers the action ONLY when `ServerSession.trust == TrustState.VERIFIED` **and** the capability bit is set.
- The client renders `detail` as plain text: every `§` and its following formatting-code character is stripped.

---

## Task 1 — schemati: resolve endpoint (schematic refs) + rate limiter + audit

**Files:**
- Create: `/Users/harrison/Documents/code/schemati/app/Http/Controllers/Api/PluginClipboardController.php`
- Create: `/Users/harrison/Documents/code/schemati/tests/Feature/Api/PluginClipboardResolveTest.php`
- Modify: `/Users/harrison/Documents/code/schemati/routes/api.php` (one route inside the existing `Route::prefix('plugin')->middleware('ensure_valid_jwt')->name('api.plugin.')` group)
- Modify: `/Users/harrison/Documents/code/schemati/app/Providers/RouteServiceProvider.php` (register the `clipboard-resolve` limiter)
- Modify: `/Users/harrison/Documents/code/schemati/app/Models/CommunityTokenAudit.php` (add `ACTION_CLIPBOARD_RESOLVE` constant + display-name arm)

**Interfaces:**
- Consumes: contract B1; `EnsureValidJWT` merged attributes (`is_community_token`, `community_id`, `token_payload['jti']`, from `app/Http/Middleware/EnsureValidJWT.php`); `SchematicService::findSchematic(string $identifier): Schematic` (throws `ModelNotFoundException`); `SchematicPolicy::view` via `Gate::forUser($user)->allows('view', $schematic)`; `Schematic::headVersion(): ?SchematicVersion`, `Schematic::getLegacyFile()`, `Schematic->format`; `SchematicVersion->file` / `->size_bytes` / `->format`; `User.uuid === Player.id`; `CommunityToken::findByJti`; `CommunityTokenAudit::log(string $action, ?string $tokenId, ?string $actorId, ?array $metadata)`.
- Produces: route `POST /api/v1/plugin/clipboard/resolve` named `api.plugin.clipboard.resolve` behaving per contract B1 (schematic refs; share refs land in Task 2); `PluginClipboardController::MAX_BYTES = 8388608`; audit action `CommunityTokenAudit::ACTION_CLIPBOARD_RESOLVE = 'clipboard_resolve'` with one row per resolve (metadata: community_id, player_uuid, ref_type, ref_id, version_id, outcome).

- [ ] **Step 1.1: Write the failing test**

`tests/Feature/Api/PluginClipboardResolveTest.php` (setup mirrors the proven `tests/Feature/Api/PluginVersionApiTest.php` pattern):

```php
<?php

use App\Helpers\JWT;
use App\Models\Community;
use App\Models\CommunityTokenAudit;
use App\Models\Player;
use App\Models\Schematic;
use App\Models\Tag;
use App\Models\User;
use App\Services\Schematics\SchematicVersioningService;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\Http;
use Illuminate\Support\Str;

uses(RefreshDatabase::class);

const CLIP_RESOLVE_URL = '/api/v1/plugin/clipboard/resolve';

beforeEach(function () {
    // Stop the Player boot hook from hitting the Mojang API.
    Http::fake(['*' => Http::response(['name' => 'TestPlayer', 'id' => 'some-uuid'], 200)]);
    app(\Spatie\Permission\PermissionRegistrar::class)->forgetCachedPermissions();

    clipSetupTagHierarchy();

    $this->creator = Player::create([
        'id' => Str::uuid()->toString(),
        'last_seen_name' => 'ClipCreator',
    ]);

    $this->community = Community::create([
        'name' => 'Clip Community',
        'slug' => 'clip-community',
        'description' => 'Community for clipboard resolve tests',
        'is_public' => true,
        'is_active' => true,
        'created_by' => $this->creator->id,
    ]);
    $this->community->addMember($this->creator, Community::ROLE_ADMIN);

    $this->token = JWT::generateCommunityToken($this->community, $this->creator, 'Clip Token')['token'];

    // The requesting in-game player, WITH a linked site account (User.uuid === Player.id).
    $this->player = Player::create([
        'id' => Str::uuid()->toString(),
        'last_seen_name' => 'ClipPlayer',
    ]);
    $this->user = User::factory()->create(['uuid' => $this->player->id]);
});

/** Root + 'community' parent tags, required by Community::getOrCreateTag(). */
function clipSetupTagHierarchy(): void
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

/** Plain (non-versioned) schematic with media bytes, authored by $author. */
function clipMakeSchematic(Player $author, bool $public = true, string $bytes = 'clip-legacy-bytes'): Schematic
{
    $schematic = Schematic::factory()->create([
        'name' => 'Clip Schem',
        'is_public' => $public,
        'posted_by' => $author->id,
    ]);
    $schematic->authors()->sync([$author->id]);
    $schematic->addMediaFromString($bytes)
        ->usingFileName('build.litematic')
        ->toMediaCollection('schematic');

    return $schematic->fresh();
}

function clipMakeVersionedSchematic(Player $author, bool $public = true, string $bytes = 'clip-v1-bytes'): Schematic
{
    $schematic = clipMakeSchematic($author, $public, $bytes);

    return app(SchematicVersioningService::class)->convert($schematic, $author->id)->fresh();
}

it('streams a public schematic to any linked player', function () {
    $schematic = clipMakeSchematic($this->creator, public: true, bytes: 'public-bytes');

    $response = $this->withToken($this->token)->postJson(CLIP_RESOLVE_URL, [
        'player_uuid' => $this->player->id,
        'ref_type' => 'schematic',
        'ref_id' => $schematic->id,
    ]);

    $response->assertOk()
        ->assertHeader('Content-Type', 'application/octet-stream')
        ->assertHeader('X-Schematio-Format', 'litematic')
        ->assertHeader('Content-Length', (string) strlen('public-bytes'));
    expect($response->streamedContent())->toBe('public-bytes');
});

it('serves the default branch head for a versioned schematic', function () {
    $schematic = clipMakeVersionedSchematic($this->creator, bytes: 'clip-v1-bytes');
    app(SchematicVersioningService::class)->commit(
        $schematic,
        $schematic->defaultBranch,
        'clip-v2-bytes',
        'second commit',
        $this->creator->id,
        'build.litematic',
    );

    $response = $this->withToken($this->token)->postJson(CLIP_RESOLVE_URL, [
        'player_uuid' => $this->player->id,
        'ref_type' => 'schematic',
        'ref_id' => $schematic->id,
    ]);

    $response->assertOk();
    expect($response->streamedContent())->toBe('clip-v2-bytes');
});

it('serves an explicit version_id', function () {
    $schematic = clipMakeVersionedSchematic($this->creator, bytes: 'clip-v1-bytes');
    $v1 = $schematic->headVersion();
    app(SchematicVersioningService::class)->commit(
        $schematic,
        $schematic->defaultBranch,
        'clip-v2-bytes',
        'second commit',
        $this->creator->id,
        'build.litematic',
    );

    $response = $this->withToken($this->token)->postJson(CLIP_RESOLVE_URL, [
        'player_uuid' => $this->player->id,
        'ref_type' => 'schematic',
        'ref_id' => $schematic->id,
        'version_id' => $v1->id,
    ]);

    $response->assertOk();
    expect($response->streamedContent())->toBe('clip-v1-bytes');
});

it('denies a private schematic to a non-author (policy parity with web download)', function () {
    $schematic = clipMakeSchematic($this->creator, public: false);

    $this->withToken($this->token)->postJson(CLIP_RESOLVE_URL, [
        'player_uuid' => $this->player->id,
        'ref_type' => 'schematic',
        'ref_id' => $schematic->id,
    ])->assertStatus(403)->assertJson(['error' => 'access_denied']);
});

it('serves a private schematic to its author', function () {
    $schematic = clipMakeSchematic($this->player, public: false, bytes: 'private-own');

    $response = $this->withToken($this->token)->postJson(CLIP_RESOLVE_URL, [
        'player_uuid' => $this->player->id,
        'ref_type' => 'schematic',
        'ref_id' => $schematic->id,
    ]);

    $response->assertOk();
    expect($response->streamedContent())->toBe('private-own');
});

it('rejects an unlinked player uuid with 403 player_not_linked', function () {
    $schematic = clipMakeSchematic($this->creator, public: true);

    $this->withToken($this->token)->postJson(CLIP_RESOLVE_URL, [
        'player_uuid' => Str::uuid()->toString(), // no User row
        'ref_type' => 'schematic',
        'ref_id' => $schematic->id,
    ])->assertStatus(403)->assertJson(['error' => 'player_not_linked']);
});

it('returns 404 for an unknown schematic and an unknown version', function () {
    $this->withToken($this->token)->postJson(CLIP_RESOLVE_URL, [
        'player_uuid' => $this->player->id,
        'ref_type' => 'schematic',
        'ref_id' => 'does-not-exist',
    ])->assertStatus(404)->assertJson(['error' => 'not_found']);

    $schematic = clipMakeVersionedSchematic($this->creator);
    $this->withToken($this->token)->postJson(CLIP_RESOLVE_URL, [
        'player_uuid' => $this->player->id,
        'ref_type' => 'schematic',
        'ref_id' => $schematic->id,
        'version_id' => Str::uuid()->toString(),
    ])->assertStatus(404)->assertJson(['error' => 'not_found']);
});

it('refuses oversize schematics with 413 before loading bytes', function () {
    $schematic = clipMakeVersionedSchematic($this->creator);
    // Forge the head version's recorded size past the cap — the endpoint must
    // short-circuit on size_bytes without reading the media.
    $schematic->headVersion()->update(['size_bytes' => 8388609]);

    $this->withToken($this->token)->postJson(CLIP_RESOLVE_URL, [
        'player_uuid' => $this->player->id,
        'ref_type' => 'schematic',
        'ref_id' => $schematic->id,
    ])->assertStatus(413)->assertJson(['error' => 'too_large']);
});

it('rejects non-community tokens with 403 community_token_required', function () {
    $this->withToken(JWT::getTestToken())->postJson(CLIP_RESOLVE_URL, [
        'player_uuid' => $this->player->id,
        'ref_type' => 'schematic',
        'ref_id' => 'anything',
    ])->assertStatus(403)->assertJson(['error' => 'community_token_required']);
});

it('validates the request shape', function () {
    $this->withToken($this->token)->postJson(CLIP_RESOLVE_URL, [
        'player_uuid' => $this->player->id,
        'ref_type' => 'nonsense',
        'ref_id' => 'x',
    ])->assertStatus(422);

    $this->withToken($this->token)->postJson(CLIP_RESOLVE_URL, [
        'player_uuid' => $this->player->id,
        'ref_type' => 'schematic',
        'ref_id' => str_repeat('a', 65),
    ])->assertStatus(422);
});

it('rate limits to 10 per minute per (token, player)', function () {
    $schematic = clipMakeSchematic($this->creator, public: true);

    for ($i = 0; $i < 10; $i++) {
        $this->withToken($this->token)->postJson(CLIP_RESOLVE_URL, [
            'player_uuid' => $this->player->id,
            'ref_type' => 'schematic',
            'ref_id' => $schematic->id,
        ])->assertOk();
    }

    $this->withToken($this->token)->postJson(CLIP_RESOLVE_URL, [
        'player_uuid' => $this->player->id,
        'ref_type' => 'schematic',
        'ref_id' => $schematic->id,
    ])->assertStatus(429);

    // A different player_uuid on the same token is NOT limited (keyed per pair):
    // the creator has no User row, so passing the throttle proves the key includes
    // player_uuid (403 player_not_linked, not 429).
    $this->withToken($this->token)->postJson(CLIP_RESOLVE_URL, [
        'player_uuid' => $this->creator->id,
        'ref_type' => 'schematic',
        'ref_id' => $schematic->id,
    ])->assertStatus(403);
});

it('writes one audit row per resolve, including denials', function () {
    $schematic = clipMakeSchematic($this->creator, public: false);

    $this->withToken($this->token)->postJson(CLIP_RESOLVE_URL, [
        'player_uuid' => $this->player->id,
        'ref_type' => 'schematic',
        'ref_id' => $schematic->id,
    ])->assertStatus(403);

    $audit = CommunityTokenAudit::where('action', CommunityTokenAudit::ACTION_CLIPBOARD_RESOLVE)->first();
    expect($audit)->not->toBeNull();
    expect($audit->metadata['outcome'])->toBe('access_denied');
    expect($audit->metadata['player_uuid'])->toBe($this->player->id);
    expect($audit->metadata['ref_type'])->toBe('schematic');
});
```

- [ ] **Step 1.2: Run the test to verify it fails**

Run: `php artisan test --filter=PluginClipboardResolve`
Expected: FAIL — 404 on every request (route not defined) and "undefined constant `ACTION_CLIPBOARD_RESOLVE`".

- [ ] **Step 1.3: Implement**

`app/Models/CommunityTokenAudit.php` — add below the existing action constants (including `ACTION_ATTEST_ISSUED` added by sub-project A, if present):

```php
    const ACTION_CLIPBOARD_RESOLVE = 'clipboard_resolve';
```

and add one arm to `getActionNameAttribute()`'s `match`, above the `default` arm:

```php
            self::ACTION_CLIPBOARD_RESOLVE => 'Clipboard Resolve',
```

`app/Providers/RouteServiceProvider.php` — in `boot()`, immediately after the `RateLimiter::for('attest', ...)` block added by sub-project A (or after `RateLimiter::for('search-api', ...)` if the attest limiter is absent), add:

```php
        // In-game clipboard loads: 10/min per (community token, requesting player).
        RateLimiter::for('clipboard-resolve', function (Request $request) {
            $key = sha1(($request->bearerToken() ?? $request->ip()).'|'.(string) $request->input('player_uuid'));

            return Limit::perMinute(10)->by('clipboard:'.$key);
        });
```

(The file already imports `Illuminate\Cache\RateLimiting\Limit`, `Illuminate\Support\Facades\RateLimiter`, and `Illuminate\Http\Request`; add any missing `use` line.)

`routes/api.php` — inside the existing `Route::prefix('plugin')->middleware('ensure_valid_jwt')->name('api.plugin.')` group, directly below the versioning endpoints block (`schematics/{id}/branches` etc.), add:

```php
            // In-game clipboard load (reference-pull): the plugin fetches bytes on
            // behalf of a player; authorization mirrors the web download policy.
            Route::post('clipboard/resolve', [App\Http\Controllers\Api\PluginClipboardController::class, 'resolve'])
                ->middleware('throttle:clipboard-resolve')
                ->name('clipboard.resolve');
```

`app/Http/Controllers/Api/PluginClipboardController.php` (Task 1 version — `ref_type=share` lands in Task 2):

```php
<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Models\Community;
use App\Models\CommunityToken;
use App\Models\CommunityTokenAudit;
use App\Models\SchematicVersion;
use App\Models\User;
use App\Services\SchematicService;
use Illuminate\Database\Eloquent\ModelNotFoundException;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Gate;
use Symfony\Component\HttpFoundation\StreamedResponse;

/**
 * Reference-pull clipboard resolve for the server plugin (IPC sub-project B).
 *
 * The plugin sends a schematic/share REFERENCE plus the requesting player's
 * UUID; this endpoint authorizes exactly like the web download
 * (SchematicPolicy::view via the Gate) and streams the raw bytes back.
 * Contract: SchematioConnector docs/superpowers/plans/2026-07-17-ipc-b-clipboard-load.md §B1.
 */
class PluginClipboardController extends Controller
{
    /** Hard response cap (contract B1): 8 MiB. */
    public const MAX_BYTES = 8388608;

    public function __construct(private SchematicService $schematics)
    {
    }

    /**
     * POST /api/v1/plugin/clipboard/resolve (community JWT, throttle:clipboard-resolve).
     */
    public function resolve(Request $request): StreamedResponse|JsonResponse
    {
        $validated = $request->validate([
            'player_uuid' => ['required', 'string', 'max:64'],
            'ref_type' => ['required', 'string', 'in:schematic'],
            'ref_id' => ['required', 'string', 'max:64'],
            'version_id' => ['nullable', 'string', 'max:64'],
        ]);

        $community = $this->getCommunityFromToken($request);
        if (! $community) {
            return response()->json([
                'error' => 'community_token_required',
                'message' => 'This endpoint requires a community JWT token. '
                    .'Please use a token generated for your community.',
            ], 403);
        }

        $playerUuid = $this->formatUuid($validated['player_uuid']);
        $user = User::where('uuid', $playerUuid)->first();
        if (! $user) {
            $this->audit($request, $community, $playerUuid, 'player_not_linked', $validated);

            return response()->json([
                'error' => 'player_not_linked',
                'message' => 'That player has no linked schemat.io account.',
            ], 403);
        }

        return $this->resolveSchematic($request, $community, $user, $playerUuid, $validated);
    }

    private function resolveSchematic(
        Request $request,
        Community $community,
        User $user,
        string $playerUuid,
        array $validated,
    ): StreamedResponse|JsonResponse {
        try {
            $schematic = $this->schematics->findSchematic($validated['ref_id']);
        } catch (ModelNotFoundException) {
            $this->audit($request, $community, $playerUuid, 'not_found', $validated);

            return $this->notFound();
        }

        // EXACT web-download policy path: SchematicPolicy::view (public || author,
        // with the admin before() hook), evaluated as the requesting player's user.
        if (! Gate::forUser($user)->allows('view', $schematic)) {
            $this->audit($request, $community, $playerUuid, 'access_denied', $validated, [
                'schematic_id' => $schematic->id,
            ]);

            return response()->json([
                'error' => 'access_denied',
                'message' => 'That player does not have access to this schematic.',
            ], 403);
        }

        $version = null;
        if (! empty($validated['version_id'])) {
            $version = SchematicVersion::where('schematic_id', $schematic->id)
                ->where('id', $validated['version_id'])
                ->first();
            if (! $version) {
                $this->audit($request, $community, $playerUuid, 'not_found', $validated, [
                    'schematic_id' => $schematic->id,
                ]);

                return $this->notFound();
            }
        } elseif ($schematic->is_versioned) {
            $version = $schematic->headVersion();
        }

        // Short-circuit on the recorded size before touching storage.
        if ($version && $version->size_bytes > self::MAX_BYTES) {
            $this->audit($request, $community, $playerUuid, 'too_large', $validated, [
                'schematic_id' => $schematic->id,
                'size_bytes' => $version->size_bytes,
            ]);

            return $this->tooLarge();
        }

        $bytes = $version ? $version->file : $schematic->getLegacyFile();
        if ($bytes === null) {
            $this->audit($request, $community, $playerUuid, 'not_found', $validated, [
                'schematic_id' => $schematic->id,
            ]);

            return $this->notFound();
        }
        if (strlen($bytes) > self::MAX_BYTES) {
            $this->audit($request, $community, $playerUuid, 'too_large', $validated, [
                'schematic_id' => $schematic->id,
                'size_bytes' => strlen($bytes),
            ]);

            return $this->tooLarge();
        }

        $this->audit($request, $community, $playerUuid, 'ok', $validated, [
            'schematic_id' => $schematic->id,
            'version_id' => $version?->id,
            'size_bytes' => strlen($bytes),
        ]);

        return $this->streamBytes($bytes, $version ? $version->format : $schematic->format);
    }

    private function streamBytes(string $bytes, ?string $format): StreamedResponse
    {
        $format = $format && $format !== 'unknown' ? $format : 'schem';

        return response()->stream(
            function () use ($bytes) {
                echo $bytes;
            },
            200,
            [
                'Content-Type' => 'application/octet-stream',
                'Content-Length' => strlen($bytes),
                'X-Schematio-Format' => $format,
            ],
        );
    }

    private function notFound(): JsonResponse
    {
        return response()->json(['error' => 'not_found', 'message' => 'Not found.'], 404);
    }

    private function tooLarge(): JsonResponse
    {
        return response()->json([
            'error' => 'too_large',
            'message' => 'The schematic exceeds the 8 MiB clipboard limit.',
        ], 413);
    }

    /** One audit row per resolve — success and every denial (contract B1). */
    private function audit(
        Request $request,
        Community $community,
        string $playerUuid,
        string $outcome,
        array $validated,
        array $extra = [],
    ): void {
        $jti = $request->token_payload['jti'] ?? null;
        $token = $jti ? CommunityToken::findByJti($jti) : null;

        CommunityTokenAudit::log(
            CommunityTokenAudit::ACTION_CLIPBOARD_RESOLVE,
            $token?->id,
            null,
            array_merge([
                'community_id' => $community->id,
                'player_uuid' => $playerUuid,
                'ref_type' => $validated['ref_type'],
                'ref_id' => $validated['ref_id'],
                'version_id' => $validated['version_id'] ?? null,
                'outcome' => $outcome,
            ], $extra),
        );
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

    private function formatUuid(string $uuid): string
    {
        if (preg_match('/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i', $uuid)) {
            return $uuid;
        }
        $uuid = str_replace('-', '', $uuid);
        if (strlen($uuid) !== 32) {
            return $uuid;
        }

        return substr($uuid, 0, 8).'-'.substr($uuid, 8, 4).'-'.
            substr($uuid, 12, 4).'-'.substr($uuid, 16, 4).'-'.substr($uuid, 20);
    }
}
```

Note: `'ref_type' => ['required', 'string', 'in:schematic']` is intentional in Task 1 — the `'nonsense'` validation test passes because only `schematic` is accepted; Task 2 widens it to `in:schematic,share`.

- [ ] **Step 1.4: Run the test to verify it passes**

Run: `php artisan test --filter=PluginClipboardResolve`
Expected: all 12 tests PASS. Then run `php artisan test --filter=PluginVersionApi` — still green (plugin group untouched).

*(No commit — global constraint.)*

---

## Task 2 — schemati: share refs (QuickShare) on the same endpoint

**Files:**
- Modify: `/Users/harrison/Documents/code/schemati/app/Http/Controllers/Api/PluginClipboardController.php`
- Modify: `/Users/harrison/Documents/code/schemati/tests/Feature/Api/PluginClipboardResolveTest.php` (append tests)

**Interfaces:**
- Consumes: contract B1; `QuickShare::findByAccessCode(string): ?QuickShare`, `QuickShare->format`, `->getSchematicData(): ?string` (base64, cache-backed), `->revoke()`; `QuickShareService::createQuickShare(string $base64SchematicData, array $options = [], ?Player $player = null, ?CommunityToken $token = null): QuickShare`, `::validateAccess(QuickShare $share, ?string $password, ?string $playerUuid, string $accessMethod, ?string $ipAddress, ?string $userAgent): array{allowed: bool, reason: string, message: string}` (reasons: revoked, expired, data_expired, password_required, not_whitelisted, limit_exceeded, rate_limited, success), `::recordSuccessfulDownload(...)`; `QuickShareAccessLog::METHOD_API`.
- Produces: `ref_type=share` accepted per contract B1 — valid share streams bytes with `X-Schematio-Format` from `QuickShare->format`; revoked/expired/password/whitelist → 403 `share_denied`; unknown code or `data_expired` → 404 `not_found`; usage/rate limits → 429; oversize → 413.

- [ ] **Step 2.1: Write the failing tests**

Append to `tests/Feature/Api/PluginClipboardResolveTest.php` (add these `use` lines to the imports at the top: `use App\Models\QuickShare;` and `use App\Services\QuickShareService;`):

```php
function clipMakeShare(Player $creator, string $bytes = 'share-bytes', array $options = []): QuickShare
{
    return app(QuickShareService::class)->createQuickShare(
        base64_encode($bytes),
        array_merge(['format' => 'litematic'], $options),
        $creator,
    );
}

it('streams a valid quick share by access code', function () {
    $share = clipMakeShare($this->creator, 'share-bytes');

    $response = $this->withToken($this->token)->postJson(CLIP_RESOLVE_URL, [
        'player_uuid' => $this->player->id,
        'ref_type' => 'share',
        'ref_id' => $share->access_code,
    ]);

    $response->assertOk()
        ->assertHeader('Content-Type', 'application/octet-stream')
        ->assertHeader('X-Schematio-Format', 'litematic')
        ->assertHeader('Content-Length', (string) strlen('share-bytes'));
    expect($response->streamedContent())->toBe('share-bytes');
});

it('denies a revoked share', function () {
    $share = clipMakeShare($this->creator);
    $share->revoke();

    $this->withToken($this->token)->postJson(CLIP_RESOLVE_URL, [
        'player_uuid' => $this->player->id,
        'ref_type' => 'share',
        'ref_id' => $share->access_code,
    ])->assertStatus(403)->assertJson(['error' => 'share_denied']);
});

it('denies an expired share', function () {
    $share = clipMakeShare($this->creator);
    $share->update(['expires_at' => now()->subMinute()]);

    $this->withToken($this->token)->postJson(CLIP_RESOLVE_URL, [
        'player_uuid' => $this->player->id,
        'ref_type' => 'share',
        'ref_id' => $share->access_code,
    ])->assertStatus(403)->assertJson(['error' => 'share_denied']);
});

it('denies a password-protected share (the plugin flow cannot supply passwords)', function () {
    $share = clipMakeShare($this->creator, options: ['password' => 'hunter2']);

    $this->withToken($this->token)->postJson(CLIP_RESOLVE_URL, [
        'player_uuid' => $this->player->id,
        'ref_type' => 'share',
        'ref_id' => $share->access_code,
    ])->assertStatus(403)->assertJson(['error' => 'share_denied']);
});

it('denies a share whose whitelist excludes the player', function () {
    $share = clipMakeShare($this->creator, options: [
        'allowed_players' => [Str::uuid()->toString()],
    ]);

    $this->withToken($this->token)->postJson(CLIP_RESOLVE_URL, [
        'player_uuid' => $this->player->id,
        'ref_type' => 'share',
        'ref_id' => $share->access_code,
    ])->assertStatus(403)->assertJson(['error' => 'share_denied']);
});

it('returns 404 for an unknown access code', function () {
    $this->withToken($this->token)->postJson(CLIP_RESOLVE_URL, [
        'player_uuid' => $this->player->id,
        'ref_type' => 'share',
        'ref_id' => 'qs_nope',
    ])->assertStatus(404)->assertJson(['error' => 'not_found']);
});

it('refuses an oversize share with 413', function () {
    $share = clipMakeShare($this->creator, str_repeat('x', 8388609));

    $this->withToken($this->token)->postJson(CLIP_RESOLVE_URL, [
        'player_uuid' => $this->player->id,
        'ref_type' => 'share',
        'ref_id' => $share->access_code,
    ])->assertStatus(413)->assertJson(['error' => 'too_large']);
});
```

- [ ] **Step 2.2: Run to verify the new tests fail**

Run: `php artisan test --filter=PluginClipboardResolve`
Expected: the 7 new tests FAIL with 422 (`ref_type` `in:schematic` rejects `share`); the 12 Task-1 tests still pass.

- [ ] **Step 2.3: Implement**

In `app/Http/Controllers/Api/PluginClipboardController.php`:

(a) Add imports: `use App\Models\QuickShare;`, `use App\Models\QuickShareAccessLog;`, `use App\Services\QuickShareService;`.

(b) Widen the constructor:

```php
    public function __construct(
        private SchematicService $schematics,
        private QuickShareService $quickShares,
    ) {
    }
```

(c) In `resolve()`, change the validation line for `ref_type` to:

```php
            'ref_type' => ['required', 'string', 'in:schematic,share'],
```

and replace the final `return $this->resolveSchematic(...)` line with:

```php
        return $validated['ref_type'] === 'share'
            ? $this->resolveShare($request, $community, $playerUuid, $validated)
            : $this->resolveSchematic($request, $community, $user, $playerUuid, $validated);
```

(d) Add the share branch below `resolveSchematic()`:

```php
    /**
     * ref_type=share: quick-share access codes. Validity (revocation = is_active,
     * expiry, password, whitelist, usage/rate limits) reuses QuickShareService::validateAccess
     * — the same rules as the public quick-share download endpoint. version_id is
     * ignored: shares are unversioned snapshots.
     */
    private function resolveShare(
        Request $request,
        Community $community,
        string $playerUuid,
        array $validated,
    ): StreamedResponse|JsonResponse {
        $share = QuickShare::findByAccessCode($validated['ref_id']);
        if (! $share) {
            $this->audit($request, $community, $playerUuid, 'not_found', $validated);

            return $this->notFound();
        }

        $access = $this->quickShares->validateAccess(
            $share,
            null, // the plugin flow cannot supply share passwords
            $playerUuid,
            QuickShareAccessLog::METHOD_API,
            $request->ip(),
            $request->userAgent(),
        );

        if (! $access['allowed']) {
            $this->audit($request, $community, $playerUuid, 'share_'.$access['reason'], $validated, [
                'quick_share_id' => $share->id,
            ]);

            return match (true) {
                $access['reason'] === 'data_expired' => $this->notFound(),
                in_array($access['reason'], ['limit_exceeded', 'rate_limited'], true) => response()->json([
                    'error' => 'rate_limited',
                    'message' => $access['message'],
                ], 429),
                default => response()->json([
                    'error' => 'share_denied',
                    'message' => $access['message'],
                ], 403),
            };
        }

        $bytes = base64_decode((string) $share->getSchematicData(), true);
        if ($bytes === false || $bytes === '') {
            $this->audit($request, $community, $playerUuid, 'not_found', $validated, [
                'quick_share_id' => $share->id,
            ]);

            return $this->notFound();
        }
        if (strlen($bytes) > self::MAX_BYTES) {
            $this->audit($request, $community, $playerUuid, 'too_large', $validated, [
                'quick_share_id' => $share->id,
                'size_bytes' => strlen($bytes),
            ]);

            return $this->tooLarge();
        }

        $this->quickShares->recordSuccessfulDownload(
            $share,
            $playerUuid,
            QuickShareAccessLog::METHOD_API,
            $request->ip(),
            $request->userAgent(),
        );
        $this->audit($request, $community, $playerUuid, 'ok', $validated, [
            'quick_share_id' => $share->id,
            'size_bytes' => strlen($bytes),
        ]);

        return $this->streamBytes($bytes, $share->format);
    }
```

- [ ] **Step 2.4: Run to verify everything passes**

Run: `php artisan test --filter=PluginClipboardResolve`
Expected: all 19 tests PASS. Also run `php artisan test --filter=QuickShare` — the existing quick-share suites stay green.

*(No commit — global constraint.)*

---

## Task 3 — :core — opcodes 5/6, size caps, strict UTF-8

**Files:**
- Modify: `/Users/harrison/IdeaProjects/SchematioConnector/core/src/main/kotlin/io/schemat/connector/core/ipc/IpcProtocol.kt`
- Modify: `/Users/harrison/IdeaProjects/SchematioConnector/core/src/main/kotlin/io/schemat/connector/core/ipc/IpcBuffer.kt`
- Modify: `/Users/harrison/IdeaProjects/SchematioConnector/core/src/main/kotlin/io/schemat/connector/core/ipc/IpcMessages.kt`
- Test: `/Users/harrison/IdeaProjects/SchematioConnector/core/src/test/kotlin/io/schemat/connector/core/ipc/IpcCodecTest.kt` (append tests)

**Interfaces:**
- Consumes: contract B2; existing `IpcWriter`/`IpcReader` primitives; A's `IpcOpcode.ATTEST = 4` and decode functions (`decodeHelloServer/decodeHelloClient/decodeAttest`).
- Produces (exact signatures — later tasks rely on these):

```kotlin
// IpcProtocol.kt
object IpcOpcode { /* existing 1..4 */ const val LOAD_REQUEST: Int = 5; const val STATUS: Int = 6 }
object IpcCaps {
    const val HELLO_SERVER: Int = 2048; const val HELLO_CLIENT: Int = 512; const val ATTEST: Int = 4096
    const val LOAD_REQUEST: Int = 256; const val STATUS: Int = 512
    fun forOpcode(opcode: Int): Int?
}
enum class LoadRefType(val wire: Int) { SCHEMATIC(0), SHARE_TOKEN(1);
    companion object { fun fromWire(wire: Int): LoadRefType? } }
enum class StatusState(val wire: Int) { RESOLVING(0), DOWNLOADING(1), OK(2), DENIED(3), NOT_FOUND(4),
    TOO_LARGE(5), RATE_LIMITED(6), UNAVAILABLE(7), ERROR(8);
    val isTerminal: Boolean  // wire >= OK.wire
    companion object { fun fromWire(wire: Int): StatusState? } }

// IpcBuffer.kt
open class IpcFormatException(message: String) : RuntimeException(message)
class IpcPayloadTooLargeException(message: String) : IpcFormatException(message)
// IpcReader.readString() now rejects malformed UTF-8 with IpcFormatException

// IpcMessages.kt
data class LoadRequest(val protocolVersion: Int, val requestId: Int, val refType: Int,
    val refId: String, val versionId: String = "") { companion object { const val MAX_REF_CHARS = 64 } }
data class Status(val protocolVersion: Int, val requestId: Int, val state: Int,
    val detail: String = "") { companion object { const val MAX_DETAIL_CHARS = 256 } }
// IpcCodec gains: encodeLoadRequest(msg): ByteArray, decodeLoadRequest(bytes): LoadRequest,
//                 encodeStatus(msg): ByteArray,      decodeStatus(bytes): Status
// and EVERY decode function starts with a cap check (IpcPayloadTooLargeException, no parsing).
```

- [ ] **Step 3.1: Write the failing tests**

Append to `IpcCodecTest.kt` (keep all existing tests, including the LoadClipboard ones — they are deleted in Task 8, not here):

```kotlin
    @Test
    fun `load request round-trips`() {
        val msg = LoadRequest(
            protocolVersion = IpcProtocol.VERSION,
            requestId = 42,
            refType = LoadRefType.SCHEMATIC.wire,
            refId = "11111111-2222-3333-4444-555555555555",
            versionId = "66666666-7777-8888-9999-aaaaaaaaaaaa",
        )
        val bytes = IpcCodec.encodeLoadRequest(msg)
        assertEquals(IpcOpcode.LOAD_REQUEST, IpcCodec.peekOpcode(bytes))
        assertTrue(bytes.size <= IpcCaps.LOAD_REQUEST)
        assertEquals(msg, IpcCodec.decodeLoadRequest(bytes))
    }

    @Test
    fun `load request with empty versionId round-trips (default branch head)`() {
        val msg = LoadRequest(IpcProtocol.VERSION, 1, LoadRefType.SHARE_TOKEN.wire, "qs_abc123", "")
        assertEquals(msg, IpcCodec.decodeLoadRequest(IpcCodec.encodeLoadRequest(msg)))
    }

    @Test
    fun `load request rejects bad fields at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            LoadRequest(2, -1, 0, "x") // negative requestId
        }
        assertThrows(IllegalArgumentException::class.java) {
            LoadRequest(2, 1, 7, "x") // unknown refType
        }
        assertThrows(IllegalArgumentException::class.java) {
            LoadRequest(2, 1, 0, "") // empty refId
        }
        assertThrows(IllegalArgumentException::class.java) {
            LoadRequest(2, 1, 0, "a".repeat(65)) // refId over 64 chars
        }
        assertThrows(IllegalArgumentException::class.java) {
            LoadRequest(2, 1, 0, "x", "a".repeat(65)) // versionId over 64 chars
        }
    }

    @Test
    fun `decoding a hand-built load request with a bad refType throws IpcFormatException`() {
        val bytes = IpcWriter().apply {
            writeByte(IpcOpcode.LOAD_REQUEST)
            writeVarInt(2)
            writeVarInt(1)
            writeByte(9) // refType from the future
            writeString("ref")
            writeString("")
        }.toByteArray()
        assertThrows(IpcFormatException::class.java) { IpcCodec.decodeLoadRequest(bytes) }
    }

    @Test
    fun `status round-trips including empty detail`() {
        val msg = Status(IpcProtocol.VERSION, 42, StatusState.DENIED.wire, "no access")
        val bytes = IpcCodec.encodeStatus(msg)
        assertEquals(IpcOpcode.STATUS, IpcCodec.peekOpcode(bytes))
        assertEquals(msg, IpcCodec.decodeStatus(bytes))

        val bare = Status(IpcProtocol.VERSION, 7, StatusState.OK.wire, "")
        assertEquals(bare, IpcCodec.decodeStatus(IpcCodec.encodeStatus(bare)))
    }

    @Test
    fun `status rejects unknown states and oversize detail at construction`() {
        assertThrows(IllegalArgumentException::class.java) { Status(2, 1, 99, "") }
        assertThrows(IllegalArgumentException::class.java) { Status(2, 1, 0, "a".repeat(257)) }
    }

    @Test
    fun `encodeStatus drops the detail rather than exceed the 512-byte cap`() {
        // 200 x '✓' = 200 chars (legal) but 600 UTF-8 bytes — over the wire cap.
        val fat = Status(IpcProtocol.VERSION, 1, StatusState.ERROR.wire, "✓".repeat(200))
        val bytes = IpcCodec.encodeStatus(fat)
        assertTrue(bytes.size <= IpcCaps.STATUS)
        assertEquals("", IpcCodec.decodeStatus(bytes).detail)
    }

    @Test
    fun `oversize payloads are rejected before parsing`() {
        val fatLoad = ByteArray(IpcCaps.LOAD_REQUEST + 1).also { it[0] = IpcOpcode.LOAD_REQUEST.toByte() }
        assertThrows(IpcPayloadTooLargeException::class.java) { IpcCodec.decodeLoadRequest(fatLoad) }

        val fatStatus = ByteArray(IpcCaps.STATUS + 1).also { it[0] = IpcOpcode.STATUS.toByte() }
        assertThrows(IpcPayloadTooLargeException::class.java) { IpcCodec.decodeStatus(fatStatus) }

        val fatHello = ByteArray(IpcCaps.HELLO_SERVER + 1).also { it[0] = IpcOpcode.HELLO_SERVER.toByte() }
        assertThrows(IpcPayloadTooLargeException::class.java) { IpcCodec.decodeHelloServer(fatHello) }
    }

    @Test
    fun `every live opcode has a size cap`() {
        for (opcode in intArrayOf(
            IpcOpcode.HELLO_SERVER, IpcOpcode.HELLO_CLIENT, IpcOpcode.ATTEST,
            IpcOpcode.LOAD_REQUEST, IpcOpcode.STATUS,
        )) {
            assertNotNull(IpcCaps.forOpcode(opcode), "opcode $opcode has no cap")
        }
    }

    @Test
    fun `strings must be valid utf-8`() {
        // A LOAD_REQUEST whose refId bytes are an invalid UTF-8 sequence.
        val bytes = IpcWriter().apply {
            writeByte(IpcOpcode.LOAD_REQUEST)
            writeVarInt(2)
            writeVarInt(1)
            writeByte(0)
            writeBytes(byteArrayOf(0xC3.toByte(), 0x28)) // varint len 2 + invalid continuation
            writeString("")
        }.toByteArray()
        assertThrows(IpcFormatException::class.java) { IpcCodec.decodeLoadRequest(bytes) }
    }
```

Add these imports to the test file if missing: `import org.junit.jupiter.api.Assertions.assertNotNull` and `import org.junit.jupiter.api.Assertions.assertTrue`.

- [ ] **Step 3.2: Run to verify compilation fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:test`
Expected: compilation FAILS (no `LoadRequest`, `Status`, `IpcCaps`, `LoadRefType`, `StatusState`, `IpcPayloadTooLargeException`).

- [ ] **Step 3.3: Implement**

`IpcProtocol.kt` — three edits:

(a) In `IpcOpcode`, replace the `LOAD_CLIPBOARD` doc comment and append the new opcodes so the object reads:

```kotlin
/** First byte of every payload; selects the message type on a single multiplexed channel. */
object IpcOpcode {
    const val HELLO_SERVER: Int = 1
    const val HELLO_CLIENT: Int = 2

    /**
     * DEAD-RESERVED. The v1 raw-bytes clipboard load (POC) was removed in protocol v2:
     * receivers ignore this opcode (one rate-limited log line) and 3 must never be reused.
     */
    const val LOAD_CLIPBOARD: Int = 3

    /** S2C: backend-signed attestation of the server's community binding (protocol v2). */
    const val ATTEST: Int = 4

    /** C2S: reference-pull clipboard load — the client sends a schematic REFERENCE, never bytes. */
    const val LOAD_REQUEST: Int = 5

    /** S2C: progress/terminal status for a client request. Generic: sub-project C reuses it. */
    const val STATUS: Int = 6
}
```

(b) In `Capabilities`, replace the `LOAD_CLIPBOARD` line's comment:

```kotlin
    const val LOAD_CLIPBOARD: Int = 1 shl 4       // server can pull a REFERENCED schematic from the backend into a player's WorldEdit clipboard (reference-pull, protocol >= 2)
```

(c) Append at the end of the file (after `IpcPlatform`):

```kotlin
/**
 * Per-opcode maximum encoded payload size, enforced at the TOP of every decode
 * function: an over-cap buffer throws [IpcPayloadTooLargeException] before any
 * parsing (spec: drop quietly, no parse attempt).
 */
object IpcCaps {
    const val HELLO_SERVER: Int = 2048
    const val HELLO_CLIENT: Int = 512
    const val ATTEST: Int = 4096
    const val LOAD_REQUEST: Int = 256
    const val STATUS: Int = 512

    /** Null for opcodes without a live decoder (unknown / dead-reserved). */
    fun forOpcode(opcode: Int): Int? = when (opcode) {
        IpcOpcode.HELLO_SERVER -> HELLO_SERVER
        IpcOpcode.HELLO_CLIENT -> HELLO_CLIENT
        IpcOpcode.ATTEST -> ATTEST
        IpcOpcode.LOAD_REQUEST -> LOAD_REQUEST
        IpcOpcode.STATUS -> STATUS
        else -> null
    }
}

/** Reference kind carried by a LOAD_REQUEST. */
enum class LoadRefType(val wire: Int) {
    SCHEMATIC(0),
    SHARE_TOKEN(1),
    ;

    companion object {
        fun fromWire(wire: Int): LoadRefType? = entries.firstOrNull { it.wire == wire }
    }
}

/** STATUS states. Wire values are frozen protocol; states >= OK are terminal. */
enum class StatusState(val wire: Int) {
    RESOLVING(0),
    DOWNLOADING(1),
    OK(2),
    DENIED(3),
    NOT_FOUND(4),
    TOO_LARGE(5),
    RATE_LIMITED(6),
    UNAVAILABLE(7),
    ERROR(8),
    ;

    /** Terminal states complete a request; RESOLVING/DOWNLOADING are progress. */
    val isTerminal: Boolean get() = wire >= OK.wire

    companion object {
        fun fromWire(wire: Int): StatusState? = entries.firstOrNull { it.wire == wire }
    }
}
```

`IpcBuffer.kt` — two edits:

(a) Replace the exception declaration at the top:

```kotlin
/** Thrown when a buffer is malformed or truncated. */
open class IpcFormatException(message: String) : RuntimeException(message)

/**
 * Thrown by decoders when a payload exceeds its opcode's [IpcCaps] limit. Distinct
 * from plain format errors so handlers can drop it QUIETLY (spec: no parse, no log spam).
 */
class IpcPayloadTooLargeException(message: String) : IpcFormatException(message)
```

(b) Replace `IpcReader.readString()` with a strict-UTF-8 version (add imports `java.nio.ByteBuffer`, `java.nio.charset.CharacterCodingException`, `java.nio.charset.CodingErrorAction` at the top of the file):

```kotlin
    fun readString(): String {
        val len = readVarInt()
        if (len < 0 || len > remaining()) throw IpcFormatException("string length $len exceeds buffer")
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val s = try {
            decoder.decode(ByteBuffer.wrap(bytes, pos, len)).toString()
        } catch (e: CharacterCodingException) {
            throw IpcFormatException("string is not valid UTF-8")
        }
        pos += len
        return s
    }
```

`IpcMessages.kt` — three edits:

(a) Append the two message classes after `LoadClipboard` (which stays until Task 8):

```kotlin
/**
 * C2S: reference-pull clipboard load. Carries a REFERENCE only — never schematic
 * bytes (spec invariant 1). [refType] and lengths are validated at construction;
 * [versionId] == "" means "default branch head".
 */
data class LoadRequest(
    val protocolVersion: Int,
    val requestId: Int,
    val refType: Int,
    val refId: String,
    val versionId: String = "",
) {
    init {
        require(requestId >= 0) { "requestId must be non-negative" }
        require(LoadRefType.fromWire(refType) != null) { "unknown refType $refType" }
        require(refId.isNotEmpty() && refId.length <= MAX_REF_CHARS) { "refId must be 1..$MAX_REF_CHARS chars" }
        require(versionId.length <= MAX_REF_CHARS) { "versionId must be at most $MAX_REF_CHARS chars" }
    }

    companion object {
        const val MAX_REF_CHARS: Int = 64
    }
}

/**
 * S2C: progress/terminal status for [requestId]. [detail] is optional human text —
 * clients render it as PLAIN text (formatting codes stripped client-side).
 */
data class Status(
    val protocolVersion: Int,
    val requestId: Int,
    val state: Int,
    val detail: String = "",
) {
    init {
        require(requestId >= 0) { "requestId must be non-negative" }
        require(StatusState.fromWire(state) != null) { "unknown status state $state" }
        require(detail.length <= MAX_DETAIL_CHARS) { "detail must be at most $MAX_DETAIL_CHARS chars" }
    }

    companion object {
        const val MAX_DETAIL_CHARS: Int = 256
    }
}
```

(b) In `IpcCodec`, add a private cap guard and call it as the FIRST line of every decode function (`decodeHelloServer`, `decodeHelloClient`, `decodeAttest`, and the two new ones — `decodeLoadClipboard` gets none: opcode 3 is dead-reserved and uncapped by design until its Task-8 deletion):

```kotlin
    /** Spec: payloads over their opcode cap are dropped without any parse attempt. */
    private fun checkCap(bytes: ByteArray, opcode: Int) {
        val cap = IpcCaps.forOpcode(opcode) ?: return
        if (bytes.size > cap) {
            throw IpcPayloadTooLargeException("opcode $opcode payload ${bytes.size}B exceeds cap ${cap}B")
        }
    }
```

e.g. `decodeHelloServer` begins:

```kotlin
    fun decodeHelloServer(bytes: ByteArray): HelloServer {
        checkCap(bytes, IpcOpcode.HELLO_SERVER)
        val r = IpcReader(bytes)
        ...
```

(same one-line insertion for `decodeHelloClient` with `IpcOpcode.HELLO_CLIENT` and `decodeAttest` with `IpcOpcode.ATTEST`).

(c) Add the encode/decode pairs to `IpcCodec`:

```kotlin
    fun encodeLoadRequest(msg: LoadRequest): ByteArray {
        val bytes = IpcWriter().apply {
            writeByte(IpcOpcode.LOAD_REQUEST)
            writeVarInt(msg.protocolVersion)
            writeVarInt(msg.requestId)
            writeByte(msg.refType)
            writeString(msg.refId)
            writeString(msg.versionId)
        }.toByteArray()
        require(bytes.size <= IpcCaps.LOAD_REQUEST) {
            "encoded LOAD_REQUEST is ${bytes.size}B (cap ${IpcCaps.LOAD_REQUEST}B) — refs must be ASCII ids"
        }
        return bytes
    }

    /** If [msg.detail] pushes the frame over the cap (multibyte text), it is sent empty instead. */
    fun encodeStatus(msg: Status): ByteArray {
        val bytes = rawEncodeStatus(msg)
        if (bytes.size <= IpcCaps.STATUS) return bytes
        return rawEncodeStatus(Status(msg.protocolVersion, msg.requestId, msg.state, ""))
    }

    private fun rawEncodeStatus(msg: Status): ByteArray = IpcWriter().apply {
        writeByte(IpcOpcode.STATUS)
        writeVarInt(msg.protocolVersion)
        writeVarInt(msg.requestId)
        writeByte(msg.state)
        writeString(msg.detail)
    }.toByteArray()

    fun decodeLoadRequest(bytes: ByteArray): LoadRequest {
        checkCap(bytes, IpcOpcode.LOAD_REQUEST)
        val r = IpcReader(bytes)
        val op = r.readByte()
        if (op != IpcOpcode.LOAD_REQUEST) throw IpcFormatException("expected LOAD_REQUEST, got $op")
        try {
            return LoadRequest(
                protocolVersion = r.readVarInt(),
                requestId = r.readVarInt(),
                refType = r.readByte(),
                refId = r.readString(),
                versionId = r.readString(),
            )
        } catch (e: IllegalArgumentException) {
            throw IpcFormatException("invalid LOAD_REQUEST: ${e.message}")
        }
    }

    fun decodeStatus(bytes: ByteArray): Status {
        checkCap(bytes, IpcOpcode.STATUS)
        val r = IpcReader(bytes)
        val op = r.readByte()
        if (op != IpcOpcode.STATUS) throw IpcFormatException("expected STATUS, got $op")
        try {
            return Status(
                protocolVersion = r.readVarInt(),
                requestId = r.readVarInt(),
                state = r.readByte(),
                detail = r.readString(),
            )
        } catch (e: IllegalArgumentException) {
            throw IpcFormatException("invalid STATUS: ${e.message}")
        }
    }
```

- [ ] **Step 3.4: Run to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:test`
Expected: BUILD SUCCESSFUL, all new + existing tests green. Also run `:bukkit:test` and `:fabric:1.21.11:test` — both still compile (nothing existing was removed).

*(No commit — global constraint.)*

---

## Task 4 — :core — ClipboardResolveClient + byte-counted transport cap

**Files:**
- Modify: `/Users/harrison/IdeaProjects/SchematioConnector/core/src/main/kotlin/io/schemat/connector/core/modapi/transport/ApiTransport.kt` (open exception + subclass)
- Modify: `/Users/harrison/IdeaProjects/SchematioConnector/core/src/main/kotlin/io/schemat/connector/core/modapi/transport/HttpTransport.kt` (configurable response cap)
- Create: `/Users/harrison/IdeaProjects/SchematioConnector/core/src/main/kotlin/io/schemat/connector/core/modapi/ClipboardResolveClient.kt`
- Modify: `/Users/harrison/IdeaProjects/SchematioConnector/core/src/test/kotlin/io/schemat/connector/core/modapi/FakeTransport.kt` (add `enqueueFailure`)
- Test: `/Users/harrison/IdeaProjects/SchematioConnector/core/src/test/kotlin/io/schemat/connector/core/modapi/ClipboardResolveClientTest.kt`

**Interfaces:**
- Consumes: contract B1 (plugin-side mapping table); `ApiTransport`/`ApiRequest`/`ApiResponse`/`HttpMethod` (existing); `LoadRefType` (Task 3); `kotlinx.coroutines.withTimeoutOrNull`.
- Produces:

```kotlin
// ApiTransport.kt
open class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)
class ResponseTooLargeException(message: String) : TransportException(message)

// HttpTransport.kt — new defaulted constructor param; behavior unchanged for existing callers
class HttpTransport(apiEndpoint: String, logger: Logger, trustAllCertificates: Boolean = false,
    private val maxResponseSizeBytes: Long = MAX_RESPONSE_SIZE.toLong())

// ClipboardResolveClient.kt
sealed class ClipboardResolveOutcome {
    class Bytes(val bytes: ByteArray, val format: String) : ClipboardResolveOutcome()
    object Denied : ClipboardResolveOutcome(); object NotFound : ClipboardResolveOutcome()
    object TooLarge : ClipboardResolveOutcome(); object RateLimited : ClipboardResolveOutcome()
    object Unavailable : ClipboardResolveOutcome(); object Error : ClipboardResolveOutcome()
}
class ClipboardResolveClient(transport: ApiTransport, tokenProvider: () -> String?,
    timeoutMs: Long = 30_000, maxBytes: Int = MAX_SCHEMATIC_BYTES) {
    companion object { const val MAX_SCHEMATIC_BYTES: Int = 8 * 1024 * 1024 }
    suspend fun resolve(playerUuid: String, refType: LoadRefType, refId: String, versionId: String): ClipboardResolveOutcome
}
```

- [ ] **Step 4.1: Write the failing test**

`ClipboardResolveClientTest.kt`:

```kotlin
package io.schemat.connector.core.modapi

import io.schemat.connector.core.ipc.LoadRefType
import io.schemat.connector.core.modapi.transport.HttpMethod
import io.schemat.connector.core.modapi.transport.ResponseTooLargeException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClipboardResolveClientTest {

    private val payload = ByteArray(5) { (it + 1).toByte() }
    private val okHeaders = mapOf("Content-Length" to "5", "X-Schematio-Format" to "litematic")

    private fun client(transport: FakeTransport, token: String? = "jwt") =
        ClipboardResolveClient(transport, { token })

    @Test
    fun `posts the reference and parses bytes + format header`() = runTest {
        val transport = FakeTransport()
        transport.enqueueBinary(200, payload, okHeaders)

        val outcome = client(transport).resolve(
            "11111111-2222-3333-4444-555555555555",
            LoadRefType.SCHEMATIC,
            "my-schem",
            "v-1",
        )

        outcome as ClipboardResolveOutcome.Bytes
        assertTrue(outcome.bytes.contentEquals(payload))
        assertEquals("litematic", outcome.format)

        val request = transport.lastRequest()
        assertEquals(HttpMethod.POST, request.method)
        assertEquals("/plugin/clipboard/resolve", request.path)
        assertEquals("jwt", transport.lastToken())
        assertEquals(
            """{"player_uuid":"11111111-2222-3333-4444-555555555555","ref_type":"schematic","ref_id":"my-schem","version_id":"v-1"}""",
            request.jsonBody,
        )
    }

    @Test
    fun `share refs post ref_type share and omit empty version_id`() = runTest {
        val transport = FakeTransport()
        transport.enqueueBinary(200, payload, okHeaders)

        client(transport).resolve("uuid", LoadRefType.SHARE_TOKEN, "qs_abc", "")

        assertEquals(
            """{"player_uuid":"uuid","ref_type":"share","ref_id":"qs_abc"}""",
            transport.lastRequest().jsonBody,
        )
    }

    @Test
    fun `format falls back to schem and headers are case-insensitive`() = runTest {
        val transport = FakeTransport()
        transport.enqueueBinary(200, payload, mapOf("content-length" to "5"))

        val outcome = client(transport).resolve("u", LoadRefType.SCHEMATIC, "r", "")

        outcome as ClipboardResolveOutcome.Bytes
        assertEquals("schem", outcome.format)
    }

    @Test
    fun `missing Content-Length is a protocol violation (Error)`() = runTest {
        val transport = FakeTransport()
        transport.enqueueBinary(200, payload, mapOf("X-Schematio-Format" to "schem"))

        assertEquals(
            ClipboardResolveOutcome.Error,
            client(transport).resolve("u", LoadRefType.SCHEMATIC, "r", ""),
        )
    }

    @Test
    fun `oversize Content-Length or body maps to TooLarge`() = runTest {
        val transport = FakeTransport()
        // Header over cap (body small): rejected on the header alone.
        transport.enqueueBinary(200, payload, mapOf("Content-Length" to "9000000"))
        assertEquals(
            ClipboardResolveOutcome.TooLarge,
            client(transport).resolve("u", LoadRefType.SCHEMATIC, "r", ""),
        )

        // Lying (small) Content-Length with an over-cap body: rejected on the counted bytes.
        val fat = ByteArray(ClipboardResolveClient.MAX_SCHEMATIC_BYTES + 1)
        transport.enqueueBinary(200, fat, mapOf("Content-Length" to "5"))
        assertEquals(
            ClipboardResolveOutcome.TooLarge,
            client(transport).resolve("u", LoadRefType.SCHEMATIC, "r", ""),
        )

        // Transport-level hard cap (ResponseTooLargeException while streaming).
        transport.enqueueFailure(ResponseTooLargeException("too big"))
        assertEquals(
            ClipboardResolveOutcome.TooLarge,
            client(transport).resolve("u", LoadRefType.SCHEMATIC, "r", ""),
        )
    }

    @Test
    fun `status codes map per the contract`() = runTest {
        val transport = FakeTransport()
        val client = client(transport)
        val cases = listOf(
            401 to ClipboardResolveOutcome.Denied,
            403 to ClipboardResolveOutcome.Denied,
            404 to ClipboardResolveOutcome.NotFound,
            413 to ClipboardResolveOutcome.TooLarge,
            429 to ClipboardResolveOutcome.RateLimited,
            500 to ClipboardResolveOutcome.Unavailable,
            503 to ClipboardResolveOutcome.Unavailable,
            402 to ClipboardResolveOutcome.Error,
        )
        for ((status, expected) in cases) {
            transport.enqueue(status, """{"error":"x"}""")
            assertEquals(expected, client.resolve("u", LoadRefType.SCHEMATIC, "r", ""), "status $status")
        }
    }

    @Test
    fun `no token or network failure maps to Unavailable`() = runTest {
        val silent = FakeTransport()
        assertEquals(
            ClipboardResolveOutcome.Unavailable,
            client(silent, token = null).resolve("u", LoadRefType.SCHEMATIC, "r", ""),
        )
        assertEquals(0, silent.requests.size) // no token -> no request at all

        val failing = FakeTransport()
        failing.enqueueNetworkFailure()
        assertEquals(
            ClipboardResolveOutcome.Unavailable,
            client(failing).resolve("u", LoadRefType.SCHEMATIC, "r", ""),
        )
    }
}
```

- [ ] **Step 4.2: Run to verify compilation fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:test`
Expected: compilation FAILS (no `ClipboardResolveClient`, `ClipboardResolveOutcome`, `ResponseTooLargeException`, `FakeTransport.enqueueFailure`).

- [ ] **Step 4.3: Implement**

`ApiTransport.kt` — replace the `TransportException` declaration with:

```kotlin
/** Thrown/returned transport-level failure (no HTTP response at all). */
open class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The response exceeded the transport's byte-counted size cap (declared OR actual —
 * a lying Content-Length is caught during transfer). Distinct so callers can map it
 * to a TOO_LARGE outcome rather than a generic "backend unavailable".
 */
class ResponseTooLargeException(message: String) : TransportException(message)
```

`HttpTransport.kt` — three edits:

(a) Constructor gains a defaulted cap (existing call sites keep the 50MB behavior):

```kotlin
class HttpTransport(
    private val apiEndpoint: String,
    private val logger: Logger,
    trustAllCertificates: Boolean = false,
    private val maxResponseSizeBytes: Long = MAX_RESPONSE_SIZE.toLong(),
) : ApiTransport, Closeable {
```

(b) In `execute()`, replace the declared-length check:

```kotlin
                        val contentLength = entity.contentLength
                        if (contentLength > maxResponseSizeBytes) {
                            EntityUtils.consume(entity)
                            throw ResponseTooLargeException("Response too large: $contentLength bytes (max: $maxResponseSizeBytes)")
                        }
```

(c) and the during-transfer check inside the read loop:

```kotlin
                                if (totalBytes > maxResponseSizeBytes) {
                                    throw ResponseTooLargeException("Response exceeded max size during transfer (max: $maxResponseSizeBytes)")
                                }
```

(`ResponseTooLargeException` is a `TransportException`, so the existing `catch (e: TransportException) { throw e }` rethrow path is unchanged.)

`FakeTransport.kt` — add one method next to `enqueueNetworkFailure`:

```kotlin
    fun enqueueFailure(exception: Throwable) {
        queue.addLast(Result.failure(exception))
    }
```

`ClipboardResolveClient.kt`:

```kotlin
package io.schemat.connector.core.modapi

import com.google.gson.JsonObject
import io.schemat.connector.core.ipc.LoadRefType
import io.schemat.connector.core.modapi.transport.ApiRequest
import io.schemat.connector.core.modapi.transport.ApiResponse
import io.schemat.connector.core.modapi.transport.ApiTransport
import io.schemat.connector.core.modapi.transport.HttpMethod
import io.schemat.connector.core.modapi.transport.ResponseTooLargeException
import io.schemat.connector.core.modapi.transport.TransportException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Outcome of a reference-pull clipboard resolve. Maps 1:1 onto terminal STATUS
 * states (contract B1): Denied -> DENIED, NotFound -> NOT_FOUND, TooLarge ->
 * TOO_LARGE, RateLimited -> RATE_LIMITED, Unavailable -> UNAVAILABLE, Error -> ERROR.
 */
sealed class ClipboardResolveOutcome {
    class Bytes(val bytes: ByteArray, val format: String) : ClipboardResolveOutcome()
    object Denied : ClipboardResolveOutcome()
    object NotFound : ClipboardResolveOutcome()
    object TooLarge : ClipboardResolveOutcome()
    object RateLimited : ClipboardResolveOutcome()
    object Unavailable : ClipboardResolveOutcome()
    object Error : ClipboardResolveOutcome()
}

/**
 * Server-side (plugin) client for POST /plugin/clipboard/resolve. Enforces the
 * client-side caps REGARDLESS of what the backend claims: Content-Length is
 * required and <= [maxBytes]; the received byte count is re-checked after the
 * transport's own byte-counted read (defense against a lying Content-Length).
 */
class ClipboardResolveClient(
    private val transport: ApiTransport,
    private val tokenProvider: () -> String?,
    private val timeoutMs: Long = 30_000,
    private val maxBytes: Int = MAX_SCHEMATIC_BYTES,
) {

    companion object {
        /** Hard schematic cap (contract B1): 8 MiB. */
        const val MAX_SCHEMATIC_BYTES: Int = 8 * 1024 * 1024
    }

    suspend fun resolve(
        playerUuid: String,
        refType: LoadRefType,
        refId: String,
        versionId: String,
    ): ClipboardResolveOutcome {
        val token = tokenProvider() ?: return ClipboardResolveOutcome.Unavailable

        val body = JsonObject().apply {
            addProperty("player_uuid", playerUuid)
            addProperty("ref_type", if (refType == LoadRefType.SHARE_TOKEN) "share" else "schematic")
            addProperty("ref_id", refId)
            if (versionId.isNotEmpty()) addProperty("version_id", versionId)
        }

        val response = try {
            withTimeoutOrNull(timeoutMs) {
                transport.execute(
                    ApiRequest(HttpMethod.POST, "/plugin/clipboard/resolve", jsonBody = body.toString()),
                    token,
                )
            }
        } catch (_: ResponseTooLargeException) {
            return ClipboardResolveOutcome.TooLarge
        } catch (_: TransportException) {
            return ClipboardResolveOutcome.Unavailable
        } ?: return ClipboardResolveOutcome.Unavailable

        return when {
            response.status == 200 -> parseBytes(response)
            response.status == 401 || response.status == 403 -> ClipboardResolveOutcome.Denied
            response.status == 404 -> ClipboardResolveOutcome.NotFound
            response.status == 413 -> ClipboardResolveOutcome.TooLarge
            response.status == 429 -> ClipboardResolveOutcome.RateLimited
            response.status >= 500 -> ClipboardResolveOutcome.Unavailable
            else -> ClipboardResolveOutcome.Error
        }
    }

    private fun parseBytes(response: ApiResponse): ClipboardResolveOutcome {
        // Content-Length is mandatory (contract B1) — its absence is a protocol violation.
        val declared = header(response, "Content-Length")?.toLongOrNull()
            ?: return ClipboardResolveOutcome.Error
        if (declared > maxBytes) return ClipboardResolveOutcome.TooLarge

        val bytes = response.body ?: return ClipboardResolveOutcome.Error
        if (bytes.size > maxBytes) return ClipboardResolveOutcome.TooLarge // lying Content-Length

        val format = header(response, "X-Schematio-Format")?.takeIf { it.isNotBlank() } ?: "schem"
        return ClipboardResolveOutcome.Bytes(bytes, format)
    }

    private fun header(response: ApiResponse, name: String): String? =
        response.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
```

- [ ] **Step 4.4: Run to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:test`
Expected: BUILD SUCCESSFUL (new tests + existing `HttpTransportTest`/`VersionApiTest` green — the transport change is a defaulted parameter and an exception subclass).

*(No commit — global constraint.)*

---

## Task 5 — bukkit — guards, LOAD_REQUEST handler, legacy deletion, permission node

**Files:**
- Create: `/Users/harrison/IdeaProjects/SchematioConnector/bukkit/src/main/kotlin/io/schemat/schematioConnector/ipc/LoadRequestGuards.kt`
- Modify: `/Users/harrison/IdeaProjects/SchematioConnector/bukkit/src/main/kotlin/io/schemat/schematioConnector/ipc/PluginIpcService.kt` (full new content below)
- Modify: `/Users/harrison/IdeaProjects/SchematioConnector/bukkit/src/main/kotlin/io/schemat/schematioConnector/SchematioConnector.kt` (clipboard client wiring)
- Modify: `/Users/harrison/IdeaProjects/SchematioConnector/bukkit/src/main/resources/plugin.yml` (permission node)
- Test: `/Users/harrison/IdeaProjects/SchematioConnector/bukkit/src/test/kotlin/io/schemat/schematioConnector/ipc/LoadRequestGuardsTest.kt`

**Interfaces:**
- Consumes: `StatusState`, `LoadRefType`, `LoadRequest`, `Status`, `IpcCodec.decodeLoadRequest/encodeStatus`, `IpcPayloadTooLargeException` (Task 3); `ClipboardResolveClient`/`ClipboardResolveOutcome` (Task 4); core `RateLimiter(maxRequests, windowMs)` with `tryAcquire(UUID): Int?`, `getWaitTimeSeconds(UUID): Int`, `removePlayer(UUID)`; A's `PluginIpcService` (constructor `SchematioConnector`, `requestAndSendAttest`, `wantsAttestation`); A's `SchematioConnector` fields (`apiEndpoint`, `communityToken`, `attestationClient`, `communityId`, `communitySlug`); `WorldEditUtil.byteArrayToClipboard(data: ByteArray): Clipboard?` and `WorldEditUtil.setClipboard(player: Player, clipboard: Clipboard)`.
- Produces: `LoadRequestGuards` (constants `LOAD_PERMISSION = "schematio.clipboard.load"`, `REQUESTS_PER_MINUTE = 5`, `WINDOW_MS = 60_000L`; `enum Failure(val state: StatusState, val detail: String)`; `fun firstFailure(worldEditAvailable: Boolean, attested: Boolean, hasPermission: Boolean): Failure?`; `fun statusFor(outcome: ClipboardResolveOutcome): Pair<StatusState, String>?`); `SchematioConnector.clipboardResolveClient: ClipboardResolveClient?`; opcode 5 handled, opcode 3 quietly ignored, legacy `handleLoadClipboard` deleted; bukkit permission `schematio.clipboard.load` default `true`.

- [ ] **Step 5.1: Write the failing test**

`LoadRequestGuardsTest.kt` (pure JVM — no Bukkit types, so it compiles regardless of the paper-api test classpath):

```kotlin
package io.schemat.schematioConnector.ipc

import io.schemat.connector.core.cache.RateLimiter
import io.schemat.connector.core.ipc.StatusState
import io.schemat.connector.core.modapi.ClipboardResolveOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

class LoadRequestGuardsTest {

    @Test
    fun `guard order is UNAVAILABLE then DENIED (attest) then DENIED (permission)`() {
        // Spec invariant 2: an unattested session is DENIED before any permission
        // or rate-limit consideration — and the caller never reaches the backend.
        assertEquals(
            LoadRequestGuards.Failure.WORLDEDIT_MISSING,
            LoadRequestGuards.firstFailure(worldEditAvailable = false, attested = false, hasPermission = false),
        )
        assertEquals(
            LoadRequestGuards.Failure.NOT_ATTESTED,
            LoadRequestGuards.firstFailure(worldEditAvailable = true, attested = false, hasPermission = false),
        )
        assertEquals(
            LoadRequestGuards.Failure.NO_PERMISSION,
            LoadRequestGuards.firstFailure(worldEditAvailable = true, attested = true, hasPermission = false),
        )
        assertNull(
            LoadRequestGuards.firstFailure(worldEditAvailable = true, attested = true, hasPermission = true),
        )
    }

    @Test
    fun `failure states map to the spec's terminal statuses`() {
        assertEquals(StatusState.UNAVAILABLE, LoadRequestGuards.Failure.WORLDEDIT_MISSING.state)
        assertEquals(StatusState.DENIED, LoadRequestGuards.Failure.NOT_ATTESTED.state)
        assertEquals(StatusState.DENIED, LoadRequestGuards.Failure.NO_PERMISSION.state)
    }

    @Test
    fun `token bucket allows 5 per minute then limits`() {
        val limiter = RateLimiter(
            maxRequests = LoadRequestGuards.REQUESTS_PER_MINUTE,
            windowMs = LoadRequestGuards.WINDOW_MS,
        )
        val player = UUID.randomUUID()
        repeat(5) { assertNotNull(limiter.tryAcquire(player), "request ${it + 1} should pass") }
        assertNull(limiter.tryAcquire(player), "6th request within the window must be limited")
        // Another player is unaffected (per-player bucket).
        assertNotNull(limiter.tryAcquire(UUID.randomUUID()))
    }

    @Test
    fun `resolve outcomes map to exactly one terminal status`() {
        assertNull(LoadRequestGuards.statusFor(ClipboardResolveOutcome.Bytes(ByteArray(1), "schem")))
        assertEquals(StatusState.DENIED, LoadRequestGuards.statusFor(ClipboardResolveOutcome.Denied)!!.first)
        assertEquals(StatusState.NOT_FOUND, LoadRequestGuards.statusFor(ClipboardResolveOutcome.NotFound)!!.first)
        assertEquals(StatusState.TOO_LARGE, LoadRequestGuards.statusFor(ClipboardResolveOutcome.TooLarge)!!.first)
        assertEquals(StatusState.RATE_LIMITED, LoadRequestGuards.statusFor(ClipboardResolveOutcome.RateLimited)!!.first)
        assertEquals(StatusState.UNAVAILABLE, LoadRequestGuards.statusFor(ClipboardResolveOutcome.Unavailable)!!.first)
        assertEquals(StatusState.ERROR, LoadRequestGuards.statusFor(ClipboardResolveOutcome.Error)!!.first)
    }
}
```

- [ ] **Step 5.2: Run to verify compilation fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :bukkit:test`
Expected: compilation FAILS (no `LoadRequestGuards`).

- [ ] **Step 5.3: Implement**

`LoadRequestGuards.kt` (new file — deliberately free of Bukkit imports so it is unit-testable):

```kotlin
package io.schemat.schematioConnector.ipc

import io.schemat.connector.core.ipc.StatusState
import io.schemat.connector.core.modapi.ClipboardResolveOutcome

/**
 * Pure decision logic for LOAD_REQUEST handling (spec: guard order, token bucket,
 * outcome -> STATUS mapping). Kept Bukkit-free so it runs under plain JUnit.
 */
object LoadRequestGuards {

    /** plugin.yml permission node, default true. */
    const val LOAD_PERMISSION: String = "schematio.clipboard.load"

    /** Per-player LOAD_REQUEST budget (spec: 5/min token bucket). */
    const val REQUESTS_PER_MINUTE: Int = 5
    const val WINDOW_MS: Long = 60_000L

    enum class Failure(val state: StatusState, val detail: String) {
        WORLDEDIT_MISSING(StatusState.UNAVAILABLE, "WorldEdit is not installed on this server"),
        NOT_ATTESTED(StatusState.DENIED, "Session is not attested; rejoin the server"),
        NO_PERMISSION(StatusState.DENIED, "Missing permission schematio.clipboard.load"),
    }

    /**
     * First failing guard, in spec order: WorldEdit present -> session attested ->
     * bukkit permission. The rate limit is checked by the caller AFTER these pass,
     * so denied/unattested requests never consume bucket slots.
     */
    fun firstFailure(worldEditAvailable: Boolean, attested: Boolean, hasPermission: Boolean): Failure? =
        when {
            !worldEditAvailable -> Failure.WORLDEDIT_MISSING
            !attested -> Failure.NOT_ATTESTED
            !hasPermission -> Failure.NO_PERMISSION
            else -> null
        }

    /** Terminal STATUS for a backend outcome; null for Bytes (the caller loads + sends OK). */
    fun statusFor(outcome: ClipboardResolveOutcome): Pair<StatusState, String>? = when (outcome) {
        is ClipboardResolveOutcome.Bytes -> null
        ClipboardResolveOutcome.Denied -> StatusState.DENIED to "The site denied access to that schematic"
        ClipboardResolveOutcome.NotFound -> StatusState.NOT_FOUND to "Schematic not found"
        ClipboardResolveOutcome.TooLarge -> StatusState.TOO_LARGE to "Schematic exceeds the 8 MiB limit"
        ClipboardResolveOutcome.RateLimited -> StatusState.RATE_LIMITED to "The site rate-limited this server; try again shortly"
        ClipboardResolveOutcome.Unavailable -> StatusState.UNAVAILABLE to "The schemat.io backend is unreachable"
        ClipboardResolveOutcome.Error -> StatusState.ERROR to "Unexpected backend response"
    }
}
```

`plugin.yml` — add under the existing feature permissions (right after `schematio.download`):

```yaml
    schematio.clipboard.load:
        description: Load schematics from schemat.io into your server-side WorldEdit clipboard via the client mod
        default: true
```

`SchematioConnector.kt` — three edits (anchors reference sub-project A's final state of this file):

(a) Next to the `attestationClient` property added by A (near `communityToken`/`apiEndpoint`), add:

```kotlin
    // Reference-pull clipboard loads (IPC sub-project B): dedicated transport with a
    // hard 8 MiB + slack byte-counted cap (defense against a lying Content-Length).
    var clipboardResolveClient: io.schemat.connector.core.modapi.ClipboardResolveClient? = null
        private set
    private var clipboardTransport: io.schemat.connector.core.modapi.transport.HttpTransport? = null
```

(b) In `loadConfiguration()`: the early-return teardown block that nulls `versionApi` / closes `versionTransport` (and nulls `attestationClient` per A) additionally gets:

```kotlin
        clipboardResolveClient = null
        clipboardTransport?.close()
        clipboardTransport = null
```

and immediately after the `attestationClient = ...` assignment A added (which itself follows `versionApi = io.schemat.connector.core.modapi.VersionApi(versionTransport!!) { ... }`), add:

```kotlin
        clipboardTransport?.close()
        clipboardTransport = io.schemat.connector.core.modapi.transport.HttpTransport(
            apiEndpoint,
            logger,
            trustAllCerts,
            maxResponseSizeBytes = io.schemat.connector.core.modapi.ClipboardResolveClient.MAX_SCHEMATIC_BYTES.toLong() + 1024,
        )
        clipboardResolveClient = io.schemat.connector.core.modapi.ClipboardResolveClient(clipboardTransport!!) {
            communityToken.takeIf { it.isNotEmpty() }
        }
```

(c) In `onDisable()`, next to the existing `versionTransport?.close()` line, add:

```kotlin
        clipboardTransport?.close()
```

`PluginIpcService.kt` — full new content (composed on top of sub-project A's Task-7 version of this file; if a local detail of A's landed code differs trivially, keep A's line and apply this task's *additions* around it):

```kotlin
package io.schemat.schematioConnector.ipc

import io.schemat.connector.core.attest.bytesToHexLower
import io.schemat.connector.core.cache.RateLimiter
import io.schemat.connector.core.ipc.Attest
import io.schemat.connector.core.ipc.Capabilities
import io.schemat.connector.core.ipc.HelloClient
import io.schemat.connector.core.ipc.HelloServer
import io.schemat.connector.core.ipc.IpcCodec
import io.schemat.connector.core.ipc.IpcFormatException
import io.schemat.connector.core.ipc.IpcOpcode
import io.schemat.connector.core.ipc.IpcPayloadTooLargeException
import io.schemat.connector.core.ipc.IpcPlatform
import io.schemat.connector.core.ipc.IpcProtocol
import io.schemat.connector.core.ipc.LoadRefType
import io.schemat.connector.core.ipc.LoadRequest
import io.schemat.connector.core.ipc.Status
import io.schemat.connector.core.ipc.StatusState
import io.schemat.connector.core.modapi.ClipboardResolveOutcome
import io.schemat.schematioConnector.SchematioConnector
import io.schemat.schematioConnector.utils.WorldEditUtil
import kotlinx.coroutines.runBlocking
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRegisterChannelEvent
import org.bukkit.plugin.messaging.PluginMessageListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Answers the Schematio IPC handshake over the plugin-messaging channel and serves
 * reference-pull clipboard loads (LOAD_REQUEST -> STATUS). The listener itself runs
 * on the main thread; the backend fetch is dispatched async and hops back for WorldEdit.
 */
class PluginIpcService(private val plugin: SchematioConnector) : PluginMessageListener, Listener {

    /** Players we have already greeted this session, to dedupe register-event vs client-hello triggers. */
    private val greeted: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /**
     * Players whose connection we successfully attested (ATTEST relayed) this session.
     * This is the server-side "session is ATTESTED" gate for LOAD_REQUEST: the server
     * cannot observe the client's verification result, but a relayed ATTEST proves the
     * community token was live and the client sent a v2 nonce.
     */
    private val attested: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /** Per-player LOAD_REQUEST budget (spec: 5/min token bucket). */
    private val loadLimiter = RateLimiter(
        maxRequests = LoadRequestGuards.REQUESTS_PER_MINUTE,
        windowMs = LoadRequestGuards.WINDOW_MS,
    )

    /** Last unknown/legacy-opcode log, for the spec's "one rate-limited log line". */
    @Volatile
    private var lastOpcodeNoiseLogMs: Long = 0L

    /**
     * Whether the WorldEdit API is on the classpath. WorldEdit is a soft dependency
     * (compileOnly + plugin.yml softdepend), so we must not touch its classes unless present —
     * otherwise the plugin would fail to load on servers without WorldEdit.
     */
    private val worldEditAvailable: Boolean = run {
        try {
            Class.forName("com.sk89q.worldedit.WorldEdit")
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Capabilities this build supports. LOAD_CLIPBOARD (reference-pull semantics in
     * protocol v2) is only advertised when WorldEdit is actually present.
     */
    private val capabilities: Int =
        Capabilities.DOWNLOAD_CMD or
            Capabilities.WANTS_COMMAND_OWNERSHIP or
            (if (worldEditAvailable) Capabilities.LOAD_CLIPBOARD else 0)

    fun register() {
        val messenger = plugin.server.messenger
        messenger.registerOutgoingPluginChannel(plugin, IpcProtocol.CHANNEL)
        messenger.registerIncomingPluginChannel(plugin, IpcProtocol.CHANNEL, this)
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.logger.info("Schematio IPC registered on channel ${IpcProtocol.CHANNEL}")
    }

    /** Client advertised our channel via minecraft:register — greet it proactively. */
    @EventHandler
    fun onRegisterChannel(event: PlayerRegisterChannelEvent) {
        if (event.channel == IpcProtocol.CHANNEL) greet(event.player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        greeted.remove(event.player.uniqueId)
        attested.remove(event.player.uniqueId)
        loadLimiter.removePlayer(event.player.uniqueId)
    }

    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (channel != IpcProtocol.CHANNEL) return
        try {
            when (IpcCodec.peekOpcode(message)) {
                IpcOpcode.HELLO_CLIENT -> {
                    val hello: HelloClient = IpcCodec.decodeHelloClient(message)
                    plugin.logger.info("Schematio mod present for ${player.name}: v${hello.modVersion} (proto ${hello.protocolVersion})")
                    greet(player) // fallback path; deduped
                    if (wantsAttestation(hello)) {
                        requestAndSendAttest(player, hello.nonce)
                    }
                }
                IpcOpcode.LOAD_REQUEST -> handleLoadRequest(player, IpcCodec.decodeLoadRequest(message))
                IpcOpcode.LOAD_CLIPBOARD -> logOpcodeNoise("legacy LOAD_CLIPBOARD (removed in protocol v2)", player)
                else -> logOpcodeNoise("unknown opcode", player)
            }
        } catch (_: IpcPayloadTooLargeException) {
            // Spec: over-cap payloads are dropped quietly — no parse, no log spam.
        } catch (e: IpcFormatException) {
            plugin.logger.warning("Malformed Schematio IPC from ${player.name}: ${e.message}")
        }
    }

    /** Spec: unknown/dead opcodes are ignored with at most one log line per minute. */
    private fun logOpcodeNoise(what: String, player: Player) {
        val now = System.currentTimeMillis()
        if (now - lastOpcodeNoiseLogMs >= 60_000L) {
            lastOpcodeNoiseLogMs = now
            plugin.logger.info("Ignoring $what on ${IpcProtocol.CHANNEL} from ${player.name}")
        }
    }

    // ---- LOAD_REQUEST (reference-pull clipboard load) ----

    /**
     * Guards in spec order (each failure -> exactly one terminal STATUS), then:
     * STATUS RESOLVING (main) -> async backend fetch (DOWNLOADING emitted just before
     * the blocking call) -> main-thread hop -> WorldEdit parse + setClipboard -> STATUS OK.
     * No schematic bytes are ever echoed back over the channel.
     */
    private fun handleLoadRequest(player: Player, msg: LoadRequest) {
        val guard = LoadRequestGuards.firstFailure(
            worldEditAvailable = worldEditAvailable,
            attested = attested.contains(player.uniqueId),
            hasPermission = player.hasPermission(LoadRequestGuards.LOAD_PERMISSION),
        )
        if (guard != null) {
            sendStatus(player, msg.requestId, guard.state, guard.detail)
            return
        }
        if (loadLimiter.tryAcquire(player.uniqueId) == null) {
            val waitSeconds = loadLimiter.getWaitTimeSeconds(player.uniqueId)
            sendStatus(player, msg.requestId, StatusState.RATE_LIMITED, "Too many clipboard loads; retry in ${waitSeconds}s")
            return
        }
        val client = plugin.clipboardResolveClient
        if (client == null) {
            sendStatus(player, msg.requestId, StatusState.UNAVAILABLE, "The plugin is not connected to schemat.io")
            return
        }
        val refType = LoadRefType.fromWire(msg.refType)
        if (refType == null) { // unreachable: decode validates; belt-and-braces
            sendStatus(player, msg.requestId, StatusState.ERROR, "Unknown reference type")
            return
        }

        sendStatus(player, msg.requestId, StatusState.RESOLVING, "")
        val playerUuid = player.uniqueId.toString()
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            // The transport reads the body in one blocking call, so "while streaming"
            // collapses to: announce DOWNLOADING just before the fetch (main-thread send).
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (player.isOnline) sendStatus(player, msg.requestId, StatusState.DOWNLOADING, "")
            })
            val outcome = runBlocking { client.resolve(playerUuid, refType, msg.refId, msg.versionId) }
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (!player.isOnline) return@Runnable
                finishLoadRequest(player, msg.requestId, outcome)
            })
        })
    }

    /** Main thread only: WorldEdit session APIs + sendPluginMessage. */
    private fun finishLoadRequest(player: Player, requestId: Int, outcome: ClipboardResolveOutcome) {
        LoadRequestGuards.statusFor(outcome)?.let { (state, detail) ->
            sendStatus(player, requestId, state, detail)
            return
        }
        val bytes = (outcome as ClipboardResolveOutcome.Bytes).bytes
        try {
            val clipboard = WorldEditUtil.byteArrayToClipboard(bytes)
            if (clipboard == null) {
                sendStatus(player, requestId, StatusState.ERROR, "Could not parse the schematic (format '${outcome.format}')")
                return
            }
            WorldEditUtil.setClipboard(player, clipboard)
            plugin.logger.info("Loaded ${bytes.size}-byte schematic into ${player.name}'s WorldEdit clipboard (format '${outcome.format}')")
            sendStatus(player, requestId, StatusState.OK, "")
            // Chat confirmation kept on purpose: the flow stays observable without the UI.
            player.sendMessage("§aSchematio: schematic loaded into your WorldEdit clipboard. Use //paste to place it.")
        } catch (e: Throwable) {
            plugin.logger.warning("Error loading clipboard for ${player.name}: ${e.javaClass.simpleName}: ${e.message}")
            sendStatus(player, requestId, StatusState.ERROR, "An error occurred loading the schematic")
        }
    }

    /** Main thread only. */
    private fun sendStatus(player: Player, requestId: Int, state: StatusState, detail: String) {
        val status = Status(IpcProtocol.VERSION, requestId, state.wire, detail)
        player.sendPluginMessage(plugin, IpcProtocol.CHANNEL, IpcCodec.encodeStatus(status))
    }

    // ---- handshake (sub-project A) ----

    private fun greet(player: Player) {
        if (!greeted.add(player.uniqueId)) return
        if (!player.listeningPluginChannels.contains(IpcProtocol.CHANNEL)) {
            greeted.remove(player.uniqueId) // not ready yet; allow a later trigger to retry
            return
        }
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
        player.sendPluginMessage(plugin, IpcProtocol.CHANNEL, IpcCodec.encodeHelloServer(hello))
    }

    /**
     * Fetches a backend attestation for [nonce] off-thread and relays it verbatim as an
     * ATTEST message on the main thread. On success the player's connection is marked
     * attested, unlocking LOAD_REQUEST. Failure sends nothing — the client settles at
     * UNVERIFIED and load stays gated off.
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
                attested.add(player.uniqueId)
            })
        })
    }

    companion object {
        /** Pure gate: only v2 hellos carrying a well-formed 16-byte nonce get attested. */
        fun wantsAttestation(hello: HelloClient): Boolean =
            hello.protocolVersion >= 2 && hello.nonce.size == 16
    }
}
```

Note what this deletes relative to the pre-task file: the `handleLoadClipboard` function, the `LoadClipboard` import, and the decode branch for opcode 3 — opcode 3 now hits `logOpcodeNoise` (dead-reserved per B2). The `org.bukkit.plugin.java.JavaPlugin` import is gone (A's constructor change).

- [ ] **Step 5.4: Run to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :bukkit:test`
Expected: BUILD SUCCESSFUL — 4 new tests plus A's `PluginIpcAttestGateTest` and the existing vcs tests green. Then run `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :bukkit:build` — shadowJar compiles against the new core APIs.

*(No commit — global constraint.)*

---

## Task 6 — fabric — ClipboardLoadTracker (pending map, timeout, § stripping)

**Files:**
- Create: `/Users/harrison/IdeaProjects/SchematioConnector/fabric/src/client/kotlin/io/schemat/connector/fabric/client/ipc/ClipboardLoadTracker.kt`
- Test: `/Users/harrison/IdeaProjects/SchematioConnector/fabric/src/test/kotlin/io/schemat/connector/fabric/client/ipc/ClipboardLoadTrackerTest.kt`

**Interfaces:**
- Consumes: `StatusState` (Task 3). No Minecraft classes — the tracker must stay headless-testable.
- Produces:

```kotlin
fun sanitizeDetail(detail: String): String   // strips every '§' + its following code char

object ClipboardLoadTracker {
    const val TIMEOUT_MS: Long = 30_000
    fun register(onStatus: (StatusState, String) -> Unit, nowMs: Long = System.currentTimeMillis()): Int  // requestId
    fun onStatus(requestId: Int, state: StatusState, detail: String, nowMs: Long = System.currentTimeMillis())
    fun tick(nowMs: Long = System.currentTimeMillis())  // expires overdue requests with synthetic ERROR
    fun pendingCount(): Int
    fun reset()
}
```

- [ ] **Step 6.1: Write the failing test**

`ClipboardLoadTrackerTest.kt`:

```kotlin
package io.schemat.connector.fabric.client.ipc

import io.schemat.connector.core.ipc.StatusState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ClipboardLoadTrackerTest {

    private val events = mutableListOf<Pair<StatusState, String>>()
    private val sink: (StatusState, String) -> Unit = { state, detail -> events += state to detail }

    @BeforeEach
    fun setUp() = ClipboardLoadTracker.reset()

    @AfterEach
    fun tearDown() = ClipboardLoadTracker.reset()

    @Test
    fun `register issues distinct requestIds`() {
        val a = ClipboardLoadTracker.register(sink)
        val b = ClipboardLoadTracker.register(sink)
        assertNotEquals(a, b)
        assertEquals(2, ClipboardLoadTracker.pendingCount())
    }

    @Test
    fun `progress statuses invoke the callback and keep the request pending`() {
        val id = ClipboardLoadTracker.register(sink, nowMs = 0)
        ClipboardLoadTracker.onStatus(id, StatusState.RESOLVING, "", nowMs = 1)
        ClipboardLoadTracker.onStatus(id, StatusState.DOWNLOADING, "", nowMs = 2)
        assertEquals(listOf(StatusState.RESOLVING to "", StatusState.DOWNLOADING to ""), events)
        assertEquals(1, ClipboardLoadTracker.pendingCount())
    }

    @Test
    fun `a terminal status completes the request`() {
        val id = ClipboardLoadTracker.register(sink)
        ClipboardLoadTracker.onStatus(id, StatusState.OK, "")
        assertEquals(0, ClipboardLoadTracker.pendingCount())
        // Late duplicates are ignored.
        ClipboardLoadTracker.onStatus(id, StatusState.ERROR, "late")
        assertEquals(listOf(StatusState.OK to ""), events)
    }

    @Test
    fun `statuses for unknown requestIds are ignored`() {
        ClipboardLoadTracker.onStatus(999, StatusState.OK, "")
        assertTrue(events.isEmpty())
    }

    @Test
    fun `an idle request times out with a synthetic ERROR`() {
        ClipboardLoadTracker.register(sink, nowMs = 0)
        ClipboardLoadTracker.tick(nowMs = ClipboardLoadTracker.TIMEOUT_MS - 1)
        assertTrue(events.isEmpty())
        ClipboardLoadTracker.tick(nowMs = ClipboardLoadTracker.TIMEOUT_MS)
        assertEquals(1, events.size)
        assertEquals(StatusState.ERROR, events.single().first)
        assertEquals(0, ClipboardLoadTracker.pendingCount())
    }

    @Test
    fun `progress statuses refresh the deadline`() {
        val id = ClipboardLoadTracker.register(sink, nowMs = 0)
        ClipboardLoadTracker.onStatus(id, StatusState.DOWNLOADING, "", nowMs = 20_000)
        ClipboardLoadTracker.tick(nowMs = 40_000) // 40s after register, 20s after progress
        assertEquals(1, ClipboardLoadTracker.pendingCount()) // still alive
        ClipboardLoadTracker.tick(nowMs = 20_000 + ClipboardLoadTracker.TIMEOUT_MS)
        assertEquals(0, ClipboardLoadTracker.pendingCount())
    }

    @Test
    fun `detail text is sanitized before reaching the callback`() {
        val id = ClipboardLoadTracker.register(sink)
        ClipboardLoadTracker.onStatus(id, StatusState.DENIED, "§4§lDenied§r by admin§")
        assertEquals("Denied by admin", events.single().second)
    }

    @Test
    fun `sanitizeDetail strips formatting codes`() {
        assertEquals("hello", sanitizeDetail("§ahello"))
        assertEquals("ab", sanitizeDetail("a§xb"))
        assertEquals("plain", sanitizeDetail("plain"))
        assertEquals("trailing", sanitizeDetail("trailing§"))
        assertEquals("", sanitizeDetail("§a§b§c"))
    }
}
```

- [ ] **Step 6.2: Run to verify compilation fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:1.21.11:test`
Expected: compilation FAILS (no `ClipboardLoadTracker` / `sanitizeDetail`).

- [ ] **Step 6.3: Implement**

`ClipboardLoadTracker.kt`:

```kotlin
package io.schemat.connector.fabric.client.ipc

import io.schemat.connector.core.ipc.StatusState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Server-supplied detail text is rendered as PLAIN text: strip every '§' together
 * with its following formatting-code character (spec invariant 5).
 */
fun sanitizeDetail(detail: String): String = detail.replace(Regex("§."), "").replace("§", "")

/**
 * Pending LOAD_REQUESTs awaiting STATUS replies. requestIds are client-generated
 * and monotonically increasing; a request completes on its first terminal status,
 * or expires with a synthetic ERROR after [TIMEOUT_MS] of silence (progress
 * statuses refresh the deadline). Clock-injectable for tests; call [tick] from the
 * client tick and [reset] on join/disconnect.
 */
object ClipboardLoadTracker {

    const val TIMEOUT_MS: Long = 30_000

    private class Pending(
        val onStatus: (StatusState, String) -> Unit,
        @Volatile var deadlineMs: Long,
    )

    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, Pending>()

    fun register(onStatus: (StatusState, String) -> Unit, nowMs: Long = System.currentTimeMillis()): Int {
        val id = nextId.getAndIncrement()
        pending[id] = Pending(onStatus, nowMs + TIMEOUT_MS)
        return id
    }

    fun onStatus(requestId: Int, state: StatusState, detail: String, nowMs: Long = System.currentTimeMillis()) {
        val entry = if (state.isTerminal) {
            pending.remove(requestId) ?: return // unknown or already completed: ignore
        } else {
            (pending[requestId] ?: return).also { it.deadlineMs = nowMs + TIMEOUT_MS }
        }
        entry.onStatus(state, sanitizeDetail(detail))
    }

    /** Expire overdue requests with a synthetic ERROR (spec: 30 s client-side timeout). */
    fun tick(nowMs: Long = System.currentTimeMillis()) {
        for ((id, entry) in pending) {
            if (nowMs >= entry.deadlineMs && pending.remove(id) != null) {
                entry.onStatus(StatusState.ERROR, "Timed out waiting for the server")
            }
        }
    }

    fun pendingCount(): Int = pending.size

    fun reset() {
        pending.clear()
    }
}
```

- [ ] **Step 6.4: Run to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:1.21.11:test`
Expected: BUILD SUCCESSFUL, 8 new tests green.

*(No commit — global constraint.)*

---

## Task 7 — fabric — ServerIpc STATUS wiring + "Load on server" UI + legacy sender deletion

**Files:**
- Modify: `/Users/harrison/IdeaProjects/SchematioConnector/fabric/src/client/kotlin/io/schemat/connector/fabric/client/ipc/ServerIpc.kt`
- Modify: `/Users/harrison/IdeaProjects/SchematioConnector/fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/panels/SchematicDetailPanel.kt`
- Modify: `/Users/harrison/IdeaProjects/SchematioConnector/fabric/src/client/kotlin/io/schemat/connector/fabric/client/SchematioClientMod.kt` (tick + reset wiring)

No new unit test file: the logic (tracker, sanitize, codec, gating states) is covered by Tasks 3/6 tests and A's `ServerSessionTrustTest`; this task is wiring, verified by compilation + the existing fabric suite + the Task 9 manual checklist. (The browse grid has no per-row action affordance — cards open the detail panel — so per the spec's intent the action lives in the detail panel, which the browse context row opens. Adding a card context menu is deliberately out of scope.)

**Interfaces:**
- Consumes: `IpcCodec.encodeLoadRequest/decodeStatus`, `LoadRequest`, `LoadRefType`, `StatusState`, `Capabilities.LOAD_CLIPBOARD`, `IpcPayloadTooLargeException` (Task 3); `ClipboardLoadTracker`/`sanitizeDetail` (Task 6); A's `ServerSession.trust`/`TrustState.VERIFIED`/`ServerSession.capabilities` and the ATTEST branch in `ServerIpc.handle()`; `Widgets.statusText(text, kind)` + `Widgets.StatusKind`; `Icons.DOWNLOAD`.
- Produces:

```kotlin
// ServerIpc.kt
fun canLoadOnServer(): Boolean  // trust == VERIFIED && capability bit
fun sendLoadRequest(refType: LoadRefType, refId: String, versionId: String = "",
    onStatus: (StatusState, String) -> Unit): Int?   // requestId, or null when not sent
// sendLoadClipboard(bytes, format) is DELETED
```

- [ ] **Step 7.1: Implement ServerIpc changes**

(a) Replace the imports `io.schemat.connector.core.ipc.LoadClipboard` with:

```kotlin
import io.schemat.connector.core.ipc.Capabilities
import io.schemat.connector.core.ipc.IpcPayloadTooLargeException
import io.schemat.connector.core.ipc.LoadRefType
import io.schemat.connector.core.ipc.LoadRequest
import io.schemat.connector.core.ipc.StatusState
```

(b) In `handle()`'s `when`, after the `IpcOpcode.ATTEST` branch (added by sub-project A), add:

```kotlin
                IpcOpcode.STATUS -> {
                    val status = IpcCodec.decodeStatus(data)
                    val state = StatusState.fromWire(status.state) ?: return
                    ClipboardLoadTracker.onStatus(status.requestId, state, status.detail)
                }
```

and change the surrounding `catch` to swallow over-cap payloads quietly (the catch block becomes):

```kotlin
        } catch (_: IpcPayloadTooLargeException) {
            // Spec: over-cap payloads are dropped quietly.
        } catch (e: IpcFormatException) {
            LOGGER.warn("Malformed Schematio IPC from server: ${e.message}")
        }
```

(c) DELETE the whole `sendLoadClipboard` function (and its doc comment), and add in its place:

```kotlin
    /**
     * Reference-pull loads are offered ONLY against a VERIFIED (attested) session
     * that advertised the LOAD_CLIPBOARD capability (spec: UI gating).
     */
    fun canLoadOnServer(): Boolean =
        ServerSession.trust == TrustState.VERIFIED &&
            Capabilities.has(ServerSession.capabilities, Capabilities.LOAD_CLIPBOARD)

    /**
     * Sends a LOAD_REQUEST carrying a schematic REFERENCE (never bytes). [onStatus]
     * receives every STATUS for this request (detail already §-sanitized) plus a
     * synthetic ERROR on the 30 s timeout. Returns the requestId, or null without
     * sending when the session is not VERIFIED+capable or the channel is not sendable.
     */
    fun sendLoadRequest(
        refType: LoadRefType,
        refId: String,
        versionId: String = "",
        onStatus: (StatusState, String) -> Unit,
    ): Int? {
        if (!canLoadOnServer()) return null
        if (!ClientPlayNetworking.canSend(SchematioPayload.TYPE)) return null
        val requestId = ClipboardLoadTracker.register(onStatus)
        val bytes = IpcCodec.encodeLoadRequest(
            LoadRequest(IpcProtocol.VERSION, requestId, refType.wire, refId, versionId),
        )
        ClientPlayNetworking.send(SchematioPayload(bytes))
        return requestId
    }
```

(`TrustState` is in the same package; no import needed.)

- [ ] **Step 7.2: Implement SchematicDetailPanel changes**

(a) Add imports:

```kotlin
import io.schemat.connector.core.ipc.LoadRefType
import io.schemat.connector.core.ipc.StatusState
import io.schemat.connector.fabric.client.ui.theme.Icons
```

(b) Next to the `statusText`/`statusKind` fields, add:

```kotlin
    /** requestId of an in-flight server clipboard load; null when idle. */
    private var pendingServerLoad: Int? = null
```

and in `show(...)`, inside the `if (replacing) { ... }` block after `statusText = null`, add:

```kotlin
            pendingServerLoad = null
```

(c) In `renderActions`, REPLACE the whole `if (ServerSession.pluginPresent) { ... }` block (the "Load to server clipboard" button) with:

```kotlin
        // Server-side WorldEdit clipboard, reference-pull (sub-project B): shown ONLY
        // when the session is VERIFIED and the server advertised LOAD_CLIPBOARD.
        if (ServerIpc.canLoadOnServer()) {
            val serverDisabled = actionsDisabled || pendingServerLoad != null
            if (serverDisabled) ImGui.beginDisabled()
            if (Widgets.button("${Icons.DOWNLOAD}  Load on server")) { d?.let { loadOnServer(it) } }
            if (serverDisabled) ImGui.endDisabled()
            ImGui.sameLine()
        }
```

(d) REPLACE the whole `loadToServerClipboard` function (including its doc comment) with:

```kotlin
    /**
     * Reference-pull server clipboard load: sends only the schematic id over IPC;
     * the SERVER pulls the bytes from the backend itself. STATUS updates stream
     * into [statusText] (toast-style, spec §Client); a terminal status (or the
     * tracker's 30 s synthetic ERROR) re-enables the button.
     */
    private fun loadOnServer(d: SchematicDetail) {
        statusText = "Requesting server-side load…"
        statusKind = Widgets.StatusKind.INFO
        val requestId = ServerIpc.sendLoadRequest(LoadRefType.SCHEMATIC, d.id) { state, detail ->
            if (state.isTerminal) pendingServerLoad = null
            val (text, kind) = serverLoadMessage(state, detail, d.name)
            statusText = text
            statusKind = kind
        }
        if (requestId != null) {
            pendingServerLoad = requestId // non-null disables the button until a terminal status/timeout
        } else {
            statusText = "No verified Schematio server connection"
            statusKind = Widgets.StatusKind.WARNING
        }
    }

    /** detail arrives pre-sanitized (ClipboardLoadTracker strips '§' codes). */
    private fun serverLoadMessage(state: StatusState, detail: String, name: String): Pair<String, Widgets.StatusKind> =
        when (state) {
            StatusState.RESOLVING -> "Server is resolving \"$name\"…" to Widgets.StatusKind.INFO
            StatusState.DOWNLOADING -> "Server is downloading \"$name\"…" to Widgets.StatusKind.INFO
            StatusState.OK -> "Loaded \"$name\" into your server-side WorldEdit clipboard — //paste to place it" to Widgets.StatusKind.SUCCESS
            StatusState.DENIED -> detail.ifBlank { "The server denied the request" } to Widgets.StatusKind.DANGER
            StatusState.NOT_FOUND -> "The server's backend could not find this schematic" to Widgets.StatusKind.DANGER
            StatusState.TOO_LARGE -> "Too large for a server clipboard load (8 MiB limit)" to Widgets.StatusKind.DANGER
            StatusState.RATE_LIMITED -> detail.ifBlank { "Rate limited — try again shortly" } to Widgets.StatusKind.WARNING
            StatusState.UNAVAILABLE -> detail.ifBlank { "The server can't load schematics right now" } to Widgets.StatusKind.DANGER
            StatusState.ERROR -> detail.ifBlank { "Unexpected error during the server-side load" } to Widgets.StatusKind.DANGER
        }
```

(The client no longer downloads bytes for this action, opens no URLs, and writes no files — spec §Client.)

- [ ] **Step 7.3: Implement SchematioClientMod wiring**

(a) In the `ClientPlayConnectionEvents.JOIN` block that calls `ServerSession.reset()`, add a tracker reset so stale requestIds can't match a new connection:

```kotlin
        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            ServerSession.reset()
            ClipboardLoadTracker.reset()
            ServerIpc.sendClientHello() // fallback trigger; server greets on register-event too
        }
```

(b) Same for the `DISCONNECT` block:

```kotlin
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            ServerSession.reset()
            ClipboardLoadTracker.reset()
        }
```

(c) In the existing `ClientTickEvents.END_CLIENT_TICK` block (currently only `Keybinds.handleInput(client)`), add the timeout sweep:

```kotlin
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            Keybinds.handleInput(client)
            ClipboardLoadTracker.tick()
        }
```

(d) Add the import `import io.schemat.connector.fabric.client.ipc.ClipboardLoadTracker` at the top of the file.

- [ ] **Step 7.4: Run to verify**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:1.21.11:test`
Expected: BUILD SUCCESSFUL — everything compiles with `sendLoadClipboard` gone (its only caller was the panel function replaced above); Task 6 + A's fabric tests stay green.

*(No commit — global constraint.)*

---

## Task 8 — :core — delete the legacy LoadClipboard (opcode 3 dead-reserve, finalized)

**Files:**
- Modify: `/Users/harrison/IdeaProjects/SchematioConnector/core/src/main/kotlin/io/schemat/connector/core/ipc/IpcMessages.kt`
- Modify: `/Users/harrison/IdeaProjects/SchematioConnector/core/src/test/kotlin/io/schemat/connector/core/ipc/IpcCodecTest.kt`

**Interfaces:**
- Consumes: nothing new. Precondition: Tasks 5 and 7 already removed the bukkit and fabric usages — verify with `grep -rn "LoadClipboard" --include="*.kt" core/src bukkit/src fabric/src | grep -v "LOAD_CLIPBOARD"` returning only comments/capability references before starting.
- Produces: `core/ipc` with **no message type carrying schematic bytes** (spec invariant 1 becomes grep-provable: no `ByteArray` field on any C2S/S2C message except `HelloClient.nonce` and `Attest.signature`); `IpcOpcode.LOAD_CLIPBOARD = 3` remains as the documented dead-reserved constant (Task 3 already rewrote its comment).

- [ ] **Step 8.1: Delete the message + codec + tests**

1. In `IpcMessages.kt`: delete the entire `LoadClipboard` class (with its doc comment), the `encodeLoadClipboard` function, and the `decodeLoadClipboard` function.
2. In `IpcCodecTest.kt`: delete every test that references `LoadClipboard`/`encodeLoadClipboard`/`decodeLoadClipboard` (the pre-existing round-trip and wrong-opcode tests for opcode 3), and remove the now-unused `LoadClipboard` import if present.
3. Add one replacement test asserting the dead-reserve at the codec level:

```kotlin
    @Test
    fun `opcode 3 is dead-reserved with no decoder or cap`() {
        assertEquals(3, IpcOpcode.LOAD_CLIPBOARD)
        assertEquals(null, IpcCaps.forOpcode(IpcOpcode.LOAD_CLIPBOARD))
    }
```

- [ ] **Step 8.2: Run all three suites**

Run:
```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:test :bukkit:test :fabric:1.21.11:test
```
Expected: BUILD SUCCESSFUL — nothing outside core references `LoadClipboard` anymore; if a compile error surfaces here, a Task 5/7 deletion was missed (fix there, not by re-adding the class).

*(No commit — global constraint.)*

---

## Task 9 — Integration checkpoint: full suites + manual run-paper checklist

**Files:** none created/modified (verification only).

- [ ] **Step 9.1: Full automated sweep**

```bash
cd /Users/harrison/IdeaProjects/SchematioConnector
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:test :bukkit:test :fabric:1.21.11:test
# Expected: BUILD SUCCESSFUL, zero failures.

cd /Users/harrison/Documents/code/schemati
php artisan test --filter=PluginClipboardResolve   # 19 tests green
php artisan test --filter=PluginVersionApi         # regression: plugin group untouched
php artisan test --filter=QuickShare               # regression: share systems untouched
# Expected: all green. Optionally the full suite (php artisan test): the in-flight
# VCS work was green at 1486 tests; this plan only ADDS the clipboard tests.
```

- [ ] **Step 9.2: Manual run-paper checklist** (record results in task notes; do not commit)

1. Backend up with attestation configured (sub-project A checklist item 1). Bukkit: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :bukkit:runServer`, community token set (`/schematio settoken <jwt>` + `/schematio reload`). Client: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:1.21.11:runClient`, join `localhost`, confirm the client log shows `Server attestation VERIFIED`.
2. **Public load OK:** open a public schematic's detail panel → the "Load on server" button (download icon) is visible → click → status line walks RESOLVING → DOWNLOADING → OK; chat shows the green clipboard confirmation; `//paste` places the build. Laravel log shows one `POST /api/v1/plugin/clipboard/resolve` → 200 and a `clipboard_resolve` audit row with `outcome: ok`.
3. **Private schematic of another user → DENIED:** panel shows the DANGER status with the server's detail text (no `§` artifacts); backend audit row `outcome: access_denied`.
4. **Spam clicking → RATE_LIMITED:** click ~6× fast on a public schematic — the 6th shows the WARNING rate-limit status with a retry-in-seconds detail (plugin-side bucket; the backend 10/min limit is not consumed by guarded-off requests).
5. **WorldEdit removed → UNAVAILABLE:** stop the server, remove the WorldEdit jar from the run-paper plugins directory, restart, rejoin. With WorldEdit absent the server never advertises LOAD_CLIPBOARD, so the client hides the action entirely — that is the UNAVAILABLE surface at handshake time (the `WORLDEDIT_MISSING → STATUS UNAVAILABLE` guard covers the race where WorldEdit dies after the handshake, and is unit-tested in Task 5). Restore WorldEdit afterwards.
6. **Unattested session gate:** blank `SCHEMATIO_ATTEST_SEED` in schemati `.env` + `php artisan config:clear`, rejoin. Client settles UNVERIFIED → the button is hidden; nothing hits `/clipboard/resolve` (spec invariant 2, observable in the Laravel log). Restore the seed.
7. **Legacy opcode ignored:** (spot check, optional) a v1 client jar sending opcode 3 produces at most one `Ignoring legacy LOAD_CLIPBOARD` log line per minute and no error.

**Done-when:** both suites green, checklist items 1–4 + 6 observed (5/7 as feasible), both trees left dirty (NO commits) for Harrison's review.

---

## Self-review notes (spec coverage)

- **Spec §Protocol:** LOAD_REQUEST/STATUS fields, string caps, per-opcode byte caps, unknown-opcode rate-limited ignore, over-cap quiet drop, strict UTF-8 — Task 3 (opcodes renumbered 5/6 around A's ATTEST=4; documented in "Resolved ambiguities"). STATUS kept generic (plain `requestId` map) for sub-project C.
- **Spec §Backend:** resolve endpoint under the community-JWT plugin group, web-download policy parity (`Gate::forUser` → `SchematicPolicy::view`), share validity incl. expiry/revocation (`QuickShareService::validateAccess`), default-branch-head version resolution (`Schematic::headVersion()`), octet-stream + `X-Schematio-Format` + mandatory `Content-Length`, 8 MiB 413, 10/min per (JWT, player) 429, audit row per resolve — Tasks 1–2.
- **Spec §Plugin:** guard order (WE → attested → permission → 5/min bucket) with early STATUS per guard, RESOLVING → async fetch → DOWNLOADING → main-thread WE hop → OK + chat line, one terminal STATUS per failure path, `handleLoadClipboard` deleted, opcode 3 ignore+log — Task 5 (+ Task 4 for the 30 s timeout, mandatory Content-Length, byte-counted 8 MiB stream via `ResponseTooLargeException`).
- **Spec §Client:** pending-request map + 30 s synthetic-ERROR timeout, `§` stripping, "Load on server" (`Icons.DOWNLOAD`) gated on VERIFIED + capability, toast-style `Widgets.statusText` 1:1 state mapping, no URLs/files/bytes client-side — Tasks 6–7. Browse-row entry point resolved to the detail panel (cards have no row-action affordance; noted in Task 7).
- **Security invariants:** (1) no byte-array fields on new opcodes + legacy deletion, grep-provable after Task 8; (2) unattested → DENIED before any backend call — Task 5 guard order test + checklist 6; (3) policy-parity Pest matrix (public OK / private non-member DENIED / revoked share DENIED / oversize 413) — Tasks 1–2; (4) both rate limits tested (Pest 429, bucket unit test); (5) `§`-strip unit test — Task 6.
- **Type consistency check:** `StatusState`/`LoadRefType`/`IpcCaps` (T3) are consumed with identical signatures in T4 (`ClipboardResolveClient`), T5 (`LoadRequestGuards`, `PluginIpcService`), T6 (`ClipboardLoadTracker`), T7 (`ServerIpc`, panel). `ClipboardResolveOutcome` object-vs-class arms match between T4's sealed class and T5's `when`. `MAX_SCHEMATIC_BYTES` (8388608) equals the backend `MAX_BYTES`.

