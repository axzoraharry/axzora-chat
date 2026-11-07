package app.axzora.chat

import android.app.Application
import app.axzora.chat.iconswitch.IconSwitchTaskDescription
import app.axzora.chat.nostr.RelayDirectory
import app.axzora.chat.ui.theme.ThemePreferenceManager
import app.axzora.chat.net.ArtiTorManager

/**
 * Main application class for zemzeme Android
 */
class ZemzemeApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize Tor first so any early network goes over Tor
        try {
            val torProvider = ArtiTorManager.getInstance()
            torProvider.init(this)
        } catch (_: Exception){}

        // Initialize relay directory (loads assets/nostr_relays.csv)
        RelayDirectory.initialize(this)

        // Initialize LocationNotesManager dependencies early so sheet subscriptions can start immediately
        try { app.axzora.chat.nostr.LocationNotesInitializer.initialize(this) } catch (_: Exception) { }

        // Initialize favorites persistence early so MessageRouter/NostrTransport can use it on startup
        try {
            app.axzora.chat.favorites.FavoritesPersistenceService.initialize(this)
        } catch (_: Exception) { }

        // Warm up Nostr identity to ensure npub is available for favorite notifications
        try {
            app.axzora.chat.nostr.NostrIdentityBridge.getCurrentNostrIdentity(this)
        } catch (_: Exception) { }

        // Initialize theme preference
        ThemePreferenceManager.init(this)

        // Initialize debug preference manager (persists debug toggles)
        try { app.axzora.chat.ui.debug.DebugPreferenceManager.init(this) } catch (_: Exception) { }

        // Initialize Geohash Registries for persistence
        try {
            app.axzora.chat.nostr.GeohashAliasRegistry.initialize(this)
            app.axzora.chat.nostr.GeohashConversationRegistry.initialize(this)
        } catch (_: Exception) { }

        // Initialize P2P Alias Registry for P2P peer display name persistence
        try {
            app.axzora.chat.p2p.P2PAliasRegistry.initialize(this)
            app.axzora.chat.p2p.P2PFavoritesRegistry.initialize(this)
        } catch (_: Exception) { }

        // Initialize mesh service preferences
        try { app.axzora.chat.service.MeshServicePreferences.init(this) } catch (_: Exception) { }

        // Proactively start the foreground service to keep mesh alive
        try { app.axzora.chat.service.MeshForegroundService.start(this) } catch (_: Exception) { }

        // Update recent-apps card with current dynamic icon/name
        registerActivityLifecycleCallbacks(IconSwitchTaskDescription())

        // TorManager already initialized above
    }
}
