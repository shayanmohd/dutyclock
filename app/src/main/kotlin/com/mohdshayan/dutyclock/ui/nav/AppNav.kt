package com.mohdshayan.dutyclock.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.CalendarViewWeek
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mohdshayan.dutyclock.data.prefs.Settings
import com.mohdshayan.dutyclock.ui.catchup.CatchUpScreen
import com.mohdshayan.dutyclock.ui.firstrun.FirstRunScreen
import com.mohdshayan.dutyclock.ui.log.LogScreen
import com.mohdshayan.dutyclock.ui.plan.PlanScreen
import com.mohdshayan.dutyclock.ui.records.RecordsScreen
import com.mohdshayan.dutyclock.ui.settings.SettingsScreen
import com.mohdshayan.dutyclock.ui.today.RuleSetTitle
import com.mohdshayan.dutyclock.ui.today.TodayScreen
import com.mohdshayan.dutyclock.di.ServiceLocator
import kotlinx.coroutines.launch
import com.mohdshayan.dutyclock.ui.week.WeekScreen
import kotlin.reflect.KClass
import kotlinx.serialization.Serializable

@Serializable object TodayRoute
@Serializable object WeekRoute
@Serializable object LogRoute
@Serializable object PlanRoute
@Serializable object RecordsRoute
@Serializable object SettingsRoute
@Serializable object CatchUpRoute

val LocalSnackbar = staticCompositionLocalOf<SnackbarHostState> { error("No snackbar host") }

/** A scope that outlives any one screen, for a message shown after navigating away. */
val LocalShellScope = staticCompositionLocalOf<kotlinx.coroutines.CoroutineScope> { error("No shell scope") }

private data class Tab(val route: Any, val cls: KClass<*>, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(TodayRoute, TodayRoute::class, "Today", Icons.Outlined.Timer),
    Tab(WeekRoute, WeekRoute::class, "Week", Icons.Outlined.CalendarViewWeek),
    Tab(LogRoute, LogRoute::class, "Log", Icons.AutoMirrored.Outlined.ListAlt),
    Tab(PlanRoute, PlanRoute::class, "Plan", Icons.Outlined.Route),
)

@Composable
fun AppNav(settings: Settings?) {
    when {
        settings == null -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        !settings.onboardingDone -> FirstRunScreen()
        else -> MainShell(settings)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainShell(settings: Settings) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val entry by nav.currentBackStackEntryAsState()
    val dest = entry?.destination
    val title = when {
        dest?.hasRoute(WeekRoute::class) == true -> "Week"
        dest?.hasRoute(LogRoute::class) == true -> "Log"
        dest?.hasRoute(PlanRoute::class) == true -> "Can I make it home"
        dest?.hasRoute(RecordsRoute::class) == true -> "Records"
        dest?.hasRoute(SettingsRoute::class) == true -> "Settings"
        dest?.hasRoute(CatchUpRoute::class) == true -> "Bring your week in"
        else -> "Today"
    }
    val secondary = dest?.let { d -> tabs.none { d.hasRoute(it.cls) } } == true

    fun go(route: Any) {
        // From Records, Settings or the catch-up sheet, drop back to the tab stack first so the
        // saved state of the start tab does not bring the secondary screen straight back.
        if (secondary) nav.popBackStack(nav.graph.findStartDestination().id, inclusive = false)
        nav.navigate(route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    CompositionLocalProvider(LocalSnackbar provides snackbar, LocalShellScope provides scope) {
        Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (wide) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.background,
                    modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
                ) {
                    for (t in tabs) {
                        NavigationRailItem(
                            selected = dest?.hasRoute(t.cls) == true,
                            onClick = { go(t.route) },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label) },
                            colors = NavigationRailItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primary,
                                selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                selectedTextColor = MaterialTheme.colorScheme.onBackground,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
            Scaffold(
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.background,
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = {
                    TopAppBar(
                        title = {
                            if (dest?.hasRoute(TodayRoute::class) == true) {
                                RuleSetTitle(settings.ruleSet) { rs -> scope.launch { ServiceLocator.logRepository.setRuleSet(rs) } }
                            } else {
                                Text(title, style = MaterialTheme.typography.headlineMedium)
                            }
                        },
                        navigationIcon = {
                            if (secondary) {
                                IconButton(onClick = { nav.popBackStack() }) {
                                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                                }
                            }
                        },
                        actions = {
                            IconButton(onClick = { nav.navigate(RecordsRoute) { launchSingleTop = true } }) {
                                Icon(Icons.Outlined.Description, contentDescription = "Records")
                            }
                            IconButton(onClick = { nav.navigate(SettingsRoute) { launchSingleTop = true } }) {
                                Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                            scrolledContainerColor = MaterialTheme.colorScheme.background,
                            titleContentColor = MaterialTheme.colorScheme.onBackground,
                            actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                            navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                        ),
                    )
                },
                bottomBar = {
                    if (!wide) {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                            for (t in tabs) {
                                NavigationBarItem(
                                    selected = dest?.hasRoute(t.cls) == true,
                                    onClick = { go(t.route) },
                                    icon = { Icon(t.icon, contentDescription = null) },
                                    label = { Text(t.label) },
                                    colors = NavigationBarItemDefaults.colors(
                                        indicatorColor = MaterialTheme.colorScheme.primary,
                                        selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                        selectedTextColor = MaterialTheme.colorScheme.onBackground,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                Graph(nav, Modifier.padding(padding))
            }
        }
    }
}

@Composable
private fun Graph(nav: NavHostController, modifier: Modifier) {
    NavHost(navController = nav, startDestination = TodayRoute, modifier = modifier) {
        composable<TodayRoute> {
            TodayScreen(onCatchUp = { nav.navigate(CatchUpRoute) }, onRecords = { nav.navigate(RecordsRoute) })
        }
        composable<WeekRoute> { WeekScreen(onRecords = { nav.navigate(RecordsRoute) }, onToday = { nav.navigate(TodayRoute) }) }
        composable<LogRoute> { LogScreen(onRecords = { nav.navigate(RecordsRoute) }) }
        composable<PlanRoute> { PlanScreen(onLog = { nav.navigate(LogRoute) }, onRecords = { nav.navigate(RecordsRoute) }) }
        composable<RecordsRoute> { RecordsScreen(onLog = { nav.navigate(LogRoute) }) }
        composable<SettingsRoute> { SettingsScreen(onCatchUp = { nav.navigate(CatchUpRoute) }) }
        composable<CatchUpRoute> { CatchUpScreen(onDone = { nav.popBackStack() }) }
    }
}
