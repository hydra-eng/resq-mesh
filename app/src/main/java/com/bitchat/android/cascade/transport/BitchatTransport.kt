package com.bitchat.android.cascade.transport

import android.content.Context
import com.bitchat.android.mesh.BluetoothMeshDelegate
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.model.BitchatMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import org.cascade.crypto.*
import com.bitchat.android.protocol.MessageType
import org.cascade.transport.*
import javax.inject.Inject
import javax.inject.Singleton

/** Adapter to the upstream BLE/Noise/gossip/dedup stack; does not implement a second radio protocol. */
@Singleton
class BitchatTransport @Inject constructor(@ApplicationContext private val context: Context) : MeshTransport, BluetoothMeshDelegate {
    private var service: BluetoothMeshService? = null
    @Volatile private var starting=false
    var hopLimit: Int=7
    private val ingress=IngressBudget()
    private val relaySecurity=SecurityLayer(maxPerMinute=120)
    private val frames=MutableSharedFlow<MeshFrame>(extraBufferCapacity=64,onBufferOverflow=BufferOverflow.DROP_OLDEST)
    private val mesh=MutableStateFlow(MeshStatus())
    override val incoming=frames.asSharedFlow()
    override val status=mesh.asStateFlow()
    data class ChatFrame(val id: String,val peer: String,val text: String)
    private val chats=MutableSharedFlow<ChatFrame>(extraBufferCapacity=64,onBufferOverflow=BufferOverflow.DROP_OLDEST)
    val chatIncoming=chats.asSharedFlow()
    private val chatAcks=MutableSharedFlow<Pair<String,String>>(extraBufferCapacity=64,onBufferOverflow=BufferOverflow.DROP_OLDEST)
    val chatDelivered=chatAcks.asSharedFlow()
    fun sendChat(peer: String,text: String,id: String): Boolean {
        val radio=service ?: return false
        if(peer !in mesh.value.peers || !mesh.value.running) return false
        if(!radio.hasEstablishedSession(peer)) { radio.initiateNoiseHandshake(peer); return false }
        radio.sendPrivateMessage("RQCHAT1:"+text.take(1000),peer,"resQ mesh",id)
        return true
    }
    override fun start() {
        runCatching {
            val issue=RadioReadiness.problem(context)
            if(issue!=null) { stop(); mesh.value=MeshStatus(problem=issue); return }
            if(mesh.value.running || starting) return
            starting=true
            runCatching { service?.stopServices() }; service=null
            mesh.value=MeshStatus(problem="Starting Bluetooth discovery…")
            service=BluetoothMeshService(context).also { radio ->
                radio.delegate=this
                radio.setPrivateApplicationDelivery { message ->
                    if(message.content.startsWith(Wire.PREFIX) || message.content.startsWith("RQCHAT1:")) { didReceiveMessage(message);true } else false
                }
                radio.observeRadioStartup { started ->
                    starting=false
                    mesh.value=mesh.value.copy(running=started,problem=if(started) null else "Android could not start Bluetooth advertising or scanning. Toggle Bluetooth, then tap Connect.")
                }
                radio.setApplicationAdmission(
                    ingress={ routed -> ingress.allow(routed.ingressLinkID ?: routed.relayAddress ?: "unidentified-link",routed.packet.payload.size,android.os.SystemClock.elapsedRealtime()) },
                    application={ routed ->
                        when(MessageType.fromValue(routed.packet.type)) {
                            MessageType.MESSAGE -> {
                                val content=String(routed.packet.payload,Charsets.UTF_8)
                                content.startsWith(Wire.PREFIX) && relaySecurity.admit(content,routed.ingressLinkID ?: routed.relayAddress ?: "unknown",System.currentTimeMillis()) is Admission.Accepted
                            }
                            MessageType.FILE_TRANSFER, MessageType.VOICE_FRAME -> false
                            else -> true
                        }
                    }
                )
                radio.startServices()
            }
            if(service?.isRadioStarted()!=true) { starting=false;mesh.value=MeshStatus(problem="Bluetooth discovery did not start. Tap Connect to retry.") }
        }.onFailure {
            starting=false
            // Never include packet data, peer identifiers, or exception messages in UI.
            android.util.Log.e("ResQRadio","Radio startup failed: ${it.javaClass.simpleName}")
            it.stackTrace.take(5).forEach { frame -> android.util.Log.e("ResQRadio","${frame.className}.${frame.methodName}:${frame.lineNumber}") }
            runCatching { service?.stopServices() }; service=null
            mesh.value=MeshStatus(problem=if(it is SecurityException) "Android blocked Bluetooth access. Review Nearby devices and precise location, then tap Connect." else "Bluetooth startup failed (${it.javaClass.simpleName}). Tap Connect to retry.")
        }
    }
    override fun stop() { starting=false;service?.stopServices(); service=null; mesh.value=MeshStatus() }
    override fun broadcast(payload: String) { require(payload.length<=Wire.MAX_FRAME); service?.sendMessage(payload,hopLimit=hopLimit.coerceIn(1,7).toUByte()) }
    override fun sendPrivate(peer: String,payload: String) { require(payload.length<=Wire.MAX_FRAME); service?.sendPrivateMessage(payload,peer,"CASCADE") }
    fun sendSealed(recipientIdentity: String,payload: String) {
        val radio=service ?: return
        val peer=mesh.value.peers.firstOrNull { id -> radio.getPeerInfo(id)?.signingPublicKey?.let(PacketCrypto::identity)==recipientIdentity }
        if(peer!=null && radio.hasEstablishedSession(peer)) radio.sendPrivateMessage(payload,peer,"CASCADE")
        else { if(peer!=null) radio.initiateNoiseHandshake(peer); broadcast(payload) }
    }
    override fun didReceiveMessage(message: BitchatMessage) {
        if(message.isPrivate && message.content.startsWith("RQCHAT1:") && message.content.length<=1008) {
            val peer=message.senderPeerID ?: return
            if(service?.hasEstablishedSession(peer)==true) chats.tryEmit(ChatFrame(message.id,peer,message.content.removePrefix("RQCHAT1:")))
            return
        }
        if(message.content.startsWith(Wire.PREFIX) && message.content.length<=Wire.MAX_FRAME)
            frames.tryEmit(MeshFrame(message.content,message.senderPeerID ?: "unknown",message.isPrivate))
    }
    override fun didUpdatePeerList(peers: List<String>) {
        val radio=service
        val observations=peers.mapNotNull { id ->
            val info=radio?.getPeerInfo(id) ?: return@mapNotNull null
            val key=info.signingPublicKey ?: return@mapNotNull null
            if(!info.hasVerifiedAnnouncement) return@mapNotNull null
            PeerObservation(id,PacketCrypto.identity(key),radio.getPeerRSSI()[id],info.isDirectConnection,info.lastSeen)
        }
        mesh.value=mesh.value.copy(peers=peers.distinct().sorted(),observations=observations)
    }
    fun proximity(identity: String): PeerObservation? {
        didUpdatePeerList(mesh.value.peers)
        return mesh.value.observations.firstOrNull { it.signingId==identity && it.direct && it.rssi!=null && it.rssi in -90..-15 && System.currentTimeMillis()-it.observedAt in 0..120000 }
    }
    override fun didReceiveChannelLeave(channel: String,fromPeer: String) = Unit
    override fun didReceiveDeliveryAck(messageID: String,recipientPeerID: String) { chatAcks.tryEmit(messageID to recipientPeerID) }
    override fun didReceiveReadReceipt(messageID: String,recipientPeerID: String) = Unit
    override fun didReceiveVerifyChallenge(peerID: String,payload: ByteArray,timestampMs: Long) = Unit
    override fun didReceiveVerifyResponse(peerID: String,payload: ByteArray,timestampMs: Long) = Unit
    override fun decryptChannelMessage(encryptedContent: ByteArray,channel: String): String? = null
    override fun getNickname()="CASCADE"
    override fun isFavorite(peerID: String)=false
}
