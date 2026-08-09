package be.valuya.breadbin.emu

/**
 * Keeps the running machine alive across navigation.
 *
 * A composable's `remember` dies when its destination leaves the back stack, which for an emulator
 * means that opening the settings to turn the sound down would throw away the game. The session
 * therefore lives here, above the navigation graph, and is only replaced when something the machine
 * is actually built from changes.
 */
class SessionHolder {

    private var key: String? = null

    var session: EmulatorSession? = null
        private set

    /**
     * The session for [key], building and starting a new one if what is being asked for is not what
     * is already running.
     */
    fun obtain(key: String, build: () -> EmulatorSession): EmulatorSession {
        val current = session
        if (current != null && this.key == key) return current
        current?.stop()
        val fresh = build()
        this.key = key
        session = fresh
        fresh.start()
        return fresh
    }

    /** Shuts the machine down. Leaving the emulator for the library is the only thing that does. */
    fun stop() {
        session?.stop()
        session = null
        key = null
    }
}
