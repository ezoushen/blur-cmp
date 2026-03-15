//
//  RealTimeVariableBlurView.swift
//  BlurView
//
//  Created by EZOU on 2025/12/5.
//
//  Variable blur implementation using CABackdropLayer with gradient mask.
//  Based on Flutter's iOS PlatformView approach and community implementations:
//  - https://github.com/aheze/VariableBlurView
//  - https://github.com/nikstar/VariableBlur
//

import UIKit
import QuartzCore

// MARK: - Gradient Types

/// Gradient stop describing intensity at a normalized location (0...1)
public struct BlurGradientStop: Equatable {
    public var location: CGFloat
    public var intensity: CGFloat

    public init(location: CGFloat, intensity: CGFloat) {
        self.location = location
        self.intensity = intensity
    }

    internal var clamped: BlurGradientStop {
        BlurGradientStop(
            location: max(0, min(1, location)),
            intensity: max(0, min(1, intensity))
        )
    }
}

/// Type of gradient for variable blur with multi-stop support
public enum BlurGradientType: Equatable {
    /// Linear gradient with normalized start/end points and stops
    case linear(startPoint: CGPoint, endPoint: CGPoint, stops: [BlurGradientStop])

    /// Radial gradient with normalized center, radii, and stops
    case radial(center: CGPoint, startRadius: CGFloat, endRadius: CGFloat, stops: [BlurGradientStop])

    /// Custom mask image (should have alpha channel where alpha=1 means max blur)
    case custom(image: CGImage)

    public static let defaultStops: [BlurGradientStop] = [
        BlurGradientStop(location: 0, intensity: 1),
        BlurGradientStop(location: 1, intensity: 0)
    ]

    public static func == (lhs: BlurGradientType, rhs: BlurGradientType) -> Bool {
        switch (lhs, rhs) {
        case (.linear(let s1, let e1, let stops1), .linear(let s2, let e2, let stops2)):
            return s1 == s2 && e1 == e2 && stops1 == stops2
        case (.radial(let c1, let sr1, let er1, let stops1),
              .radial(let c2, let sr2, let er2, let stops2)):
            return c1 == c2 && sr1 == sr2 && er1 == er2 && stops1 == stops2
        case (.custom(let img1), .custom(let img2)):
            return img1 === img2
        default:
            return false
        }
    }
}

// MARK: - RealTimeVariableBlurView

/// A blur view with variable intensity controlled by a gradient mask.
/// Blur intensity at each pixel = blurRadius * mask alpha value.
public final class RealTimeVariableBlurView: UIView, BlurViewAnimatable {

    // MARK: - Instance Properties

    private var backdropLayer: CALayer?
    private var animationEngine = BlurAnimationEngine()
    private var tintController = TintLayerController()
    private var preBlendController = PreBlendLayerController()
    private var fallbackBlurView: UIVisualEffectView?

    // Cached gradient mask
    private var cachedMask: CGImage?
    private var cachedMaskSize: CGSize = .zero
    private var cachedGradientType: BlurGradientType?

    /// Mask resolution (lower = better performance)
    private static let maskResolution: CGFloat = 256

    /// The maximum blur radius. Default: 20.
    public var blurRadius: CGFloat = 20.0 {
        didSet {
            if blurRadius != oldValue {
                applyBlur(from: oldValue)
            }
        }
    }

    /// The gradient type controlling blur distribution. Default: linear top-to-bottom with 2 stops.
    public var gradientType: BlurGradientType = .linear(
        startPoint: CGPoint(x: 0.5, y: 0),
        endPoint: CGPoint(x: 0.5, y: 1),
        stops: BlurGradientType.defaultStops
    ) {
        didSet {
            if gradientType != oldValue {
                invalidateMaskCache()
                applyCurrentBlur()
                if blurTintColor != nil {
                    applyTint()
                }
            }
        }
    }

    /// Scale factor for blur resolution. Default: 1.0 (full resolution).
    public var scale: CGFloat = 1.0 {
        didSet {
            if scale != oldValue {
                applyScale()
            }
        }
    }

    /// Tint color to blend with the blurred background. Default: nil (no tint).
    /// The alpha component of the color controls the tint intensity.
    public var blurTintColor: UIColor? {
        didSet {
            if blurTintColor != oldValue {
                reconfigureBlendOrderIfNeeded()
                applyTintColor(from: oldValue)
            }
        }
    }

    /// Blend mode for the tint layer. Default: .normal (standard alpha compositing).
    /// Use .colorDodge with a light tint color to create brightening effects.
    public var tintBlendMode: BlurBlendMode = .normal {
        didSet {
            if tintBlendMode != oldValue {
                reconfigureBlendOrderIfNeeded()
                applyBlendConfiguration()
            }
        }
    }

    /// Order of blend mode application relative to blur. Default: .auto.
    /// - `.auto`: Automatically uses `.beforeBlur` when tintColor and non-normal blendMode are set
    /// - `.afterBlur`: Blur first, then blend tint on top (frosted glass effect)
    /// - `.beforeBlur`: Blend first, then blur (bloom/glow effect)
    public var blendOrder: TintBlendOrder = .auto {
        didSet {
            if blendOrder != oldValue {
                configureBlendOrder()
            }
        }
    }

    /// Cached effective blend order to detect changes
    private var lastEffectiveBlendOrder: TintBlendOrder = .afterBlur

    /// Resolves `.auto` to the actual blend order based on current tint/blend configuration
    private var effectiveBlendOrder: TintBlendOrder {
        switch blendOrder {
        case .auto:
            if blurTintColor != nil && tintBlendMode != .normal {
                return .beforeBlur
            }
            return .afterBlur
        case .afterBlur, .beforeBlur:
            return blendOrder
        }
    }

    // MARK: - Initialization

    public override init(frame: CGRect) {
        super.init(frame: frame)
        setup()
    }

    public required init?(coder: NSCoder) {
        super.init(coder: coder)
        setup()
    }

    private func setup() {
        backgroundColor = .clear

        if BackdropLayerProvider.isValidForVariableBlur,
           let backdrop = BackdropLayerProvider.createBackdropLayer() {
            backdrop.frame = bounds
            backdrop.setValue(UUID().uuidString, forKey: "groupName")
            backdrop.setValue(true, forKey: "allowsInPlaceFiltering")

            layer.addSublayer(backdrop)
            backdropLayer = backdrop

            tintController.setup(in: layer, frame: bounds)

            setupAnimationEngine()
            applyInitialBlur()
        } else {
            setupFallback()
        }
    }

    private func setupAnimationEngine() {
        animationEngine.onTick { [weak self] currentTime in
            guard let self = self else { return false }

            if let blurValue = self.animationEngine.currentBlurValue(at: currentTime) {
                self.applyBlurRadius(blurValue)
            }

            if let tintColor = self.animationEngine.currentTintColor(at: currentTime) {
                self.applyTintWithMask(tintColor)
            }

            return self.animationEngine.isAnimating
        }
    }

    private func setupFallback() {
        let effectView = UIVisualEffectView(effect: UIBlurEffect(style: .light))
        effectView.frame = bounds
        effectView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        addSubview(effectView)
        fallbackBlurView = effectView

        debugLog("RealTimeVariableBlurView: Using UIVisualEffectView fallback")
    }

    // MARK: - Layout

    public override func layoutSubviews() {
        super.layoutSubviews()
        backdropLayer?.frame = bounds
        tintController.updateFrame(bounds)
        preBlendController.updateFrame(bounds)

        let sizeChanged = abs(cachedMaskSize.width - bounds.size.width) > 1 ||
                          abs(cachedMaskSize.height - bounds.size.height) > 1
        if sizeChanged && bounds.size.width > 0 && bounds.size.height > 0 {
            invalidateMaskCache()
            applyCurrentBlur()
            if blurTintColor != nil {
                applyTint()
            }
        }
    }

    // MARK: - Public Animation Methods

    public func animateBlurRadius(from previousValue: CGFloat, duration: CFTimeInterval = 0.3) {
        guard backdropLayer != nil, duration > 0 else {
            applyBlurRadius(blurRadius)
            return
        }
        animationEngine.animateBlur(from: previousValue, to: blurRadius, duration: duration)
    }

    public func animateScale(from previousValue: CGFloat, duration: CFTimeInterval = 0.3) {
        // Variable blur doesn't animate scale, applies directly
        applyScale()
    }

    public func animateTintColor(from previousValue: UIColor?, duration: CFTimeInterval = 0.3) {
        guard tintController.tintLayer != nil, duration > 0,
              previousValue != nil || blurTintColor != nil else {
            applyTint()
            return
        }
        animationEngine.animateTintColor(from: previousValue, to: blurTintColor, duration: duration)
    }

    // MARK: - Gradient Mask Generation

    private func invalidateMaskCache() {
        cachedMask = nil
        cachedMaskSize = .zero
        cachedGradientType = nil
    }

    private func getOrCreateMask() -> CGImage? {
        if let cached = cachedMask, cachedGradientType == gradientType {
            return cached
        }

        let mask: CGImage?
        switch gradientType {
        case .linear(let startPoint, let endPoint, let stops):
            mask = createLinearGradientMask(
                startPoint: startPoint,
                endPoint: endPoint,
                stops: normalizeStops(stops)
            )
        case .radial(let center, let startRadius, let endRadius, let stops):
            mask = createRadialGradientMask(
                center: center,
                startRadius: startRadius,
                endRadius: endRadius,
                stops: normalizeStops(stops)
            )
        case .custom(let image):
            mask = image
        }

        cachedMask = mask
        cachedMaskSize = bounds.size
        cachedGradientType = gradientType

        return mask
    }

    private func createLinearGradientMask(
        startPoint: CGPoint,
        endPoint: CGPoint,
        stops: [BlurGradientStop]
    ) -> CGImage? {
        let width = Int(Self.maskResolution)
        let height = Int(Self.maskResolution)

        let colorSpace = CGColorSpaceCreateDeviceRGB()
        guard let context = CGContext(
            data: nil,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: width * 4,
            space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return nil }

        context.clear(CGRect(x: 0, y: 0, width: width, height: height))

        guard let gradient = createGradient(colorSpace: colorSpace, stops: stops) else { return nil }

        let start = CGPoint(
            x: startPoint.x * CGFloat(width),
            y: (1 - startPoint.y) * CGFloat(height)
        )
        let end = CGPoint(
            x: endPoint.x * CGFloat(width),
            y: (1 - endPoint.y) * CGFloat(height)
        )

        context.drawLinearGradient(
            gradient,
            start: start,
            end: end,
            options: [.drawsBeforeStartLocation, .drawsAfterEndLocation]
        )

        return context.makeImage()
    }

    private func createRadialGradientMask(
        center: CGPoint,
        startRadius: CGFloat,
        endRadius: CGFloat,
        stops: [BlurGradientStop]
    ) -> CGImage? {
        let width = Int(Self.maskResolution)
        let height = Int(Self.maskResolution)

        let colorSpace = CGColorSpaceCreateDeviceRGB()
        guard let context = CGContext(
            data: nil,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: width * 4,
            space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return nil }

        context.clear(CGRect(x: 0, y: 0, width: width, height: height))

        guard let gradient = createGradient(colorSpace: colorSpace, stops: stops) else { return nil }

        let centerPoint = CGPoint(
            x: center.x * CGFloat(width),
            y: (1 - center.y) * CGFloat(height)
        )

        let maxDimension = CGFloat(max(width, height))
        let startRadiusScaled = max(0, startRadius * maxDimension)
        let endRadiusScaled = max(startRadiusScaled + .ulpOfOne, endRadius * maxDimension)

        context.drawRadialGradient(
            gradient,
            startCenter: centerPoint,
            startRadius: startRadiusScaled,
            endCenter: centerPoint,
            endRadius: endRadiusScaled,
            options: [.drawsBeforeStartLocation, .drawsAfterEndLocation]
        )

        return context.makeImage()
    }

    private func normalizeStops(_ stops: [BlurGradientStop]) -> [BlurGradientStop] {
        let clamped = stops.map { $0.clamped }
        let finalStops = clamped.count >= 2 ? clamped : BlurGradientType.defaultStops
        return finalStops.sorted { $0.location < $1.location }
    }

    private func createGradient(colorSpace: CGColorSpace, stops: [BlurGradientStop]) -> CGGradient? {
        let locations = stops.map { $0.location }
        var components: [CGFloat] = []
        for stop in stops {
            // Use black with varying alpha; alpha controls blur intensity.
            components.append(contentsOf: [0, 0, 0, stop.intensity])
        }

        return CGGradient(
            colorSpace: colorSpace,
            colorComponents: components,
            locations: locations,
            count: stops.count
        )
    }

    // MARK: - Blur Application

    private func applyBlur(from previousRadius: CGFloat) {
        guard backdropLayer != nil else { return }

        let duration = UIView.inheritedAnimationDuration
        if duration > 0 {
            animationEngine.animateBlur(from: previousRadius, to: blurRadius, duration: duration)
        } else {
            applyBlurRadius(blurRadius)
        }
    }

    private func applyCurrentBlur() {
        applyBlurRadius(blurRadius)
    }

    private func applyBlurRadius(_ radius: CGFloat) {
        guard let backdropLayer = backdropLayer,
              let mask = getOrCreateMask(),
              let blurFilter = BackdropLayerProvider.createVariableBlurFilter(radius: radius, maskImage: mask) else {
            return
        }

        CATransaction.begin()
        CATransaction.setDisableActions(true)
        backdropLayer.setValue([blurFilter], forKey: "filters")
        CATransaction.commit()
    }

    // MARK: - Scale Application

    private func applyScale() {
        guard let backdropLayer = backdropLayer else { return }
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        backdropLayer.setValue(scale, forKey: "scale")
        CATransaction.commit()
    }

    // MARK: - Tint Application

    private func applyTintColor(from previousColor: UIColor?) {
        // For beforeBlur mode or when animation is not needed, apply directly
        if effectiveBlendOrder == .beforeBlur {
            applyBlendConfiguration()
            return
        }

        guard tintController.tintLayer != nil else {
            applyBlendConfiguration()
            return
        }

        let duration = UIView.inheritedAnimationDuration
        if duration > 0, previousColor != nil || blurTintColor != nil {
            animationEngine.animateTintColor(from: previousColor, to: blurTintColor, duration: duration)
        } else {
            applyBlendConfiguration()
        }
    }

    private func applyTint() {
        applyTintWithMask(blurTintColor)
    }

    private func applyTintWithMask(_ color: UIColor?) {
        tintController.applyColor(color, withMask: getOrCreateMask())
    }

    // MARK: - Blend Order Configuration

    /// Reconfigure blend order if the effective value changed (for .auto mode)
    private func reconfigureBlendOrderIfNeeded() {
        let newEffective = effectiveBlendOrder
        if newEffective != lastEffectiveBlendOrder {
            lastEffectiveBlendOrder = newEffective
            configureBlendOrder()
        }
    }

    /// Configure layer hierarchy based on blend order
    private func configureBlendOrder() {
        guard backdropLayer != nil else { return }

        let effective = effectiveBlendOrder
        lastEffectiveBlendOrder = effective

        switch effective {
        case .afterBlur:
            // Tear down pre-blend layer, use tint controller on top of blur
            preBlendController.teardown()
            applyBlendConfiguration()

        case .beforeBlur:
            // Set up pre-blend layer below the main blur backdrop
            // The pre-blend layer captures backdrop, applies tint+blend (no blur)
            // The main blur layer then blurs the combined result
            if !preBlendController.isActive {
                preBlendController.setup(in: layer, frame: bounds, insertAt: 0)
            }
            applyBlendConfiguration()

        case .auto:
            // Should never reach here as effectiveBlendOrder resolves .auto
            break
        }
    }

    /// Apply tint color and blend mode to the appropriate layer based on blend order
    private func applyBlendConfiguration() {
        switch effectiveBlendOrder {
        case .afterBlur:
            // Standard mode: tint+blend on top of blur (with mask for variable blur)
            applyTintWithMask(blurTintColor)
            tintController.applyBlendMode(tintBlendMode)
            // Clear pre-blend layer if active
            if preBlendController.isActive {
                preBlendController.applyTint(color: nil, blendMode: .normal)
            }

        case .beforeBlur:
            // Blend-first mode: tint+blend in pre-blend layer with gradient mask, main tint layer is clear
            preBlendController.applyTint(color: blurTintColor, blendMode: tintBlendMode, mask: getOrCreateMask())
            // Clear the main tint layer (blur will apply on top of pre-blended content)
            tintController.applyColor(nil)
            tintController.applyBlendMode(.normal)

        case .auto:
            // Should never reach here as effectiveBlendOrder resolves .auto
            break
        }
    }

    // MARK: - Initial Setup

    private func applyInitialBlur() {
        applyBlurRadius(blurRadius)
        applyScale()
    }
}
