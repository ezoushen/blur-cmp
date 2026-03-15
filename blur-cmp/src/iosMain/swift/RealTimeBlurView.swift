//
//  RealTimeBlurView.swift
//  BlurView
//
//  Created by EZOU on 2025/12/5.
//
//  Implementation based on Flutter's iOS PlatformView BackdropFilter approach:
//  https://files.flutter-io.cn/flutter-design-docs/Flutter_iOS_PlatformView_BackdropFilter.pdf
//  https://api.flutter.dev/ios-embedder/_flutter_platform_views_8mm_source.html
//
//  This approach extracts blur capabilities from UIVisualEffectView internals
//  rather than directly referencing private API class names.

import UIKit
import QuartzCore

/// A blur view that extracts backdrop capabilities from UIVisualEffectView.
/// Uses Flutter's proven approach for safer App Store compatibility.
public final class RealTimeBlurView: UIView, BlurViewAnimatable {

    // MARK: - Instance Properties

    private var backdropLayer: CALayer?
    private var animationEngine = BlurAnimationEngine()
    private var tintController = TintLayerController()
    private var preBlendController = PreBlendLayerController()
    private var fallbackBlurView: UIVisualEffectView?

    /// The blur radius (sigma). Default: 10.
    public var blurRadius: CGFloat = 10.0 {
        didSet {
            if blurRadius != oldValue {
                applyBlur(from: oldValue)
            }
        }
    }

    /// Scale factor for blur resolution. Default: 0.25
    /// Lower values improve performance, higher values improve quality.
    public var scale: CGFloat = 0.25 {
        didSet {
            if scale != oldValue {
                applyScale(from: oldValue)
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

        if BackdropLayerProvider.isValidForUniformBlur,
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

            if let scaleValue = self.animationEngine.currentScaleValue(at: currentTime) {
                self.applyScaleValue(scaleValue)
            }

            if let tintColor = self.animationEngine.currentTintColor(at: currentTime) {
                self.tintController.applyColor(tintColor)
            }

            return self.animationEngine.isAnimating
        }
    }

    private func setupFallback() {
        let blurEffect = UIBlurEffect(style: .light)
        let effectView = UIVisualEffectView(effect: blurEffect)
        effectView.frame = bounds
        effectView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        addSubview(effectView)
        fallbackBlurView = effectView

        debugLog("RealTimeBlurView: Using UIVisualEffectView fallback (extraction failed)")
    }

    // MARK: - Layout

    public override func layoutSubviews() {
        super.layoutSubviews()
        backdropLayer?.frame = bounds
        tintController.updateFrame(bounds)
        preBlendController.updateFrame(bounds)
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
        guard backdropLayer != nil, duration > 0 else {
            applyScaleValue(scale)
            return
        }
        animationEngine.animateScale(from: previousValue, to: scale, duration: duration)
    }

    public func animateTintColor(from previousValue: UIColor?, duration: CFTimeInterval = 0.3) {
        guard tintController.tintLayer != nil, duration > 0,
              previousValue != nil || blurTintColor != nil else {
            tintController.applyColor(blurTintColor)
            return
        }
        animationEngine.animateTintColor(from: previousValue, to: blurTintColor, duration: duration)
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

    private func applyBlurRadius(_ radius: CGFloat) {
        guard let backdropLayer = backdropLayer,
              let blurFilter = BackdropLayerProvider.createGaussianBlurFilter(radius: radius) else {
            return
        }

        CATransaction.begin()
        CATransaction.setDisableActions(true)
        backdropLayer.setValue([blurFilter], forKey: "filters")
        CATransaction.commit()
    }

    // MARK: - Scale Application

    private func applyScale(from previousScale: CGFloat) {
        guard backdropLayer != nil else { return }

        let duration = UIView.inheritedAnimationDuration
        if duration > 0 {
            animationEngine.animateScale(from: previousScale, to: scale, duration: duration)
        } else {
            applyScaleValue(scale)
        }
    }

    private func applyScaleValue(_ value: CGFloat) {
        guard let backdropLayer = backdropLayer else { return }
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        backdropLayer.setValue(value, forKey: "scale")
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

    // MARK: - Initial Setup

    private func applyInitialBlur() {
        applyBlurRadius(blurRadius)
        applyScaleValue(scale)
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
            // Standard mode: tint+blend on top of blur
            tintController.applyColor(blurTintColor)
            tintController.applyBlendMode(tintBlendMode)
            // Clear pre-blend layer if active
            if preBlendController.isActive {
                preBlendController.applyTint(color: nil, blendMode: .normal)
            }

        case .beforeBlur:
            // Blend-first mode: tint+blend in pre-blend layer, main tint layer is clear
            preBlendController.applyTint(color: blurTintColor, blendMode: tintBlendMode)
            // Clear the main tint layer (blur will apply on top of pre-blended content)
            tintController.applyColor(nil)
            tintController.applyBlendMode(.normal)

        case .auto:
            // Should never reach here as effectiveBlendOrder resolves .auto
            break
        }
    }
}
