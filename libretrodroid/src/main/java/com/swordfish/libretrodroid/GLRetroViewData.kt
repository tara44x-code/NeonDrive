/*
 *     Copyright (C) 2022  Filippo Scognamiglio
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.swordfish.libretrodroid

import android.content.Context

class GLRetroViewData(context: Context) {
    var coreFilePath: String? = null
    var gameFilePath: String? = null
    var gameFileBytes: ByteArray? = null
    var gameVirtualFiles: List<VirtualFile> = listOf()
    var systemDirectory: String = context.filesDir.absolutePath
    var savesDirectory: String = context.filesDir.absolutePath
    var variables: Array<Variable> = arrayOf()
    var saveRAMState: ByteArray? = null
    var shader: ShaderConfig = ShaderConfig.Default
    var colorAdjust: ColorAdjust = ColorAdjust()
    var viewportAlignment = ViewportAlignment.CENTER
    var rumbleEventsEnabled: Boolean = true
    var preferLowLatencyAudio: Boolean = true
    var skipDuplicateFrames: Boolean = false
    var enableMicrophone: Boolean = false
    var immersiveMode: ImmersiveMode? = null
}

enum class ViewportAlignment(val value: Int) {
    CENTER(0),
    TOP(1),
    BOTTOM(2)
}

/** NeonDrive: picture adjustment applied after any shader. Neutral values add no extra work. */
data class ColorAdjust(
    val brightness: Float = 0f,   // -0.5 .. 0.5
    val contrast: Float = 1f,     //  0.5 .. 1.5
    val saturation: Float = 1f,   //  0.0 .. 2.0
) {
    val isNeutral: Boolean get() = brightness == 0f && contrast == 1f && saturation == 1f
}
