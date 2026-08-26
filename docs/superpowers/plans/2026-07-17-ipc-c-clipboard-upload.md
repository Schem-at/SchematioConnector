# IPC Sub-project C — Server Clipboard → Draft Upload + In-Game Labelling

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Date:** 2026-07-17
**Spec:** `docs/superpowers/specs/2026-07-17-ipc-clipboard-upload-design.md`
**Depends on:** Sub-project A (`docs/superpowers/plans/2026-07-17-ipc-a-handshake-v2.md`) and Sub-project B (`docs/superpowers/plans/2026-07-17-ipc-b-clipboard-load.md`). Everything those plans build — `TrustState`/`ServerSession` v2, the `attested` set + `requestAndSendAttest` in `PluginIpcService`, `StatusState`/`Status`/`IpcCaps`/opcodes 5–6, `ClipboardResolveClient` + `clipboardTransport`, `ClipboardLoadTracker`, the `/api/v1/plugin` route-group and limiter patterns — is treated as EXISTING and is NOT re-planned here.

**Goal:** A player (via the client mod's toolbar or `/schematio upload` in chat) asks the server to push its copy of their WorldEdit clipboard to schemat.io as a **draft schematic** owned by the player's account; with the mod, the wizard opens in "complete draft" mode and the USER's own auth publishes it; without the mod, a clickable chat link finishes it on the web.

**Architecture:** One new plugin-JWT backend endpoint (`POST /api/v1/plugin/clipboard/drafts`) creating size/quota/rate-capped, auto-expiring `Schematic` rows flagged by a new nullable `draft_expires_at` column; two new opcodes (7/8) in `core/ipc` with hard decode caps; a shared bukkit `ClipboardUploadService` (main-thread clipboard snapshot → async serialize + POST) driving both the IPC handler and the rewritten `/schematio upload` subcommand; a fabric `ClipboardUploadTracker` + ownership-checked "complete draft" mode in `UploadWizardPanel`.

**Tech Stack:** schemati: Laravel 12 + Pest (SQLite in-memory), Spatie MediaLibrary. SchematioConnector: Kotlin 2.4, JDK 21, Gradle + Stonecutter, JUnit 5, gson, Apache HttpClient (existing `ApiTransport`/`HttpTransport` — multipart already supported), kotlinx-coroutines.

## Global Constraints

- **Do NOT git commit in either repo (user preference — skip every commit step).** Leave both trees dirty for review. Stay on branch `feature/ingame-diff-viewer` in SchematioConnector.
- **Touch only the files each task names.** Both trees are dirty with unrelated in-flight work (schemati has uncommitted VCS-hardening work; the connector has the diff-viewer branch) — never reformat, revert, or "fix" anything a task does not name.
- **Test commands:**
  - SchematioConnector (run from `/Users/harrison/IdeaProjects/SchematioConnector`, ALWAYS prefix with JAVA_HOME):
    - `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:test`
    - `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :bukkit:test`
    - `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:1.21.11:test`
  - schemati (run from `/Users/harrison/Documents/code/schemati`):
    - `php artisan test --filter=SchematicDraftVisibility`
    - `php artisan test --filter=PluginClipboardDraft`
    - `php artisan test --filter=SchematicDraftPublish`
    - `php artisan test --filter=SchematicDraftExpiry`
    - `php artisan test --filter=SchematicDraftUploader`
- schemati tests live under `tests/Feature/Api/` (the Livewire one under `tests/Feature/Livewire/`, matching `VersionHistoryTest`); Unit tests have no app/DB. Each file MUST declare `uses(RefreshDatabase::class);` itself (not global). Pest helper function names are global — prefix them (`cdraft…`) to avoid collisions.
- **User tokens must NEVER appear in bukkit/plugin code paths** (spec invariant 1). The plugin's draft upload uses ONLY the community token (`SchematioConnector.communityToken`). No bukkit file may reference user auth, `ensureAuthenticated`, or receive/forward a user JWT. Task 12 grep-proves this.
- **Hard payload cap everywhere: 8 MiB = 8388608 bytes** (backend 413, core client pre-check, bukkit post-serialization re-check).
- Every new opcode payload has a max size enforced in decode (`IpcCaps`); over-cap → `IpcPayloadTooLargeException` BEFORE parsing; handlers drop quietly (B's convention).
- TDD per task: write the failing test, run it and see it fail, implement, run it and see it pass.

---

## Resolved ambiguities (spec ↔ codebases ↔ sub-projects A/B)

1. **Route prefix.** The spec's `/api/plugin/clipboard/drafts` resolves to **`/api/v1/plugin/clipboard/drafts`** — the plugin group lives under the `v1` prefix (same resolution as A's attest and B's resolve endpoints).
2. **Draft state field.** `Schematic` has NO status/state enum — visibility is the single `is_public` boolean. Per the spec's fallback ("if none, add nullable `draft_expires_at`, non-null = draft"), C adds **`draft_expires_at` (nullable timestamp)**; non-null = draft.
3. **Unlinked player → 400.** The C spec explicitly says 400 (unlike B's resolve, which used 403 `player_not_linked`). C returns **400 `player_not_linked`**; the plugin maps it to `DENIED` + the account-link chat hint either way.
4. **Quota 409 → STATUS.** No STATUS state matches "quota" exactly; `RATE_LIMITED` would mislead ("retry shortly" — wrong, the user must publish/delete drafts). 409 maps to **`DENIED`** with detail "Draft limit reached (10) — publish or delete drafts on schemat.io first".
5. **"ATTESTED" session.** As in B: client-side = `TrustState.VERIFIED`; server-side = the `PluginIpcService.attested` set (ATTEST relayed this connection). The IPC path requires it; the standalone `/schematio upload` chat path has no IPC session and therefore no attestation requirement (permission + rate limit still apply).
6. **Web link shape.** The spec's `<backend>/upload/drafts/{id}` maps to the codebase's actual convention: **`route('schematics.upload.details', ['shortId' => $schematic->short_id])`** = `/schematics/upload/{shortId}` — the existing "finish an in-game upload in the browser" page (`SchematicUploader`), which Task 5 teaches to resolve persisted drafts (today it only reads the temp-cache short links). The backend returns the full `web_url` in the 201 response so neither plugin nor client ever constructs URLs.
7. **`/schematio upload` replaces the legacy direct upload.** The existing `UploadSubcommand` (community-token POST to `/schematics/upload` via the old `HttpUtil`, cache-shortlink flow) is REWRITTEN to drive the new draft service — the spec says the chat command "drives the same plugin service". Its permission changes from `schematio.upload` (default op) to the spec's `schematio.clipboard.upload` (default true). The old `schematio.upload` node stays in plugin.yml (other code may reference it) but no longer gates this subcommand.
8. **STATUS requestId collision.** STATUS (opcode 6) is shared between B's load flow and C's upload flow, and `ServerIpc.handle()` dispatches each STATUS to BOTH trackers. To keep the id spaces disjoint without touching B's `ClipboardLoadTracker` internals (ids from 1 upward), `ClipboardUploadTracker` issues ids from **1_000_000** upward.
9. **Publish trigger.** No new publish endpoint: any successful owner-authenticated `PUT /api/v1/schematics/{id}` **that includes `name`** clears `draft_expires_at` (the wizard's save always sends name; a bare `{is_public: …}` tweak does not accidentally publish an unlabelled draft). The web path (Task 5) clears it explicitly in its transaction.
10. **Wizard-published drafts have no preview image.** `updateSchematic` (PUT) cannot carry a PNG, so the confirm step's preview section is hidden in complete-draft mode and the published schematic has no `preview_image` media (the site already tolerates schematics without previews). The WEB completion path keeps the existing required-preview rule. Recorded as a known limitation in Task 12's checklist.
11. **Duplicate hashes allowed for drafts.** `SchematicService::createSchematic`'s duplicate-hash check is deliberately NOT applied — re-capturing the same clipboard must not 409; quota + expiry bound abuse.
12. **Interim STATUS.** The spec demands no progress states for uploads (unlike B's RESOLVING/DOWNLOADING). The plugin sends exactly one reply per request: `DRAFT_CREATED` or one terminal `STATUS`. The client shows its own "Uploading…" notice and relies on the 30 s tracker timeout.

---

## Cross-Repo Contract (single source of truth — referenced verbatim by both repos' tasks)

### C1. HTTP: `POST /api/v1/plugin/clipboard/drafts`

Community JWT required (`ensure_valid_jwt` group); rate limit `throttle:clipboard-draft` = **4/min per (bearer token, player_uuid)**.

Request (multipart/form-data):

| field | value |
|---|---|
| `player_uuid` | minecraft uuid, dashed or undashed (≤64 chars) |
| `file` | binary schematic bytes (the plugin always sends Sponge `.schem`), field name `file`, filename `clipboard.schem`, content type `application/octet-stream`, ≤ **8388608** bytes |

Success **201** (JSON):

```json
{
  "draft_id":  "<schematic uuid>",
  "short_id":  "<schematic short_id>",
  "web_url":   "<absolute URL to /schematics/upload/{short_id}>",
  "expires_at": "<ISO-8601, now + 48h>"
}
```

Created state: `is_public = false`, `posted_by` = player, player attached as author, bytes in the `schematic` media collection, `draft_expires_at = now + 48h`, name `"Clipboard upload YYYY-MM-DD HH:MM"`, one `CommunityTokenAudit` row (`clipboard_draft`, metadata: community_id, player_uuid, size, draft_id).

Errors (JSON `{"error": <code>, "message": <human text>}`):

| HTTP | error code | when | plugin STATUS |
|---|---|---|---|
| 400 | `player_not_linked` | no `User` with `uuid == player_uuid` | DENIED (+ account-link chat hint) |
| 403 | `community_token_required` | non-community JWT | DENIED |
| 409 | `draft_quota_exceeded` | ≥ **10** unexpired drafts for this user | DENIED |
| 413 | `too_large` | file > **8388608** bytes | TOO_LARGE |
| 422 | (Laravel validation) | missing/malformed fields | ERROR |
| 429 | (throttle) | over 4/min | RATE_LIMITED |

Plugin-side mapping (Task 7): `201 → Created(draftId, webUrl, expiresAt)`, `400 player_not_linked → NotLinked`, `401/403 → Denied`, `409 → QuotaExceeded`, `413 → TooLarge`, `429 → RateLimited`, `5xx / timeout / transport failure → Unavailable`, anything else → `Error`. Core client pre-checks the 8 MiB cap before ever calling the transport.

**Publishing:** owner-authenticated `PUT /api/v1/schematics/{id}` including `name` clears `draft_expires_at` (ambiguity 9). Drafts are invisible in every listing/search/feed; direct `GET /api/v1/schematics/{id}` by a non-owner is 404 (existing `canUserAccessSchematic`: drafts are `is_public=false`).

**Expiry:** `schematics:purge-expired-drafts` (scheduled daily) force-deletes drafts past `draft_expires_at`, which purges media files via the model's deleting hook.

### C2. Wire format (core/ipc, protocol VERSION = 2)

- `UPLOAD_CLIPBOARD` (**opcode 7**, C→S): `byte 7, varint protocolVersion, varint requestId`. No other fields — the subject is always "the requesting player's current WE clipboard". Max encoded size **64 bytes**. Accepted ONLY from attested connections (server-side `attested` set) when the `UPLOAD` capability was advertised.
- `DRAFT_CREATED` (**opcode 8**, S→C): `byte 8, varint protocolVersion, varint requestId, string draftId (1..64 chars)`. Max encoded size **256 bytes**. The client treats `draftId` as an opaque id for its OWN backend API — nothing else from the server is trusted.
- Failures reuse `STATUS` (opcode 6, states from B): `TOO_LARGE` = clipboard over cap, `DENIED` = permission/attestation/unlinked/quota, `UNAVAILABLE` = no WE clipboard on the guard path is EMPTY_CLIPBOARD→UNAVAILABLE / backend down, `RATE_LIMITED`, `ERROR`.
- `IpcCaps`: `UPLOAD_CLIPBOARD = 64`, `DRAFT_CREATED = 256` (`forOpcode` extended). Over cap → `IpcPayloadTooLargeException` before parsing.
- **`Capabilities.UPLOAD = 1 shl 1` is un-reserved**: advertised iff WorldEdit is present AND the backend upload client is configured. The client offers "Upload clipboard" ONLY when `ServerSession.trust == TrustState.VERIFIED` **and** the `UPLOAD` bit is set.
- Client timeout: 30 s per pending request → synthetic `ERROR` (tracker tick).

---
## Task 1 — schemati: `draft_expires_at` column + central listing exclusion

**Files:**
- Create: `/Users/harrison/Documents/code/schemati/database/migrations/2026_07_17_100000_add_draft_expires_at_to_schematics_table.php`
- Modify: `/Users/harrison/Documents/code/schemati/app/Models/Schematic.php` (cast + `isDraft()` + `scopeWithoutDrafts`)
- Modify: `/Users/harrison/Documents/code/schemati/app/Livewire/Schematics/Grid.php` (one scope call in `loadSchematics()`)
- Modify: `/Users/harrison/Documents/code/schemati/app/Services/SchematicService.php` (one scope call in `applyVisibilityFilter()`)
- Modify: `/Users/harrison/Documents/code/schemati/routes/api.php` (the two `schematics/search` closures — article-editor search)
- Test: `/Users/harrison/Documents/code/schemati/tests/Feature/Api/SchematicDraftVisibilityTest.php`

**Interfaces:**
- Consumes: `Schematic` model (fillable `name/description/is_public/format/meta_data/hash/is_versioned/default_branch_id/posted_by`; UUID pk from `creating` hook; `authors()` belongsToMany Player; Spatie media collection `'schematic'`; SoftDeletes); `Grid::loadSchematics()` inline visibility query (`app/Livewire/Schematics/Grid.php` ~line 279); `SchematicService::applyVisibilityFilter(Builder, ?string, bool)`; the optional-auth public index `GET /api/v1/schematics` (`SchematicController::index`).
- Produces: `schematics.draft_expires_at` nullable timestamp (indexed); `Schematic::isDraft(): bool`; `Schematic::scopeWithoutDrafts(Builder $query): Builder` (`whereNull('draft_expires_at')`) applied to every listing/search path. Drafts are invisible in ALL listings — including the owner's own (completion happens only via the draft link or the mod wizard).

- [ ] **Step 1.1: Write the failing test**

`tests/Feature/Api/SchematicDraftVisibilityTest.php`:

```php
<?php

use App\Models\Player;
use App\Models\Schematic;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\Http;
use Illuminate\Support\Str;
use App\Helpers\JWT;

uses(RefreshDatabase::class);

beforeEach(function () {
    // Stop the Player boot hook from hitting the Mojang API.
    Http::fake(['*' => Http::response(['name' => 'TestPlayer', 'id' => 'some-uuid'], 200)]);
    app(\Spatie\Permission\PermissionRegistrar::class)->forgetCachedPermissions();

    $this->player = Player::create([
        'id' => Str::uuid()->toString(),
        'last_seen_name' => 'DraftOwner',
    ]);
    $this->user = User::factory()->create(['uuid' => $this->player->id]);
});

/** A plugin-created draft ($public forces is_public for belt-and-braces tests). */
function cdraftVisMakeDraft(Player $owner, bool $public = false): Schematic
{
    $schematic = Schematic::factory()->create([
        'name' => 'Secret draft build',
        'is_public' => $public,
        'posted_by' => $owner->id,
    ]);
    $schematic->authors()->sync([$owner->id]);
    $schematic->draft_expires_at = now()->addHours(48);
    $schematic->save();

    return $schematic->fresh();
}

it('never lists a draft in the public browse endpoint, even with is_public forced', function () {
    $draft = cdraftVisMakeDraft($this->player, public: true); // belt and braces
    $published = Schematic::factory()->create(['name' => 'Public build', 'is_public' => true]);

    $response = $this->getJson('/api/v1/schematics');

    $response->assertOk();
    $ids = collect($response->json('data'))->pluck('id');
    expect($ids)->toContain($published->id);
    expect($ids)->not->toContain($draft->id);
});

it('hides drafts from the owner\'s own authenticated listing too', function () {
    $draft = cdraftVisMakeDraft($this->player);

    $response = $this->withToken(JWT::getTestToken([], $this->player->id))
        ->getJson('/api/v1/schematics?visibility=all');

    $response->assertOk();
    expect(collect($response->json('data'))->pluck('id'))->not->toContain($draft->id);
});

it('hides drafts from the article-editor search', function () {
    cdraftVisMakeDraft($this->player, public: true);

    $response = $this->getJson('/api/schematics/search?q=Secret');

    $response->assertOk();
    expect($response->json('data'))->toBe([]);
});

it('hides drafts from the schematics Grid', function () {
    $draft = cdraftVisMakeDraft($this->player);

    Livewire\Livewire::actingAs($this->user)
        ->test(\App\Livewire\Schematics\Grid::class)
        ->assertDontSee('Secret draft build');
});

it('exposes isDraft() on the model', function () {
    $draft = cdraftVisMakeDraft($this->player);
    $published = Schematic::factory()->create(['is_public' => true]);

    expect($draft->isDraft())->toBeTrue();
    expect($published->isDraft())->toBeFalse();
    expect(Schematic::query()->withoutDrafts()->pluck('id'))->not->toContain($draft->id);
});
```

Note: if `GET /api/schematics/search` 404s (the article-editor search closure may be registered under the `v1` prefix in this tree), change that ONE test's URL to `/api/v1/schematics/search?q=Secret` — check with `php artisan route:list | grep schematics/search`. Both closures get the scope either way.

- [ ] **Step 1.2: Run the test to verify it fails**

Run: `php artisan test --filter=SchematicDraftVisibility`
Expected: FAIL — SQL error `no such column: draft_expires_at` (and `withoutDrafts` undefined).

- [ ] **Step 1.3: Implement**

`database/migrations/2026_07_17_100000_add_draft_expires_at_to_schematics_table.php`:

```php
<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        if (! Schema::hasColumn('schematics', 'draft_expires_at')) {
            Schema::table('schematics', function (Blueprint $table) {
                // Non-null = plugin-created draft awaiting user publication
                // (IPC sub-project C). Cleared on publish; expired rows are purged
                // (files included) by schematics:purge-expired-drafts.
                $table->timestamp('draft_expires_at')->nullable();
                $table->index('draft_expires_at');
            });
        }
    }

    public function down(): void
    {
        if (Schema::hasColumn('schematics', 'draft_expires_at')) {
            Schema::table('schematics', function (Blueprint $table) {
                $table->dropIndex(['draft_expires_at']);
                $table->dropColumn('draft_expires_at');
            });
        }
    }
};
```

`app/Models/Schematic.php` — three edits:

(a) In `$casts`, after `'is_versioned' => 'boolean',` add:

```php
        'draft_expires_at' => 'datetime',
```

(`draft_expires_at` is deliberately NOT fillable — publish/expiry state must never be mass-assignable from request data.)

(b) Next to the existing scopes (`scopeForListing` etc.), add (ensure `use Illuminate\Database\Eloquent\Builder;` is imported — it already is for the existing scopes):

```php
    /** True for a plugin-created draft awaiting user publication (IPC sub-project C). */
    public function isDraft(): bool
    {
        return $this->draft_expires_at !== null;
    }

    /**
     * Excludes plugin-created drafts. Drafts are invisible in EVERY listing/search/feed —
     * even to their owner (completion happens via the draft link or the mod wizard) —
     * until published, which clears draft_expires_at.
     */
    public function scopeWithoutDrafts(Builder $query): Builder
    {
        return $query->whereNull('draft_expires_at');
    }
```

`app/Livewire/Schematics/Grid.php` — in `loadSchematics()`, change the query head:

```php
        $query = Schematic::query()->withoutDrafts()->where(function ($q) use ($player) {
```

(the rest of the closure is unchanged).

`app/Services/SchematicService.php` — first line of `applyVisibilityFilter()`:

```php
    private function applyVisibilityFilter(Builder $query, ?string $userUuid, bool $isAuthenticated): void
    {
        $query->withoutDrafts();
        // ... existing body unchanged ...
```

`routes/api.php` — in BOTH `Route::get('schematics/search', …)` closures (the article-editor searches; find them with `grep -n "schematics/search" routes/api.php`), change the query head:

```php
    $schematics = \App\Models\Schematic::query()
        ->withoutDrafts()
        ->where('is_public', true)
```

- [ ] **Step 1.4: Run the test to verify it passes**

Run: `php artisan test --filter=SchematicDraftVisibility`
Expected: PASS (5 tests).

*(No commit — global constraint.)*

---

## Task 2 — schemati: `POST /api/v1/plugin/clipboard/drafts` (+ limiter + audit)

**Files:**
- Create: `/Users/harrison/Documents/code/schemati/app/Http/Controllers/Api/PluginClipboardDraftController.php`
- Modify: `/Users/harrison/Documents/code/schemati/routes/api.php` (one route inside the existing `Route::prefix('plugin')->middleware('ensure_valid_jwt')->name('api.plugin.')` group)
- Modify: `/Users/harrison/Documents/code/schemati/app/Providers/RouteServiceProvider.php` (register the `clipboard-draft` limiter)
- Modify: `/Users/harrison/Documents/code/schemati/app/Models/CommunityTokenAudit.php` (add `ACTION_CLIPBOARD_DRAFT` constant + display-name arm)
- Test: `/Users/harrison/Documents/code/schemati/tests/Feature/Api/PluginClipboardDraftTest.php`

**Interfaces:**
- Consumes: contract C1; Task 1's `draft_expires_at`; `EnsureValidJWT` merged attributes (`is_community_token`, `community_id`, `token_payload['jti']`); `SchematicFileService::processSchematicFile(UploadedFile $file, ?string $format = null): array{contents,hash,metadata,format}`; `User.uuid === Player.id`; `CommunityToken::findByJti(string $jti): ?CommunityToken`; `CommunityTokenAudit::log(string $action, ?string $tokenId = null, ?string $actorId = null, ?array $metadata = null, ?string $ipAddress = null, ?string $userAgent = null): self`; web route name `schematics.upload.details` (`/schematics/upload/{shortId}`).
- Produces: route `POST /api/v1/plugin/clipboard/drafts` named `api.plugin.clipboard.drafts.store` behaving per contract C1; `PluginClipboardDraftController::MAX_BYTES = 8388608`, `::MAX_DRAFTS_PER_USER = 10`, `::TTL_HOURS = 48`; audit action `CommunityTokenAudit::ACTION_CLIPBOARD_DRAFT = 'clipboard_draft'`.

- [ ] **Step 2.1: Write the failing test**

`tests/Feature/Api/PluginClipboardDraftTest.php` (setup mirrors the proven `tests/Feature/Api/PluginVersionApiTest.php` / `PluginClipboardResolveTest.php` pattern):

```php
<?php

use App\Helpers\JWT;
use App\Models\Community;
use App\Models\CommunityTokenAudit;
use App\Models\Player;
use App\Models\Schematic;
use App\Models\Tag;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Http\UploadedFile;
use Illuminate\Support\Facades\Http;
use Illuminate\Support\Str;

uses(RefreshDatabase::class);

const CDRAFT_URL = '/api/v1/plugin/clipboard/drafts';

beforeEach(function () {
    // Stop the Player boot hook from hitting the Mojang API.
    Http::fake(['*' => Http::response(['name' => 'TestPlayer', 'id' => 'some-uuid'], 200)]);
    app(\Spatie\Permission\PermissionRegistrar::class)->forgetCachedPermissions();

    cdraftSetupTagHierarchy();

    $this->creator = Player::create([
        'id' => Str::uuid()->toString(),
        'last_seen_name' => 'DraftCreator',
    ]);

    $this->community = Community::create([
        'name' => 'Draft Community',
        'slug' => 'draft-community',
        'description' => 'Community for clipboard draft tests',
        'is_public' => true,
        'is_active' => true,
        'created_by' => $this->creator->id,
    ]);
    $this->community->addMember($this->creator, Community::ROLE_ADMIN);

    $this->token = JWT::generateCommunityToken($this->community, $this->creator, 'Draft Token')['token'];

    // The requesting in-game player, WITH a linked site account (User.uuid === Player.id).
    $this->player = Player::create([
        'id' => Str::uuid()->toString(),
        'last_seen_name' => 'DraftPlayer',
    ]);
    $this->user = User::factory()->create(['uuid' => $this->player->id]);
});

/** Root + 'community' parent tags, required by Community::getOrCreateTag(). */
function cdraftSetupTagHierarchy(): void
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

/** Multipart POST helper. */
function cdraftPost($test, string $token, string $playerUuid, ?UploadedFile $file): Illuminate\Testing\TestResponse
{
    $payload = ['player_uuid' => $playerUuid];
    if ($file !== null) {
        $payload['file'] = $file;
    }

    return $test->withToken($token)->post(CDRAFT_URL, $payload, ['Accept' => 'application/json']);
}

it('creates a draft for a linked player (201)', function () {
    $bytes = random_bytes(64);
    $file = UploadedFile::fake()->createWithContent('clipboard.schem', $bytes);

    $response = cdraftPost($this, $this->token, $this->player->id, $file);

    $response->assertCreated()
        ->assertJsonStructure(['draft_id', 'short_id', 'web_url', 'expires_at']);

    $schematic = Schematic::find($response->json('draft_id'));
    expect($schematic)->not->toBeNull();
    expect($schematic->is_public)->toBeFalse();
    expect($schematic->posted_by)->toBe($this->player->id);
    expect($schematic->draft_expires_at)->not->toBeNull();
    expect(now()->diffInHours($schematic->draft_expires_at, false))->toBeGreaterThan(47);
    expect($schematic->authors()->pluck('players.id')->all())->toBe([$this->player->id]);
    expect($schematic->getFirstMedia('schematic'))->not->toBeNull();

    expect($response->json('web_url'))
        ->toBe(route('schematics.upload.details', ['shortId' => $schematic->short_id]));

    $audit = CommunityTokenAudit::where('action', CommunityTokenAudit::ACTION_CLIPBOARD_DRAFT)->first();
    expect($audit)->not->toBeNull();
    expect($audit->metadata['player_uuid'])->toBe($this->player->id);
    expect($audit->metadata['draft_id'])->toBe($schematic->id);
    expect($audit->metadata['size'])->toBe(64);
});

it('accepts an undashed player uuid', function () {
    $file = UploadedFile::fake()->createWithContent('clipboard.schem', 'bytes');

    $response = cdraftPost($this, $this->token, str_replace('-', '', $this->player->id), $file);

    $response->assertCreated();
    expect(Schematic::find($response->json('draft_id'))->posted_by)->toBe($this->player->id);
});

it('rejects an unlinked player with 400 player_not_linked', function () {
    $unlinked = Player::create(['id' => Str::uuid()->toString(), 'last_seen_name' => 'NoAccount']);
    $file = UploadedFile::fake()->createWithContent('clipboard.schem', 'bytes');

    $response = cdraftPost($this, $this->token, $unlinked->id, $file);

    $response->assertStatus(400)->assertJsonPath('error', 'player_not_linked');
    expect(Schematic::count())->toBe(0);
});

it('rejects a non-community JWT with 403 community_token_required', function () {
    $file = UploadedFile::fake()->createWithContent('clipboard.schem', 'bytes');

    $response = cdraftPost($this, JWT::getTestToken(), $this->player->id, $file);

    $response->assertStatus(403)->assertJsonPath('error', 'community_token_required');
});

it('rejects files over 8 MiB with 413 too_large', function () {
    // 8193 KB = 8389632 bytes > 8388608.
    $file = UploadedFile::fake()->create('clipboard.schem', 8193);

    $response = cdraftPost($this, $this->token, $this->player->id, $file);

    $response->assertStatus(413)->assertJsonPath('error', 'too_large');
    expect(Schematic::count())->toBe(0);
});

it('rejects the 11th unexpired draft with 409 draft_quota_exceeded', function () {
    foreach (range(1, 10) as $i) {
        $draft = Schematic::factory()->create([
            'is_public' => false,
            'posted_by' => $this->player->id,
        ]);
        $draft->draft_expires_at = now()->addHours(48);
        $draft->save();
    }
    $file = UploadedFile::fake()->createWithContent('clipboard.schem', 'bytes');

    $response = cdraftPost($this, $this->token, $this->player->id, $file);

    $response->assertStatus(409)->assertJsonPath('error', 'draft_quota_exceeded');
});

it('does not count EXPIRED drafts against the quota', function () {
    foreach (range(1, 10) as $i) {
        $draft = Schematic::factory()->create([
            'is_public' => false,
            'posted_by' => $this->player->id,
        ]);
        $draft->draft_expires_at = now()->subHour();
        $draft->save();
    }
    $file = UploadedFile::fake()->createWithContent('clipboard.schem', 'bytes');

    cdraftPost($this, $this->token, $this->player->id, $file)->assertCreated();
});

it('rate limits at 4 per minute per (token, player)', function () {
    foreach (range(1, 4) as $i) {
        cdraftPost(
            $this,
            $this->token,
            $this->player->id,
            UploadedFile::fake()->createWithContent("clipboard{$i}.schem", "bytes{$i}"),
        )->assertCreated();
    }

    cdraftPost(
        $this,
        $this->token,
        $this->player->id,
        UploadedFile::fake()->createWithContent('clipboard5.schem', 'bytes5'),
    )->assertStatus(429);
});

it('validates missing file with 422', function () {
    cdraftPost($this, $this->token, $this->player->id, null)->assertStatus(422);
});
```

- [ ] **Step 2.2: Run the test to verify it fails**

Run: `php artisan test --filter=PluginClipboardDraft`
Expected: FAIL — 404 on every request (route not defined) and "undefined constant `ACTION_CLIPBOARD_DRAFT`".

- [ ] **Step 2.3: Implement**

`app/Models/CommunityTokenAudit.php` — add below the existing action constants (after `ACTION_CLIPBOARD_RESOLVE` added by sub-project B, or after `ACTION_ATTEST_ISSUED` if B is absent):

```php
    const ACTION_CLIPBOARD_DRAFT = 'clipboard_draft';
```

and one arm in `getActionNameAttribute()`'s `match`, above the `default` arm:

```php
            self::ACTION_CLIPBOARD_DRAFT => 'Clipboard Draft Created',
```

`app/Providers/RouteServiceProvider.php` — in `boot()`, immediately after the `RateLimiter::for('clipboard-resolve', ...)` block added by sub-project B (or after `RateLimiter::for('attest', ...)`), add:

```php
        // Server clipboard drafts: 4/min per (community token, requesting player).
        RateLimiter::for('clipboard-draft', function (Request $request) {
            $key = 'clipboard-draft:'
                .sha1((string) ($request->bearerToken() ?? $request->ip()))
                .':'.(string) $request->input('player_uuid');

            return Limit::perMinute(4)->by($key);
        });
```

`routes/api.php` — inside the existing `Route::prefix('plugin')->middleware('ensure_valid_jwt')->name('api.plugin.')` group, next to sub-project B's `clipboard/resolve` route (or after `attest`), add:

```php
            // Server clipboard -> draft upload (IPC sub-project C).
            Route::post('clipboard/drafts', [App\Http\Controllers\Api\PluginClipboardDraftController::class, 'store'])
                ->middleware('throttle:clipboard-draft')
                ->name('clipboard.drafts.store');
```

`app/Http/Controllers/Api/PluginClipboardDraftController.php` (new file, complete):

```php
<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Models\CommunityToken;
use App\Models\CommunityTokenAudit;
use App\Models\Schematic;
use App\Models\User;
use App\Services\SchematicFileService;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;

/**
 * Plugin-created schematic DRAFTS (IPC sub-project C, contract C1).
 *
 * A community-token plugin pushes a player's server-side WorldEdit clipboard here.
 * The result is an is_public=false, auto-expiring draft owned by the player's linked
 * account — inert until the USER publishes it from their own authenticated session
 * (mod wizard save or the web upload page). The server never receives user credentials
 * and cannot publish.
 */
class PluginClipboardDraftController extends Controller
{
    /** Hard cap on draft schematic bytes (8 MiB) — contract C1. */
    public const MAX_BYTES = 8388608;

    /** Max simultaneous unexpired drafts per user — contract C1. */
    public const MAX_DRAFTS_PER_USER = 10;

    /** Draft lifetime in hours. */
    public const TTL_HOURS = 48;

    public function __construct(private SchematicFileService $fileService)
    {
    }

    public function store(Request $request): JsonResponse
    {
        if (! ($request->is_community_token ?? false)) {
            return response()->json([
                'error' => 'community_token_required',
                'message' => 'This endpoint requires a community token.',
            ], 403);
        }

        $validated = $request->validate([
            'player_uuid' => 'required|string|max:64',
            'file' => 'required|file',
        ]);

        $file = $request->file('file');
        if ($file->getSize() > self::MAX_BYTES) {
            return response()->json([
                'error' => 'too_large',
                'message' => 'Schematic exceeds the 8 MiB draft limit.',
            ], 413);
        }

        // Spec: 400 (not B's 403) when the player has no linked site account.
        $playerUuid = self::normalizeUuid($validated['player_uuid']);
        $user = User::where('uuid', $playerUuid)->first();
        if (! $user) {
            return response()->json([
                'error' => 'player_not_linked',
                'message' => 'No schemat.io account is linked to this Minecraft account.',
            ], 400);
        }

        $activeDrafts = Schematic::query()
            ->where('posted_by', $playerUuid)
            ->whereNotNull('draft_expires_at')
            ->where('draft_expires_at', '>', now())
            ->count();
        if ($activeDrafts >= self::MAX_DRAFTS_PER_USER) {
            return response()->json([
                'error' => 'draft_quota_exceeded',
                'message' => 'Draft limit reached ('.self::MAX_DRAFTS_PER_USER.'). Publish or delete existing drafts first.',
            ], 409);
        }

        // Format/hash/metadata via the standard pipeline (Nucleation FFI degrades
        // gracefully to filename/'unknown'). Duplicate hashes are deliberately
        // ALLOWED for drafts — re-capturing the same clipboard must not 409.
        $processed = $this->fileService->processSchematicFile($file);
        $format = $processed['format'] ?? 'unknown';
        $extension = in_array($format, ['litematic', 'schem', 'schematic', 'mcstructure'], true) ? $format : 'schem';

        $schematic = DB::transaction(function () use ($processed, $playerUuid, $format, $extension) {
            $schematic = new Schematic([
                'name' => 'Clipboard upload '.now()->format('Y-m-d H:i'),
                'is_public' => false,
                'format' => $format,
                'hash' => $processed['hash'],
                'meta_data' => $processed['metadata'],
                'posted_by' => $playerUuid,
            ]);
            // Not fillable by design — set explicitly.
            $schematic->draft_expires_at = now()->addHours(self::TTL_HOURS);
            $schematic->save();

            $schematic->authors()->attach($playerUuid);

            $schematic->addMediaFromString($processed['contents'])
                ->usingFileName($schematic->id.'.'.$extension)
                ->toMediaCollection('schematic');

            return $schematic;
        });

        $tokenId = CommunityToken::findByJti($request->token_payload['jti'] ?? '')?->id;
        CommunityTokenAudit::log(
            CommunityTokenAudit::ACTION_CLIPBOARD_DRAFT,
            $tokenId,
            $playerUuid,
            [
                'community_id' => $request->community_id,
                'player_uuid' => $playerUuid,
                'size' => $file->getSize(),
                'draft_id' => $schematic->id,
            ],
        );

        return response()->json([
            'draft_id' => $schematic->id,
            'short_id' => $schematic->short_id,
            'web_url' => route('schematics.upload.details', ['shortId' => $schematic->short_id]),
            'expires_at' => $schematic->draft_expires_at->toIso8601String(),
        ], 201);
    }

    /** Accepts dashed or undashed Minecraft uuids (contract C1). */
    private static function normalizeUuid(string $uuid): string
    {
        $hex = strtolower(str_replace('-', '', trim($uuid)));
        if (! preg_match('/^[0-9a-f]{32}$/', $hex)) {
            return strtolower(trim($uuid)); // let the linked-account lookup fail naturally
        }

        return sprintf(
            '%s-%s-%s-%s-%s',
            substr($hex, 0, 8),
            substr($hex, 8, 4),
            substr($hex, 12, 4),
            substr($hex, 16, 4),
            substr($hex, 20),
        );
    }
}
```

- [ ] **Step 2.4: Run the test to verify it passes**

Run: `php artisan test --filter=PluginClipboardDraft`
Expected: PASS (9 tests). Also re-run `php artisan test --filter=SchematicDraftVisibility` — still green.

*(No commit — global constraint.)*

---
## Task 3 — schemati: publish-on-update + `is_draft` in the resource

**Files:**
- Modify: `/Users/harrison/Documents/code/schemati/app/Http/Controllers/SchematicController.php` (`update()` only)
- Modify: `/Users/harrison/Documents/code/schemati/app/Http/Resources/SchematicResource.php` (two fields)
- Test: `/Users/harrison/Documents/code/schemati/tests/Feature/Api/SchematicDraftPublishTest.php`

**Interfaces:**
- Consumes: Task 1's `draft_expires_at`; `SchematicController::update(SchematicUpdateRequest $request, string $id)` (rules: `name/description/is_public/tags` all `sometimes`); `SchematicService::canUserAccessSchematic(Schematic, ?string $userUuid): bool` (true for authors of private schematics); `JWT::getTestToken(array $permissions = [], ?string $userUuid = null)` (user JWT with `sub = $userUuid`).
- Produces: owner-auth `PUT /api/v1/schematics/{id}` **including `name`** clears `draft_expires_at` (contract C1 "Publishing"); `SchematicResource` gains `is_draft` (bool) + `draft_expires_at`. Non-owners keep getting 404 on draft GET/PUT (drafts are `is_public=false` — existing `canUserAccessSchematic`).

- [ ] **Step 3.1: Write the failing test**

`tests/Feature/Api/SchematicDraftPublishTest.php`:

```php
<?php

use App\Helpers\JWT;
use App\Models\Player;
use App\Models\Schematic;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\Http;
use Illuminate\Support\Str;

uses(RefreshDatabase::class);

beforeEach(function () {
    Http::fake(['*' => Http::response(['name' => 'TestPlayer', 'id' => 'some-uuid'], 200)]);
    app(\Spatie\Permission\PermissionRegistrar::class)->forgetCachedPermissions();

    $this->player = Player::create([
        'id' => Str::uuid()->toString(),
        'last_seen_name' => 'DraftOwner',
    ]);
    $this->user = User::factory()->create(['uuid' => $this->player->id]);

    $this->stranger = Player::create([
        'id' => Str::uuid()->toString(),
        'last_seen_name' => 'Stranger',
    ]);
    User::factory()->create(['uuid' => $this->stranger->id]);

    $this->draft = Schematic::factory()->create([
        'name' => 'Clipboard upload 2026-07-17 12:00',
        'is_public' => false,
        'posted_by' => $this->player->id,
    ]);
    $this->draft->authors()->sync([$this->player->id]);
    $this->draft->draft_expires_at = now()->addHours(48);
    $this->draft->save();
});

it('lets the owner GET their draft, flagged is_draft', function () {
    $response = $this->withToken(JWT::getTestToken([], $this->player->id))
        ->getJson("/api/v1/schematics/{$this->draft->id}");

    $response->assertOk()
        ->assertJsonPath('data.is_draft', true);
});

it('hides the draft from non-owners with 404', function () {
    $this->withToken(JWT::getTestToken([], $this->stranger->id))
        ->getJson("/api/v1/schematics/{$this->draft->id}")
        ->assertNotFound();
});

it('publishes when the owner saves metadata including name', function () {
    $response = $this->withToken(JWT::getTestToken([], $this->player->id))
        ->putJson("/api/v1/schematics/{$this->draft->id}", [
            'name' => 'My finished build',
            'description' => '<p>A lovely build description.</p>',
            'is_public' => true,
        ]);

    $response->assertOk()
        ->assertJsonPath('data.is_draft', false)
        ->assertJsonPath('data.name', 'My finished build');

    expect($this->draft->fresh()->draft_expires_at)->toBeNull();
});

it('does not publish on a name-less update', function () {
    $this->withToken(JWT::getTestToken([], $this->player->id))
        ->putJson("/api/v1/schematics/{$this->draft->id}", ['is_public' => false])
        ->assertOk();

    expect($this->draft->fresh()->draft_expires_at)->not->toBeNull();
});

it('rejects a non-owner PUT with 404', function () {
    $this->withToken(JWT::getTestToken([], $this->stranger->id))
        ->putJson("/api/v1/schematics/{$this->draft->id}", ['name' => 'Hijacked'])
        ->assertNotFound();

    expect($this->draft->fresh()->draft_expires_at)->not->toBeNull();
});
```

- [ ] **Step 3.2: Run the test to verify it fails**

Run: `php artisan test --filter=SchematicDraftPublish`
Expected: FAIL — `data.is_draft` missing from the resource, and the publish test finds `draft_expires_at` still set.

- [ ] **Step 3.3: Implement**

`app/Http/Resources/SchematicResource.php` — in `toArray()`, directly after `'is_public' => $this->is_public,` add:

```php
            // Plugin-created draft state (IPC sub-project C). Drafts are invisible in
            // listings; owners see them via direct GET (the wizard's ownership check).
            'is_draft' => $this->draft_expires_at !== null,
            'draft_expires_at' => $this->draft_expires_at,
```

`app/Http/Controllers/SchematicController.php` — in `update()`, after the `$updatedSchematic = $this->schematicService->updateSchematic(...)` line and before `return new SchematicResource($updatedSchematic);`, add:

```php
            // Publishing a plugin-created draft (IPC sub-project C): the owner's normal
            // metadata save — the upload wizard always sends `name` — clears the expiry,
            // making the schematic permanent. A bare visibility tweak does not publish.
            if ($updatedSchematic->draft_expires_at !== null && $request->filled('name')) {
                $updatedSchematic->draft_expires_at = null;
                $updatedSchematic->save();
            }
```

- [ ] **Step 3.4: Run the test to verify it passes**

Run: `php artisan test --filter=SchematicDraftPublish`
Expected: PASS (5 tests).

*(No commit — global constraint.)*

---

## Task 4 — schemati: expiry command + daily schedule

**Files:**
- Create: `/Users/harrison/Documents/code/schemati/app/Console/Commands/PurgeExpiredSchematicDrafts.php`
- Modify: `/Users/harrison/Documents/code/schemati/routes/console.php` (one `Schedule::command` line)
- Test: `/Users/harrison/Documents/code/schemati/tests/Feature/Api/SchematicDraftExpiryTest.php`

**Interfaces:**
- Consumes: Task 1's `draft_expires_at`; `Schematic` SoftDeletes + the model's `deleting` hook (media/versions/branches are purged **only on force delete** — `isForceDeleting()`); this app schedules via `routes/console.php` with the `Illuminate\Support\Facades\Schedule` facade (e.g. the existing `Schedule::command('model:prune', ...)->daily();`).
- Produces: artisan command `schematics:purge-expired-drafts` (force-deletes drafts past expiry, files included), scheduled daily.

- [ ] **Step 4.1: Write the failing test**

`tests/Feature/Api/SchematicDraftExpiryTest.php`:

```php
<?php

use App\Models\Player;
use App\Models\Schematic;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\Http;
use Illuminate\Support\Facades\Storage;
use Illuminate\Support\Str;
use Spatie\MediaLibrary\MediaCollections\Models\Media;

uses(RefreshDatabase::class);

beforeEach(function () {
    Http::fake(['*' => Http::response(['name' => 'TestPlayer', 'id' => 'some-uuid'], 200)]);
    app(\Spatie\Permission\PermissionRegistrar::class)->forgetCachedPermissions();

    $this->player = Player::create([
        'id' => Str::uuid()->toString(),
        'last_seen_name' => 'ExpiryOwner',
    ]);
});

function cdraftExpiryMakeDraft(Player $owner, string $bytes = 'draft-bytes'): Schematic
{
    $schematic = Schematic::factory()->create([
        'is_public' => false,
        'posted_by' => $owner->id,
    ]);
    $schematic->authors()->sync([$owner->id]);
    $schematic->draft_expires_at = now()->addHours(48);
    $schematic->save();
    $schematic->addMediaFromString($bytes)
        ->usingFileName($schematic->id.'.schem')
        ->toMediaCollection('schematic');

    return $schematic->fresh();
}

it('force-deletes expired drafts including their stored files', function () {
    $expired = cdraftExpiryMakeDraft($this->player);
    $media = $expired->getFirstMedia('schematic');
    $disk = $media->disk;
    $path = $media->getPathRelativeToRoot();
    expect(Storage::disk($disk)->exists($path))->toBeTrue();

    $this->travel(49)->hours();

    $this->artisan('schematics:purge-expired-drafts')->assertExitCode(0);

    // Gone from the DB entirely (force delete, not soft delete)…
    expect(Schematic::withTrashed()->find($expired->id))->toBeNull();
    // …media row gone…
    expect(Media::where('model_id', $expired->id)->count())->toBe(0);
    // …and the file bytes really deleted from disk (spec invariant 5).
    expect(Storage::disk($disk)->exists($path))->toBeFalse();
});

it('leaves unexpired drafts and published schematics alone', function () {
    $fresh = cdraftExpiryMakeDraft($this->player);
    $published = Schematic::factory()->create(['is_public' => true]);

    $this->travel(24)->hours(); // fresh draft still has ~24h left

    $this->artisan('schematics:purge-expired-drafts')->assertExitCode(0);

    expect(Schematic::find($fresh->id))->not->toBeNull();
    expect(Schematic::find($published->id))->not->toBeNull();
});

it('is registered on the daily schedule', function () {
    $events = collect(app(\Illuminate\Console\Scheduling\Schedule::class)->events());

    expect(
        $events->contains(fn ($event) => str_contains($event->command ?? '', 'schematics:purge-expired-drafts')),
    )->toBeTrue();
});
```

- [ ] **Step 4.2: Run the test to verify it fails**

Run: `php artisan test --filter=SchematicDraftExpiry`
Expected: FAIL — "There are no commands defined in the 'schematics' namespace."

- [ ] **Step 4.3: Implement**

`app/Console/Commands/PurgeExpiredSchematicDrafts.php` (new file, complete):

```php
<?php

namespace App\Console\Commands;

use App\Models\Schematic;
use Illuminate\Console\Command;

/**
 * Deletes plugin-created schematic drafts past draft_expires_at (IPC sub-project C).
 *
 * forceDelete() (not delete()) is essential: the Schematic deleting hook only purges
 * media, versions, and branches when isForceDeleting() — a soft delete would strand
 * the stored bytes forever (spec invariant 5: "expired drafts are really deleted,
 * files included").
 */
class PurgeExpiredSchematicDrafts extends Command
{
    protected $signature = 'schematics:purge-expired-drafts';

    protected $description = 'Force-delete plugin-created schematic drafts past draft_expires_at (files included)';

    public function handle(): int
    {
        $expired = Schematic::query()
            ->whereNotNull('draft_expires_at')
            ->where('draft_expires_at', '<', now())
            ->get();

        foreach ($expired as $draft) {
            $draft->forceDelete();
        }

        $this->info("Purged {$expired->count()} expired schematic draft(s).");

        return self::SUCCESS;
    }
}
```

`routes/console.php` — next to the existing `Schedule::command('model:prune', ...)->daily();` line, add:

```php
// Plugin-created clipboard drafts expire after 48h (IPC sub-project C).
Schedule::command('schematics:purge-expired-drafts')->daily();
```

- [ ] **Step 4.4: Run the test to verify it passes**

Run: `php artisan test --filter=SchematicDraftExpiry`
Expected: PASS (3 tests).

*(No commit — global constraint.)*

---

## Task 5 — schemati: web draft completion (standalone chat-link path)

**Files:**
- Modify: `/Users/harrison/Documents/code/schemati/app/Livewire/Schematics/Upload/SchematicUploader.php`
- Test: `/Users/harrison/Documents/code/schemati/tests/Feature/Livewire/SchematicDraftUploaderTest.php`

**Interfaces:**
- Consumes: Tasks 1–2 (draft rows + `web_url` pointing at `route('schematics.upload.details', shortId)`); `SchematicUploader` internals: `mount(?string $shortId)`, `mode` property (`'direct'|'cached'`), `loadFromShortId()`, `create()` (the 130-line transaction), `creditedAuthorIds()`, validated props `name/description/isPublic/previewImage/selectedTags`, `$this->author` (= `Auth::user()->player->id`), `schematicBase64` (feeds the in-browser renderer + the blade `hasFile` gating — no blade changes needed: the upload dropzone is `@if ($mode === 'direct')` and everything else keys off `hasFile`); `Schematic::getLegacyFile()`.
- Produces: `mode === 'draft'` — mount resolves a persisted draft by `short_id` (cache short-links still checked first), prefills the form, and `create()` routes to a new `publishDraft()` that updates the draft in place, attaches the preview image, syncs authors/tags, and clears `draft_expires_at`.

- [ ] **Step 5.1: Write the failing test**

`tests/Feature/Livewire/SchematicDraftUploaderTest.php`:

```php
<?php

use App\Livewire\Schematics\Upload\SchematicUploader;
use App\Models\Player;
use App\Models\Schematic;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Http\UploadedFile;
use Illuminate\Support\Facades\Http;
use Illuminate\Support\Str;
use Livewire\Livewire;

uses(RefreshDatabase::class);

beforeEach(function () {
    Http::fake(['*' => Http::response(['name' => 'TestPlayer', 'id' => 'some-uuid'], 200)]);
    app(\Spatie\Permission\PermissionRegistrar::class)->forgetCachedPermissions();

    $this->player = Player::create([
        'id' => Str::uuid()->toString(),
        'last_seen_name' => 'WebDraftOwner',
    ]);
    $this->user = User::factory()->create(['uuid' => $this->player->id]);

    $this->draft = Schematic::factory()->create([
        'name' => 'Clipboard upload 2026-07-17 12:00',
        'is_public' => false,
        'format' => 'schem',
        'posted_by' => $this->player->id,
    ]);
    $this->draft->authors()->sync([$this->player->id]);
    $this->draft->draft_expires_at = now()->addHours(48);
    $this->draft->save();
    $this->draft->addMediaFromString('draft-schem-bytes')
        ->usingFileName($this->draft->id.'.schem')
        ->toMediaCollection('schematic');
});

it('mounts in draft mode from the draft short link, prefilled', function () {
    Livewire::actingAs($this->user)
        ->test(SchematicUploader::class, ['shortId' => $this->draft->short_id])
        ->assertSet('mode', 'draft')
        ->assertSet('draftId', $this->draft->id)
        ->assertSet('name', $this->draft->name)
        ->assertSet('schematicBase64', base64_encode('draft-schem-bytes'));
});

it('publishes the draft on create()', function () {
    Livewire::actingAs($this->user)
        ->test(SchematicUploader::class, ['shortId' => $this->draft->short_id])
        ->set('name', 'My web-finished build')
        ->set('description', 'A meaningful description of the build.')
        ->set('isPublic', true)
        ->set('previewImage', UploadedFile::fake()->image('preview.png'))
        ->call('create');

    $published = $this->draft->fresh();
    expect($published->draft_expires_at)->toBeNull();
    expect($published->name)->toBe('My web-finished build');
    expect($published->is_public)->toBeTrue();
    expect($published->getFirstMedia('preview_image'))->not->toBeNull();
    // The original schematic bytes are untouched.
    expect($published->getFirstMedia('schematic'))->not->toBeNull();
});

it('refuses a stranger\'s draft link', function () {
    $stranger = Player::create(['id' => Str::uuid()->toString(), 'last_seen_name' => 'Stranger']);
    $strangerUser = User::factory()->create(['uuid' => $stranger->id]);

    Livewire::actingAs($strangerUser)
        ->test(SchematicUploader::class, ['shortId' => $this->draft->short_id])
        ->assertRedirect('/schematics');

    expect($this->draft->fresh()->draft_expires_at)->not->toBeNull();
});
```

- [ ] **Step 5.2: Run the test to verify it fails**

Run: `php artisan test --filter=SchematicDraftUploader`
Expected: FAIL — mount falls into the cached path ("The schematic you are trying to upload is not valid." redirect), `mode` is `'cached'` not `'draft'`.

- [ ] **Step 5.3: Implement**

`app/Livewire/Schematics/Upload/SchematicUploader.php` — five edits (the file already imports `Schematic`, `DB`, `Log`, `Cache`, `Auth`, `SchematicTagFilterValue`):

(a) Next to `public ?string $shortId = null;`, add:

```php
    // Persisted plugin-created draft being completed ('draft' mode — IPC sub-project C).
    public ?string $draftId = null;
```

(b) In `mount()`, replace the trailing block

```php
        if ($shortId) {
            $this->shortId = $shortId;
            $this->mode = 'cached';
            $this->loadFromShortId($shortId);
        }
```

with:

```php
        if ($shortId) {
            $this->shortId = $shortId;

            // Persisted plugin-created draft? (IPC sub-project C.) Cache short-links
            // keep priority-by-existence: drafts are looked up by the schematic's own
            // short_id, cache links by their random key — the two cannot collide, so
            // order only matters for the not-found fallthrough.
            $draft = Schematic::query()
                ->whereNotNull('draft_expires_at')
                ->where('short_id', $shortId)
                ->first();

            if ($draft) {
                $this->mode = 'draft';
                $this->loadFromDraft($draft);
            } else {
                $this->mode = 'cached';
                $this->loadFromShortId($shortId);
            }
        }
```

(c) After `loadFromShortId()`, add:

```php
    /**
     * Plugin-created draft (IPC sub-project C): prefill the form from the persisted
     * schematic. Ownership gate: only the player the plugin attributed may complete it.
     */
    protected function loadFromDraft(Schematic $draft): void
    {
        if ($draft->posted_by !== $this->author) {
            session()->flash('error', 'This draft belongs to a different account.');
            $this->redirect('/schematics');

            return;
        }

        $this->draftId = $draft->id;
        $this->name = $draft->name;
        $this->description = $draft->description ?? '';
        $this->isPublic = (bool) $draft->is_public;
        $this->fileExtension = $draft->format ?: 'schem';

        // Bytes for the in-browser renderer preview (drives the blade's hasFile state).
        $file = $draft->getLegacyFile();
        if ($file) {
            $this->schematicBase64 = base64_encode($file);
        }
    }
```

(d) In `create()`, directly after the closing `}` of the `try { $this->validate(); } catch (...) {...}` block (i.e. BEFORE the `// In direct mode, must have a file` check), add:

```php
        // Plugin-created draft (IPC sub-project C): update-in-place publish.
        if ($this->mode === 'draft') {
            return $this->publishDraft();
        }
```

(e) After `create()`, add:

```php
    /**
     * Publish a plugin-created draft: update its metadata in place, attach the preview
     * image, and clear draft_expires_at — the publish signal that makes it visible
     * (IPC sub-project C). The schematic file already exists as media; it is untouched.
     * Validation has already run in create().
     */
    protected function publishDraft()
    {
        $draft = Schematic::query()
            ->whereNotNull('draft_expires_at')
            ->where('id', $this->draftId)
            ->first();

        if (! $draft || $draft->posted_by !== $this->author) {
            $this->errorMessage = 'This draft no longer exists — it may have expired.';

            return;
        }

        if (! $this->builtThis && ! $this->authorUnknown && $this->creditedAuthorIds() === []) {
            $this->addError('coAuthors', 'Credit at least one author, or mark the author as unknown.');
            $this->errorMessage = "You've marked that you didn't build this schematic — credit at least one author, or mark the author as unknown.";

            return;
        }

        try {
            $schematic = DB::transaction(function () use ($draft) {
                $draft->update([
                    'name' => $this->name,
                    'description' => $this->description,
                    'is_public' => $this->isPublic,
                ]);

                // Credited authors: same rules as a fresh upload. The plugin attached
                // the uploader at draft creation; rebuild the set from the form.
                $authorIds = $this->builtThis ? [$this->author] : [];
                $draft->authors()->sync(array_unique(array_merge($authorIds, $this->creditedAuthorIds())));

                if (isset($this->selectedTags) && is_array($this->selectedTags)) {
                    foreach ($this->selectedTags as $tagData) {
                        $draft->tags()->syncWithoutDetaching([$tagData['tagId']]);

                        if (isset($tagData['filters']) && is_array($tagData['filters'])) {
                            foreach ($tagData['filters'] as $filterId => $value) {
                                if ($value !== null && $value !== '') {
                                    SchematicTagFilterValue::create([
                                        'schematic_id' => $draft->id,
                                        'tag_filter_id' => $filterId,
                                        'value' => (string) $value,
                                    ]);
                                }
                            }
                        }
                    }
                }

                $draft
                    ->addMedia($this->previewImage->getRealPath())
                    ->usingFileName($draft->id.'.png')
                    ->toMediaCollection('preview_image');

                // The publish signal: a non-null expiry is what hides drafts everywhere.
                $draft->draft_expires_at = null;
                $draft->save();

                $draft->triggerCallbacks();

                return $draft;
            });

            app(\App\Services\AuthorshipService::class)->notifyAttributedAuthors(
                $schematic,
                $schematic->authors()->pluck('players.id')->all()
            );

            session()->flash('success', 'Schematic published successfully!');

            return $this->redirect('/schematics', navigate: true);
        } catch (\Exception $e) {
            Log::error('Draft publish failed: '.$e->getMessage());
            $this->errorMessage = 'Failed to publish draft: '.$e->getMessage();
        }
    }
```

- [ ] **Step 5.4: Run the test to verify it passes**

Run: `php artisan test --filter=SchematicDraftUploader`
Expected: PASS (3 tests). Then run the full draft matrix so far: `php artisan test --filter="SchematicDraft|PluginClipboardDraft"` — all green.

*(No commit — global constraint.)*

---
## Task 6 — :core — opcodes 7/8, caps, UPLOAD capability un-reserved

**Files:**
- Modify: `/Users/harrison/IdeaProjects/SchematioConnector/core/src/main/kotlin/io/schemat/connector/core/ipc/IpcProtocol.kt`
- Modify: `/Users/harrison/IdeaProjects/SchematioConnector/core/src/main/kotlin/io/schemat/connector/core/ipc/IpcMessages.kt`
- Test: `/Users/harrison/IdeaProjects/SchematioConnector/core/src/test/kotlin/io/schemat/connector/core/ipc/IpcCodecTest.kt` (append tests)

**Interfaces:**
- Consumes: contract C2; B's `IpcCaps`/`IpcPayloadTooLargeException`/`IpcFormatException`, `IpcWriter`/`IpcReader` primitives, `IpcCodec.peekOpcode`.
- Produces (exact signatures — later tasks rely on these):

```kotlin
// IpcProtocol.kt
object IpcOpcode { /* existing 1..6 */ const val UPLOAD_CLIPBOARD: Int = 7; const val DRAFT_CREATED: Int = 8 }
object IpcCaps { /* existing */ const val UPLOAD_CLIPBOARD: Int = 64; const val DRAFT_CREATED: Int = 256 }
// Capabilities.UPLOAD (1 shl 1) keeps its value; only the comment changes (un-reserved).

// IpcMessages.kt
data class UploadClipboard(val protocolVersion: Int, val requestId: Int)
data class DraftCreated(val protocolVersion: Int, val requestId: Int, val draftId: String) {
    companion object { const val MAX_DRAFT_ID_CHARS = 64 }
}
// IpcCodec gains: encodeUploadClipboard(msg): ByteArray, decodeUploadClipboard(bytes): UploadClipboard,
//                 encodeDraftCreated(msg): ByteArray,   decodeDraftCreated(bytes): DraftCreated
// Both decodes start with the IpcCaps check (IpcPayloadTooLargeException, no parsing).
```

- [ ] **Step 6.1: Write the failing tests**

Append to `IpcCodecTest.kt` (keep every existing test):

```kotlin
    @Test
    fun `upload clipboard round-trips within its cap`() {
        val msg = UploadClipboard(IpcProtocol.VERSION, 42)
        val bytes = IpcCodec.encodeUploadClipboard(msg)
        assertEquals(IpcOpcode.UPLOAD_CLIPBOARD, IpcCodec.peekOpcode(bytes))
        assertTrue(bytes.size <= IpcCaps.UPLOAD_CLIPBOARD)
        assertEquals(msg, IpcCodec.decodeUploadClipboard(bytes))
    }

    @Test
    fun `upload clipboard rejects a negative requestId at construction`() {
        assertThrows(IllegalArgumentException::class.java) { UploadClipboard(2, -1) }
    }

    @Test
    fun `over-cap upload clipboard payload throws before parsing`() {
        val padded = IpcCodec.encodeUploadClipboard(UploadClipboard(2, 1)) +
            ByteArray(IpcCaps.UPLOAD_CLIPBOARD)
        assertThrows(IpcPayloadTooLargeException::class.java) { IpcCodec.decodeUploadClipboard(padded) }
    }

    @Test
    fun `draft created round-trips within its cap`() {
        val msg = DraftCreated(IpcProtocol.VERSION, 7, "11111111-2222-3333-4444-555555555555")
        val bytes = IpcCodec.encodeDraftCreated(msg)
        assertEquals(IpcOpcode.DRAFT_CREATED, IpcCodec.peekOpcode(bytes))
        assertTrue(bytes.size <= IpcCaps.DRAFT_CREATED)
        assertEquals(msg, IpcCodec.decodeDraftCreated(bytes))
    }

    @Test
    fun `draft created rejects bad fields at construction`() {
        assertThrows(IllegalArgumentException::class.java) { DraftCreated(2, -1, "x") }
        assertThrows(IllegalArgumentException::class.java) { DraftCreated(2, 1, "") }
        assertThrows(IllegalArgumentException::class.java) { DraftCreated(2, 1, "a".repeat(65)) }
    }

    @Test
    fun `decoding a hand-built draft created with an empty draftId throws IpcFormatException`() {
        val bytes = IpcWriter().apply {
            writeByte(IpcOpcode.DRAFT_CREATED)
            writeVarInt(2)
            writeVarInt(1)
            writeString("")
        }.toByteArray()
        assertThrows(IpcFormatException::class.java) { IpcCodec.decodeDraftCreated(bytes) }
    }

    @Test
    fun `caps table covers the new opcodes`() {
        assertEquals(IpcCaps.UPLOAD_CLIPBOARD, IpcCaps.forOpcode(IpcOpcode.UPLOAD_CLIPBOARD))
        assertEquals(IpcCaps.DRAFT_CREATED, IpcCaps.forOpcode(IpcOpcode.DRAFT_CREATED))
    }
```

(add the imports `UploadClipboard` / `DraftCreated` alongside the file's existing `io.schemat.connector.core.ipc` imports if the test file uses explicit imports).

- [ ] **Step 6.2: Run to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:test`
Expected: compilation FAILS (`UPLOAD_CLIPBOARD`/`UploadClipboard` unresolved).

- [ ] **Step 6.3: Implement**

`IpcProtocol.kt` — three edits:

(a) In `IpcOpcode`, after `const val STATUS: Int = 6` (added by B):

```kotlin
    /** C->S: "push MY current WE clipboard to the backend as a draft" (sub-project C). */
    const val UPLOAD_CLIPBOARD: Int = 7

    /** S->C: the backend created a draft; draftId is an opaque id for the CLIENT's own API. */
    const val DRAFT_CREATED: Int = 8
```

(b) In `IpcCaps`, add the two constants and extend `forOpcode`:

```kotlin
    const val UPLOAD_CLIPBOARD: Int = 64
    const val DRAFT_CREATED: Int = 256
```

and in `forOpcode`'s `when`, before the null/else arm:

```kotlin
        IpcOpcode.UPLOAD_CLIPBOARD -> UPLOAD_CLIPBOARD
        IpcOpcode.DRAFT_CREATED -> DRAFT_CREATED
```

(c) In `Capabilities`, replace the `UPLOAD` line's comment (the VALUE stays `1 shl 1`):

```kotlin
    /** Server accepts UPLOAD_CLIPBOARD: push the player's server-side WE clipboard to the backend as a draft (sub-project C). */
    const val UPLOAD: Int = 1 shl 1
```

`IpcMessages.kt` — append the message types and codec functions (match the surrounding style of B's `LoadRequest`/`Status`; if B landed a shared cap-check helper, reuse it instead of the inline size checks below):

```kotlin
/**
 * C->S (opcode 7): upload the requesting player's CURRENT server-side WorldEdit
 * clipboard as a backend draft. Deliberately field-free beyond the requestId —
 * the subject is always "my clipboard" (spec).
 */
data class UploadClipboard(val protocolVersion: Int, val requestId: Int) {
    init {
        require(requestId >= 0) { "requestId must be non-negative" }
    }
}

/**
 * S->C (opcode 8): the backend created a draft. [draftId] is an OPAQUE id the client
 * resolves against its OWN backend with the USER's auth — nothing else from the
 * server is trusted (spec).
 */
data class DraftCreated(val protocolVersion: Int, val requestId: Int, val draftId: String) {
    init {
        require(requestId >= 0) { "requestId must be non-negative" }
        require(draftId.isNotEmpty() && draftId.length <= MAX_DRAFT_ID_CHARS) {
            "draftId must be 1..$MAX_DRAFT_ID_CHARS chars"
        }
    }

    companion object {
        const val MAX_DRAFT_ID_CHARS = 64
    }
}
```

and inside `IpcCodec`:

```kotlin
    fun encodeUploadClipboard(msg: UploadClipboard): ByteArray = IpcWriter().apply {
        writeByte(IpcOpcode.UPLOAD_CLIPBOARD)
        writeVarInt(msg.protocolVersion)
        writeVarInt(msg.requestId)
    }.toByteArray()

    fun decodeUploadClipboard(bytes: ByteArray): UploadClipboard {
        if (bytes.size > IpcCaps.UPLOAD_CLIPBOARD) {
            throw IpcPayloadTooLargeException(
                "UPLOAD_CLIPBOARD payload ${bytes.size} bytes exceeds cap ${IpcCaps.UPLOAD_CLIPBOARD}",
            )
        }
        val reader = IpcReader(bytes)
        val opcode = reader.readByte()
        if (opcode != IpcOpcode.UPLOAD_CLIPBOARD) {
            throw IpcFormatException("expected UPLOAD_CLIPBOARD opcode, got $opcode")
        }
        val protocolVersion = reader.readVarInt()
        val requestId = reader.readVarInt()
        if (requestId < 0) throw IpcFormatException("negative requestId")
        return UploadClipboard(protocolVersion, requestId)
    }

    fun encodeDraftCreated(msg: DraftCreated): ByteArray = IpcWriter().apply {
        writeByte(IpcOpcode.DRAFT_CREATED)
        writeVarInt(msg.protocolVersion)
        writeVarInt(msg.requestId)
        writeString(msg.draftId)
    }.toByteArray()

    fun decodeDraftCreated(bytes: ByteArray): DraftCreated {
        if (bytes.size > IpcCaps.DRAFT_CREATED) {
            throw IpcPayloadTooLargeException(
                "DRAFT_CREATED payload ${bytes.size} bytes exceeds cap ${IpcCaps.DRAFT_CREATED}",
            )
        }
        val reader = IpcReader(bytes)
        val opcode = reader.readByte()
        if (opcode != IpcOpcode.DRAFT_CREATED) {
            throw IpcFormatException("expected DRAFT_CREATED opcode, got $opcode")
        }
        val protocolVersion = reader.readVarInt()
        val requestId = reader.readVarInt()
        if (requestId < 0) throw IpcFormatException("negative requestId")
        val draftId = reader.readString()
        if (draftId.isEmpty() || draftId.length > DraftCreated.MAX_DRAFT_ID_CHARS) {
            throw IpcFormatException("draftId must be 1..${DraftCreated.MAX_DRAFT_ID_CHARS} chars")
        }
        return DraftCreated(protocolVersion, requestId, draftId)
    }
```

- [ ] **Step 6.4: Run to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:test`
Expected: BUILD SUCCESSFUL — 7 new tests plus all existing codec tests green.

*(No commit — global constraint.)*

---

## Task 7 — :core — ClipboardUploadClient (multipart POST, outcome mapping)

**Files:**
- Create: `/Users/harrison/IdeaProjects/SchematioConnector/core/src/main/kotlin/io/schemat/connector/core/modapi/ClipboardUploadClient.kt`
- Test: `/Users/harrison/IdeaProjects/SchematioConnector/core/src/test/kotlin/io/schemat/connector/core/modapi/ClipboardUploadClientTest.kt`

**Interfaces:**
- Consumes: contract C1 (plugin-side mapping table); existing `ApiTransport`/`ApiRequest`/`ApiResponse`/`HttpMethod`/`MultipartRequest`/`MultipartFile` (multipart is already supported by `HttpTransport`); `io.schemat.connector.core.json.parseJsonSafe`/`safeGetString`; `kotlinx.coroutines.withTimeoutOrNull`; test double `FakeTransport` (`enqueue(status, body, headers)`, `enqueueNetworkFailure()`, `requests`, `lastRequest()`, `lastToken()`).
- Produces:

```kotlin
sealed class ClipboardUploadOutcome {
    class Created(val draftId: String, val webUrl: String, val expiresAt: String) : ClipboardUploadOutcome()
    object NotLinked : ClipboardUploadOutcome()      // 400 player_not_linked
    object Denied : ClipboardUploadOutcome()         // 401/403
    object QuotaExceeded : ClipboardUploadOutcome()  // 409
    object TooLarge : ClipboardUploadOutcome()       // 413 or client pre-check
    object RateLimited : ClipboardUploadOutcome()    // 429
    object Unavailable : ClipboardUploadOutcome()    // 5xx / timeout / transport failure / no token
    object Error : ClipboardUploadOutcome()          // anything else (incl. 422, bad 201 body)
}
class ClipboardUploadClient(transport: ApiTransport, tokenProvider: () -> String?, timeoutMs: Long = 30_000) {
    companion object { const val MAX_UPLOAD_BYTES: Int = 8 * 1024 * 1024 }
    suspend fun upload(playerUuid: String, schemBytes: ByteArray): ClipboardUploadOutcome
}
```

- [ ] **Step 7.1: Write the failing test**

`ClipboardUploadClientTest.kt`:

```kotlin
package io.schemat.connector.core.modapi

import io.schemat.connector.core.modapi.transport.HttpMethod
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClipboardUploadClientTest {

    private val bytes = ByteArray(16) { it.toByte() }

    private fun client(transport: FakeTransport, token: String? = "community-jwt") =
        ClipboardUploadClient(transport, { token })

    @Test
    fun `201 parses Created and posts the right multipart request`() = runTest {
        val transport = FakeTransport()
        transport.enqueue(
            201,
            """{"draft_id":"d-1","short_id":"s1","web_url":"https://schemat.io/schematics/upload/s1","expires_at":"2026-07-19T12:00:00+00:00"}""",
        )

        val outcome = client(transport).upload("player-uuid", bytes)

        val created = outcome as ClipboardUploadOutcome.Created
        assertEquals("d-1", created.draftId)
        assertEquals("https://schemat.io/schematics/upload/s1", created.webUrl)
        assertEquals("2026-07-19T12:00:00+00:00", created.expiresAt)

        val request = transport.lastRequest()
        assertEquals(HttpMethod.POST, request.method)
        assertEquals("/plugin/clipboard/drafts", request.path)
        val multipart = request.multipart!!
        assertEquals(listOf("player_uuid" to "player-uuid"), multipart.fields)
        assertEquals("file", multipart.files.single().fieldName)
        assertEquals("clipboard.schem", multipart.files.single().fileName)
        assertTrue(multipart.files.single().bytes.contentEquals(bytes))
        // COMMUNITY token only — user tokens never reach this client (spec invariant 1).
        assertEquals("community-jwt", transport.lastToken())
    }

    @Test
    fun `error statuses map per the contract table`() = runTest {
        suspend fun outcomeFor(status: Int, body: String): ClipboardUploadOutcome {
            val transport = FakeTransport()
            transport.enqueue(status, body)
            return client(transport).upload("p", bytes)
        }

        assertEquals(ClipboardUploadOutcome.NotLinked, outcomeFor(400, """{"error":"player_not_linked"}"""))
        assertEquals(ClipboardUploadOutcome.Error, outcomeFor(400, """{"error":"something_else"}"""))
        assertEquals(ClipboardUploadOutcome.Denied, outcomeFor(403, """{"error":"community_token_required"}"""))
        assertEquals(ClipboardUploadOutcome.QuotaExceeded, outcomeFor(409, """{"error":"draft_quota_exceeded"}"""))
        assertEquals(ClipboardUploadOutcome.TooLarge, outcomeFor(413, """{"error":"too_large"}"""))
        assertEquals(ClipboardUploadOutcome.RateLimited, outcomeFor(429, "{}"))
        assertEquals(ClipboardUploadOutcome.Unavailable, outcomeFor(500, "oops"))
        assertEquals(ClipboardUploadOutcome.Error, outcomeFor(422, """{"message":"validation"}"""))
    }

    @Test
    fun `a 201 with a malformed body is Error, not a crash`() = runTest {
        val transport = FakeTransport()
        transport.enqueue(201, "not-json")
        assertEquals(ClipboardUploadOutcome.Error, client(transport).upload("p", bytes))
    }

    @Test
    fun `transport failure maps to Unavailable`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNetworkFailure()
        assertEquals(ClipboardUploadOutcome.Unavailable, client(transport).upload("p", bytes))
    }

    @Test
    fun `oversize bytes are rejected client-side without any transport call`() = runTest {
        val transport = FakeTransport()
        val oversize = ByteArray(ClipboardUploadClient.MAX_UPLOAD_BYTES + 1)
        assertEquals(ClipboardUploadOutcome.TooLarge, client(transport).upload("p", oversize))
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `missing community token is Unavailable without any transport call`() = runTest {
        val transport = FakeTransport()
        assertEquals(ClipboardUploadOutcome.Unavailable, client(transport, token = null).upload("p", bytes))
        assertTrue(transport.requests.isEmpty())
    }
}
```

- [ ] **Step 7.2: Run to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:test`
Expected: compilation FAILS (no `ClipboardUploadClient`).

- [ ] **Step 7.3: Implement**

`ClipboardUploadClient.kt` (new file, complete):

```kotlin
package io.schemat.connector.core.modapi

import io.schemat.connector.core.json.parseJsonSafe
import io.schemat.connector.core.json.safeGetString
import io.schemat.connector.core.modapi.transport.ApiRequest
import io.schemat.connector.core.modapi.transport.ApiResponse
import io.schemat.connector.core.modapi.transport.ApiTransport
import io.schemat.connector.core.modapi.transport.HttpMethod
import io.schemat.connector.core.modapi.transport.MultipartFile
import io.schemat.connector.core.modapi.transport.MultipartRequest
import kotlinx.coroutines.withTimeoutOrNull

/** Outcome of a draft upload, one variant per contract-C1 mapping row. */
sealed class ClipboardUploadOutcome {
    class Created(val draftId: String, val webUrl: String, val expiresAt: String) : ClipboardUploadOutcome()
    object NotLinked : ClipboardUploadOutcome()
    object Denied : ClipboardUploadOutcome()
    object QuotaExceeded : ClipboardUploadOutcome()
    object TooLarge : ClipboardUploadOutcome()
    object RateLimited : ClipboardUploadOutcome()
    object Unavailable : ClipboardUploadOutcome()
    object Error : ClipboardUploadOutcome()
}

/**
 * POSTs serialized clipboard bytes to `POST /plugin/clipboard/drafts` (contract C1)
 * with the COMMUNITY token. This client never sees user credentials — the draft is
 * inert until the USER publishes it from their own session (spec invariant 1).
 */
class ClipboardUploadClient(
    private val transport: ApiTransport,
    private val tokenProvider: () -> String?,
    private val timeoutMs: Long = 30_000,
) {
    companion object {
        /** Hard cap, checked BEFORE any network call (backend re-checks with 413). */
        const val MAX_UPLOAD_BYTES: Int = 8 * 1024 * 1024
    }

    suspend fun upload(playerUuid: String, schemBytes: ByteArray): ClipboardUploadOutcome {
        if (schemBytes.size > MAX_UPLOAD_BYTES) return ClipboardUploadOutcome.TooLarge
        val token = tokenProvider() ?: return ClipboardUploadOutcome.Unavailable

        val request = ApiRequest(
            method = HttpMethod.POST,
            path = "/plugin/clipboard/drafts",
            multipart = MultipartRequest(
                fields = listOf("player_uuid" to playerUuid),
                files = listOf(
                    MultipartFile("file", "clipboard.schem", "application/octet-stream", schemBytes),
                ),
            ),
        )

        val response = try {
            withTimeoutOrNull(timeoutMs) { transport.execute(request, token) }
        } catch (_: Exception) {
            null
        } ?: return ClipboardUploadOutcome.Unavailable

        return when (response.status) {
            201 -> parseCreated(response)
            400 -> if (errorCode(response) == "player_not_linked") {
                ClipboardUploadOutcome.NotLinked
            } else {
                ClipboardUploadOutcome.Error
            }
            401, 403 -> ClipboardUploadOutcome.Denied
            409 -> ClipboardUploadOutcome.QuotaExceeded
            413 -> ClipboardUploadOutcome.TooLarge
            429 -> ClipboardUploadOutcome.RateLimited
            in 500..599 -> ClipboardUploadOutcome.Unavailable
            else -> ClipboardUploadOutcome.Error
        }
    }

    private fun parseCreated(response: ApiResponse): ClipboardUploadOutcome {
        val json = parseJsonSafe(response.bodyAsString())
        val draftId = json.safeGetString("draft_id")
        val webUrl = json.safeGetString("web_url")
        if (draftId.isNullOrEmpty() || webUrl.isNullOrEmpty()) return ClipboardUploadOutcome.Error
        return ClipboardUploadOutcome.Created(draftId, webUrl, json.safeGetString("expires_at") ?: "")
    }

    private fun errorCode(response: ApiResponse): String? =
        parseJsonSafe(response.bodyAsString()).safeGetString("error")
}
```

(If `ApiResponse` in this tree lacks `bodyAsString()`, use `response.body?.toString(Charsets.UTF_8) ?: ""` — `ApiError.fromResponse` shows which one exists.)

- [ ] **Step 7.4: Run to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:test`
Expected: BUILD SUCCESSFUL — 6 new tests plus everything else green.

*(No commit — global constraint.)*

---
## Task 8 — bukkit — ClipboardUploadGuards + ClipboardUploadService + plugin wiring

**Files:**
- Create: `/Users/harrison/IdeaProjects/SchematioConnector/bukkit/src/main/kotlin/io/schemat/schematioConnector/ipc/ClipboardUploadGuards.kt`
- Create: `/Users/harrison/IdeaProjects/SchematioConnector/bukkit/src/main/kotlin/io/schemat/schematioConnector/ipc/ClipboardUploadService.kt`
- Modify: `/Users/harrison/IdeaProjects/SchematioConnector/bukkit/src/main/kotlin/io/schemat/schematioConnector/SchematioConnector.kt` (upload client wiring)
- Test: `/Users/harrison/IdeaProjects/SchematioConnector/bukkit/src/test/kotlin/io/schemat/schematioConnector/ipc/ClipboardUploadGuardsTest.kt`

**Interfaces:**
- Consumes: `StatusState` (B Task 3); `ClipboardUploadClient`/`ClipboardUploadOutcome` (Task 7); core `RateLimiter(maxRequests, windowMs)` with `tryAcquire(UUID): Int?`, `getWaitTimeSeconds(UUID): Int`, `removePlayer(UUID)`; `WorldEditUtil.getClipboard(player: Player): Clipboard?` and `WorldEditUtil.clipboardToByteArray(clipboard: Clipboard): ByteArray?` (both EXIST — `clipboardToByteArray` writes `BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC`); B's `SchematioConnector` fields `clipboardTransport` (private) + `clipboardResolveClient` and its `loadConfiguration()` teardown/setup blocks.
- Produces:

```kotlin
object ClipboardUploadGuards {
    const val UPLOAD_PERMISSION: String = "schematio.clipboard.upload"
    const val REQUESTS_PER_MINUTE: Int = 2
    const val WINDOW_MS: Long = 60_000L
    enum class Failure(val state: StatusState, val detail: String) {
        WORLDEDIT_MISSING, EMPTY_CLIPBOARD, NO_PERMISSION, NOT_ATTESTED
    }
    fun firstFailure(worldEditAvailable: Boolean, hasClipboard: Boolean, hasPermission: Boolean,
        requireAttested: Boolean, attested: Boolean): Failure?
    fun statusFor(outcome: ClipboardUploadOutcome): Pair<StatusState, String>?  // null for Created
}
class ClipboardUploadService(plugin: SchematioConnector) {
    sealed class Result {
        class Created(val draftId: String, val webUrl: String) : Result()
        class Failed(val state: StatusState, val detail: String, val notLinked: Boolean = false) : Result()
    }
    fun removePlayer(playerId: UUID)
    fun uploadCurrentClipboard(player: Player, requireAttested: Boolean, attested: Boolean,
        onResult: (Result) -> Unit)  // main thread in, main thread out, exactly once
}
SchematioConnector.clipboardUploadClient: ClipboardUploadClient?   // private set
SchematioConnector.clipboardUploadService: ClipboardUploadService  // lazy val
```

- [ ] **Step 8.1: Write the failing test**

`ClipboardUploadGuardsTest.kt` (pure JVM — no Bukkit types):

```kotlin
package io.schemat.schematioConnector.ipc

import io.schemat.connector.core.cache.RateLimiter
import io.schemat.connector.core.ipc.StatusState
import io.schemat.connector.core.modapi.ClipboardUploadOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class ClipboardUploadGuardsTest {

    @Test
    fun `guard order is spec order - WE, clipboard, permission, attestation`() {
        assertEquals(
            ClipboardUploadGuards.Failure.WORLDEDIT_MISSING,
            ClipboardUploadGuards.firstFailure(false, false, false, requireAttested = true, attested = false),
        )
        assertEquals(
            ClipboardUploadGuards.Failure.EMPTY_CLIPBOARD,
            ClipboardUploadGuards.firstFailure(true, false, false, requireAttested = true, attested = false),
        )
        assertEquals(
            ClipboardUploadGuards.Failure.NO_PERMISSION,
            ClipboardUploadGuards.firstFailure(true, true, false, requireAttested = true, attested = false),
        )
        assertEquals(
            ClipboardUploadGuards.Failure.NOT_ATTESTED,
            ClipboardUploadGuards.firstFailure(true, true, true, requireAttested = true, attested = false),
        )
        assertNull(
            ClipboardUploadGuards.firstFailure(true, true, true, requireAttested = true, attested = true),
        )
    }

    @Test
    fun `the standalone chat path never requires attestation`() {
        assertNull(
            ClipboardUploadGuards.firstFailure(true, true, true, requireAttested = false, attested = false),
        )
    }

    @Test
    fun `failure states map to the spec's terminal statuses`() {
        assertEquals(StatusState.UNAVAILABLE, ClipboardUploadGuards.Failure.WORLDEDIT_MISSING.state)
        assertEquals(StatusState.UNAVAILABLE, ClipboardUploadGuards.Failure.EMPTY_CLIPBOARD.state)
        assertEquals(StatusState.DENIED, ClipboardUploadGuards.Failure.NO_PERMISSION.state)
        assertEquals(StatusState.DENIED, ClipboardUploadGuards.Failure.NOT_ATTESTED.state)
    }

    @Test
    fun `token bucket allows 2 per minute then limits`() {
        val limiter = RateLimiter(
            maxRequests = ClipboardUploadGuards.REQUESTS_PER_MINUTE,
            windowMs = ClipboardUploadGuards.WINDOW_MS,
        )
        val player = UUID.randomUUID()
        repeat(2) { assertNotNull(limiter.tryAcquire(player), "request ${it + 1} should pass") }
        assertNull(limiter.tryAcquire(player), "3rd request within the window must be limited")
        assertNotNull(limiter.tryAcquire(UUID.randomUUID()), "other players unaffected")
    }

    @Test
    fun `upload outcomes map to exactly one terminal status`() {
        assertNull(ClipboardUploadGuards.statusFor(ClipboardUploadOutcome.Created("d", "url", "exp")))
        assertEquals(StatusState.DENIED, ClipboardUploadGuards.statusFor(ClipboardUploadOutcome.NotLinked)!!.first)
        assertEquals(StatusState.DENIED, ClipboardUploadGuards.statusFor(ClipboardUploadOutcome.Denied)!!.first)
        assertEquals(StatusState.DENIED, ClipboardUploadGuards.statusFor(ClipboardUploadOutcome.QuotaExceeded)!!.first)
        assertEquals(StatusState.TOO_LARGE, ClipboardUploadGuards.statusFor(ClipboardUploadOutcome.TooLarge)!!.first)
        assertEquals(StatusState.RATE_LIMITED, ClipboardUploadGuards.statusFor(ClipboardUploadOutcome.RateLimited)!!.first)
        assertEquals(StatusState.UNAVAILABLE, ClipboardUploadGuards.statusFor(ClipboardUploadOutcome.Unavailable)!!.first)
        assertEquals(StatusState.ERROR, ClipboardUploadGuards.statusFor(ClipboardUploadOutcome.Error)!!.first)
        // The quota detail must tell the user WHAT to do (it is not a retry-later case).
        assertTrue(ClipboardUploadGuards.statusFor(ClipboardUploadOutcome.QuotaExceeded)!!.second.contains("publish or delete", ignoreCase = true))
    }
}
```

- [ ] **Step 8.2: Run to verify compilation fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :bukkit:test`
Expected: compilation FAILS (no `ClipboardUploadGuards`).

- [ ] **Step 8.3: Implement**

`ClipboardUploadGuards.kt` (new file — deliberately Bukkit-free so it runs under plain JUnit):

```kotlin
package io.schemat.schematioConnector.ipc

import io.schemat.connector.core.ipc.StatusState
import io.schemat.connector.core.modapi.ClipboardUploadOutcome

/**
 * Pure decision logic for clipboard-draft uploads (spec: guard order, 2/min token
 * bucket, outcome -> STATUS mapping). Kept Bukkit-free so it runs under plain JUnit.
 */
object ClipboardUploadGuards {

    /** plugin.yml permission node, default true (spec). */
    const val UPLOAD_PERMISSION: String = "schematio.clipboard.upload"

    /** Per-player upload budget, shared by the IPC and chat paths (spec: 2/min). */
    const val REQUESTS_PER_MINUTE: Int = 2
    const val WINDOW_MS: Long = 60_000L

    enum class Failure(val state: StatusState, val detail: String) {
        WORLDEDIT_MISSING(StatusState.UNAVAILABLE, "WorldEdit is not installed on this server"),
        EMPTY_CLIPBOARD(StatusState.UNAVAILABLE, "Your WorldEdit clipboard is empty — //copy something first"),
        NO_PERMISSION(StatusState.DENIED, "Missing permission schematio.clipboard.upload"),
        NOT_ATTESTED(StatusState.DENIED, "Session is not attested; rejoin the server"),
    }

    /**
     * First failing guard, in spec order: WE present -> non-empty clipboard ->
     * permission -> attested (IPC path only; the chat command has no session to
     * attest). The rate limit is checked by the caller AFTER these pass, so denied
     * requests never consume bucket slots.
     */
    fun firstFailure(
        worldEditAvailable: Boolean,
        hasClipboard: Boolean,
        hasPermission: Boolean,
        requireAttested: Boolean,
        attested: Boolean,
    ): Failure? = when {
        !worldEditAvailable -> Failure.WORLDEDIT_MISSING
        !hasClipboard -> Failure.EMPTY_CLIPBOARD
        !hasPermission -> Failure.NO_PERMISSION
        requireAttested && !attested -> Failure.NOT_ATTESTED
        else -> null
    }

    /** Terminal STATUS for a backend outcome; null for Created (the caller answers DRAFT_CREATED). */
    fun statusFor(outcome: ClipboardUploadOutcome): Pair<StatusState, String>? = when (outcome) {
        is ClipboardUploadOutcome.Created -> null
        ClipboardUploadOutcome.NotLinked -> StatusState.DENIED to "No schemat.io account is linked to your Minecraft account"
        ClipboardUploadOutcome.Denied -> StatusState.DENIED to "The site denied the draft upload"
        ClipboardUploadOutcome.QuotaExceeded -> StatusState.DENIED to "Draft limit reached (10) — publish or delete drafts on schemat.io first"
        ClipboardUploadOutcome.TooLarge -> StatusState.TOO_LARGE to "Clipboard exceeds the 8 MiB limit"
        ClipboardUploadOutcome.RateLimited -> StatusState.RATE_LIMITED to "The site rate-limited this server; try again shortly"
        ClipboardUploadOutcome.Unavailable -> StatusState.UNAVAILABLE to "The schemat.io backend is unreachable"
        ClipboardUploadOutcome.Error -> StatusState.ERROR to "Unexpected backend response"
    }
}
```

`ClipboardUploadService.kt` (new file, complete):

```kotlin
package io.schemat.schematioConnector.ipc

import io.schemat.connector.core.cache.RateLimiter
import io.schemat.connector.core.ipc.StatusState
import io.schemat.connector.core.modapi.ClipboardUploadClient
import io.schemat.connector.core.modapi.ClipboardUploadOutcome
import io.schemat.schematioConnector.SchematioConnector
import io.schemat.schematioConnector.utils.WorldEditUtil
import kotlinx.coroutines.runBlocking
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Shared "upload my server-side WE clipboard as a draft" flow for BOTH entry points
 * (IPC UPLOAD_CLIPBOARD and /schematio upload). Spec discipline:
 * guards -> rate limit -> MAIN-THREAD clipboard snapshot -> ASYNC serialize + POST ->
 * main-thread callback (exactly once). User tokens NEVER appear here — the HTTP layer
 * is the community-token ClipboardUploadClient (spec invariant 1).
 */
class ClipboardUploadService(private val plugin: SchematioConnector) {

    sealed class Result {
        class Created(val draftId: String, val webUrl: String) : Result()
        class Failed(val state: StatusState, val detail: String, val notLinked: Boolean = false) : Result()
    }

    /** One bucket for both entry points — a player can't dodge the limit by mixing paths. */
    private val limiter = RateLimiter(
        maxRequests = ClipboardUploadGuards.REQUESTS_PER_MINUTE,
        windowMs = ClipboardUploadGuards.WINDOW_MS,
    )

    /** WorldEdit is a soft dependency — never touch its classes unless present. */
    private val worldEditAvailable: Boolean =
        runCatching { Class.forName("com.sk89q.worldedit.WorldEdit") }.isSuccess

    fun removePlayer(playerId: UUID) = limiter.removePlayer(playerId)

    /** Main thread only. [onResult] is invoked exactly once, back on the main thread. */
    fun uploadCurrentClipboard(
        player: Player,
        requireAttested: Boolean,
        attested: Boolean,
        onResult: (Result) -> Unit,
    ) {
        // MAIN-THREAD SNAPSHOT: the WE session API is main-thread; the Clipboard
        // reference captured here is the snapshot — only serialization goes async (spec).
        val clipboard = if (worldEditAvailable) WorldEditUtil.getClipboard(player) else null

        val guard = ClipboardUploadGuards.firstFailure(
            worldEditAvailable = worldEditAvailable,
            hasClipboard = clipboard != null,
            hasPermission = player.hasPermission(ClipboardUploadGuards.UPLOAD_PERMISSION),
            requireAttested = requireAttested,
            attested = attested,
        )
        if (guard != null) {
            onResult(Result.Failed(guard.state, guard.detail))
            return
        }
        if (limiter.tryAcquire(player.uniqueId) == null) {
            val wait = limiter.getWaitTimeSeconds(player.uniqueId)
            onResult(Result.Failed(StatusState.RATE_LIMITED, "Too many clipboard uploads; retry in ${wait}s"))
            return
        }
        val client = plugin.clipboardUploadClient
        if (client == null) {
            onResult(Result.Failed(StatusState.UNAVAILABLE, "The plugin is not connected to schemat.io"))
            return
        }

        val playerUuid = player.uniqueId.toString()
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val bytes = WorldEditUtil.clipboardToByteArray(clipboard!!)
            val result: Result = when {
                bytes == null ->
                    Result.Failed(StatusState.ERROR, "Could not serialize your clipboard")
                bytes.size > ClipboardUploadClient.MAX_UPLOAD_BYTES ->
                    Result.Failed(StatusState.TOO_LARGE, "Clipboard exceeds the 8 MiB limit")
                else -> when (val outcome = runBlocking { client.upload(playerUuid, bytes) }) {
                    is ClipboardUploadOutcome.Created -> Result.Created(outcome.draftId, outcome.webUrl)
                    else -> {
                        val (state, detail) = ClipboardUploadGuards.statusFor(outcome)!!
                        Result.Failed(state, detail, notLinked = outcome == ClipboardUploadOutcome.NotLinked)
                    }
                }
            }
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (player.isOnline) onResult(result)
            })
        })
    }
}
```

`SchematioConnector.kt` — three edits (anchors reference sub-project B's final state of this file):

(a) Next to B's `clipboardResolveClient` property, add:

```kotlin
    // Server clipboard -> backend draft uploads (IPC sub-project C). Shares B's
    // clipboardTransport; its response cap easily fits the small draft-JSON reply.
    var clipboardUploadClient: io.schemat.connector.core.modapi.ClipboardUploadClient? = null
        private set

    /** Shared draft-upload flow for the IPC handler and /schematio upload. */
    val clipboardUploadService: io.schemat.schematioConnector.ipc.ClipboardUploadService by lazy {
        io.schemat.schematioConnector.ipc.ClipboardUploadService(this)
    }
```

(b) In `loadConfiguration()`: the early-return teardown block that already nulls `clipboardResolveClient` (B) additionally gets:

```kotlin
        clipboardUploadClient = null
```

and immediately after B's `clipboardResolveClient = ...` assignment, add:

```kotlin
        clipboardUploadClient = io.schemat.connector.core.modapi.ClipboardUploadClient(clipboardTransport!!) {
            communityToken.takeIf { it.isNotEmpty() }
        }
```

(c) No `onDisable()` change — B already closes `clipboardTransport`, which both clients share.

- [ ] **Step 8.4: Run to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :bukkit:test`
Expected: BUILD SUCCESSFUL — 5 new tests plus B's `LoadRequestGuardsTest`, A's `PluginIpcAttestGateTest`, and the vcs tests green.

*(No commit — global constraint.)*

---

## Task 9 — bukkit — UPLOAD_CLIPBOARD handler, dynamic capabilities, `/schematio upload` rewrite, permission node

**Files:**
- Modify: `/Users/harrison/IdeaProjects/SchematioConnector/bukkit/src/main/kotlin/io/schemat/schematioConnector/ipc/PluginIpcService.kt`
- Modify: `/Users/harrison/IdeaProjects/SchematioConnector/bukkit/src/main/kotlin/io/schemat/schematioConnector/commands/UploadSubcommand.kt` (full rewrite below)
- Modify: `/Users/harrison/IdeaProjects/SchematioConnector/bukkit/src/main/resources/plugin.yml` (permission node)
- Test: `/Users/harrison/IdeaProjects/SchematioConnector/bukkit/src/test/kotlin/io/schemat/schematioConnector/ipc/PluginIpcCapabilitiesTest.kt`

**Interfaces:**
- Consumes: `UploadClipboard`/`DraftCreated`/`IpcCodec.decodeUploadClipboard/encodeDraftCreated` (Task 6); `ClipboardUploadService`/`ClipboardUploadGuards` (Task 8); B's `PluginIpcService` final state (fields `greeted`/`attested`/`loadLimiter`/`worldEditAvailable`, `private val capabilities`, `onPluginMessageReceived` `when`, `sendStatus(player, requestId, state, detail)`, `greet()`); `Subcommand` interface (`name/permission/description/execute(sender: Player, args): Boolean`); the `SchematioCommand` router (checks `sender.hasPermission(subcommand.permission)` BEFORE `execute` — the chat path's permission guard); `CommandSender.audience()` extension (in `commands/SchematioCommand.kt`); `plugin.baseUrl`.
- Produces: opcode 7 handled (attested-only) → `DRAFT_CREATED` or one terminal `STATUS`; `PluginIpcService.Companion.capabilitiesFor(worldEditAvailable: Boolean, uploadConfigured: Boolean): Int` (pure, tested) with `HELLO_SERVER` capabilities computed per-greet; `/schematio upload` = draft flow with clickable web link; bukkit permission `schematio.clipboard.upload` default `true`.

- [ ] **Step 9.1: Write the failing test**

`PluginIpcCapabilitiesTest.kt` (pure JVM — no Bukkit types):

```kotlin
package io.schemat.schematioConnector.ipc

import io.schemat.connector.core.ipc.Capabilities
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PluginIpcCapabilitiesTest {

    @Test
    fun `UPLOAD is advertised only with WorldEdit AND a configured backend`() {
        // Spec: "advertised only when WorldEdit + backend configured".
        assertTrue(Capabilities.has(PluginIpcService.capabilitiesFor(true, true), Capabilities.UPLOAD))
        assertFalse(Capabilities.has(PluginIpcService.capabilitiesFor(true, false), Capabilities.UPLOAD))
        assertFalse(Capabilities.has(PluginIpcService.capabilitiesFor(false, true), Capabilities.UPLOAD))
        assertFalse(Capabilities.has(PluginIpcService.capabilitiesFor(false, false), Capabilities.UPLOAD))
    }

    @Test
    fun `existing bits are unchanged`() {
        val caps = PluginIpcService.capabilitiesFor(worldEditAvailable = true, uploadConfigured = true)
        assertTrue(Capabilities.has(caps, Capabilities.DOWNLOAD_CMD))
        assertTrue(Capabilities.has(caps, Capabilities.WANTS_COMMAND_OWNERSHIP))
        assertTrue(Capabilities.has(caps, Capabilities.LOAD_CLIPBOARD))
        assertFalse(Capabilities.has(PluginIpcService.capabilitiesFor(false, false), Capabilities.LOAD_CLIPBOARD))
    }
}
```

- [ ] **Step 9.2: Run to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :bukkit:test`
Expected: compilation FAILS (`capabilitiesFor` unresolved).

- [ ] **Step 9.3: Implement PluginIpcService changes**

All edits are relative to sub-project B's final version of the file.

(a) Add imports:

```kotlin
import io.schemat.connector.core.ipc.DraftCreated
import io.schemat.connector.core.ipc.UploadClipboard
```

(b) REPLACE the `private val capabilities: Int = ...` property (B's static computation) with:

```kotlin
    /**
     * Capabilities advertised in HELLO_SERVER, computed per-greet: UPLOAD depends on
     * the backend connection, which races plugin startup (spec: "advertised only when
     * WorldEdit + backend configured").
     */
    private fun currentCapabilities(): Int =
        capabilitiesFor(worldEditAvailable, plugin.clipboardUploadClient != null)
```

and in `greet(player)`, change the `HelloServer` construction line `capabilities = capabilities,` to:

```kotlin
            capabilities = currentCapabilities(),
```

(c) In the `companion object` (which already holds `wantsAttestation`), add:

```kotlin
        /** Pure gate for tests: which capability bits a build/config combination advertises. */
        fun capabilitiesFor(worldEditAvailable: Boolean, uploadConfigured: Boolean): Int =
            Capabilities.DOWNLOAD_CMD or
                Capabilities.WANTS_COMMAND_OWNERSHIP or
                (if (worldEditAvailable) Capabilities.LOAD_CLIPBOARD else 0) or
                (if (worldEditAvailable && uploadConfigured) Capabilities.UPLOAD else 0)
```

(d) In `onPluginMessageReceived`'s `when`, after the `IpcOpcode.LOAD_REQUEST` branch, add:

```kotlin
                IpcOpcode.UPLOAD_CLIPBOARD -> handleUploadClipboard(player, IpcCodec.decodeUploadClipboard(message))
```

(e) In `onQuit`, next to `loadLimiter.removePlayer(...)`, add:

```kotlin
        plugin.clipboardUploadService.removePlayer(event.player.uniqueId)
```

(f) Add the handler + sender (place them after `finishLoadRequest`, before `sendStatus`):

```kotlin
    // ---- UPLOAD_CLIPBOARD (server clipboard -> backend draft, sub-project C) ----

    /**
     * The IPC path REQUIRES an attested session (spec). Every request gets exactly one
     * reply: DRAFT_CREATED on success, or one terminal STATUS. The draft id is the ONLY
     * thing the client trusts from this server — it re-fetches the draft with the
     * USER's own auth and checks ownership before opening any UI.
     */
    private fun handleUploadClipboard(player: Player, msg: UploadClipboard) {
        plugin.clipboardUploadService.uploadCurrentClipboard(
            player,
            requireAttested = true,
            attested = attested.contains(player.uniqueId),
        ) { result ->
            when (result) {
                is ClipboardUploadService.Result.Created ->
                    sendDraftCreated(player, msg.requestId, result.draftId)
                is ClipboardUploadService.Result.Failed ->
                    sendStatus(player, msg.requestId, result.state, result.detail)
            }
        }
    }

    /** Main thread only. */
    private fun sendDraftCreated(player: Player, requestId: Int, draftId: String) {
        val msg = DraftCreated(IpcProtocol.VERSION, requestId, draftId)
        player.sendPluginMessage(plugin, IpcProtocol.CHANNEL, IpcCodec.encodeDraftCreated(msg))
    }
```

- [ ] **Step 9.4: Rewrite UploadSubcommand**

`UploadSubcommand.kt` — full new content. This DELETES the legacy direct-upload path (old `HttpUtil` multipart to `/schematics/upload`, `InputValidator`, `plugin.rateLimiter`, the community-membership error UI) and keeps the uuid-v3-aware account-link hint:

```kotlin
package io.schemat.schematioConnector.commands

import io.schemat.schematioConnector.SchematioConnector
import io.schemat.schematioConnector.ipc.ClipboardUploadService
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player

/**
 * Uploads the player's current WorldEdit clipboard to schemat.io as a DRAFT
 * (IPC sub-project C, standalone path — works without the client mod).
 *
 * Drives the same [ClipboardUploadService] as the IPC handler: guards, 2/min token
 * bucket, main-thread snapshot, async serialize + community-token POST. The result
 * is a clickable web link to finish labelling in the browser; the draft expires in
 * 48h if left unfinished. No user credentials are involved anywhere in this flow.
 *
 * Usage: /schematio upload
 */
class UploadSubcommand(private val plugin: SchematioConnector) : Subcommand {

    override val name = "upload"

    // The router checks this before execute() — the guard object re-checks it for
    // the IPC path, where there is no router.
    override val permission = io.schemat.schematioConnector.ipc.ClipboardUploadGuards.UPLOAD_PERMISSION

    override val description = "Upload your clipboard to schemat.io as a draft"

    override fun execute(player: Player, args: Array<out String>): Boolean {
        val audience = player.audience()
        audience.sendMessage(Component.text("Uploading your clipboard as a draft...").color(NamedTextColor.YELLOW))

        plugin.clipboardUploadService.uploadCurrentClipboard(
            player,
            requireAttested = false, // standalone chat path — no IPC session to attest
            attested = false,
        ) { result ->
            when (result) {
                is ClipboardUploadService.Result.Created -> showDraftLink(player, result.webUrl)
                is ClipboardUploadService.Result.Failed ->
                    if (result.notLinked) {
                        showAccountNotLinkedError(player)
                    } else {
                        player.audience().sendMessage(Component.text(result.detail).color(NamedTextColor.RED))
                    }
            }
        }

        return true
    }

    private fun showDraftLink(player: Player, url: String) {
        val audience = player.audience()

        audience.sendMessage(Component.text("Draft created!").color(NamedTextColor.GREEN))
        audience.sendMessage(
            Component.text("Finish it in your browser: ").color(NamedTextColor.GRAY)
                .append(
                    Component.text("[Complete your upload]")
                        .color(NamedTextColor.AQUA)
                        .decorate(TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.openUrl(url))
                        .hoverEvent(HoverEvent.showText(Component.text("Open the draft upload page"))),
                ),
        )
        audience.sendMessage(
            Component.text("[Click to copy link]")
                .color(NamedTextColor.YELLOW)
                .clickEvent(ClickEvent.copyToClipboard(url))
                .hoverEvent(HoverEvent.showText(Component.text("Copy link to clipboard"))),
        )
        audience.sendMessage(
            Component.text("The draft expires in 48 hours if left unfinished.").color(NamedTextColor.GRAY),
        )
    }

    /**
     * Shown when the backend can't match the uploader to an account (400 player_not_linked).
     * Two very different causes, told apart by the UUID version:
     *   - v3 (name-based UUID) -> an offline-mode / unauthenticated client. schemat.io can
     *     never match this to a real account — the real fix is to join in online mode.
     *   - v4 (random UUID) -> a genuine Mojang identity with no schemat.io account yet;
     *     point them at the site to sign up (clickable link).
     */
    private fun showAccountNotLinkedError(player: Player) {
        val audience = player.audience()
        if (player.uniqueId.version() == 3) {
            audience.sendMessage(Component.text("We couldn't verify your Minecraft account.").color(NamedTextColor.RED))
            audience.sendMessage(
                Component.text(
                    "This server is in offline mode, so schemat.io can't confirm who you are. " +
                        "Join from an online-mode (Mojang-authenticated) client to upload.",
                ).color(NamedTextColor.GRAY),
            )
        } else {
            val url = plugin.baseUrl.ifBlank { "https://schemat.io" }
            audience.sendMessage(Component.text("No schemat.io account is linked to your Minecraft yet.").color(NamedTextColor.RED))
            audience.sendMessage(
                Component.text("Sign up free and link your account at ").color(NamedTextColor.GRAY)
                    .append(
                        Component.text(url).color(NamedTextColor.AQUA)
                            .decorate(TextDecoration.UNDERLINED)
                            .clickEvent(ClickEvent.openUrl(url))
                            .hoverEvent(HoverEvent.showText(Component.text("Open schemat.io"))),
                    )
                    .append(Component.text(", then try again.").color(NamedTextColor.GRAY)),
            )
        }
    }

    override fun tabComplete(player: Player, args: Array<out String>): List<String> = emptyList()
}
```

`plugin.yml` — add under the feature permissions, right after B's `schematio.clipboard.load` node:

```yaml
    schematio.clipboard.upload:
        description: Upload your server-side WorldEdit clipboard to schemat.io as a draft
        default: true
```

(the old `schematio.upload` node stays — resolved ambiguity 7.)

- [ ] **Step 9.5: Run to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :bukkit:test`
Expected: BUILD SUCCESSFUL — 2 new tests, everything else green. Then `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :bukkit:build` — shadowJar compiles with the legacy upload path gone.

*(No commit — global constraint.)*

---
## Task 10 — fabric — ClipboardUploadTracker + draft-ownership check

**Files:**
- Create: `/Users/harrison/IdeaProjects/SchematioConnector/fabric/src/client/kotlin/io/schemat/connector/fabric/client/ipc/ClipboardUploadTracker.kt`
- Test: `/Users/harrison/IdeaProjects/SchematioConnector/fabric/src/test/kotlin/io/schemat/connector/fabric/client/ipc/ClipboardUploadTrackerTest.kt`

**Interfaces:**
- Consumes: `StatusState` (B Task 3); `sanitizeDetail(detail: String): String` (B Task 6, file-level fun in `ClipboardLoadTracker.kt`, same package). No Minecraft classes — headless-testable.
- Produces:

```kotlin
fun isDraftOwnedBy(authorUuids: List<String>, playerUuid: String?): Boolean  // dash/case-insensitive

object ClipboardUploadTracker {
    const val TIMEOUT_MS: Long = 30_000
    const val ID_BASE: Int = 1_000_000  // disjoint from ClipboardLoadTracker's ids (ambiguity 8)
    fun register(onStatus: (StatusState, String) -> Unit, onDraft: (String) -> Unit,
        nowMs: Long = System.currentTimeMillis()): Int
    fun onStatus(requestId: Int, state: StatusState, detail: String, nowMs: Long = System.currentTimeMillis())
    fun onDraft(requestId: Int, draftId: String)
    fun tick(nowMs: Long = System.currentTimeMillis())  // expires overdue requests with synthetic ERROR
    fun pendingCount(): Int
    fun reset()
}
```

- [ ] **Step 10.1: Write the failing test**

`ClipboardUploadTrackerTest.kt`:

```kotlin
package io.schemat.connector.fabric.client.ipc

import io.schemat.connector.core.ipc.StatusState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ClipboardUploadTrackerTest {

    private val statuses = mutableListOf<Pair<StatusState, String>>()
    private var draftId: String? = null
    private val onStatus: (StatusState, String) -> Unit = { s, d -> statuses += s to d }
    private val onDraft: (String) -> Unit = { draftId = it }

    @BeforeEach
    fun setUp() {
        ClipboardUploadTracker.reset()
        statuses.clear()
        draftId = null
    }

    @AfterEach
    fun tearDown() = ClipboardUploadTracker.reset()

    @Test
    fun `register issues distinct ids from the reserved range`() {
        val a = ClipboardUploadTracker.register(onStatus, onDraft)
        val b = ClipboardUploadTracker.register(onStatus, onDraft)
        assertNotEquals(a, b)
        assertTrue(a >= ClipboardUploadTracker.ID_BASE, "ids must never collide with the load tracker's")
        assertEquals(2, ClipboardUploadTracker.pendingCount())
    }

    @Test
    fun `onDraft completes the request exactly once`() {
        val id = ClipboardUploadTracker.register(onStatus, onDraft)
        ClipboardUploadTracker.onDraft(id, "draft-1")
        assertEquals("draft-1", draftId)
        assertEquals(0, ClipboardUploadTracker.pendingCount())
        draftId = null
        ClipboardUploadTracker.onDraft(id, "draft-2") // already completed: no-op
        assertNull(draftId)
    }

    @Test
    fun `a terminal STATUS completes the request and sanitizes the detail`() {
        val id = ClipboardUploadTracker.register(onStatus, onDraft)
        ClipboardUploadTracker.onStatus(id, StatusState.DENIED, "§cnot §lallowed")
        assertEquals(listOf(StatusState.DENIED to "not allowed"), statuses)
        assertEquals(0, ClipboardUploadTracker.pendingCount())
    }

    @Test
    fun `unknown requestIds are ignored (STATUS frames are shared with the load tracker)`() {
        ClipboardUploadTracker.onStatus(1, StatusState.OK, "") // a load-tracker id
        ClipboardUploadTracker.onDraft(1, "not-ours")
        assertTrue(statuses.isEmpty())
        assertNull(draftId)
    }

    @Test
    fun `tick expires overdue requests with a synthetic ERROR`() {
        val now = 1_000L
        ClipboardUploadTracker.register(onStatus, onDraft, nowMs = now)
        ClipboardUploadTracker.tick(now + ClipboardUploadTracker.TIMEOUT_MS - 1)
        assertTrue(statuses.isEmpty())
        ClipboardUploadTracker.tick(now + ClipboardUploadTracker.TIMEOUT_MS)
        assertEquals(1, statuses.size)
        assertEquals(StatusState.ERROR, statuses.single().first)
        assertEquals(0, ClipboardUploadTracker.pendingCount())
    }

    @Test
    fun `ownership check is dash- and case-insensitive`() {
        val authors = listOf("AABBCCDD-1122-3344-5566-778899AABBCC")
        assertTrue(isDraftOwnedBy(authors, "aabbccdd-1122-3344-5566-778899aabbcc"))
        assertTrue(isDraftOwnedBy(authors, "aabbccdd11223344556677" + "8899aabbcc"))
        assertFalse(isDraftOwnedBy(authors, "00000000-0000-0000-0000-000000000000"))
        assertFalse(isDraftOwnedBy(authors, null))
        assertFalse(isDraftOwnedBy(emptyList(), "aabbccdd-1122-3344-5566-778899aabbcc"))
    }
}
```

- [ ] **Step 10.2: Run to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:1.21.11:test`
Expected: compilation FAILS (no `ClipboardUploadTracker`).

- [ ] **Step 10.3: Implement**

`ClipboardUploadTracker.kt` (new file, complete):

```kotlin
package io.schemat.connector.fabric.client.ipc

import io.schemat.connector.core.ipc.StatusState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Dash/case-insensitive draft-ownership check (spec invariant 4: the client refuses
 * drafts not owned by the current user — a malicious server handing someone else's
 * draft id gets an error and nothing more).
 */
fun isDraftOwnedBy(authorUuids: List<String>, playerUuid: String?): Boolean {
    val me = playerUuid?.lowercase()?.replace("-", "")?.takeIf { it.isNotEmpty() } ?: return false
    return authorUuids.any { it.lowercase().replace("-", "") == me }
}

/**
 * Pending UPLOAD_CLIPBOARD requests: DRAFT_CREATED completes them, terminal STATUS
 * fails them, [tick] times them out with a synthetic ERROR after [TIMEOUT_MS] (spec).
 * Mirrors [ClipboardLoadTracker]; kept free of Minecraft classes for headless tests.
 */
object ClipboardUploadTracker {

    const val TIMEOUT_MS: Long = 30_000

    /**
     * Upload requestIds start here: [ClipboardLoadTracker] issues ids from 1 upward and
     * STATUS frames are dispatched to BOTH trackers (STATUS is generic — contract C2),
     * so the id spaces must stay disjoint within a session (resolved ambiguity 8).
     */
    const val ID_BASE: Int = 1_000_000

    private class Pending(
        val onStatus: (StatusState, String) -> Unit,
        val onDraft: (String) -> Unit,
        @Volatile var deadlineMs: Long,
    )

    private val nextId = AtomicInteger(ID_BASE)
    private val pending = ConcurrentHashMap<Int, Pending>()

    fun register(
        onStatus: (StatusState, String) -> Unit,
        onDraft: (String) -> Unit,
        nowMs: Long = System.currentTimeMillis(),
    ): Int {
        val id = nextId.getAndIncrement()
        pending[id] = Pending(onStatus, onDraft, nowMs + TIMEOUT_MS)
        return id
    }

    fun onStatus(requestId: Int, state: StatusState, detail: String, nowMs: Long = System.currentTimeMillis()) {
        val entry = (if (state.isTerminal) pending.remove(requestId) else pending[requestId]) ?: return
        if (!state.isTerminal) entry.deadlineMs = nowMs + TIMEOUT_MS
        entry.onStatus(state, sanitizeDetail(detail))
    }

    fun onDraft(requestId: Int, draftId: String) {
        val entry = pending.remove(requestId) ?: return
        entry.onDraft(draftId)
    }

    /** Called from the client tick: expires overdue requests with a synthetic ERROR. */
    fun tick(nowMs: Long = System.currentTimeMillis()) {
        for ((id, entry) in pending) {
            if (nowMs >= entry.deadlineMs && pending.remove(id) != null) {
                entry.onStatus(StatusState.ERROR, "Timed out waiting for the server")
            }
        }
    }

    fun pendingCount(): Int = pending.size

    fun reset() = pending.clear()
}
```

- [ ] **Step 10.4: Run to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:1.21.11:test`
Expected: BUILD SUCCESSFUL — 6 new tests plus B's `ClipboardLoadTrackerTest` and the rest green.

*(No commit — global constraint.)*

---

## Task 11 — fabric — ServerIpc wiring, ClipboardUploadFlow, wizard complete-draft mode, toolbar action

**Files:**
- Modify: `/Users/harrison/IdeaProjects/SchematioConnector/fabric/src/client/kotlin/io/schemat/connector/fabric/client/ipc/ServerIpc.kt`
- Create: `/Users/harrison/IdeaProjects/SchematioConnector/fabric/src/client/kotlin/io/schemat/connector/fabric/client/ipc/ClipboardUploadFlow.kt`
- Modify: `/Users/harrison/IdeaProjects/SchematioConnector/fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/panels/UploadWizardPanel.kt`
- Modify: `/Users/harrison/IdeaProjects/SchematioConnector/fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/panels/upload/UploadDetailsStep.kt`
- Modify: `/Users/harrison/IdeaProjects/SchematioConnector/fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/panels/upload/UploadConfirmStep.kt`
- Modify: `/Users/harrison/IdeaProjects/SchematioConnector/fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/panels/upload/UploadSubmit.kt`
- Modify: `/Users/harrison/IdeaProjects/SchematioConnector/fabric/src/client/kotlin/io/schemat/connector/fabric/client/ui/framework/Toolbar.kt`
- Modify: `/Users/harrison/IdeaProjects/SchematioConnector/fabric/src/client/kotlin/io/schemat/connector/fabric/client/SchematioClientMod.kt` (tick + reset wiring)

**Interfaces:**
- Consumes: `IpcCodec.encodeUploadClipboard/decodeDraftCreated`, `UploadClipboard`, `Capabilities.UPLOAD` (Task 6); `ClipboardUploadTracker`/`isDraftOwnedBy` (Task 10); A's `ServerSession.trust`/`TrustState.VERIFIED`/`ServerSession.capabilities`; B's STATUS branch in `ServerIpc.handle()` and `sendLoadRequest` shape (`SchematioPayload`, `ClientPlayNetworking.canSend/send`); `ClientServices.call(busy, block, onResult)` (`ui/foundation/UiDispatch.kt`), `toUserMessage()`; `ChatNotice.success(message, url, linkLabel)/info(message)/error(message)`; `services.cached.schematic(id): ApiResult<SchematicDetail>`, `updateSchematic(id, name?, description?, isPublic?): ApiResult<SchematicDetail>`, `setTags(id, tagIds: List<String>, tagFilters: Map<Long, String>): ApiResult<List<TagNode>>`, `addCoAuthor(id, playerUuid): ApiResult<Unit>`; `SchematicDetail` (fields `id, shortId, name, format, isPublic, authors: List<AuthorInfo(uuid, …)>, webUrl`); `UploadWizardPanel` internals (`nameBuf`, `descEditor`, `visibilityBuf`, `isPublic`, `coAuthorPicker`, `selectedTagIds`, `selectedTagFilters`, `selectedCommunity`, `uploadBusy`, `step`/`Step`, `reset()`, `renderNavButtons`, `webLink(detail)` in the upload package); `Icons.UPLOAD`; `PanelManager.open/close`; `BrowsePanel.invalidate()`/`MySchematicsPanel.invalidate()`.
- Produces:

```kotlin
// ServerIpc.kt
fun canUploadClipboard(): Boolean  // trust == VERIFIED && Capabilities.UPLOAD bit
fun sendUploadClipboard(onStatus: (StatusState, String) -> Unit, onDraft: (String) -> Unit): Int?
// handle(): STATUS dispatched to BOTH trackers; DRAFT_CREATED -> ClipboardUploadTracker.onDraft

// ClipboardUploadFlow.kt
object ClipboardUploadFlow { fun start(); fun isBusy(): Boolean }

// UploadWizardPanel
internal var completingDraft: SchematicDetail?
fun openCompleteDraft(detail: SchematicDetail)   // skips SOURCE, prefills, opens at DETAILS
internal fun UploadWizardPanel.completeDraftSubmit()  // in UploadSubmit.kt
```

- [ ] **Step 11.1: Implement ServerIpc changes**

(a) Add imports:

```kotlin
import io.schemat.connector.core.ipc.UploadClipboard
```

(b) In `handle()`'s `when`, REPLACE B's `IpcOpcode.STATUS` branch body with the dual dispatch, and add the `DRAFT_CREATED` branch after it:

```kotlin
                IpcOpcode.STATUS -> {
                    val status = IpcCodec.decodeStatus(data)
                    val state = StatusState.fromWire(status.state) ?: return
                    // STATUS is generic (contract C2): the load and upload trackers issue
                    // ids from disjoint ranges, so exactly one of these reacts.
                    ClipboardLoadTracker.onStatus(status.requestId, state, status.detail)
                    ClipboardUploadTracker.onStatus(status.requestId, state, status.detail)
                }
                IpcOpcode.DRAFT_CREATED -> {
                    val msg = IpcCodec.decodeDraftCreated(data)
                    ClipboardUploadTracker.onDraft(msg.requestId, msg.draftId)
                }
```

(c) After `sendLoadRequest`, add:

```kotlin
    /** "Upload clipboard" is offered ONLY on VERIFIED sessions advertising UPLOAD (spec). */
    fun canUploadClipboard(): Boolean =
        ServerSession.trust == TrustState.VERIFIED &&
            Capabilities.has(ServerSession.capabilities, Capabilities.UPLOAD)

    /**
     * Asks the server to push ITS copy of the player's WE clipboard to the backend as
     * a draft (opcode 7 — carries only a requestId; no bytes ever travel on the MC
     * channel). [onDraft] receives the draftId; [onStatus] receives failures plus the
     * tracker's 30 s synthetic ERROR. Returns the requestId, or null without sending
     * when the session is not VERIFIED+capable or the channel is not sendable.
     */
    fun sendUploadClipboard(
        onStatus: (StatusState, String) -> Unit,
        onDraft: (String) -> Unit,
    ): Int? {
        if (!canUploadClipboard()) return null
        if (!ClientPlayNetworking.canSend(SchematioPayload.TYPE)) return null
        val requestId = ClipboardUploadTracker.register(onStatus, onDraft)
        val bytes = IpcCodec.encodeUploadClipboard(UploadClipboard(IpcProtocol.VERSION, requestId))
        ClientPlayNetworking.send(SchematioPayload(bytes))
        return requestId
    }
```

- [ ] **Step 11.2: Implement ClipboardUploadFlow**

`ClipboardUploadFlow.kt` (new file, complete):

```kotlin
package io.schemat.connector.fabric.client.ipc

import io.schemat.connector.core.modapi.ApiResult
import io.schemat.connector.fabric.client.SchematioClientMod
import io.schemat.connector.fabric.client.services.ChatNotice
import io.schemat.connector.fabric.client.services.ClientServices
import io.schemat.connector.fabric.client.ui.foundation.call
import io.schemat.connector.fabric.client.ui.foundation.toUserMessage
import io.schemat.connector.fabric.client.ui.panels.UploadWizardPanel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "Upload clipboard" flow (spec §Client): UPLOAD_CLIPBOARD -> DRAFT_CREATED ->
 * re-fetch the draft with the USER's OWN auth -> ownership check -> wizard in
 * complete-draft mode. The server-supplied draft id is never trusted: a draft not
 * owned by the current user produces an error and nothing else (spec invariant 4).
 */
object ClipboardUploadFlow {

    private val services: ClientServices get() = SchematioClientMod.instance.services
    private val busy = AtomicBoolean(false)

    fun isBusy(): Boolean = busy.get()

    fun start() {
        if (!busy.compareAndSet(false, true)) return
        val requestId = ServerIpc.sendUploadClipboard(
            onStatus = { state, detail ->
                if (state.isTerminal) {
                    busy.set(false)
                    ChatNotice.error(detail.ifBlank { "Clipboard upload failed (${state.name})" })
                }
            },
            onDraft = { draftId -> fetchAndOpen(draftId) },
        )
        if (requestId == null) {
            busy.set(false)
            ChatNotice.error("No verified Schematio server connection")
        } else {
            ChatNotice.info("Uploading your server-side clipboard as a draft…")
        }
    }

    private fun fetchAndOpen(draftId: String) {
        services.call(
            block = { services.cached.schematic(draftId) },
        ) { result ->
            busy.set(false)
            when (result) {
                is ApiResult.Success -> {
                    val detail = result.value
                    val me = services.authManager.session?.playerUuid
                    if (!isDraftOwnedBy(detail.authors.map { it.uuid }, me)) {
                        ChatNotice.error("The server returned a draft that isn't yours — ignoring it")
                    } else {
                        UploadWizardPanel.openCompleteDraft(detail)
                    }
                }
                is ApiResult.Failure ->
                    ChatNotice.error("Could not fetch the created draft: ${result.error.toUserMessage()}")
            }
        }
    }
}
```

- [ ] **Step 11.3: Implement UploadWizardPanel complete-draft mode**

`UploadWizardPanel.kt` — three edits:

(a) In the state block (next to `selectedTagFilters`), add:

```kotlin
    /** Non-null = "complete draft" mode: the bytes are already on the backend (sub-project C). */
    internal var completingDraft: SchematicDetail? = null
```

(b) In `reset()`, next to `selectedSource = null`, add:

```kotlin
        completingDraft = null
```

(c) After `open(preselect: ExportSource?)`, add:

```kotlin
    /**
     * Open in "complete draft" mode (IPC sub-project C): the schematic bytes already
     * live on the backend as [detail]; the Source step is skipped and Save publishes
     * the draft via the USER's normal update path (name-carrying PUT clears the
     * expiry server-side). The preview composer is unavailable in this mode — the
     * update API cannot carry an image.
     */
    fun openCompleteDraft(detail: SchematicDetail) {
        reset()
        completingDraft = detail
        selectedSource = null           // reset() may have auto-picked a Litematica source
        nameBuf.set(detail.name)
        isPublic = detail.isPublic
        visibilityBuf.set(detail.isPublic)
        step = Step.DETAILS
        PanelManager.open(this)
    }
```

- [ ] **Step 11.4: Implement the step changes**

`UploadDetailsStep.kt` — three edits inside `renderDetailsStep()`:

(a) REPLACE the source-affordance block (the `val srcLabel = ...` line through the `if (Widgets.button("Change...")) { ... }` block) with:

```kotlin
    val draft = completingDraft
    if (draft != null) {
        ImGui.textColored(
            ImGuiColors.TEXT_MUTED.x, ImGuiColors.TEXT_MUTED.y,
            ImGuiColors.TEXT_MUTED.z, ImGuiColors.TEXT_MUTED.w,
            "Source: server clipboard — already uploaded as draft ${draft.shortId}",
        )
    } else {
        val srcLabel = "Source: " + (selectedSource?.let { ExportSources.label(it) } ?: "none selected")
        ImGui.textColored(
            ImGuiColors.TEXT_MUTED.x, ImGuiColors.TEXT_MUTED.y,
            ImGuiColors.TEXT_MUTED.z, ImGuiColors.TEXT_MUTED.w,
            srcLabel,
        )
        ImGui.sameLine()
        if (Widgets.button("Change...")) {
            step = Step.SOURCE
            statusMessage = null
        }
    }
```

(b) WRAP the community-selector block (the `// Community selector` comment through its trailing `ImGui.spacing()`) in a draft-mode guard — the user-auth update path has no community parameter:

```kotlin
    if (draft == null) {
        // Community selector
        val communityNames = (listOf("None") + communities.map { it.name }).toTypedArray()
        ImGui.setNextItemWidth(220f)
        ImGui.combo("Community", communityIndexBuf, communityNames)
        // Keep index in range if community list shrinks
        if (communityIndexBuf.get() > communities.size) communityIndexBuf.set(0)
        ImGui.spacing()
    }
```

(c) In the trailing `renderNavButtons(...)` call, change the back target:

```kotlin
    renderNavButtons(
        backStep = if (completingDraft != null) null else Step.SOURCE,
        nextLabel = "Next >",
        nextEnabled = true,
        onNext = { validateDetailsAndAdvance() },
    )
```

`UploadConfirmStep.kt` — four edits inside `renderConfirmStep()`:

(a) After `val source = selectedSource`, add `val draft = completingDraft`, then change the summary rows that differ:

```kotlin
    summaryRow("Source", draft?.let { "Server clipboard draft (${it.shortId})" } ?: source?.let { ExportSources.label(it) } ?: "none")
```

```kotlin
    summaryRow("Community", if (draft != null) "—" else (selectedCommunity?.name ?: "None"))
```

```kotlin
    summaryRow("Format", draft?.format ?: ExportSources.formatFor(source))
```

(b) WRAP the whole `sectionHeading("Preview")` block (heading, thumbnail/status, and the `Generate preview` button) in:

```kotlin
    if (draft == null) {
        // ... existing Preview section unchanged ...
    }
```

(c) Change the busy status line:

```kotlin
    if (busy) {
        Widgets.statusText(if (draft != null) "Saving..." else "Uploading...", Widgets.StatusKind.INFO)
    }
```

(d) Change the nav buttons:

```kotlin
    renderNavButtons(
        backStep = Step.DETAILS,
        nextLabel = if (draft != null) "Save & Publish" else "Upload",
        nextEnabled = !busy,
        nextAccent = true,
        onNext = { if (completingDraft != null) completeDraftSubmit() else startUpload() },
    )
```

- [ ] **Step 11.5: Implement completeDraftSubmit**

`UploadSubmit.kt` — append (same file as `startUpload`, so `webLink` and the panel extensions are in scope):

```kotlin
/**
 * Complete-draft save (sub-project C): the bytes are already on the backend, so this
 * is metadata-only — updateSchematic (name/description/visibility; the name-carrying
 * PUT is what PUBLISHES the draft server-side), then tags, then co-authors.
 * All with the USER's own auth — the server is out of the loop entirely.
 */
internal fun UploadWizardPanel.completeDraftSubmit() {
    val draft = completingDraft ?: return
    if (uploadBusy.get()) return
    val authorId = services.authManager.session?.playerUuid
    if (authorId == null) {
        statusMessage = "Not signed in to schemat.io"
        statusKind = Widgets.StatusKind.DANGER
        return
    }
    statusMessage = null
    val name = nameBuf.get().trim()
    val description = descEditor.toHtml()
    val tagIds = selectedTagIds.toList()
    val tagFilters = selectedTagFilters
    val coAuthorIds = coAuthorPicker.uuids()
        .filter { it.lowercase().replace("-", "") != authorId.lowercase().replace("-", "") }

    services.call(
        busy = uploadBusy,
        block = {
            val updated = services.cached.updateSchematic(
                draft.id,
                name = name,
                description = description,
                isPublic = isPublic,
            )
            val afterTags = when {
                updated is ApiResult.Failure -> updated
                tagIds.isEmpty() && tagFilters.isEmpty() -> updated
                else -> when (val tags = services.cached.setTags(draft.id, tagIds, tagFilters)) {
                    is ApiResult.Failure -> ApiResult.Failure(tags.error)
                    is ApiResult.Success -> updated
                }
            }
            if (afterTags is ApiResult.Success) {
                // Best-effort: a failed co-author add must not fail the publish.
                coAuthorIds.forEach { services.cached.addCoAuthor(draft.id, it) }
            }
            afterTags
        },
    ) { result ->
        when (result) {
            is ApiResult.Success -> {
                val detail = result.value
                BrowsePanel.invalidate()
                MySchematicsPanel.invalidate()
                PanelManager.close(id)
                ChatNotice.success(
                    "Published \"${detail.name}\" successfully",
                    webLink(detail),
                    "Open in browser",
                )
            }
            is ApiResult.Failure -> {
                val error = result.error
                if (error is ApiError.Validation) {
                    step = Step.DETAILS
                    statusMessage = if (error.fieldErrors.isEmpty()) error.message
                    else error.fieldErrors.entries.joinToString("; ") { (field, messages) ->
                        "$field: ${messages.firstOrNull() ?: "invalid"}"
                    }
                } else {
                    statusMessage = error.toUserMessage()
                }
                statusKind = Widgets.StatusKind.DANGER
            }
        }
    }
}
```

- [ ] **Step 11.6: Implement the toolbar action + tick/reset wiring**

`Toolbar.kt` — two edits:

(a) Add imports:

```kotlin
import io.schemat.connector.fabric.client.ipc.ClipboardUploadFlow
import io.schemat.connector.fabric.client.ipc.ServerIpc
```

(b) In `renderMenuBar()`, after the "Quick Share" `toolButton`, add:

```kotlin
        // Server clipboard -> draft upload (sub-project C). Rendered ONLY on VERIFIED
        // sessions advertising the UPLOAD capability — invisible otherwise.
        if (ServerIpc.canUploadClipboard()) {
            uploadClipboardButton()
        }
```

and after the private `toolButton` function, add:

```kotlin
    /**
     * Action button (no window of its own): pushes the SERVER's copy of your WE
     * clipboard to schemat.io as a draft, then opens the wizard to finish labelling.
     */
    private fun uploadClipboardButton() {
        ImGui.pushStyleColor(ImGuiCol.Button, 0f, 0f, 0f, 0f)
        ImGui.pushStyleColor(
            ImGuiCol.ButtonHovered,
            ImGuiColors.SURFACE_HOVER.x, ImGuiColors.SURFACE_HOVER.y,
            ImGuiColors.SURFACE_HOVER.z, ImGuiColors.SURFACE_HOVER.w,
        )
        ImGui.pushStyleColor(
            ImGuiCol.ButtonActive,
            ImGuiColors.ACCENT_DIM.x, ImGuiColors.ACCENT_DIM.y,
            ImGuiColors.ACCENT_DIM.z, ImGuiColors.ACCENT_DIM.w,
        )
        val busy = ClipboardUploadFlow.isBusy()
        if (busy) ImGui.beginDisabled()
        val clicked = ImGui.button("${Icons.UPLOAD}  Upload Clipboard")
        if (busy) ImGui.endDisabled()
        ImGui.popStyleColor(3)
        if (ImGui.isItemHovered(ImGuiHoveredFlags.AllowWhenDisabled)) {
            ImGui.setTooltip("Upload your server-side WorldEdit clipboard to schemat.io as a draft")
        }
        if (clicked && !busy) ClipboardUploadFlow.start()
    }
```

`SchematioClientMod.kt` — three edits (anchors reference B's final state):

(a) Add the import `import io.schemat.connector.fabric.client.ipc.ClipboardUploadTracker`.

(b) In the `ClientPlayConnectionEvents.JOIN` and `DISCONNECT` blocks, next to `ClipboardLoadTracker.reset()`, add:

```kotlin
            ClipboardUploadTracker.reset()
```

(c) In the `ClientTickEvents.END_CLIENT_TICK` block, next to `ClipboardLoadTracker.tick()`, add:

```kotlin
            ClipboardUploadTracker.tick()
```

- [ ] **Step 11.7: Run to verify**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:1.21.11:test`
Expected: BUILD SUCCESSFUL — everything compiles (the wizard's normal upload path is untouched for non-draft sources); Tasks 6/7/10 tests plus A's and B's fabric tests stay green.

*(No commit — global constraint.)*

---

## Task 12 — Integration checkpoint: full suites, invariant greps, run-paper checklist

**Files:** none created/modified (verification only).

- [ ] **Step 12.1: Full automated suites**

```bash
cd /Users/harrison/IdeaProjects/SchematioConnector
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:test :bukkit:test :fabric:1.21.11:test
# Expected: BUILD SUCCESSFUL, zero failures.

cd /Users/harrison/Documents/code/schemati
php artisan test --filter="SchematicDraft|PluginClipboardDraft"
# Expected: all green (Tasks 1-5 ≈ 25 tests). Optionally the full suite (php artisan test):
# the in-flight VCS work was green at 1486 tests; this plan only ADDS the draft tests.
```

- [ ] **Step 12.2: Security-invariant greps (spec invariant 1 — user tokens never transit the server)**

```bash
cd /Users/harrison/IdeaProjects/SchematioConnector
# The plugin's HTTP layer must have NO user-auth code path. Every hit below must be
# a communityToken reference (or nothing):
grep -rn "tokenProvider\|Bearer\|authManager\|ensureAuthenticated\|userToken\|user_token" \
  bukkit/src/main/kotlin --include="*.kt"
# Expected: only community-token wiring in SchematioConnector.kt (the
# `communityToken.takeIf { ... }` lambdas) and the old HttpUtil apiKey usages —
# nothing that reads, stores, or forwards a USER credential.

# No schematic bytes on the MC channel (B's invariant still holds with opcodes 7/8):
grep -n "ByteArray" core/src/main/kotlin/io/schemat/connector/core/ipc/IpcMessages.kt
# Expected: only HelloClient.nonce and Attest.signature carry ByteArray fields.
```

- [ ] **Step 12.3: Manual run-paper checklist (Harrison)**

1. **Mod path end-to-end:** attested session (backend keys configured) → `//pos1`/`//pos2`/`//copy` → toolbar shows "Upload Clipboard" → click → chat notice → wizard opens at Details, name prefilled `Clipboard upload …` → set name/description/tags → Save & Publish → schematic visible on the site, `draft_expires_at` NULL, correct author.
2. **Gating:** on an unattested session (break the backend key config), the toolbar button is absent; sending a hand-crafted opcode 7 (or joining mid-configuration) yields `STATUS DENIED "Session is not attested…"`.
3. **Standalone path:** without the mod, `/schematio upload` → "Draft created!" + clickable link → browser opens `/schematics/upload/{shortId}` prefilled with the 3D preview → complete + publish. Also verify a logged-out browser hits the login redirect and returns to the draft.
4. **Unlinked account:** run `/schematio upload` as a player with no site account → the account-link hint (offline-mode variant on an offline server).
5. **Quota exhaustion message:** create 10 drafts (loop `/schematio upload` across minutes or seed via tinker) → 11th shows "Draft limit reached (10) — publish or delete drafts…".
6. **Empty clipboard:** `//clearclipboard` (or fresh join) → both paths answer "clipboard is empty".
7. **Expiry:** backdate a draft (`php artisan tinker` → set `draft_expires_at` to yesterday) → `php artisan schematics:purge-expired-drafts` → row AND media files gone (check the media disk).
8. **Known limitation check:** a wizard-published draft has no preview image — confirm the site card renders acceptably; the web-published path always has one.
9. **Rate limit:** three rapid `/schematio upload` → third says "Too many clipboard uploads; retry in Ns".

---

## Self-review notes (spec coverage)

- **Protocol:** opcode 7 (64 B cap, requestId only) / opcode 8 (256 B cap, draftId ≤ 64) — Task 6; `UPLOAD` capability un-reserved + gated on WE+backend — Tasks 6/9; failures reuse STATUS with `TOO_LARGE`/`DENIED`/`UNAVAILABLE`/`RATE_LIMITED` — Tasks 8/9; standalone chat path with clickable web link — Task 9.
- **Backend:** `POST /api/v1/plugin/clipboard/drafts` multipart (8 MiB → 413, 10-draft quota → 409, 4/min → 429, unlinked → 400, 48 h expiry, audit line) — Task 2; draft state via `draft_expires_at` (spec's fallback since no status enum exists) — Task 1; central listing/search/Grid exclusion + Pest proof — Task 1; publish = existing update path clearing expiry (no new endpoint) — Task 3; daily expiry job w/ file deletion + time-travel test — Task 4; web completion page for the standalone link — Task 5.
- **Plugin:** guard order (WE → clipboard → permission → attested[IPC-only] → 2/min bucket), main-thread snapshot + async serialization, post-serialization 8 MiB cap, 4xx/5xx → terminal STATUS mapping — Tasks 8/9; permission `schematio.clipboard.upload` default true — Task 9.
- **Client:** DRAFT_CREATED → user-auth re-fetch + ownership refusal (unit-tested, Task 10) → complete-draft wizard (file step skipped, save = normal update = publish) — Task 11; toolbar entry gated on VERIFIED+UPLOAD — Task 11; 30 s timeout → synthetic ERROR — Task 10.
- **Security invariants 1–5:** grep + review (12.2), Pest 413/409/429 (Task 2), listing invisibility + non-owner 404 (Tasks 1/3), fabric ownership unit test (Task 10), expiry file-deletion time-travel test (Task 4).
- **Type consistency check done:** `ClipboardUploadOutcome` variants match between Task 7 (definition), Task 8 (`statusFor`), and the C1 mapping table; `ClipboardUploadService.Result` matches between Tasks 8/9; `ClipboardUploadTracker` signatures match between Tasks 10/11; `capabilitiesFor` matches between Task 9's test and implementation; `draft_expires_at` handling matches Tasks 1–5 and the C1 contract.

