package rat.poison.game.hooks

import com.sun.jna.platform.win32.WinNT
import rat.poison.game.*
import rat.poison.game.CSGO.GLOW_OBJECT_SIZE
import rat.poison.game.CSGO.clientDLL
import rat.poison.game.CSGO.csgoEXE
import rat.poison.game.CSGO.engineDLL
import rat.poison.game.entity.EntityType
import rat.poison.game.entity.absPosition
import rat.poison.game.offsets.ClientOffsets
import rat.poison.game.offsets.ClientOffsets.dwEntityList
import rat.poison.game.offsets.ClientOffsets.dwGlowObject
import rat.poison.game.offsets.ClientOffsets.dwLocalPlayer
import rat.poison.game.offsets.EngineOffsets
import rat.poison.game.offsets.EngineOffsets.dwClientState
import rat.poison.scripts.entsToTrack
import rat.poison.settings.*
import rat.poison.utils.every
import rat.poison.utils.extensions.uint
import rat.poison.utils.notInGame
import java.util.concurrent.atomic.AtomicLong
import kotlin.properties.Delegates

private val lastCleanup = AtomicLong(0L)

private val contexts = Array(MAX_ENTITIES) { EntityContext() }

private fun shouldReset() = System.currentTimeMillis() - lastCleanup.get() >= CLEANUP_TIME

private fun reset() {
    for (cacheableList in entitiesValues)
        cacheableList?.clear()
    lastCleanup.set(System.currentTimeMillis())
}

private var state by Delegates.observable(SignOnState.MAIN_MENU) { _, old, new ->
    if (old != new) {
        notInGame = if (new == SignOnState.IN_GAME) {
            if (PROCESS_ACCESS_FLAGS and WinNT.PROCESS_VM_OPERATION > 0) {
                val write = 0x74.toByte()
                try {
                    clientDLL[ClientOffsets.dwGlowUpdate] = write
                } catch (e: Exception) { e.printStackTrace() }

                try {
                    clientDLL[ClientOffsets.dwGlowUpdate2] = write
                } catch (e: Exception) { e.printStackTrace() }
            }

            if (GARBAGE_COLLECT_ON_MAP_START) {
                System.gc()
            }
            false
        } else {
            true
        }
    }
}
var cursorEnable = false
private val cursorEnableAddress by lazy(LazyThreadSafetyMode.NONE) { clientDLL.address + ClientOffsets.dwMouseEnable }
private val cursorEnablePtr by lazy(LazyThreadSafetyMode.NONE) { clientDLL.address + ClientOffsets.dwMouseEnablePtr }

fun updateCursorEnable() { //Call when needed
    cursorEnable = csgoEXE.int(cursorEnableAddress) xor cursorEnablePtr.toInt() != 1
}

var defuseKitEntities = mutableListOf<Long>()
var toneMapController = 0L

fun constructEntities() = every(500) {
    updateCursorEnable()
    state = SignOnState[csgoEXE.int(clientState + EngineOffsets.dwSignOnState)]

    me = clientDLL.uint(dwLocalPlayer)
    if (me <= 0) return@every

    clientState = engineDLL.uint(dwClientState)

    var dzMode = false

    val glowObject = clientDLL.uint(dwGlowObject)
    val glowObjectCount = clientDLL.int(dwGlowObject + 4)

    if (shouldReset()) reset()

    var tmpEntsToAdd = mutableListOf<Long>()

    for (glowIndex in 0..glowObjectCount) {
        val glowAddress = glowObject + (glowIndex * GLOW_OBJECT_SIZE)
        val entity = csgoEXE.uint(glowAddress)

        if (entity != 0L) {
            val type = EntityType.byEntityAddress(entity)

            if (type == EntityType.CFists) {
                //sometimes it takes a while for game to initialize gameRulesProxy
                //so our dz mode detection wasn't working perfectly.
                dzMode = true
            }

            //Fuck this?
            if (type.grenadeProjectile) {
                val tmpPos = entity.absPosition()
                val check = (tmpPos.x in -2.0..2.0 && tmpPos.y in -2.0..2.0 && tmpPos.z in -2.0..2.0)
                if (!check) {
                    tmpEntsToAdd.add(entity)
                }
            }

            val context = contexts[glowIndex].set(entity, glowAddress, glowIndex, type)

            with(entities[type]!!) {
                if (!contains(context)) add(context)
            }
        }
    }

    entsToTrack = tmpEntsToAdd

    val maxIndex = clientDLL.int(dwEntityList + 0x24 - 0x10) //Not right?
    tmpEntsToAdd = mutableListOf()

    for (i in 64..maxIndex) {
        val entity = clientDLL.uint(dwEntityList + (i * 0x10) - 0x10)
        if (entity != 0L) {
            val type = EntityType.byEntityAddress(entity)

            if (type == EntityType.CEconEntity) {
                tmpEntsToAdd.add(entity)
            }

            if (type == EntityType.CEnvTonemapController) {
                toneMapController = entity
            }
        }
    }
    defuseKitEntities = tmpEntsToAdd

    DANGER_ZONE = dzMode
}