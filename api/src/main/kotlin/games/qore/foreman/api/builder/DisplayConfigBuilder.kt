package games.qore.foreman.api.builder

import games.qore.foreman.api.data.DisplayConfig
import games.qore.foreman.api.data.RenderShape

class DisplayConfigBuilder {
    private var color: Int = 0x00FF00
    private var name: String? = null
    private var glow: Boolean = true
    private var shape: RenderShape = RenderShape.CUBE
    private var size: Float = 1.0f
    private var visibleDistance: Float = 128.0f

    fun color(rgb: Int): DisplayConfigBuilder {
        this.color = rgb
        return this
    }

    fun color(r: Int, g: Int, b: Int): DisplayConfigBuilder {
        this.color = (r shl 16) or (g shl 8) or b
        return this
    }

    fun name(name: String): DisplayConfigBuilder {
        this.name = name
        return this
    }

    fun glow(glow: Boolean): DisplayConfigBuilder {
        this.glow = glow
        return this
    }

    fun shape(shape: RenderShape): DisplayConfigBuilder {
        this.shape = shape
        return this
    }

    fun size(size: Float): DisplayConfigBuilder {
        this.size = size
        return this
    }

    fun visibleDistance(distance: Float): DisplayConfigBuilder {
        this.visibleDistance = distance
        return this
    }

    fun build(): DisplayConfig {
        return DisplayConfig(color, name, glow, shape, size, visibleDistance)
    }
}
