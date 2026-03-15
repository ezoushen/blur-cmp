//
//  BlurViewCore.swift
//  BlurView
//
//  Shared infrastructure for blur views following protocol-oriented design.
//  Eliminates code duplication between RealTimeBlurView and RealTimeVariableBlurView.
//

import UIKit
import QuartzCore

// MARK: - Debug Logging

/// Debug-only print function. Compiles to no-op in release builds.
@inline(__always)
internal func debugLog(_ message: @autoclosure () -> String) {
    #if DEBUG
    print(message())
    #endif
}

// MARK: - Blend Order

/// Determines when the tint blend mode is applied relative to the blur effect.
public enum TintBlendOrder: Sendable {
    /// Automatically determine blend order based on tint and blend mode configuration.
    /// Uses `.beforeBlur` when tintColor is set AND blendMode is not `.normal`,
    /// otherwise uses `.afterBlur`.
    /// This is the recommended default for most use cases.
    case auto

    /// Apply blur first, then blend tint on top.
    /// The tint color blends with the already-blurred content.
    /// Best for: frosted glass overlays, subtle color tints.
    case afterBlur

    /// Apply tint blend first, then blur the result.
    /// The blend mode affects sharp content, which is then blurred.
    /// Best for: bloom effects, light leaks, dramatic glows.
    /// Note: Uses an additional internal backdrop layer for correct compositing.
    case beforeBlur
}

// MARK: - Blend Mode

/// Blend modes for tint layer compositing.
/// These map to CIFilter blend mode names used with CALayer.compositingFilter.
public enum BlurBlendMode: String, CaseIterable, Sendable {
    /// Standard alpha compositing (no blend mode applied)
    case normal = ""

    /// Brightens the background to reflect the tint color.
    /// Creates vivid highlights and saturation effects.
    case colorDodge = "colorDodgeBlendMode"

    /// Darkens the background by increasing contrast.
    /// Creates deep shadows and rich darks.
    case colorBurn = "colorBurnBlendMode"

    /// Multiplies colors, always producing darker results.
    /// Useful for shadow effects.
    case multiply = "multiplyBlendMode"

    /// Inverts, multiplies, and inverts again.
    /// Always produces lighter results.
    case screen = "screenBlendMode"

    /// Combines multiply and screen based on background color.
    /// Preserves highlights and shadows.
    case overlay = "overlayBlendMode"

    /// Softens the blend, similar to diffused lighting.
    case softLight = "softLightBlendMode"

    /// Applies strong multiply or screen based on tint color.
    case hardLight = "hardLightBlendMode"

    /// Selects the darker of the two colors.
    case darken = "darkenBlendMode"

    /// Selects the lighter of the two colors.
    case lighten = "lightenBlendMode"

    /// Subtracts colors, revealing differences.
    case difference = "differenceBlendMode"

    /// Similar to difference but with lower contrast.
    case exclusion = "exclusionBlendMode"

    /// The CIFilter name string, or nil for normal mode.
    internal var filterName: String? {
        rawValue.isEmpty ? nil : rawValue
    }
}

// MARK: - Backdrop Layer Provider

/// Centralized provider for CABackdropLayer and CAFilter extraction.
/// Extracts private API capabilities from UIVisualEffectView once and caches them.
public enum BackdropLayerProvider {

    // MARK: - Cached State

    private static var _preparedOnce = false
    private static var _indexOfBackdropView: Int = -1
    private static var _indexOfVisualEffectSubview: Int = -1
    private static var _gaussianBlurFilter: NSObject?
    private static var _filterClass: AnyClass?
    private static var _backdropLayerClass: AnyClass?

    // MARK: - Preparation

    /// Extract internal structure from UIVisualEffectView (called once)
    public static func prepareOnce() {
        guard !_preparedOnce else { return }
        _preparedOnce = true

        let visualEffectView = UIVisualEffectView(effect: UIBlurEffect(style: .light))

        for i in 0..<visualEffectView.subviews.count {
            let view = visualEffectView.subviews[i]
            let className = NSStringFromClass(type(of: view))

            if className.hasSuffix("BackdropView") {
                _indexOfBackdropView = i
                _backdropLayerClass = type(of: view.layer)

                if let filters = view.layer.value(forKey: "filters") as? [NSObject] {
                    for filter in filters {
                        if let name = filter.value(forKey: "name") as? String,
                           name == "gaussianBlur",
                           filter.value(forKey: "inputRadius") is NSNumber {
                            _gaussianBlurFilter = filter
                            _filterClass = type(of: filter)
                            break
                        }
                    }
                }
            } else if className.hasSuffix("Subview") && !className.contains("Content") {
                _indexOfVisualEffectSubview = i
            }
        }
    }

    /// Reset preparation state
    public static func resetPreparation() {
        _preparedOnce = false
        _indexOfBackdropView = -1
        _indexOfVisualEffectSubview = -1
        _gaussianBlurFilter = nil
        _filterClass = nil
        _backdropLayerClass = nil
    }

    /// Whether extraction was successful for uniform blur
    public static var isValidForUniformBlur: Bool {
        prepareOnce()
        return _indexOfBackdropView > -1 &&
               _indexOfVisualEffectSubview > -1 &&
               _gaussianBlurFilter != nil
    }

    /// Whether extraction was successful for variable blur
    public static var isValidForVariableBlur: Bool {
        prepareOnce()
        return _backdropLayerClass != nil && _filterClass != nil
    }

    // MARK: - Layer Creation

    /// Create a new backdrop layer instance
    public static func createBackdropLayer() -> CALayer? {
        prepareOnce()
        guard let layerClass = _backdropLayerClass as? CALayer.Type else { return nil }
        return layerClass.init()
    }

    // MARK: - Filter Creation

    /// Create a gaussianBlur filter with custom radius (for uniform blur)
    public static func createGaussianBlurFilter(radius: CGFloat) -> NSObject? {
        prepareOnce()
        guard let templateFilter = _gaussianBlurFilter else { return nil }

        let copySelector = NSSelectorFromString("copy")
        guard templateFilter.responds(to: copySelector),
              let copiedFilter = templateFilter.perform(copySelector)?
                .takeRetainedValue() as? NSObject else { return nil }

        copiedFilter.setValue(radius, forKey: "inputRadius")
        copiedFilter.setValue(true, forKey: "inputNormalizeEdges")
        return copiedFilter
    }

    /// Create a variableBlur filter with mask image
    public static func createVariableBlurFilter(radius: CGFloat, maskImage: CGImage) -> NSObject? {
        prepareOnce()
        guard let filterClass = _filterClass else { return nil }

        let selector = NSSelectorFromString("filterWithType:")
        guard (filterClass as AnyObject).responds(to: selector),
              let result = (filterClass as AnyObject).perform(selector, with: "variableBlur"),
              let filter = result.takeUnretainedValue() as? NSObject else { return nil }

        filter.setValue(radius, forKey: "inputRadius")
        filter.setValue(maskImage, forKey: "inputMaskImage")
        filter.setValue(true, forKey: "inputNormalizeEdges")
        return filter
    }
}

// MARK: - Animation Engine

/// Generic display link animation engine for blur view properties.
/// Manages multiple concurrent animations with proper lifecycle.
public final class BlurAnimationEngine {

    // MARK: - Animation State

    public struct AnimationState<Value> {
        var startTime: CFTimeInterval = 0
        var duration: CFTimeInterval = 0
        var fromValue: Value
        var toValue: Value
        var isAnimating: Bool = false

        init(initialValue: Value) {
            self.fromValue = initialValue
            self.toValue = initialValue
        }
    }

    public typealias ColorAnimationState = AnimationState<UIColor?>

    // MARK: - Properties

    private var displayLink: CADisplayLink?
    private weak var target: AnyObject?
    private var tickCallback: ((CFTimeInterval) -> Bool)?

    public var blurAnimation = AnimationState<CGFloat>(initialValue: 0)
    public var scaleAnimation = AnimationState<CGFloat>(initialValue: 1)
    public var tintColorAnimation = AnimationState<UIColor?>(initialValue: nil)

    // MARK: - Initialization

    public init() {}

    deinit {
        stop()
    }

    // MARK: - Public API

    /// Start blur radius animation
    public func animateBlur(from: CGFloat, to: CGFloat, duration: CFTimeInterval) {
        guard duration > 0 else { return }
        blurAnimation.fromValue = from
        blurAnimation.toValue = to
        blurAnimation.duration = duration
        blurAnimation.startTime = CACurrentMediaTime()
        blurAnimation.isAnimating = true
        ensureRunning()
    }

    /// Start scale animation
    public func animateScale(from: CGFloat, to: CGFloat, duration: CFTimeInterval) {
        guard duration > 0 else { return }
        scaleAnimation.fromValue = from
        scaleAnimation.toValue = to
        scaleAnimation.duration = duration
        scaleAnimation.startTime = CACurrentMediaTime()
        scaleAnimation.isAnimating = true
        ensureRunning()
    }

    /// Start tint color animation
    public func animateTintColor(from: UIColor?, to: UIColor?, duration: CFTimeInterval) {
        guard duration > 0, from != nil || to != nil else { return }
        tintColorAnimation.fromValue = from
        tintColorAnimation.toValue = to
        tintColorAnimation.duration = duration
        tintColorAnimation.startTime = CACurrentMediaTime()
        tintColorAnimation.isAnimating = true
        ensureRunning()
    }

    /// Set the tick callback (called each frame with current time, return false to stop all animations)
    public func onTick(_ callback: @escaping (CFTimeInterval) -> Bool) {
        self.tickCallback = callback
    }

    /// Check if any animation is running
    public var isAnimating: Bool {
        blurAnimation.isAnimating || scaleAnimation.isAnimating || tintColorAnimation.isAnimating
    }

    /// Stop all animations
    public func stop() {
        displayLink?.invalidate()
        displayLink = nil
        blurAnimation.isAnimating = false
        scaleAnimation.isAnimating = false
        tintColorAnimation.isAnimating = false
    }

    // MARK: - Animation Helpers

    /// Calculate current blur value with easing
    public func currentBlurValue(at time: CFTimeInterval) -> CGFloat? {
        guard blurAnimation.isAnimating else { return nil }
        let progress = calculateProgress(for: &blurAnimation, at: time)
        return blurAnimation.fromValue + (blurAnimation.toValue - blurAnimation.fromValue) * progress
    }

    /// Calculate current scale value with easing
    public func currentScaleValue(at time: CFTimeInterval) -> CGFloat? {
        guard scaleAnimation.isAnimating else { return nil }
        let progress = calculateProgress(for: &scaleAnimation, at: time)
        return scaleAnimation.fromValue + (scaleAnimation.toValue - scaleAnimation.fromValue) * progress
    }

    /// Calculate current tint color with easing
    public func currentTintColor(at time: CFTimeInterval) -> UIColor?? {
        guard tintColorAnimation.isAnimating else { return nil }
        let progress = calculateProgress(for: &tintColorAnimation, at: time)
        return ColorInterpolator.interpolate(
            from: tintColorAnimation.fromValue,
            to: tintColorAnimation.toValue,
            progress: progress
        )
    }

    // MARK: - Private

    private func ensureRunning() {
        guard displayLink == nil else { return }
        displayLink = CADisplayLink(target: self, selector: #selector(tick))
        displayLink?.add(to: .main, forMode: .common)
    }

    private func stopIfNeeded() {
        if !isAnimating {
            displayLink?.invalidate()
            displayLink = nil
        }
    }

    @objc private func tick(_ link: CADisplayLink) {
        let currentTime = CACurrentMediaTime()

        if let callback = tickCallback {
            if !callback(currentTime) {
                stop()
                return
            }
        }

        stopIfNeeded()
    }

    private func calculateProgress<T>(for animation: inout AnimationState<T>, at time: CFTimeInterval) -> CGFloat {
        let elapsed = time - animation.startTime
        var progress = elapsed / animation.duration

        if progress >= 1.0 {
            progress = 1.0
            animation.isAnimating = false
        }

        return easeInOutQuad(progress)
    }

    /// Ease-in-out quadratic timing function
    private func easeInOutQuad(_ t: Double) -> CGFloat {
        if t < 0.5 {
            return CGFloat(2 * t * t)
        } else {
            return CGFloat(-1 + (4 - 2 * t) * t)
        }
    }
}

// MARK: - Color Interpolator

/// Utility for interpolating colors with correct alpha handling.
public enum ColorInterpolator {

    /// Interpolate between two colors, correctly handling nil cases.
    /// - nil → color: RGB adopts target, alpha fades from 0
    /// - color → nil: RGB stays constant, alpha fades to 0
    /// - color → color: Linear RGB and alpha interpolation
    public static func interpolate(from: UIColor?, to: UIColor?, progress: CGFloat) -> UIColor? {
        if from == nil && to == nil { return nil }

        // Fade from clear to color (nil → color)
        if from == nil, let toColor = to {
            var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
            toColor.getRed(&r, green: &g, blue: &b, alpha: &a)
            return UIColor(red: r, green: g, blue: b, alpha: a * progress)
        }

        // Fade from color to clear (color → nil)
        if let fromColor = from, to == nil {
            var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
            fromColor.getRed(&r, green: &g, blue: &b, alpha: &a)
            return UIColor(red: r, green: g, blue: b, alpha: a * (1 - progress))
        }

        // Interpolate between two colors
        guard let fromColor = from, let toColor = to else { return nil }

        var fromR: CGFloat = 0, fromG: CGFloat = 0, fromB: CGFloat = 0, fromA: CGFloat = 0
        var toR: CGFloat = 0, toG: CGFloat = 0, toB: CGFloat = 0, toA: CGFloat = 0

        fromColor.getRed(&fromR, green: &fromG, blue: &fromB, alpha: &fromA)
        toColor.getRed(&toR, green: &toG, blue: &toB, alpha: &toA)

        let r = fromR + (toR - fromR) * progress
        let g = fromG + (toG - fromG) * progress
        let b = fromB + (toB - fromB) * progress
        let a = fromA + (toA - fromA) * progress

        return UIColor(red: r, green: g, blue: b, alpha: a)
    }
}

// MARK: - Tint Layer Controller

/// Manages tint overlay layer for blur views.
public final class TintLayerController {

    public private(set) var tintLayer: CALayer?
    public private(set) var maskLayer: CALayer?

    /// Current blend mode applied to the tint layer
    public private(set) var blendMode: BlurBlendMode = .normal

    /// Set up tint layer as sublayer of the given parent
    public func setup(in parent: CALayer, frame: CGRect) {
        let layer = CALayer()
        layer.frame = frame
        parent.addSublayer(layer)
        tintLayer = layer
    }

    /// Update frame
    public func updateFrame(_ frame: CGRect) {
        tintLayer?.frame = frame
        maskLayer?.frame = frame
    }

    /// Apply blend mode to the tint layer
    public func applyBlendMode(_ mode: BlurBlendMode) {
        guard let tintLayer = tintLayer else { return }

        blendMode = mode

        CATransaction.begin()
        CATransaction.setDisableActions(true)

        if let filterName = mode.filterName {
            tintLayer.compositingFilter = filterName
        } else {
            tintLayer.compositingFilter = nil
        }

        CATransaction.commit()
    }

    /// Apply solid tint color (no mask)
    public func applyColor(_ color: UIColor?) {
        guard let tintLayer = tintLayer else { return }

        CATransaction.begin()
        CATransaction.setDisableActions(true)

        if let color = color {
            tintLayer.backgroundColor = color.cgColor
        } else {
            tintLayer.backgroundColor = nil
        }
        tintLayer.mask = nil

        CATransaction.commit()
    }

    /// Apply tint color with gradient mask
    public func applyColor(_ color: UIColor?, withMask mask: CGImage?) {
        guard let tintLayer = tintLayer else { return }

        CATransaction.begin()
        CATransaction.setDisableActions(true)

        if let color = color, let mask = mask {
            tintLayer.backgroundColor = color.cgColor

            if maskLayer == nil {
                maskLayer = CALayer()
            }

            if let maskLayer = maskLayer {
                maskLayer.frame = tintLayer.bounds
                maskLayer.contents = mask
                maskLayer.contentsGravity = .resizeAspectFill
                tintLayer.mask = maskLayer
            }
        } else if let color = color {
            tintLayer.backgroundColor = color.cgColor
            tintLayer.mask = nil
        } else {
            tintLayer.backgroundColor = nil
            tintLayer.mask = nil
        }

        CATransaction.commit()
    }
}

// MARK: - Pre-Blend Layer Controller

/// Manages an additional backdrop layer for "blend before blur" mode.
/// This layer captures the backdrop with no blur (radius=0) and applies tint+blend,
/// so that the main blur layer can then blur the blended result.
public final class PreBlendLayerController {

    private var backdropLayer: CALayer?
    private var tintLayer: CALayer?
    private var blurFilter: NSObject?

    /// Whether the pre-blend layer is currently active
    public private(set) var isActive: Bool = false

    /// Set up the pre-blend layer structure
    /// - Parameters:
    ///   - parent: The parent layer to add sublayers to
    ///   - frame: The frame for the layers
    ///   - insertAt: Index to insert at (should be below the main blur layer)
    public func setup(in parent: CALayer, frame: CGRect, insertAt index: UInt32) {
        guard backdropLayer == nil else { return }

        // Create backdrop layer (captures content, no blur)
        guard let backdrop = BackdropLayerProvider.createBackdropLayer() else {
            debugLog("[PreBlendLayerController] Failed to create backdrop layer")
            return
        }

        backdrop.frame = frame
        backdrop.setValue(1.0, forKey: "scale")

        // Create zero-radius blur filter (effectively pass-through, but needed for backdrop to work)
        if let filter = BackdropLayerProvider.createGaussianBlurFilter(radius: 0) {
            backdrop.setValue([filter], forKey: "filters")
            blurFilter = filter
        }

        // Create tint layer on top of backdrop
        let tint = CALayer()
        tint.frame = frame
        backdrop.addSublayer(tint)

        // Insert at specified index
        parent.insertSublayer(backdrop, at: index)

        backdropLayer = backdrop
        tintLayer = tint
        isActive = true

        debugLog("[PreBlendLayerController] Setup complete")
    }

    /// Remove and clean up the pre-blend layer
    public func teardown() {
        backdropLayer?.removeFromSuperlayer()
        backdropLayer = nil
        tintLayer = nil
        blurFilter = nil
        isActive = false
    }

    /// Update frame
    public func updateFrame(_ frame: CGRect) {
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        backdropLayer?.frame = frame
        tintLayer?.frame = backdropLayer?.bounds ?? frame
        CATransaction.commit()
    }

    /// Apply tint color and blend mode
    public func applyTint(color: UIColor?, blendMode: BlurBlendMode) {
        guard let tintLayer = tintLayer else { return }

        CATransaction.begin()
        CATransaction.setDisableActions(true)

        // Set tint color
        tintLayer.backgroundColor = color?.cgColor

        // Set blend mode
        if let filterName = blendMode.filterName {
            tintLayer.compositingFilter = filterName
        } else {
            tintLayer.compositingFilter = nil
        }

        CATransaction.commit()
    }

    /// Apply tint with gradient mask (for variable blur)
    public func applyTint(color: UIColor?, blendMode: BlurBlendMode, mask: CGImage?) {
        guard let tintLayer = tintLayer else { return }

        CATransaction.begin()
        CATransaction.setDisableActions(true)

        tintLayer.backgroundColor = color?.cgColor

        if let filterName = blendMode.filterName {
            tintLayer.compositingFilter = filterName
        } else {
            tintLayer.compositingFilter = nil
        }

        if let mask = mask {
            let maskLayer = CALayer()
            maskLayer.frame = tintLayer.bounds
            maskLayer.contents = mask
            maskLayer.contentsGravity = .resizeAspectFill
            tintLayer.mask = maskLayer
        } else {
            tintLayer.mask = nil
        }

        CATransaction.commit()
    }
}

// MARK: - Blur View Protocol

/// Protocol defining common blur view capabilities.
public protocol BlurViewAnimatable: UIView {
    var blurRadius: CGFloat { get set }
    var scale: CGFloat { get set }
    var blurTintColor: UIColor? { get set }

    func animateBlurRadius(from previousValue: CGFloat, duration: CFTimeInterval)
    func animateScale(from previousValue: CGFloat, duration: CFTimeInterval)
    func animateTintColor(from previousValue: UIColor?, duration: CFTimeInterval)
}
