package io.github.ezoushen.blur.cmp

/**
 * Controls when tint color is applied relative to the blur effect.
 *
 * [POST_BLUR] (default): Tint applied after blur, matching Apple's UIVisualEffectView.
 * [PRE_BLUR]: Tint blended with content before blur, creating a softer diffused look.
 */
enum class TintOrder {
    PRE_BLUR,
    POST_BLUR
}
