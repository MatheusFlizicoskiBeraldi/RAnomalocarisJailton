package com.ifpr.androidapptemplatear.ui

import androidx.fragment.app.Fragment
import com.ifpr.androidapptemplatear.R
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.ar.core.Config
import com.ifpr.androidapptemplatear.databinding.FragmentCameraBinding
import io.github.sceneview.ar.ArSceneView
import io.github.sceneview.ar.node.ArModelNode
import io.github.sceneview.ar.node.PlacementMode
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import okio.IOException
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class CameraFragment : Fragment() {

    private lateinit var sceneView: ArSceneView
    private lateinit var modelNode: ArModelNode
    private lateinit var placeButton: ExtendedFloatingActionButton
    private lateinit var rotateButton: ExtendedFloatingActionButton
    private lateinit var fireButton: ExtendedFloatingActionButton
    private lateinit var summonCounter: TextView
    private lateinit var ammoCounter: TextView
    private lateinit var targetInfo: TextView
    private lateinit var crosshair: ImageView

    // Som tocado quando um animal é invocado (botão "Place") e som dos disparos
    private var soundPool: SoundPool? = null
    private var summonSoundId: Int = 0
    private var summonSoundLoaded = false
    private var shotSoundId: Int = 0
    private var shotSoundLoaded = false

    // Alarmes de explosão (nuke-alarm) em reprodução (MediaPlayer porque o áudio tem ~15 s)
    private val explosionPlayers = mutableListOf<MediaPlayer>()

    // Quantidade de animais invocados e ângulo atual do modelo (eixo Y)
    private var summonCount = 0
    private var modelAngleY = 0f

    // Modelos que são animais (tocam o som ao serem invocados e entram no combate)
    private val animalModels = setOf(
        "anomalocaris.glb",
        "baat outta hell.glb",
        "Tyrannosaurus Rex.glb"
    )

    // ---------------------------------------------------------------------------------------
    // COMBATE
    // ---------------------------------------------------------------------------------------

    companion object {
        private const val MAX_AMMO = 30                  // munições no pente
        private const val RELOAD_COOLDOWN_MS = 1500L     // recarga automática (1,5 s)
        private const val ANIMAL_MAX_HEALTH = 30f        // vida de cada animal invocado
        private const val BULLET_DAMAGE = 12.5f          // dano de cada projétil
        private const val FIRE_INTERVAL_MS = 120L        // cadência de tiro (segurando o botão)
        private const val BULLET_SPEED = 8f              // metros por segundo
        private const val BULLET_LIFETIME = 1.2f         // segundos até o projétil sumir
        private const val BULLET_POOL_SIZE = 16          // projéteis pré-carregados
        private const val DEFAULT_HIT_RADIUS = 0.5f      // raio de acerto (metros) de cada animal
        private const val EXPLOSION_DURATION = 0.8f      // segundos
        private const val EXPLOSION_MAX_SCALE = 2.5f

        private const val BULLET_MODEL = "combat/bullet.glb"
        private const val EXPLOSION_MODEL = "combat/explosion.glb"
        private const val RIFLE_MODEL = "combat/rifle_a1_fps.glb"

        // Posição do rifle em primeira pessoa, no espaço da câmera (metros / graus).
        // A câmera olha para -Z, +X é a direita e +Y é para cima.
        private const val RIFLE_X = 0.10f
        private const val RIFLE_Y = -0.12f
        private const val RIFLE_Z = -0.20f
        private const val RIFLE_SCALE = 0.6f
        private const val RIFLE_YAW = 5f       // inclina o cano levemente para o centro da mira
        private const val RIFLE_PITCH = 4f

        // Recuo ao atirar
        private const val RECOIL_BACK = 0.03f  // metros para trás
        private const val RECOIL_PITCH = 3f    // graus para cima
        private const val RECOIL_RECOVERY = 9f // velocidade de retorno (1/s)

        // Ponta do cano no espaço da câmera (de onde saem os projéteis)
        private const val MUZZLE_RIGHT = 0.063f
        private const val MUZZLE_DOWN = 0.091f
        private const val MUZZLE_FORWARD = 0.62f
    }

    /** Raio de acerto específico por modelo (ajuste conforme o tamanho de cada modelo). */
    private val hitRadiusByModel = mapOf(
        "anomalocaris.glb" to 0.5f,
        "baat outta hell.glb" to 0.5f,
        "Tyrannosaurus Rex.glb" to 0.8f
    )

    private class Animal(val node: ArModelNode, val hitRadius: Float) {
        var health = ANIMAL_MAX_HEALTH
        var alive = true
    }

    private class Bullet(val node: ArModelNode) {
        var active = false
        var x = 0f; var y = 0f; var z = 0f
        var vx = 0f; var vy = 0f; var vz = 0f
        var life = 0f
    }

    private class Explosion(val node: ArModelNode) {
        var age = 0f
    }

    private var rifleNode: ArModelNode? = null
    private var recoil = 0f

    private val animals = mutableListOf<Animal>()
    private val bullets = mutableListOf<Bullet>()
    private val explosions = mutableListOf<Explosion>()

    private var ammo = MAX_AMMO
    private var reloading = false
    private var firing = false

    private val handler = Handler(Looper.getMainLooper())
    private var lastFrameNanos = 0L
    private var gameLoopRunning = false

    private val fireRunnable = object : Runnable {
        override fun run() {
            if (!firing) return
            tryFire()
            handler.postDelayed(this, FIRE_INTERVAL_MS)
        }
    }

    private val reloadRunnable = Runnable {
        ammo = MAX_AMMO
        reloading = false
        updateAmmoCounter()
    }

    private val clearTargetInfoRunnable = Runnable { targetInfo.visibility = View.INVISIBLE }
    private val resetCrosshairRunnable = Runnable { crosshair.setColorFilter(Color.WHITE) }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!gameLoopRunning) return
            val dt = if (lastFrameNanos == 0L) 0f
            else min((frameTimeNanos - lastFrameNanos) / 1_000_000_000f, 0.05f)
            lastFrameNanos = frameTimeNanos

            updateBullets(dt)
            updateExplosions(dt)
            updateRifle(dt)

            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private lateinit var sharedViewModel: SharedModelViewModel

    private val CAMERA_PERMISSION_CODE = 1001

    private var currentModelFile: String = "neutrofilo.glb"

    private var _binding: FragmentCameraBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        super.onCreate(savedInstanceState)
        sharedViewModel = ViewModelProvider(requireActivity())[SharedModelViewModel::class.java]
        sharedViewModel.selectedModel.observe(viewLifecycleOwner) { path ->
            loadModel(path)
        }

        val view = inflater.inflate(R.layout.fragment_camera, container, false)

        sceneView = view.findViewById<ArSceneView?>(R.id.sceneView).apply {
            lightEstimationMode = Config.LightEstimationMode.DISABLED
        }

        placeButton = view.findViewById(R.id.place)

        placeButton.setOnClickListener {
            placeModel()
        }

        summonCounter = view.findViewById(R.id.summonCounter)
        updateSummonCounter()

        rotateButton = view.findViewById(R.id.rotate)
        rotateButton.setOnClickListener {
            rotateModel()
        }

        // HUD de combate
        ammoCounter = view.findViewById(R.id.ammoCounter)
        targetInfo = view.findViewById(R.id.targetInfo)
        crosshair = view.findViewById(R.id.crosshair)
        fireButton = view.findViewById(R.id.fire)
        setupFireButton()
        updateAmmoCounter()

        initSound()

        return view
    }

    override fun onDestroyView() {
        super.onDestroyView()

        // Para o combate e libera os recursos
        firing = false
        gameLoopRunning = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        handler.removeCallbacksAndMessages(null)
        animals.clear()
        bullets.clear()
        explosions.clear()
        rifleNode = null
        explosionPlayers.toList().forEach { releasePlayer(it) }
        explosionPlayers.clear()

        soundPool?.release()
        soundPool = null
        summonSoundLoaded = false
        shotSoundLoaded = false
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        checkCameraPermission()
    }

    override fun onPause() {
        super.onPause()
        stopFiring()
    }

    private fun placeModel() {
        if (!::modelNode.isInitialized) return

        val placedNode = modelNode
        placedNode.anchor()
        placedNode.isEditable = true
        placedNode.isScaleEditable = false

        sceneView.planeRenderer.isVisible = false

        // Se o modelo invocado for um animal, toca o som, conta o ponto e entra no combate
        if (currentModelFile in animalModels) {
            animals.add(
                Animal(
                    placedNode,
                    hitRadiusByModel[currentModelFile] ?: DEFAULT_HIT_RADIUS
                )
            )
            playSummonSound()
            summonCount++
            updateSummonCounter()
        }

        // O nó atual fica fixo na cena; cria um novo nó de pré-visualização para a próxima invocação
        createPreviewNode(currentModelFile, resetAngle = false)
    }

    /**
     * Gira o modelo 45 graus no eixo Y a cada toque no botão "Girar".
     */
    private fun rotateModel() {
        if (!::modelNode.isInitialized) return
        modelAngleY = (modelAngleY + 45f) % 360f
        modelNode.modelRotation = Rotation(0f, modelAngleY, 0f)
    }

    private fun updateSummonCounter() {
        summonCounter.text = getString(R.string.summon_counter, summonCount)
    }

    // ---------------------------------------------------------------------------------------
    // ARMA
    // ---------------------------------------------------------------------------------------

    private fun setupFireButton() {
        fireButton.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    startFiring()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    stopFiring()
                    if (event.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                }
            }
            true
        }
    }

    private fun startFiring() {
        if (firing) return
        firing = true
        handler.removeCallbacks(fireRunnable)
        handler.post(fireRunnable) // atira imediatamente e continua enquanto o botão estiver pressionado
    }

    private fun stopFiring() {
        firing = false
        handler.removeCallbacks(fireRunnable)
    }

    /**
     * Dispara um projétil na direção para onde a câmera aponta, se houver munição.
     */
    private fun tryFire() {
        if (reloading || ammo <= 0) return
        if (bullets.isEmpty()) return
        val bullet = bullets.firstOrNull { !it.active } ?: return

        // Posição e direção da câmera (a câmera olha para -Z no seu espaço local)
        val m = sceneView.cameraNode.worldTransform
        var fx = -m.z.x
        var fy = -m.z.y
        var fz = -m.z.z
        val len = sqrt(fx * fx + fy * fy + fz * fz)
        if (len < 1e-5f) return
        fx /= len; fy /= len; fz /= len

        val camPos = sceneView.cameraNode.worldPosition

        // Sai da ponta do cano do rifle (à frente, abaixo e à direita da câmera)
        bullet.x = camPos.x + fx * MUZZLE_FORWARD + m.x.x * MUZZLE_RIGHT - m.y.x * MUZZLE_DOWN
        bullet.y = camPos.y + fy * MUZZLE_FORWARD + m.x.y * MUZZLE_RIGHT - m.y.y * MUZZLE_DOWN
        bullet.z = camPos.z + fz * MUZZLE_FORWARD + m.x.z * MUZZLE_RIGHT - m.y.z * MUZZLE_DOWN
        bullet.vx = fx * BULLET_SPEED
        bullet.vy = fy * BULLET_SPEED
        bullet.vz = fz * BULLET_SPEED
        bullet.life = BULLET_LIFETIME
        bullet.active = true
        bullet.node.worldPosition = Position(bullet.x, bullet.y, bullet.z)
        bullet.node.isVisible = true

        playShotSound()
        recoil = 1f

        ammo--
        updateAmmoCounter()

        // Acabou o pente: recarrega automaticamente após o cooldown
        if (ammo <= 0) startReload()
    }

    private fun startReload() {
        if (reloading) return
        reloading = true
        updateAmmoCounter()
        handler.removeCallbacks(reloadRunnable)
        handler.postDelayed(reloadRunnable, RELOAD_COOLDOWN_MS)
    }

    private fun updateAmmoCounter() {
        ammoCounter.text = if (reloading) {
            getString(R.string.reloading)
        } else {
            getString(R.string.ammo_counter, ammo, MAX_AMMO)
        }
    }

    /**
     * Cria o rifle em primeira pessoa, preso à câmera: ele acompanha todos os movimentos do aparelho.
     */
    private fun initRifle() {
        if (rifleNode != null) return
        val node = ArModelNode(sceneView.engine, PlacementMode.DISABLED).apply {
            loadModelGlbAsync(glbFileLocation = RIFLE_MODEL)
            position = Position(RIFLE_X, RIFLE_Y, RIFLE_Z)
            rotation = Rotation(RIFLE_PITCH, RIFLE_YAW, 0f)
            scale = Scale(RIFLE_SCALE, RIFLE_SCALE, RIFLE_SCALE)
        }
        sceneView.cameraNode.addChild(node)
        rifleNode = node
    }

    /**
     * Anima o recuo do rifle: a cada tiro ele vai para trás/para cima e volta suavemente.
     */
    private fun updateRifle(dt: Float) {
        val node = rifleNode ?: return
        if (recoil <= 0f) return
        recoil = max(0f, recoil - RECOIL_RECOVERY * dt)
        node.position = Position(RIFLE_X, RIFLE_Y, RIFLE_Z + RECOIL_BACK * recoil)
        node.rotation = Rotation(RIFLE_PITCH + RECOIL_PITCH * recoil, RIFLE_YAW, 0f)
    }

    // ---------------------------------------------------------------------------------------
    // SIMULAÇÃO DOS PROJÉTEIS
    // ---------------------------------------------------------------------------------------

    private fun updateBullets(dt: Float) {
        if (dt <= 0f) return
        for (bullet in bullets) {
            if (!bullet.active) continue

            val px = bullet.x
            val py = bullet.y
            val pz = bullet.z
            bullet.x += bullet.vx * dt
            bullet.y += bullet.vy * dt
            bullet.z += bullet.vz * dt
            bullet.life -= dt

            // Testa colisão do trajeto do projétil neste quadro contra cada animal vivo
            val target = animals.firstOrNull { animal ->
                animal.alive && segmentHitsSphere(
                    px, py, pz, bullet.x, bullet.y, bullet.z,
                    animal.node.worldPosition.x,
                    animal.node.worldPosition.y,
                    animal.node.worldPosition.z,
                    animal.hitRadius
                )
            }

            if (target != null) {
                releaseBullet(bullet)
                hitAnimal(target)
            } else if (bullet.life <= 0f) {
                releaseBullet(bullet)
            } else {
                bullet.node.worldPosition = Position(bullet.x, bullet.y, bullet.z)
            }
        }
    }

    private fun releaseBullet(bullet: Bullet) {
        bullet.active = false
        bullet.node.isVisible = false
    }

    private fun segmentHitsSphere(
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float,
        radius: Float
    ): Boolean {
        val dx = bx - ax
        val dy = by - ay
        val dz = bz - az
        val lenSq = dx * dx + dy * dy + dz * dz
        var t = if (lenSq > 0f) ((cx - ax) * dx + (cy - ay) * dy + (cz - az) * dz) / lenSq else 0f
        t = max(0f, min(1f, t))
        val qx = ax + dx * t - cx
        val qy = ay + dy * t - cy
        val qz = az + dz * t - cz
        return qx * qx + qy * qy + qz * qz <= radius * radius
    }

    // ---------------------------------------------------------------------------------------
    // DANO, MORTE E EXPLOSÃO
    // ---------------------------------------------------------------------------------------

    private fun hitAnimal(animal: Animal) {
        animal.health -= BULLET_DAMAGE

        // Feedback visual: mira vermelha por um instante
        crosshair.setColorFilter(Color.RED)
        handler.removeCallbacks(resetCrosshairRunnable)
        handler.postDelayed(resetCrosshairRunnable, 120)

        if (animal.health <= 0f) {
            animal.health = 0f
            animal.alive = false
            showTargetInfo(getString(R.string.target_dead))
            explodeAnimal(animal)
        } else {
            showTargetInfo(
                getString(
                    R.string.target_health,
                    String.format(Locale.getDefault(), "%.1f", animal.health),
                    String.format(Locale.getDefault(), "%.0f", ANIMAL_MAX_HEALTH)
                )
            )
        }
    }

    private fun showTargetInfo(text: String) {
        targetInfo.text = text
        targetInfo.visibility = View.VISIBLE
        handler.removeCallbacks(clearTargetInfoRunnable)
        handler.postDelayed(clearTargetInfoRunnable, 1500)
    }

    /**
     * Remove o animal da cena, cria a explosão e toca o nuke-alarm.
     */
    private fun explodeAnimal(animal: Animal) {
        val pos = animal.node.worldPosition

        animals.remove(animal)
        try {
            sceneView.removeChild(animal.node)
            animal.node.destroy()
        } catch (e: Exception) {
        }

        spawnExplosion(Position(pos.x, pos.y, pos.z))
        playExplosionSound()
    }

    private fun spawnExplosion(position: Position) {
        val node = ArModelNode(sceneView.engine, PlacementMode.DISABLED).apply {
            loadModelGlbAsync(glbFileLocation = EXPLOSION_MODEL)
            worldPosition = position
            scale = Scale(0.2f, 0.2f, 0.2f)
        }
        sceneView.addChild(node)
        explosions.add(Explosion(node))
    }

    private fun updateExplosions(dt: Float) {
        if (explosions.isEmpty()) return
        val iterator = explosions.iterator()
        while (iterator.hasNext()) {
            val explosion = iterator.next()
            explosion.age += dt
            val progress = explosion.age / EXPLOSION_DURATION
            if (progress >= 1f) {
                try {
                    sceneView.removeChild(explosion.node)
                    explosion.node.destroy()
                } catch (e: Exception) {
                }
                iterator.remove()
            } else {
                val s = 0.2f + (EXPLOSION_MAX_SCALE - 0.2f) * progress
                explosion.node.scale = Scale(s, s, s)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // ÁUDIO
    // ---------------------------------------------------------------------------------------

    private fun initSound() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(8)
            .setAudioAttributes(attrs)
            .build()
            .also { pool ->
                pool.setOnLoadCompleteListener { _, sampleId, status ->
                    if (sampleId == summonSoundId) summonSoundLoaded = (status == 0)
                    if (sampleId == shotSoundId) shotSoundLoaded = (status == 0)
                }
                summonSoundId = pool.load(requireContext(), R.raw.taco_bell_bong, 1)
                shotSoundId = pool.load(requireContext(), R.raw.m16_burst_shot, 1)
            }
    }

    private fun playSummonSound() {
        if (summonSoundLoaded) {
            soundPool?.play(summonSoundId, 1f, 1f, 1, 0, 1f)
        }
    }

    private fun playShotSound() {
        if (shotSoundLoaded) {
            soundPool?.play(shotSoundId, 1f, 1f, 2, 0, 1f)
        }
    }

    private fun playExplosionSound() {
        val context = context ?: return
        try {
            val player = MediaPlayer.create(context, R.raw.nuke_alarm) ?: return
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            player.setOnCompletionListener { releasePlayer(it) }
            explosionPlayers.add(player)
            player.start()
        } catch (e: Exception) {
        }
    }

    private fun releasePlayer(player: MediaPlayer) {
        explosionPlayers.remove(player)
        try {
            player.release()
        } catch (e: Exception) {
        }
    }

    // ---------------------------------------------------------------------------------------
    // CÂMERA / AR
    // ---------------------------------------------------------------------------------------

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(android.Manifest.permission.CAMERA),
                CAMERA_PERMISSION_CODE
            )
        } else {
            initAR()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            initAR()
        } else {
            Toast.makeText(requireContext(), "Permissão de câmera negada", Toast.LENGTH_LONG).show()
        }
    }

    private fun initAR() {
        initBulletPool()
        initRifle()
        startGameLoop()
        loadModel(currentModelFile)
    }

    /**
     * Pré-carrega os projéteis (ocultos) para que atirar não precise carregar nenhum arquivo.
     */
    private fun initBulletPool() {
        if (bullets.isNotEmpty()) return
        repeat(BULLET_POOL_SIZE) {
            val node = ArModelNode(sceneView.engine, PlacementMode.DISABLED).apply {
                loadModelGlbAsync(glbFileLocation = BULLET_MODEL)
                isVisible = false
            }
            sceneView.addChild(node)
            bullets.add(Bullet(node))
        }
    }

    private fun startGameLoop() {
        if (gameLoopRunning) return
        gameLoopRunning = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    /**
     * Troca o modelo exibido na pré-visualização. Animais já invocados continuam na cena.
     */
    private fun loadModel(fileName: String) {
        currentModelFile = fileName
        createPreviewNode(fileName, resetAngle = true)
    }

    private fun createPreviewNode(fileName: String, resetAngle: Boolean) {
        if (resetAngle) modelAngleY = 0f

        // Remove apenas a pré-visualização anterior (nunca um animal já invocado)
        if (::modelNode.isInitialized && animals.none { it.node === modelNode }) {
            try {
                sceneView.removeChild(modelNode)
            } catch (e: Exception) {

            }
        }

        modelNode = ArModelNode(sceneView.engine, PlacementMode.INSTANT).apply {
            loadModelGlbAsync(
                glbFileLocation = getFilePath(requireContext(), fileName),
                centerOrigin = Position(0f, 0f, 1f),

            ) {
                sceneView.planeRenderer.isVisible = true
            }
            // Permitir interação com o modelo
            isEditable = true
            isScaleEditable = false

        }

        if (!resetAngle) {
            modelNode.modelRotation = Rotation(0f, modelAngleY, 0f)
        }

        sceneView.addChild(modelNode)
    }

    private fun getFilePath(context: Context?, fileName: String): String {
        try {
            context?.assets?.open(fileName)?.close() // Tenta abrir o arquivo dos assets
            return fileName // Se abrir sem erro, presume-se que está nos assets
        } catch (e: IOException) {
            // Arquivo não encontrado nos assets, agora vamos procurar no filesDir
            val internalFile = File(context?.filesDir, fileName)
            if (internalFile.exists()) {
                return  Uri.fromFile(internalFile).toString()
            } else {
                return "Arquivo não encontrado em assets nem em filesDir: $fileName"
            }
        }
    }
}
