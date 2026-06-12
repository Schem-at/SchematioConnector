package io.schemat.connector.core.modapi

import com.google.gson.JsonObject
import io.schemat.connector.core.json.parseJsonSafe
import io.schemat.connector.core.modapi.dto.AuthorInfo
import io.schemat.connector.core.modapi.dto.CommunitySummary
import io.schemat.connector.core.modapi.dto.InvitationInfo
import io.schemat.connector.core.modapi.dto.MeSnapshot
import io.schemat.connector.core.modapi.dto.MemberInfo
import io.schemat.connector.core.modapi.dto.Page
import io.schemat.connector.core.modapi.dto.PageMeta
import io.schemat.connector.core.modapi.dto.PlayerProfile
import io.schemat.connector.core.modapi.dto.QuickShareInfo
import io.schemat.connector.core.modapi.dto.RoleInfo
import io.schemat.connector.core.modapi.dto.SchematicDetail
import io.schemat.connector.core.modapi.dto.SchematicSummary
import io.schemat.connector.core.modapi.dto.TagFilterDef
import io.schemat.connector.core.modapi.dto.TagNode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * DTO parsing tests. Fixtures are copied verbatim from the contract reference:
 * docs/api/mod-namespace.md (schemati repo).
 */
class DtoParsingTest {

    private fun obj(json: String): JsonObject = parseJsonSafe(json)!!

    @Nested
    @DisplayName("RoleInfo")
    inner class RoleInfoTests {

        @Test
        fun `parses contract fixture`() {
            // From GET /mod/me: roles entry
            val role = RoleInfo.fromJson(obj("""{ "id": "<uuid>", "name": "Builder", "color": "#aabbcc" }"""))!!
            assertEquals("<uuid>", role.id)
            assertEquals("Builder", role.name)
            assertEquals("#aabbcc", role.color)
        }

        @Test
        fun `missing id returns null, minimal object gets defaults`() {
            assertNull(RoleInfo.fromJson(obj("{}")))
            val role = RoleInfo.fromJson(obj("""{"id": "r1"}"""))!!
            assertEquals("r1", role.id)
            assertEquals("", role.name)
            assertNull(role.color)
        }

        @Test
        fun `parses full role definition shape`() {
            // From GET /mod/communities/{slug}/roles
            val role = RoleInfo.fromJson(
                obj(
                    """
                    { "id": "<uuid>", "name": "Admin", "color": "#ff0000",
                      "is_system": true, "permissions": ["manage-members", "manage-roles"], "position": 3 }
                    """.trimIndent(),
                ),
            )!!
            assertEquals("Admin", role.name)
            assertTrue(role.isSystem)
            assertEquals(listOf("manage-members", "manage-roles"), role.permissions)
            assertEquals(3, role.position)
        }

        @Test
        fun `payloads without the role-definition keys default safely`() {
            // Existing roles arrays (/mod/me, members) omit is_system/permissions/position.
            val role = RoleInfo.fromJson(obj("""{ "id": "r1", "name": "Builder", "color": "#aabbcc" }"""))!!
            assertFalse(role.isSystem)
            assertTrue(role.permissions.isEmpty())
            assertEquals(0, role.position)
        }
    }

    @Nested
    @DisplayName("PlayerProfile")
    inner class PlayerProfileTests {

        @Test
        fun `parses contract fixture`() {
            // From GET /mod/me: "player"
            val player = PlayerProfile.fromJson(obj("""{ "id": "<uuid>", "name": "<last seen name>" }"""))!!
            assertEquals("<uuid>", player.id)
            assertEquals("<last seen name>", player.name)
        }

        @Test
        fun `missing id returns null, minimal object gets defaults`() {
            assertNull(PlayerProfile.fromJson(obj("{}")))
            val player = PlayerProfile.fromJson(obj("""{"id": "p1"}"""))!!
            assertEquals("p1", player.id)
            assertEquals("", player.name)
        }
    }

    @Nested
    @DisplayName("CommunitySummary")
    inner class CommunitySummaryTests {

        // Verbatim community payload from GET /mod/communities (docs/api/mod-namespace.md)
        private val fixture = """
            {
              "id": "<uuid>", "slug": "...", "name": "...", "description": "...",
              "is_public": true, "member_count": 12, "is_member": true,
              "permissions": ["..."], "roles": [{ "id": "...", "name": "...", "color": "..." }]
            }
        """.trimIndent()

        @Test
        fun `parses contract fixture`() {
            val community = CommunitySummary.fromJson(obj(fixture))!!
            assertEquals("<uuid>", community.id)
            assertEquals("...", community.slug)
            assertEquals("...", community.name)
            assertEquals("...", community.description)
            assertTrue(community.isPublic)
            assertEquals(12, community.memberCount)
            assertTrue(community.isMember)
            assertEquals(setOf("..."), community.permissions)
            assertEquals(1, community.roles.size)
            assertEquals("...", community.roles[0].id)
            assertEquals("...", community.roles[0].name)
            assertEquals("...", community.roles[0].color)
        }

        @Test
        fun `parses me-payload shape (no description, member_count, is_member)`() {
            // Verbatim communities entry from GET /mod/me
            val community = CommunitySummary.fromJson(
                obj(
                    """
                    {
                      "id": "<uuid>", "slug": "...", "name": "...", "is_public": true,
                      "permissions": ["manage-tags", "..."],
                      "roles": [{ "id": "<uuid>", "name": "Builder", "color": "#aabbcc" }]
                    }
                    """.trimIndent()
                )
            )!!
            assertNull(community.description)
            assertNull(community.memberCount)
            assertTrue(community.isMember)
            assertEquals(setOf("manage-tags", "..."), community.permissions)
            assertEquals("Builder", community.roles[0].name)
        }

        @Test
        fun `can() checks the permissions set`() {
            val community = CommunitySummary.fromJson(
                obj("""{"slug": "s", "permissions": ["manage-tags"]}""")
            )!!
            assertTrue(community.can("manage-tags"))
            assertFalse(community.can("manage-members"))
        }

        @Test
        fun `missing slug returns null, minimal object gets defaults`() {
            assertNull(CommunitySummary.fromJson(obj("{}")))
            val community = CommunitySummary.fromJson(obj("""{"slug": "builders"}"""))!!
            assertEquals("", community.id)
            assertEquals("builders", community.slug)
            assertEquals("builders", community.name)
            assertNull(community.description)
            assertTrue(community.isPublic)
            assertNull(community.memberCount)
            assertTrue(community.isMember)
            assertTrue(community.permissions.isEmpty())
            assertTrue(community.roles.isEmpty())
        }
    }

    @Nested
    @DisplayName("MeSnapshot")
    inner class MeSnapshotTests {

        // Verbatim 200 response of GET /api/v1/mod/me
        private val fixture = """
            {
              "player": { "id": "<uuid>", "name": "<last seen name>" },
              "communities": [
                {
                  "id": "<uuid>", "slug": "...", "name": "...", "is_public": true,
                  "permissions": ["manage-tags", "..."],
                  "roles": [{ "id": "<uuid>", "name": "Builder", "color": "#aabbcc" }]
                }
              ],
              "pending_invitations": 2
            }
        """.trimIndent()

        @Test
        fun `parses contract fixture`() {
            val me = MeSnapshot.fromJson(obj(fixture))
            assertNotNull(me.player)
            assertEquals("<uuid>", me.player!!.id)
            assertEquals("<last seen name>", me.player!!.name)
            assertEquals(1, me.communities.size)
            assertEquals("...", me.communities[0].slug)
            assertTrue(me.communities[0].can("manage-tags"))
            assertEquals("Builder", me.communities[0].roles[0].name)
            assertEquals("#aabbcc", me.communities[0].roles[0].color)
            assertEquals(2, me.pendingInvitations)
        }

        @Test
        fun `tolerates empty object`() {
            val me = MeSnapshot.fromJson(obj("{}"))
            assertNull(me.player)
            assertTrue(me.communities.isEmpty())
            assertEquals(0, me.pendingInvitations)
        }
    }

    @Nested
    @DisplayName("PageMeta and Page")
    inner class PageTests {

        @Test
        fun `parses Laravel meta block`() {
            val meta = PageMeta.fromJson(obj("""{"current_page": 2, "last_page": 3, "per_page": 20, "total": 55}"""))
            assertEquals(2, meta.currentPage)
            assertEquals(3, meta.lastPage)
            assertEquals(20, meta.perPage)
            assertEquals(55, meta.total)
        }

        @Test
        fun `parses members-style meta (no per_page)`() {
            // Verbatim meta from GET /mod/communities/{slug}/members
            val meta = PageMeta.fromJson(obj("""{ "total": 42, "current_page": 1, "last_page": 2 }"""))
            assertEquals(1, meta.currentPage)
            assertEquals(2, meta.lastPage)
            assertEquals(0, meta.perPage)
            assertEquals(42, meta.total)
        }

        @Test
        fun `null meta yields defaults`() {
            val meta = PageMeta.fromJson(null)
            assertEquals(1, meta.currentPage)
            assertEquals(1, meta.lastPage)
            assertEquals(0, meta.perPage)
            assertEquals(0, meta.total)
        }

        @Test
        fun `hasMore is true when current page is before the last page`() {
            val page = Page(listOf("a"), PageMeta(currentPage = 1, lastPage = 3, perPage = 20, total = 55))
            assertTrue(page.hasMore)
        }

        @Test
        fun `hasMore is false on the last page`() {
            val page = Page(listOf("a"), PageMeta(currentPage = 3, lastPage = 3, perPage = 20, total = 55))
            assertFalse(page.hasMore)
        }
    }

    @Nested
    @DisplayName("AuthorInfo")
    inner class AuthorInfoTests {

        @Test
        fun `parses contract fixture`() {
            // From the SchematicResource shape: authors entry
            val author = AuthorInfo.fromJson(obj("""{ "uuid": "<uuid>", "last_seen_name": "...", "head_url": "..." }"""))!!
            assertEquals("<uuid>", author.uuid)
            assertEquals("...", author.lastSeenName)
            assertEquals("...", author.headUrl)
        }

        @Test
        fun `missing uuid returns null, minimal object gets defaults`() {
            assertNull(AuthorInfo.fromJson(obj("{}")))
            val author = AuthorInfo.fromJson(obj("""{"uuid": "u1"}"""))!!
            assertEquals("u1", author.uuid)
            assertEquals("", author.lastSeenName)
            assertNull(author.headUrl)
        }
    }

    @Nested
    @DisplayName("SchematicSummary and SchematicDetail")
    inner class SchematicTests {

        // Verbatim schematic resource shape from docs/api/mod-namespace.md
        private val fixture = """
            {
              "id": "<uuid>", "short_id": "...", "name": "...", "description": "...",
              "slug": "...", "format": "schem", "is_public": true,
              "created_at": "...", "updated_at": "...",
              "authors": [{ "uuid": "<uuid>", "last_seen_name": "...", "head_url": "..." }],
              "tags": [{ "id": "<tag-uuid>", "name": "...", "color": "...", "text_color": "..." }],
              "tag_filter_values": { "1": "10", "7": "1" },
              "web_url": "https://schemat.io/schematics/FFm40B",
              "preview_image_url": "...", "preview_video_url": null, "download_link": "..."
            }
        """.trimIndent()

        @Test
        fun `summary parses contract fixture`() {
            val schematic = SchematicSummary.fromJson(obj(fixture))!!
            assertEquals("<uuid>", schematic.id)
            assertEquals("...", schematic.shortId)
            assertEquals("...", schematic.name)
            assertEquals("...", schematic.description)
            assertEquals("...", schematic.slug)
            assertEquals("schem", schematic.format)
            assertTrue(schematic.isPublic)
            assertEquals("...", schematic.createdAt)
            assertEquals("...", schematic.updatedAt)
            assertEquals(1, schematic.authors.size)
            assertEquals("<uuid>", schematic.authors[0].uuid)
            assertEquals("...", schematic.authors[0].lastSeenName)
            assertEquals("...", schematic.authors[0].headUrl)
            assertEquals(1, schematic.tags.size)
            assertEquals("<tag-uuid>", schematic.tags[0].id)
            assertEquals("...", schematic.tags[0].name)
            assertEquals("...", schematic.tags[0].color)
            assertEquals("...", schematic.tags[0].textColor)
            assertEquals("...", schematic.previewImageUrl)
            assertNull(schematic.previewVideoUrl)
            assertEquals("...", schematic.downloadLink)
            assertEquals("https://schemat.io/schematics/FFm40B", schematic.webUrl)
        }

        @Test
        fun `detail parses contract fixture`() {
            val schematic = SchematicDetail.fromJson(obj(fixture))!!
            assertEquals("<uuid>", schematic.id)
            assertEquals("...", schematic.shortId)
            assertEquals("...", schematic.slug)
            assertEquals("...", schematic.name)
            assertEquals("...", schematic.description)
            assertEquals("schem", schematic.format)
            assertTrue(schematic.isPublic)
            assertEquals(1, schematic.authors.size)
            assertEquals(1, schematic.tags.size)
            assertEquals(mapOf(1L to "10", 7L to "1"), schematic.tagFilterValues)
            assertEquals("...", schematic.previewImageUrl)
            assertNull(schematic.previewVideoUrl)
            assertEquals("...", schematic.downloadLink)
            assertEquals("https://schemat.io/schematics/FFm40B", schematic.webUrl)
        }

        @Test
        fun `detail tag_filter_values skips non-numeric keys and tolerates empty object`() {
            val schematic = SchematicDetail.fromJson(
                obj("""{"id": "s1", "tag_filter_values": { "3": "2.5", "not-a-number": "x" }}""")
            )!!
            assertEquals(mapOf(3L to "2.5"), schematic.tagFilterValues)
            val empty = SchematicDetail.fromJson(obj("""{"id": "s1", "tag_filter_values": {}}"""))!!
            assertTrue(empty.tagFilterValues.isEmpty())
        }

        @Test
        fun `summary missing id returns null, minimal object gets defaults`() {
            assertNull(SchematicSummary.fromJson(obj("{}")))
            val schematic = SchematicSummary.fromJson(obj("""{"id": "s1"}"""))!!
            assertEquals("s1", schematic.id)
            assertNull(schematic.shortId)
            assertNull(schematic.slug)
            assertEquals("", schematic.name)
            assertNull(schematic.description)
            assertNull(schematic.format)
            assertTrue(schematic.isPublic)
            assertNull(schematic.createdAt)
            assertNull(schematic.updatedAt)
            assertTrue(schematic.authors.isEmpty())
            assertTrue(schematic.tags.isEmpty())
            assertNull(schematic.previewImageUrl)
            assertNull(schematic.previewVideoUrl)
            assertNull(schematic.downloadLink)
        }

        @Test
        fun `tag without id (older server) parses with null id`() {
            val schematic = SchematicSummary.fromJson(
                obj("""{"id": "s1", "tags": [{ "name": "medieval", "color": "#fff", "text_color": "#000" }]}""")
            )!!
            assertEquals(1, schematic.tags.size)
            assertNull(schematic.tags[0].id)
            assertEquals("medieval", schematic.tags[0].name)
        }

        @Test
        fun `detail missing id returns null, minimal object gets defaults`() {
            assertNull(SchematicDetail.fromJson(obj("{}")))
            val schematic = SchematicDetail.fromJson(obj("""{"id": "s1"}"""))!!
            assertEquals("s1", schematic.id)
            assertEquals("", schematic.name)
            assertTrue(schematic.authors.isEmpty())
            assertTrue(schematic.tags.isEmpty())
            assertTrue(schematic.tagFilterValues.isEmpty(), "absent tag_filter_values must default to empty")
        }
    }

    @Nested
    @DisplayName("MemberInfo")
    inner class MemberInfoTests {

        // Verbatim data entry from GET /mod/communities/{slug}/members
        private val fixture = """
            {
              "id": "<uuid>", "name": "...", "joined_at": "...", "is_owner": false,
              "roles": [{ "id": "...", "name": "...", "color": "..." }]
            }
        """.trimIndent()

        @Test
        fun `parses contract fixture`() {
            val member = MemberInfo.fromJson(obj(fixture))!!
            assertEquals("<uuid>", member.id)
            assertEquals("...", member.name)
            assertEquals("...", member.joinedAt)
            assertFalse(member.isOwner)
            assertEquals(1, member.roles.size)
            assertEquals("...", member.roles[0].id)
        }

        @Test
        fun `missing id returns null, minimal object gets defaults`() {
            assertNull(MemberInfo.fromJson(obj("{}")))
            val member = MemberInfo.fromJson(obj("""{"id": "m1"}"""))!!
            assertEquals("m1", member.id)
            assertEquals("", member.name)
            assertNull(member.joinedAt)
            assertFalse(member.isOwner)
            assertTrue(member.roles.isEmpty())
        }
    }

    @Nested
    @DisplayName("InvitationInfo")
    inner class InvitationInfoTests {

        // Verbatim invitations entry from GET /mod/invitations
        private val fixture = """
            {
              "id": 7,
              "community": { "slug": "...", "name": "..." },
              "invited_by": "<player name or null>",
              "message": "...", "expires_at": "..."
            }
        """.trimIndent()

        @Test
        fun `parses contract fixture`() {
            val invitation = InvitationInfo.fromJson(obj(fixture))!!
            assertEquals(7L, invitation.id)
            assertEquals("...", invitation.communitySlug)
            assertEquals("...", invitation.communityName)
            assertEquals("<player name or null>", invitation.invitedBy)
            assertEquals("...", invitation.message)
            assertEquals("...", invitation.expiresAt)
        }

        @Test
        fun `null invited_by is tolerated`() {
            val invitation = InvitationInfo.fromJson(
                obj("""{"id": 8, "community": {"slug": "s", "name": "n"}, "invited_by": null, "message": null, "expires_at": null}""")
            )!!
            assertEquals(8L, invitation.id)
            assertNull(invitation.invitedBy)
            assertNull(invitation.message)
            assertNull(invitation.expiresAt)
        }

        @Test
        fun `missing id returns null, minimal object gets defaults`() {
            assertNull(InvitationInfo.fromJson(obj("{}")))
            val invitation = InvitationInfo.fromJson(obj("""{"id": 9}"""))!!
            assertEquals(9L, invitation.id)
            assertEquals("", invitation.communitySlug)
            assertEquals("", invitation.communityName)
            assertNull(invitation.invitedBy)
        }
    }

    @Nested
    @DisplayName("TagNode")
    inner class TagNodeTests {

        // Node shape per GET /mod/communities/{slug}/tags and GET /mod/tags:
        // { "id", "name", "color", "scope", "is_manually_assignable", "filters": [<filter>], "children": [<node>] }
        // (community tag payloads may omit "is_manually_assignable" - level-3 covers that;
        //  the level-2 "filters" entry is the verbatim filter fixture from the contract doc)
        private val fixture = """
            {
              "id": "root-child", "name": "Buildings", "color": "#ff0000", "scope": "public_use",
              "is_manually_assignable": false,
              "filters": [],
              "children": [
                {
                  "id": "level-2", "name": "Houses", "color": "#00ff00", "scope": "public_viewing",
                  "is_manually_assignable": true,
                  "filters": [
                    { "id": 1, "name": "Tick Rate", "type": "int", "min_value": 1, "max_value": 20,
                      "enum_values": null, "is_required": true, "default_value": "10", "unit": "gt" }
                  ],
                  "children": [
                    {
                      "id": "level-3", "name": "Cottages", "color": "#0000ff", "scope": "private",
                      "children": []
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        @Test
        fun `parses three-level nested tree`() {
            val root = TagNode.fromJson(obj(fixture))!!
            assertEquals("root-child", root.id)
            assertEquals("Buildings", root.name)
            assertEquals("#ff0000", root.color)
            assertEquals("public_use", root.scope)
            assertFalse(root.isManuallyAssignable, "category-only root must parse is_manually_assignable=false")
            assertTrue(root.filters.isEmpty())
            assertEquals(1, root.children.size)

            val level2 = root.children[0]
            assertEquals("level-2", level2.id)
            assertEquals("Houses", level2.name)
            assertEquals("public_viewing", level2.scope)
            assertTrue(level2.isManuallyAssignable)
            assertEquals(1, level2.children.size)

            val level3 = level2.children[0]
            assertEquals("level-3", level3.id)
            assertEquals("Cottages", level3.name)
            assertEquals("#0000ff", level3.color)
            assertEquals("private", level3.scope)
            assertTrue(level3.isManuallyAssignable, "absent is_manually_assignable must default to true")
            assertTrue(level3.children.isEmpty())
            assertTrue(level3.filters.isEmpty(), "absent filters must default to empty")
        }

        @Test
        fun `parses filters recursively on nested nodes`() {
            val root = TagNode.fromJson(obj(fixture))!!
            val filter = root.children[0].filters.single()
            assertEquals(1L, filter.id)
            assertEquals("Tick Rate", filter.name)
            assertEquals("int", filter.type)
            assertEquals(1.0, filter.minValue)
            assertEquals(20.0, filter.maxValue)
            assertTrue(filter.enumValues.isEmpty(), "null enum_values must parse as empty list")
            assertTrue(filter.isRequired)
            assertEquals("10", filter.defaultValue)
            assertEquals("gt", filter.unit)
        }

        @Test
        fun `missing id returns null, minimal object gets defaults`() {
            assertNull(TagNode.fromJson(obj("{}")))
            val tag = TagNode.fromJson(obj("""{"id": "t1"}"""))!!
            assertEquals("t1", tag.id)
            assertEquals("", tag.name)
            assertNull(tag.color)
            assertNull(tag.scope)
            assertTrue(tag.isManuallyAssignable)
            assertTrue(tag.children.isEmpty())
            assertTrue(tag.filters.isEmpty())
        }
    }

    @Nested
    @DisplayName("TagFilterDef")
    inner class TagFilterDefTests {

        @Test
        fun `parses enum filter definition`() {
            val filter = TagFilterDef.fromJson(
                obj(
                    """
                    { "id": 4, "name": "Mob", "type": "enum", "min_value": null, "max_value": null,
                      "enum_values": ["zombie", "skeleton"], "is_required": false, "default_value": null, "unit": null }
                    """.trimIndent(),
                ),
            )!!
            assertEquals(4L, filter.id)
            assertEquals("Mob", filter.name)
            assertEquals("enum", filter.type)
            assertNull(filter.minValue)
            assertNull(filter.maxValue)
            assertEquals(listOf("zombie", "skeleton"), filter.enumValues)
            assertFalse(filter.isRequired)
            assertNull(filter.defaultValue)
            assertNull(filter.unit)
        }

        @Test
        fun `strips stray quote characters from enum values (legacy malformed rows)`() {
            // Real-world bug: quoted user input baked literal quotes into the
            // first/last options, e.g. ["\"1.15", "1.16", ..., "1.21\""].
            val filter = TagFilterDef.fromJson(
                obj(
                    """
                    { "id": 5, "name": "Version", "type": "enum",
                      "enum_values": ["\"1.15", "1.16", " 1.17 ", "1.21\""] }
                    """.trimIndent(),
                ),
            )!!
            assertEquals(listOf("1.15", "1.16", "1.17", "1.21"), filter.enumValues)
        }

        @Test
        fun `parses comma-joined string enum_values defensively into N options`() {
            // Older/buggy servers sent the whole list as ONE string instead of an array.
            val filter = TagFilterDef.fromJson(
                obj(
                    """
                    { "id": 6, "name": "data format", "type": "enum",
                      "enum_values": "\"binary, floating point, fixed point, ss (hex), hss\"" }
                    """.trimIndent(),
                ),
            )!!
            assertEquals(
                listOf("binary", "floating point", "fixed point", "ss (hex)", "hss"),
                filter.enumValues,
            )
            // Each option validates individually; the joined blob does not.
            assertNull(filter.validate("binary"))
            assertNotNull(filter.validate("binary, floating point, fixed point, ss (hex), hss"))
        }

        @Test
        fun `missing or non-numeric id returns null, minimal object gets defaults`() {
            assertNull(TagFilterDef.fromJson(obj("{}")))
            assertNull(TagFilterDef.fromJson(obj("""{"id": "not-a-number"}""")))
            val filter = TagFilterDef.fromJson(obj("""{"id": 9}"""))!!
            assertEquals(9L, filter.id)
            assertEquals("", filter.name)
            assertEquals("", filter.type)
            assertNull(filter.minValue)
            assertNull(filter.maxValue)
            assertTrue(filter.enumValues.isEmpty())
            assertFalse(filter.isRequired)
            assertNull(filter.defaultValue)
            assertNull(filter.unit)
        }

        @Test
        fun `parses float bounds`() {
            val filter = TagFilterDef.fromJson(
                obj("""{ "id": 2, "name": "Efficiency", "type": "float", "min_value": 0.5, "max_value": 99.9 }"""),
            )!!
            assertEquals(0.5, filter.minValue)
            assertEquals(99.9, filter.maxValue)
        }
    }

    @Nested
    @DisplayName("QuickShareInfo")
    inner class QuickShareInfoTests {

        // Verbatim quick_share object from POST /mod/quick-shares 201 response
        private val storeFixture = """
            {
              "id": 1, "access_code": "...", "name": "...", "description": "...",
              "format": "schem", "web_url": "...", "api_url": ".../api/v1/plugin/quick-shares/{code}/download",
              "expires_at": "<iso8601|null>", "has_password": false, "has_whitelist": false,
              "limit_type": "unlimited", "max_uses": null,
              "created_by_player": { "id": "<uuid>", "name": "..." }
            }
        """.trimIndent()

        // Verbatim quick_shares entry from GET /mod/quick-shares
        private val listFixture = """
            { "id": 1, "access_code": "...", "name": "...", "format": "schem",
              "expires_at": "...", "is_active": true, "current_uses": 0, "has_data": true }
        """.trimIndent()

        @Test
        fun `parses store response fixture`() {
            val share = QuickShareInfo.fromJson(obj(storeFixture))!!
            assertEquals(1L, share.id)
            assertEquals("...", share.accessCode)
            assertEquals("...", share.name)
            assertEquals("...", share.description)
            assertEquals("schem", share.format)
            assertEquals("...", share.webUrl)
            assertEquals(".../api/v1/plugin/quick-shares/{code}/download", share.apiUrl)
            assertEquals("<iso8601|null>", share.expiresAt)
            assertFalse(share.hasPassword)
            assertFalse(share.hasWhitelist)
            assertEquals("unlimited", share.limitType)
            assertNull(share.maxUses)
            assertNotNull(share.createdByPlayer)
            assertEquals("<uuid>", share.createdByPlayer!!.id)
            assertEquals("...", share.createdByPlayer!!.name)
        }

        @Test
        fun `parses list response fixture`() {
            val share = QuickShareInfo.fromJson(obj(listFixture))!!
            assertEquals(1L, share.id)
            assertEquals("...", share.accessCode)
            assertEquals("schem", share.format)
            assertEquals("...", share.expiresAt)
            assertTrue(share.isActive)
            assertEquals(0, share.currentUses)
            assertTrue(share.hasData)
        }

        @Test
        fun `missing access_code returns null, minimal object gets defaults`() {
            assertNull(QuickShareInfo.fromJson(obj("{}")))
            val share = QuickShareInfo.fromJson(obj("""{"access_code": "abc123"}"""))!!
            assertEquals(0L, share.id)
            assertEquals("abc123", share.accessCode)
            assertNull(share.name)
            assertNull(share.description)
            assertEquals("schem", share.format)
            assertNull(share.webUrl)
            assertNull(share.apiUrl)
            assertNull(share.expiresAt)
            assertFalse(share.hasPassword)
            assertFalse(share.hasWhitelist)
            assertEquals("unlimited", share.limitType)
            assertNull(share.maxUses)
            assertTrue(share.isActive)
            assertEquals(0, share.currentUses)
            assertFalse(share.hasData)
            assertNull(share.createdByPlayer)
        }
    }
}
