package edu.uwm.cs595.goup11.frontend.core

import android.content.Context
import edu.uwm.cs595.goup11.frontend.core.mesh.DefaultBackendFacade
import edu.uwm.cs595.goup11.frontend.core.mesh.MeshGateway
import edu.uwm.cs595.goup11.frontend.core.mesh.RealMeshGateway

/**
 * AppContainer - Application-Level Dependency Holder
 *
 * PURPOSE
 * -------
 * Holds the SINGLE instance of MeshGateway used by the app.
 *
 * This prevents:
 * - Multiple Client instances being created
 * - Multiple Network instances running simultaneously
 * - Screens instantiating LocalNetwork or ConnectNetwork directly
 * - Inconsistent mesh state across features
 *
 * ARCHITECTURE RULE
 * -----------------
 * UI Layer
 *   -> MeshGateway (interface)
 *      -> RealMeshGateway
 *         -> BackendFacade
 *            -> Client + Network (LocalNetwork / ConnectNetwork)
 *
 *
 * If dependency injection (Hilt/Koin) is added later,
 * this object should be replaced with proper DI,
 * but the "single instance per app" rule must remain.
 *
 * PRIMARY MAINTAINER: Frontend Integration
 */
object AppContainer {
    lateinit var meshGateway: MeshGateway

    fun init(context: Context) {
        // IMPORTANT for Emulator testing:
        // Set useRealNearby to FALSE to use the in-memory network emulator.
        // Set to TRUE only for testing on REAL physical hardware.
        val useRealNearby = false

        val facade = DefaultBackendFacade(
            context = context,
            myId = "android-${(100..999).random()}", // Randomized ID for debugging
            useRealNearby = useRealNearby
        )
        meshGateway = RealMeshGateway(facade)
    }
}
