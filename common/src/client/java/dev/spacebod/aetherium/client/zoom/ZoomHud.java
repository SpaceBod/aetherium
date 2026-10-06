package dev.spacebod.aetherium.client.zoom;

import com.google.gson.JsonPrimitive;
import dev.spacebod.aetherium.shaders.config.ShaderPackSettings;
import dev.spacebod.aetherium.shaders.gui.Theme;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * While zoomed, two small glass panels in a screen corner (chosen in the settings) about what the crosshair is on: a mob
 * when one is in front of the terrain (dropped items and other entities are looked through), else the block. The outer
 * one has the block coordinates over how far away it is and the compass direction faced, in small type; the one beside
 * it, towards the middle and as tall, a 3D preview (the block's pick-block item, or the mob itself, live and fitted) and
 * the name. Either can be turned off. With nothing in range only the distance row shows. They fade in and out with the
 * zoom.
 */
public final class ZoomHud {
	/** The corner the panels sit in; {@code id} is how the config names it. */
	public enum Corner {
		TOP_RIGHT("top_right", "Top right"),
		TOP_LEFT("top_left", "Top left"),
		BOTTOM_RIGHT("bottom_right", "Bottom right"),
		BOTTOM_LEFT("bottom_left", "Bottom left");

		public final String id;
		public final String label;

		Corner(String id, String label) {
			this.id = id;
			this.label = label;
		}

		boolean right() {
			return this == TOP_RIGHT || this == BOTTOM_RIGHT;
		}

		boolean bottom() {
			return this == BOTTOM_RIGHT || this == BOTTOM_LEFT;
		}

		public static Corner of(String id) {
			for (Corner c : values()) {
				if (c.id.equals(id)) {
					return c;
				}
			}
			return TOP_RIGHT;
		}

		public static List<String> ids() {
			return java.util.Arrays.stream(values()).map(c -> c.id).toList();
		}
	}

	private static final int PAD = 4;
	/** Gap between the two panels, and between them and the screen edges. */
	private static final int GAP = 2;
	/** The eight directions, from the way the camera faces: south at 0 degrees of yaw, west at 90. */
	private static final String[] DIRECTIONS = {"South", "Southwest", "West", "Northwest", "North", "Northeast", "East", "Southeast"};
	/** Type sizes: the coordinates and the name, then the distance row. */
	private static final float LARGE = 0.75f;
	private static final float SMALL = 0.5f;
	/** Height of a capital letter at full size (the font's lines also hold room for descenders below it). */
	private static final int CAP = 7;
	/** Between the bottom of the coordinates and the top of the distance row. */
	private static final float ROW_GAP = 2.5f;
	/** Both panels' height. */
	private static final int HEIGHT = 18;
	/** The preview tile, an item model scaled down from its own 16 pixels. */
	private static final int TILE = 14;
	private static final long FADE_MS = 160;

	private static boolean visible;
	private static long since;
	private static @Nullable Corner corner;
	private static @Nullable Boolean showCoordinates;
	private static @Nullable Boolean showTarget;

	/** What the crosshair is on: its block, distance and name, and the mob or the block's item to preview. */
	private record Target(BlockPos pos, double distance, String name, @Nullable LivingEntity mob, ItemStack icon) {
	}

	private ZoomHud() {
	}

	public static Corner corner() {
		if (corner == null) {
			corner = Corner.of(ShaderPackSettings.interfaceSetting("zoom", "corner", new JsonPrimitive(Corner.TOP_RIGHT.id)).getAsString());
		}
		return corner;
	}

	public static void setCorner(String id) {
		corner = Corner.of(id);
		ShaderPackSettings.setInterfaceSetting("zoom", "corner", new JsonPrimitive(corner.id));
	}

	/** Whether the panel with the coordinates, distance and direction shows. */
	public static boolean showCoordinates() {
		if (showCoordinates == null) {
			showCoordinates = ShaderPackSettings.interfaceSetting("zoom", "coordinates", new JsonPrimitive(true)).getAsBoolean();
		}
		return showCoordinates;
	}

	public static void setShowCoordinates(boolean on) {
		showCoordinates = on;
		ShaderPackSettings.setInterfaceSetting("zoom", "coordinates", new JsonPrimitive(on));
	}

	/** Whether the panel with the target's preview and name shows. */
	public static boolean showTarget() {
		if (showTarget == null) {
			showTarget = ShaderPackSettings.interfaceSetting("zoom", "target", new JsonPrimitive(true)).getAsBoolean();
		}
		return showTarget;
	}

	public static void setShowTarget(boolean on) {
		showTarget = on;
		ShaderPackSettings.setInterfaceSetting("zoom", "target", new JsonPrimitive(on));
	}

	/**
	 * Draws the panels in their corner; in the top-right one their top is {@code topRight} (below whatever else Aetherium
	 * shows there) when that is not 0, elsewhere just off the screen edge.
	 */
	public static void draw(GuiGraphicsExtractor g, int topRight) {
		Minecraft mc = Minecraft.getInstance();
		long now = Util.getMillis();
		boolean on = Zoom.zooming();
		if (on != visible) {
			visible = on;
			since = now;
		}
		float t = Math.min(1.0f, (now - since) / (float) FADE_MS);
		float alpha = visible ? t : 1.0f - t;
		Entity camera = mc.getCameraEntity();
		if (alpha <= 0.0f || mc.level == null || camera == null || mc.gui.hud.isHidden()) {
			return;
		}
		boolean readout = showCoordinates();
		boolean identify = showTarget();
		if (!readout && !identify) {
			return;
		}
		Font font = mc.font;
		Corner where = corner();
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		Target target = target(mc, camera, partial);
		if (!readout && target == null) {
			return;
		}
		int sw = mc.getWindow().getGuiScaledWidth();
		int sh = mc.getWindow().getGuiScaledHeight();
		// With nothing in range the readout is a single row.
		int height = readout && target == null ? 12 : HEIGHT;
		int y0 = where.bottom() ? sh - GAP - height : where == Corner.TOP_RIGHT && topRight > 0 ? topRight : GAP;
		int y1 = y0 + height;
		// The next panel's outer edge, moving in from the side.
		int edge = where.right() ? sw - GAP : GAP;

		if (readout) {
			// Coordinates over distance and the direction faced. Both rows span the same width, so the left and right edges
			// line up: X starts and Z ends at the padding, Y sits evenly between, and the distance and direction take the ends
			// of the row below. The direction's slot fits the longest name, so turning does not resize the panel.
			String direction = DIRECTIONS[Math.floorMod(Math.round(camera.getViewYRot(partial) / 45.0f), 8)];
			int directionSlot = 0;
			for (String d : DIRECTIONS) {
				directionSlot = Math.max(directionSlot, font.width(d));
			}
			String distance;
			if (target == null) {
				distance = "Nothing in range";
			} else {
				long blocks = Math.round(target.distance());
				distance = blocks + (blocks == 1 ? " block" : " blocks");
			}
			float inner = (font.width(distance) + 12 + directionSlot) * SMALL;
			float[] axes = null;
			if (target != null) {
				axes = new float[] {axisWidth(font, "X", target.pos().getX()), axisWidth(font, "Y", target.pos().getY()),
						axisWidth(font, "Z", target.pos().getZ())};
				inner = Math.max(inner, axes[0] + axes[1] + axes[2] + 2 * 5);
			}
			int width = (int) Math.ceil(inner) + 2 * PAD;
			int x0 = where.right() ? edge - width : edge;
			int x1 = x0 + width;
			panel(g, x0, y0, x1, y1, alpha);
			// Rows centred on their capitals (the font's lines have room for descenders underneath).
			float y = target == null ? y0 + (height - CAP * SMALL) / 2.0f : y0 + (height - (CAP * LARGE + ROW_GAP + CAP * SMALL)) / 2.0f;
			if (axes != null) {
				// X, Y and Z: labels muted, values bright.
				float spacing = (x1 - x0 - 2 * PAD - axes[0] - axes[1] - axes[2]) / 2.0f;
				float x = x0 + PAD;
				axis(g, font, "X", target.pos().getX(), x, y, alpha);
				x += axes[0] + spacing;
				axis(g, font, "Y", target.pos().getY(), x, y, alpha);
				x = x1 - PAD - axes[2];
				axis(g, font, "Z", target.pos().getZ(), x, y, alpha);
				y += CAP * LARGE + ROW_GAP;
			}
			text(g, font, distance, x0 + PAD, y, SMALL, faded(Theme.TEXT_DIM, alpha));
			text(g, font, direction, x1 - PAD - font.width(direction) * SMALL, y, SMALL, faded(Theme.ACCENT_TEXT, alpha));
			edge = where.right() ? x0 - GAP : x1 + GAP;
		}

		// What it is, in a panel of its own (beside the readout, towards the middle): the preview (no backdrop) and the name.
		if (identify && target != null) {
			String name = Theme.fit(font, target.name(), 160);
			int ty0 = y0 + (height - TILE) / 2;
			int side = ty0 - y0;
			int iw = side + TILE + 4 + (int) Math.ceil(font.width(name) * LARGE) + PAD;
			int ix0 = where.right() ? edge - iw : edge;
			int ix1 = ix0 + iw;
			panel(g, ix0, y0, ix1, y1, alpha);
			// Models cannot be drawn half transparent: they show once the panel is mostly faded in.
			if (alpha > 0.5f) {
				preview(g, target, ix0 + side, ty0);
			}
			text(g, font, name, ix0 + side + TILE + 4, y0 + (height - CAP * LARGE) / 2.0f, LARGE, faded(Theme.TEXT, alpha));
		}
	}

	/**
	 * The mob or block under the crosshair, as far as the render distance reaches (the smaller of the player's and the
	 * server's; nothing past it is loaded); a mob wins when it is nearer than the block.
	 */
	private static @Nullable Target target(Minecraft mc, Entity camera, float partial) {
		Vec3 eye = camera.getEyePosition(partial);
		Vec3 look = camera.getViewVector(partial);
		double range = mc.options.getEffectiveRenderDistance() * 16.0;
		HitResult blockHit = camera.pick(range, partial, false);
		double reach = blockHit.getType() == HitResult.Type.MISS ? range : eye.distanceTo(blockHit.getLocation());
		Vec3 end = eye.add(look.scale(reach));
		AABB along = camera.getBoundingBox().expandTowards(look.scale(reach)).inflate(1.0);
		EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(camera, eye, end, along,
				e -> e instanceof Mob && !e.isSpectator() && e.isPickable(), reach * reach);
		if (entityHit != null) {
			Mob mob = (Mob) entityHit.getEntity();
			return new Target(mob.blockPosition(), eye.distanceTo(entityHit.getLocation()), mob.getDisplayName().getString(), mob, ItemStack.EMPTY);
		}
		if (blockHit.getType() == HitResult.Type.BLOCK && blockHit instanceof BlockHitResult block) {
			BlockPos pos = block.getBlockPos();
			BlockState state = mc.level.getBlockState(pos);
			// The item pick-block gives (tall seagrass shows seagrass, a wall torch the torch); a fluid's own block has none, so
			// water and lava show their bucket.
			ItemStack icon = state.getCloneItemStack(mc.level, pos, false);
			if (icon.isEmpty() && state.getBlock() instanceof LiquidBlock) {
				icon = new ItemStack(state.getFluidState().getType().getBucket());
			}
			return new Target(pos, eye.distanceTo(block.getLocation()), state.getBlock().getName().getString(), null, icon);
		}
		return null;
	}

	/**
	 * The target in the tile at ({@code x}, {@code y}): a mob as itself, live, turned three-quarters and scaled so its
	 * box fits with room around it; a block as its item model, scaled to the tile.
	 */
	private static void preview(GuiGraphicsExtractor g, Target target, int x, int y) {
		LivingEntity mob = target.mob();
		if (mob != null) {
			// Fitted to about 60% of the tile, so a turned model (wider than its box) and its limbs keep clear of the edges.
			float size = Math.max(mob.getBbHeight(), mob.getBbWidth());
			int scale = Math.max(1, Math.round(TILE * 0.6f / Math.max(0.4f, size)));
			// The "mouse" it looks towards: up and to the left of the tile, for a three-quarter view.
			InventoryScreen.extractEntityInInventoryFollowsMouse(g, x, y, x + TILE, y + TILE, scale, 0.0625f, x - 20, y - 6, mob);
			return;
		}
		if (!target.icon().isEmpty()) {
			g.pose().pushMatrix();
			g.pose().translate(x, y);
			g.pose().scale(TILE / 16.0f, TILE / 16.0f);
			g.fakeItem(target.icon(), 0, 0);
			g.pose().popMatrix();
		}
	}

	/** The overlays' glass: lighter than the menus', so the world shows through. */
	private static void panel(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, float alpha) {
		Theme.round(g, x0, y0, x1, y1, faded(Theme.OVERLAY, alpha));
		Theme.roundOutline(g, x0, y0, x1, y1, faded(Theme.BORDER, alpha));
	}

	/** {@code label} muted, then {@code value}, in the large type; returns the x after the value. */
	private static float axis(GuiGraphicsExtractor g, Font font, String label, int value, float x, float y, float alpha) {
		text(g, font, label, x, y, LARGE, faded(Theme.TEXT_MUTED, alpha));
		x += (font.width(label) + 3) * LARGE;
		String number = String.valueOf(value);
		text(g, font, number, x, y, LARGE, faded(Theme.TEXT, alpha));
		return x + font.width(number) * LARGE;
	}

	/** How wide {@link #axis} draws {@code label} and {@code value}. */
	private static float axisWidth(Font font, String label, int value) {
		return (font.width(label) + 3 + font.width(String.valueOf(value))) * LARGE;
	}

	/** {@code s} at ({@code x}, {@code y}), scaled by {@code scale}. */
	private static void text(GuiGraphicsExtractor g, Font font, String s, float x, float y, float scale, int color) {
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(scale, scale);
		g.text(font, s, 0, 0, color, false);
		g.pose().popMatrix();
	}

	private static int faded(int color, float alpha) {
		return Math.round((color >>> 24) * alpha) << 24 | color & 0x00FFFFFF;
	}
}
