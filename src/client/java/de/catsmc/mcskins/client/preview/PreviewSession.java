package de.catsmc.mcskins.client.preview;

import com.mojang.blaze3d.platform.NativeImage;
import de.catsmc.mcskins.client.compat.GameCompat;
import de.catsmc.mcskins.skin.SkinDocument;
import java.io.IOException;
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
	private enum Phase { CREATING, JOINING, ACTIVE, STOPPING, ERROR, FINISHED }

	private static boolean initialized;
	private static boolean orphansChecked;
	private static PreviewSession current;
	private final Minecraft client;
	private Screen editor;
	private Consumer<String> report;
	private final PreviewWorld world;
	private final int[] pixels;
	private final boolean slim;
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

	private PreviewSession(Screen editor, SkinDocument document, Consumer<String> report) {
		client = Minecraft.getInstance();
		this.editor = editor;
		this.report = report;
		world = new PreviewWorld(client.getLevelSource());
		pixels = document.flatten();
		slim = document.slim();
		previousCamera = client.options.getCameraType();
		previousHideGui = GameCompat.hudHidden(client);
	}

	public static void initialize() {
		if (!initialized) {
			initialized = true;
			ClientTickEvents.END_CLIENT_TICK.register(client -> {
				if (!orphansChecked) {
					orphansChecked = true;
					PreviewWorld.cleanOrphans(client.getLevelSource());
				}
				if (current != null) {
					current.tick();
				}
			});
		}
	}

	public static boolean canStart() {
		Minecraft client = Minecraft.getInstance();
		return initialized && client.level == null && client.getSingleplayerServer() == null
			&& client.getConnection() == null && !client.isDemo()
			&& (current == null || current.phase == Phase.ERROR);
	}

	public static void start(Screen editor, SkinDocument document, Consumer<String> report) {
		if (current != null && !discardStale(current, editor, report)) {
			return;
		}
		if (!canStart()) {
			report.accept("World preview requires the title menu, a full game, and preview initialization.");
			return;
		}
		PreviewSession session = new PreviewSession(editor, document, report);
		current = session;
		session.create();
	}

	private static boolean discardStale(PreviewSession session, Screen editor, Consumer<String> report) {
		if (session.phase != Phase.ERROR) {
			report.accept("A temporary preview session is already running.");
			return false;
		}
		session.retarget(editor, report);
		if (!canStart()) {
			report.accept("A previous preview still needs recovery first: " + session.problem());
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
			problem = "Preview creation failed: " + message(exception);
		} finally {
			creating = false;
		}
		captureServer();
		if (!problem.isEmpty() || fallbackShown || server == null) {
			if (problem.isEmpty()) {
				problem = "Minecraft could not initialize the temporary world.";
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
					problem = "Server shutdown is not confirmed. Cleanup remains blocked; retry Safe exit.";
					phase = Phase.ERROR;
					showError();
				}
				return;
			}
			if (phase == Phase.JOINING) {
				if (fallbackShown || server == null || client.getSingleplayerServer() != server
					|| server.isShutdown() || !server.getRunningThread().isAlive()) {
					problem = "Temporary world loading was interrupted.";
					exit();
				} else if (client.level != null && client.player != null && GameCompat.currentScreen(client) == null
					&& client.level.hasChunk(client.player.blockPosition().getX() >> 4, client.player.blockPosition().getZ() >> 4)) {
					activate();
				} else if (System.nanoTime() - joinStarted > 120_000_000_000L) {
					problem = "Joining the preview timed out. Use Safe exit to stop it before cleanup.";
					phase = Phase.ERROR;
					showError();
				}
			} else if (phase == Phase.ACTIVE) {
				if (client.level != level || client.player != player || client.getSingleplayerServer() != server
					|| server.isShutdown() || server.isPublished()) {
					problem = "Temporary preview was interrupted.";
					exit();
					return;
				}
				ticks++;
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
			problem = "Preview failed: " + message(exception);
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
			problem = "Preview world calm-down skipped: no local server belongs to this session.";
			return;
		}
		try {
			running.execute(() -> calmRules(running));
		} catch (RuntimeException exception) {
			problem = "Preview world calm-down incomplete: " + message(exception);
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
			problem = "Preview world calm-down incomplete: " + message(exception);
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
					abandon("Preview session closed without cleanup because another world is active.");
					return;
				}
				if (client.level != null) {
					client.level.disconnect(Component.literal("Returning to the skin editor"));
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
			report.accept(problem.isEmpty() ? "Temporary world deleted. Unsaved edits preserved; account unchanged."
				: problem + " Temporary world cleanup completed; edits preserved.");
			current = null;
		} catch (IOException | ContentValidationException | RuntimeException exception) {
			problem = "Cleanup not completed: " + message(exception);
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
			message += " Leftover preview folder " + world.folder() + ": " + message(failure);
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
			report.accept("Preview cleanup pending: " + world.folder() + ". Test in World reopens recovery.");
			GameCompat.setScreen(client, editor);
		}
	}

	private void showError() {
		GameCompat.setScreen(client, new PreviewFailureScreen(this, false));
	}

	String problem() {
		return problem.isEmpty() ? "Minecraft could not initialize the temporary world." : problem;
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
				state.chestEquipment = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ELYTRA);
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

	void animation(Animation animation) {
		this.animation = animation;
	}
}
