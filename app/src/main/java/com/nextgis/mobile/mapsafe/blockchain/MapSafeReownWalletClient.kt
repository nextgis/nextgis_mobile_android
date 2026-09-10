package com.nextgis.mobile.mapsafe.blockchain

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.reown.android.Core
import com.reown.android.CoreClient
import com.reown.android.relay.ConnectionType
import com.reown.appkit.client.AppKit
import com.reown.appkit.client.Modal
import com.reown.appkit.client.models.request.Request
import com.reown.appkit.client.models.request.SentRequestResult
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArraySet

sealed interface MapSafeWalletEvent {
    data class Connected(val address: String, val chainId: Long) : MapSafeWalletEvent
    data object Disconnected : MapSafeWalletEvent
    data class TransactionSubmitted(val transactionHash: String) : MapSafeWalletEvent
    data class Error(val message: String) : MapSafeWalletEvent
}

/**
 * Reown/WalletConnect bridge for MapSafe notarisation.
 *
 * It never accepts, reads, or stores a wallet private key. The wallet separately
 * approves and signs the transaction requested through `eth_sendTransaction`.
 */
object MapSafeReownWalletClient : AppKit.ModalDelegate {
    fun interface Listener {
        fun onWalletEvent(event: MapSafeWalletEvent)
    }

    private data class WalletApp(
        val name: String,
        val packageName: String,
        val pairingLink: String
    )

    private val supportedWallets = listOf(
        WalletApp("Trust Wallet", "com.wallet.crypto.trustapp", "trust://wc?uri="),
        WalletApp("MetaMask", "io.metamask", "metamask://wc?uri=")
    )
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val transactionHashPattern = Regex("^0x[0-9a-fA-F]{64}$")

    @Volatile private var configured = false
    @Volatile private var ready = false
    @Volatile private var initializationMessage = "External wallet support is initializing."
    @Volatile private var pendingRequestId: Long? = null
    @Volatile private var lastEvent: MapSafeWalletEvent? = null

    @JvmStatic
    fun initialize(application: Application, projectId: String) {
        if (configured) return
        configured = true
        if (!Regex("^[0-9a-fA-F]{32}$").matches(projectId)) {
            initializationMessage = "Reown Project ID is not configured for this build."
            return
        }
        val metadata = Core.Model.AppMetaData(
            name = "MapSafe Mobile",
            description = "Field geospatial privacy and integrity workflows",
            url = "https://github.com/sharmapn/nextgis_mobile_mapsafe_geoprivacy",
            icons = emptyList(),
            redirect = "mapsafe-wc://request",
            linkMode = false,
            appLink = ""
        )
        CoreClient.initialize(
            projectId = projectId,
            connectionType = ConnectionType.AUTOMATIC,
            application = application,
            metaData = metadata,
            onError = { error ->
                ready = false
                initializationMessage = publicError(error.throwable)
                emit(MapSafeWalletEvent.Error(initializationMessage))
            }
        )
        AppKit.initialize(
            init = Modal.Params.Init(core = CoreClient, coinbaseEnabled = false),
            onSuccess = {
                AppKit.setDelegate(this)
                ready = true
                initializationMessage = "External wallet support is ready."
                val account = runCatching { AppKit.getAccount() }.getOrNull()
                if (account == null) {
                    emit(MapSafeWalletEvent.Disconnected)
                } else {
                    emit(
                        MapSafeWalletEvent.Connected(
                            account.address,
                            account.chain.chainReference.toLongOrNull() ?: 0L
                        )
                    )
                }
            },
            onError = { error ->
                ready = false
                initializationMessage = publicError(error.throwable)
                emit(MapSafeWalletEvent.Error(initializationMessage))
            }
        )
    }

    fun addListener(listener: Listener) {
        listeners += listener
        (lastEvent as? MapSafeWalletEvent.TransactionSubmitted)?.let { event ->
            mainHandler.post { listener.onWalletEvent(event) }
        }
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    fun status(profile: BlockchainNetworkProfile): MapSafeWalletEvent? {
        if (!ready) return MapSafeWalletEvent.Error(initializationMessage)
        val account = runCatching { AppKit.getAccount() }.getOrNull() ?: return null
        return if (account.chain.chainReference == profile.chainId.toString()) {
            MapSafeWalletEvent.Connected(account.address, profile.chainId)
        } else {
            MapSafeWalletEvent.Error(
                "The wallet session is connected to ${account.chain.chainName}. Reconnect it to ${profile.displayName}."
            )
        }
    }

    fun installedWalletName(context: Context): String? = installedWallet(context)?.name

    fun isExternalWalletInstalled(context: Context): Boolean = installedWallet(context) != null

    fun connect(context: Context, profile: BlockchainNetworkProfile) {
        if (!ready) {
            emit(MapSafeWalletEvent.Error(initializationMessage))
            return
        }
        status(profile)?.let { state ->
            if (state is MapSafeWalletEvent.Connected) {
                emit(state)
                return
            }
        }
        val wallet = installedWallet(context)
        if (wallet == null) {
            emit(MapSafeWalletEvent.Error("Install Trust Wallet or MetaMask before connecting."))
            return
        }
        val chain = chain(profile)
        AppKit.setChains(listOf(chain))
        val pairing = CoreClient.Pairing.create { error ->
            emit(MapSafeWalletEvent.Error(publicError(error.throwable)))
        } ?: return
        val namespace = Modal.Model.Namespace.Proposal(
            chains = listOf(chain.id),
            methods = listOf("eth_sendTransaction"),
            events = listOf("accountsChanged", "chainChanged")
        )
        AppKit.connect(
            connectParams = Modal.Params.ConnectParams(
                sessionNamespaces = mapOf("eip155" to namespace),
                pairing = pairing
            ),
            onSuccess = { pairingUri -> openWallet(context, wallet, pairingUri) },
            onError = { error -> emit(MapSafeWalletEvent.Error(publicError(error.throwable))) }
        )
    }

    fun requestMint(
        context: Context,
        profile: BlockchainNetworkProfile,
        fileName: String,
        sha256: String
    ) {
        if (!ready) {
            emit(MapSafeWalletEvent.Error(initializationMessage))
            return
        }
        val account = runCatching { AppKit.getAccount() }.getOrNull()
        if (account == null || account.chain.chainReference != profile.chainId.toString()) {
            emit(MapSafeWalletEvent.Error("Connect a wallet to ${profile.displayName} before notarising."))
            return
        }
        val transaction = runCatching {
            MapSafeWalletTransactionCodec.create(profile, account.address, fileName, sha256)
        }.getOrElse {
            emit(MapSafeWalletEvent.Error(it.message ?: "The notarisation transaction is invalid."))
            return
        }
        pendingRequestId = null
        AppKit.request(
            request = Request(
                method = "eth_sendTransaction",
                params = transaction.jsonRpcParams,
                chainId = transaction.chainId
            ),
            onSuccess = { sent ->
                pendingRequestId = (sent as? SentRequestResult.WalletConnect)?.requestId
                val wallet = installedWallet(context)
                if (wallet == null) {
                    emit(MapSafeWalletEvent.Error("The connected external wallet is no longer installed."))
                } else {
                    openWallet(context, wallet, null)
                }
            },
            onError = { error -> emit(MapSafeWalletEvent.Error(publicError(error))) }
        )
    }

    private fun chain(profile: BlockchainNetworkProfile) = Modal.Model.Chain(
        chainName = profile.displayName,
        chainNamespace = "eip155",
        chainReference = profile.chainId.toString(),
        requiredMethods = listOf("eth_sendTransaction"),
        optionalMethods = emptyList(),
        events = listOf("accountsChanged", "chainChanged"),
        token = Modal.Model.Token("Ether", "ETH", 18),
        rpcUrl = profile.rpcUrl,
        blockExplorerUrl = profile.explorerBaseUrl
    )

    private fun installedWallet(context: Context): WalletApp? = supportedWallets.firstOrNull { wallet ->
        context.packageManager.getLaunchIntentForPackage(wallet.packageName) != null
    }

    private fun openWallet(context: Context, wallet: WalletApp, pairingUri: String?) {
        val intent = if (pairingUri == null) {
            context.packageManager.getLaunchIntentForPackage(wallet.packageName)
        } else {
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse(wallet.pairingLink + URLEncoder.encode(pairingUri, Charsets.UTF_8.name()))
            ).apply { `package` = wallet.packageName }
        }
        if (intent == null) {
            emit(MapSafeWalletEvent.Error("${wallet.name} could not be opened."))
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.applicationContext.startActivity(intent) }
            .onFailure { emit(MapSafeWalletEvent.Error("${wallet.name} could not be opened.")) }
    }

    override fun onSessionApproved(approvedSession: Modal.Model.ApprovedSession) {
        val account = runCatching { AppKit.getAccount() }.getOrNull()
        if (account != null) {
            emit(
                MapSafeWalletEvent.Connected(
                    account.address,
                    account.chain.chainReference.toLongOrNull() ?: 0L
                )
            )
        }
    }

    override fun onSessionRejected(rejectedSession: Modal.Model.RejectedSession) =
        emit(MapSafeWalletEvent.Error("The wallet connection was declined."))

    override fun onSessionUpdate(updatedSession: Modal.Model.UpdatedSession) {
        val account = runCatching { AppKit.getAccount() }.getOrNull() ?: return
        emit(
            MapSafeWalletEvent.Connected(
                account.address,
                account.chain.chainReference.toLongOrNull() ?: 0L
            )
        )
    }

    @Suppress("DEPRECATION")
    override fun onSessionEvent(sessionEvent: Modal.Model.SessionEvent) = Unit

    override fun onSessionEvent(sessionEvent: Modal.Model.Event) = Unit
    override fun onSessionExtend(session: Modal.Model.Session) = Unit

    override fun onSessionDelete(deletedSession: Modal.Model.DeletedSession) {
        pendingRequestId = null
        emit(MapSafeWalletEvent.Disconnected)
    }

    override fun onSessionRequestResponse(response: Modal.Model.SessionRequestResponse) {
        if (response.method != "eth_sendTransaction") return
        val expectedId = pendingRequestId
        if (expectedId != null && response.result.id != expectedId) return
        pendingRequestId = null
        when (val result = response.result) {
            is Modal.Model.JsonRpcResponse.JsonRpcResult -> {
                val hash = result.result as? String
                if (hash != null && transactionHashPattern.matches(hash)) {
                    emit(MapSafeWalletEvent.TransactionSubmitted(hash))
                } else {
                    emit(MapSafeWalletEvent.Error("The wallet returned an invalid transaction hash."))
                }
            }
            is Modal.Model.JsonRpcResponse.JsonRpcError ->
                emit(MapSafeWalletEvent.Error(result.message.ifBlank { "The wallet declined the transaction." }))
        }
    }

    override fun onProposalExpired(proposal: Modal.Model.ExpiredProposal) =
        emit(MapSafeWalletEvent.Error("The wallet connection request expired."))

    override fun onRequestExpired(request: Modal.Model.ExpiredRequest) {
        pendingRequestId = null
        emit(MapSafeWalletEvent.Error("The wallet transaction request expired."))
    }

    override fun onConnectionStateChange(state: Modal.Model.ConnectionState) = Unit
    override fun onSessionAuthenticateResponse(sessionAuthenticateResponse: Modal.Model.SessionAuthenticateResponse) = Unit
    override fun onSIWEAuthenticationResponse(response: Modal.Model.SIWEAuthenticateResponse) = Unit
    override fun onError(error: Modal.Model.Error) =
        emit(MapSafeWalletEvent.Error(publicError(error.throwable)))

    private fun emit(event: MapSafeWalletEvent) {
        lastEvent = event
        mainHandler.post { listeners.forEach { listener -> listener.onWalletEvent(event) } }
    }

    private fun publicError(error: Throwable): String {
        val message = error.localizedMessage
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(160)
        return message?.takeIf(String::isNotBlank) ?: "The external wallet operation failed."
    }
}
