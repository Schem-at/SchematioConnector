package io.schemat.connector.fabric.client.compat

// Input-event compatibility shims for MC 1.21.8.
//
// 1.21.9 introduced event objects (net.minecraft.client.input.MouseButtonEvent /
// KeyEvent / CharacterEvent) for GUI input callbacks. The mod's widgets are
// written against the event-shaped methods; on 1.21.8 the old primitive-based
// overrides delegate to them using these shims. A Stonecutter string replacement
// in fabric/stonecutter.gradle.kts rewrites the net.minecraft.client.input
// imports to this package for <1.21.9 builds, so the shims are only referenced
// (and only compiled) there.

//? if <1.21.9 {
/*import net.minecraft.client.gui.screens.Screen
import net.minecraft.util.StringUtil
import org.lwjgl.glfw.GLFW

class MouseButtonEvent(private val mx: Double, private val my: Double, private val btn: Int = 0) {
    fun x(): Double = mx
    fun y(): Double = my
    fun button(): Int = btn
    fun hasShiftDown(): Boolean = Screen.hasShiftDown()
}

class KeyEvent(private val keyCode: Int, private val scanCode: Int, private val mods: Int) {
    fun key(): Int = keyCode
    fun isSelectAll(): Boolean = Screen.isSelectAll(keyCode)
    fun isCopy(): Boolean = Screen.isCopy(keyCode)
    fun isCut(): Boolean = Screen.isCut(keyCode)
    fun isPaste(): Boolean = Screen.isPaste(keyCode)
    fun isConfirmation(): Boolean =
        keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER
    fun hasShiftDown(): Boolean = Screen.hasShiftDown()
}

class CharacterEvent(private val codepoint: Int, private val mods: Int) {
    fun codepointAsString(): String = String(Character.toChars(codepoint))
    fun isAllowedChatCharacter(): Boolean =
        codepointAsString().all { StringUtil.isAllowedChatCharacter(it) }
}
*///?}
