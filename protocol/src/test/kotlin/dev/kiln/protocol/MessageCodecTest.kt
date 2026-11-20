package dev.kiln.protocol

import dev.kiln.protocol.codec.MessageCodec
import dev.kiln.protocol.codec.ProtocolWriter
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.random.Random

class MessageCodecTest {

    companion object {
        private val b = Bounds(0, 0, 10, 10)

        /** Real gzip data, as Paper's serializeAsBytes() would produce (content irrelevant for the codec). */
        val gzip: ByteArray = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.GZIPOutputStream(out).use { it.write("fake nbt".toByteArray()) }
        }.toByteArray()

        val diamond = ItemStackData(ResourceId.parse("diamond"), 64)
        val fancySword = ItemStackData(ResourceId.parse("diamond_sword"), 1234, gzip)

        val fullTree: Element = Panel(
            id = "root",
            bounds = Bounds(0, 0, 200, 150),
            background = Color.rgb(0x202020),
            texture = ResourceId.of("example", "textures/gui/panel.png"),
            border = Color.argb(128, 255, 0, 0),
            children = listOf(
                Text("title", Bounds(-5, 4, 190, 10), """{"text":"Factory ✓"}""", Color.rgb(0xFFAA00), TextAlign.CENTER, false),
                Button("start", Bounds(10, 20, 80, 20), """{"text":"Start"}""", enabled = false, tooltip = """{"text":"tip"}"""),
                Button("stop", Bounds(100, 20, 80, 20), """{"text":"Stop"}"""),
                Image("logo", Bounds(0, 50, 16, 16), ResourceId.parse("example:textures/gui/logo.png"), 0.5f, 1f, 16, 16, 64, 64),
                ItemDisplay("item", Bounds(20, 50, 16, 16), diamond),
                ItemDisplay("item2", Bounds(20, 50, 16, 16), fancySword, showItemTooltip = false, tooltip = """{"text":"5 coins"}"""),
                ItemSlot("slot_empty", Bounds(0, 0, 18, 18), placeholderSprite = ResourceId.parse("container/slot/sword")),
                ItemSlot(
                    "slot", Bounds(18, 0, 18, 18), fancySword, slotBackground = false, highlight = false,
                    selected = true, enabled = false, showItemTooltip = false, tooltip = "\"x\"",
                ),
                Dropdown(
                    "sort", Bounds(0, 0, 80, 20), listOf(DropdownOption("price", "\"Price\""), DropdownOption("name", "\"Name\"")),
                    "name", "\"Sort\"", enabled = false,
                ),
                Dropdown("empty_dd", b, emptyList()),
                Checkbox("check", b, true, "\"Only mine\"", CheckboxStyle.TOGGLE, enabled = false, tooltip = "\"t\""),
                Slider("amount", Bounds(0, 0, 100, 20), 1.0, 64.0, 1.0, 16.0, "\"Amount\""),
                Slider("cont", b, -0.5, 0.5, 0.0, 0.25, enabled = false),
                TextField("price", b, "12", numeric = NumericRange(0.0, 1e6, integer = true)),
                ProgressBar("tip_progress", b, 0.1f, tooltip = "\"10%\""),
                Panel(
                    "tile", b, background = Color.BLACK, hoverBackground = Color.WHITE, selectedBackground = Color.rgb(0x00FF00),
                    selected = true, clickable = true, tooltip = "\"tile\"",
                ),
                ScrollPanel(
                    "list", Bounds(0, 20, 100, 60), contentHeight = 30_000, background = Color.BLACK, border = null, scrollbar = false,
                    children = listOf(Text("deep_row", Bounds(0, 29_000, 100, 10), "\"row\"")),
                ),
                ProgressBar("progress", Bounds(40, 50, 100, 6), 0.42f),
                TextField("name", Bounds(0, 70, 120, 18), "Steve", """{"text":"Name"}""", 32),
                Panel("inner", b, children = listOf(
                    Custom("gauge", b, ResourceId.of("example", "gauge"), linkedMapOf("value" to "3", "max" to "10")),
                )),
                Modal("confirm", Bounds(50, 40, 100, 60), dimColor = Color.argb(0x80, 0, 0, 0), children = listOf(
                    Button("confirm_yes", Bounds(5, 30, 40, 20), "\"Yes\""),
                )),
                Modal("second", b, background = null, border = null),
            ),
        )

        @JvmStatic
        fun serverbound(): List<ServerboundMessage> = listOf(
            Hello(PROTOCOL_VERSION, listOf("example-client", "other_mod")),
            Hello(PROTOCOL_VERSION, emptyList()),
            ScreenClick(7, "start", 0),
            ScreenClick(Int.MAX_VALUE, "a/b:c.d-e_f", 2),
            ScreenClick(1, "slot", 1, ClickModifiers.SHIFT),
            ScreenClick(1, "slot", 7, ClickModifiers.ALL),
            Viewport(427, 240),
            Viewport(1, ProtocolLimits.MAX_SIZE),
            ModalDismiss(4, "confirm"),
            ScreenInput(1, "name", "Hällo wörld 😀"),
            ScreenClosed(3),
            KeyPressed("open_menu"),
            KeyReleased("open_menu"),
            ExtensionMessage(ResourceId.of("example", "ping"), """{"text":"Hällo 😀","n":[1,2.5,null]}"""),
            ExtensionMessage(ResourceId.of("example", "empty"), ""),
            ExtensionRequest(listOf("kiln_example", "other")),
            ExtensionRequest(emptyList()),
            ExtensionsReady(true),
            ExtensionsReady(false, "main.lua:3: attempt to index nil"),
        )

        @JvmStatic
        fun clientbound(): List<ClientboundMessage> = listOf(
            Welcome(PROTOCOL_VERSION),
            DeclareKeybinds(listOf(
                KeybindDeclaration("open_menu", "Open menu", "Factory", "key.keyboard.g"),
                KeybindDeclaration("boost", "Boost", "Factory", "key.mouse.middle"),
            )),
            DeclareKeybinds(emptyList()),
            OpenScreen(1, fullTree),
            UpdateScreen(1, fullTree),
            OpenScreen(5, fullTree, fullscreen = true),
            UpdateScreen(5, fullTree, fullscreen = true),
            ShowToast("\"Sold\""),
            ShowToast("\"Sold\"", "\"3x Diamond for 30 coins\"", fancySword, 60_000),
            OpenScreen(2, Text("only", b, "\"plain\"")),
            CloseScreen(1),
            ExtensionMessage(ResourceId.of("example", "data"), "x".repeat(ProtocolLimits.MAX_EXTENSION_JSON_LENGTH)),
            ExtensionManifest(emptyList()),
            ExtensionManifest(listOf(
                ExtensionInfo("kiln_example", "1.0.0", "Beispiel", ByteArray(32) { it.toByte() }, 1234, ExtensionPermissions.ALL),
                ExtensionInfo("b", "", "", ByteArray(32), ProtocolLimits.MAX_EXTENSION_PACKAGE_BYTES, emptyList()),
                ExtensionInfo("c", "1", "Icon", ByteArray(32), 1, listOf("hud"), icon = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)),
                ExtensionInfo("d", "1", "Deps", ByteArray(32), 1, listOf("screens", "inventory"), dependencies = listOf("b", "c")),
            )),
            ExtensionChunk("kiln_example", 0, 1, byteArrayOf(1, 2, 3)),
            ExtensionChunk("x", 7, 8, Random(1).nextBytes(ProtocolLimits.MAX_EXTENSION_CHUNK_BYTES)),
            SetPostEffect("kiln_content:wobble"),
            SetPostEffect(null),
        )
    }

    @ParameterizedTest
    @MethodSource("serverbound")
    fun `serverbound roundtrip`(message: ServerboundMessage) {
        val bytes = MessageCodec.encodeServerbound(message)
        assertEquals(message, MessageCodec.decodeServerbound(bytes))
        assertArrayEquals(bytes, MessageCodec.encodeServerbound(MessageCodec.decodeServerbound(bytes)))
    }

    @ParameterizedTest
    @MethodSource("clientbound")
    fun `clientbound roundtrip`(message: ClientboundMessage) {
        val bytes = MessageCodec.encodeClientbound(message)
        assertEquals(message, MessageCodec.decodeClientbound(bytes))
    }

    @Test
    fun `negative coordinates and extreme varints survive`() {
        val tree = Panel("r", Bounds(-ProtocolLimits.MAX_COORDINATE, ProtocolLimits.MAX_COORDINATE, 0, ProtocolLimits.MAX_SIZE))
        val msg = OpenScreen(0, tree)
        assertEquals(msg, MessageCodec.decodeClientbound(MessageCodec.encodeClientbound(msg)))
    }

    // ---- rejection ------------------------------------------------------------------------------------------

    @Test
    fun `too deep tree is rejected on encode and decode`() {
        var element: Element = Text("leaf", b, "\"x\"")
        repeat(ProtocolLimits.MAX_DEPTH) { element = Panel("p$it", b, listOf(element)) }
        assertThrows<ProtocolException> { MessageCodec.encodeClientbound(OpenScreen(1, element)) }

        // hand-crafted: a chain of panels far deeper than allowed must fail cleanly, not overflow the stack
        val w = ProtocolWriter()
        w.varInt(2) // OPEN_SCREEN
        w.varInt(1)
        w.boolean(false) // fullscreen
        repeat(10_000) { i ->
            w.varInt(0); w.string("p$i", 64); w.signedVarInt(0); w.signedVarInt(0); w.varInt(1); w.varInt(1)
            w.boolean(false) // tooltip
            repeat(5) { w.boolean(false) } // background, texture, border, hover background, selected background
            w.boolean(false); w.boolean(false) // selected, clickable
            w.varInt(1)
        }
        assertThrows<ProtocolException> { MessageCodec.decodeClientbound(w.toByteArray()) }
    }

    @Test
    fun `too many elements are rejected`() {
        val children = List(ProtocolLimits.MAX_ELEMENTS) { Text("t$it", b, "\"\"") }
        assertThrows<ProtocolException> { MessageCodec.encodeClientbound(OpenScreen(1, Panel("root", b, children))) }
    }

    @Test
    fun `duplicate and invalid element ids are rejected`() {
        val dup = Panel("root", b, listOf(Text("x", b, "\"\""), Text("x", b, "\"\"")))
        assertThrows<ProtocolException> { MessageCodec.encodeClientbound(OpenScreen(1, dup)) }
        assertThrows<ProtocolException> { MessageCodec.encodeClientbound(OpenScreen(1, Text("bad id!", b, "\"\""))) }
        assertThrows<ProtocolException> { MessageCodec.encodeServerbound(ScreenClick(1, "", 0)) }
    }

    @Test
    fun `too long strings are rejected`() {
        val long = "x".repeat(ProtocolLimits.MAX_STRING_LENGTH + 1)
        assertThrows<ProtocolException> { MessageCodec.encodeClientbound(OpenScreen(1, Text("t", b, long))) }
        assertThrows<ProtocolException> {
            MessageCodec.encodeServerbound(ScreenInput(1, "f", "y".repeat(ProtocolLimits.MAX_TEXT_FIELD_LENGTH + 1)))
        }
        assertThrows<ProtocolException> {
            MessageCodec.encodeClientbound(OpenScreen(1, TextField("f", b, value = "abc", maxLength = 2)))
        }
    }

    @Test
    fun `oversized messages are rejected`() {
        assertThrows<ProtocolException> {
            MessageCodec.encodeServerbound(ExtensionMessage(ResourceId.of("a", "b"), "x".repeat(ProtocolLimits.MAX_EXTENSION_JSON_LENGTH + 1)))
        }
        assertThrows<ProtocolException> { MessageCodec.decodeServerbound(ByteArray(ProtocolLimits.MAX_SERVERBOUND_BYTES + 1)) }
        assertThrows<ProtocolException> { MessageCodec.decodeClientbound(ByteArray(ProtocolLimits.MAX_CLIENTBOUND_BYTES + 1)) }
    }

    @Test
    fun `v5 dependencies must be listed earlier and clientbound extension json may be larger`() {
        val sha = ByteArray(32)
        fun info(id: String, vararg deps: String) = ExtensionInfo(id, "1", id, sha, 10, emptyList(), dependencies = deps.toList())
        val ok = ExtensionManifest(listOf(info("ui_kit"), info("shop", "ui_kit")))
        assertEquals(ok, MessageCodec.decodeClientbound(MessageCodec.encodeClientbound(ok)))
        // dependency after the dependent, missing, self, duplicate, invalid id
        assertThrows<ProtocolException> { MessageCodec.encodeClientbound(ExtensionManifest(listOf(info("shop", "ui_kit"), info("ui_kit")))) }
        assertThrows<ProtocolException> { MessageCodec.encodeClientbound(ExtensionManifest(listOf(info("shop", "ui_kit")))) }
        assertThrows<ProtocolException> { MessageCodec.encodeClientbound(ExtensionManifest(listOf(info("shop", "shop")))) }
        assertThrows<ProtocolException> { MessageCodec.encodeClientbound(ExtensionManifest(listOf(info("a"), info("shop", "a", "a")))) }
        assertThrows<ProtocolException> { MessageCodec.encodeClientbound(ExtensionManifest(listOf(info("shop", "Bad-Id")))) }
        val big = ExtensionMessage(ResourceId.of("shop", "offers"), "\"" + "x".repeat(ProtocolLimits.MAX_CLIENTBOUND_EXTENSION_JSON_LENGTH - 2) + "\"")
        assertEquals(big, MessageCodec.decodeClientbound(MessageCodec.encodeClientbound(big)))
        assertThrows<ProtocolException> { MessageCodec.encodeServerbound(big) }
        assertThrows<ProtocolException> {
            MessageCodec.encodeClientbound(ExtensionMessage(ResourceId.of("a", "b"), "x".repeat(ProtocolLimits.MAX_CLIENTBOUND_EXTENSION_JSON_LENGTH + 1)))
        }
    }

    @Test
    fun `invalid v3 extension messages are rejected`() {
        val sha = ByteArray(32)
        fun info(id: String = "ok", sha256: ByteArray = sha, size: Int = 10, permissions: List<String> = emptyList()) =
            ExtensionManifest(listOf(ExtensionInfo(id, "1", "n", sha256, size, permissions)))
        assertThrows<ProtocolException> { MessageCodec.encodeClientbound(info(id = "Bad-Id")) }
        assertThrows<ProtocolException> { MessageCodec.encodeClientbound(info(id = "a".repeat(33))) }
        assertThrows<ProtocolException> { MessageCodec.encodeClientbound(info(sha256 = ByteArray(31))) }
        assertThrows<ProtocolException> { MessageCodec.encodeClientbound(info(size = 0)) }
        assertThrows<ProtocolException> { MessageCodec.encodeClientbound(info(size = ProtocolLimits.MAX_EXTENSION_PACKAGE_BYTES + 1)) }
        assertThrows<ProtocolException> {
            MessageCodec.encodeClientbound(ExtensionManifest(listOf(ExtensionInfo("ok", "1", "n", sha, 10, emptyList(), ByteArray(ProtocolLimits.MAX_EXTENSION_ICON_BYTES + 1)))))
        }
        assertThrows<ProtocolException> { MessageCodec.encodeClientbound(info(permissions = listOf("hud", "hud"))) }
        assertThrows<ProtocolException> { MessageCodec.encodeClientbound(info(permissions = listOf("root"))) }
        assertThrows<ProtocolException> {
            MessageCodec.encodeClientbound(ExtensionManifest(listOf(info().extensions[0], info().extensions[0])))
        }
        assertThrows<ProtocolException> { MessageCodec.encodeClientbound(ExtensionChunk("a", 1, 1, byteArrayOf(1))) }
        assertThrows<ProtocolException> { MessageCodec.encodeClientbound(ExtensionChunk("a", 0, 1, ByteArray(0))) }
        assertThrows<ProtocolException> {
            MessageCodec.encodeClientbound(ExtensionChunk("a", 0, ProtocolLimits.MAX_EXTENSION_CHUNKS + 1, byteArrayOf(1)))
        }
        assertThrows<ProtocolException> {
            MessageCodec.encodeClientbound(ExtensionChunk("a", 0, 1, ByteArray(ProtocolLimits.MAX_EXTENSION_CHUNK_BYTES + 1)))
        }
        assertThrows<ProtocolException> { MessageCodec.encodeServerbound(ExtensionRequest(listOf("a", "a"))) }
        assertThrows<ProtocolException> { MessageCodec.encodeServerbound(ExtensionRequest(listOf("a.b"))) }
        assertThrows<ProtocolException> {
            MessageCodec.encodeServerbound(ExtensionsReady(false, "x".repeat(ProtocolLimits.MAX_EXTENSION_ERROR_LENGTH + 1)))
        }
        // channel must be <extension id>:<name>
        assertThrows<ProtocolException> { MessageCodec.encodeServerbound(ExtensionMessage(ResourceId.of("a.b", "c"), "{}")) }
        assertThrows<ProtocolException> { MessageCodec.encodeServerbound(ExtensionMessage(ResourceId.of("a", "c/d"), "{}")) }
        assertEquals(64, ExtensionInfo("a", "", "", ByteArray(32) { 0xAB.toByte() }, 1, emptyList()).sha256Hex.length)
        assertEquals(1, info(size = ProtocolLimits.MAX_EXTENSION_CHUNK_BYTES).extensions[0].chunkCount)
        assertEquals(2, info(size = ProtocolLimits.MAX_EXTENSION_CHUNK_BYTES + 1).extensions[0].chunkCount)
        assertEquals(ProtocolLimits.MAX_EXTENSION_CHUNKS, info(size = ProtocolLimits.MAX_EXTENSION_PACKAGE_BYTES).extensions[0].chunkCount)
    }

    @Test
    fun `invalid values are rejected`() {
        assertThrows<ProtocolException> { MessageCodec.encodeClientbound(OpenScreen(1, ProgressBar("p", b, 1.5f))) }
        assertThrows<ProtocolException> { MessageCodec.encodeClientbound(OpenScreen(1, ProgressBar("p", b, Float.NaN))) }
        assertThrows<ProtocolException> { MessageCodec.encodeClientbound(OpenScreen(1, ItemDisplay("i", b, diamond.withCount(0)))) }
        assertThrows<ProtocolException> { MessageCodec.encodeClientbound(OpenScreen(-1, Text("t", b, "\"\""))) }
        assertThrows<ProtocolException> { MessageCodec.encodeServerbound(Hello(1, listOf("Not Valid"))) }
        assertThrows<ProtocolException> {
            MessageCodec.encodeClientbound(DeclareKeybinds(listOf(
                KeybindDeclaration("a", "A", "C", "key.keyboard.a"),
                KeybindDeclaration("a", "B", "C", "key.keyboard.b"),
            )))
        }
    }

    // ---- rejection of the v2 additions ----------------------------------------------------------------------

    private fun rejects(root: Element) {
        assertThrows<ProtocolException>(root.toString()) { MessageCodec.encodeClientbound(OpenScreen(1, root)) }
    }

    private fun inRoot(vararg children: Element): Element = Panel("root", b, children.toList())

    @Test
    fun `invalid items are rejected`() {
        rejects(ItemDisplay("i", b, diamond.withCount(ProtocolLimits.MAX_ITEM_COUNT + 1)))
        rejects(ItemDisplay("i", b, ItemStackData(diamond.itemId, 1, byteArrayOf(1, 2, 3))))
        rejects(ItemDisplay("i", b, ItemStackData(diamond.itemId, 1, ByteArray(0))))
        val huge = ByteArray(ProtocolLimits.MAX_ITEM_NBT_BYTES + 1).also { it[0] = 0x1F; it[1] = 0x8B.toByte() }
        rejects(ItemSlot("s", b, ItemStackData(diamond.itemId, 1, huge)))
        assertThrows<ProtocolException> {
            MessageCodec.encodeClientbound(ShowToast("\"t\"", icon = ItemStackData(diamond.itemId, 1, byteArrayOf(0, 0))))
        }
        // decode side: a valid message whose gzip magic is corrupted
        val bytes = MessageCodec.encodeClientbound(OpenScreen(1, ItemDisplay("i", b, fancySword)))
        val magic = (0 until bytes.size - 1).first { bytes[it] == 0x1F.toByte() && bytes[it + 1] == 0x8B.toByte() }
        bytes[magic] = 0
        assertThrows<ProtocolException> { MessageCodec.decodeClientbound(bytes) }
    }

    @Test
    fun `modals only as direct children of the root`() {
        rejects(Modal("m", b))
        rejects(inRoot(Panel("p", b, listOf(Modal("m", b)))))
        rejects(inRoot(Modal("m", b, listOf(Modal("n", b)))))
        MessageCodec.encodeClientbound(OpenScreen(1, inRoot(Modal("m", b), Modal("n", b))))
        assertEquals("n", inRoot(Text("t", b, "\"\""), Modal("m", b), Modal("n", b), Text("u", b, "\"\"")).topModal()?.id)
        assertEquals(null, inRoot(Text("t", b, "\"\"")).topModal())

        // decode side: hand-crafted modal as root
        val w = ProtocolWriter()
        w.varInt(2); w.varInt(1); w.boolean(false)
        w.varInt(13); w.string("m", 64); w.signedVarInt(0); w.signedVarInt(0); w.varInt(1); w.varInt(1)
        w.boolean(false); w.boolean(false); w.boolean(false); w.int(0); w.varInt(0)
        assertThrows<ProtocolException> { MessageCodec.decodeClientbound(w.toByteArray()) }
    }

    @Test
    fun `invalid inputs are rejected`() {
        val options = listOf(DropdownOption("a", "\"A\""), DropdownOption("b", "\"B\""))
        rejects(Dropdown("d", b, options, selected = "c"))
        rejects(Dropdown("d", b, options + DropdownOption("a", "\"again\"")))
        rejects(Dropdown("d", b, List(ProtocolLimits.MAX_DROPDOWN_OPTIONS + 1) { DropdownOption("o$it", "\"\"") }))
        rejects(Dropdown("d", b, listOf(DropdownOption("x".repeat(ProtocolLimits.MAX_OPTION_VALUE_LENGTH + 1), "\"\""))))

        rejects(Slider("s", b, 10.0, 0.0, 1.0, 5.0))
        rejects(Slider("s", b, 0.0, 10.0, 1.0, 11.0))
        rejects(Slider("s", b, 0.0, 10.0, -1.0, 5.0))
        rejects(Slider("s", b, 0.0, Double.POSITIVE_INFINITY, 1.0, 5.0))
        rejects(Slider("s", b, 0.0, 10.0, Double.NaN, 5.0))
        rejects(Slider("s", b, 0.0, 1e16, 1.0, 5.0))

        rejects(TextField("f", b, "abc", numeric = NumericRange(0.0, 10.0)))
        rejects(TextField("f", b, "11", numeric = NumericRange(0.0, 10.0)))
        rejects(TextField("f", b, "1.5", numeric = NumericRange(0.0, 10.0, integer = true)))
        rejects(TextField("f", b, "", numeric = NumericRange(10.0, 0.0)))
        MessageCodec.encodeClientbound(OpenScreen(1, TextField("f", b, "", numeric = NumericRange(1.0, 10.0))))
        MessageCodec.encodeClientbound(OpenScreen(1, TextField("f", b, "1.5", numeric = NumericRange(0.0, 10.0, integer = false))))

        rejects(ScrollPanel("s", b, contentHeight = ProtocolLimits.MAX_SCROLL_CONTENT_HEIGHT + 1))
        rejects(ScrollPanel("s", b, contentHeight = -1))
        rejects(Text("t", Bounds(0, ProtocolLimits.MAX_COORDINATE + 1, 1, 1), "\"\""))
        rejects(Text("t", b, "\"\"", tooltip = "x".repeat(ProtocolLimits.MAX_STRING_LENGTH + 1)))
    }

    @Test
    fun `invalid v2 messages are rejected`() {
        assertThrows<ProtocolException> { MessageCodec.encodeServerbound(Viewport(0, 100)) }
        assertThrows<ProtocolException> { MessageCodec.encodeServerbound(Viewport(100, ProtocolLimits.MAX_SIZE + 1)) }
        assertThrows<ProtocolException> { MessageCodec.encodeServerbound(ScreenClick(1, "x", 0, 8)) }
        assertThrows<ProtocolException> { MessageCodec.encodeServerbound(ScreenClick(1, "x", 0, -1)) }
        assertThrows<ProtocolException> { MessageCodec.encodeServerbound(ModalDismiss(1, "bad id!")) }
        assertThrows<ProtocolException> { MessageCodec.encodeClientbound(ShowToast("\"t\"", displayMillis = 0)) }
        assertThrows<ProtocolException> { MessageCodec.encodeClientbound(ShowToast("\"t\"", displayMillis = ProtocolLimits.MAX_TOAST_MILLIS + 1)) }
        // hand-crafted click with modifier bits outside ClickModifiers.ALL
        val w = ProtocolWriter().apply { varInt(1); varInt(1); string("x", 64); varInt(0); varInt(9) }
        assertThrows<ProtocolException> { MessageCodec.decodeServerbound(w.toByteArray()) }
    }

    @Test
    fun `validation helpers`() {
        val range = NumericRange(-5.0, 5.0, integer = true)
        assertTrue(range.accepts("-5") && range.accepts("0") && range.accepts("5"))
        assertTrue(!range.accepts("6") && !range.accepts("1.5") && !range.accepts("") && !range.accepts("x"))
        assertTrue(NumericRange(0.0, 1.0, integer = false).accepts("0.25"))
        val slider = Slider("s", b, 0.0, 10.0, 2.5, 0.0)
        assertTrue(slider.accepts(0.0) && slider.accepts(7.5) && slider.accepts(10.0))
        assertTrue(!slider.accepts(1.0) && !slider.accepts(-2.5) && !slider.accepts(Double.NaN))
        assertTrue(Slider("s", b, 0.0, 1.0, 0.0, 0.0).accepts(0.123))
        assertTrue(ScreenClick(1, "x", 0, ClickModifiers.SHIFT or ClickModifiers.ALT).shift)
    }

    @Test
    fun `truncated and trailing data are rejected`() {
        for (message in clientbound()) {
            val bytes = MessageCodec.encodeClientbound(message)
            for (cut in listOf(0, 1, bytes.size / 2, bytes.size - 1).distinct().filter { it < bytes.size }) {
                assertThrows<ProtocolException>("cut $cut of $message") { MessageCodec.decodeClientbound(bytes.copyOf(cut)) }
            }
            assertThrows<ProtocolException> { MessageCodec.decodeClientbound(bytes + 0) }
        }
        for (message in serverbound()) {
            val bytes = MessageCodec.encodeServerbound(message)
            assertThrows<ProtocolException> { MessageCodec.decodeServerbound(bytes.copyOf(bytes.size - 1)) }
            assertThrows<ProtocolException> { MessageCodec.decodeServerbound(bytes + 0) }
        }
    }

    @Test
    fun `unknown types and lying lengths are rejected`() {
        assertThrows<ProtocolException> { MessageCodec.decodeServerbound(byteArrayOf(99)) }
        assertThrows<ProtocolException> { MessageCodec.decodeClientbound(byteArrayOf(99)) }
        // varint longer than 5 bytes
        assertThrows<ProtocolException> { MessageCodec.decodeServerbound(byteArrayOf(-1, -1, -1, -1, -1, -1)) }
        // Hello announcing a huge extension list with no data
        val w = ProtocolWriter().apply { varInt(0); varInt(1); varInt(Int.MAX_VALUE) }
        assertThrows<ProtocolException> { MessageCodec.decodeServerbound(w.toByteArray()) }
        // extension payload claiming more bytes than present
        val x = ProtocolWriter().apply { varInt(6); string("a:b", 256); varInt(10_000) }
        assertThrows<ProtocolException> { MessageCodec.decodeServerbound(x.toByteArray()) }
        // invalid UTF-8
        val u = ProtocolWriter().apply { varInt(4); varInt(2); byte(0xC3); byte(0x28) }
        assertThrows<ProtocolException> { MessageCodec.decodeServerbound(u.toByteArray()) }
    }

    @Test
    fun `random garbage never throws anything but ProtocolException`() {
        val random = Random(42)
        repeat(20_000) {
            val bytes = random.nextBytes(random.nextInt(1, 64))
            runCatching { MessageCodec.decodeServerbound(bytes) }.exceptionOrNull()?.let { assertTrue(it is ProtocolException, it.toString()) }
            runCatching { MessageCodec.decodeClientbound(bytes) }.exceptionOrNull()?.let { assertTrue(it is ProtocolException, it.toString()) }
        }
        // mutations of valid messages
        val valid = MessageCodec.encodeClientbound(OpenScreen(1, fullTree))
        repeat(20_000) {
            val bytes = valid.copyOf()
            repeat(random.nextInt(1, 4)) { bytes[random.nextInt(bytes.size)] = random.nextInt(256).toByte() }
            runCatching { MessageCodec.decodeClientbound(bytes) }.exceptionOrNull()?.let { assertTrue(it is ProtocolException, it.toString()) }
        }
    }
}
