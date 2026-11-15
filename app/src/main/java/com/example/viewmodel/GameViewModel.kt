package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.audio.GameAudioController
import com.example.audio.NoOpAudioController
import com.example.billing.DiamondCatalog
import com.example.data.AchievementCatalog
import com.example.data.AchievementDef
import com.example.data.BetCurrency
import com.example.data.BoosterType
import com.example.data.CasinoCatalog
import com.example.data.ComboSystem
import com.example.data.DailyQuestCatalog
import com.example.data.DailyQuestDef
import com.example.data.DailyRewardCatalog
import com.example.data.DayClock
import com.example.data.GameRepository
import com.example.data.GameSettings
import com.example.data.GameStateEntity
import com.example.data.PremiumShopCosts
import com.example.data.RelicId
import com.example.data.SeasonPassCatalog
import com.example.data.SettingsRepository
import com.example.data.ShardShopCosts
import com.example.ui.Formatters
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToLong
import kotlin.random.Random

enum class GameTab {
    TAP,
    POINT_SHOP,
    CASH_SHOP,
    ACHIEVEMENTS,
    RESTARTS
}

data class FloatingPopup(
    val id: Long,
    val text: String,
    val offsetXFraction: Float,
    val comboCount: Int = 0,
    val isCrit: Boolean = false
)

data class ToastBanner(
    val id: Long,
    val message: String,
    val isError: Boolean
)

data class GameUiState(
    val isLoading: Boolean = true,
    val game: GameStateEntity = GameStateEntity(),
    val selectedTab: GameTab = GameTab.TAP,
    val isCooldownActive: Boolean = false,
    val cooldownRemainingFraction: Float = 0f,
    val comboCount: Int = 0,
    val comboMultiplier: Double = 1.0,
    val comboRemainingFraction: Float = 0f,
    val comboTierLabel: String = ComboSystem.tierLabelFor(0),
    val isOverdriveActive: Boolean = false,
    val overdriveRemainingFraction: Float = 0f,
    val tapBurstCounter: Long = 0L,
    val lastTapWasCrit: Boolean = false,
    val floatingPopups: List<FloatingPopup> = emptyList(),
    val toastBanner: ToastBanner? = null,
    val showAutoUpgradeSheet: Boolean = false,
    val showRestartDialog: Boolean = false,
    val autoTickCounter: Long = 0L,
    val offlineEarnedPoints: Double = 0.0,
    val unclaimedAchievementCount: Int = 0,
    val settings: GameSettings = GameSettings(),
    val showSettings: Boolean = false,
    val showPremiumShop: Boolean = false,
    val showLeaderboard: Boolean = false,
    val showDailyRewards: Boolean = false,
    val showSeasonPass: Boolean = false,
    val showCasino: Boolean = false,
    val showBossAndRelics: Boolean = false,
    val premiumShopTab: Int = 0,
    val goldenFeverRemainingSec: Long = 0L,
    val tapSurgeRemainingSec: Long = 0L,
    val critStormRemainingSec: Long = 0L,
    val autoFrenzyRemainingSec: Long = 0L,
    val canClaimDailyToday: Boolean = false,
    val unclaimedSeasonRewardsCount: Int = 0,
    val unclaimedDailyQuestCount: Int = 0,
    val activeSupplyDrop: SupplyDrop? = null,
    val casinoSelectedGame: Int = 0,
    val casinoCurrency: BetCurrency = BetCurrency.POINTS,
    val casinoBetTier: Int = 0,
    val wheelSpinId: Long = 0L,
    val wheelTargetSliceIndex: Int = 0,
    val slotReels: List<String> = listOf("7️⃣", "💎", "7️⃣"),
    val lastDicePair: Pair<Int, Int>? = null,
    val lastCasinoBanner: String? = null,
    val lastCasinoWon: Boolean = false,
    val minefield: MinefieldState? = null,
    val bossHitCounter: Long = 0L,
    val lastBossDamageText: String? = null,
    val lastMysteryCrateReward: String? = null
)

class GameViewModel(
    private val repository: GameRepository,
    private val settingsRepository: SettingsRepository,
    private val audioController: GameAudioController = NoOpAudioController()
) : ViewModel() {

    private val _uiState = MutableStateFlow(GameUiState(settings = settingsRepository.load()))
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

    private var cooldownJob: Job? = null
    private var comboTimerJob: Job? = null
    private var overdriveJob: Job? = null
    private var toastJob: Job? = null
    private var nextPopupId = 1L
    private var nextToastId = 1L

    @Volatile
    private var isDirty = false

    init {
        viewModelScope.launch {
            val loaded = repository.loadOrCreate()
            val now = System.currentTimeMillis()
            val elapsedSec = ((now - loaded.updatedAt) / 1000.0).coerceIn(0.0, 14_400.0)

            var initialGame = loaded
            var offlineEarned = 0.0
            if (elapsedSec >= 10.0) {
                val passiveRate = loaded.passivePointsPerSec
                val autoRate = if (loaded.autoUnlocked && loaded.autoEnabled) {
                    (loaded.effectiveAutoPointsPerTick / loaded.autoCooldownSec.coerceAtLeast(0.05)) * 0.5
                } else {
                    0.0
                }
                offlineEarned = (passiveRate + autoRate) * elapsedSec
                if (offlineEarned >= 1.0) {
                    initialGame = loaded.copy(
                        diem = loaded.diem + offlineEarned,
                        lifetimePoints = loaded.lifetimePoints + offlineEarned
                    )
                    repository.saveState(initialGame)
                } else {
                    offlineEarned = 0.0
                }
            }

            val initialGameSynced = MegaUpdateEngine.syncDailyQuestDay(initialGame)
            val initialFeverSec = ((initialGameSynced.premGoldenFeverEndAt - now) / 1000L).coerceAtLeast(0L)
            val initialTapSurgeSec = ((initialGameSynced.boosterTapSurgeEndAt - now) / 1000L).coerceAtLeast(0L)
            val initialCritStormSec = ((initialGameSynced.boosterCritStormEndAt - now) / 1000L).coerceAtLeast(0L)
            val initialAutoFrenzySec = ((initialGameSynced.boosterAutoFrenzyEndAt - now) / 1000L).coerceAtLeast(0L)

            _uiState.update {
                it.copy(
                    isLoading = false,
                    game = initialGameSynced,
                    offlineEarnedPoints = offlineEarned,
                    unclaimedAchievementCount = countUnclaimed(initialGameSynced),
                    goldenFeverRemainingSec = initialFeverSec,
                    tapSurgeRemainingSec = initialTapSurgeSec,
                    critStormRemainingSec = initialCritStormSec,
                    autoFrenzyRemainingSec = initialAutoFrenzySec,
                    canClaimDailyToday = DailyRewardCatalog.canClaimToday(initialGameSynced),
                    unclaimedSeasonRewardsCount = MegaUpdateEngine.countUnclaimedSeasonRewards(initialGameSynced),
                    unclaimedDailyQuestCount = MegaUpdateEngine.countUnclaimedDailyQuests(initialGameSynced)
                )
            }
            startAutoWorker()
            startPassiveWorker()
            startGoldenFeverTicker()
            startSupplyDropLoop()
            startPeriodicSaveLoop()
        }
    }

    private fun countUnclaimed(game: GameStateEntity): Int {
        return AchievementCatalog.all.count { def ->
            !game.isAchievementClaimed(def.id) && def.progressSelector(game) >= def.targetValue
        }
    }

    private fun startAutoWorker() {
        viewModelScope.launch {
            while (isActive) {
                val stateSnap = _uiState.value
                val snapshot = stateSnap.game
                if (snapshot.autoUnlocked && snapshot.autoEnabled) {
                    val speedBoost = if (stateSnap.autoFrenzyRemainingSec > 0L) 0.4 else 1.0
                    val delayMs = (snapshot.autoCooldownSec * speedBoost * 1000.0).roundToLong().coerceAtLeast(40L)
                    delay(delayMs)
                    val stateNow = _uiState.value
                    val latest = stateNow.game
                    if (latest.autoUnlocked && latest.autoEnabled) {
                        val overdriveMult = if (stateNow.isOverdriveActive) 2.0 else 1.0
                        val goldenMult = if (stateNow.goldenFeverRemainingSec > 0L) 3.0 else 1.0
                        val frenzyMult = if (stateNow.autoFrenzyRemainingSec > 0L) 2.0 else 1.0
                        val gained = latest.effectiveAutoPointsPerTick * overdriveMult * goldenMult * frenzyMult
                        _uiState.update { state ->
                            val updatedGame = state.game.copy(
                                diem = state.game.diem + gained,
                                lifetimePoints = state.game.lifetimePoints + gained,
                                clicks = state.game.clicks + 1L
                            )
                            state.copy(
                                game = updatedGame,
                                autoTickCounter = state.autoTickCounter + 1L,
                                unclaimedAchievementCount = countUnclaimed(updatedGame)
                            )
                        }
                        isDirty = true
                    }
                } else {
                    delay(120L)
                }
            }
        }
    }

    private fun startPassiveWorker() {
        viewModelScope.launch {
            while (isActive) {
                delay(500L)
                val stateNow = _uiState.value
                val rate = stateNow.game.passivePointsPerSec
                if (!stateNow.isLoading && rate > 0.0) {
                    val overdriveMult = if (stateNow.isOverdriveActive) 2.0 else 1.0
                    val goldenMult = if (stateNow.goldenFeverRemainingSec > 0L) 3.0 else 1.0
                    val gained = rate * 0.5 * overdriveMult * goldenMult
                    _uiState.update { state ->
                        val updatedGame = state.game.copy(
                            diem = state.game.diem + gained,
                            lifetimePoints = state.game.lifetimePoints + gained
                        )
                        state.copy(
                            game = updatedGame,
                            unclaimedAchievementCount = countUnclaimed(updatedGame)
                        )
                    }
                    isDirty = true
                }
            }
        }
    }

    private fun startGoldenFeverTicker() {
        viewModelScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val g = _uiState.value.game
                val remFever = ((g.premGoldenFeverEndAt - now) / 1000L).coerceAtLeast(0L)
                val remTapSurge = ((g.boosterTapSurgeEndAt - now) / 1000L).coerceAtLeast(0L)
                val remCritStorm = ((g.boosterCritStormEndAt - now) / 1000L).coerceAtLeast(0L)
                val remAutoFrenzy = ((g.boosterAutoFrenzyEndAt - now) / 1000L).coerceAtLeast(0L)
                val canDaily = DailyRewardCatalog.canClaimToday(g)

                val cur = _uiState.value
                if (cur.goldenFeverRemainingSec != remFever ||
                    cur.tapSurgeRemainingSec != remTapSurge ||
                    cur.critStormRemainingSec != remCritStorm ||
                    cur.autoFrenzyRemainingSec != remAutoFrenzy ||
                    cur.canClaimDailyToday != canDaily
                ) {
                    _uiState.update {
                        it.copy(
                            goldenFeverRemainingSec = remFever,
                            tapSurgeRemainingSec = remTapSurge,
                            critStormRemainingSec = remCritStorm,
                            autoFrenzyRemainingSec = remAutoFrenzy,
                            canClaimDailyToday = canDaily
                        )
                    }
                }
                delay(500L)
            }
        }
    }

    private fun startSupplyDropLoop() {
        viewModelScope.launch {
            while (isActive) {
                delay(28_000L)
                val cur = _uiState.value
                if (!cur.isLoading && cur.activeSupplyDrop == null) {
                    val g = cur.game
                    val roll = Random.nextInt(100)
                    val drop = when {
                        roll < 35 -> {
                            val pts = maxOf(800.0, g.effectivePointsPerClick * 60.0)
                            SupplyDrop(
                                id = System.currentTimeMillis(),
                                label = "Tàu Tiếp Tế: +${Formatters.formatPoints(pts)}đ",
                                rewardType = SupplyDropReward.POINTS,
                                amount = pts
                            )
                        }
                        roll < 65 -> SupplyDrop(
                            id = System.currentTimeMillis(),
                            label = "Kén Kim Cương: +8💎",
                            rewardType = SupplyDropReward.DIAMONDS,
                            amount = 8.0
                        )
                        roll < 85 -> SupplyDrop(
                            id = System.currentTimeMillis(),
                            label = "Thùng Đô La: +$25",
                            rewardType = SupplyDropReward.DOLLARS,
                            amount = 25.0
                        )
                        else -> SupplyDrop(
                            id = System.currentTimeMillis(),
                            label = "Hộp XP Mùa 1: +45 XP",
                            rewardType = SupplyDropReward.SEASON_XP,
                            amount = 45.0
                        )
                    }
                    _uiState.update { it.copy(activeSupplyDrop = drop) }
                    delay(14_000L)
                    _uiState.update { state ->
                        if (state.activeSupplyDrop?.id == drop.id) state.copy(activeSupplyDrop = null) else state
                    }
                }
            }
        }
    }

    private fun startPeriodicSaveLoop() {
        viewModelScope.launch {
            while (isActive) {
                delay(1500L)
                if (isDirty) {
                    isDirty = false
                    repository.saveState(_uiState.value.game)
                }
            }
        }
    }

    fun flushSave() {
        val current = _uiState.value
        if (!current.isLoading && isDirty) {
            isDirty = false
            viewModelScope.launch {
                repository.saveState(current.game)
            }
        }
    }

    fun dismissOfflineBanner() {
        _uiState.update { it.copy(offlineEarnedPoints = 0.0) }
    }

    fun selectTab(tab: GameTab) {
        _uiState.update {
            it.copy(
                selectedTab = tab,
                showAutoUpgradeSheet = false,
                showRestartDialog = false
            )
        }
    }

    fun onTapCore() {
        val current = _uiState.value
        if (current.isLoading || current.isCooldownActive) return

        val newCombo = current.comboCount + 1
        val comboMult = ComboSystem.multiplierFor(newCombo)
        val comboLabel = ComboSystem.tierLabelFor(newCombo)
        val critStormActive = current.critStormRemainingSec > 0L
        val isCrit = critStormActive || Random.nextDouble(0.0, 100.0) < current.game.critChancePercent
        val critMult = if (isCrit) current.game.critMultiplier else 1.0
        val overdriveMult = if (current.isOverdriveActive) 2.0 else 1.0
        val goldenMult = if (current.goldenFeverRemainingSec > 0L) 3.0 else 1.0
        val tapSurgeMult = if (current.tapSurgeRemainingSec > 0L) 4.0 else 1.0
        val gained = current.game.effectivePointsPerClick * comboMult * critMult * overdriveMult * goldenMult * tapSurgeMult

        val popupId = nextPopupId++
        val offsetFraction = ((popupId % 5) - 2) * 0.14f
        val popupText = buildString {
            if (isCrit) append("CRIT! ")
            append("+${Formatters.formatDecimal(gained)}")
            if (comboMult > 1.0 && !isCrit) {
                append(" (x${Formatters.formatDecimal(comboMult)})")
            }
        }

        val nextCharge = if (current.isOverdriveActive) {
            current.game.overdriveCharge
        } else {
            (current.game.overdriveCharge + current.game.overdriveChargePerTap).coerceAtMost(100.0)
        }

        _uiState.update { state ->
            val updatedPopups = (state.floatingPopups + FloatingPopup(
                id = popupId,
                text = popupText,
                offsetXFraction = offsetFraction,
                comboCount = newCombo,
                isCrit = isCrit
            )).takeLast(6)

            val synced = MegaUpdateEngine.syncDailyQuestDay(state.game)
            val withXp = MegaUpdateEngine.addSeasonXp(
                synced.copy(
                    diem = synced.diem + gained,
                    lifetimePoints = synced.lifetimePoints + gained,
                    clicks = synced.clicks + 1L,
                    totalCrits = synced.totalCrits + if (isCrit) 1L else 0L,
                    bestCombo = maxOf(synced.bestCombo, newCombo),
                    overdriveCharge = nextCharge,
                    dailyQuestTaps = synced.dailyQuestTaps + 1,
                    dailyQuestCrits = synced.dailyQuestCrits + if (isCrit) 1 else 0
                ),
                baseXp = if (isCrit) 5L else 2L
            )

            state.copy(
                game = withXp,
                isCooldownActive = true,
                cooldownRemainingFraction = 1f,
                comboCount = newCombo,
                comboMultiplier = comboMult,
                comboRemainingFraction = 1f,
                comboTierLabel = comboLabel,
                tapBurstCounter = state.tapBurstCounter + 1L,
                lastTapWasCrit = isCrit,
                floatingPopups = updatedPopups,
                unclaimedAchievementCount = countUnclaimed(withXp),
                unclaimedSeasonRewardsCount = MegaUpdateEngine.countUnclaimedSeasonRewards(withXp),
                unclaimedDailyQuestCount = MegaUpdateEngine.countUnclaimedDailyQuests(withXp)
            )
        }
        isDirty = true
        if (isCrit) {
            audioController.playCrit()
        } else {
            audioController.playTap(newCombo)
        }

        viewModelScope.launch {
            delay(650L)
            _uiState.update { state ->
                state.copy(floatingPopups = state.floatingPopups.filterNot { it.id == popupId })
            }
        }

        comboTimerJob?.cancel()
        comboTimerJob = viewModelScope.launch {
            val windowMs = maxOf(
                current.game.comboWindowMs,
                (current.game.clickCooldownSec * 1000.0).roundToLong() + 1200L
            )
            val comboStart = System.currentTimeMillis()
            while (isActive) {
                val elapsed = System.currentTimeMillis() - comboStart
                val remaining = (windowMs - elapsed).coerceAtLeast(0L)
                if (remaining == 0L) break
                _uiState.update {
                    it.copy(comboRemainingFraction = remaining.toFloat() / windowMs.toFloat())
                }
                delay(32L)
            }
            _uiState.update {
                it.copy(
                    comboCount = 0,
                    comboMultiplier = 1.0,
                    comboRemainingFraction = 0f,
                    comboTierLabel = ComboSystem.tierLabelFor(0)
                )
            }
        }

        cooldownJob?.cancel()
        cooldownJob = viewModelScope.launch {
            val speedFactor = if (current.isOverdriveActive) 0.5 else 1.0
            val totalMs = (current.game.effectiveClickCooldownSec * speedFactor * 1000.0)
                .roundToLong()
                .coerceAtLeast(5L)
            val startTime = System.currentTimeMillis()
            while (isActive) {
                val elapsed = System.currentTimeMillis() - startTime
                val remaining = (totalMs - elapsed).coerceAtLeast(0L)
                if (remaining == 0L) break
                _uiState.update {
                    it.copy(cooldownRemainingFraction = remaining.toFloat() / totalMs.toFloat())
                }
                delay(16L)
            }
            _uiState.update {
                it.copy(isCooldownActive = false, cooldownRemainingFraction = 0f)
            }
        }
    }

    fun activateOverdrive() {
        val current = _uiState.value
        if (current.isOverdriveActive || current.game.overdriveCharge < 100.0) return

        val updatedGame = current.game.copy(overdriveCharge = 0.0)
        _uiState.update {
            it.copy(
                game = updatedGame,
                isOverdriveActive = true,
                overdriveRemainingFraction = 1f
            )
        }
        isDirty = true
        audioController.playOverdrive()
        showToast("OVERDRIVE KÍCH HOẠT! x2 Điểm & 2x Tốc độ!", isError = false)

        overdriveJob?.cancel()
        overdriveJob = viewModelScope.launch {
            val totalMs = (updatedGame.overdriveDurationSec * 1000.0).roundToLong()
            val startTime = System.currentTimeMillis()
            while (isActive) {
                val elapsed = System.currentTimeMillis() - startTime
                val remaining = (totalMs - elapsed).coerceAtLeast(0L)
                if (remaining == 0L) break
                _uiState.update {
                    it.copy(overdriveRemainingFraction = remaining.toFloat() / totalMs.toFloat())
                }
                delay(40L)
            }
            _uiState.update {
                it.copy(isOverdriveActive = false, overdriveRemainingFraction = 0f)
            }
        }
    }

    fun toggleAutoEnabled() {
        val current = _uiState.value.game
        if (!current.autoUnlocked) return
        val nextEnabled = !current.autoEnabled
        updateAndPersist(current.copy(autoEnabled = nextEnabled))
        audioController.playUpgrade()
        showToast(
            if (nextEnabled) "Đã bật Auto Click" else "Đã tạm dừng Auto Click",
            isError = false
        )
    }

    // --- Point Shop (Shop 1) ---
    fun buyPointPower() {
        val g = _uiState.value.game
        val cost = g.discountedPointCost(g.pointPowerCost)
        if (g.diem >= cost) {
            updateAndPersist(
                g.copy(
                    diem = (g.diem - cost).coerceAtLeast(0.0),
                    pointsPerClick = g.pointsPerClick * 2.0,
                    pointPowerCost = g.pointPowerCost * 3.0,
                    pointPowerLv = g.pointPowerLv + 1
                )
            )
            audioController.playUpgrade()
            showToast("Mua nâng cấp +Điểm/Click thành công!", isError = false)
        } else {
            val needed = Formatters.formatPoints(cost - g.diem)
            showToast("Cần thêm $needed điểm để mua nâng cấp", isError = true)
        }
    }

    fun buyMultiplier() {
        val g = _uiState.value.game
        val cost = g.discountedPointCost(g.multiplierCost)
        if (g.diem >= cost) {
            val newMult = ((g.multiplier + 0.1) * 100.0).roundToLong() / 100.0
            updateAndPersist(
                g.copy(
                    diem = (g.diem - cost).coerceAtLeast(0.0),
                    multiplier = newMult,
                    multiplierCost = g.multiplierCost * 3.0,
                    multiplierLv = g.multiplierLv + 1
                )
            )
            audioController.playUpgrade()
            showToast("Tăng hệ số nhân lên ${Formatters.formatMultiplier(newMult)}!", isError = false)
        } else {
            val needed = Formatters.formatPoints(cost - g.diem)
            showToast("Cần thêm $needed điểm để mua nâng cấp", isError = true)
        }
    }

    fun buyCooldownReduction() {
        val g = _uiState.value.game
        val cost = g.discountedPointCost(g.cooldownCost)
        if (g.diem >= cost) {
            val newCd = (g.clickCooldownSec - g.cooldownStep).coerceAtLeast(0.01)
            updateAndPersist(
                g.copy(
                    diem = (g.diem - cost).coerceAtLeast(0.0),
                    clickCooldownSec = newCd,
                    cooldownStep = g.cooldownStep / 2.0,
                    cooldownCost = g.cooldownCost * 3.0,
                    cooldownLv = g.cooldownLv + 1
                )
            )
            audioController.playUpgrade()
            showToast("Giảm hồi chiêu xuống ${Formatters.formatSeconds(newCd)}!", isError = false)
        } else {
            val needed = Formatters.formatPoints(cost - g.diem)
            showToast("Cần thêm $needed điểm để mua nâng cấp", isError = true)
        }
    }

    fun buyCritChance() {
        val g = _uiState.value.game
        if (g.critChanceLv >= 15) {
            showToast("Tỉ lệ Chí mạng từ Shop đã đạt tối đa!", isError = true)
            return
        }
        val cost = g.discountedPointCost(g.critChanceCost)
        if (g.diem >= cost) {
            val next = g.copy(
                diem = (g.diem - cost).coerceAtLeast(0.0),
                critChanceLv = g.critChanceLv + 1,
                critChanceCost = g.critChanceCost * 2.4
            )
            updateAndPersist(next)
            audioController.playUpgrade()
            showToast("Tỉ lệ Chí mạng tăng lên ${Formatters.formatDecimal(next.critChancePercent)}%!", isError = false)
        } else {
            val needed = Formatters.formatPoints(cost - g.diem)
            showToast("Cần thêm $needed điểm để nâng Chí mạng", isError = true)
        }
    }

    fun buyCritMultiplier() {
        val g = _uiState.value.game
        val cost = g.discountedPointCost(g.critMultCost)
        if (g.diem >= cost) {
            val next = g.copy(
                diem = (g.diem - cost).coerceAtLeast(0.0),
                critMultLv = g.critMultLv + 1,
                critMultCost = g.critMultCost * 2.8
            )
            updateAndPersist(next)
            audioController.playUpgrade()
            showToast("Sát thương Chí mạng lên ${Formatters.formatMultiplier(next.critMultiplier)}!", isError = false)
        } else {
            val needed = Formatters.formatPoints(cost - g.diem)
            showToast("Cần thêm $needed điểm để nâng sát thương Crit", isError = true)
        }
    }

    fun buyComboWindow() {
        val g = _uiState.value.game
        if (g.comboWindowLv >= 12) {
            showToast("Thời gian giữ Combo đã đạt tối đa!", isError = true)
            return
        }
        val cost = g.discountedPointCost(g.comboWindowCost)
        if (g.diem >= cost) {
            val next = g.copy(
                diem = (g.diem - cost).coerceAtLeast(0.0),
                comboWindowLv = g.comboWindowLv + 1,
                comboWindowCost = g.comboWindowCost * 2.5
            )
            updateAndPersist(next)
            audioController.playUpgrade()
            showToast("Giữ Combo lâu hơn (${Formatters.formatSeconds(next.comboWindowMs / 1000.0)})!", isError = false)
        } else {
            val needed = Formatters.formatPoints(cost - g.diem)
            showToast("Cần thêm $needed điểm để kéo dài Combo", isError = true)
        }
    }

    // --- Exchange & $Shop$ ---
    fun exchange1000Points() {
        val g = _uiState.value.game
        if (g.diem >= 1000.0) {
            val dollarsGained = 1.0 * g.dollarExchangeMultiplier
            updateAndPersist(
                g.copy(
                    diem = (g.diem - 1000.0).coerceAtLeast(0.0),
                    dollars = g.dollars + dollarsGained
                )
            )
            audioController.playUpgrade()
            showToast("Đổi 1.000 điểm → +${Formatters.formatDollars(dollarsGained)} thành công!", isError = false)
        } else {
            val needed = Formatters.formatPoints(1000.0 - g.diem)
            showToast("Chưa đủ điểm! Cần thêm $needed điểm", isError = true)
        }
    }

    fun exchangeAllPoints() {
        val g = _uiState.value.game
        if (g.diem > 0.0) {
            val gained = (g.diem / 1000.0) * g.dollarExchangeMultiplier
            updateAndPersist(
                g.copy(
                    diem = 0.0,
                    dollars = g.dollars + gained
                )
            )
            audioController.playUpgrade()
            showToast("Đã đổi toàn bộ điểm (+${Formatters.formatDollars(gained)})!", isError = false)
        } else {
            showToast("Bạn chưa có điểm nào để quy đổi", isError = true)
        }
    }

    fun buyDiscount() {
        val g = _uiState.value.game
        if (g.dollars >= g.discountCost) {
            val newFactor = g.discountFactor + g.discountStep
            updateAndPersist(
                g.copy(
                    dollars = (g.dollars - g.discountCost).coerceAtLeast(0.0),
                    discountCost = g.discountCost * 3.0,
                    discountFactor = newFactor,
                    discountStep = g.discountStep * 2.0,
                    discountLv = g.discountLv + 1
                )
            )
            audioController.playUpgrade()
            showToast("Mua Giảm giá thành công (÷${Formatters.formatDecimal(newFactor)})!", isError = false)
        } else {
            val needed = Formatters.formatDollars(g.discountCost - g.dollars)
            showToast("Thiếu $needed để mua Giảm giá", isError = true)
        }
    }

    fun buyMenuSpeed() {
        val g = _uiState.value.game
        if (g.dollars >= g.menuSpeedCost) {
            val newMenuSec = (g.menuTransitionSec / 2.0).coerceAtLeast(0.05)
            updateAndPersist(
                g.copy(
                    dollars = (g.dollars - g.menuSpeedCost).coerceAtLeast(0.0),
                    menuTransitionSec = newMenuSec,
                    menuSpeedCost = g.menuSpeedCost * 3.0,
                    menuSpeedLv = g.menuSpeedLv + 1
                )
            )
            audioController.playUpgrade()
            showToast("Tăng tốc chuyển cảnh (${Formatters.formatSeconds(newMenuSec)})!", isError = false)
        } else {
            val needed = Formatters.formatDollars(g.menuSpeedCost - g.dollars)
            showToast("Thiếu $needed để mua vật phẩm", isError = true)
        }
    }

    fun buyToastSpeed() {
        val g = _uiState.value.game
        if (g.dollars >= g.toastSpeedCost) {
            val newToastSec = (g.toastDurationSec / 2.0).coerceAtLeast(0.15)
            updateAndPersist(
                g.copy(
                    dollars = (g.dollars - g.toastSpeedCost).coerceAtLeast(0.0),
                    toastDurationSec = newToastSec,
                    toastSpeedCost = g.toastSpeedCost * 3.0,
                    toastSpeedLv = g.toastSpeedLv + 1
                )
            )
            audioController.playUpgrade()
            showToast("Giảm thời gian thông báo (${Formatters.formatSeconds(newToastSec)})!", isError = false)
        } else {
            val needed = Formatters.formatDollars(g.toastSpeedCost - g.dollars)
            showToast("Thiếu $needed để mua vật phẩm", isError = true)
        }
    }

    fun buyAutoClick() {
        val g = _uiState.value.game
        if (g.autoUnlocked) {
            showToast("Bạn đã mua Auto Click rồi!", isError = true)
            return
        }
        val price = 25.0
        if (g.dollars >= price) {
            updateAndPersist(
                g.copy(
                    dollars = (g.dollars - price).coerceAtLeast(0.0),
                    autoUnlocked = true,
                    autoEnabled = true
                )
            )
            audioController.playUpgrade()
            showToast("Đã kích hoạt Auto Click!", isError = false)
        } else {
            val needed = Formatters.formatDollars(price - g.dollars)
            showToast("Thiếu $needed để mở khóa Auto Click", isError = true)
        }
    }

    fun buyPassiveCore() {
        val g = _uiState.value.game
        if (g.dollars >= g.passiveCost) {
            val next = g.copy(
                dollars = (g.dollars - g.passiveCost).coerceAtLeast(0.0),
                passiveLv = g.passiveLv + 1,
                passiveCost = g.passiveCost * 2.5
            )
            updateAndPersist(next)
            audioController.playUpgrade()
            showToast("Lõi Thụ động LV ${next.passiveLv} (+${Formatters.formatDecimal(next.passivePointsPerSec)}/s)!", isError = false)
        } else {
            val needed = Formatters.formatDollars(g.passiveCost - g.dollars)
            showToast("Thiếu $needed để nâng Lõi Thụ động", isError = true)
        }
    }

    fun buyOverdriveUpgrade() {
        val g = _uiState.value.game
        if (g.dollars >= g.overdriveCost) {
            val next = g.copy(
                dollars = (g.dollars - g.overdriveCost).coerceAtLeast(0.0),
                overdriveLv = g.overdriveLv + 1,
                overdriveCost = g.overdriveCost * 2.6
            )
            updateAndPersist(next)
            audioController.playUpgrade()
            showToast("Nâng cấp Overdrive lên ${Formatters.formatSeconds(next.overdriveDurationSec)}!", isError = false)
        } else {
            val needed = Formatters.formatDollars(g.overdriveCost - g.dollars)
            showToast("Thiếu $needed để nâng cấp Overdrive", isError = true)
        }
    }

    fun openAutoUpgradeSheet() {
        _uiState.update { it.copy(showAutoUpgradeSheet = true) }
    }

    fun dismissAutoUpgradeSheet() {
        _uiState.update { it.copy(showAutoUpgradeSheet = false) }
    }

    fun buyAutoPower() {
        val g = _uiState.value.game
        if (!g.autoUnlocked) {
            showToast("Cần mở khóa Auto Click trước", isError = true)
            return
        }
        if (g.dollars >= g.autoPowerCost) {
            val newAutoPoints = g.autoPoints * 2.0
            updateAndPersist(
                g.copy(
                    dollars = (g.dollars - g.autoPowerCost).coerceAtLeast(0.0),
                    autoPoints = newAutoPoints,
                    autoPowerCost = g.autoPowerCost * 3.0,
                    autoPowerLv = g.autoPowerLv + 1
                )
            )
            audioController.playUpgrade()
            showToast("Tăng điểm Auto lên ${Formatters.formatDecimal(newAutoPoints)}!", isError = false)
        } else {
            val needed = Formatters.formatDollars(g.autoPowerCost - g.dollars)
            showToast("Thiếu $needed để nâng cấp sức mạnh Auto", isError = true)
        }
    }

    fun buyAutoSpeed() {
        val g = _uiState.value.game
        if (!g.autoUnlocked) {
            showToast("Cần mở khóa Auto Click trước", isError = true)
            return
        }
        if (g.dollars >= g.autoSpeedCost) {
            val newAutoCd = (g.autoCooldownSec - g.autoSpeedStep).coerceAtLeast(0.05)
            updateAndPersist(
                g.copy(
                    dollars = (g.dollars - g.autoSpeedCost).coerceAtLeast(0.0),
                    autoCooldownSec = newAutoCd,
                    autoSpeedStep = g.autoSpeedStep / 2.0,
                    autoSpeedCost = g.autoSpeedCost * 3.0,
                    autoSpeedLv = g.autoSpeedLv + 1
                )
            )
            audioController.playUpgrade()
            showToast("Tăng tốc Auto còn ${Formatters.formatSeconds(newAutoCd)}!", isError = false)
        } else {
            val needed = Formatters.formatDollars(g.autoSpeedCost - g.dollars)
            showToast("Thiếu $needed để nâng cấp tốc độ Auto", isError = true)
        }
    }

    // --- Achievements ---
    fun claimAchievement(def: AchievementDef) {
        val g = _uiState.value.game
        if (g.isAchievementClaimed(def.id)) return
        if (def.progressSelector(g) < def.targetValue) {
            showToast("Chưa đạt điều kiện thành tựu!", isError = true)
            return
        }

        val updatedIds = if (g.claimedAchievements.isBlank()) {
            def.id
        } else {
            "${g.claimedAchievements},${def.id}"
        }
        updateAndPersist(
            g.copy(
                dollars = g.dollars + def.rewardDollars,
                shards = g.shards + def.rewardShards,
                claimedAchievements = updatedIds
            )
        )
        val rewardParts = buildList {
            if (def.rewardDollars > 0.0) add("+${Formatters.formatDollars(def.rewardDollars)}")
            if (def.rewardShards > 0L) add("+${def.rewardShards}✦")
        }.joinToString(" & ")
        audioController.playAchievement()
        showToast("Nhận thưởng thành tựu: $rewardParts!", isError = false)
    }

    // --- Permanent Shard Upgrades ---
    fun buyPermStartPoints() {
        val g = _uiState.value.game
        val cost = ShardShopCosts.costFor(g.permStartPointsLv, 2L)
        if (g.shards >= cost) {
            val next = g.copy(
                shards = g.shards - cost,
                permStartPointsLv = g.permStartPointsLv + 1
            )
            updateAndPersist(next)
            audioController.playUpgrade()
            showToast("Khởi đầu sau Restart: +${Formatters.formatPoints(next.permStartPointsAmount)} điểm!", isError = false)
        } else {
            showToast("Cần $cost✦ Mảnh Lõi để nâng cấp", isError = true)
        }
    }

    fun buyPermGlobalMult() {
        val g = _uiState.value.game
        val cost = ShardShopCosts.costFor(g.permGlobalMultLv, 3L)
        if (g.shards >= cost) {
            val next = g.copy(
                shards = g.shards - cost,
                permGlobalMultLv = g.permGlobalMultLv + 1
            )
            updateAndPersist(next)
            audioController.playUpgrade()
            showToast("Hệ số Vĩnh viễn tăng lên ${Formatters.formatMultiplier(next.globalPermanentMultiplier)}!", isError = false)
        } else {
            showToast("Cần $cost✦ Mảnh Lõi để nâng cấp", isError = true)
        }
    }

    fun buyPermCrit() {
        val g = _uiState.value.game
        if (g.permCritLv >= 8) {
            showToast("Chí mạng nền đã đạt tối đa!", isError = true)
            return
        }
        val cost = ShardShopCosts.costFor(g.permCritLv, 2L)
        if (g.shards >= cost) {
            val next = g.copy(
                shards = g.shards - cost,
                permCritLv = g.permCritLv + 1
            )
            updateAndPersist(next)
            audioController.playUpgrade()
            showToast("Chí mạng vĩnh viễn +3% (${Formatters.formatDecimal(next.critChancePercent)}%)!", isError = false)
        } else {
            showToast("Cần $cost✦ Mảnh Lõi để nâng cấp", isError = true)
        }
    }

    fun buyPermDiscount() {
        val g = _uiState.value.game
        val cost = ShardShopCosts.costFor(g.permDiscountLv, 3L)
        if (g.shards >= cost) {
            val next = g.copy(
                shards = g.shards - cost,
                permDiscountLv = g.permDiscountLv + 1,
                discountFactor = g.discountFactor + 0.15
            )
            updateAndPersist(next)
            audioController.playUpgrade()
            showToast("Giảm giá vĩnh viễn +15%!", isError = false)
        } else {
            showToast("Cần $cost✦ Mảnh Lõi để nâng cấp", isError = true)
        }
    }

    // --- Restarts (Prestige) ---
    fun requestRestartDialog() {
        val g = _uiState.value.game
        val req = g.restartRequirement
        if (g.diem >= req) {
            _uiState.update { it.copy(showRestartDialog = true) }
        } else {
            val needed = Formatters.formatPoints(req - g.diem)
            showToast("Chưa đủ điểm để Restarts! Cần thêm $needed điểm", isError = true)
        }
    }

    fun dismissRestartDialog() {
        _uiState.update { it.copy(showRestartDialog = false) }
    }

    fun confirmRestart() {
        val g = _uiState.value.game
        val req = g.restartRequirement
        if (g.diem < req) {
            _uiState.update { it.copy(showRestartDialog = false) }
            val needed = Formatters.formatPoints(req - g.diem)
            showToast("Cần thêm $needed điểm để Restarts", isError = true)
            return
        }

        comboTimerJob?.cancel()
        overdriveJob?.cancel()
        val shardsEarned = g.pendingShardsOnRestart
        val newBaseMultiplier = ((g.baseMultiplier + 0.5) * 100.0).roundToLong() / 100.0
        val startPoints = g.permStartPointsAmount
        val startDiscount = 1.0 + g.permStartDiscountBonus

        val resetState = GameStateEntity(
            id = 1,
            diem = startPoints,
            dollars = 0.0,
            clicks = 0L,
            multiplier = newBaseMultiplier,
            baseMultiplier = newBaseMultiplier,
            pointsPerClick = 1.0,
            clickCooldownSec = 0.25,
            cooldownStep = 0.1,
            discountFactor = startDiscount,
            discountStep = 0.1,
            pointPowerLv = 0,
            pointPowerCost = 25.0,
            multiplierLv = 0,
            multiplierCost = 50.0,
            cooldownLv = 0,
            cooldownCost = 75.0,
            discountLv = 0,
            discountCost = 5.0,
            menuSpeedLv = 0,
            menuSpeedCost = 3.0,
            menuTransitionSec = 1.0,
            toastSpeedLv = 0,
            toastSpeedCost = 3.0,
            toastDurationSec = 1.0,
            autoUnlocked = false,
            autoEnabled = false,
            autoPoints = 1.0,
            autoCooldownSec = 1.0,
            autoPowerLv = 0,
            autoPowerCost = 15.0,
            autoSpeedLv = 0,
            autoSpeedCost = 20.0,
            autoSpeedStep = 0.25,
            restartLv = g.restartLv + 1,
            restartCost = g.restartCost * 3.0,
            bestCombo = g.bestCombo,
            lifetimePoints = g.lifetimePoints + startPoints,
            totalCrits = g.totalCrits,
            critChanceLv = 0,
            critChanceCost = 120.0,
            critMultLv = 0,
            critMultCost = 200.0,
            comboWindowLv = 0,
            comboWindowCost = 90.0,
            passiveLv = 0,
            passiveCost = 8.0,
            overdriveCharge = 0.0,
            overdriveLv = 0,
            overdriveCost = 12.0,
            shards = g.shards + shardsEarned,
            permStartPointsLv = g.permStartPointsLv,
            permGlobalMultLv = g.permGlobalMultLv,
            permCritLv = g.permCritLv,
            permDiscountLv = g.permDiscountLv,
            claimedAchievements = g.claimedAchievements,
            diamonds = g.diamonds,
            hasVipPass = g.hasVipPass,
            premTapGodLv = g.premTapGodLv,
            premMegaCritLv = g.premMegaCritLv,
            premAutoGodLv = g.premAutoGodLv,
            premZeroCdLv = g.premZeroCdLv,
            premWealthMagnetLv = g.premWealthMagnetLv,
            premGoldenFeverEndAt = g.premGoldenFeverEndAt,
            dailyStreak = g.dailyStreak,
            lastDailyClaimDay = g.lastDailyClaimDay,
            totalDailyClaims = g.totalDailyClaims,
            seasonXp = g.seasonXp,
            seasonPassPremium = g.seasonPassPremium,
            claimedSeasonFree = g.claimedSeasonFree,
            claimedSeasonPremium = g.claimedSeasonPremium,
            gambleSpins = g.gambleSpins,
            gambleWins = g.gambleWins,
            gambleBiggestWin = g.gambleBiggestWin,
            gambleJackpotPool = g.gambleJackpotPool,
            bossLevel = g.bossLevel,
            bossCurrentHp = g.bossCurrentHp,
            bossesDefeated = g.bossesDefeated,
            relicTapLv = g.relicTapLv,
            relicCritLv = g.relicCritLv,
            relicLuckLv = g.relicLuckLv,
            relicBossLv = g.relicBossLv,
            relicPassiveLv = g.relicPassiveLv,
            boosterTapSurgeEndAt = g.boosterTapSurgeEndAt,
            boosterCritStormEndAt = g.boosterCritStormEndAt,
            boosterAutoFrenzyEndAt = g.boosterAutoFrenzyEndAt,
            dailyQuestDay = g.dailyQuestDay,
            dailyQuestTaps = g.dailyQuestTaps,
            dailyQuestCrits = g.dailyQuestCrits,
            dailyQuestGambles = g.dailyQuestGambles,
            dailyQuestBossKills = g.dailyQuestBossKills,
            claimedDailyQuests = g.claimedDailyQuests
        )

        _uiState.update {
            it.copy(
                game = resetState,
                showRestartDialog = false,
                isCooldownActive = false,
                cooldownRemainingFraction = 0f,
                comboCount = 0,
                comboMultiplier = 1.0,
                comboRemainingFraction = 0f,
                comboTierLabel = ComboSystem.tierLabelFor(0),
                isOverdriveActive = false,
                overdriveRemainingFraction = 0f,
                selectedTab = GameTab.TAP,
                unclaimedAchievementCount = countUnclaimed(resetState)
            )
        }
        isDirty = false
        viewModelScope.launch {
            repository.saveState(resetState)
        }
        audioController.playRestart()
        showToast(
            "Restarts thành công! +${shardsEarned}✦ • Hệ số ${Formatters.formatMultiplier(newBaseMultiplier)}",
            isError = false
        )
    }

    // --- Premium Shop & Real-Money Billing ---
    fun openPremiumShop(initialTab: Int = 0) {
        _uiState.update {
            it.copy(
                showPremiumShop = true,
                premiumShopTab = initialTab.coerceIn(0, 1)
            )
        }
    }

    fun closePremiumShop() {
        _uiState.update { it.copy(showPremiumShop = false) }
    }

    fun selectPremiumShopTab(tabIndex: Int) {
        _uiState.update { it.copy(premiumShopTab = tabIndex.coerceIn(0, 1)) }
    }

    fun grantRealMoneyProduct(productId: String, quantity: Int = 1) {
        val def = DiamondCatalog.findById(productId) ?: return
        val g = _uiState.value.game
        val safeQty = quantity.coerceAtLeast(1)
        val addedDiamonds = def.diamondsGranted * safeQty
        val next = g.copy(
            diamonds = g.diamonds + addedDiamonds,
            hasVipPass = g.hasVipPass || def.isVipPass
        )
        updateAndPersist(next)
        audioController.playDiamond()
        val msg = if (def.isVipPass) {
            "Kích hoạt ĐẶC QUYỀN VIP OMEGA + ${addedDiamonds}💎 thành công!"
        } else {
            "Nạp thành công +${Formatters.formatLong(addedDiamonds)}💎 Kim Cương!"
        }
        showToast(msg, isError = false)
    }

    fun showBillingMessage(message: String, isError: Boolean) {
        showToast(message, isError = isError)
    }

    fun buyPointsWithDiamonds(tier: Int) {
        val g = _uiState.value.game
        val (cost, pointsAmount, label) = when (tier) {
            0 -> Triple(5L, PremiumShopCosts.smallPointsPack(g), "Túi Điểm Khởi Nghiệp")
            1 -> Triple(20L, PremiumShopCosts.mediumPointsPack(g), "Rương Năng Lượng Lớn")
            else -> Triple(60L, PremiumShopCosts.megaPointsPack(g), "Kho Báu Lõi Vô Cực")
        }
        if (g.diamonds < cost) {
            showToast("Thiếu ${cost - g.diamonds}💎 Kim Cương!", isError = true)
            return
        }
        updateAndPersist(
            g.copy(
                diamonds = g.diamonds - cost,
                diem = g.diem + pointsAmount,
                lifetimePoints = g.lifetimePoints + pointsAmount
            )
        )
        audioController.playDiamond()
        showToast("$label: +${Formatters.formatPoints(pointsAmount)} điểm!", isError = false)
    }

    fun buyDollarsWithDiamonds(tier: Int) {
        val g = _uiState.value.game
        val (cost, dollarAmount, label) = when (tier) {
            0 -> Triple(10L, 25.0, "Vali Tiền Mặt")
            1 -> Triple(35L, 150.0, "Két Sắt Tỷ Phú")
            else -> Triple(90L, 600.0, "Ngân Hàng Trung Ương")
        }
        if (g.diamonds < cost) {
            showToast("Thiếu ${cost - g.diamonds}💎 Kim Cương!", isError = true)
            return
        }
        updateAndPersist(
            g.copy(
                diamonds = g.diamonds - cost,
                dollars = g.dollars + dollarAmount
            )
        )
        audioController.playDiamond()
        showToast("$label: +${Formatters.formatDollars(dollarAmount)}!", isError = false)
    }

    fun buyShardsWithDiamonds() {
        val g = _uiState.value.game
        val cost = 25L
        if (g.diamonds < cost) {
            showToast("Thiếu ${cost - g.diamonds}💎 Kim Cương!", isError = true)
            return
        }
        updateAndPersist(
            g.copy(
                diamonds = g.diamonds - cost,
                shards = g.shards + 5L
            )
        )
        audioController.playDiamond()
        showToast("Mua thành công +5✦ Mảnh Lõi!", isError = false)
    }

    fun buyInstantOverdriveWithDiamonds() {
        val g = _uiState.value.game
        val cost = 8L
        if (g.diamonds < cost) {
            showToast("Thiếu ${cost - g.diamonds}💎 Kim Cương!", isError = true)
            return
        }
        updateAndPersist(
            g.copy(
                diamonds = g.diamonds - cost,
                overdriveCharge = 100.0
            )
        )
        audioController.playDiamond()
        if (!_uiState.value.isOverdriveActive) {
            activateOverdrive()
        } else {
            showToast("Đã sạc đầy 100% Overdrive dự phòng!", isError = false)
        }
    }

    fun buyGoldenFeverWithDiamonds() {
        val g = _uiState.value.game
        val cost = 15L
        if (g.diamonds < cost) {
            showToast("Thiếu ${cost - g.diamonds}💎 Kim Cương!", isError = true)
            return
        }
        val now = System.currentTimeMillis()
        val baseEnd = maxOf(now, g.premGoldenFeverEndAt)
        val newEnd = baseEnd + 300_000L // +5 minutes
        val remSec = ((newEnd - now) / 1000L).coerceAtLeast(1L)
        updateAndPersist(
            g.copy(
                diamonds = g.diamonds - cost,
                premGoldenFeverEndAt = newEnd
            )
        )
        _uiState.update { it.copy(goldenFeverRemainingSec = remSec) }
        audioController.playDiamond()
        showToast("CƠN BÃO HOÀNG KIM x3 ĐIỂM (+5 phút) đã kích hoạt!", isError = false)
    }

    fun openMysteryCrateWithDiamonds() {
        val g = _uiState.value.game
        val cost = 30L
        if (g.diamonds < cost) {
            showToast("Thiếu ${cost - g.diamonds}💎 để mở Hộp Bí Ẩn!", isError = true)
            return
        }
        val roll = Random.nextInt(100)
        val (updatedGame, summary) = when {
            roll < 30 -> {
                val pts = PremiumShopCosts.mediumPointsPack(g) * 1.5
                g.copy(
                    diamonds = g.diamonds - cost,
                    diem = g.diem + pts,
                    lifetimePoints = g.lifetimePoints + pts
                ) to "Bão Năng Lượng: +${Formatters.formatPoints(pts)} điểm!"
            }
            roll < 58 -> {
                val cash = 180.0
                g.copy(
                    diamonds = g.diamonds - cost,
                    dollars = g.dollars + cash
                ) to "Mưa Đô La: +${Formatters.formatDollars(cash)}!"
            }
            roll < 82 -> {
                val shardsWon = 8L
                g.copy(
                    diamonds = g.diamonds - cost,
                    shards = g.shards + shardsWon
                ) to "Kho Tinh Thể: +${shardsWon}✦ Mảnh Lõi!"
            }
            roll < 95 -> {
                val jackpotDiamonds = 50L
                g.copy(
                    diamonds = g.diamonds - cost + jackpotDiamonds
                ) to "JACKPOT KIM CƯƠNG: +${jackpotDiamonds}💎!"
            }
            else -> {
                val megaPts = PremiumShopCosts.megaPointsPack(g)
                val bonusCash = 350.0
                g.copy(
                    diamonds = g.diamonds - cost,
                    diem = g.diem + megaPts,
                    lifetimePoints = g.lifetimePoints + megaPts,
                    dollars = g.dollars + bonusCash,
                    shards = g.shards + 10L
                ) to "SIÊU NỔ HŨ VŨ TRỤ: +${Formatters.formatPoints(megaPts)} điểm, +$350 & +10✦!"
            }
        }
        updateAndPersist(updatedGame)
        _uiState.update { it.copy(lastMysteryCrateReward = summary) }
        audioController.playDiamond()
        showToast(summary, isError = false)
    }

    fun buyPremTapGod() {
        val g = _uiState.value.game
        if (g.premTapGodLv >= 10) {
            showToast("Thánh Chỉ Bấm Tay đã đạt tối đa!", isError = true)
            return
        }
        val cost = PremiumShopCosts.upgradeCost(g.premTapGodLv, 20L)
        if (g.diamonds < cost) {
            showToast("Thiếu ${cost - g.diamonds}💎 Kim Cương!", isError = true)
            return
        }
        val next = g.copy(
            diamonds = g.diamonds - cost,
            premTapGodLv = g.premTapGodLv + 1
        )
        updateAndPersist(next)
        audioController.playDiamond()
        showToast("Thánh Chỉ Bấm Tay LV ${next.premTapGodLv} (x${Formatters.formatLong(next.premTapMultiplier.roundToLong())} Tap)!", isError = false)
    }

    fun buyPremMegaCrit() {
        val g = _uiState.value.game
        if (g.premMegaCritLv >= 5) {
            showToast("Lõi Hủy Diệt đã đạt tối đa!", isError = true)
            return
        }
        val cost = PremiumShopCosts.upgradeCost(g.premMegaCritLv, 30L)
        if (g.diamonds < cost) {
            showToast("Thiếu ${cost - g.diamonds}💎 Kim Cương!", isError = true)
            return
        }
        val next = g.copy(
            diamonds = g.diamonds - cost,
            premMegaCritLv = g.premMegaCritLv + 1
        )
        updateAndPersist(next)
        audioController.playDiamond()
        showToast("Lõi Hủy Diệt LV ${next.premMegaCritLv} (Crit ${Formatters.formatDecimal(next.critChancePercent)}% • ${Formatters.formatMultiplier(next.critMultiplier)})!", isError = false)
    }

    fun buyPremAutoGod() {
        val g = _uiState.value.game
        if (g.premAutoGodLv >= 10) {
            showToast("Siêu Trí Tuệ Auto đã đạt tối đa!", isError = true)
            return
        }
        val cost = PremiumShopCosts.upgradeCost(g.premAutoGodLv, 35L)
        if (g.diamonds < cost) {
            showToast("Thiếu ${cost - g.diamonds}💎 Kim Cương!", isError = true)
            return
        }
        val next = g.copy(
            diamonds = g.diamonds - cost,
            premAutoGodLv = g.premAutoGodLv + 1
        )
        updateAndPersist(next)
        audioController.playDiamond()
        showToast("Siêu Trí Tuệ Auto LV ${next.premAutoGodLv}!", isError = false)
    }

    fun buyPremZeroCd() {
        val g = _uiState.value.game
        if (g.premZeroCdLv >= 5) {
            showToast("Phá Vỡ Giới Hạn đã đạt tối đa!", isError = true)
            return
        }
        val cost = PremiumShopCosts.upgradeCost(g.premZeroCdLv, 40L)
        if (g.diamonds < cost) {
            showToast("Thiếu ${cost - g.diamonds}💎 Kim Cương!", isError = true)
            return
        }
        val next = g.copy(
            diamonds = g.diamonds - cost,
            premZeroCdLv = g.premZeroCdLv + 1
        )
        updateAndPersist(next)
        audioController.playDiamond()
        showToast("Phá Vỡ Giới Hạn LV ${next.premZeroCdLv} (Hồi chiêu thực: ${Formatters.formatSeconds(next.effectiveClickCooldownSec)})!", isError = false)
    }

    fun buyPremWealthMagnet() {
        val g = _uiState.value.game
        if (g.premWealthMagnetLv >= 8) {
            showToast("Nam Châm Tỷ Phú đã đạt tối đa!", isError = true)
            return
        }
        val cost = PremiumShopCosts.upgradeCost(g.premWealthMagnetLv, 45L)
        if (g.diamonds < cost) {
            showToast("Thiếu ${cost - g.diamonds}💎 Kim Cương!", isError = true)
            return
        }
        val next = g.copy(
            diamonds = g.diamonds - cost,
            premWealthMagnetLv = g.premWealthMagnetLv + 1
        )
        updateAndPersist(next)
        audioController.playDiamond()
        showToast("Nam Châm Tỷ Phú LV ${next.premWealthMagnetLv} (x${Formatters.formatDecimal(next.dollarExchangeMultiplier)} Đổi $)!", isError = false)
    }

    // --- Settings & Data Management ---
    fun openSettings() {
        _uiState.update { it.copy(showSettings = true) }
    }

    fun closeSettings() {
        _uiState.update { it.copy(showSettings = false) }
    }

    fun openLeaderboard() {
        _uiState.update { it.copy(showLeaderboard = true) }
    }

    fun closeLeaderboard() {
        _uiState.update { it.copy(showLeaderboard = false) }
    }

    fun updateSettings(newSettings: GameSettings) {
        val sanitized = newSettings.copy(
            bgmVolume = newSettings.bgmVolume.coerceIn(0f, 1f),
            sfxVolume = newSettings.sfxVolume.coerceIn(0f, 1f)
        )
        settingsRepository.save(sanitized)
        _uiState.update { it.copy(settings = sanitized) }
        audioController.applySettings(sanitized)
    }

    fun testSfx() {
        audioController.playCrit()
    }

    // --- Mega Update: Supply Drops, Daily Rewards, Season Pass, Cyber Casino, Boss Raid, Relics & Daily Quests ---
    fun collectSupplyDrop() {
        val drop = _uiState.value.activeSupplyDrop ?: return
        val g = _uiState.value.game
        val updated = when (drop.rewardType) {
            SupplyDropReward.POINTS -> g.copy(diem = g.diem + drop.amount, lifetimePoints = g.lifetimePoints + drop.amount)
            SupplyDropReward.DIAMONDS -> g.copy(diamonds = g.diamonds + drop.amount.roundToLong())
            SupplyDropReward.DOLLARS -> g.copy(dollars = g.dollars + drop.amount)
            SupplyDropReward.SEASON_XP -> MegaUpdateEngine.addSeasonXp(g, drop.amount.roundToLong())
        }
        _uiState.update { it.copy(activeSupplyDrop = null) }
        updateAndPersist(updated)
        audioController.playDiamond()
        showToast("Đã thu thập ${drop.label}!", isError = false)
    }

    fun openDailyRewards() {
        _uiState.update { it.copy(showDailyRewards = true) }
    }

    fun closeDailyRewards() {
        _uiState.update { it.copy(showDailyRewards = false) }
    }

    fun claimDailyReward() {
        val outcome = MegaUpdateEngine.claimDailyReward(_uiState.value.game)
        if (outcome.updatedGame == null) {
            showToast(outcome.message, isError = true)
            return
        }
        updateAndPersist(outcome.updatedGame)
        audioController.playJackpot()
        showToast(outcome.message, isError = false)
    }

    fun openSeasonPass() {
        _uiState.update { it.copy(showSeasonPass = true) }
    }

    fun closeSeasonPass() {
        _uiState.update { it.copy(showSeasonPass = false) }
    }

    fun unlockEliteSeasonPass() {
        val g = _uiState.value.game
        if (g.seasonPassPremium) {
            showToast("Bạn đã sở hữu Cyber Elite Pass rồi!", isError = true)
            return
        }
        val cost = SeasonPassCatalog.eliteUnlockCost(g.hasVipPass)
        if (g.diamonds < cost) {
            showToast("Cần thêm ${cost - g.diamonds}💎 để mở khóa Cyber Elite Pass!", isError = true)
            return
        }
        updateAndPersist(g.copy(diamonds = g.diamonds - cost, seasonPassPremium = true))
        audioController.playJackpot()
        showToast("Đã mở khóa CYBER ELITE PASS! (+50% Season XP & toàn bộ quà VIP)", isError = false)
    }

    fun claimSeasonTier(level: Int, isElite: Boolean) {
        val g = _uiState.value.game
        val tier = SeasonPassCatalog.tiers.firstOrNull { it.level == level } ?: return
        if (g.seasonLevel < level) {
            showToast("Cần đạt Cấp $level Mùa 1 để nhận!", isError = true)
            return
        }
        if (isElite) {
            if (!g.seasonPassPremium) {
                showToast("Hãy mở khóa Cyber Elite Pass trước!", isError = true)
                return
            }
            if (g.isSeasonEliteClaimed(level)) return
            val applied = MegaUpdateEngine.applySeasonReward(g, tier.eliteReward)
            val newIds = if (applied.claimedSeasonPremium.isBlank()) "$level" else "${applied.claimedSeasonPremium},$level"
            updateAndPersist(applied.copy(claimedSeasonPremium = newIds))
            audioController.playAchievement()
            showToast("Nhận quà Elite Cấp $level: ${tier.eliteReward.label}!", isError = false)
        } else {
            if (g.isSeasonFreeClaimed(level)) return
            val applied = MegaUpdateEngine.applySeasonReward(g, tier.freeReward)
            val newIds = if (applied.claimedSeasonFree.isBlank()) "$level" else "${applied.claimedSeasonFree},$level"
            updateAndPersist(applied.copy(claimedSeasonFree = newIds))
            audioController.playAchievement()
            showToast("Nhận quà Miễn Phí Cấp $level: ${tier.freeReward.label}!", isError = false)
        }
    }

    fun claimAllSeasonRewards() {
        var g = _uiState.value.game
        val currentLv = g.seasonLevel
        var claimedCount = 0
        for (tier in SeasonPassCatalog.tiers) {
            if (tier.level <= currentLv) {
                if (!g.isSeasonFreeClaimed(tier.level)) {
                    g = MegaUpdateEngine.applySeasonReward(g, tier.freeReward)
                    val ids = if (g.claimedSeasonFree.isBlank()) "${tier.level}" else "${g.claimedSeasonFree},${tier.level}"
                    g = g.copy(claimedSeasonFree = ids)
                    claimedCount++
                }
                if (g.seasonPassPremium && !g.isSeasonEliteClaimed(tier.level)) {
                    g = MegaUpdateEngine.applySeasonReward(g, tier.eliteReward)
                    val ids = if (g.claimedSeasonPremium.isBlank()) "${tier.level}" else "${g.claimedSeasonPremium},${tier.level}"
                    g = g.copy(claimedSeasonPremium = ids)
                    claimedCount++
                }
            }
        }
        if (claimedCount == 0) {
            showToast("Không có phần thưởng Mùa nào đang chờ nhận!", isError = true)
            return
        }
        updateAndPersist(g)
        audioController.playJackpot()
        showToast("Đã nhận toàn bộ $claimedCount phần thưởng Season Pass!", isError = false)
    }

    fun openCasino() {
        _uiState.update { it.copy(showCasino = true) }
    }

    fun closeCasino() {
        _uiState.update { it.copy(showCasino = false) }
    }

    fun selectCasinoGame(index: Int) {
        _uiState.update { it.copy(casinoSelectedGame = index.coerceIn(0, 3)) }
    }

    fun selectCasinoCurrency(currency: BetCurrency) {
        _uiState.update { it.copy(casinoCurrency = currency, minefield = null) }
    }

    fun selectCasinoBetTier(tier: Int) {
        _uiState.update { it.copy(casinoBetTier = tier.coerceIn(0, 2)) }
    }

    fun spinLuckyWheel() {
        val state = _uiState.value
        val g = MegaUpdateEngine.syncDailyQuestDay(state.game)
        val currency = state.casinoCurrency
        val bet = CasinoCatalog.betAmountsFor(currency, g)[state.casinoBetTier.coerceIn(0, 2)]

        if (!MegaUpdateEngine.canAfford(g, currency, bet)) {
            showToast("Không đủ ${MegaUpdateEngine.formatBet(currency, bet)} để quay!", isError = true)
            return
        }

        val slice = MegaUpdateEngine.pickWeightedWheelSlice(g.relicLuckLv)
        var next = MegaUpdateEngine.deductCurrency(g, currency, bet)
        val payout = bet * slice.multiplier
        val won = slice.multiplier >= 1.0 || slice.bonusShards > 0L || slice.bonusDiamonds > 0L

        if (payout > 0.0) {
            next = MegaUpdateEngine.addCurrency(next, currency, payout)
        }
        if (slice.bonusShards > 0L) {
            next = next.copy(shards = next.shards + slice.bonusShards)
        }
        if (slice.bonusDiamonds > 0L) {
            next = next.copy(diamonds = next.diamonds + slice.bonusDiamonds)
        }

        var jackpotWon = 0.0
        var nextPool = next.gambleJackpotPool + (bet * if (currency == BetCurrency.POINTS) 0.05 else 15.0)
        if (slice.isJackpot) {
            jackpotWon = nextPool * 0.45
            next = next.copy(diem = next.diem + jackpotWon, lifetimePoints = next.lifetimePoints + jackpotWon)
            nextPool = maxOf(2500.0, nextPool - jackpotWon)
        }

        next = MegaUpdateEngine.addSeasonXp(
            next.copy(
                gambleSpins = next.gambleSpins + 1L,
                gambleWins = next.gambleWins + if (won) 1L else 0L,
                gambleBiggestWin = maxOf(next.gambleBiggestWin, payout + jackpotWon),
                gambleJackpotPool = nextPool,
                dailyQuestGambles = next.dailyQuestGambles + 1
            ),
            baseXp = 22L
        )

        val banner = buildString {
            append("Vòng Quay: [${slice.label}] ")
            if (payout > 0.0) append("Nhận ${MegaUpdateEngine.formatBet(currency, payout)} ")
            if (slice.bonusShards > 0L) append("+${slice.bonusShards}✦ ")
            if (slice.bonusDiamonds > 0L) append("+${slice.bonusDiamonds}💎 ")
            if (jackpotWon > 0.0) append("• NỔ HŨ +${Formatters.formatPoints(jackpotWon)}đ!")
            if (payout == 0.0 && slice.bonusShards == 0L && slice.bonusDiamonds == 0L) append("Chúc bạn may mắn lần sau!")
        }

        _uiState.update {
            it.copy(
                wheelSpinId = it.wheelSpinId + 1L,
                wheelTargetSliceIndex = slice.id,
                lastCasinoBanner = banner,
                lastCasinoWon = won
            )
        }
        updateAndPersist(next)
        if (slice.isJackpot) audioController.playJackpot()
        else if (won) audioController.playDiamond()
        else audioController.playSpinTick()
        showToast(banner, isError = !won)
    }

    fun playSlot777() {
        val state = _uiState.value
        val g = MegaUpdateEngine.syncDailyQuestDay(state.game)
        val currency = state.casinoCurrency
        val bet = CasinoCatalog.betAmountsFor(currency, g)[state.casinoBetTier.coerceIn(0, 2)]

        if (!MegaUpdateEngine.canAfford(g, currency, bet)) {
            showToast("Không đủ ${MegaUpdateEngine.formatBet(currency, bet)} để kéo Slot 777!", isError = true)
            return
        }

        val symbols = CasinoCatalog.slotSymbols
        // Relic luck gives subtle extra chance of matching reels
        val r1 = symbols[Random.nextInt(symbols.size)]
        val r2 = if (Random.nextDouble(100.0) < g.relicLuckLv * 2.5) r1 else symbols[Random.nextInt(symbols.size)]
        val r3 = if (Random.nextDouble(100.0) < g.relicLuckLv * 2.0) r2 else symbols[Random.nextInt(symbols.size)]
        val reels = listOf(r1, r2, r3)

        var next = MegaUpdateEngine.deductCurrency(g, currency, bet)
        var nextPool = next.gambleJackpotPool + (bet * if (currency == BetCurrency.POINTS) 0.1 else 25.0)
        var jackpotBonus = 0.0
        var bonusDiamonds = 0L

        val mult = when {
            r1 == r2 && r2 == r3 -> when (r1) {
                "7️⃣" -> {
                    jackpotBonus = nextPool * 0.4
                    nextPool = maxOf(2500.0, nextPool - jackpotBonus)
                    25.0
                }
                "💎" -> {
                    bonusDiamonds = 20L
                    12.0
                }
                "✦" -> 8.0
                else -> 5.0
            }
            r1 == r2 || r2 == r3 || r1 == r3 -> 1.8
            else -> 0.0
        }

        val payout = bet * mult
        val won = mult > 0.0
        if (payout > 0.0) {
            next = MegaUpdateEngine.addCurrency(next, currency, payout)
        }
        if (jackpotBonus > 0.0) {
            next = next.copy(diem = next.diem + jackpotBonus, lifetimePoints = next.lifetimePoints + jackpotBonus)
        }
        if (bonusDiamonds > 0L) {
            next = next.copy(diamonds = next.diamonds + bonusDiamonds)
        }

        next = MegaUpdateEngine.addSeasonXp(
            next.copy(
                gambleSpins = next.gambleSpins + 1L,
                gambleWins = next.gambleWins + if (won) 1L else 0L,
                gambleBiggestWin = maxOf(next.gambleBiggestWin, payout + jackpotBonus),
                gambleJackpotPool = nextPool,
                dailyQuestGambles = next.dailyQuestGambles + 1
            ),
            baseXp = 25L
        )

        val banner = when {
            jackpotBonus > 0.0 -> "777 SIÊU NỔ HŨ! +${MegaUpdateEngine.formatBet(currency, payout)} & +${Formatters.formatPoints(jackpotBonus)}đ!"
            mult >= 5.0 -> "TRÚNG LỚN (${reels.joinToString("")})! +${MegaUpdateEngine.formatBet(currency, payout)}" +
                if (bonusDiamonds > 0L) " & +${bonusDiamonds}💎!" else "!"
            mult > 0.0 -> "Cặp đôi may mắn (${reels.joinToString("")})! +${MegaUpdateEngine.formatBet(currency, payout)}"
            else -> "Trượt rồi (${reels.joinToString("")})! Quỹ Hũ tăng lên ${Formatters.formatPoints(nextPool)}đ"
        }

        _uiState.update {
            it.copy(
                slotReels = reels,
                lastCasinoBanner = banner,
                lastCasinoWon = won
            )
        }
        updateAndPersist(next)
        if (jackpotBonus > 0.0 || mult >= 5.0) audioController.playJackpot()
        else if (won) audioController.playDiamond()
        else audioController.playSpinTick()
        showToast(banner, isError = !won)
    }

    fun playQuantumDice(choice: Int) {
        // choice: 0 = Xỉu (2..6 -> x2.1), 1 = Hòa 7 (7 -> x4.5), 2 = Tài (8..12 -> x2.1)
        val state = _uiState.value
        val g = MegaUpdateEngine.syncDailyQuestDay(state.game)
        val currency = state.casinoCurrency
        val bet = CasinoCatalog.betAmountsFor(currency, g)[state.casinoBetTier.coerceIn(0, 2)]

        if (!MegaUpdateEngine.canAfford(g, currency, bet)) {
            showToast("Không đủ ${MegaUpdateEngine.formatBet(currency, bet)} để tung Xúc Xắc!", isError = true)
            return
        }

        val d1 = Random.nextInt(1, 7)
        val d2 = Random.nextInt(1, 7)
        val sum = d1 + d2
        val mult = when {
            choice == 0 && sum in 2..6 -> 2.1
            choice == 1 && sum == 7 -> 4.5
            choice == 2 && sum in 8..12 -> 2.1
            else -> 0.0
        }

        var next = MegaUpdateEngine.deductCurrency(g, currency, bet)
        val payout = bet * mult
        val won = mult > 0.0
        if (won) {
            next = MegaUpdateEngine.addCurrency(next, currency, payout)
        }
        next = MegaUpdateEngine.addSeasonXp(
            next.copy(
                gambleSpins = next.gambleSpins + 1L,
                gambleWins = next.gambleWins + if (won) 1L else 0L,
                gambleBiggestWin = maxOf(next.gambleBiggestWin, payout),
                dailyQuestGambles = next.dailyQuestGambles + 1
            ),
            baseXp = 20L
        )

        val resultLabel = when {
            sum == 7 -> "HÒA 7"
            sum < 7 -> "XỈU"
            else -> "TÀI"
        }
        val banner = if (won) {
            "Xúc xắc $d1 + $d2 = $sum ($resultLabel)! Thắng +${MegaUpdateEngine.formatBet(currency, payout)}!"
        } else {
            "Xúc xắc $d1 + $d2 = $sum ($resultLabel). Mất cược!"
        }

        _uiState.update {
            it.copy(
                lastDicePair = d1 to d2,
                lastCasinoBanner = banner,
                lastCasinoWon = won
            )
        }
        updateAndPersist(next)
        if (won) audioController.playDiamond() else audioController.playError()
    }

    fun startMinefieldGame() {
        val state = _uiState.value
        val g = MegaUpdateEngine.syncDailyQuestDay(state.game)
        val currency = state.casinoCurrency
        val bet = CasinoCatalog.betAmountsFor(currency, g)[state.casinoBetTier.coerceIn(0, 2)]

        if (!MegaUpdateEngine.canAfford(g, currency, bet)) {
            showToast("Không đủ ${MegaUpdateEngine.formatBet(currency, bet)} để mở Bàn Kho Báu!", isError = true)
            return
        }

        val bombs = (0 until 9).shuffled().take(2).toSet()
        val next = MegaUpdateEngine.deductCurrency(g, currency, bet).copy(
            gambleSpins = g.gambleSpins + 1L,
            dailyQuestGambles = g.dailyQuestGambles + 1
        )
        _uiState.update {
            it.copy(
                minefield = MinefieldState(currency = currency, betAmount = bet, bombIndices = bombs),
                lastCasinoBanner = "Đã đặt ${MegaUpdateEngine.formatBet(currency, bet)}! Chọn ô an toàn để nhân thưởng!",
                lastCasinoWon = false
            )
        }
        updateAndPersist(next)
        audioController.playSpinTick()
    }

    fun revealMinefieldTile(index: Int) {
        val mine = _uiState.value.minefield ?: return
        if (!mine.isActive || index in mine.revealedSafe) return

        if (index in mine.bombIndices) {
            val lostMine = mine.copy(hitBombIndex = index, isActive = false)
            _uiState.update {
                it.copy(
                    minefield = lostMine,
                    lastCasinoBanner = "BÙM! Trúng mìn tại ô #${index + 1}!",
                    lastCasinoWon = false
                )
            }
            audioController.playError()
        } else {
            val newSafe = mine.revealedSafe + index
            val updatedMine = mine.copy(revealedSafe = newSafe)
            if (newSafe.size >= 7) {
                // Auto cashout on full board clear!
                _uiState.update { it.copy(minefield = updatedMine) }
                cashOutMinefield()
            } else {
                _uiState.update {
                    it.copy(
                        minefield = updatedMine,
                        lastCasinoBanner = "Tìm thấy Tinh Thể! Thưởng hiện tại: x${Formatters.formatDecimal(updatedMine.currentMultiplier)}"
                    )
                }
                audioController.playTap(newSafe.size + 2)
            }
        }
    }

    fun cashOutMinefield() {
        val mine = _uiState.value.minefield ?: return
        if (!mine.isActive || mine.revealedSafe.isEmpty()) return
        val payout = mine.currentPayout
        val g = _uiState.value.game
        val next = MegaUpdateEngine.addSeasonXp(
            MegaUpdateEngine.addCurrency(g, mine.currency, payout).copy(
                gambleWins = g.gambleWins + 1L,
                gambleBiggestWin = maxOf(g.gambleBiggestWin, payout)
            ),
            baseXp = 30L
        )
        val banner = "Rút tiền thành công! +${MegaUpdateEngine.formatBet(mine.currency, payout)}!"
        _uiState.update {
            it.copy(
                minefield = mine.copy(isActive = false),
                lastCasinoBanner = banner,
                lastCasinoWon = true
            )
        }
        updateAndPersist(next)
        audioController.playJackpot()
        showToast(banner, isError = false)
    }

    fun openBossAndRelics() {
        _uiState.update { it.copy(showBossAndRelics = true) }
    }

    fun closeBossAndRelics() {
        _uiState.update { it.copy(showBossAndRelics = false) }
    }

    fun attackBoss() {
        val current = _uiState.value
        val g = current.game
        val critStorm = current.critStormRemainingSec > 0L
        val isCrit = critStorm || Random.nextDouble(0.0, 100.0) < g.critChancePercent
        val outcome = MegaUpdateEngine.hitBoss(
            game = g,
            isCrit = isCrit,
            isTapSurge = current.tapSurgeRemainingSec > 0L,
            isOverdrive = current.isOverdriveActive
        )
        val dmgLabel = (if (isCrit) "CRIT -" else "-") + Formatters.formatPoints(outcome.damageDealt) + " HP"
        _uiState.update {
            it.copy(
                bossHitCounter = it.bossHitCounter + 1L,
                lastBossDamageText = dmgLabel
            )
        }
        updateAndPersist(outcome.updatedGame)
        if (outcome.bossDefeated) {
            audioController.playJackpot()
            showToast(outcome.rewardSummary ?: "HẠ GỤC BOSS!", isError = false)
        } else {
            audioController.playBossHit()
        }
    }

    fun upgradeRelic(relic: RelicId) {
        val (nextGame, msg) = MegaUpdateEngine.upgradeRelic(_uiState.value.game, relic)
        if (nextGame == null) {
            showToast(msg, isError = true)
            return
        }
        updateAndPersist(nextGame)
        audioController.playUpgrade()
        showToast(msg, isError = false)
    }

    fun activateBooster(booster: BoosterType) {
        val g = _uiState.value.game
        if (g.diamonds < booster.diamondCost) {
            showToast("Cần ${booster.diamondCost}💎 để kích hoạt ${booster.title}!", isError = true)
            return
        }
        val now = System.currentTimeMillis()
        val addMs = booster.durationSec * 1000L
        val next = when (booster) {
            BoosterType.TAP_SURGE -> g.copy(
                diamonds = g.diamonds - booster.diamondCost,
                boosterTapSurgeEndAt = maxOf(now, g.boosterTapSurgeEndAt) + addMs
            )
            BoosterType.CRIT_STORM -> g.copy(
                diamonds = g.diamonds - booster.diamondCost,
                boosterCritStormEndAt = maxOf(now, g.boosterCritStormEndAt) + addMs
            )
            BoosterType.AUTO_FRENZY -> g.copy(
                diamonds = g.diamonds - booster.diamondCost,
                boosterAutoFrenzyEndAt = maxOf(now, g.boosterAutoFrenzyEndAt) + addMs
            )
        }
        updateAndPersist(next)
        audioController.playOverdrive()
        showToast("${booster.title} đã kích hoạt!", isError = false)
    }

    fun claimDailyQuest(quest: DailyQuestDef) {
        val g = MegaUpdateEngine.syncDailyQuestDay(_uiState.value.game)
        if (g.isDailyQuestClaimed(quest.id)) return
        if (quest.progressSelector(g) < quest.target) {
            showToast("Chưa hoàn thành nhiệm vụ hàng ngày này!", isError = true)
            return
        }
        val newIds = if (g.claimedDailyQuests.isBlank()) quest.id else "${g.claimedDailyQuests},${quest.id}"
        val next = MegaUpdateEngine.addSeasonXp(
            g.copy(
                dollars = g.dollars + quest.rewardDollars,
                shards = g.shards + quest.rewardShards,
                diamonds = g.diamonds + quest.rewardDiamonds,
                claimedDailyQuests = newIds
            ),
            baseXp = quest.rewardXp
        )
        updateAndPersist(next)
        audioController.playAchievement()
        showToast("Nhận NV Hàng Ngày: +${quest.rewardDiamonds}💎, +${quest.rewardShards}✦ & +${quest.rewardXp} XP!", isError = false)
    }

    fun hardResetGame() {
        cooldownJob?.cancel()
        comboTimerJob?.cancel()
        overdriveJob?.cancel()

        val freshState = GameStateEntity(id = 1)
        isDirty = false
        _uiState.update {
            it.copy(
                game = freshState,
                selectedTab = GameTab.TAP,
                isCooldownActive = false,
                cooldownRemainingFraction = 0f,
                comboCount = 0,
                comboMultiplier = 1.0,
                comboRemainingFraction = 0f,
                comboTierLabel = ComboSystem.tierLabelFor(0),
                isOverdriveActive = false,
                overdriveRemainingFraction = 0f,
                floatingPopups = emptyList(),
                showAutoUpgradeSheet = false,
                showRestartDialog = false,
                showSettings = false,
                showPremiumShop = false,
                showLeaderboard = false,
                showDailyRewards = false,
                showSeasonPass = false,
                showCasino = false,
                showBossAndRelics = false,
                goldenFeverRemainingSec = 0L,
                tapSurgeRemainingSec = 0L,
                critStormRemainingSec = 0L,
                autoFrenzyRemainingSec = 0L,
                canClaimDailyToday = true,
                unclaimedSeasonRewardsCount = 0,
                unclaimedDailyQuestCount = 0,
                activeSupplyDrop = null,
                minefield = null,
                lastCasinoBanner = null,
                lastBossDamageText = null,
                lastMysteryCrateReward = null,
                offlineEarnedPoints = 0.0,
                unclaimedAchievementCount = countUnclaimed(freshState)
            )
        }
        viewModelScope.launch {
            repository.saveState(freshState)
        }
        audioController.playRestart()
        showToast("Đã đặt lại toàn bộ dữ liệu trò chơi!", isError = false)
    }

    private fun updateAndPersist(newState: GameStateEntity) {
        val synced = MegaUpdateEngine.syncDailyQuestDay(newState)
        val now = System.currentTimeMillis()
        _uiState.update {
            it.copy(
                game = synced,
                unclaimedAchievementCount = countUnclaimed(synced),
                canClaimDailyToday = DailyRewardCatalog.canClaimToday(synced),
                unclaimedSeasonRewardsCount = MegaUpdateEngine.countUnclaimedSeasonRewards(synced),
                unclaimedDailyQuestCount = MegaUpdateEngine.countUnclaimedDailyQuests(synced),
                goldenFeverRemainingSec = ((synced.premGoldenFeverEndAt - now) / 1000L).coerceAtLeast(0L),
                tapSurgeRemainingSec = ((synced.boosterTapSurgeEndAt - now) / 1000L).coerceAtLeast(0L),
                critStormRemainingSec = ((synced.boosterCritStormEndAt - now) / 1000L).coerceAtLeast(0L),
                autoFrenzyRemainingSec = ((synced.boosterAutoFrenzyEndAt - now) / 1000L).coerceAtLeast(0L)
            )
        }
        isDirty = false
        viewModelScope.launch {
            repository.saveState(synced)
        }
    }

    private fun showToast(message: String, isError: Boolean) {
        if (isError) {
            audioController.playError()
        }
        val toastId = nextToastId++
        _uiState.update {
            it.copy(toastBanner = ToastBanner(id = toastId, message = message, isError = isError))
        }
        toastJob?.cancel()
        toastJob = viewModelScope.launch {
            val durationMs = (_uiState.value.game.toastDurationSec * 1000.0)
                .roundToLong()
                .coerceAtLeast(250L)
            delay(durationMs)
            _uiState.update { state ->
                if (state.toastBanner?.id == toastId) {
                    state.copy(toastBanner = null)
                } else {
                    state
                }
            }
        }
    }

    class Factory(
        private val repository: GameRepository,
        private val settingsRepository: SettingsRepository,
        private val audioController: GameAudioController
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return GameViewModel(repository, settingsRepository, audioController) as T
        }
    }
}
