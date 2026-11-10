package com.vk.autotube.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import com.vk.autotube.R

class AutoTubeCarService : CarAppService() {
    // Sideloaded personal app: accept any Android Auto host.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = AutoTubeSession()
}

class AutoTubeSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        val renderer = CarRenderer(carContext)
        lifecycle.addObserver(renderer)
        return MainScreen(carContext, renderer)
    }
}

/**
 * The navigation template only gives us the raw surface plus a few Android Auto
 * buttons in the corner. The buttons change with what's on screen.
 */
class MainScreen(ctx: CarContext, private val renderer: CarRenderer) : Screen(ctx) {

    init {
        renderer.onModeChanged = { invalidate() }
    }

    override fun onGetTemplate(): Template {
        val mode = renderer.mode
        val strip = ActionStrip.Builder()
        when (mode) {
            CarUi.Mode.HOME -> strip.addAction(iconAction(R.drawable.ic_search) { renderer.browse() })
            CarUi.Mode.WEB -> {
                strip.addAction(iconAction(R.drawable.ic_back) { renderer.back() })
                strip.addAction(iconAction(R.drawable.ic_home) { renderer.home() })
            }
            CarUi.Mode.WATCH -> strip.addAction(iconAction(R.drawable.ic_home) { renderer.home() })
        }

        val builder = NavigationTemplate.Builder().setActionStrip(strip.build())
        if (mode != CarUi.Mode.WATCH) {
            val mapStrip = ActionStrip.Builder()
                .addAction(iconAction(R.drawable.ic_up) { renderer.page(-1) })
                .addAction(iconAction(R.drawable.ic_down) { renderer.page(1) })
                .addAction(Action.PAN)
                .build()
            builder.setMapActionStrip(mapStrip)
            builder.setPanModeListener { }
        }
        return builder.build()
    }

    private fun iconAction(res: Int, onClick: () -> Unit): Action {
        val icon = CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()
        return Action.Builder().setIcon(icon).setOnClickListener { onClick() }.build()
    }
}
