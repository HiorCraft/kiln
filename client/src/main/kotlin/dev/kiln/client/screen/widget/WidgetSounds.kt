package dev.kiln.client.screen.widget

import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents

/** The vanilla button click, for clickable elements that are not vanilla buttons. */
internal fun playClickSound() {
    Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f))
}
