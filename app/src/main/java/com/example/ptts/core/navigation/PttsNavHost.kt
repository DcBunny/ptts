package com.example.ptts.core.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.ptts.features.jump_session.presentation.JumpSessionDefaults
import com.example.ptts.features.jump_session.presentation.JumpSessionViewModel
import com.example.ptts.features.jump_session.ui.JumpSessionScreen
import com.example.ptts.features.parent_camera.ui.ParentCameraScreen

object PttsRoute {
    const val Session = "session"
    const val ParentCamera = "parent_camera"
    const val DurationSecondsArg = "durationSeconds"
    const val ParentCameraPattern = "$ParentCamera/{$DurationSecondsArg}"

    fun parentCamera(durationSeconds: Int): String = "$ParentCamera/$durationSeconds"
}

@Composable
fun PttsNavHost(modifier: Modifier = Modifier) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = PttsRoute.Session,
        modifier = modifier,
    ) {
        composable(PttsRoute.Session) {
            val context = LocalContext.current
            val viewModel: JumpSessionViewModel = viewModel(
                factory = JumpSessionViewModel.Factory(context.applicationContext as android.app.Application),
            )
            val bestRecord by viewModel.bestRecord.collectAsStateWithLifecycle()
            JumpSessionScreen(
                onOpenParentCamera = { durationSeconds ->
                    navController.navigate(PttsRoute.parentCamera(durationSeconds))
                },
                bestRecord = bestRecord,
            )
        }
        composable(
            route = PttsRoute.ParentCameraPattern,
            arguments = listOf(
                navArgument(PttsRoute.DurationSecondsArg) {
                    type = NavType.IntType
                },
            ),
        ) { backStackEntry ->
            val durationSeconds = backStackEntry.arguments
                ?.getInt(PttsRoute.DurationSecondsArg)
                ?: JumpSessionDefaults.InitialDurationSeconds
            ParentCameraScreen(
                durationSeconds = durationSeconds,
                onExit = {
                    navController.popBackStack()
                },
            )
        }
    }
}
