package com.vk.autotube.car

import android.app.Presentation
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.vk.autotube.Bridge
import com.vk.autotube.Library

/**
 * Takes the drawing surface Android Auto gives navigation apps, puts a private
 * virtual display on it, and shows a normal Android view tree there.
 */
class CarRenderer(private val carContext: CarContext) : DefaultLifecycleObserver, SurfaceCallback {

    var onModeChanged: (() -> Unit)? = null

    val mode: CarUi.Mode
        get() = ui?.mode ?: CarUi.Mode.HOME

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var ui: CarUi? = null
    private var stableArea: Rect? = null

    private val motion = MotionGate(carContext) { moving -> ui?.setMoving(moving) }

    override fun onCreate(owner: LifecycleOwner) {
        Library.init(carContext)
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        release()
        Bridge.listener = null
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        release()
        val surface = surfaceContainer.surface ?: return
        val w = surfaceContainer.width
        val h = surfaceContainer.height
        if (w <= 0 || h <= 0) return

        val dm = carContext.getSystemService(DisplayManager::class.java) ?: return
        val vd = dm.createVirtualDisplay(
            "AutoTube",
            w,
            h,
            surfaceContainer.dpi,
            surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        ) ?: return
        virtualDisplay = vd

        val p = Presentation(carContext, vd.display)
        p.window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        val u = CarUi(p.context, w, h) { onModeChanged?.invoke() }
        p.setContentView(u.root)
        p.show()
        presentation = p
        ui = u

        stableArea?.let { u.applyInsets(it) }
        Bridge.listener = { cmd -> ui?.handle(cmd) }
        u.handle(Bridge.last)

        motion.start()
        u.setMoving(motion.moving)
        onModeChanged?.invoke()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        release()
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        this.stableArea = Rect(stableArea)
        ui?.applyInsets(stableArea)
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {}

    override fun onClick(x: Float, y: Float) {
        ui?.tap(x, y)
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        ui?.scroll(distanceX, distanceY)
    }

    fun back() {
        ui?.back()
    }

    fun home() {
        ui?.goHome()
    }

    fun browse() {
        ui?.browse(Bridge.lastBrowse ?: Bridge.YT_HOME)
    }

    fun page(direction: Int) {
        ui?.page(direction)
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
