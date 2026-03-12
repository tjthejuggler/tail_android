package com.example.tail

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.tail.data.HabitsRepository
import com.example.tail.data.SettingsRepository
import com.example.tail.data.TextInputRepository
import com.example.tail.ui.HabitGridScreen
import com.example.tail.ui.HabitViewModel
import com.example.tail.ui.HabitViewModelFactory
import com.example.tail.ui.SettingsScreen
import com.example.tail.ui.theme.TailTheme

private const val ROUTE_GRID = "grid"
private const val ROUTE_SETTINGS = "settings"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TailTheme(darkTheme = true) {
                TailApp(
                    habitsRepo = HabitsRepository(),
                    settingsRepo = SettingsRepository(applicationContext),
                    textInputRepo = TextInputRepository()
                )
            }
        }
    }
}

@Composable
private fun TailApp(
    habitsRepo: HabitsRepository,
    settingsRepo: SettingsRepository,
    textInputRepo: TextInputRepository
) {
    val navController = rememberNavController()
    val viewModel: HabitViewModel = viewModel(
        factory = HabitViewModelFactory(
            habitsRepo = habitsRepo,
            settingsRepo = settingsRepo,
            textInputRepo = textInputRepo,
            context = androidx.compose.ui.platform.LocalContext.current
        )
    )

    NavHost(navController = navController, startDestination = ROUTE_GRID) {
        composable(ROUTE_GRID) {
            HabitGridScreen(
                viewModel = viewModel,
                onNavigateToSettings = { navController.navigate(ROUTE_SETTINGS) }
            )
        }
        composable(ROUTE_SETTINGS) {
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
