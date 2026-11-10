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
        val renderer = SplitRenderer(carContext)
        lifecycle.addObserver(renderer)
        return MainScreen(carContext, renderer)
    }
}

/**
 * The navigation template gives us a raw drawing surface. Buttons:
 * top strip = Back / Home for the video side, map strip = page up / down / pan.
 */
class MainScreen(ctx: CarContext, private val renderer: SplitRenderer) : Screen(ctx) {

    override fun onGetTemplate(): Template {
        val strip = ActionStrip.Builder()
            .addAction(
                Action.Builder().setTitle("Back").setOnClickListener { renderer.goBack() }.build()
            )
            .addAction(
                Action.Builder().setTitle("Home").setOnClickListener { renderer.goHome() }.build()
            )
            .build()

        val mapStrip = ActionStrip.Builder()
            .addAction(iconAction(R.drawable.ic_up) { renderer.scrollPage(-1) })
            .addAction(iconAction(R.drawable.ic_down) { renderer.scrollPage(1) })
            .addAction(Action.PAN)
            .build()

        return NavigationTemplate.Builder()
            .setActionStrip(strip)
            .setMapActionStrip(mapStrip)
            .setPanModeListener { }
            .build()
    }

    private fun iconAction(res: Int, onClick: () -> Unit): Action {
        val icon = CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()
        return Action.Builder().setIcon(icon).setOnClickListener { onClick() }.build()
    }
}
