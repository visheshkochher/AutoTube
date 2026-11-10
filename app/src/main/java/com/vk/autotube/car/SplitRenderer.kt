package com.vk.autotube.car

import android.app.Presentation
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.vk.autotube.Bridge

/**
 * Takes the surface Android Auto hands to navigation apps, puts a private
 * virtual display on it, and shows a normal Android view tree there
 * (YouTube on the left, map with Google Maps turns on the right).
 */
class SplitRenderer(private val carContext: CarContext) : DefaultLifecycleObserver, SurfaceCallback {

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var ui: CarUi? = null
    private var stableArea: Rect? = null
    private var width = 0
    private var height = 0

    private val motion = MotionGate(carContext) { moving -> ui?.setMoving(moving) }

    override fun onCreate(owner: LifecycleOwner) {
        Bridge.init(carContext)
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        release()
        Bridge.onUrl = null
        Bridge.onNav = null
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        release()
        val surface = surfaceContainer.surface ?: return
        width = surfaceContainer.width
        height = surfaceContainer.height
        if (width <= 0 || height <= 0) return

        val dm = carContext.getSystemService(DisplayManager::class.java) ?: return
        val vd = dm.createVirtualDisplay(
            "AutoTube",
            width,
            height,
            surfaceContainer.dpi,
            surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        ) ?: return
        virtualDisplay = vd

        val p = Presentation(carContext, vd.display)
        val u = CarUi(p.context)
        p.setContentView(u.root)
        p.show()
        presentation = p
        ui = u

        u.start(Bridge.url)
        stableArea?.let { u.applyInsets(it, width, height) }
        u.setNav(Bridge.nav)

        Bridge.onUrl = { url -> ui?.load(url) }
        Bridge.onNav = { info -> ui?.setNav(info) }

        motion.start()
        u.setMoving(motion.moving)
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        release()
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        this.stableArea = Rect(stableArea)
        ui?.applyInsets(stableArea, width, height)
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {}

    override fun onClick(x: Float, y: Float) {
        ui?.tap(x, y)
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        ui?.scrollWeb(distanceX, distanceY)
    }

    fun goBack() {
        ui?.back()
    }

    fun goHome() {
        Bridge.open(carContext, Bridge.HOME)
    }

    fun scrollPage(direction: Int) {
        ui?.scrollPage(direction)
    }

    private fun release() {
        motion.stop()
        ui?.destroy()
        ui = null
        try {
            presentation?.dismiss()
        } catch (_: Exception) {
        }
        presentation = null
        virtualDisplay?.release()
        virtualDisplay = null
    }
}
