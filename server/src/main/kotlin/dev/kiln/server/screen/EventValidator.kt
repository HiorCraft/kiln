package dev.kiln.server.screen

import dev.kiln.protocol.Button
import dev.kiln.protocol.Checkbox
import dev.kiln.protocol.Custom
import dev.kiln.protocol.Dropdown
import dev.kiln.protocol.ItemSlot
import dev.kiln.protocol.ModalDismiss
import dev.kiln.protocol.Panel
import dev.kiln.protocol.ScreenClick
import dev.kiln.protocol.ScreenInput
import dev.kiln.protocol.Slider
import dev.kiln.protocol.TextField

/** Outcome of checking a client screen event against the screen state the server last sent. */
sealed interface Validation {
    data object Valid : Validation

    data class Invalid(val reason: String) : Validation
}

/**
 * Checks client screen events against the open screen. Pure function of the event and the last sent tree, so it
 * is unit-testable; the caller drops invalid events silently (debug log only).
 *
 * Modal blockade: while the tree contains a modal, only elements inside the topmost modal accept clicks and input.
 */
object EventValidator {

    /** [openScreenId]: id of the currently open screen or `null`; [tree]: last tree sent for it. */
    fun validateClick(event: ScreenClick, openScreenId: Int?, tree: ConvertedScreen?): Validation {
        screenMismatch(event.screenId, openScreenId, tree)?.let { return it }
        val element = tree!!.elements[event.elementId] ?: return Validation.Invalid("unknown element '${event.elementId}'")
        blockedByModal(event.elementId, tree)?.let { return it }
        return when (element) {
            is Button -> if (element.enabled) Validation.Valid else Validation.Invalid("button '${element.id}' is disabled")
            is ItemSlot -> if (element.enabled) Validation.Valid else Validation.Invalid("slot '${element.id}' is disabled")
            is Panel -> if (element.clickable) Validation.Valid else Validation.Invalid("panel '${element.id}' is not clickable")
            is Custom -> Validation.Valid
            else -> Validation.Invalid("element '${element.id}' (${element::class.simpleName}) is not clickable")
        }
    }

    fun validateInput(event: ScreenInput, openScreenId: Int?, tree: ConvertedScreen?): Validation {
        screenMismatch(event.screenId, openScreenId, tree)?.let { return it }
        val element = tree!!.elements[event.elementId] ?: return Validation.Invalid("unknown element '${event.elementId}'")
        blockedByModal(event.elementId, tree)?.let { return it }
        val value = event.value
        return when (element) {
            is TextField -> when {
                value.length > element.maxLength -> Validation.Invalid("input for '${element.id}' longer than maxLength ${element.maxLength}")
                element.numeric != null && value.isNotEmpty() && !element.numeric!!.accepts(value) ->
                    Validation.Invalid("input '$value' for '${element.id}' outside ${element.numeric}")
                else -> Validation.Valid
            }
            is Dropdown -> when {
                !element.enabled -> Validation.Invalid("dropdown '${element.id}' is disabled")
                element.options.none { it.value == value } -> Validation.Invalid("dropdown '${element.id}' has no option '$value'")
                else -> Validation.Valid
            }
            is Checkbox -> when {
                !element.enabled -> Validation.Invalid("checkbox '${element.id}' is disabled")
                value != "true" && value != "false" -> Validation.Invalid("checkbox value '$value' is not true/false")
                else -> Validation.Valid
            }
            is Slider -> {
                val number = value.toDoubleOrNull()
                when {
                    !element.enabled -> Validation.Invalid("slider '${element.id}' is disabled")
                    number == null || !element.accepts(number) ->
                        Validation.Invalid("slider value '$value' for '${element.id}' outside ${element.min}..${element.max} step ${element.step}")
                    else -> Validation.Valid
                }
            }
            else -> Validation.Invalid("element '${element.id}' (${element::class.simpleName}) is not an input")
        }
    }

    fun validateModalDismiss(event: ModalDismiss, openScreenId: Int?, tree: ConvertedScreen?): Validation {
        screenMismatch(event.screenId, openScreenId, tree)?.let { return it }
        val top = tree!!.topModal ?: return Validation.Invalid("no modal open")
        return if (top.id == event.modalId) Validation.Valid
        else Validation.Invalid("modal '${event.modalId}' is not the topmost modal ('${top.id}')")
    }

    fun validateClosed(screenId: Int, openScreenId: Int?): Validation =
        if (openScreenId != null && screenId == openScreenId) Validation.Valid
        else Validation.Invalid("screen $screenId is not open (open: $openScreenId)")

    private fun blockedByModal(elementId: String, tree: ConvertedScreen): Validation.Invalid? {
        val top = tree.topModal ?: return null
        return if (tree.modalOf[elementId] == top.id) null
        else Validation.Invalid("element '$elementId' is blocked by modal '${top.id}'")
    }

    private fun screenMismatch(screenId: Int, openScreenId: Int?, tree: ConvertedScreen?): Validation.Invalid? = when {
        openScreenId == null || tree == null -> Validation.Invalid("no screen open")
        screenId != openScreenId -> Validation.Invalid("screen $screenId is not open (open: $openScreenId)")
        else -> null
    }
}
