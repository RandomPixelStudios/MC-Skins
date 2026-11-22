package de.catsmc.mcskins.client.preview;

import com.mojang.blaze3d.platform.NativeImage;
import de.catsmc.mcskins.client.McSkinsClient;
import de.catsmc.mcskins.client.Text;
import de.catsmc.mcskins.client.compat.GameCompat;
import de.catsmc.mcskins.skin.SkinDocument;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.ClientAsset;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.validation.ContentValidationException;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class PreviewSession {
	public enum Animation { IDLE, WALK, RUN, SWIM, SNEAK, ELYTRA, SWING, CRAWL }
	public enum Environment { DAY, NIGHT, NETHER, CAVE }
	/** Armor materials in cycle order; index 0 of every slot list is "none". */
	static final String[] MATERIALS = {"none", "leather", "chainmail", "iron", "golden", "diamond", "netherite", "copper"};
	static final String[] HAND_ITEMS = {"none", "diamond_sword", "iron_sword", "diamond_pickaxe", "bow", "crossbow", "trident",
		"shield", "torch", "fishing_rod", "totem_of_undying"};
	/** Seconds of recording at {@link #RECORD_FPS} frames per second. */
	static final int RECORD_SECONDS = 4;
	static final int RECORD_FPS = 10;
	private static final int CHAMBER_RADIUS = 6;
	private static final int CHAMBER_HEIGHT = 6;
	private enum Phase { CREATING, JOINING, ACTIVE, STOPPING, ERROR, FINISHED }

	private static boolean initialized;
	private static boolean orphansChecked;
	/** Left-over preview worlds from a crash are deleted on the I/O thread; previews wait until that is done. */
	private static CompletableFuture<Void> orphanCleanup = CompletableFuture.completedFuture(null);
	private static net.minecraft.world.item.ItemStack elytra;
	private static PreviewSession current;
	private final Minecraft client;
	private Screen editor;
	private Consumer<String> report;
	private final PreviewWorld world;
	private final int[] pixels;
	private final boolean slim;
	private final String skinName;
	private final CameraType previousCamera;
	private final boolean previousHideGui;
	private Phase phase = Phase.CREATING;
	private IntegratedServer server;
	private ClientLevel level;
	private LocalPlayer player;
	private Identifier textureId;
	private ClientAsset.Texture body;
	private PreviewOverlayScreen overlay;
	private boolean creating;
	private boolean fallbackShown;
	private boolean perspectiveChanged;
	private boolean exiting;
	private volatile String problem = "";
	private long joinStarted;
	private long shutdownWaitStarted;
	private long ticks;
	private Animation animation = Animation.IDLE;
	private boolean playing;
	private float yaw = 180;
	private float pitch = 12;
	private double distance = 4;
	private double panX;
	private double panY;
	/** Armor per slot (head, chest, legs, feet) as an index into {@link #MATERIALS}; chest one past the end is an elytra. */
	private final int[] armor = new int[4];
	private int mainHand;
	private int offHand;
	private Environment environment = Environment.DAY;
	private final java.util.Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState> chamber = new java.util.HashMap<>();
	private boolean recording;
	private int recordTicks;
	private int recordWidth;
	private int recordHeight;
	private final java.util.List<int[]> frames = new java.util.ArrayList<>();
	private String recordStatus = "";

	private PreviewSession(Screen editor, SkinDocument document, Consumer<String> report) {
		client = Minecraft.getInstance();
		this.editor = editor;
		this.report = report;
		world = new PreviewWorld(client.getLevelSource());
		pixels = document.flatten();
		slim = document.slim();
		skinName = document.name();
		previousCamera = client.options.getCameraType();
		previousHideGui = GameCompat.hudHidden(client);
	}

	public static void initialize() {
		if (!initialized) {
			initialized = true;
			ClientTickEvents.END_CLIENT_TICK.register(client -> {
				if (!orphansChecked) {
					orphansChecked = true;
					var source = client.getLevelSource();
					orphanCleanup = CompletableFuture.runAsync(() -> PreviewWorld.cleanOrphans(source), McSkinsClient.io());
				}
				if (current != null) {
					current.tick();
				}
			});
		}
	}

	public static boolean canStart() {
		Minecraft client = Minecraft.getInstance();
		return initialized && orphanCleanup.isDone() && client.level == null && client.getSingleplayerServer() == null
			&& client.getConnection() == null && !client.isDemo()
			&& (current == null || current.phase == Phase.ERROR);
	}

	public static void start(Screen editor, SkinDocument document, Consumer<String> report) {
		if (current != null && !discardStale(current, editor, report)) {
			return;
		}
		if (!canStart()) {
			report.accept(Text.tr("mcskins.preview.msg.requirements"));
			return;
		}
		PreviewSession session = new PreviewSession(editor, document, report);
		current = session;
		session.create();
	}

	private static boolean discardStale(PreviewSession session, Screen editor, Consumer<String> report) {
		if (session.phase != Phase.ERROR) {
			report.accept(Text.tr("mcskins.preview.msg.running"));
			return false;
		}
		session.retarget(editor, report);
		if (!canStart()) {
			report.accept(Text.tr("mcskins.preview.msg.needs_recovery", session.problem()));
			return false;
		}
		if (session.release()) {
			return true;
		}
		session.showError();
		return false;
	}

	private void retarget(Screen editor, Consumer<String> report) {
		this.editor = editor;
		this.report = report;
	}

	private boolean release() {
		try {
			disableRendering();
		} catch (RuntimeException failure) {
			return false;
		}
		phase = Phase.FINISHED;
		if (current == this) {
			current = null;
		}
		return true;
	}

	private void create() {
		creating = true;
		try {
			world.reserve();
			LevelSettings settings = new LevelSettings("MC Skins temporary preview", GameType.CREATIVE,
				new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, true), false, WorldDataConfiguration.DEFAULT);
			client.createWorldOpenFlows().createFreshLevel(world.folder(), settings, new WorldOptions(0L, false, false),
				provider -> provider.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT)
					.value().createWorldDimensions(), new PreviewFailureScreen(this, true));
			phase = Phase.JOINING;
			joinStarted = System.nanoTime();
		} catch (IOException | RuntimeException exception) {
			problem = Text.tr("mcskins.preview.msg.creation_failed", message(exception));
		} finally {
			creating = false;
		}
		captureServer();
		if (!problem.isEmpty() || fallbackShown || server == null) {
			if (problem.isEmpty()) {
				problem = Text.tr("mcskins.preview.msg.init_failed");
			}
			if (client.level == null && client.getSingleplayerServer() == null) {
				exit();
			} else {
				phase = Phase.ERROR;
				showError();
			}
		}
	}

	private boolean owns(IntegratedServer candidate) {
		return candidate != null && candidate.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().equals(world.path());
	}

	private void captureServer() {
		IntegratedServer candidate = client.getSingleplayerServer();
		if (server == null && owns(candidate)) {
			server = candidate;
		}
	}

	private void tick() {
		if (creating || exiting || phase == Phase.ERROR || phase == Phase.FINISHED) {
			return;
		}
		try {
			captureServer();
			if (phase == Phase.STOPPING) {
				if (stopped()) {
					exit();
				} else if (System.nanoTime() - shutdownWaitStarted > 30_000_000_000L) {
					problem = Text.tr("mcskins.preview.msg.shutdown_unconfirmed");
					phase = Phase.ERROR;
					showError();
				}
				return;
			}
			if (phase == Phase.JOINING) {
				if (fallbackShown || server == null || client.getSingleplayerServer() != server
					|| server.isShutdown() || !server.getRunningThread().isAlive()) {
					problem = Text.tr("mcskins.preview.msg.loading_interrupted");
					exit();
				} else if (client.level != null && client.player != null && GameCompat.currentScreen(client) == null
					&& client.level.hasChunk(client.player.blockPosition().getX() >> 4, client.player.blockPosition().getZ() >> 4)) {
					activate();
				} else if (System.nanoTime() - joinStarted > 120_000_000_000L) {
					problem = Text.tr("mcskins.preview.msg.join_timeout");
					phase = Phase.ERROR;
					showError();
				}
			} else if (phase == Phase.ACTIVE) {
				if (client.level != level || client.player != player || client.getSingleplayerServer() != server
					|| server.isShutdown() || server.isPublished()) {
					problem = Text.tr("mcskins.preview.msg.interrupted");
					exit();
					return;
				}
				ticks++;
				if (recording) {
					captureFrame();
				}
				if (!playing) {
					hoverElytra();
					client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
					setHudHidden(true);
					if (GameCompat.currentScreen(client) != overlay) {
						GameCompat.setScreen(client, overlay);
					}
				}
			}
		} catch (RuntimeException exception) {
			problem = Text.tr("mcskins.preview.msg.failed", message(exception));
			exit();
		}
	}

	private void activate() {
		if (!owns(server) || server.isPublished()) {
			throw new IllegalStateException("The preview is not an isolated local world.");
		}
		level = client.level;
		player = client.player;
		calmWorld();
		NativeImage image = new NativeImage(64, 64, false);
		DynamicTexture texture = null;
		boolean registered = false;
		try {
			for (int y = 0; y < 64; y++) {
				for (int x = 0; x < 64; x++) {
					image.setPixel(x, y, pixels[y * 64 + x]);
				}
			}
			texture = new DynamicTexture(() -> "MC Skins temporary preview", image);
			textureId = Identifier.fromNamespaceAndPath("mcskins", "preview/" + world.folder());
			client.getTextureManager().register(textureId, texture);
			registered = true;
			body = new ClientAsset.ResourceTexture(textureId, textureId);
		} finally {
			if (!registered) {
				if (texture != null) {
					texture.close();
				} else {
					image.close();
				}
			}
		}
		perspectiveChanged = true;
		client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
		setHudHidden(true);
		yaw = player.getYRot() + 180;
		overlay = new PreviewOverlayScreen(this);
		phase = Phase.ACTIVE;
		GameCompat.setScreen(client, overlay);
	}

	void fallbackShown() {
		fallbackShown = true;
	}

	private void calmWorld() {
		IntegratedServer running = server;
		if (running == null) {
			problem = Text.tr("mcskins.preview.msg.calm_skipped");
			return;
		}
		try {
			running.execute(() -> calmRules(running));
		} catch (RuntimeException exception) {
			problem = Text.tr("mcskins.preview.msg.calm_incomplete", message(exception));
		}
	}

	private void calmRules(IntegratedServer running) {
		try {
			var rules = running.overworld().getGameRules();
			rules.set(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS, false, running);
			rules.set(net.minecraft.world.level.gamerules.GameRules.SPAWN_MONSTERS, false, running);
			rules.set(net.minecraft.world.level.gamerules.GameRules.SPAWN_PATROLS, false, running);
			rules.set(net.minecraft.world.level.gamerules.GameRules.SPAWN_PHANTOMS, false, running);
			rules.set(net.minecraft.world.level.gamerules.GameRules.SPAWN_WANDERING_TRADERS, false, running);
			rules.set(net.minecraft.world.level.gamerules.GameRules.SPAWN_WARDENS, false, running);
			rules.set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME, false, running);
			rules.set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_WEATHER, false, running);
			running.overworld().dimensionTypeRegistration().value().defaultClock().ifPresent(clock -> {
				running.clockManager().setTotalTicks(clock, 6000);
				running.clockManager().setPaused(clock, true);
			});
		} catch (RuntimeException exception) {
			problem = Text.tr("mcskins.preview.msg.calm_incomplete", message(exception));
		}
	}

	void exit() {
		if (creating || exiting || phase == Phase.FINISHED || current != this) {
			return;
		}
		exiting = true;
		playing = false;
		phase = Phase.STOPPING;
		try {
			disableRendering();
			captureServer();
			if (client.getSingleplayerServer() != null || client.level != null || client.getConnection() != null) {
				if (server == null || client.getSingleplayerServer() != server || !owns(server)
					|| level != null && client.level != null && client.level != level) {
					abandon(Text.tr("mcskins.preview.msg.abandoned"));
					return;
				}
				if (client.level != null) {
					client.level.disconnect(Component.translatable("mcskins.preview.msg.disconnect"));
				}
				client.disconnectWithSavingScreen();
			}
			if (!stopped()) {
				shutdownWaitStarted = System.nanoTime();
				return;
			}
			world.delete();
			client.options.setCameraType(previousCamera);
			setHudHidden(previousHideGui);
			phase = Phase.FINISHED;
			GameCompat.setScreen(client, editor);
			report.accept(problem.isEmpty() ? Text.tr("mcskins.preview.msg.deleted")
				: problem + " " + Text.tr("mcskins.preview.msg.cleanup_done"));
			current = null;
		} catch (IOException | ContentValidationException | RuntimeException exception) {
			problem = Text.tr("mcskins.preview.msg.cleanup_failed", message(exception));
			phase = Phase.ERROR;
			showError();
		} finally {
			exiting = false;
		}
	}

	private void abandon(String message) {
		try {
			world.delete();
		} catch (IOException | ContentValidationException | RuntimeException failure) {
			message += " " + Text.tr("mcskins.preview.msg.leftover", world.folder(), message(failure));
		}
		phase = Phase.FINISHED;
		Screen visible = GameCompat.currentScreen(client);
		if (visible instanceof PreviewFailureScreen || visible instanceof PreviewOverlayScreen) {
			GameCompat.setScreen(client, editor);
		}
		report.accept(message);
		current = null;
	}

	private void setHudHidden(boolean hidden) {
		GameCompat.setHudHidden(client, hidden);
	}

	private void disableRendering() {
		body = null;
		if (perspectiveChanged) {
			client.options.setCameraType(previousCamera);
			setHudHidden(previousHideGui);
			perspectiveChanged = false;
		}
		if (textureId != null) {
			client.getTextureManager().release(textureId);
			textureId = null;
		}
	}

	boolean stopped() {
		return !creating && client.level == null && client.getConnection() == null && client.getSingleplayerServer() == null
			&& (server == null || server.isShutdown() && !server.getRunningThread().isAlive());
	}

	void backWithoutCleanup() {
		if (stopped()) {
			report.accept(Text.tr("mcskins.preview.msg.cleanup_pending", world.folder()));
			GameCompat.setScreen(client, editor);
		}
	}

	private void showError() {
		GameCompat.setScreen(client, new PreviewFailureScreen(this, false));
	}

	String problem() {
		return problem.isEmpty() ? Text.tr("mcskins.preview.msg.init_failed") : problem;
	}

	String folder() {
		return world.folder();
	}

	private static String message(Exception exception) {
		String message = exception.getMessage();
		return message == null ? exception.getClass().getSimpleName() : message.replace('\n', ' ').replace('\r', ' ');
	}

	public static boolean isPreviewPlayer(Object entity) {
		PreviewSession session = current;
		return session != null && session.phase == Phase.ACTIVE && session.body != null
			&& entity == session.player && entity == session.client.player && session.client.level == session.level
			&& session.client.getSingleplayerServer() == session.server && !session.server.isPublished();
	}

	public static PlayerSkin skin(Object entity, PlayerSkin original) {
		if (!isPreviewPlayer(entity)) {
			return original;
		}
		return new PlayerSkin(current.body, original.cape(), original.elytra(),
			current.slim ? PlayerModelType.SLIM : PlayerModelType.WIDE, false);
	}

	public static void animate(Object entity, AvatarRenderState state, float partialTick) {
		if (isPreviewPlayer(entity) && !current.playing) {
			float rate = switch (current.animation) {
				case IDLE -> 0.7F;
				case WALK -> 0.7F;
				case RUN -> 1.3F;
				case SWIM -> 0.9F;
				case SNEAK -> 0.45F;
				case ELYTRA -> 0.7F;
				case SWING -> 0.7F;
				case CRAWL -> 0.5F;
			};
			state.walkAnimationPos = (current.ticks + partialTick) * rate;
			state.walkAnimationSpeed = switch (current.animation) {
				case IDLE -> 0;
				case WALK -> 0.65F;
				case RUN -> 1.0F;
				case SWIM -> 0.9F;
				case SNEAK -> 0.65F;
				case ELYTRA -> 0;
				case SWING -> 0;
				case CRAWL -> 0.4F;
			};
			state.isVisuallySwimming = current.animation == Animation.SWIM;
			state.swimAmount = current.animation == Animation.SWIM || current.animation == Animation.CRAWL ? 1.0F : 0.0F;
			state.isCrouching = current.animation == Animation.SNEAK;
			if (current.animation == Animation.SNEAK) {
				state.pose = net.minecraft.world.entity.Pose.CROUCHING;
			}
			state.isFallFlying = current.animation == Animation.ELYTRA;
			if (current.animation == Animation.ELYTRA) {
				if (elytra == null) {
					elytra = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ELYTRA);
				}
				state.chestEquipment = elytra;
			}
			if (current.animation == Animation.SWING) {
				GameCompat.applySwing(state, (current.ticks + partialTick) % 12 / 12.0F);
			}
			if (current.animation == Animation.CRAWL) {
				state.pose = net.minecraft.world.entity.Pose.SWIMMING;
			}
		}
	}

	public record CameraPose(float yaw, float pitch, double x, double y, double z) {
	}

	public static CameraPose camera(Entity entity, float partialTick) {
		if (current == null || current.playing || !isPreviewPlayer(entity)) {
			return null;
		}
		return current.orbitPose(entity, partialTick);
	}

	private CameraPose orbitPose(Entity entity, float partialTick) {
		Vec3 target = entity.getPosition(partialTick);
		double yaw = Math.toRadians(this.yaw);
		double pitch = Math.toRadians(this.pitch);
		double horizontal = distance * Math.cos(pitch);
		Vec3 pivot = new Vec3(target.x + panX * Math.cos(yaw), target.y + 1.0 + panY,
			target.z + panX * Math.sin(yaw));
		Vec3 wanted = new Vec3(pivot.x + horizontal * Math.sin(yaw), pivot.y + distance * Math.sin(pitch),
			pivot.z - horizontal * Math.cos(yaw));
		Vec3 eye = clampToBlocks(entity, pivot, wanted);
		return new CameraPose(this.yaw, this.pitch, eye.x, eye.y, eye.z);
	}

	private Vec3 clampToBlocks(Entity entity, Vec3 pivot, Vec3 wanted) {
		if (level == null) {
			return wanted;
		}
		try {
			var hit = level.clip(new ClipContext(pivot, wanted, ClipContext.Block.COLLIDER,
				ClipContext.Fluid.NONE, entity));
			if (hit.getType() == HitResult.Type.MISS) {
				return wanted;
			}
			Vec3 blocked = hit.getLocation();
			Vec3 direction = wanted.subtract(pivot);
			double length = direction.length();
			if (length < 1.0E-4) {
				return blocked;
			}
			Vec3 safe = blocked.subtract(direction.scale(0.15 / length));
			return safe.distanceToSqr(pivot) > blocked.distanceToSqr(pivot) ? blocked : safe;
		} catch (RuntimeException exception) {
			return wanted;
		}
	}

	public static boolean isActive() {
		PreviewSession session = current;
		return session != null && session.phase != Phase.ERROR;
	}

	public static void exitToEditor() {
		if (current != null) {
			current.exit();
		}
	}

	void play() {
		if (phase != Phase.ACTIVE || playing) {
			return;
		}
		playing = true;
		setHudHidden(false);
		GameCompat.setScreen(client, null);
	}

	private void hoverElytra() {
		if (animation != Animation.ELYTRA || player == null || level == null) {
			return;
		}
		int ground = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, player.blockPosition());
		double target = ground + 1.2 + Math.sin(ticks * 0.08) * 0.15;
		player.setPos(player.getX(), target, player.getZ());
		player.setDeltaMovement(Vec3.ZERO);
		player.fallDistance = 0;
	}

	void orbit(double dx, double dy) {
		yaw = (float) Math.IEEEremainder(yaw + dx * 0.5, 360);
		pitch = (float) Math.clamp(pitch + dy * 0.5, -15, 75);
	}

	void pan(double dx, double dy) {
		panX = Math.clamp(panX + dx * distance * 0.002, -2, 2);
		panY = Math.clamp(panY - dy * distance * 0.002, -0.5, 2);
	}

	void zoom(double amount) {
		distance = Math.clamp(distance * Math.exp(-Math.clamp(amount, -10, 10) * 0.12), 1.5, 12);
	}

	void resetCamera() {
		yaw = player == null ? 180 : player.getYRot() + 180;
		pitch = 12;
		distance = 4;
		panX = 0;
		panY = 0;
	}

	Animation animation() {
		return animation;
	}

	// ---- equipment, environment and recording ---------------------------------------------------------------

	int armor(int slot) {
		return armor[slot];
	}

	/** Cycles the armor of a slot (0 head, 1 chest, 2 legs, 3 feet); the chest also offers an elytra. */
	void cycleArmor(int slot) {
		int options = MATERIALS.length + (slot == 1 ? 1 : 0);
		armor[slot] = (armor[slot] + 1) % options;
		applyEquipment();
	}

	int mainHand() {
		return mainHand;
	}

	int offHand() {
		return offHand;
	}

	void cycleHand(boolean main) {
		if (main) {
			mainHand = (mainHand + 1) % HAND_ITEMS.length;
		} else {
			offHand = (offHand + 1) % HAND_ITEMS.length;
		}
		applyEquipment();
	}

	Environment environment() {
		return environment;
	}

	void cycleEnvironment() {
		environment = Environment.values()[(environment.ordinal() + 1) % Environment.values().length];
		applyEnvironment();
	}

	private static net.minecraft.world.item.ItemStack stack(String id) {
		if (id.equals("none")) {
			return net.minecraft.world.item.ItemStack.EMPTY;
		}
		var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(id));
		return item == null ? net.minecraft.world.item.ItemStack.EMPTY : new net.minecraft.world.item.ItemStack(item);
	}

	private static String armorItem(int slot, int material) {
		if (material == 0) {
			return "none";
		}
		if (slot == 1 && material == MATERIALS.length) {
			return "elytra";
		}
		String piece = switch (slot) {
			case 0 -> "helmet";
			case 1 -> "chestplate";
			case 2 -> "leggings";
			default -> "boots";
		};
		return MATERIALS[material] + "_" + piece;
	}

	/** Equips the preview player on the server thread, so the game itself renders armor, elytra and held items. */
	private void applyEquipment() {
		IntegratedServer running = server;
		if (running == null || player == null || phase != Phase.ACTIVE) {
			return;
		}
		java.util.UUID id = player.getUUID();
		String[] items = {armorItem(0, armor[0]), armorItem(1, armor[1]), armorItem(2, armor[2]), armorItem(3, armor[3]),
			HAND_ITEMS[mainHand], HAND_ITEMS[offHand]};
		net.minecraft.world.entity.EquipmentSlot[] slots = {net.minecraft.world.entity.EquipmentSlot.HEAD,
			net.minecraft.world.entity.EquipmentSlot.CHEST, net.minecraft.world.entity.EquipmentSlot.LEGS,
			net.minecraft.world.entity.EquipmentSlot.FEET, net.minecraft.world.entity.EquipmentSlot.MAINHAND,
			net.minecraft.world.entity.EquipmentSlot.OFFHAND};
		try {
			running.execute(() -> {
				var serverPlayer = running.getPlayerList().getPlayer(id);
				if (serverPlayer != null) {
					for (int index = 0; index < slots.length; index++) {
						serverPlayer.setItemSlot(slots[index], stack(items[index]));
					}
				}
			});
		} catch (RuntimeException exception) {
			problem = Text.tr("mcskins.preview.msg.equipment_failed", message(exception));
		}
	}

	/**
	 * Day and night set the (paused) clock. Nether and cave build a closed chamber of netherrack or stone around the
	 * player, lit by glowstone and magma or by a single torch; the blocks it replaced are put back when switching away.
	 * A real dimension change is avoided because it would replace the preview player and end the isolated session.
	 */
	private void applyEnvironment() {
		IntegratedServer running = server;
		if (running == null || player == null || phase != Phase.ACTIVE) {
			return;
		}
		java.util.UUID id = player.getUUID();
		Environment target = environment;
		try {
			running.execute(() -> buildEnvironment(running, id, target));
		} catch (RuntimeException exception) {
			problem = Text.tr("mcskins.preview.msg.environment_failed", message(exception));
		}
	}

	private void buildEnvironment(IntegratedServer running, java.util.UUID id, Environment target) {
		try {
			var level = running.overworld();
			for (var entry : chamber.entrySet()) {
				level.setBlock(entry.getKey(), entry.getValue(), 2);
			}
			chamber.clear();
			long time = target == Environment.DAY ? 6000 : 18000;
			level.dimensionTypeRegistration().value().defaultClock().ifPresent(clock -> {
				running.clockManager().setTotalTicks(clock, time);
				running.clockManager().setPaused(clock, true);
			});
			var serverPlayer = running.getPlayerList().getPlayer(id);
			if (serverPlayer == null || target == Environment.DAY || target == Environment.NIGHT) {
				return;
			}
			var feet = serverPlayer.blockPosition();
			boolean nether = target == Environment.NETHER;
			var rock = (nether ? net.minecraft.world.level.block.Blocks.NETHERRACK : net.minecraft.world.level.block.Blocks.STONE).defaultBlockState();
			var accent = (nether ? net.minecraft.world.level.block.Blocks.MAGMA_BLOCK : net.minecraft.world.level.block.Blocks.DEEPSLATE).defaultBlockState();
			var light = (nether ? net.minecraft.world.level.block.Blocks.GLOWSTONE : net.minecraft.world.level.block.Blocks.TUFF).defaultBlockState();
			var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
			for (int dy = -1; dy <= CHAMBER_HEIGHT; dy++) {
				for (int dx = -CHAMBER_RADIUS; dx <= CHAMBER_RADIUS; dx++) {
					for (int dz = -CHAMBER_RADIUS; dz <= CHAMBER_RADIUS; dz++) {
						var position = feet.offset(dx, dy, dz);
						boolean shell = dy == -1 || dy == CHAMBER_HEIGHT || Math.abs(dx) == CHAMBER_RADIUS || Math.abs(dz) == CHAMBER_RADIUS;
						var state = !shell ? air
							: dy == CHAMBER_HEIGHT && Math.floorMod(dx, 4) == 2 && Math.floorMod(dz, 4) == 2 ? light
							: Math.floorMod(dx * 7 + dz * 13 + dy * 5, 9) == 0 ? accent : rock;
						var old = level.getBlockState(position);
						if (old != state) {
							chamber.putIfAbsent(position.immutable(), old);
							level.setBlock(position, state, 2);
						}
					}
				}
			}
			if (!nether) {
				var torch = feet.offset(CHAMBER_RADIUS - 2, 0, CHAMBER_RADIUS - 2);
				chamber.putIfAbsent(torch.immutable(), level.getBlockState(torch));
				level.setBlock(torch, net.minecraft.world.level.block.Blocks.TORCH.defaultBlockState(), 2);
			}
		} catch (RuntimeException exception) {
			problem = Text.tr("mcskins.preview.msg.environment_failed", message(exception));
		}
	}

	boolean recording() {
		return recording;
	}

	String recordStatus() {
		return recordStatus;
	}

	/** Records {@link #RECORD_SECONDS} seconds of the view (UI hidden) and saves them as a GIF in config/mcskins/recordings/. */
	void startRecording() {
		if (recording || phase != Phase.ACTIVE) {
			return;
		}
		frames.clear();
		recordTicks = 0;
		recordWidth = 0;
		recordHeight = 0;
		recording = true;
		recordStatus = Text.tr("mcskins.preview.record.running");
	}

	private void captureFrame() {
		int interval = 20 / RECORD_FPS;
		if (recordTicks++ % interval != 0) {
			return;
		}
		if (frames.size() >= RECORD_SECONDS * RECORD_FPS) {
			finishRecording();
			return;
		}
		try {
			net.minecraft.client.Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), 1, image -> {
				try (image) {
					addFrame(image);
				} catch (RuntimeException exception) {
					recordStatus = Text.tr("mcskins.preview.record.failed", message(exception));
				}
			});
		} catch (RuntimeException exception) {
			recording = false;
			recordStatus = Text.tr("mcskins.preview.record.failed", message(exception));
		}
	}

	private void addFrame(NativeImage image) {
		if (!recording) {
			return;
		}
		int width = image.getWidth();
		int height = image.getHeight();
		int factor = Math.max(1, (int) Math.ceil(Math.max(width, height) / 480.0));
		int frameWidth = Math.max(1, width / factor);
		int frameHeight = Math.max(1, height / factor);
		if (recordWidth == 0) {
			recordWidth = frameWidth;
			recordHeight = frameHeight;
		} else if (frameWidth != recordWidth || frameHeight != recordHeight) {
			return; // the window was resized while recording
		}
		int[] pixels = new int[frameWidth * frameHeight];
		for (int y = 0; y < frameHeight; y++) {
			for (int x = 0; x < frameWidth; x++) {
				pixels[y * frameWidth + x] = image.getPixel(x * factor, y * factor) | 0xFF000000;
			}
		}
		frames.add(pixels);
	}

	private void finishRecording() {
		recording = false;
		if (frames.isEmpty()) {
			recordStatus = Text.tr("mcskins.preview.record.failed", "no frames");
			return;
		}
		var captured = java.util.List.copyOf(frames);
		frames.clear();
		int width = recordWidth;
		int height = recordHeight;
		String name = skinName;
		recordStatus = Text.tr("mcskins.preview.record.saving");
		CompletableFuture.supplyAsync(() -> {
			try {
				byte[] gif = de.catsmc.mcskins.skin.GifEncoder.encode(captured, width, height, 1000 / RECORD_FPS);
				return McSkinsClient.repository().writeOutput("recordings", name, "gif", gif);
			} catch (IOException exception) {
				throw new java.util.concurrent.CompletionException(exception);
			}
		}, McSkinsClient.io()).whenComplete((path, error) -> client.execute(() -> recordStatus = error == null
			? Text.tr("mcskins.preview.record.saved", path.getFileName())
			: Text.tr("mcskins.preview.record.failed", message(error.getCause() instanceof Exception cause ? cause : new IOException(error)))));
	}

	void animation(Animation animation) {
		this.animation = animation;
	}
}
