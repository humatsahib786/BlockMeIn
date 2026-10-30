package com.blockmein;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.minecraft.block.AbstractCauldronBlock;
import net.minecraft.block.AbstractRedstoneGateBlock;
import net.minecraft.block.AnvilBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.CakeBlock;
import net.minecraft.block.CandleBlock;
import net.minecraft.block.CandleCakeBlock;
import net.minecraft.block.ComposterBlock;
import net.minecraft.block.CraftingTableBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.DragonEggBlock;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.FlowerPotBlock;
import net.minecraft.block.GrindstoneBlock;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.LoomBlock;
import net.minecraft.block.NoteBlock;
import net.minecraft.block.RedstoneWireBlock;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.block.StonecutterBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.lwjgl.glfw.GLFW;

/**
 * Block Me In.
 *
 * PRESS the key once (default G, rebindable in Options > Controls > Key Binds) to turn it on,
 * press it again to turn it off. While it's on:
 *  1. it walks you to the middle of the block you're standing in, using your normal movement
 *     keys (with braking so it doesn't overshoot),
 *  2. turns smoothly toward each spot and right-clicks exactly what your crosshair is on:
 *     a face of a real block you can actually see. Never thin air, never through walls,
 *  3. builds the 4 walls at feet + head height, then jumps to put a block on top of a wall
 *     and places the roof against it (the way a player does it from inside a 1x1),
 *  4. then GUARDS the box: if SOMEONE ELSE breaks one of your blocks, it re-places it the
 *     same way after a short reaction delay. Blocks YOU break yourself are left open.
 *
 * While it's just guarding, you have full control (movement keys, hotbar, mouse). It only
 * takes over for the second or so it needs to repair something, then gives everything back.
 * If you walk out of the box yourself it switches off. If you get knocked out, it walks you
 * back in. If you get knocked back it re-centres. Crouching is fine.
 *
 * Blocks: END STONE first (anywhere in your inventory), otherwise any other full block.
 */
public class BlockMeInClient implements ClientModInitializer {
	public static final String MOD_ID = "blockmein";

	// ---------------- Tuning (change these to make it faster/slower) ----------------

	/** Ticks spent turning to each block (20 ticks = 1 second). 2 = about 10 blocks/second. */
	private static final int TURN_TICKS = 2;
	/** Reaction delay before re-placing a block someone broke (ticks). */
	private static final int REACT_TICKS = 2;
	/** How close to the middle of the block you must be before it starts placing. */
	private static final double CENTER_TOLERANCE = 0.12;
	/** While placing, how far you can be pushed before it stops and walks back. */
	private static final double DRIFT_TOLERANCE = 0.2;
	/** You count as "standing still" below this speed (blocks/tick). */
	private static final double STILL_SPEED = 0.03;
	/** Ignore position errors smaller than this when steering (prevents jitter). */
	private static final double MOVE_DEADZONE = 0.03;
	/** How far ahead it predicts your sliding when deciding to brake. */
	private static final double BRAKE_FACTOR = 1.2;
	/** Max distance to the face it clicks (normal survival reach is 4.5). */
	private static final double REACH = 4.5;
	/** How many jumps it tries for the roof before giving up (until you turn it off and on). */
	private static final int MAX_JUMP_TRIES = 3;
	/** After getting hit, for how many ticks movement counts as knockback (not you walking). */
	private static final int HIT_MEMORY_TICKS = 20;
	/** How long it tries to walk you back in after being knocked out of the box. */
	private static final int MAX_RETURN_TICKS = 60;

	// ----------------------------------------------------------------------------------

	private static final KeyBinding.Category CATEGORY =
			KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));

	/** Where we look for something to click on: below first, then the sides, then above. */
	private static final Direction[] SUPPORT_ORDER = {
			Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP
	};
	/** Horizontal directions in the order yaw increases (turning right). */
	private static final Direction[] TURN_ORDER = {
			Direction.SOUTH, Direction.WEST, Direction.NORTH, Direction.EAST
	};

	private static final int HOTBAR_SIZE = 9;
	private static final int INVENTORY_SIZE = 36; // hotbar (0-8) + main inventory (9-35)

	private enum Phase { CENTERING, PLACING, JUMPING }

	/** One planned click: fill {@code target} by clicking {@code point} on {@code face} of {@code support}. */
	private record Plan(BlockPos target, BlockPos support, Direction face, Vec3d point) {}

	private static KeyBinding surroundKey;

	// ---- state while it's switched on ----
	private static boolean active;
	private static BlockPos anchor;          // the block you're being boxed into
	/** Blocks YOU broke while it was on. Never re-placed (until something fills them again). */
	private static final Set<BlockPos> ownBroken = new HashSet<>();
	private static int hitTicks;             // > 0 = you were hit recently (movement = knockback)
	private static boolean returning;        // walking back in after being knocked out
	private static int returnTicks;
	private static boolean boxedShown;
	private static boolean noBlocksShown;
	private static boolean firstBatch;
	private static int reactTicks;
	private static int cooldown;             // wait a bit after giving up on an unreachable spot

	// ---- state while it's actually building/repairing ("batch") ----
	private static boolean working;          // we're controlling movement/aim right now
	private static Phase phase;
	private static Plan pending;             // block we're currently turning toward
	private static int turnLeft;             // turning ticks left before we click
	private static int userSlot;             // your hotbar slot before the batch, restored after
	private static int workSlot;
	private static final List<int[]> swaps = new ArrayList<>();
	// roof jump
	private static BlockPos jumpWall;        // head-level wall we jump to place on top of
	private static BlockPos jumpHelper;      // the block that goes on top of it
	private static boolean jumped;
	private static int jumpTicks;
	private static int jumpTries;

	@Override
	public void onInitializeClient() {
		surroundKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.blockmein.surround",   // name shown in the Controls menu
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_G,            // default key; change it in-game
				CATEGORY));

		ClientTickEvents.END_CLIENT_TICK.register(BlockMeInClient::onTick);

		// Remember blocks YOU break, so they're not put back.
		ClientPlayerBlockBreakEvents.AFTER.register((world, player, pos, state) -> {
			if (active) {
				ownBroken.add(pos.toImmutable());
			}
		});
	}

	// ================================ main loop ================================

	private static void onTick(MinecraftClient client) {
		boolean pressed = false;
		while (surroundKey.wasPressed()) {
			pressed = true;
		}
		ClientPlayerEntity player = client.player;
		if (player == null || client.world == null || client.interactionManager == null) {
			if (active) {
				stop(client, null);
			}
			return;
		}
		if (pressed) {
			if (active) {
				stop(client, "blockmein.message.off");
				return;
			}
			start(player);
			message(client, "blockmein.message.on");
		}
		if (!active) {
			return;
		}
		if (!player.isAlive() || player.isSpectator()) {
			stop(client, "blockmein.message.off");
			return;
		}
		if (client.currentScreen != null) {
			pending = null; // paused while a menu/chat is open
			return;
		}
		tickActive(client, player, client.world);
	}

	private static void start(ClientPlayerEntity player) {
		active = true;
		anchor = player.getBlockPos();
		ownBroken.clear();
		hitTicks = 0;
		returning = false;
		returnTicks = 0;
		boxedShown = false;
		noBlocksShown = false;
		firstBatch = true;
		reactTicks = 0;
		cooldown = 0;
		working = false;
		phase = Phase.CENTERING;
		pending = null;
		swaps.clear();
		workSlot = -1;
		jumpTries = 0;
		resetJump();
	}

	private static void stop(MinecraftClient client, String messageKey) {
		if (working) {
			endBatch(client);
		}
		active = false;
		pending = null;
		ownBroken.clear();
		resetJump();
		if (messageKey != null) {
			message(client, messageKey);
		}
	}

	/** Take control for a build/repair. */
	private static void beginBatch(ClientPlayerEntity player) {
		working = true;
		userSlot = player.getInventory().getSelectedSlot();
		workSlot = -1;
		swaps.clear();
		phase = Phase.CENTERING;
		pending = null;
	}

	/** Done for now: give back your hotbar slot, inventory layout and movement keys. */
	private static void endBatch(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (player != null && client.interactionManager != null) {
			for (int i = swaps.size() - 1; i >= 0; i--) {   // undo inventory swaps, newest first
				int[] sw = swaps.get(i);
				swap(client, player, sw[0], sw[1]);
			}
			player.getInventory().setSelectedSlot(userSlot);
		}
		swaps.clear();
		workSlot = -1;
		pending = null;
		phase = Phase.CENTERING;
		resetJump();
		working = false;
		// Hand the movement keys back: re-read which keys you're physically holding.
		KeyBinding.updatePressedStates();
	}

	private static void reanchor(ClientPlayerEntity player) {
		anchor = player.getBlockPos();
		pending = null;
		phase = Phase.CENTERING;
		jumpTries = 0;
		resetJump();
		boxedShown = false;
	}

	private static void tickActive(MinecraftClient client, ClientPlayerEntity player, ClientWorld world) {
		// Forget "you broke this" once something fills the spot again.
		ownBroken.removeIf(pos -> !world.getBlockState(pos).isReplaceable());
		if (player.hurtTime > 0) {
			hitTicks = HIT_MEMORY_TICKS;
		} else if (hitTicks > 0) {
			hitTicks--;
		}
		if (cooldown > 0) {
			cooldown--;
		}

		// ---- Are you still in the box? ----
		boolean sameLevel = !player.isOnGround() || MathHelper.floor(player.getY() + 1.0E-4) == anchor.getY();
		if (!sameLevel) {
			if (ownBroken.contains(anchor.down())) {
				stop(client, "blockmein.message.left"); // you dug down yourself
				return;
			}
			reanchor(player); // the floor went / you got moved up or down: box up again here
			return;
		}
		double centerX = anchor.getX() + 0.5;
		double centerZ = anchor.getZ() + 0.5;
		double offX = player.getX() - centerX;
		double offZ = player.getZ() - centerZ;
		boolean inColumn = Math.abs(offX) <= 0.5 && Math.abs(offZ) <= 0.5;
		if (!inColumn) {
			if (hitTicks > 0 || returning) {
				// Knocked out of the box: walk back in.
				if (!returning) {
					returning = true;
					returnTicks = 0;
				}
				if (++returnTicks > MAX_RETURN_TICKS) {
					returning = false;
					reanchor(player); // couldn't get back in: box up where you are
					return;
				}
				if (!working) {
					beginBatch(player);
				}
				phase = Phase.CENTERING;
				pending = null;
				resetJump();
				steerTo(client, player, centerX, centerZ);
				return;
			}
			stop(client, "blockmein.message.left"); // you walked out yourself
			return;
		}
		returning = false;
		returnTicks = 0;

		if (phase == Phase.JUMPING && working) {
			tickJump(client, player, world);
			return;
		}

		// ---- Anything to do? ----
		boolean knockedOffCenter = hitTicks > 0
				&& (Math.abs(offX) > DRIFT_TOLERANCE || Math.abs(offZ) > DRIFT_TOLERANCE);
		boolean gaps = hasGaps(world, player);
		if (!gaps && !knockedOffCenter) {
			if (working) {
				endBatch(client);
			}
			reactTicks = 0;
			if (!boxedShown && isFullyBoxed(world, player)) {
				message(client, "blockmein.message.boxed");
				boxedShown = true;
			}
			return; // just guarding: you have full control
		}

		if (!working) {
			if (cooldown > 0) {
				return;
			}
			// Short human-like reaction delay before repairing (not for the first build).
			if (!firstBatch && reactTicks < REACT_TICKS) {
				reactTicks++;
				return;
			}
			reactTicks = 0;
			firstBatch = false;
			beginBatch(player);
		}
		boxedShown = false;

		// ---- Get to the middle and stand still ----
		double speed = Math.sqrt(player.getVelocity().x * player.getVelocity().x
				+ player.getVelocity().z * player.getVelocity().z);
		double tolerance = (phase == Phase.PLACING) ? DRIFT_TOLERANCE : CENTER_TOLERANCE;
		boolean needsCentering = Math.abs(offX) > tolerance || Math.abs(offZ) > tolerance
				|| !player.isOnGround()
				|| (phase == Phase.CENTERING && speed > STILL_SPEED);
		if (needsCentering) {
			phase = Phase.CENTERING;
			pending = null;
			steerTo(client, player, centerX, centerZ);
			return;
		}
		phase = Phase.PLACING;
		setMovement(client, false, false, false, false, false); // stand still while building

		// ---- Finish the block we were turning toward ----
		if (pending != null) {
			if (turnLeft > 0) {
				turnTowards(player, pending.point());
				return;
			}
			clickIfAimed(client, player, world, pending);
			pending = null;
		}

		// ---- Next block ----
		Plan next = planNext(world, player);
		if (next == null) {
			if (!hasGaps(world, player)) {
				return; // all done; the batch ends next tick
			}
			if (startRoofJumpIfNeeded(world, player)) {
				return;
			}
			// Nothing reachable right now: let go and try again in a second.
			endBatch(client);
			cooldown = 20;
			return;
		}

		int slot = selectBlock(client, player, world, next.target());
		if (slot == -1) {
			if (!noBlocksShown) {
				message(client, "blockmein.message.no_blocks");
				noBlocksShown = true;
			}
			endBatch(client);
			cooldown = 40;
			return;
		}
		noBlocksShown = false;
		player.getInventory().setSelectedSlot(slot);

		pending = next;
		turnLeft = TURN_TICKS;
		turnTowards(player, next.point());
	}

	/** Any wall/roof spot that's empty, not inside you, and wasn't opened by you. */
	private static boolean hasGaps(ClientWorld world, ClientPlayerEntity player) {
		Box box = player.getBoundingBox();
		int minY = MathHelper.floor(box.minY + 1.0E-4);
		int maxY = MathHelper.floor(box.maxY - 1.0E-4);
		for (int y = minY; y <= maxY; y++) {
			for (Direction side : TURN_ORDER) {
				if (isGap(world, player, new BlockPos(anchor.getX(), y, anchor.getZ()).offset(side))) {
					return true;
				}
			}
		}
		return isGap(world, player, new BlockPos(anchor.getX(), maxY + 1, anchor.getZ()));
	}

	private static boolean isGap(ClientWorld world, ClientPlayerEntity player, BlockPos pos) {
		return world.getBlockState(pos).isReplaceable()
				&& !ownBroken.contains(pos)
				&& !new Box(pos).intersects(player.getBoundingBox());
	}

	// ================================ placing ================================

	/**
	 * Next block to place: walls side by side in the order you'd turn (starting with the
	 * side you're facing), feet then head on each side, then the roof. Only spots that are
	 * empty, not inside you, and have a visible face next to them to click on.
	 */
	private static Plan planNext(ClientWorld world, ClientPlayerEntity player) {
		Box box = player.getBoundingBox();
		int x = anchor.getX();
		int z = anchor.getZ();
		int minY = MathHelper.floor(box.minY + 1.0E-4);
		int maxY = MathHelper.floor(box.maxY - 1.0E-4);
		Vec3d eye = player.getEyePos();

		for (Direction side : sidesInTurnOrder(player.getYaw())) {
			for (int y = minY; y <= maxY; y++) {
				Plan plan = planFor(world, player, eye, new BlockPos(x, y, z).offset(side));
				if (plan != null) {
					return plan;
				}
			}
		}
		return planFor(world, player, eye, new BlockPos(x, maxY + 1, z));
	}

	private static Plan planFor(ClientWorld world, ClientPlayerEntity player, Vec3d eye, BlockPos target) {
		if (!world.getBlockState(target).isReplaceable()) {
			return null; // already has a block -> skip
		}
		if (ownBroken.contains(target)) {
			return null; // you broke this one yourself -> leave it open
		}
		if (new Box(target).intersects(player.getBoundingBox())) {
			return null; // would be inside you
		}
		for (Direction dir : SUPPORT_ORDER) {
			BlockPos support = target.offset(dir);
			BlockState state = world.getBlockState(support);
			if (state.isReplaceable() || !isSafeToClick(state)) {
				continue;
			}
			Direction face = dir.getOpposite();
			Vec3d point = visiblePoint(world, player, eye, support, face);
			if (point != null) {
				return new Plan(target, support, face, point);
			}
		}
		return null;
	}

	/** A point on the face that you can actually see from your eyes, within reach, or null. */
	private static Vec3d visiblePoint(ClientWorld world, ClientPlayerEntity player, Vec3d eye,
			BlockPos support, Direction face) {
		Vec3d faceCenter = Vec3d.ofCenter(support).add(
				face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
		double[][] offsets = {{0, 0}, {0.3, 0}, {-0.3, 0}, {0, 0.3}, {0, -0.3}};
		for (double[] o : offsets) {
			Vec3d point = switch (face.getAxis()) {
				case X -> faceCenter.add(0, o[0], o[1]);
				case Y -> faceCenter.add(o[0], 0, o[1]);
				case Z -> faceCenter.add(o[0], o[1], 0);
			};
			if (eye.distanceTo(point) > REACH - 0.2) {
				continue;
			}
			// Aim a hair past the surface, then check the first thing the ray hits is this face.
			Vec3d inside = point.add(face.getOffsetX() * -0.05, face.getOffsetY() * -0.05, face.getOffsetZ() * -0.05);
			BlockHitResult hit = world.raycast(new RaycastContext(
					eye, inside, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player));
			if (hit != null && hit.getType() == HitResult.Type.BLOCK
					&& hit.getBlockPos().equals(support) && hit.getSide() == face) {
				return point;
			}
		}
		return null;
	}

	/** Right-click whatever the crosshair is on, but only if it's the face we planned. */
	private static void clickIfAimed(MinecraftClient client, ClientPlayerEntity player, ClientWorld world, Plan plan) {
		if (!world.getBlockState(plan.target()).isReplaceable()) {
			return; // got filled meanwhile
		}
		BlockHitResult hit = crosshair(world, player);
		if (hit == null || hit.getType() != HitResult.Type.BLOCK
				|| !hit.getBlockPos().equals(plan.support()) || hit.getSide() != plan.face()) {
			return; // aim got disturbed (e.g. you got hit) -> it gets re-planned next tick
		}
		int slot = selectBlock(client, player, world, plan.target());
		if (slot == -1) {
			return;
		}
		player.getInventory().setSelectedSlot(slot);
		ActionResult result = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
		if (result.isAccepted()) {
			player.swingHand(Hand.MAIN_HAND);
		}
	}

	private static boolean isFullyBoxed(ClientWorld world, ClientPlayerEntity player) {
		Box box = player.getBoundingBox();
		int minY = MathHelper.floor(box.minY + 1.0E-4);
		int maxY = MathHelper.floor(box.maxY - 1.0E-4);
		for (int y = minY; y <= maxY; y++) {
			for (Direction side : TURN_ORDER) {
				if (world.getBlockState(new BlockPos(anchor.getX(), y, anchor.getZ()).offset(side)).isReplaceable()) {
					return false;
				}
			}
		}
		return !world.getBlockState(new BlockPos(anchor.getX(), maxY + 1, anchor.getZ())).isReplaceable();
	}

	// ================================ roof jump ================================

	/**
	 * The block above your head usually has nothing next to it you can see. So: jump, and
	 * at the top of the jump put a block on top of one of your head-level walls. After landing,
	 * the roof gets placed against the side of that block (normal planning does that).
	 */
	private static boolean startRoofJumpIfNeeded(ClientWorld world, ClientPlayerEntity player) {
		if (jumpTries >= MAX_JUMP_TRIES) {
			return false;
		}
		Box box = player.getBoundingBox();
		int maxY = MathHelper.floor(box.maxY - 1.0E-4);
		BlockPos head = new BlockPos(anchor.getX(), maxY, anchor.getZ());
		BlockPos roof = head.up();
		if (!world.getBlockState(roof).isReplaceable() || ownBroken.contains(roof)) {
			return false;
		}
		for (Direction side : sidesInTurnOrder(player.getYaw())) {
			BlockPos wall = head.offset(side);
			BlockPos helper = wall.up();
			BlockState wallState = world.getBlockState(wall);
			if (!wallState.isReplaceable() && isSafeToClick(wallState)
					&& world.getBlockState(helper).isReplaceable() && !ownBroken.contains(helper)) {
				phase = Phase.JUMPING;
				jumpWall = wall;
				jumpHelper = helper;
				jumped = false;
				jumpTicks = 0;
				pending = null;
				turnLeft = TURN_TICKS;
				return true;
			}
		}
		return false;
	}

	private static void tickJump(MinecraftClient client, ClientPlayerEntity player, ClientWorld world) {
		jumpTicks++;
		if (!world.getBlockState(jumpHelper).isReplaceable()) {
			finishJump(); // helper is there -> roof can be placed now
			return;
		}
		if (jumpTicks > 30) {
			jumpTries++;
			finishJump();
			return;
		}
		Vec3d point = Vec3d.ofCenter(jumpWall).add(0, 0.5, 0); // top face of the wall

		if (!jumped) {
			setMovement(client, false, false, false, false, false);
			// Get the block in hand while still standing, not mid-air.
			int slot = selectBlock(client, player, world, jumpHelper);
			if (slot == -1) {
				jumpTries = MAX_JUMP_TRIES;
				finishJump();
				return;
			}
			player.getInventory().setSelectedSlot(slot);
			if (turnLeft > 0) {
				turnTowards(player, point); // look up at the wall's top edge first
				return;
			}
			if (player.isOnGround()) {
				setMovement(client, false, false, false, false, true); // jump next tick
				jumped = true;
			}
			return;
		}

		setMovement(client, false, false, false, false, false);
		// In the air: if the crosshair is on top of the wall now, place the helper block.
		BlockHitResult hit = crosshair(world, player);
		if (hit != null && hit.getType() == HitResult.Type.BLOCK
				&& hit.getBlockPos().equals(jumpWall) && hit.getSide() == Direction.UP) {
			int slot = selectBlock(client, player, world, jumpHelper);
			if (slot != -1) {
				player.getInventory().setSelectedSlot(slot);
				ActionResult result = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
				if (result.isAccepted()) {
					player.swingHand(Hand.MAIN_HAND);
					return;
				}
			}
		}
		// Keep the crosshair on that face while rising/falling.
		turnLeft = 1;
		turnTowards(player, point);

		if (player.isOnGround() && jumpTicks > 4) {
			jumpTries++; // landed without getting it, try again
			if (jumpTries >= MAX_JUMP_TRIES) {
				finishJump();
			} else {
				jumped = false;
				turnLeft = 1;
			}
		}
	}

	private static void finishJump() {
		phase = Phase.PLACING;
		pending = null;
		resetJump();
	}

	private static void resetJump() {
		jumpWall = null;
		jumpHelper = null;
		jumped = false;
		jumpTicks = 0;
	}

	// ================================ looking ================================

	/** Turn part of the way (or the rest of the way) toward a point, like a quick mouse flick. */
	private static void turnTowards(ClientPlayerEntity player, Vec3d point) {
		Vec3d eye = player.getEyePos();
		double dx = point.x - eye.x;
		double dy = point.y - eye.y;
		double dz = point.z - eye.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
		float targetPitch = MathHelper.clamp((float) -Math.toDegrees(Math.atan2(dy, horizontal)), -90.0F, 90.0F);

		float fraction = 1.0F / Math.max(1, turnLeft);
		float yaw = player.getYaw();
		float pitch = player.getPitch();
		player.setYaw(yaw + MathHelper.wrapDegrees(targetYaw - yaw) * fraction);
		player.setPitch(pitch + (targetPitch - pitch) * fraction);
		if (turnLeft > 0) {
			turnLeft--;
		}
	}

	/** What the crosshair is on right now (blocks only), same as the game's own targeting. */
	private static BlockHitResult crosshair(ClientWorld world, ClientPlayerEntity player) {
		Vec3d eye = player.getEyePos();
		double yaw = Math.toRadians(player.getYaw());
		double pitch = Math.toRadians(player.getPitch());
		Vec3d dir = new Vec3d(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
		Vec3d end = eye.add(dir.multiply(REACH));
		return world.raycast(new RaycastContext(
				eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player));
	}

	/** The 4 sides, starting with the one you're facing, then turning right. */
	private static Direction[] sidesInTurnOrder(float yaw) {
		int start = Math.floorMod(Math.round(yaw / 90.0F), 4);
		Direction[] out = new Direction[4];
		for (int i = 0; i < 4; i++) {
			out[i] = TURN_ORDER[(start + i) % 4];
		}
		return out;
	}

	// ================================ moving ================================

	/**
	 * Walk toward the middle of the block by pressing your movement keys, predicting how
	 * far you'll slide so it lets go (or counter-steers) in time instead of overshooting.
	 */
	private static void steerTo(MinecraftClient client, ClientPlayerEntity player, double x, double z) {
		Vec3d v = player.getVelocity();
		double errX = x - (player.getX() + v.x * BRAKE_FACTOR);
		double errZ = z - (player.getZ() + v.z * BRAKE_FACTOR);
		double yaw = Math.toRadians(player.getYaw());
		double forward = -errX * Math.sin(yaw) + errZ * Math.cos(yaw);
		double left = errX * Math.cos(yaw) + errZ * Math.sin(yaw);
		setMovement(client,
				forward > MOVE_DEADZONE, forward < -MOVE_DEADZONE,
				left > MOVE_DEADZONE, left < -MOVE_DEADZONE,
				false);
	}

	private static void setMovement(MinecraftClient client, boolean forward, boolean back,
			boolean left, boolean right, boolean jump) {
		GameOptions o = client.options;
		o.forwardKey.setPressed(forward);
		o.backKey.setPressed(back);
		o.leftKey.setPressed(left);
		o.rightKey.setPressed(right);
		o.jumpKey.setPressed(jump);
	}

	// ================================ blocks & inventory ================================

	/**
	 * Picks the block to use and makes sure it's in the hotbar. Returns the hotbar slot,
	 * or -1 if there's nothing usable anywhere in the inventory.
	 */
	private static int selectBlock(MinecraftClient client, ClientPlayerEntity player, ClientWorld world, BlockPos pos) {
		PlayerInventory inventory = player.getInventory();
		int index = findBestStack(inventory, world, pos);
		if (index == -1) {
			return -1;
		}
		if (index < HOTBAR_SIZE) {
			return index;
		}
		// In the main inventory: swap it into a hotbar slot (same as pressing a number key
		// over it in the inventory). Undone as soon as the build/repair is finished.
		int hotbarSlot = chooseWorkSlot(inventory);
		swap(client, player, index, hotbarSlot);
		swaps.add(new int[] {index, hotbarSlot});
		return hotbarSlot;
	}

	/**
	 * Inventory index (0-8 hotbar, 9-35 main inventory) of the best stack to build with:
	 * END STONE first (held item, then hotbar, then the rest of the inventory), and only if
	 * there's no end stone at all, any other full block in the same order.
	 */
	private static int findBestStack(PlayerInventory inventory, ClientWorld world, BlockPos pos) {
		int selected = inventory.getSelectedSlot();

		if (isEndStone(inventory.getStack(selected))) {
			return selected;
		}
		for (int i = 0; i < INVENTORY_SIZE; i++) {
			if (isEndStone(inventory.getStack(i))) {
				return i;
			}
		}

		if (isGoodWallBlock(inventory.getStack(selected), world, pos)) {
			return selected;
		}
		for (int i = 0; i < INVENTORY_SIZE; i++) {
			if (isGoodWallBlock(inventory.getStack(i), world, pos)) {
				return i;
			}
		}
		return -1;
	}

	private static boolean isEndStone(ItemStack stack) {
		return !stack.isEmpty() && stack.isOf(Items.END_STONE);
	}

	/** Hotbar slot we swap blocks into: an empty one if you have it, otherwise your held slot. */
	private static int chooseWorkSlot(PlayerInventory inventory) {
		if (workSlot == -1) {
			workSlot = userSlot;
			for (int i = 0; i < HOTBAR_SIZE; i++) {
				if (inventory.getStack(i).isEmpty()) {
					workSlot = i;
					break;
				}
			}
		}
		return workSlot;
	}

	/** Same click the game sends when you press a number key over an inventory slot. */
	private static void swap(MinecraftClient client, ClientPlayerEntity player, int inventoryIndex, int hotbarSlot) {
		// In the player's inventory screen, main inventory slots 9-35 have the same numbers.
		client.interactionManager.clickSlot(player.playerScreenHandler.syncId,
				inventoryIndex, hotbarSlot, SlotActionType.SWAP, player);
	}

	/**
	 * A solid, full-size block. Skips sand/gravel/concrete powder/anvils (they'd fall
	 * on you), and chests/shulker boxes/furnaces etc. (so you don't waste them).
	 */
	private static boolean isGoodWallBlock(ItemStack stack, ClientWorld world, BlockPos pos) {
		if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
			return false;
		}
		Block block = blockItem.getBlock();
		if (block instanceof FallingBlock || block instanceof BlockEntityProvider) {
			return false;
		}
		return block.getDefaultState().isFullCube(world, pos);
	}

	/** Never click blocks that would open, toggle or use something. */
	private static boolean isSafeToClick(BlockState state) {
		Block b = state.getBlock();
		return !(b instanceof BlockEntityProvider       // chests, furnaces, signs, beds, shulkers...
				|| b instanceof DoorBlock
				|| b instanceof TrapdoorBlock
				|| b instanceof FenceGateBlock
				|| b instanceof ButtonBlock
				|| b instanceof LeverBlock
				|| b instanceof CraftingTableBlock  // also smithing/cartography/fletching tables
				|| b instanceof LoomBlock
				|| b instanceof StonecutterBlock
				|| b instanceof GrindstoneBlock
				|| b instanceof AnvilBlock
				|| b instanceof NoteBlock
				|| b instanceof RespawnAnchorBlock
				|| b instanceof CakeBlock
				|| b instanceof CandleBlock
				|| b instanceof CandleCakeBlock
				|| b instanceof AbstractCauldronBlock
				|| b instanceof AbstractRedstoneGateBlock
				|| b instanceof RedstoneWireBlock
				|| b instanceof ComposterBlock
				|| b instanceof DragonEggBlock
				|| b instanceof FlowerPotBlock);
	}

	private static void message(MinecraftClient client, String key) {
		client.inGameHud.setOverlayMessage(Text.translatable(key), false);
	}
}
