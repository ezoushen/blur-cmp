//
//  AnimatableColorState.swift
//  BlurView
//
//  Created by EZOU on 2025/12/8.
//

import SwiftUI

// MARK: - Animatable Color Support

/// Animatable color state using **premultiplied alpha** for correct color transitions.
///
/// This stores RGB values multiplied by visibility: `(r * v, g * v, b * v, v)`
/// Standard linear interpolation then automatically produces correct visual results
/// without any special-case logic in the arithmetic operators.B
///
/// Visual behavior (matching UIKit):
/// - color → nil: Color fades out smoothly without darkening toward black
/// - nil → color: Color fades in with the target RGB (no color shift)
/// - color → color: RGB interpolates linearly between colors
///
/// The math works because:
/// - Interpolating premultiplied `(r1*v1, ...)` to `(r2*v2, ...)` at progress t:
///   `current = start + (end - start) * t`
/// - When unpremultiplied (RGB / visibility), yields the correct blended color
/// - At t=0.5 from red(1,0,0,1) to nil(0,0,0,0): premult=(0.5,0,0,0.5) → unpremult=(1,0,0,0.5) ✓
public struct AnimatableColorState: VectorArithmetic {
    /// Red component premultiplied by visibility (r * visibility)
    public var premultipliedRed: Double
    /// Green component premultiplied by visibility (g * visibility)
    public var premultipliedGreen: Double
    /// Blue component premultiplied by visibility (b * visibility)
    public var premultipliedBlue: Double
    /// Visibility/alpha: 0 = nil/transparent, otherwise the alpha value
    public var visibility: Double

    public static var zero: AnimatableColorState {
        AnimatableColorState(premultipliedRed: 0, premultipliedGreen: 0, premultipliedBlue: 0, visibility: 0)
    }

    public var magnitudeSquared: Double {
        premultipliedRed * premultipliedRed +
        premultipliedGreen * premultipliedGreen +
        premultipliedBlue * premultipliedBlue +
        visibility * visibility
    }

    public static func + (lhs: AnimatableColorState, rhs: AnimatableColorState) -> AnimatableColorState {
        AnimatableColorState(
            premultipliedRed: lhs.premultipliedRed + rhs.premultipliedRed,
            premultipliedGreen: lhs.premultipliedGreen + rhs.premultipliedGreen,
            premultipliedBlue: lhs.premultipliedBlue + rhs.premultipliedBlue,
            visibility: lhs.visibility + rhs.visibility
        )
    }

    public static func - (lhs: AnimatableColorState, rhs: AnimatableColorState) -> AnimatableColorState {
        AnimatableColorState(
            premultipliedRed: lhs.premultipliedRed - rhs.premultipliedRed,
            premultipliedGreen: lhs.premultipliedGreen - rhs.premultipliedGreen,
            premultipliedBlue: lhs.premultipliedBlue - rhs.premultipliedBlue,
            visibility: lhs.visibility - rhs.visibility
        )
    }

    public mutating func scale(by rhs: Double) {
        premultipliedRed *= rhs
        premultipliedGreen *= rhs
        premultipliedBlue *= rhs
        visibility *= rhs
    }

    /// Initialize from UIColor, storing premultiplied RGB values (RGB * alpha).
    public init(from color: UIColor) {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        color.getRed(&r, green: &g, blue: &b, alpha: &a)
        // Store premultiplied: RGB * alpha
        self.premultipliedRed = Double(r * a)
        self.premultipliedGreen = Double(g * a)
        self.premultipliedBlue = Double(b * a)
        self.visibility = Double(a)
    }

    public init(premultipliedRed: Double, premultipliedGreen: Double, premultipliedBlue: Double, visibility: Double) {
        self.premultipliedRed = premultipliedRed
        self.premultipliedGreen = premultipliedGreen
        self.premultipliedBlue = premultipliedBlue
        self.visibility = visibility
    }

    /// Convert to UIColor by unpremultiplying RGB (divide by visibility).
    /// Returns nil if visibility is effectively zero.
    public func uiColor() -> UIColor? {
        guard visibility > 0.001 else { return nil }
        // Unpremultiply: divide by visibility to recover actual RGB
        let r = premultipliedRed / visibility
        let g = premultipliedGreen / visibility
        let b = premultipliedBlue / visibility
        return UIColor(
            red: CGFloat(max(0, min(1, r))),
            green: CGFloat(max(0, min(1, g))),
            blue: CGFloat(max(0, min(1, b))),
            alpha: CGFloat(max(0, min(1, visibility)))
        )
    }
}
